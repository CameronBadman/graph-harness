#!/usr/bin/env python3
from __future__ import annotations

import ast
import hashlib
import json
import sys
from typing import Any


MAX_INPUT_BYTES = 6_400_000
MAX_SOURCE_BYTES = 1_000_000
MAX_DEFINITIONS = 10_000
MAX_DIAGNOSTICS = 100


def emit(value: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")


def failure(message: str, path: str | None = None) -> None:
    emit(
        {
            "parserVersion": f"python-ast/{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
            "path": path,
            "diagnostics": [{"severity": "error", "message": message}],
            "definitions": [],
        }
    )


def main() -> int:
    raw = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
    if len(raw) > MAX_INPUT_BYTES:
        failure("input exceeds maximum size")
        return 0
    try:
        request = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        failure("input must be one UTF-8 JSON object")
        return 0
    if not isinstance(request, dict):
        failure("input must be a JSON object")
        return 0

    path = request.get("path")
    source = request.get("source")
    expected_hash = request.get("hash")
    if not isinstance(path, str) or not isinstance(source, str) or not isinstance(expected_hash, str):
        failure("path, source, and hash must be strings", path if isinstance(path, str) else None)
        return 0
    source_bytes = source.encode("utf-8")
    if len(source_bytes) > MAX_SOURCE_BYTES:
        failure("source exceeds maximum size", path)
        return 0

    line_starts = [0]
    for index, byte in enumerate(source_bytes):
        if byte == 10:
            line_starts.append(index + 1)

    def byte_offset(line: int | None, column: int | None) -> int:
        if line is None or column is None or line < 1 or line > len(line_starts):
            return 0
        return line_starts[line - 1] + column

    try:
        tree = ast.parse(source, filename=path, type_comments=True)
    except SyntaxError as error:
        emit(
            {
                "parserVersion": f"python-ast/{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
                "path": path,
                "hash": expected_hash,
                "actualHash": hashlib.sha256(source_bytes).hexdigest(),
                "diagnostics": [
                    {
                        "severity": "error",
                        "message": error.msg,
                        "startByte": byte_offset(error.lineno, error.offset - 1 if error.offset else 0),
                        "endByte": byte_offset(error.lineno, error.offset if error.offset else 0),
                        "startLine": error.lineno or 1,
                        "endLine": error.end_lineno or error.lineno or 1,
                    }
                ],
                "definitions": [],
            }
        )
        return 0

    definitions: list[dict[str, Any]] = []
    diagnostics: list[dict[str, Any]] = []
    declaration_ordinals: dict[tuple[str | None, str, str], int] = {}
    kinds = (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)

    def visit(node: ast.AST, parent: str | None, parent_identity: str | None) -> None:
        if len(definitions) >= MAX_DEFINITIONS:
            if not diagnostics:
                diagnostics.append({"severity": "warning", "message": "definition limit reached"})
            return
        next_parent, next_parent_identity = parent, parent_identity
        if isinstance(node, kinds):
            qualified_name = f"{parent}.{node.name}" if parent else node.name
            start_byte = byte_offset(node.lineno, node.col_offset)
            end_byte = byte_offset(node.end_lineno, node.end_col_offset)
            kind = "class" if isinstance(node, ast.ClassDef) else "async_function" if isinstance(node, ast.AsyncFunctionDef) else "function"
            ordinal_key = (parent_identity, kind, node.name)
            ordinal = declaration_ordinals.get(ordinal_key, 0)
            declaration_ordinals[ordinal_key] = ordinal + 1
            identity = f"{path}:{parent_identity or 'root'}:{kind}:{qualified_name}:{ordinal}"
            definitions.append(
                {
                    "kind": kind,
                    "name": node.name,
                    "qualifiedName": qualified_name,
                    "parent": parent,
                    "identity": identity,
                    "parentIdentity": parent_identity,
                    "startByte": start_byte,
                    "endByte": end_byte,
                    "startLine": node.lineno,
                    "endLine": node.end_lineno,
                }
            )
            next_parent, next_parent_identity = qualified_name, identity
        for child in ast.iter_child_nodes(node):
            visit(child, next_parent, next_parent_identity)

    visit(tree, None, None)
    emit(
        {
            "parserVersion": f"python-ast/{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
            "path": path,
            "hash": expected_hash,
            "actualHash": hashlib.sha256(source_bytes).hexdigest(),
            "diagnostics": diagnostics[:MAX_DIAGNOSTICS],
            "definitions": definitions,
        }
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
