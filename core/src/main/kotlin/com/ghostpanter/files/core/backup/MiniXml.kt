package com.ghostpanter.files.core.backup

import java.io.Reader
import java.io.Writer

/**
 * Minimal streaming XML for backup files (SMS Backup & Restore style: attribute-only elements,
 * possibly hundreds of MB with base64 attachments). Runs the same on the JVM and Android.
 */
object MiniXml {
    fun escape(s: String, out: Appendable) {
        for (ch in s) when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&apos;")
            '\n' -> out.append("&#10;")
            '\r' -> out.append("&#13;")
            '\t' -> out.append("&#9;")
            else -> if (ch.code < 0x20) Unit /* not allowed in XML 1.0 */ else out.append(ch)
        }
    }

    fun escape(s: String): String = StringBuilder(s.length + 8).also { escape(s, it) }.toString()

    /** Writes `<tag a="v" …` (open), the caller closes with "/>" or ">". */
    fun startTag(w: Writer, tag: String, attrs: Map<String, String?>, indent: Int) {
        repeat(indent) { w.write("  ") }
        w.write("<"); w.write(tag)
        for ((k, v) in attrs) { w.write(" "); w.write(k); w.write("=\""); escape(v ?: "null", w); w.write("\"") }
    }

    sealed interface Event
    data class Start(val tag: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Event
    data class End(val tag: String) : Event

    /** Pull reader: elements and attributes; text, comments, PIs and DOCTYPE are skipped. */
    class PullReader(private val r: Reader) {
        private val buf = CharArray(1 shl 16)
        private var pos = 0
        private var len = 0
        private var pendingEnd: String? = null

        private fun peek(): Int { if (pos >= len) { len = r.read(buf); pos = 0; if (len <= 0) { len = 0; return -1 } }; return buf[pos].code }
        private fun read(): Int { val c = peek(); if (c >= 0) pos++; return c }

        fun next(): Event? {
            pendingEnd?.let { pendingEnd = null; return End(it) }
            while (true) {
                var c = read()
                while (c >= 0 && c != '<'.code) c = read()
                if (c < 0) return null
                when (peek()) {
                    '?'.code -> { skipUntil("?>"); continue }
                    '!'.code -> {
                        read()
                        if (peek() == '-'.code) { skipUntil("-->"); continue }
                        if (peek() == '['.code) { skipUntil("]]>"); continue }
                        skipUntil(">"); continue
                    }
                    '/'.code -> { read(); val name = readName(); skipUntil(">"); return End(name) }
                }
                val tag = readName()
                val attrs = LinkedHashMap<String, String>()
                while (true) {
                    skipSpace()
                    val p = peek()
                    if (p < 0) throw java.io.EOFException("unexpected end in <$tag>")
                    if (p == '/'.code) { read(); skipUntil(">"); pendingEnd = tag; return Start(tag, attrs, true) }
                    if (p == '>'.code) { read(); return Start(tag, attrs, false) }
                    val k = readName()
                    skipSpace()
                    if (read() != '='.code) throw java.io.IOException("bad attribute $k in <$tag>")
                    skipSpace()
                    val q = read()
                    if (q != '"'.code && q != '\''.code) throw java.io.IOException("unquoted attribute $k in <$tag>")
                    attrs[k] = readValue(q)
                }
            }
        }

        private fun skipSpace() { while (true) { val c = peek(); if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) read() else return } }

        private fun readName(): String {
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                if (c < 0 || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == '>'.code || c == '/'.code || c == '='.code) break
                sb.append(read().toChar())
            }
            return sb.toString()
        }

        private fun readValue(q: Int): String {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0) throw java.io.EOFException("unterminated attribute")
                if (c == q) return sb.toString()
                if (c == '&'.code) {
                    val ent = StringBuilder()
                    while (true) { val e = read(); if (e < 0 || e == ';'.code || ent.length > 10) break; ent.append(e.toChar()) }
                    val s = ent.toString()
                    when {
                        s == "amp" -> sb.append('&'); s == "lt" -> sb.append('<'); s == "gt" -> sb.append('>')
                        s == "quot" -> sb.append('"'); s == "apos" -> sb.append('\'')
                        s.startsWith("#x") || s.startsWith("#X") -> s.substring(2).toIntOrNull(16)?.let { sb.appendCodePoint(it) }
                        s.startsWith("#") -> s.substring(1).toIntOrNull()?.let { sb.appendCodePoint(it) }
                        else -> sb.append('&').append(s).append(';')
                    }
                } else sb.append(c.toChar())
            }
        }

        private fun skipUntil(end: String) {
            var m = 0
            while (true) {
                val c = read()
                if (c < 0) return
                m = if (c == end[m].code) m + 1 else if (c == end[0].code) 1 else 0
                if (m == end.length) return
            }
        }
    }
}
