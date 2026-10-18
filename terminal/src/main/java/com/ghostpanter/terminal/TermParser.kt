package com.ghostpanter.terminal

/**
 * Splits backend output into text and in-band control records. Backends make the shell print
 * `\u001eGFP<rc>;<cwd>\u001e` whenever it is ready for the next command (PS1 on a PTY, an explicit
 * printf after each line otherwise) and `\u001eGFPID<pid>\u001e` once at start. Records may be
 * split across reads; partial ones are held back.
 */
class TermParser {
    sealed interface Event {
        data class Text(val text: String) : Event
        data class Prompt(val rc: Int, val cwd: String) : Event
        data class Pid(val pid: Int) : Event
    }

    private val pending = StringBuilder()

    fun feed(chunk: String): List<Event> {
        pending.append(chunk)
        val out = ArrayList<Event>()
        val text = StringBuilder()
        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            if (c != RS) { text.append(c); i++; continue }
            val end = pending.indexOf(RS.toString(), i + 1)
            if (end < 0) {
                // a record start that might still be completed by the next chunk
                if (pending.length - i <= MAX_RECORD && GFP.startsWith(pending.substring(i + 1, minOf(pending.length, i + 1 + GFP.length)))) break
                text.append(c); i++; continue
            }
            val body = pending.substring(i + 1, end)
            when {
                body.startsWith("GFPID") -> { flush(text, out); body.removePrefix("GFPID").trim().toIntOrNull()?.let { out += Event.Pid(it) }; i = end + 1 }
                body.startsWith(GFP) -> {
                    flush(text, out)
                    val rest = body.removePrefix(GFP)
                    val rc = rest.substringBefore(';').trim().toIntOrNull() ?: 0
                    out += Event.Prompt(rc, rest.substringAfter(';', "/").ifEmpty { "/" })
                    i = end + 1
                }
                else -> { text.append(c); i++ }
            }
        }
        flush(text, out)
        pending.delete(0, i)
        return out
    }

    private fun flush(text: StringBuilder, out: MutableList<Event>) {
        if (text.isNotEmpty()) { out += Event.Text(text.toString()); text.setLength(0) }
    }

    companion object {
        const val RS = '\u001e'
        const val GFP = "GFP"
        private const val MAX_RECORD = 4200
    }
}

/** One styled run of terminal output (after ANSI SGR decoding). */
data class Styled(val text: String, val fg: Int = -1, val bold: Boolean = false)

/** Control actions embedded in output that the view applies to its buffer. */
sealed interface AnsiOp {
    data class Write(val run: Styled) : AnsiOp
    /** '\r' not followed by '\n': next text overwrites the current line. */
    data object CarriageReturn : AnsiOp
    data object Backspace : AnsiOp
    /** ESC[2J / ESC[H... clear screen (or Ctrl-L from a program). */
    data object ClearScreen : AnsiOp
    /** ESC[K: erase to end of line (after a CR this clears the line being redrawn). */
    data object EraseLine : AnsiOp
}

/**
 * Minimal ANSI decoder for a line-oriented transcript: SGR colours (30–37, 90–97, 39, 0, 1),
 * CR / BS / clear-screen / erase-line; other CSI and OSC sequences are dropped. Keeps state
 * across chunks.
 */
class AnsiDecoder {
    private var fg = -1
    private var bold = false
    private var esc = StringBuilder()
    private var inEsc = false
    private var pendingCr = false

    fun decode(s: String): List<AnsiOp> {
        val ops = ArrayList<AnsiOp>()
        val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { ops += AnsiOp.Write(Styled(run.toString(), fg, bold)); run.setLength(0) } }
        for (c in s) {
            if (inEsc) {
                esc.append(c)
                if (finishEscape(ops)) { inEsc = false; esc.setLength(0) }
                continue
            }
            if (pendingCr) {
                pendingCr = false
                if (c != '\n') { flush(); ops += AnsiOp.CarriageReturn }
            }
            when (c) {
                '\u001b' -> { flush(); inEsc = true; esc.setLength(0) }
                '\r' -> pendingCr = true
                '\b' -> { flush(); ops += AnsiOp.Backspace }
                '\u0007', '\u0000', '\u000e', '\u000f' -> Unit
                else -> run.append(c)
            }
        }
        flush()
        return ops
    }

    /** Returns true when the sequence in [esc] is complete (and applies it). */
    private fun finishEscape(ops: MutableList<AnsiOp>): Boolean {
        if (esc.isEmpty()) return false
        val first = esc[0]
        if (first == '[') {
            val last = esc[esc.length - 1]
            if (esc.length == 1 || last !in '@'..'~') return esc.length > 64
            val params = esc.substring(1, esc.length - 1)
            when (last) {
                'm' -> sgr(params)
                'J' -> if (params == "2" || params == "3") ops += AnsiOp.ClearScreen
                'K' -> ops += AnsiOp.EraseLine
            }
            return true
        }
        if (first == ']') {
            // OSC ... BEL or ST (ESC \)
            val l = esc[esc.length - 1]
            return l == '\u0007' || (esc.length >= 2 && esc[esc.length - 2] == '\u001b' && l == '\\') || esc.length > 512
        }
        if (first == '(' || first == ')') return esc.length >= 2
        return true // two-byte escapes (ESC =, ESC >, ESC c ...)
    }

    private fun sgr(params: String) {
        val ps = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < ps.size) {
            when (val p = ps[i]) {
                0 -> { fg = -1; bold = false }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> fg = p - 30
                in 90..97 -> fg = p - 90 + 8
                39 -> fg = -1
                38 -> { // 38;5;n or 38;2;r;g;b -> approximate to the 16-colour palette
                    if (ps.getOrNull(i + 1) == 5) { fg = (ps.getOrNull(i + 2) ?: 7).coerceIn(0, 15); i += 2 }
                    else if (ps.getOrNull(i + 1) == 2) i += 4
                }
            }
            i++
        }
    }
}
