# Parser adapter implementation notes

Prepared during C acceptance; this is a design, not language-support evidence. Begin implementation after C acceptance.

Keep one snapshot publisher. Capture admitted source bytes once, then pass those immutable bytes to language parsers. Parser helpers have no tool, session, lease, edit or repository-discovery authority. Cache results by language, parser version, relative path and file hash, discarding entries absent from the next capture.

Use the pinned TypeScript compiler API for TypeScript/JavaScript definitions and containment, and Python's standard `ast` parser for Python. This avoids introducing a second parser package runtime on the JVM. Ship the helper sources and the pinned TypeScript dependency in the distribution; require Node and Python explicitly. Parse with `createSourceFile`/`forEachChild` and `ast.parse`; never import or execute submitted project code. See Microsoft's [compiler API guide](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API) and Python's [AST position contract](https://docs.python.org/3.15/library/ast.html).

The normalized result carries parser/version, diagnostics, file hash and definitions. A definition has kind, name, qualified name, parent identity, zero-based half-open UTF-8 byte span and one-based line range. IDs include language and relative path; scope-local duplicate ordinals distinguish repeated names. Whitespace outside declarations must not change identity. TypeScript positions are converted from JavaScript string indices to UTF-8 offsets. Python columns already measure UTF-8 bytes and must be combined with original-byte line starts. Do not normalize CRLF before computing spans. Syntax errors yield diagnostics and no invented successful symbol graph.

Add file nodes and containment edges. Mark these edges parser-observed. Do not add guessed calls, import resolution or cross-language runtime relationships. Java relationships keep their actual backend and quality labels. Non-Java edit operations remain disabled. The shared search, source and bounded context tools must reach structural nodes; a renderer-only language feature is insufficient.

Helpers use bounded JSON framing, bounded input/output, cancellation and a wall-clock deadline. Missing runtimes or dependencies produce explicit unavailable-adapter diagnostics and capability omissions. Integration must not call Java parsing on non-Java files or re-run Joern for an unchanged Java manifest when only another language changes.

Verification: definitions/nesting, same-name declarations in one and multiple files, overloads where applicable, CRLF, non-BMP Unicode, multiline strings, comments containing fake declarations, syntax errors, exact source extraction and whitespace-stable IDs. Integration adds create/change/rename/delete watcher coverage and a mixed fixture proving no same-name cross-language edges. Record the actual runtime/parser versions and installation path before claiming support.
