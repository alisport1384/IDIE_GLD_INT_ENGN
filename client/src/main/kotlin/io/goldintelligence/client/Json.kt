package io.goldintelligence.client

/**
 * Minimal recursive-descent JSON reader.
 *
 * The module deliberately carries no third-party dependency: the client is
 * embedded in an Android application where every added library is both APK
 * weight and an extra supply-chain surface, and the parsing requirement here
 * is a few well-known response shapes, not arbitrary schema binding.
 */
sealed class Json {
    object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json()
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)
    operator fun get(index: Int): Json? = (this as? Arr)?.items?.getOrNull(index)

    val asArray: List<Json> get() = (this as? Arr)?.items ?: emptyList()
    val asObject: Map<String, Json> get() = (this as? Obj)?.fields ?: emptyMap()
    val asString: String? get() = (this as? Str)?.value ?: (this as? Num)?.value?.toString()

    /** Accepts numbers encoded either as JSON numbers or as numeric strings. */
    val asDouble: Double?
        get() = when (this) {
            is Num -> value
            is Str -> value.trim().toDoubleOrNull()
            else -> null
        }

    val isNull: Boolean get() = this is Null

    companion object {
        fun parse(text: String): Json = Parser(text).run {
            skipWhitespace()
            val v = readValue()
            v
        }

        fun parseOrNull(text: String): Json? = try {
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Json {
            skipWhitespace()
            if (i >= s.length) throw IllegalArgumentException("unexpected end of input")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Str(readString())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else -> readNumber()
            }
        }

        private fun expect(literal: String) {
            if (!s.startsWith(literal, i)) throw IllegalArgumentException("expected $literal at $i")
            i += literal.length
        }

        private fun readObject(): Json {
            i++ // {
            val map = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return Obj(map) }
            while (i < s.length) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected ':' at $i")
                i++
                map[key] = readValue()
                skipWhitespace()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return Obj(map) }
                    else -> throw IllegalArgumentException("malformed object at $i")
                }
            }
            throw IllegalArgumentException("unterminated object")
        }

        private fun readArray(): Json {
            i++ // [
            val items = ArrayList<Json>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return Arr(items) }
            while (i < s.length) {
                items += readValue()
                skipWhitespace()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return Arr(items) }
                    else -> throw IllegalArgumentException("malformed array at $i")
                }
            }
            throw IllegalArgumentException("unterminated array")
        }

        private fun readString(): String {
            if (s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun readNumber(): Json {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))
            ) i++
            val raw = s.substring(start, i)
            return Num(raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$raw' at $start"))
        }
    }
}

/** Compact JSON writer used by the server's REST layer. */
object JsonWriter {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun str(s: String?): String = if (s == null) "null" else "\"${escape(s)}\""

    fun num(v: Double?): String =
        if (v == null || v.isNaN() || v.isInfinite()) "null" else trimTrailingZeros(v)

    fun num(v: Int?): String = v?.toString() ?: "null"

    fun bool(v: Boolean): String = if (v) "true" else "false"

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { "${str(it.first)}:${it.second}" }

    fun <T> arr(items: Iterable<T>, render: (T) -> String): String =
        items.joinToString(",", "[", "]", transform = render)

    private fun trimTrailingZeros(v: Double): String {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    }
}
