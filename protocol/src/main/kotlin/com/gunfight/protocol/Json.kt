package com.gunfight.protocol

/** Minimal JSON parser/serializer (no dependencies, works on JVM + Android).
 *  Values: Map<String, Any?>, List<Any?>, String, Double, Boolean, null. */
object Json {

    fun stringify(v: Any?): String = buildString { appendValue(v) }

    private fun StringBuilder.appendValue(v: Any?) {
        when (v) {
            null -> append("null")
            is String -> appendQuoted(v)
            is Boolean -> append(if (v) "true" else "false")
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) append("null")
                else if (v is Int || v is Long || d == kotlin.math.floor(d) && !d.isInfinite()) append(v.toLong().toString())
                else append(d.toString())
            }
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) append(',')
                    first = false
                    appendQuoted(k.toString())
                    append(':')
                    appendValue(value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                v.forEachIndexed { i, e ->
                    if (i > 0) append(',')
                    appendValue(e)
                }
                append(']')
            }
            else -> appendQuoted(v.toString())
        }
    }

    private fun StringBuilder.appendQuoted(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun parse(s: String): Any? = Parser(s).parseValue().also { it }

    private class Parser(val s: String) {
        var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expect("true", true)
                'f' -> expect("false", false)
                'n' -> expect("null", null)
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun parseObject(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string key at $i")
                val k = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected ':' at $i")
                i++
                m[k] = parseValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("unterminated object")
                if (s[i] == '}') {
                    i++
                    return m
                }
                if (s[i] != ',') throw IllegalArgumentException("expected ',' at $i")
                i++
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l.add(parseValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("unterminated array")
                if (s[i] == ']') {
                    i++
                    return l
                }
                if (s[i] != ',') throw IllegalArgumentException("expected ',' at $i")
                i++
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun expect(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("expected $word at $i")
            i += word.length
            return v
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            return s.substring(start, i).toDouble()
        }
    }
}

/** Convenience getters for decoded maps. */
fun Map<String, Any?>.str(key: String, default: String = ""): String = (this[key] as? String) ?: default
fun Map<String, Any?>.int(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default
fun Map<String, Any?>.dbl(key: String, default: Double = 0.0): Double = (this[key] as? Number)?.toDouble() ?: default
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = (this[key] as? Boolean) ?: default
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?> = (this[key] as? Map<String, Any?>) ?: emptyMap()
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
fun num(v: Any?, default: Double = 0.0): Double = (v as? Number)?.toDouble() ?: default
