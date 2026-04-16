package graphharness

sealed interface JsonValue {
    fun stringify(): String = buildString { this@JsonValue.appendJson(this) }
    fun appendJson(out: StringBuilder)
}

data class JObject(val fields: LinkedHashMap<String, JsonValue>) : JsonValue {
    override fun appendJson(out: StringBuilder) {
        out.append('{')
        fields.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) out.append(',')
            JString(key).appendJson(out)
            out.append(':')
            value.appendJson(out)
        }
        out.append('}')
    }
}

data class JArray(val values: List<JsonValue>) : JsonValue {
    override fun appendJson(out: StringBuilder) {
        out.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            value.appendJson(out)
        }
        out.append(']')
    }
}

data class JString(val value: String) : JsonValue {
    override fun appendJson(out: StringBuilder) {
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u%04x".format(ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
    }
}

data class JNumber(val raw: String) : JsonValue {
    override fun appendJson(out: StringBuilder) {
        out.append(raw)
    }
}

data class JBoolean(val value: Boolean) : JsonValue {
    override fun appendJson(out: StringBuilder) {
        out.append(if (value) "true" else "false")
    }
}

data object JNull : JsonValue {
    override fun appendJson(out: StringBuilder) {
        out.append("null")
    }
}

fun jObject(vararg pairs: Pair<String, Any?>): JObject {
    val fields = linkedMapOf<String, JsonValue>()
    pairs.forEach { (key, value) ->
        if (value != null) {
            fields[key] = when (value) {
                is JsonValue -> value
                is String -> JString(value)
                is Int -> JNumber(value.toString())
                is Long -> JNumber(value.toString())
                is Double -> JNumber(value.toString())
                is Float -> JNumber(value.toString())
                is Boolean -> JBoolean(value)
                is Map<*, *> -> JObject(linkedMapOf<String, JsonValue>().apply {
                    value.forEach { (k, v) -> put(k.toString(), jValue(v)) }
                })
                is List<*> -> JArray(value.map(::jValue))
                else -> graphHarnessJson.encode(value)
            }
        }
    }
    return JObject(fields)
}

fun emptyJsonObject(): JObject = JObject(linkedMapOf())

fun jValue(value: Any?): JsonValue = when (value) {
    null -> JNull
    is JsonValue -> value
    is String -> JString(value)
    is Int -> JNumber(value.toString())
    is Long -> JNumber(value.toString())
    is Double -> JNumber(value.toString())
    is Float -> JNumber(value.toString())
    is Boolean -> JBoolean(value)
    is List<*> -> JArray(value.map(::jValue))
    is Map<*, *> -> JObject(linkedMapOf<String, JsonValue>().apply {
        value.forEach { (k, v) -> put(k.toString(), jValue(v)) }
    })
    else -> graphHarnessJson.encode(value)
}

object MiniJson {
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.isDone()) { "Unexpected trailing JSON content" }
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun isDone(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (val ch = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JString(parseString())
                't' -> parseLiteral("true", JBoolean(true))
                'f' -> parseLiteral("false", JBoolean(false))
                'n' -> parseLiteral("null", JNull)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected JSON token '$ch' at index $index")
            }
        }

        private fun parseObject(): JObject {
            expect('{')
            skipWhitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (peek('}')) {
                expect('}')
                return JObject(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                fields[key] = parseValue()
                skipWhitespace()
                if (peek('}')) {
                    expect('}')
                    return JObject(fields)
                }
                expect(',')
            }
        }

        private fun parseArray(): JArray {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (peek(']')) {
                expect(']')
                return JArray(values)
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (peek(']')) {
                    expect(']')
                    return JArray(values)
                }
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val escaped = text[index++]
                        when (escaped) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val code = text.substring(index, index + 4).toInt(16)
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid JSON escape: \\$escaped")
                        }
                    }
                    else -> out.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): JNumber {
            val start = index
            if (text[index] == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            return JNumber(text.substring(start, index))
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            require(text.startsWith(literal, index)) { "Expected $literal at index $index" }
            index += literal.length
            return value
        }

        private fun expect(ch: Char) {
            require(index < text.length && text[index] == ch) { "Expected '$ch' at index $index" }
            index++
        }

        private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch
    }
}

fun JsonValue.asObject(): JObject = this as? JObject ?: error("Expected JSON object")
fun JsonValue.asArray(): JArray = this as? JArray ?: error("Expected JSON array")
fun JsonValue.asString(): String = (this as? JString)?.value ?: error("Expected JSON string")
fun JsonValue.asInt(): Int = (this as? JNumber)?.raw?.toInt() ?: error("Expected JSON integer")

operator fun JObject.get(key: String): JsonValue? = fields[key]
fun JObject.requiredString(key: String): String = this[key]?.asString() ?: error("Missing required field: $key")
fun JObject.optionalString(key: String): String? = (this[key] as? JString)?.value
fun JObject.optionalInt(key: String): Int? = (this[key] as? JNumber)?.raw?.toIntOrNull()
fun JObject.optionalObject(key: String): JObject? = this[key] as? JObject
fun JObject.requiredArray(key: String): JArray = this[key]?.asArray() ?: error("Missing required array: $key")
