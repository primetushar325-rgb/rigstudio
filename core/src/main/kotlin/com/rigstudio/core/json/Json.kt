package com.rigstudio.core.json

/**
 * A very small JSON reader/writer.
 *
 * RigStudio persists projects as JSON and deliberately avoids pulling in a serialization
 * library: the schema is tiny, the parser is 200 lines, it has no dependencies (so the same code
 * runs in the app and in plain JVM tests) and it never reflects over user data.
 */
sealed class JsonValue {
    object Null : JsonValue() {
        override fun toString() = "null"
    }

    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue() {
        val intValue: Int get() = value.toInt()
        val longValue: Long get() = value.toLong()
        val floatValue: Float get() = value.toFloat()
    }

    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val members: Map<String, JsonValue>) : JsonValue()
}

/** Thrown for malformed JSON. Callers convert it into a user-facing "project is corrupt" error. */
class JsonParseException(message: String) : Exception(message)

object Json {

    // ---------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: JsonParseException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != src.length) throw JsonParseException("Trailing content at $pos")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= src.length) throw JsonParseException("Unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> expect("true").let { JsonValue.Bool(true) }
                'f' -> expect("false").let { JsonValue.Bool(false) }
                'n' -> expect("null").let { JsonValue.Null }
                else -> if (c == '-' || c in '0'..'9') parseNumber()
                else throw JsonParseException("Unexpected character '$c' at $pos")
            }
        }

        private fun parseObject(): JsonValue.Obj {
            expectChar('{')
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(members)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                members[key] = parseValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return JsonValue.Obj(members)
                    else -> throw JsonParseException("Expected ',' or '}' but found '$c' at $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expectChar('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return JsonValue.Arr(items)
                    else -> throw JsonParseException("Expected ',' or ']' but found '$c' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) throw JsonParseException("Unterminated string")
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw JsonParseException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw JsonParseException("Bad \\u escape")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonParseException("Unknown escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && (src[pos] in '0'..'9' || src[pos] in ".eE+-")) pos++
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull() ?: throw JsonParseException("Bad number '$text'")
            return JsonValue.Num(value)
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char =
            if (pos < src.length) src[pos] else throw JsonParseException("Unexpected end of input")

        private fun next(): Char =
            if (pos < src.length) src[pos++] else throw JsonParseException("Unexpected end of input")

        private fun expectChar(c: Char) {
            if (next() != c) throw JsonParseException("Expected '$c' at ${pos - 1}")
        }

        private fun expect(word: String) {
            if (!src.startsWith(word, pos)) throw JsonParseException("Expected '$word' at $pos")
            pos += word.length
        }
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    fun stringify(value: JsonValue, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: JsonValue, pretty: Boolean, depth: Int) {
        when (value) {
            JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(value.value.toString())
            is JsonValue.Num -> sb.append(formatNumber(value.value))
            is JsonValue.Str -> writeString(sb, value.value)
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    if (pretty) newline(sb, depth + 1)
                    write(sb, item, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append(']')
            }
            is JsonValue.Obj -> {
                if (value.members.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                value.members.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) sb.append(',')
                    if (pretty) newline(sb, depth + 1)
                    writeString(sb, key)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    write(sb, item, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append('}')
            }
        }
    }

    /** Integers stay integers; floats keep enough precision to round-trip. */
    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

}

// -------------------------------------------------------------------------------------------
// Builders — top level, so call sites can `import com.rigstudio.core.json.obj` and stay readable.
// -------------------------------------------------------------------------------------------

fun obj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj = JsonValue.Obj(
    LinkedHashMap<String, JsonValue>().apply {
        for ((key, value) in pairs) if (value != null) put(key, value)
    },
)

fun arr(vararg items: JsonValue): JsonValue.Arr = JsonValue.Arr(items.toList())

fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

fun str(value: String): JsonValue.Str = JsonValue.Str(value)
fun num(value: Int): JsonValue.Num = JsonValue.Num(value.toDouble())
fun num(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble())
fun num(value: Float): JsonValue.Num = JsonValue.Num(value.toDouble())
fun num(value: Double): JsonValue.Num = JsonValue.Num(value)
fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

/** Convenience accessors that never throw — corrupt project files degrade to defaults. */
fun JsonValue.objOrNull(): JsonValue.Obj? = this as? JsonValue.Obj
fun JsonValue.arrOrNull(): JsonValue.Arr? = this as? JsonValue.Arr

fun JsonValue.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.members?.get(key)

fun JsonValue.string(key: String, fallback: String = ""): String =
    (get(key) as? JsonValue.Str)?.value ?: fallback

fun JsonValue.stringOrNull(key: String): String? = (get(key) as? JsonValue.Str)?.value

fun JsonValue.int(key: String, fallback: Int = 0): Int =
    (get(key) as? JsonValue.Num)?.intValue ?: fallback

fun JsonValue.long(key: String, fallback: Long = 0L): Long =
    (get(key) as? JsonValue.Num)?.longValue ?: fallback

fun JsonValue.float(key: String, fallback: Float = 0f): Float =
    (get(key) as? JsonValue.Num)?.floatValue ?: fallback

fun JsonValue.boolean(key: String, fallback: Boolean = false): Boolean =
    (get(key) as? JsonValue.Bool)?.value ?: fallback

fun JsonValue.stringList(key: String): List<String> =
    (get(key) as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()

fun JsonValue.objList(key: String): List<JsonValue.Obj> =
    (get(key) as? JsonValue.Arr)?.items?.mapNotNull { it as? JsonValue.Obj } ?: emptyList()
