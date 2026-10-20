package com.ghostpanter.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Tabs + terminal + extra-keys row. Embeddable from Compose (AndroidView) and from the Java
 * scrcpy tools pane. [factories] are the shells a new tab can open (local, Root, a device...).
 */
@SuppressLint("ViewConstructor")
class TerminalPanel(
    context: Context,
    private val sessions: TermSessions,
    private val factories: () -> List<Pair<String, () -> TermBackend>>,
    private var theme: TermTheme,
) : LinearLayout(context), TerminalView.Host {

    private val store = FavoritesStore(context)
    private val tabsRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tabsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) }
    private val topBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val stack = FrameLayout(context)
    private val keys = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val keysScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(keys) }
    private val views = HashMap<TermSession, TerminalView>()
    private var ctrl = false
    private var alt = false
    private var ctrlKey: TextView? = null
    private var altKey: TextView? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "term-complete").apply { isDaemon = true } }
    private val dp = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        topBar.addView(tabsScroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(barButton("+", context.getString(R.string.term_new_tab)) { newTabMenu(it) })
        topBar.addView(barButton("★", context.getString(R.string.term_favorites)) { showFavorites() })
        topBar.addView(barButton("⋯", context.getString(R.string.term_more)) { moreMenu(it) })
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, (40 * dp).toInt()))
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(keysScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        buildKeys()
        applyTheme(theme)
        if (sessions.sessions.isEmpty()) factories().firstOrNull()?.let { (_, f) -> openSession(f()) }
        rebuild()
    }

    fun applyTheme(t: TermTheme) {
        theme = t
        setBackgroundColor(t.bg)
        topBar.setBackgroundColor(t.barBg)
        keysScroll.setBackgroundColor(t.barBg)
        sessions.sessions.forEach { it.theme = t }
        views.values.forEach { it.applyTheme(t) }
        rebuildTabs(); buildKeys()
        keysScroll.visibility = if (resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY) View.GONE else View.VISIBLE
    }

    fun openSession(backend: TermBackend) {
        val s = TermSession(backend, theme)
        sessions.add(s)
        rebuild()
    }

    /** Opens a new tab running [backend] and focuses it. */
    fun openAndFocus(backend: TermBackend) { openSession(backend); current()?.requestFocus() }

    private fun current(): TerminalView? = sessions.sessions.getOrNull(sessions.current)?.let { views[it] }

    private fun rebuild() {
        // drop views of closed sessions
        views.keys.filter { it !in sessions.sessions }.forEach { stack.removeView(views.remove(it)) }
        sessions.sessions.forEachIndexed { i, s ->
            val v = views.getOrPut(s) {
                TerminalView(context, s, this).also { tv ->
                    tv.setFont(store.fontSp)
                    stack.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    s.onChanged = { tv.refresh() }
                    s.onTitle = { rebuildTabs(); tv.refresh() }
                    tv.post { val (c, r) = tv.gridSize(); s.ensureStarted(c, r) }
                }
            }
            v.visibility = if (i == sessions.current) View.VISIBLE else View.GONE
        }
        rebuildTabs()
        current()?.let { it.post { it.refresh() } }
    }

    private fun rebuildTabs() {
        tabsRow.removeAllViews()
        sessions.sessions.forEachIndexed { i, s ->
            val active = i == sessions.current
            val tv = TextView(context).apply {
                text = s.title + if (s.exited) " ✕" else ""
                textSize = 13f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (active) theme.fg else theme.dim)
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(if (active) theme.tabActive else 0) }
                setOnClickListener { sessions.current = i; rebuild() }
                setOnLongClickListener { v -> tabMenu(v, i); true }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins((4 * dp).toInt(), 0, 0, 0) }
            tabsRow.addView(tv, lp)
        }
    }

    private fun tabMenu(anchor: View, i: Int) {
        PopupMenu(context, anchor).apply {
            menu.add(context.getString(R.string.term_close_tab)).setOnMenuItemClickListener { sessions.remove(i); if (sessions.sessions.isEmpty()) factories().firstOrNull()?.let { (_, f) -> sessions.add(TermSession(f(), theme)) }; rebuild(); true }
            show()
        }
    }

    private fun barButton(label: String, desc: String, onClick: (View) -> Unit) = TextView(context).apply {
        text = label
        contentDescription = desc
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(theme.fg)
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        setOnClickListener { onClick(it) }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    }

    private fun newTabMenu(anchor: View) {
        val list = factories()
        if (list.isEmpty()) { toast(context.getString(R.string.term_no_backends)); return }
        if (list.size == 1) { openAndFocus(list[0].second()); return }
        PopupMenu(context, anchor).apply {
            list.forEach { (label, f) -> menu.add(label).setOnMenuItemClickListener { openAndFocus(f()); true } }
            show()
        }
    }

    private fun moreMenu(anchor: View) {
        val tv = current()
        PopupMenu(context, anchor).apply {
            menu.add(context.getString(R.string.term_font_bigger)).setOnMenuItemClickListener { tv?.let { it.setFont(it.font + 1); fontChanged(it.font) }; true }
            menu.add(context.getString(R.string.term_font_smaller)).setOnMenuItemClickListener { tv?.let { it.setFont(it.font - 1); fontChanged(it.font) }; true }
            menu.add(context.getString(R.string.term_clear)).setOnMenuItemClickListener { tv?.session?.clear(); true }
            menu.add(context.getString(R.string.term_interrupt)).setOnMenuItemClickListener { tv?.session?.interrupt(); true }
            menu.add(context.getString(R.string.term_paste)).setOnMenuItemClickListener { tv?.paste(); true }
            menu.add(context.getString(R.string.term_copy_all)).setOnMenuItemClickListener {
                tv?.let { copy(it.session.buf.toString()) }; true
            }
            menu.add(context.getString(R.string.term_close_tab)).setOnMenuItemClickListener { tabMenuClose(); true }
            show()
        }
    }

    private fun tabMenuClose() {
        sessions.remove(sessions.current)
        if (sessions.sessions.isEmpty()) factories().firstOrNull()?.let { (_, f) -> sessions.add(TermSession(f(), theme)) }
        rebuild()
    }

    // ---- extra keys ----
    private fun buildKeys() {
        keys.removeAllViews()
        fun key(label: String, sticky: Boolean = false, action: (TerminalView) -> Unit): TextView = TextView(context).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(theme.keyFg)
            minWidth = (44 * dp).toInt()
            setPadding((10 * dp).toInt(), (9 * dp).toInt(), (10 * dp).toInt(), (9 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(theme.keyBg) }
            setOnClickListener { current()?.let(action) }
            keys.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins((3 * dp).toInt(), (4 * dp).toInt(), (3 * dp).toInt(), (4 * dp).toInt()) })
        }
        key("Esc") { it.session.backend.sendRaw(byteArrayOf(27)) }
        key("Tab") { complete(it) }
        ctrlKey = key("Ctrl", true) { ctrl = !ctrl; markSticky() }
        altKey = key("Alt", true) { alt = !alt; markSticky() }
        key("↑") { it.arrow(KeyEvent.KEYCODE_DPAD_UP) }
        key("↓") { it.arrow(KeyEvent.KEYCODE_DPAD_DOWN) }
        key("←") { it.arrow(KeyEvent.KEYCODE_DPAD_LEFT) }
        key("→") { it.arrow(KeyEvent.KEYCODE_DPAD_RIGHT) }
        key("^C") { it.ctrlKey('c') }
        key("^D") { it.ctrlKey('d') }
        for (c in listOf("|", "/", "-", "~", "*", ">", "&", ";")) key(c) { it.appendAtPrompt(c) }
        key("⌨") { v ->
            v.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
        }
        markSticky()
    }

    private fun markSticky() {
        fun paint(v: TextView?, on: Boolean) {
            v ?: return
            (v.background as? GradientDrawable)?.setColor(if (on) theme.promptDir else theme.keyBg)
            v.setTextColor(if (on) theme.bg else theme.keyFg)
        }
        paint(ctrlKey, ctrl); paint(altKey, alt)
    }

    override fun takeCtrl(): Boolean { if (!ctrl) return false; ctrl = false; post { markSticky() }; return true }
    override fun takeAlt(): Boolean { if (!alt) return false; alt = false; post { markSticky() }; return true }

    override fun fontChanged(sp: Float) {
        store.fontSp = sp
        views.values.forEach { if (it.font != sp) it.setFont(sp) }
    }

    // ---- Tab completion ----
    override fun complete(view: TerminalView) {
        val s = view.session
        val input = s.currentInput()
        val word = input.substringAfterLast(' ')
        val command = !input.trimStart().contains(' ')
        val cwd = s.cwd
        io.execute {
            val cands = runCatching { s.backend.complete(cwd, word, command) }.getOrDefault(emptyList())
            view.post {
                if (s.currentInput() != input) return@post
                when {
                    cands.isEmpty() -> Unit
                    cands.size == 1 -> {
                        val c = cands[0]
                        s.setInput(input.dropLast(word.length) + c + if (c.endsWith("/")) "" else " ")
                    }
                    else -> {
                        val common = cands.reduce { a, b -> a.commonPrefixWith(b) }
                        if (common.length > word.length) s.setInput(input.dropLast(word.length) + common)
                        else s.showList(cands.map { it.trimEnd('/').substringAfterLast('/') + if (it.endsWith('/')) "/" else "" }.take(120))
                    }
                }
                view.setSelection(view.text.length)
                view.refresh()
            }
        }
    }

    // ---- favorites ----
    private fun showFavorites() {
        val tv = current()
        val list = store.load().toMutableList()
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), 0) }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.term_favorites)
            .setView(android.widget.ScrollView(context).apply { addView(box) })
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.term_fav_add) { _, _ -> addFavorite(tv?.session?.currentInput().orEmpty()) }
            .setPositiveButton(R.string.term_more) { _, _ -> favoritesIo() }
            .create()
        if (list.isEmpty()) box.addView(TextView(context).apply { setText(R.string.term_fav_empty); setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt()) })
        list.forEachIndexed { i, f ->
            val row = LinearLayout(context).apply { orientation = VERTICAL; setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt()) }
            row.addView(TextView(context).apply { text = f.name; textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
            row.addView(TextView(context).apply { text = f.command; textSize = 12f; typeface = Typeface.MONOSPACE })
            row.setOnClickListener {
                dialog.dismiss()
                tv?.let { v -> v.session.setInput(f.command); v.session.submit(); v.refresh() }
            }
            row.setOnLongClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    menu.add(context.getString(R.string.term_fav_insert)).setOnMenuItemClickListener { dialog.dismiss(); tv?.let { v -> v.session.setInput(f.command); v.setSelection(v.text.length) }; true }
                    menu.add(context.getString(R.string.term_fav_delete)).setOnMenuItemClickListener { list.removeAt(i); store.save(list); dialog.dismiss(); toast(context.getString(R.string.term_fav_deleted)); true }
                    show()
                }
                true
            }
            box.addView(row)
        }
        dialog.show()
    }

    private fun addFavorite(cmd: String) {
        val name = EditText(context).apply { hint = context.getString(R.string.term_fav_name); setText(cmd.take(30)) }
        val command = EditText(context).apply { hint = context.getString(R.string.term_fav_command); setText(cmd); typeface = Typeface.MONOSPACE; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0); addView(name); addView(command) }
        AlertDialog.Builder(context).setTitle(R.string.term_fav_add).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val c = command.text.toString().trim()
                if (c.isNotEmpty()) store.save(store.load() + Favorite(name.text.toString().trim().ifEmpty { c.take(30) }, c))
            }.show()
    }

    private fun exportFile(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GhostFiles/terminal-favorites.json")

    private fun favoritesIo() {
        val items = arrayOf(context.getString(R.string.term_fav_import), context.getString(R.string.term_fav_import_file), context.getString(R.string.term_fav_export))
        AlertDialog.Builder(context).setTitle(R.string.term_favorites).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    importText(t)
                }
                1 -> importText(runCatching { exportFile().readText() }.getOrDefault(""))
                2 -> {
                    val json = FavoritesCodec.encode(store.load())
                    copy(json)
                    val f = exportFile()
                    runCatching { f.parentFile?.mkdirs(); f.writeText(json) }
                    toast(context.getString(R.string.term_fav_exported, f.path))
                }
            }
        }.show()
    }

    private fun importText(t: String) {
        val add = FavoritesCodec.decode(t)
        val merged = FavoritesCodec.merge(store.load(), add)
        store.save(merged)
        toast(context.getString(R.string.term_fav_imported, merged.size - (merged.size - add.size).coerceAtLeast(0)))
    }

    private fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", text))
        toast(context.getString(R.string.term_copied))
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // sessions keep running in the registry; views are rebuilt on next attach
        sessions.sessions.forEach { it.onChanged = null; it.onTitle = null }
    }

    companion object {
        fun matchParent() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
