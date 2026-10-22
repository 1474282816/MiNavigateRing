package com.minavigatering.probe.protocol

/**
 * Minimal dependency-free JSON reader/writer.
 *
 * The probe protocol has a fixed, small schema and the unit tests must not depend on the
 * Android SDK (org.json is an Android stub in plain JVM unit tests), so the codec carries
 * its own tiny implementation instead of pulling in a serialization runtime.
 */
internal object Json {

    fun obj(pairs: List<Pair<String, Any?>>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in pairs) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(key)).append(':').append(value(value))
        }
        return sb.append('}').toString()
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Map<*, *> -> obj(v.entries.map { it.key as String to it.value })
        is List<*> -> "[" + v.joinToString(",") { value(it) } + "]"
        else -> quote(v.toString())
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Parses a JSON document; the root is expected to be an object. */
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val root = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing content after JSON document" }
        require(root is Map<*, *>) { "expected JSON object at root" }
        @Suppress("UNCHECKED_CAST")
        return root as Map<String, Any?>
    }

    fun asString(v: Any?): String? = v as? String

    fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> if (v == v.toLong().toDouble()) v.toInt() else null
        is String -> v.toIntOrNull()
        else -> null
    }

    fun asLong(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Double -> if (v == v.toLong().toDouble()) v.toLong() else null
        is String -> v.toLongOrNull()
        else -> null
    }

    fun asBoolean(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        else -> null
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "unexpected end of JSON input" }
            return when (text[index]) {
                '{' -> parseObjectLiteral()
                '[' -> parseArrayLiteral()
                '"' -> parseStringLiteral()
                't', 'f' -> parseBooleanLiteral()
                'n' -> parseNullLiteral()
                else -> parseNumberLiteral()
            }
        }

        private fun parseObjectLiteral(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (true) {
                skipWhitespace()
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> throw IllegalArgumentException("expected ',' or '}' at offset $index")
                }
            }
        }

        private fun parseArrayLiteral(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { index++; return result }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> throw IllegalArgumentException("expected ',' or ']' at offset $index")
                }
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(!atEnd()) { "unterminated string" }
                val c = text[index++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val esc = text[index++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = text.substring(index, index + 4)
                                index += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("invalid escape \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseBooleanLiteral(): Boolean = when {
            text.startsWith("true", index) -> { index += 4; true }
            text.startsWith("false", index) -> { index += 5; false }
            else -> throw IllegalArgumentException("invalid literal at offset $index")
        }

        private fun parseNullLiteral(): Any? {
            require(text.startsWith("null", index)) { "invalid literal at offset $index" }
            index += 4
            return null
        }

        private fun parseNumberLiteral(): Double {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid number '$raw'")
        }

        private fun peek(): Char {
            require(!atEnd()) { "unexpected end of JSON input" }
            return text[index]
        }

        private fun expect(c: Char) {
            skipWhitespace()
            require(!atEnd() && text[index] == c) { "expected '$c' at offset $index" }
            index++
        }
    }
}
