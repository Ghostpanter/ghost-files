package com.ghostpanter.terminal

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * One terminal tab: the transcript (an Editable shared with the view), the editable input region
 * after the prompt, history and the backend. Output is applied on the main thread. Lives in a
 * [TermSessions] registry so tabs survive leaving the screen.
 */
class TermSession(val backend: TermBackend, var theme: TermTheme) {
    val buf = SpannableStringBuilder()
    /** Start of the editable input (after the current prompt). Everything before is read-only. */
    var inputStart = 0
        internal set
    /** Start of the current prompt line (kept by Ctrl-L). */
    private var promptStart = 0
    /** Lower bound for CR/BS edits (never touch an earlier prompt). */
    private var outputFloor = 0
    var cwd = "/"
        private set
    var lastRc = 0
        private set
    /** A command is running (between Enter and the next prompt record). */
    var busy = false
        private set
    var exited = false
        private set
    /** Our own edits pass the view's input filter. */
    @Volatile internal var programmatic = false
    private var started = false
    private var promptSeen = false
    private val history = ArrayList<String>()
    private var histIdx = -1
    private var crPending = false
    private val main = Handler(Looper.getMainLooper())
    private val parser = TermParser()
    private val ansi = AnsiDecoder()
    var onChanged: (() -> Unit)? = null
    var onTitle: (() -> Unit)? = null

    val title: String get() = backend.label

    fun ensureStarted(cols: Int, rows: Int) {
        if (started) return
        started = true
        busy = true
        Thread {
            runCatching {
                if (backend.user.isEmpty()) backend.user = backend.exec("id -un 2>/dev/null || echo shell").trim().lines().firstOrNull().orEmpty().ifEmpty { "shell" }
            }
            main.post { onTitle?.invoke() }
        }.start()
        // Shells that ignore PS1 still get a usable terminal: show output after a short grace period.
        main.postDelayed({ if (!promptSeen && !exited) prompt(0, cwd) }, 4000)
        try {
            backend.start(object : TermSink {
                override fun onOutput(text: String) { main.post { feed(text) } }
                override fun onExit(reason: String?) { main.post { exitedWith(reason) } }
            }, cols, rows)
        } catch (e: Exception) {
            exitedWith(e.message ?: e.javaClass.simpleName)
        }
    }

    private inline fun edit(block: () -> Unit) {
        programmatic = true
        try { block() } finally { programmatic = false }
    }

    /** Feeds backend output synchronously (screenshots / tests). */
    fun feedNow(text: String) = feed(text)

    private fun feed(text: String) {
        for (ev in parser.feed(text)) when (ev) {
            is TermParser.Event.Pid -> (backend as? StreamShellBackend)?.onPid(ev.pid)
            is TermParser.Event.Prompt -> prompt(ev.rc, ev.cwd)
            is TermParser.Event.Text -> if (promptSeen) output(ev.text)
        }
        trim()
        onChanged?.invoke()
    }

    /** Inserts program output before the pending input. */
    private fun output(text: String) {
        edit {
            for (op in ansi.decode(text)) when (op) {
                is AnsiOp.Write -> {
                    if (crPending) { crPending = false; eraseCurrentLine() }
                    insertStyled(op.run.text, color(op.run.fg), op.run.bold)
                }
                AnsiOp.CarriageReturn -> crPending = true
                AnsiOp.EraseLine -> if (crPending) { crPending = false; eraseCurrentLine() }
                AnsiOp.Backspace -> if (inputStart > outputFloor && buf[inputStart - 1] != '\n') { buf.delete(inputStart - 1, inputStart); inputStart-- }
                AnsiOp.ClearScreen -> clearAbove()
            }
        }
    }

    private fun color(idx: Int): Int? = if (idx in 0..15) theme.palette[idx] else null

    private fun insertStyled(s: String, fg: Int?, bold: Boolean) {
        if (s.isEmpty()) return
        val at = inputStart
        buf.insert(at, s)
        if (fg != null) buf.setSpan(ForegroundColorSpan(fg), at, at + s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) buf.setSpan(StyleSpan(Typeface.BOLD), at, at + s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        inputStart += s.length
    }

    private fun eraseCurrentLine() {
        var ls = inputStart
        while (ls > outputFloor && buf[ls - 1] != '\n') ls--
        if (ls < inputStart) { buf.delete(ls, inputStart); inputStart = ls }
    }

    private fun prompt(rc: Int, dir: String) {
        lastRc = rc
        cwd = dir
        busy = false
        crPending = false
        val firstPrompt = !promptSeen
        promptSeen = true
        edit {
            // keep whatever the user typed meanwhile after the new prompt
            val pendingInput = buf.subSequence(inputStart, buf.length).toString()
            buf.delete(inputStart, buf.length)
            if (inputStart > 0 && buf[inputStart - 1] != '\n') insertStyled("\n", null, false)
            if (firstPrompt) {
                insertStyled(banner(), theme.dim, false)
            }
            promptStart = inputStart
            insertPrompt()
            outputFloor = inputStart
            buf.append(pendingInput)
        }
        onChanged?.invoke()
    }

    private fun banner(): String = ""

    fun promptText(): Triple<String, String, String> {
        val u = backend.user.ifEmpty { "shell" }
        val h = backend.host.ifEmpty { "localhost" }
        val dir = if (cwd == "/") "/" else cwd.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        val sym = if (u == "root") "#" else "%"
        return Triple("$u@$h", dir, sym)
    }

    private fun insertPrompt() {
        val (uh, dir, sym) = promptText()
        insertStyled(uh, theme.promptUser, true)
        insertStyled(" ", null, false)
        insertStyled(dir, theme.promptDir, true)
        insertStyled(" $sym ", null, false)
    }

    private fun exitedWith(reason: String?) {
        if (exited) return
        exited = true
        busy = false
        edit {
            val pending = buf.subSequence(inputStart, buf.length).toString()
            buf.delete(inputStart, buf.length)
            if (inputStart > 0 && buf[inputStart - 1] != '\n') insertStyled("\n", null, false)
            insertStyled("[" + (reason?.let { "$it · " } ?: "") + "process exited]\n", theme.dim, false)
            outputFloor = inputStart
            buf.append(pending)
        }
        onChanged?.invoke()
    }

    // ---- user actions (main thread) ----

    fun currentInput(): String = buf.subSequence(inputStart, buf.length).toString()

    fun setInput(s: String) = edit { buf.replace(inputStart, buf.length, s) }

    fun submit(extra: String = "") {
        val line = currentInput() + extra
        edit {
            buf.replace(inputStart, buf.length, line)
            buf.append("\n")
            inputStart = buf.length
            outputFloor = inputStart
        }
        if (line.isNotBlank() && !busy) {
            if (history.lastOrNull() != line) history += line
            if (history.size > 500) history.removeAt(0)
        }
        histIdx = -1
        if (exited) return
        if (!busy && line.isBlank() && promptSeen) {
            // empty Enter: just a fresh prompt
            edit { promptStart = inputStart; insertPrompt(); outputFloor = inputStart }
            onChanged?.invoke()
            return
        }
        if (busy) {
            // a program is reading stdin (read, su password, a REPL): pass the line through as-is
            backend.sendRaw((line + "\n").toByteArray())
        } else {
            busy = true
            backend.sendLine(line)
        }
        onChanged?.invoke()
    }

    fun historyPrev() {
        if (history.isEmpty()) return
        histIdx = if (histIdx < 0) history.size - 1 else (histIdx - 1).coerceAtLeast(0)
        setInput(history[histIdx])
    }

    fun historyNext() {
        if (histIdx < 0) return
        histIdx++
        if (histIdx >= history.size) { histIdx = -1; setInput("") } else setInput(history[histIdx])
    }

    fun interrupt() {
        if (busy) backend.interrupt() else edit {
            buf.append("^C\n"); inputStart = buf.length
            promptStart = inputStart; insertPrompt(); outputFloor = inputStart
        }
        onChanged?.invoke()
    }

    fun eof() {
        if (currentInput().isEmpty()) backend.eof()
    }

    fun clear() {
        edit {
            val keepFrom = if (busy) inputStart else promptStart
            buf.delete(0, keepFrom)
            inputStart -= keepFrom; promptStart = (promptStart - keepFrom).coerceAtLeast(0); outputFloor = (outputFloor - keepFrom).coerceAtLeast(0)
        }
        onChanged?.invoke()
    }

    private fun clearAbove() {
        val cut = run { var ls = inputStart; while (ls > 0 && buf[ls - 1] != '\n') ls--; ls }
        buf.delete(0, cut)
        inputStart -= cut; promptStart = 0; outputFloor = 0
    }

    /** Prints [lines] (completion candidates) followed by a fresh prompt with the same input. */
    fun showList(lines: List<String>) {
        if (lines.isEmpty()) return
        edit {
            val pending = currentInput()
            buf.delete(inputStart, buf.length)
            insertStyled("\n" + lines.joinToString("  ") + "\n", theme.dim, false)
            promptStart = inputStart
            insertPrompt()
            outputFloor = inputStart
            buf.append(pending)
        }
        onChanged?.invoke()
    }

    fun close() { backend.close() }

    private fun trim() {
        if (buf.length <= MAX_CHARS) return
        var cut = buf.length - KEEP_CHARS
        while (cut < inputStart && buf[cut] != '\n') cut++
        if (cut >= inputStart) return
        edit { buf.delete(0, cut + 1) }
        val d = cut + 1
        inputStart -= d; promptStart = (promptStart - d).coerceAtLeast(0); outputFloor = (outputFloor - d).coerceAtLeast(0)
    }

    companion object {
        const val MAX_CHARS = 400_000
        const val KEEP_CHARS = 250_000
    }
}

/** Process-wide tab registry (tabs keep running while the terminal screen is closed). */
class TermSessions {
    val sessions = ArrayList<TermSession>()
    var current = 0
    fun add(s: TermSession): Int { sessions += s; current = sessions.size - 1; return current }
    fun remove(i: Int) {
        if (i !in sessions.indices) return
        sessions.removeAt(i).close()
        current = current.coerceAtMost(sessions.size - 1).coerceAtLeast(0)
    }
}
