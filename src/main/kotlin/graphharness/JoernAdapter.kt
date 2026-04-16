package graphharness

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

data class JoernGraphData(
    val types: List<TypeInfo>,
    val methods: List<MethodInfo>,
    val callEdges: List<EdgeSummary>,
    val typeEdges: List<EdgeSummary>,
    val dependencyEdges: List<EdgeSummary>,
)

data class JoernInstallation(
    val joern: Path,
    val javasrc2cpg: Path,
    val version: String?,
)

fun detectJoernInstallation(): JoernInstallation? {
    val candidates: List<Path> = listOfNotNull(
        System.getenv("JOERN_HOME")?.let { Paths.get(it) },
        System.getenv("GRAPHHARNESS_JOERN_HOME")?.let { Paths.get(it) },
        Paths.get(System.getProperty("user.home"), ".local", "share", "graphharness", "joern"),
        Paths.get(System.getProperty("user.home"), "bin", "joern"),
    )

    for (home in candidates) {
        val directJoern = home.resolve("joern")
        val directParser = home.resolve("javasrc2cpg")
        if (Files.isExecutable(directJoern) && Files.isExecutable(directParser)) {
            return JoernInstallation(directJoern, directParser, detectJoernVersion(home))
        }
        val nestedJoern = home.resolve("joern-cli").resolve("joern")
        val nestedParser = home.resolve("joern-cli").resolve("javasrc2cpg")
        if (Files.isExecutable(nestedJoern) && Files.isExecutable(nestedParser)) {
            return JoernInstallation(nestedJoern, nestedParser, detectJoernVersion(home.resolve("joern-cli")))
        }
    }

    return findExecutableOnPath("joern")?.let { joern ->
        val parser = findExecutableOnPath("javasrc2cpg") ?: return null
        JoernInstallation(joern, parser, detectJoernVersion(parser.parent))
    }
}

fun detectJoernVersion(home: Path?): String? =
    runCatching {
        if (home == null) return null
        val normalizedHome = if (home.fileName?.toString() == "joern-cli") home else home.resolve("joern-cli")
        val cpgVersion = normalizedHome.resolve("schema-extender").resolve("cpg-version")
        val consoleVersion = Files.list(normalizedHome.resolve("lib")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.startsWith("io.joern.console-") && it.endsWith(".jar") }
                .findFirst()
                .orElse(null)
        }
        val joernVersion = consoleVersion
            ?.removePrefix("io.joern.console-")
            ?.removeSuffix(".jar")
            ?.ifBlank { null }
        val cpg = if (Files.isRegularFile(cpgVersion)) Files.readString(cpgVersion).trim().ifBlank { null } else null
        listOfNotNull(joernVersion?.let { "joern-$it" }, cpg?.let { "cpg-$it" }).joinToString(" / ").ifBlank { null }
    }.getOrNull()

fun findExecutableOnPath(name: String): Path? {
    val path = System.getenv("PATH") ?: return null
    return path.split(':')
        .map { Paths.get(it).resolve(name) }
        .firstOrNull { Files.isExecutable(it) }
}

fun JoernInstallation.exportGraph(projectRoot: Path): JoernGraphData {
    val workDir = Files.createTempDirectory("graphharness-joern")
    val cpgFile = workDir.resolve("cpg.bin")
    val queryFile = workDir.resolve("snapshot.sc")
    val outputFile = workDir.resolve("snapshot.tsv")
    Files.writeString(queryFile, JOERN_SNAPSHOT_SCRIPT)

    runCommand(
        listOf(
            javasrc2cpg.toString(),
            projectRoot.toAbsolutePath().normalize().toString(),
            "--output",
            cpgFile.toString(),
            "--enable-file-content",
        ),
        workDir,
    )

    runCommand(
        listOf(
            joern.toString(),
            "--script",
            queryFile.toString(),
            "--param",
            "cpgFile=${cpgFile}",
            "--param",
            "outFile=${outputFile}",
        ),
        workDir,
    )
    return parseJoernSnapshot(outputFile, projectRoot)
}

fun runCommand(command: List<String>, workDir: Path) {
    val process = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    require(exit == 0) {
        "Command failed (${command.joinToString(" ")}): $output"
    }
}

fun parseJoernSnapshot(outputFile: Path, projectRoot: Path): JoernGraphData {
    val types = mutableListOf<TypeInfo>()
    val methods = mutableListOf<MethodInfo>()
    val callEdges = mutableListOf<EdgeSummary>()
    val typeEdges = mutableListOf<EdgeSummary>()
    val dependencyEdges = mutableListOf<EdgeSummary>()
    val methodIdByFullName = mutableMapOf<String, String>()
    val typeIdByQualifiedName = mutableMapOf<String, String>()
    val pendingCalls = mutableListOf<PendingCall>()
    val pendingTypeEdges = mutableListOf<PendingTypeEdge>()
    val pendingTypeRefs = mutableListOf<PendingTypeRef>()

    Files.readAllLines(outputFile).forEach { line ->
        if (line.isBlank()) return@forEach
        val parts = line.split('\t')
        when (parts[0]) {
            "TYPE" -> {
                val qualifiedName = decodeField(parts[1])
                val type = TypeInfo(
                    id = "type:$qualifiedName",
                    kind = decodeField(parts[3]).ifBlank { "class" },
                    packageName = decodeField(parts[2]),
                    simpleName = decodeField(parts[4]),
                    qualifiedName = qualifiedName,
                    file = decodeField(parts[5]),
                    lineRange = SourceRange(parts[6].toIntOrNull() ?: 1, parts[7].toIntOrNull() ?: (parts[6].toIntOrNull() ?: 1)),
                    visibility = decodeField(parts[8]).ifBlank { "package" },
                    annotations = decodeCsv(parts[9]),
                    extendsType = decodeField(parts[10]).ifBlank { null },
                    implementsTypes = decodeCsv(parts[11]),
                )
                types += type
                typeIdByQualifiedName[type.qualifiedName] = type.id
                type.extendsType?.let { pendingTypeEdges += PendingTypeEdge(type.id, it, "extends") }
                type.implementsTypes.forEach { pendingTypeEdges += PendingTypeEdge(type.id, it, "implements") }
            }

            "METHOD" -> {
                val fullName = decodeField(parts[2])
                val qualifiedName = fullName.substringBefore(':').ifBlank {
                    listOf(decodeField(parts[1]), decodeField(parts[3])).filter { it.isNotBlank() }.joinToString(".")
                }
                val parentQualifiedName = decodeField(parts[1]).ifBlank { qualifiedName.substringBeforeLast('.', "") }
                val signature = decodeField(parts[4])
                val parameterTypes = decodeCsv(parts[11])
                val returnType = decodeField(parts[12]).ifBlank { null }
                val method = MethodInfo(
                    id = "method:$qualifiedName:${signatureOf(parameterTypes, returnType)}",
                    parentTypeId = "type:$parentQualifiedName",
                    parentQualifiedName = parentQualifiedName,
                    simpleName = decodeField(parts[3]),
                    qualifiedName = qualifiedName,
                    signature = signature,
                    file = decodeField(parts[5]),
                    lineRange = SourceRange(parts[6].toIntOrNull() ?: 1, parts[7].toIntOrNull() ?: (parts[6].toIntOrNull() ?: 1)),
                    visibility = decodeField(parts[8]).ifBlank { "package" },
                    annotations = decodeCsv(parts[9]),
                    complexity = parts[10].toIntOrNull() ?: 1,
                    loc = ((parts[7].toIntOrNull() ?: 1) - (parts[6].toIntOrNull() ?: 1) + 1).coerceAtLeast(1),
                    returnType = returnType,
                    parameterTypes = parameterTypes,
                    body = "",
                    callTokens = emptyList(),
                    typeRefs = (parameterTypes + listOfNotNull(returnType)).filter { it.isNotBlank() }.toSet(),
                )
                methods += method
                methodIdByFullName[fullName] = method.id
                (parameterTypes + listOfNotNull(returnType)).forEach { pendingTypeRefs += PendingTypeRef(method.id, it) }
            }

            "CALL" -> {
                pendingCalls += PendingCall(
                    sourceQualifiedName = decodeField(parts[1]),
                    targetQualifiedName = decodeField(parts[2]),
                    file = decodeField(parts[3]).ifBlank { null },
                    line = parts[4].toIntOrNull(),
                )
            }
        }
    }

    pendingCalls.forEach { call ->
        val source = methodIdByFullName[call.sourceQualifiedName] ?: return@forEach
        val target = methodIdByFullName[call.targetQualifiedName] ?: return@forEach
        callEdges += EdgeSummary(source, target, "calls", call.file, call.line)
    }

    pendingTypeEdges.forEach { edge ->
        val target = typeIdByQualifiedName[edge.targetQualifiedName] ?: return@forEach
        val sourceType = types.firstOrNull { it.id == edge.fromId }
        val targetType = types.firstOrNull { it.id == target }
        val relationship = if (
            edge.relationship == "extends" &&
            sourceType?.kind != "interface" &&
            targetType?.kind == "interface"
        ) {
            "implements"
        } else {
            edge.relationship
        }
        typeEdges += EdgeSummary(edge.fromId, target, relationship)
    }

    pendingTypeRefs.forEach { ref ->
        val target = typeIdByQualifiedName[ref.targetQualifiedName] ?: return@forEach
        dependencyEdges += EdgeSummary(ref.methodId, target, "uses_type")
    }

    return refineJoernRanges(
        JoernGraphData(types, methods, callEdges, typeEdges, dependencyEdges),
        projectRoot,
    )
}

data class PendingCall(
    val sourceQualifiedName: String,
    val targetQualifiedName: String,
    val file: String?,
    val line: Int?,
)

data class PendingTypeEdge(
    val fromId: String,
    val targetQualifiedName: String,
    val relationship: String,
)

data class PendingTypeRef(
    val methodId: String,
    val targetQualifiedName: String,
)

fun encodeField(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

fun decodeField(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

fun decodeCsv(value: String): List<String> =
    if (value.isBlank()) emptyList() else decodeField(value).split("||").filter { it.isNotBlank() }

val JOERN_SNAPSHOT_SCRIPT = """
import java.io.PrintWriter
import java.util.Base64

def enc(value: String): String =
  Base64.getEncoder.encodeToString(Option(value).getOrElse("").getBytes("UTF-8"))

def encList(values: Iterable[String]): String =
  enc(values.filter(_ != null).mkString("||"))

@main def exec(cpgFile: String, outFile: String): Unit = {
  importCpg(cpgFile)
  val out = new PrintWriter(outFile)

  cpg.typeDecl.filterNot(_.isExternal).l.foreach { td =>
    val fullName = Option(td.fullName).map(_.toString).getOrElse("")
    val packageName = fullName.split('.').dropRight(1).mkString(".")
    val kind =
      if (Option(td.code).map(_.toString).exists(_.contains(" interface "))) "interface"
      else if (Option(td.code).map(_.toString).exists(_.contains(" enum "))) "enum"
      else "class"
    val simpleName = Option(td.name).map(_.toString).getOrElse(fullName.split('.').lastOption.getOrElse(""))
    val visibility = td.modifier.modifierType.l.find(Set("PUBLIC", "PROTECTED", "PRIVATE")).map(_.toLowerCase).getOrElse("package")
    val annotations = td.annotation.name.l.map("@" + _)
    val inherits = td.inheritsFromTypeFullName.l
    val extendsType = inherits.headOption.getOrElse("")
    val implementsTypes = if (inherits.size > 1) inherits.drop(1) else Nil
    out.println(
      List(
        "TYPE",
        enc(fullName),
        enc(packageName),
        enc(kind),
        enc(simpleName),
        enc(Option(td.filename).map(_.toString).getOrElse("")),
        td.lineNumber.getOrElse(1).toString,
        td.ast.lineNumber.l.maxOption.getOrElse(td.lineNumber.getOrElse(1)).toString,
        enc(visibility),
        encList(annotations),
        enc(extendsType),
        encList(implementsTypes)
      ).mkString("\t")
    )
  }

  cpg.method.filterNot(_.isExternal).l.foreach { m =>
    val parent = Option(m.typeDecl.fullName).map(_.toString).getOrElse("")
    val fullName = Option(m.fullName).map(_.toString).getOrElse("")
    val methodName = Option(m.name).map(_.toString).getOrElse("")
    val visibility = m.modifier.modifierType.l.find(Set("PUBLIC", "PROTECTED", "PRIVATE")).map(_.toLowerCase).getOrElse("package")
    val annotations = m.annotation.name.l.map("@" + _)
    val params = m.parameter.l
      .filterNot(p => Option(p.name).map(_.toString).contains("this"))
      .flatMap(p => Option(p.typeFullName).map(_.toString))
      .filterNot(_ == null)
    val returnType = Option(m.methodReturn.typeFullName).map(_.toString).getOrElse("")
    val signature = s"(${'$'}{params.mkString(", ")}) -> ${'$'}{if (returnType.nonEmpty) returnType else "void"}"
    val code = Option(m.code).map(_.toString).getOrElse("")
    val complexity = 1 + List("if", "for", "while", "case", "catch").count(keyword => code.contains(keyword))
    out.println(
      List(
        "METHOD",
        enc(parent),
        enc(fullName),
        enc(methodName),
        enc(signature),
        enc(Option(m.filename).map(_.toString).getOrElse("")),
        m.lineNumber.getOrElse(1).toString,
        m.ast.lineNumber.l.maxOption.getOrElse(m.lineNumber.getOrElse(1)).toString,
        enc(visibility),
        encList(annotations),
        complexity.toString,
        encList(params),
        enc(returnType)
      ).mkString("\t")
    )
  }

  cpg.call.l.foreach { call =>
    val src = Option(call.method.fullName).map(_.toString).getOrElse("")
    val dst = Option(call.methodFullName).map(_.toString).getOrElse("")
    if (src.nonEmpty && dst.nonEmpty && !dst.startsWith("<operator>")) {
      out.println(
        List(
          "CALL",
          enc(src),
          enc(dst),
          enc(Option(call.method.filename).map(_.toString).getOrElse("")),
          call.lineNumber.getOrElse(0).toString
        ).mkString("\t")
      )
    }
  }

  out.close()
}
""".trimIndent()
