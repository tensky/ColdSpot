package id.tensky.coldspot.manifest

/**
 * A JSON value as read from text or about to be written: enough of JSON for the manifests, all of it strict.
 * [at] is the offset in the text a read value started at, for error messages; zero for values built to write.
 */
internal sealed class JsonValue(val at: Int) {
    class Obj(val fields: Map<String, JsonValue>, at: Int = 0) : JsonValue(at)
    class Arr(val items: List<JsonValue>, at: Int = 0) : JsonValue(at)
    class Str(val value: String, at: Int = 0) : JsonValue(at)

    /** The number as written; the manifests hold integers only, and the typed reader decides what a text is worth. */
    class Num(val text: String, at: Int = 0) : JsonValue(at)
    class Bool(val value: Boolean, at: Int = 0) : JsonValue(at)
    class Null(at: Int = 0) : JsonValue(at)
}

/** What a value is, for messages: "a string", "an array". */
internal val JsonValue.kind: String
    get() = when (this) {
        is JsonValue.Obj -> "an object"
        is JsonValue.Arr -> "an array"
        is JsonValue.Str -> "a string"
        is JsonValue.Num -> "a number"
        is JsonValue.Bool -> "a boolean"
        is JsonValue.Null -> "null"
    }

/**
 * A strict JSON parser (RFC 8259) over the whole [text]: one value, nothing but whitespace around it. Every
 * refusal is a [ManifestFormatException] naming the line and column, 1-based, of the character it stopped at.
 */
internal class JsonParser(private val text: String) {
    private var i = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = value()
        skipWhitespace()
        if (i < text.length) fail("nothing may follow the value, found ${describe(text[i])}")
        return value
    }

    private fun value(): JsonValue {
        if (i >= text.length) fail("unexpected end of input, expected a value")
        return when (val c = text[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> {
                val at = i
                JsonValue.Str(string(), at)
            }
            't' -> literal("true", JsonValue.Bool(true, i))
            'f' -> literal("false", JsonValue.Bool(false, i))
            'n' -> literal("null", JsonValue.Null(i))
            '-', in '0'..'9' -> number()
            else -> fail("expected a value, found ${describe(c)}")
        }
    }

    private fun obj(): JsonValue {
        val at = i
        i++ // {
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            i++
            return JsonValue.Obj(fields, at)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a string key, found ${describe(peek())}")
            val keyAt = i
            val key = string()
            if (key in fields) failAt(keyAt, "the key \"$key\" appears twice in one object")
            skipWhitespace()
            if (peek() != ':') fail("expected ':' after the key \"$key\", found ${describe(peek())}")
            i++
            skipWhitespace()
            fields[key] = value()
            skipWhitespace()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonValue.Obj(fields, at)
                }
                else -> fail("expected ',' or '}' after the value of \"$key\", found ${describe(peek())}")
            }
        }
    }

    private fun arr(): JsonValue {
        val at = i
        i++ // [
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            i++
            return JsonValue.Arr(items, at)
        }
        while (true) {
            skipWhitespace()
            items += value()
            skipWhitespace()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonValue.Arr(items, at)
                }
                else -> fail("expected ',' or ']' after an array element, found ${describe(peek())}")
            }
        }
    }

    /** Reads a string at [i], which holds the opening quote, escapes decoded; [i] ends past the closing quote. */
    private fun string(): String {
        val at = i
        i++ // "
        val out = StringBuilder()
        while (true) {
            if (i >= text.length) failAt(at, "the string that starts here never ends")
            val c = text[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (i >= text.length) failAt(at, "the string that starts here never ends")
                    when (val e = text[i++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > text.length) failAt(i - 2, "\\u needs four hex digits")
                            val hex = text.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: failAt(i - 2, "\\u needs four hex digits, found \"$hex\"")
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> failAt(i - 2, "unknown escape \\$e")
                    }
                }
                c < ' ' -> failAt(i - 1, "a control character (U+${c.code.toString(16).padStart(4, '0')}) must be escaped inside a string")
                else -> out.append(c)
            }
        }
    }

    private fun number(): JsonValue {
        val at = i
        if (peek() == '-') i++
        when {
            peek() == '0' -> i++
            peek() in '1'..'9' -> while (peek() in '0'..'9') i++
            else -> fail("expected a digit, found ${describe(peek())}")
        }
        if (peek() == '.') {
            i++
            if (peek() !in '0'..'9') fail("expected a digit after '.', found ${describe(peek())}")
            while (peek() in '0'..'9') i++
        }
        if (peek() == 'e' || peek() == 'E') {
            i++
            if (peek() == '+' || peek() == '-') i++
            if (peek() !in '0'..'9') fail("expected a digit in the exponent, found ${describe(peek())}")
            while (peek() in '0'..'9') i++
        }
        return JsonValue.Num(text.substring(at, i), at)
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, i)) fail("expected a value, found ${describe(text[i])}")
        i += word.length
        return value
    }

    private fun peek(): Char = if (i < text.length) text[i] else EOF

    private fun skipWhitespace() {
        while (i < text.length && (text[i] == ' ' || text[i] == '\n' || text[i] == '\r' || text[i] == '\t')) i++
    }

    private fun describe(c: Char): String = if (c == EOF) "the end of the input" else "'$c'"

    private fun fail(message: String): Nothing = failAt(i, message)

    private fun failAt(offset: Int, message: String): Nothing = throw ManifestFormatException(message, text, offset)

    private companion object {
        const val EOF = '￿'
    }
}

/**
 * Writes a value the way kotlinx-serialization's pretty printer did, byte for byte, so that manifests written
 * before and after the switch compare equal: four-space indent, `"key": value`, one element per line, empty
 * collections as `[]` and `{}`, no trailing newline. Strings escape `"`, `\`, and control characters (`\b`,
 * `\f`, `\n`, `\r`, `\t`, else `\u00xx` in lower case); everything else, emoji and all, is written as it is.
 * The one addition: a lone surrogate, which no UTF-8 file can hold anyway, is written as `\uxxxx` rather than
 * as a character no encoding can represent.
 */
internal fun JsonValue.render(): String = StringBuilder().also { write(it, 0) }.toString()

private const val INDENT = "    "

private fun JsonValue.write(out: StringBuilder, level: Int) {
    when (this) {
        is JsonValue.Obj -> {
            if (fields.isEmpty()) {
                out.append("{}")
                return
            }
            out.append('{')
            var first = true
            for ((key, value) in fields) {
                if (!first) out.append(',')
                first = false
                newline(out, level + 1)
                out.append('"')
                escapeInto(out, key)
                out.append("\": ")
                value.write(out, level + 1)
            }
            newline(out, level)
            out.append('}')
        }
        is JsonValue.Arr -> {
            if (items.isEmpty()) {
                out.append("[]")
                return
            }
            out.append('[')
            var first = true
            for (item in items) {
                if (!first) out.append(',')
                first = false
                newline(out, level + 1)
                item.write(out, level + 1)
            }
            newline(out, level)
            out.append(']')
        }
        is JsonValue.Str -> {
            out.append('"')
            escapeInto(out, value)
            out.append('"')
        }
        is JsonValue.Num -> out.append(text)
        is JsonValue.Bool -> out.append(value)
        is JsonValue.Null -> out.append("null")
    }
}

private fun newline(out: StringBuilder, level: Int) {
    out.append('\n')
    repeat(level) { out.append(INDENT) }
}

internal fun escapeInto(out: StringBuilder, s: String) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '' -> out.append("\\f")
            c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                out.append(c).append(s[i + 1])
                i++
            }
            c.isSurrogate() -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
        i++
    }
}

/** A manifest that cannot be read: not JSON, not this schema, or not a manifest at all. [line] and [column] are 1-based. */
public class ManifestFormatException internal constructor(
    detail: String,
    public val line: Int?,
    public val column: Int?,
) : RuntimeException(if (line != null && column != null) "line $line, column $column: $detail" else detail) {
    internal constructor(detail: String, text: String, offset: Int) : this(detail, lineOf(text, offset), columnOf(text, offset))

    internal constructor(detail: String) : this(detail, null, null)

    private companion object {
        fun lineOf(text: String, offset: Int): Int {
            var line = 1
            for (k in 0 until minOf(offset, text.length)) if (text[k] == '\n') line++
            return line
        }

        fun columnOf(text: String, offset: Int): Int {
            val end = minOf(offset, text.length)
            val lastNewline = text.lastIndexOf('\n', end - 1)
            return end - lastNewline
        }
    }
}
