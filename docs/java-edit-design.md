# Safe Java method-body edits

This design replaces the legacy `methodBodyRange` brace counter only for the future coordinated endpoint. The existing `modify_method_body` and `rename_node` paths remain legacy/uncoordinated until this design and the lease/apply gate are implemented. `rename_node` stays unavailable on coordinated endpoints.

## Evidence and constraints

The current fallback node ID is `method:<qualified-name>:<parameter-types>:<return-type>` (`Analyzer.kt` and `AnalysisSupport.kt`). It has a method name, parent qualified name, parameter type strings, line range, and retained file hash, but it does not have a byte span or a parser-owned declaration handle. The fallback extractor is regex based and can produce an imprecise line range. A safe editor must treat those fields as candidate constraints and reject disagreement rather than repair them heuristically.

The local JDK is Temurin/OpenJDK 21.0.11. Its supported compiler-tree API supplies the required parser primitives without adding a dependency:

- `ToolProvider.getSystemJavaCompiler()` and `JavaCompiler.getTask(...)`
- `JavacTask.parse()` to obtain a `CompilationUnitTree`
- `TreePathScanner` to visit `MethodTree` declarations
- `Trees.instance(task).sourcePositions` to obtain start/end positions
- `CompilationUnitTree.lineMap` for one-based line validation

`SourcePositions` are indices into the compiler input character sequence, not UTF-8 byte offsets. The editor must use the exact retained UTF-8 bytes as its source of truth, decode them strictly once, and build a validated UTF-16-index to UTF-8-byte boundary map before replacing bytes. It must reject a compiler position that falls between a surrogate pair or otherwise is not a map boundary.

## Narrow API

Add a new internal service, separate from the legacy tools:

```kotlin
data class SafeJavaBodyPlanRequest(
    val nodeId: String,
    val snapshotId: String,
    val expectedFileHash: String,
    val newBody: String,
)

data class SafeJavaBodyPlan(
    val file: String,
    val baseHash: String,
    val bodyStartByte: Int,
    val bodyEndByte: Int,
    val replacementUtf8: ByteArray,
)

sealed interface SafeJavaPlanResult {
    data class Planned(val plan: SafeJavaBodyPlan) : SafeJavaPlanResult
    data class Rejected(val code: String, val detail: String) : SafeJavaPlanResult
}
```

The first release supports only `replace_body`: it replaces the bytes strictly between the opening and closing braces of one concrete method body. It does not support constructors, abstract/interface declarations, annotation elements, anchors, line patches, rename, imports, or multi-file edits. The request is bound to the creating session later in the lease layer; this planner itself is pure and makes no filesystem write.

## Selection and planning algorithm

1. Load `Snapshot.sourceFiles[file]` and require its hash equals `expectedFileHash`; reject `stale_source` otherwise. Obtain the target `MethodInfo`; reject non-method nodes and files outside the configured canonical root.
2. Decode the retained bytes with the existing strict UTF-8 decoder. Parse an in-memory `SimpleJavaFileObject` backed by that exact decoded text. Attach a `DiagnosticCollector`; reject `parse_error` if any parser diagnostic has kind `ERROR`. Do not parse the live workspace file.
3. Walk the compilation unit. For each `MethodTree` with a non-null `BlockTree`, derive its enclosing type path and source signature. A candidate must satisfy all of these constraints:
   - exact package plus nesting-qualified enclosing type equals `MethodInfo.parentQualifiedName`;
   - exact simple method name equals `MethodInfo.simpleName`;
   - parameter count and normalized declared parameter type text equal `MethodInfo.parameterTypes`;
   - parser-derived declaration start/end lines equal `MethodInfo.lineRange`.

   If zero candidates remain, return `target_not_found`; if more than one remains, return `ambiguous_target`. A parser/source mismatch is a rejection, never a best-effort selection. The planner must not use the old brace counter, `findBlockEndLine`, or a same-name regex.
4. Get the body block positions with `SourcePositions`: its start must point to `{`, its end must be one character after `}`, and both must be valid UTF-16/UTF-8 boundaries. The editable interior is `[start + 1, end - 1)`. Reject `unsupported_body_span` for `NOPOS`, malformed boundaries, or an empty/missing block. This correctly handles braces in strings, comments, character literals, and text blocks because the compiler owns the block extent.
5. Render `newBody` with a deterministic body-indent policy. Encode only the replacement as UTF-8. Construct the new file as `originalBytes[0, bodyStart) + replacement + originalBytes[bodyEnd, size)`. Keep all untouched byte slices verbatim, including CRLF and non-BMP text. Reparse the constructed bytes with the same parser and reject `replacement_parse_error` on any `ERROR` diagnostic.
6. Return the plan with exact base hash and byte span. The coordinated apply layer later rechecks the raw current hash and lease/fencing generation under its file mutex, stages an atomic same-filesystem replacement, and queues snapshot publication. A successful plan is not an authorization to write.

The normalized parameter comparison is deliberately narrow. It may reject legal source forms involving imports, annotations, varargs spelling, or generic formatting when the existing fallback `MethodInfo` cannot represent them exactly. That is preferable to selecting an overload incorrectly. A later Java adapter can provide parser-native declaration keys and expand this safely.

## Bounded implementation task

Create `SafeJavaEdit.kt` and focused tests in `SafeJavaEditTest.kt`; do not modify the legacy edit planner in the same change.

1. Implement in-memory JDK parsing, strict diagnostics, type-stack traversal, candidate filtering, and UTF-16-to-UTF-8 boundary conversion.
2. Make the planner accept only retained source bytes plus `MethodInfo`; it must have no `Path` or write API.
3. Add byte-splice construction and parse-after-replacement validation.
4. Add a thin adapter only after the planner tests pass. The coordinated daemon must expose this new operation separately and keep all legacy write operations disabled.

Required tests:

- replace a single ordinary method and verify only its body changes;
- overloads with the same name select the intended parameter signature;
- braces inside line/block comments, strings, chars, and text blocks do not move the body boundary;
- CRLF and non-BMP source preserve every untouched byte exactly;
- syntax error in original source, syntax error in replacement, missing body, mismatched line range, and zero/multiple candidates each reject without returning a plan;
- altered base hash rejects before parsing a plan for a current source;
- parser positions at a surrogate boundary convert correctly and a deliberately invalid boundary is rejected by the mapping helper.

The unit tests should instantiate the planner with `RetainedSource.fromBytes`, not a live file or watcher. Lease, atomic replace, idempotency, validation-copy manifests, and external-writer races are separate C tasks and must have their own tests.
