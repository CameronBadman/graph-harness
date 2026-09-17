#!/usr/bin/env node
"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const MAX_INPUT_BYTES = 6_400_000;
const MAX_SOURCE_BYTES = 1_000_000;
const MAX_DEFINITIONS = 10_000;
const MAX_DIAGNOSTICS = 100;

function emit(value) {
  process.stdout.write(`${JSON.stringify(value)}\n`);
}

function version(ts) {
  return `typescript/${ts ? ts.version : "unavailable"}`;
}

function fail(message, request, ts) {
  emit({
    parserVersion: version(ts),
    path: request && typeof request.path === "string" ? request.path : null,
    diagnostics: [{ severity: "error", message }],
    definitions: [],
  });
}

function loadRequest() {
  const chunks = [];
  let size = 0;
  const buffer = Buffer.allocUnsafe(64 * 1024);
  for (;;) {
    const count = fs.readSync(0, buffer, 0, buffer.length, null);
    if (count === 0) break;
    size += count;
    if (size > MAX_INPUT_BYTES) throw new Error("input exceeds maximum size");
    chunks.push(Buffer.from(buffer.subarray(0, count)));
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function main() {
  let request;
  try {
    request = loadRequest();
  } catch (error) {
    fail(error instanceof Error ? error.message : "input must be one UTF-8 JSON object");
    return;
  }
  if (!request || typeof request !== "object") return fail("input must be a JSON object", request);
  if (typeof request.path !== "string" || typeof request.source !== "string" || typeof request.hash !== "string") {
    return fail("path, source, and hash must be strings", request);
  }
  const sourceBytes = Buffer.from(request.source, "utf8");
  if (sourceBytes.length > MAX_SOURCE_BYTES) return fail("source exceeds maximum size", request);

  const tsPath = request.typescriptPath || process.env.TYPESCRIPT_PATH || process.env.TS_PATH;
  if (typeof tsPath !== "string" || tsPath.length === 0) return fail("typescriptPath or TYPESCRIPT_PATH is required", request);
  let ts;
  try {
    ts = require(path.resolve(tsPath));
  } catch (error) {
    return fail(`unable to load TypeScript compiler: ${error instanceof Error ? error.message : String(error)}`, request);
  }

  const scriptKind = /\.tsx$/i.test(request.path) ? ts.ScriptKind.TSX : /\.jsx$/i.test(request.path) ? ts.ScriptKind.JSX : /\.js$/i.test(request.path) ? ts.ScriptKind.JS : ts.ScriptKind.TS;
  const file = ts.createSourceFile(request.path, request.source, ts.ScriptTarget.Latest, true, scriptKind);
  const offsets = new Map();
  const byteAt = (utf16) => {
    if (offsets.has(utf16)) return offsets.get(utf16);
    const value = Buffer.byteLength(request.source.slice(0, utf16), "utf8");
    offsets.set(utf16, value);
    return value;
  };
  const lineAt = (utf16) => file.getLineAndCharacterOfPosition(utf16).line + 1;
  const diagnostics = file.parseDiagnostics.slice(0, MAX_DIAGNOSTICS).map((diagnostic) => {
    const start = diagnostic.start || 0;
    const end = start + (diagnostic.length || 0);
    return {
      severity: "error",
      message: ts.flattenDiagnosticMessageText(diagnostic.messageText, " "),
      startByte: byteAt(start), endByte: byteAt(end), startLine: lineAt(start), endLine: lineAt(end),
    };
  });
  if (diagnostics.length > 0) {
    emit({ parserVersion: version(ts), path: request.path, hash: request.hash,
      actualHash: crypto.createHash("sha256").update(sourceBytes).digest("hex"), diagnostics, definitions: [] });
    return;
  }
  const definitions = [];
  const declarationOrdinals = new Map();
  const declarationKinds = new Set([
    ts.SyntaxKind.ClassDeclaration, ts.SyntaxKind.FunctionDeclaration, ts.SyntaxKind.InterfaceDeclaration,
    ts.SyntaxKind.EnumDeclaration, ts.SyntaxKind.TypeAliasDeclaration, ts.SyntaxKind.MethodDeclaration,
    ts.SyntaxKind.PropertyDeclaration, ts.SyntaxKind.VariableDeclaration,
  ]);
  function nameOf(node) {
    return node.name && ts.isIdentifier(node.name) ? node.name.text : null;
  }
  function visit(node, parent, parentIdentity) {
    if (definitions.length >= MAX_DEFINITIONS) return;
    let nextParent = parent;
    if (declarationKinds.has(node.kind)) {
      const name = nameOf(node);
      if (name) {
        const qualifiedName = parent ? `${parent}.${name}` : name;
        const start = node.getStart(file, false);
        const end = node.getEnd();
        const kind = ts.SyntaxKind[node.kind];
        const ordinalKey = `${parentIdentity || ""}\u0000${kind}\u0000${name}`;
        const ordinal = declarationOrdinals.get(ordinalKey) || 0;
        declarationOrdinals.set(ordinalKey, ordinal + 1);
        const identity = `${request.path}:${parentIdentity || "root"}:${kind}:${qualifiedName}:${ordinal}`;
        definitions.push({ kind, name, qualifiedName, parent, identity, parentIdentity,
          startByte: byteAt(start), endByte: byteAt(end), startLine: lineAt(start), endLine: lineAt(end) });
        nextParent = qualifiedName;
        parentIdentity = identity;
      }
    }
    ts.forEachChild(node, (child) => visit(child, nextParent, parentIdentity));
  }
  visit(file, null, null);
  if (definitions.length >= MAX_DEFINITIONS) diagnostics.push({ severity: "warning", message: "definition limit reached" });
  emit({ parserVersion: version(ts), path: request.path, hash: request.hash,
    actualHash: crypto.createHash("sha256").update(sourceBytes).digest("hex"), diagnostics, definitions });
}

main();
