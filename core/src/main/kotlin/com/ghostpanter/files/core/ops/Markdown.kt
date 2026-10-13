package com.ghostpanter.files.core.ops

/**
 * Tiny Markdown → HTML converter for preview (headings, lists, code, emphasis, links, paragraphs).
 * Not a full CommonMark implementation.
 */
object Markdown {
    fun toHtml(src: String, title: String = "Preview"): String {
        val body = StringBuilder()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        var inCode = false
        val code = StringBuilder()
        var inUl = false
        fun closeUl() { if (inUl) { body.append("</ul>"); inUl = false } }
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    body.append("<pre><code>").append(esc(code.toString())).append("</code></pre>")
                    code.clear(); inCode = false
                } else {
                    closeUl(); inCode = true
                }
                i++; continue
            }
            if (inCode) { code.append(line).append('\n'); i++; continue }
            val t = line.trimEnd()
            when {
                t.matches(Regex("^#{1,6}\\s+.*")) -> {
                    closeUl()
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = t.drop(level).trim()
                    body.append("<h$level>").append(inline(text)).append("</h$level>")
                }
                t.matches(Regex("^[-*+]\\s+.*")) -> {
                    if (!inUl) { body.append("<ul>"); inUl = true }
                    body.append("<li>").append(inline(t.replaceFirst(Regex("^[-*+]\\s+"), ""))).append("</li>")
                }
                t.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    closeUl()
                    body.append("<p>").append(inline(t)).append("</p>")
                }
                t.startsWith("> ") -> {
                    closeUl()
                    body.append("<blockquote>").append(inline(t.removePrefix("> "))).append("</blockquote>")
                }
                t.isBlank() -> closeUl()
                else -> {
                    closeUl()
                    body.append("<p>").append(inline(t)).append("</p>")
                }
            }
            i++
        }
        closeUl()
        if (inCode) body.append("<pre><code>").append(esc(code.toString())).append("</code></pre>")
        return """<!DOCTYPE html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${esc(title)}</title>
<style>
body{font-family:sans-serif;padding:12px;line-height:1.5;word-wrap:break-word;background:#111;color:#eee}
pre{background:#1e1e1e;padding:8px;overflow:auto;border-radius:6px}
code{font-family:monospace}
a{color:#7AD9FB} h1,h2,h3{color:#F58D1D} blockquote{border-left:3px solid #555;margin:0;padding-left:12px;color:#aaa}
</style></head><body>$body</body></html>"""
    }

    private fun inline(s: String): String {
        var t = esc(s)
        t = t.replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
        t = t.replace(Regex("""\*(.+?)\*"""), "<em>$1</em>")
        t = t.replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        t = t.replace(Regex("""\[(.+?)]\((https?://[^)\s]+)\)"""), """<a href="$2">$1</a>""")
        return t
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
