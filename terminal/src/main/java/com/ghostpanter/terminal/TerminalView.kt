package com.ghostpanter.terminal

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * The scrollback *is* the input field: one EditText whose text is the session transcript, with
 * only the part after the prompt editable (macOS Terminal style). Long-press selects and copies
 * anywhere; typing anywhere goes to the prompt. Hardware keys: Enter, ↑/↓ history, Tab completion,
 * Ctrl-C (copy when text is selected, interrupt otherwise), Ctrl-D, Ctrl-L, Ctrl-A/E/U/W, Esc.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, val session: TermSession, private val host: Host) : EditText(context) {

    interface Host {
        fun complete(view: TerminalView)
        fun fontChanged(sp: Float)
        /** Sticky Ctrl / Alt from the extra-keys row; consumed by the next typed character. */
        fun takeCtrl(): Boolean
        fun takeAlt(): Boolean
    }

    private var fontSp = 13f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setFont(fontSp * d.scaleFactor)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { host.fontChanged(fontSp) }
    })

    init {
        setEditableFactory(object : Editable.Factory() {
            override fun newEditable(source: CharSequence): Editable = if (source === session.buf) session.buf else super.newEditable(source)
        })
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        isVerticalScrollBarEnabled = true
        setHorizontallyScrolling(false)
        setLineSpacing(0f, 1.08f)
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        bindText()
        filters = arrayOf(makeGuard())
        applyTheme(session.theme)
        setSelection(text.length)
    }

    fun applyTheme(t: TermTheme) {
        setBackgroundColor(t.bg)
        setTextColor(t.fg)
        highlightColor = t.selection
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(t.cursor); setSize((2 * resources.displayMetrics.density).toInt(), 1)
            }
        }
    }

    fun setFont(sp: Float) {
        fontSp = sp.coerceIn(FavoritesStore.MIN_SP, FavoritesStore.MAX_SP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
    }

    val font: Float get() = fontSp

    /** Columns / rows that fit (for `stty cols`). */
    fun gridSize(): Pair<Int, Int> {
        val cw = paint.measureText("M").coerceAtLeast(1f)
        val w = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - paddingLeft - paddingRight
        val h = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels / 2) - paddingTop - paddingBottom
        return (w / cw).toInt().coerceIn(20, 400) to (h / lineHeight.coerceAtLeast(1)).coerceIn(5, 200)
    }

    private fun bindText() {
        session.programmatic = true
        try { setText(session.buf, BufferType.EDITABLE) } finally { session.programmatic = false }
    }

    fun refresh() {
        if (text !== session.buf) bindText()
        val s = selectionStart; val e = selectionEnd
        if (s == e && s < session.inputStart) setSelection(session.buf.length)
        scrollToBottomSoon()
    }

    private fun scrollToBottomSoon() = post {
        val l = layout ?: return@post
        val bottom = l.height + paddingTop + paddingBottom - height
        if (bottom > 0) scrollTo(0, bottom)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // a plain tap in the read-only transcript puts the caret back at the prompt
        if (selStart == selEnd && selStart < session.inputStart && session.buf.length >= session.inputStart) {
            post { if (selectionStart == selectionEnd && selectionStart < session.inputStart) setSelection(session.buf.length) }
        }
    }

    /** Blocks edits before the prompt; turns typed newlines into Enter; applies sticky Ctrl / Alt. */
    private fun makeGuard(): InputFilter = InputFilter { source, start, end, dest, dstart, dend ->
        if (session.programmatic) return@InputFilter null
        val typed = source.subSequence(start, end).toString()
        if (dstart < session.inputStart) {
            if (typed.isNotEmpty()) post { appendAtPrompt(typed) }
            return@InputFilter dest.subSequence(dstart, dend)
        }
        if (typed.length == 1 && host.takeCtrl()) { post { ctrlKey(typed[0]) }; return@InputFilter "" }
        if (typed.length == 1 && host.takeAlt()) { post { session.backend.sendRaw(byteArrayOf(27) + typed.toByteArray()) }; return@InputFilter "" }
        val nl = typed.indexOf('\n')
        if (nl >= 0) {
            val parts = typed.split('\n')
            post {
                session.submit(parts[0])
                // multi-line paste: one command per line
                for (i in 1 until parts.size - 1) session.submit(parts[i])
                if (parts.size > 1 && parts.last().isNotEmpty()) appendAtPrompt(parts.last())
                refresh()
            }
            return@InputFilter ""
        }
        null
    }

    fun appendAtPrompt(s: String) {
        val e = text
        e.append(s.replace("\r", ""))
        setSelection(e.length)
    }

    fun ctrlKey(c: Char) {
        when (c.lowercaseChar()) {
            'c' -> session.interrupt()
            'd' -> session.eof()
            'l' -> session.clear()
            'a' -> setSelection(session.inputStart)
            'e' -> setSelection(text.length)
            'u' -> session.setInput("")
            'w' -> {
                val inp = session.currentInput().trimEnd()
                session.setInput(inp.substring(0, inp.lastIndexOf(' ').let { if (it < 0) 0 else it + 1 }))
            }
            'z' -> session.backend.sendRaw(byteArrayOf(26))
            else -> if (c.lowercaseChar() in 'a'..'z') session.backend.sendRaw(byteArrayOf((c.lowercaseChar() - 'a' + 1).toByte()))
        }
        refresh()
    }

    fun arrow(code: Int) {
        if (session.busy && session.backend.pty) {
            val seq = when (code) { KeyEvent.KEYCODE_DPAD_UP -> "A"; KeyEvent.KEYCODE_DPAD_DOWN -> "B"; KeyEvent.KEYCODE_DPAD_RIGHT -> "C"; else -> "D" }
            session.backend.sendRaw("\u001b[$seq".toByteArray()); return
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { session.historyPrev(); setSelection(text.length) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { session.historyNext(); setSelection(text.length) }
            KeyEvent.KEYCODE_DPAD_LEFT -> setSelection((selectionStart - 1).coerceAtLeast(session.inputStart))
            KeyEvent.KEYCODE_DPAD_RIGHT -> setSelection((selectionStart + 1).coerceAtMost(text.length))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        if (ctrl && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_C) return onTextContextMenuItem(android.R.id.copy)
        if (ctrl && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_V) { paste(); return true }
        if (ctrl && keyCode == KeyEvent.KEYCODE_V) { paste(); return true }
        if (ctrl && keyCode == KeyEvent.KEYCODE_C && selectionStart != selectionEnd) return onTextContextMenuItem(android.R.id.copy)
        if (ctrl && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) { ctrlKey('a' + (keyCode - KeyEvent.KEYCODE_A)); return true }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { session.submit(); refresh(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { arrow(keyCode); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!event.isShiftPressed) { arrow(keyCode); return true }
            KeyEvent.KEYCODE_TAB -> { host.complete(this); return true }
            KeyEvent.KEYCODE_ESCAPE -> { session.backend.sendRaw(byteArrayOf(27)); return true }
            KeyEvent.KEYCODE_MOVE_HOME -> { setSelection(session.inputStart); return true }
            KeyEvent.KEYCODE_MOVE_END -> { setSelection(text.length); return true }
            KeyEvent.KEYCODE_DEL -> if (selectionStart <= session.inputStart && selectionStart == selectionEnd) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (selectionStart < session.inputStart) setSelection(text.length)
        text.replace(selectionStart.coerceAtLeast(session.inputStart), selectionEnd.coerceAtLeast(session.inputStart), t)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) { paste(); return true }
        return super.onTextContextMenuItem(id)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (scale.isInProgress || event.pointerCount > 1) return true
        return super.onTouchEvent(event)
    }
}
