package com.ghostpanter.files.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostpanter.files.GhostApp
import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.ops.ConfigBackup
import com.ghostpanter.files.core.ops.RecycleBin
import com.ghostpanter.files.core.transfer.ConflictPolicy
import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.core.transfer.Loc
import com.ghostpanter.files.core.transfer.TransferOp
import com.ghostpanter.files.data.Bookmark
import com.ghostpanter.files.data.ViewPrefs
import com.ghostpanter.files.fs.FsManager
import com.ghostpanter.files.t
import com.ghostpanter.files.transfer.TransferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Clip(val op: TransferOp, val fsId: String, val entries: List<FileEntry>)

data class PendingTransfer(
    val op: TransferOp,
    val sources: List<Loc>,
    val dest: Loc,
    val destLabel: String,
    val names: List<String>,
    val conflicts: List<String>,
    val checking: Boolean = true,
)

sealed interface Screen {
    data object Main : Screen
    data object Transfers : Screen
    data object Trash : Screen
    data class Editor(val fsId: String, val path: String) : Screen
    data class Image(val fsId: String, val path: String, val siblings: List<String>) : Screen
    data class Apk(val file: File, val label: String) : Screen
    data class Search(val fsId: String, val root: String) : Screen
    data object LanScan : Screen
    data object Servers : Screen
    data class Hex(val fsId: String, val path: String) : Screen
    data object Apps : Screen
    data class Player(val fsId: String, val path: String, val siblings: List<String>, val audioOnly: Boolean = false) : Screen
    data class ContentSearch(val fsId: String, val root: String) : Screen
    data class Compare(val leftFs: String, val leftPath: String, val rightFs: String, val rightPath: String) : Screen
}

sealed interface UiEvent {
    data class Toast(val text: String) : UiEvent
    data class OpenFile(val file: File, val mime: String, val chooser: Boolean) : UiEvent
    data class Share(val files: List<File>) : UiEvent
    data object RequestAllFiles : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app), PaneHost {
    val g = GhostApp.graph
    val fsm: FsManager get() = g.fs
    override val panes = listOf(PaneState(0), PaneState(1))
    override var activePane by mutableIntStateOf(0)
    override val prefs: ViewPrefs get() = g.settings
    var dialog by mutableStateOf<DialogState?>(null)
    var menu by mutableStateOf<ActionMenu?>(null)
    var pathJump by mutableStateOf<PaneState?>(null)
    var clip by mutableStateOf<Clip?>(null)
    var screen by mutableStateOf<Screen>(Screen.Main)
    var pendingTransfer by mutableStateOf<PendingTransfer?>(null)
    var busy by mutableStateOf<String?>(null)
    private var busyJob: Job? = null

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

    val lan by lazy { com.ghostpanter.files.lan.LanScanner(getApplication(), viewModelScope) }

    val trash: RecycleBin by lazy { RecycleBin(fsm.local, fsm.storageRoot + "/.GhostTrash") }

    init {
        for (p in panes) {
            val (tabs, active) = g.settings.loadTabs(p.index)
            if (tabs.isEmpty()) p.tabs.add(TabState(LocalFs.ID, fsm.storageRoot))
            else tabs.forEach { (fs, path) -> p.tabs.add(TabState(fs, path)) }
            p.activeTab = active.coerceIn(0, p.tabs.size - 1)
            p.tabs.forEach { refresh(it) }
        }
        viewModelScope.launch {
            g.transfers.finished.collect { job ->
                val msg = when (job.state) {
                    JobState.COMPLETED -> t("${job.title} 完成", "${job.title} done")
                    JobState.FAILED -> t("${job.title} 失败: ${job.error}", "${job.title} failed: ${job.error}")
                    else -> null
                }
                msg?.let { toast(it) }
                val touched = job.sources.map { it.fsId }.toSet() + job.dest.fsId
                panes.forEach { p -> p.tabs.forEach { if (it.fsId in touched) refresh(it, keepScroll = true) } }
            }
        }
    }

    fun toast(s: String) { _events.tryEmit(UiEvent.Toast(s)) }

    fun pane(i: Int) = panes[i]
    fun other(p: PaneState) = panes[1 - p.index]
    val activeTab: TabState get() = panes[activePane].tab

    fun fs(tab: TabState): VirtualFs = fsm.get(tab.fsId)

    fun saveTabs() {
        panes.forEach { p -> g.settings.saveTabs(p.index, p.tabs.map { it.fsId to it.path }, p.activeTab) }
    }

    // ---------------------------------------------------------------- navigation

    fun navigate(tab: TabState, fsId: String, path: String, push: Boolean = true) {
        if (push && (tab.fsId != fsId || tab.path != path)) {
            tab.history.addLast(tab.fsId to tab.path)
            if (tab.history.size > 100) tab.history.removeFirst()
            tab.forward.clear()
        }
        tab.multiSelect = false
        tab.scroll[tab.fsId + tab.path] = tab.listState.firstVisibleItemIndex to tab.listState.firstVisibleItemScrollOffset
        val oldPath = tab.path
        tab.fsId = fsId
        tab.path = VPath.normalize(path)
        tab.selected = emptySet()
        tab.filter = ""
        tab.highlight = if (VPath.parent(oldPath) == tab.path) oldPath else null
        val saved = tab.scroll[fsId + tab.path]
        tab.listState = LazyListState(saved?.first ?: 0, saved?.second ?: 0)
        refresh(tab)
        saveTabs()
    }

    fun refresh(tab: TabState, keepScroll: Boolean = false) {
        tab.loadJob?.cancel()
        val fsId = tab.fsId
        val path = tab.path
        tab.loading = true
        tab.error = null
        tab.loadJob = viewModelScope.launch {
            val acc = ArrayList<FileEntry>()
            var first = true
            try {
                val list = withContext(Dispatchers.IO) {
                    fsm.get(fsId).list(path) { chunk ->
                        // Incremental display for huge directories.
                        acc.addAll(chunk)
                        val snapshot = ArrayList(acc)
                        if (first || snapshot.size % 2000 < chunk.size) {
                            first = false
                            viewModelScope.launch { if (tab.path == path && tab.loading) tab.entries = snapshot }
                        }
                    }
                }
                if (tab.path == path && tab.fsId == fsId) {
                    tab.entries = list
                    tab.selected = tab.selected.filter { s -> list.any { it.path == s } }.toSet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (tab.path == path) {
                    tab.entries = emptyList()
                    tab.error = e.message ?: e.javaClass.simpleName
                }
            } finally {
                if (tab.path == path) tab.loading = false
            }
        }
    }

    fun up(tab: TabState): Boolean {
        if (tab.path == VPath.ROOT) return false
        navigate(tab, tab.fsId, VPath.parent(tab.path))
        return true
    }

    fun back(tab: TabState): Boolean {
        val prev = tab.history.removeLastOrNull() ?: return up(tab)
        tab.forward.addLast(tab.fsId to tab.path)
        navigate(tab, prev.first, prev.second, push = false)
        return true
    }

    fun forward(tab: TabState): Boolean {
        val next = tab.forward.removeLastOrNull() ?: return false
        tab.history.addLast(tab.fsId to tab.path)
        navigate(tab, next.first, next.second, push = false)
        return true
    }

    /** MT "同步": the other pane jumps to the directory shown in [p]. */
    fun syncOther(p: PaneState) {
        val src = p.tab
        navigate(other(p).tab, src.fsId, src.path)
        toast(t("另一窗口已同步到此目录", "Other pane synced"))
    }

    /** MT "交换窗口": swaps the tab stacks of the two panes. */
    fun swapPanes() {
        val a = panes[0]; val b = panes[1]
        val ta = a.tabs.toList(); val tb = b.tabs.toList()
        val ia = a.activeTab; val ib = b.activeTab
        a.tabs.clear(); a.tabs.addAll(tb); a.activeTab = ib
        b.tabs.clear(); b.tabs.addAll(ta); b.activeTab = ia
        saveTabs()
    }

    // ---------------------------------------------------------------- PaneHost

    override fun fsLabel(fsId: String) = fsm.label(fsId)

    override fun onEntryClick(pane: PaneState, e: FileEntry) {
        activePane = pane.index
        val tab = pane.tab
        if (tab.selecting) toggle(tab, e) else openEntry(tab, e)
    }

    override fun onEntryLongPress(pane: PaneState, e: FileEntry) {
        activePane = pane.index
        val tab = pane.tab
        val targets = if (e.path in tab.selected) tab.selectedEntries() else listOf(e)
        menu = buildEntryMenu(pane, targets)
    }

    override fun onEntrySwipe(pane: PaneState, e: FileEntry) {
        activePane = pane.index
        pane.tab.multiSelect = true
        toggle(pane.tab, e)
    }

    override fun navigateTo(pane: PaneState, path: String) {
        activePane = pane.index
        navigate(pane.tab, pane.tab.fsId, path)
    }

    override fun onPathLongPress(pane: PaneState) { activePane = pane.index; pathJump = pane }

    override fun selectTab(pane: PaneState, i: Int) { pane.activeTab = i; activePane = pane.index; saveTabs() }

    override fun reload(tab: TabState) = refresh(tab)

    fun newTab(p: PaneState) {
        val cur = p.tab
        p.tabs.add(TabState(cur.fsId, cur.path).also { refresh(it) })
        p.activeTab = p.tabs.size - 1
        saveTabs()
    }

    override fun closeTab(pane: PaneState, i: Int) {
        val p = pane
        if (p.tabs.size <= 1) return
        p.tabs.removeAt(i)
        p.activeTab = p.activeTab.coerceIn(0, p.tabs.size - 1)
        saveTabs()
    }

    /** Opens a backend at its home in the active tab (root/Shizuku permission handled by caller). */
    fun openFs(fsId: String, path: String? = null) {
        val target = path ?: runCatching { fsm.get(fsId).homePath }.getOrElse { toast(it.message ?: "error"); return }
        screen = Screen.Main
        navigate(activeTab, fsId, target)
    }

    // ---------------------------------------------------------------- selection

    fun toggle(tab: TabState, e: FileEntry) {
        tab.selected = if (e.path in tab.selected) tab.selected - e.path else tab.selected + e.path
    }

    fun selectAll(tab: TabState, visible: List<FileEntry>) { tab.selected = visible.map { it.path }.toSet() }
    fun invertSelection(tab: TabState, visible: List<FileEntry>) {
        tab.selected = visible.map { it.path }.filterNot { it in tab.selected }.toSet()
    }
    fun clearSelection(tab: TabState) { tab.selected = emptySet(); tab.multiSelect = false }

    /** MT "类选": adds every visible entry whose type (extension / folder) matches something already selected. */
    fun selectSameType(tab: TabState, visible: List<FileEntry>) {
        val sel = tab.selectedEntries()
        if (sel.isEmpty()) { toast(t("先选择一个文件作为类型样本", "Select a sample file first")); return }
        val dirs = sel.any { it.isDirectory }
        val exts = sel.filter { !it.isDirectory }.map { it.extension }.toSet()
        tab.selected = tab.selected + visible.filter { if (it.isDirectory) dirs else it.extension in exts }.map { it.path }
    }

    // ---------------------------------------------------------------- transfers

    /** MT style: copy/move selection of [p] into the directory shown in the other pane. */
    fun transferToOther(p: PaneState, op: TransferOp, items: List<FileEntry> = p.tab.selectedEntries()) {
        val src = p.tab
        val dst = other(p).tab
        if (items.isEmpty()) { toast(t("未选择文件", "Nothing selected")); return }
        if (src.fsId == dst.fsId && VPath.normalize(src.path) == VPath.normalize(dst.path)) {
            toast(t("两个窗口是同一目录，请先在另一窗口打开目标目录", "Both panes show the same folder")); return
        }
        if (src.fsId == dst.fsId && items.any { it.isDirectory && VPath.isSameOrDescendant(it.path, dst.path) }) {
            toast(t("不能复制/移动到自身的子目录", "Cannot copy into itself")); return
        }
        startTransfer(op, src.fsId, items, dst.fsId, dst.path)
        clearSelection(src)
    }

    fun setClip(tab: TabState, op: TransferOp) {
        val items = tab.selectedEntries()
        if (items.isEmpty()) return
        clip = Clip(op, tab.fsId, items)
        clearSelection(tab)
        toast(t("已${if (op == TransferOp.COPY) "复制" else "剪切"} ${items.size} 项，到目标目录点粘贴", "${items.size} item(s) on clipboard"))
    }

    fun paste(tab: TabState) {
        val c = clip ?: return
        startTransfer(c.op, c.fsId, c.entries, tab.fsId, tab.path)
        if (c.op == TransferOp.MOVE) clip = null
    }

    private fun startTransfer(op: TransferOp, srcFs: String, items: List<FileEntry>, dstFs: String, dstPath: String) {
        val sources = items.map { Loc(srcFs, it.path) }
        val dest = Loc(dstFs, dstPath)
        val sameDir = srcFs == dstFs && items.all { VPath.parent(it.path) == VPath.normalize(dstPath) }
        if (sameDir) { enqueue(op, sources, dest, ConflictPolicy.RENAME); return }
        val label = fsm.label(dstFs) + "  " + dstPath
        val names = items.map { it.name }
        // Show the confirm dialog at once (MT style, with the target path); conflicts fill in when known.
        pendingTransfer = PendingTransfer(op, sources, dest, label, names, emptyList())
        viewModelScope.launch {
            val existing = runCatching {
                withContext(Dispatchers.IO) {
                    // Only stat the names involved: listing a huge remote dir just for this is wasteful.
                    val fs = fsm.get(dstFs)
                    if (names.size <= 64) names.filter { fs.stat(VPath.join(dstPath, it)) != null }.toSet()
                    else fs.list(dstPath).map { it.name }.toSet()
                }
            }.getOrDefault(emptySet())
            val conflicts = names.filter { it in existing }
            val cur = pendingTransfer
            if (cur != null && cur.sources == sources && cur.dest == dest) pendingTransfer = cur.copy(conflicts = conflicts, checking = false)
        }
    }

    fun confirmTransfer(policy: ConflictPolicy?) {
        val p = pendingTransfer ?: return
        pendingTransfer = null
        if (policy != null) enqueue(p.op, p.sources, p.dest, policy)
    }

    private fun enqueue(op: TransferOp, sources: List<Loc>, dest: Loc, policy: ConflictPolicy) {
        g.transfers.enqueue(op, sources, dest, policy)
        TransferService.start(getApplication())
        toast(t("已加入传输队列（后台进行，可在通知栏/传输页查看）", "Queued in background"))
    }

    // ---------------------------------------------------------------- file ops

    private fun runBusy(label: String, refreshTabs: List<TabState>, block: suspend () -> Unit) {
        busyJob?.cancel()
        busy = label
        busyJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                toast(t("已取消", "Cancelled"))
            } catch (e: Exception) {
                toast(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = null
                refreshTabs.forEach { refresh(it, keepScroll = true) }
            }
        }
    }

    fun cancelBusy() { busyJob?.cancel(); busy = null }

    private fun tabsShowing(fsId: String) = panes.flatMap { it.tabs }.filter { it.fsId == fsId }

    fun delete(tab: TabState, entries: List<FileEntry>, useTrash: Boolean) {
        val fs = fs(tab)
        tab.selected = emptySet()
        runBusy(t("正在删除…", "Deleting…"), tabsShowing(tab.fsId)) {
            for (e in entries) {
                if (useTrash && fs.id == LocalFs.ID && !e.path.startsWith(trash.trashDir)) trash.moveToTrash(e.path)
                else fs.delete(e.path, true)
            }
        }
    }

    fun rename(tab: TabState, e: FileEntry, newName: String) {
        val name = newName.trim()
        if (!validName(name)) { toast(t("名称无效", "Invalid name")); return }
        if (name == e.name) return
        val fs = fs(tab)
        runBusy(t("重命名…", "Renaming…"), listOf(tab)) {
            val target = VPath.join(VPath.parent(e.path), name)
            // Case-only rename ("a.jpg" -> "A.jpg") must work on case-insensitive storage.
            if (!name.equals(e.name, ignoreCase = true) && fs.stat(target) != null) throw FsException(t("已存在同名文件", "Name already exists"))
            fs.rename(e.path, target)
        }
        tab.selected = emptySet()
    }

    fun create(tab: TabState, name: String, dir: Boolean) {
        if (!validName(name.trim())) { toast(t("名称无效", "Invalid name")); return }
        val fs = fs(tab)
        runBusy(t("创建中…", "Creating…"), listOf(tab)) {
            val p = VPath.join(tab.path, name)
            if (fs.stat(p) != null) throw FsException(t("已存在", "Already exists"))
            if (dir) fs.mkdirs(p) else fs.openWrite(p).close()
        }
    }

    private fun validName(n: String) = n.isNotBlank() && n != "." && n != ".." && !n.contains('/') && !n.contains('\u0000')

    fun compress(tab: TabState, entries: List<FileEntry>, name: String, kind: Archives.Kind = Archives.Kind.ZIP, password: String? = null) {
        val fs = fs(tab)
        val ext = Archives.extensionOf(kind)
        val fileName = name.trim().let { if (it.endsWith(ext, true)) it else it + ext }
        if (!validName(fileName)) { toast(t("名称无效", "Invalid name")); return }
        tab.selected = emptySet()
        runBusy(t("正在压缩…", "Compressing…"), listOf(tab)) {
            var target = VPath.join(tab.path, fileName)
            var n = 1
            while (fs.stat(target) != null) target = VPath.join(tab.path, VPath.numbered(fileName.removeSuffix(ext), n++) + ext)
            try {
                Archives.compress(kind, fs, entries, fs, target, getApplication<Application>().cacheDir, password = password, isCancelled = { !isActive() }) {
                    busy = t("压缩: ", "Compress: ") + it.name
                }
            } catch (e: Throwable) {
                runCatching { fs.delete(target, false) }
                throw e
            }
        }
    }

    fun createSymlink(tab: TabState, linkName: String, target: String) {
        if (!validName(linkName.trim())) { toast(t("名称无效", "Invalid name")); return }
        val fs = fs(tab)
        runBusy(t("创建软链接…", "Creating symlink…"), listOf(tab)) {
            val link = VPath.join(tab.path, linkName.trim())
            val shell = fs as? ShellFs
            com.ghostpanter.files.core.ops.SymlinkOps.createLink(link, target.trim(), shell)
        }
    }

    fun remountRw(mountPoint: String) {
        val shell = runCatching { fsm.get(com.ghostpanter.files.fs.FsManager.ROOT) as ShellFs }.getOrNull()
            ?: run { toast(t("需要 Root", "Root required")); return }
        runBusy(t("重新挂载读写…", "Remounting rw…"), emptyList()) {
            com.ghostpanter.files.core.ops.SymlinkOps.remountRw(shell, mountPoint)
            toast(t("已挂载为读写（风险操作，改完请改回只读）", "Remounted rw — remount ro when done"))
        }
    }

    fun exportBackup(destPath: String) {
        runBusy(t("导出配置…", "Exporting…"), emptyList()) {
            val settings = g.settings
            val snap = ConfigBackup.Snapshot(
                settingsJson = ConfigBackup.settingsToJson(
                    settings.showHidden, settings.foldersFirst, settings.ascending, settings.recycleBin,
                    settings.sortBy.name, settings.paneRatio, settings.concurrentJobs, settings.jobsPerServer,
                    settings.showThumbnails, settings.viewMode,
                ),
                bookmarksJson = ConfigBackup.bookmarksToJson(g.bookmarks.items.map { Triple(it.label, it.fsId, it.path) }),
                serversJson = java.io.File(getApplication<Application>().filesDir.parent, "shared_prefs/servers.xml").let {
                    // Re-serialize from store so passwords stay Crypto-encrypted blobs
                    org.json.JSONArray().also { arr ->
                        for (s in g.servers.servers) {
                            arr.put(org.json.JSONObject()
                                .put("id", s.id).put("name", s.name).put("protocol", s.protocol.name)
                                .put("host", s.host).put("port", s.port).put("username", s.username)
                                .put("password", com.ghostpanter.files.data.Crypto.encrypt(s.password))
                                .put("path", s.path).put("domain", s.domain).put("encoding", s.encoding)
                                .put("passive", s.passiveMode)
                                .put("privateKey", com.ghostpanter.files.data.Crypto.encrypt(s.privateKey))
                                .put("anonymous", s.anonymous))
                        }
                    }.toString()
                },
            )
            ConfigBackup.exportZip(java.io.File(destPath), snap)
            toast(t("已导出到 ", "Exported to ") + destPath)
        }
    }

    fun importBackup(srcPath: String) {
        runBusy(t("导入配置…", "Importing…"), emptyList()) {
            val snap = ConfigBackup.importZip(java.io.File(srcPath))
            val o = org.json.JSONObject(snap.settingsJson)
            g.settings.showHidden = o.optBoolean("show_hidden", g.settings.showHidden)
            g.settings.foldersFirst = o.optBoolean("folders_first", g.settings.foldersFirst)
            g.settings.ascending = o.optBoolean("ascending", g.settings.ascending)
            g.settings.recycleBin = o.optBoolean("recycle_bin", g.settings.recycleBin)
            g.settings.showThumbnails = o.optBoolean("show_thumbnails", true)
            g.settings.viewMode = o.optString("view_mode", "LIST")
            for ((label, fs, path) in ConfigBackup.parseBookmarks(snap.bookmarksJson)) {
                g.bookmarks.add(com.ghostpanter.files.data.Bookmark(label, fs, path))
            }
            val arr = org.json.JSONArray(snap.serversJson)
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                g.servers.upsert(com.ghostpanter.files.core.ServerConfig(
                    id = x.getString("id"), name = x.getString("name"),
                    protocol = com.ghostpanter.files.core.Protocol.valueOf(x.getString("protocol")),
                    host = x.getString("host"), port = x.optInt("port"),
                    username = x.optString("username"),
                    password = com.ghostpanter.files.data.Crypto.decrypt(x.optString("password")),
                    path = x.optString("path"), domain = x.optString("domain"),
                    encoding = x.optString("encoding", "UTF-8"), passiveMode = x.optBoolean("passive", true),
                    privateKey = com.ghostpanter.files.data.Crypto.decrypt(x.optString("privateKey")),
                    anonymous = x.optBoolean("anonymous", false),
                ))
            }
            toast(t("导入完成（服务器密码需同一设备 Keystore）", "Imported (passwords need same device Keystore)"))
        }
    }

    /** Writes "<sha256>  <name>" next to the file (sha256sum -c compatible). */
    fun writeChecksumFile(tab: TabState, e: FileEntry, sha256: String) {
        val fs = fs(tab)
        runBusy(t("保存校验文件…", "Saving…"), listOf(tab)) {
            val p = VPath.join(VPath.parent(e.path), e.name + ".sha256")
            fs.openWrite(p).use { it.write(com.ghostpanter.files.core.ops.Checksums.sumLine(sha256, e.name).toByteArray()) }
            toast(t("已保存 ", "Saved ") + VPath.name(p))
        }
    }

    /** Batch rename (pattern); temp names first so swaps / case changes work. */
    fun batchRename(tab: TabState, plans: List<com.ghostpanter.files.core.ops.BatchRename.Plan>) {
        val fs = fs(tab)
        tab.selected = emptySet(); tab.multiSelect = false
        runBusy(t("批量重命名…", "Renaming…"), listOf(tab)) {
            val errs = com.ghostpanter.files.core.ops.BatchRename.execute(plans) { a, b -> fs.rename(a, b) }
            if (errs.isNotEmpty()) throw FsException(t("${errs.size} 项失败：", "${errs.size} failed: ") + errs.take(3).joinToString("；"))
            toast(t("已重命名 ${plans.count { it.changed && it.error == null }} 项", "Renamed"))
        }
    }

    fun extract(tab: TabState, e: FileEntry, intoSubfolder: Boolean, password: String?) {
        val fs = fs(tab)
        val dest = if (intoSubfolder) VPath.join(tab.path, Archives.baseName(e.name)) else tab.path
        runBusy(t("正在解压…", "Extracting…"), listOf(tab)) {
            Archives.extract(
                fs, e.path, fs, dest, localFile = fsm.localFile(tab.fsId, e.path),
                tmpDir = getApplication<Application>().cacheDir, password = password,
                isCancelled = { !isActive() },
            ) { busy = t("解压: ", "Extract: ") + it.name }
        }
    }

    private fun isActive() = busyJob?.isActive == true

    fun chmod(tab: TabState, e: FileEntry, mode: String, recursive: Boolean) {
        val fs = fs(tab)
        runBusy(t("修改权限…", "chmod…"), listOf(tab)) { fs.chmod(e.path, mode, recursive) }
    }

    fun chown(tab: TabState, e: FileEntry, owner: String, recursive: Boolean) {
        val fs = fs(tab) as? ShellFs ?: return
        runBusy(t("修改所有者…", "chown…"), listOf(tab)) { fs.chown(e.path, owner, recursive) }
    }

    fun addBookmark(tab: TabState, e: FileEntry? = null) {
        val path = e?.takeIf { it.isDirectory }?.path ?: tab.path
        g.bookmarks.add(Bookmark(VPath.name(path).ifEmpty { "/" } + " · " + fsm.label(tab.fsId), tab.fsId, path))
        toast(t("已添加书签", "Bookmarked"))
    }

    // ---------------------------------------------------------------- opening files

    /** Gives a java.io.File for [e]: direct for local files, otherwise a cached download. */
    private suspend fun materialize(fsId: String, e: FileEntry): File {
        fsm.localFile(fsId, e.path)?.let { if (it.canRead()) return it }
        val dir = File(getApplication<Application>().cacheDir, "open/" + Integer.toHexString((fsId + e.path).hashCode()))
        dir.mkdirs()
        val out = File(dir, e.name)
        if (out.exists() && out.length() == e.size && out.lastModified() >= e.lastModified) return out
        val fs = fsm.get(fsId)
        withContext(Dispatchers.IO) {
            fs.openRead(e.path).use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        if (busyJob?.isActive == false) throw CancellationException()
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 200) { last = now; busy = t("读取 ", "Fetching ") + "${e.name} ${formatSize(done)} / ${formatSize(e.size)}" }
                    }
                }
            }
        }
        return out
    }

    fun openEntry(tab: TabState, e: FileEntry, chooser: Boolean = false, asText: Boolean = false) {
        if (e.isDirectory && !chooser) { navigate(tab, tab.fsId, e.path); return }
        val ext = e.extension
        when {
            asText || (!chooser && ext in TEXT_EXT) -> screen = Screen.Editor(tab.fsId, e.path)
            !chooser && ext in IMAGE_EXT -> {
                val sibs = sortEntries(tab.entries.filter { it.extension in IMAGE_EXT }, g.settings.sortBy, g.settings.ascending, false).map { it.path }
                screen = Screen.Image(tab.fsId, e.path, sibs)
            }
            !chooser && (ext in VIDEO_EXT || ext in AUDIO_EXT) -> {
                val set = if (ext in AUDIO_EXT) AUDIO_EXT else VIDEO_EXT
                val sibs = sortEntries(tab.entries.filter { it.extension in set }, g.settings.sortBy, g.settings.ascending, false).map { it.path }
                screen = Screen.Player(tab.fsId, e.path, sibs, audioOnly = ext in AUDIO_EXT)
            }
            !chooser && ext == "apk" -> withFile(tab, e) { f -> screen = Screen.Apk(f, e.name) }
            !chooser && Archives.isArchive(e.name) -> pendingExtract = tab to e
            else -> withFile(tab, e) { f -> _events.tryEmit(UiEvent.OpenFile(f, mimeOf(e.name), chooser)) }
        }
    }

    var pendingExtract by mutableStateOf<Pair<TabState, FileEntry>?>(null)

    private fun withFile(tab: TabState, e: FileEntry, then: (File) -> Unit) {
        busyJob?.cancel()
        busy = t("准备文件…", "Preparing…")
        busyJob = viewModelScope.launch {
            try {
                val f = materialize(tab.fsId, e)
                busy = null
                then(f)
            } catch (ex: CancellationException) {
                busy = null
            } catch (ex: Exception) {
                busy = null
                toast(ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    fun share(tab: TabState, entries: List<FileEntry>) {
        val files = entries.filter { !it.isDirectory }
        if (files.isEmpty()) return
        busyJob?.cancel()
        busy = t("准备分享…", "Preparing…")
        busyJob = viewModelScope.launch {
            try {
                val out = files.map { materialize(tab.fsId, it) }
                busy = null
                _events.tryEmit(UiEvent.Share(out))
            } catch (ex: Exception) {
                busy = null
                if (ex !is CancellationException) toast(ex.message ?: "error")
            }
        }
    }

    fun hasCap(tab: TabState, c: Capability) = runCatching { fs(tab).has(c) }.getOrDefault(false)

    fun splitFile(tab: TabState, e: FileEntry, partSizeMb: Int) {
        val fs = fs(tab)
        runBusy(t("正在分割…", "Splitting…"), listOf(tab)) {
            val parts = com.ghostpanter.files.core.ops.FileSplit.split(
                fs, e.path, partSizeMb.toLong() * 1024 * 1024,
                isCancelled = { !isActive() },
            ) { busy = t("分割 ", "Split ") + formatSize(it) }
            toast(t("已分成 ${parts.size} 卷", "Split into ${parts.size} parts"))
        }
    }

    fun mergeParts(tab: TabState, first: FileEntry) {
        val fs = fs(tab)
        val destName = first.name.replace(Regex("""\.\d{3}$"""), "")
        runBusy(t("正在合并…", "Merging…"), listOf(tab)) {
            var dest = VPath.join(tab.path, destName)
            var n = 1
            while (fs.stat(dest) != null) dest = VPath.join(tab.path, VPath.numbered(destName, n++))
            com.ghostpanter.files.core.ops.FileSplit.merge(fs, first.path, dest, isCancelled = { !isActive() }) {
                busy = t("合并 ", "Merge ") + formatSize(it)
            }
            toast(t("已合并到 ", "Merged to ") + VPath.name(dest))
        }
    }

    fun testArchive(tab: TabState, e: FileEntry, password: String?) {
        val fs = fs(tab)
        runBusy(t("正在测试压缩包…", "Testing archive…"), emptyList()) {
            val r = com.ghostpanter.files.core.ops.ArchiveTest.test(
                fs, e.path, fsm.localFile(tab.fsId, e.path), getApplication<Application>().cacheDir, password,
                isCancelled = { !isActive() },
            )
            toast(if (r.ok) r.detail else t("失败：", "Failed: ") + r.detail)
        }
    }
}
