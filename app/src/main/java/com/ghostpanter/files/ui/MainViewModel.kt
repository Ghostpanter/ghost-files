package com.ghostpanter.files.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.ghostpanter.files.core.ops.RecycleBin
import com.ghostpanter.files.core.transfer.ConflictPolicy
import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.core.transfer.Loc
import com.ghostpanter.files.core.transfer.TransferOp
import com.ghostpanter.files.data.Bookmark
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

class TabState(fsId: String, path: String) {
    var fsId by mutableStateOf(fsId)
    var path by mutableStateOf(path)
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var filter by mutableStateOf("")
    var highlight by mutableStateOf<String?>(null)
    val history = ArrayDeque<Pair<String, String>>()
    val scroll = HashMap<String, Pair<Int, Int>>()
    var listState by mutableStateOf(LazyListState())
    var loadJob: Job? = null

    val title: String get() = VPath.name(path).ifEmpty { "/" }
    val selecting: Boolean get() = selected.isNotEmpty()
    fun selectedEntries() = entries.filter { it.path in selected }
}

class PaneState(val index: Int) {
    val tabs = mutableStateListOf<TabState>()
    var activeTab by mutableIntStateOf(0)
    val tab: TabState get() = tabs[activeTab.coerceIn(0, tabs.size - 1)]
}

data class Clip(val op: TransferOp, val fsId: String, val entries: List<FileEntry>)

data class PendingTransfer(val op: TransferOp, val sources: List<Loc>, val dest: Loc, val conflicts: List<String>)

sealed interface Screen {
    data object Main : Screen
    data object Transfers : Screen
    data object Trash : Screen
    data class Editor(val fsId: String, val path: String) : Screen
    data class Image(val fsId: String, val path: String, val siblings: List<String>) : Screen
    data class Apk(val file: File, val label: String) : Screen
    data class Search(val fsId: String, val root: String) : Screen
}

sealed interface UiEvent {
    data class Toast(val text: String) : UiEvent
    data class OpenFile(val file: File, val mime: String, val chooser: Boolean) : UiEvent
    data class Share(val files: List<File>) : UiEvent
    data object RequestAllFiles : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val g = GhostApp.graph
    val fsm: FsManager get() = g.fs
    val panes = listOf(PaneState(0), PaneState(1))
    var activePane by mutableIntStateOf(0)
    var clip by mutableStateOf<Clip?>(null)
    var screen by mutableStateOf<Screen>(Screen.Main)
    var pendingTransfer by mutableStateOf<PendingTransfer?>(null)
    var busy by mutableStateOf<String?>(null)
    private var busyJob: Job? = null

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

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
        }
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
        navigate(tab, prev.first, prev.second, push = false)
        return true
    }

    fun newTab(p: PaneState) {
        val cur = p.tab
        p.tabs.add(TabState(cur.fsId, cur.path).also { refresh(it) })
        p.activeTab = p.tabs.size - 1
        saveTabs()
    }

    fun closeTab(p: PaneState, i: Int) {
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
    fun clearSelection(tab: TabState) { tab.selected = emptySet() }

    // ---------------------------------------------------------------- transfers

    /** MT style: copy/move selection of [p] into the directory shown in the other pane. */
    fun transferToOther(p: PaneState, op: TransferOp) {
        val src = p.tab
        val dst = other(p).tab
        val items = src.selectedEntries()
        if (items.isEmpty()) return
        startTransfer(op, src.fsId, items, dst.fsId, dst.path)
        src.selected = emptySet()
    }

    fun setClip(tab: TabState, op: TransferOp) {
        val items = tab.selectedEntries()
        if (items.isEmpty()) return
        clip = Clip(op, tab.fsId, items)
        tab.selected = emptySet()
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
        viewModelScope.launch {
            val existing = runCatching { withContext(Dispatchers.IO) { fsm.get(dstFs).list(dstPath).map { it.name }.toSet() } }.getOrDefault(emptySet())
            val conflicts = items.map { it.name }.filter { it in existing }
            val sameDir = srcFs == dstFs && items.all { VPath.parent(it.path) == VPath.normalize(dstPath) }
            if (conflicts.isNotEmpty() && !sameDir) {
                pendingTransfer = PendingTransfer(op, sources, dest, conflicts)
            } else enqueue(op, sources, dest, ConflictPolicy.RENAME)
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
        if (newName.isBlank() || newName.contains('/')) { toast(t("名称无效", "Invalid name")); return }
        val fs = fs(tab)
        runBusy(t("重命名…", "Renaming…"), listOf(tab)) {
            val target = VPath.join(VPath.parent(e.path), newName)
            if (fs.stat(target) != null) throw FsException(t("已存在同名文件", "Name already exists"))
            fs.rename(e.path, target)
        }
        tab.selected = emptySet()
    }

    fun create(tab: TabState, name: String, dir: Boolean) {
        if (name.isBlank() || name.contains('/')) { toast(t("名称无效", "Invalid name")); return }
        val fs = fs(tab)
        runBusy(t("创建中…", "Creating…"), listOf(tab)) {
            val p = VPath.join(tab.path, name)
            if (fs.stat(p) != null) throw FsException(t("已存在", "Already exists"))
            if (dir) fs.mkdirs(p) else fs.openWrite(p).close()
        }
    }

    fun compress(tab: TabState, entries: List<FileEntry>, name: String) {
        val fs = fs(tab)
        val zipName = if (name.endsWith(".zip", true)) name else "$name.zip"
        tab.selected = emptySet()
        runBusy(t("正在压缩…", "Compressing…"), listOf(tab)) {
            val target = VPath.join(tab.path, zipName)
            Archives.compressZip(fs, entries, fs, target, isCancelled = { !isActive() }) { busy = t("压缩: ", "Zip: ") + it.name }
        }
    }

    fun extract(tab: TabState, e: FileEntry, intoSubfolder: Boolean, password: String?) {
        val fs = fs(tab)
        val dest = if (intoSubfolder) VPath.join(tab.path, e.name.substringBefore('.').ifEmpty { e.name + "_x" }) else tab.path
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
}
