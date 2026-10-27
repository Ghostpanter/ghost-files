package com.ghostpanter.files.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.space.DupGroups
import com.ghostpanter.files.core.space.DupRanker
import com.ghostpanter.files.core.space.DuplicateFinder
import com.ghostpanter.files.core.space.FileCategory
import com.ghostpanter.files.core.space.NodeFlags
import com.ghostpanter.files.core.space.NodeKind
import com.ghostpanter.files.core.space.Placed
import com.ghostpanter.files.core.space.Protection
import com.ghostpanter.files.core.space.Residue
import com.ghostpanter.files.core.space.ResidueGroup
import com.ghostpanter.files.core.space.ResidueKind
import com.ghostpanter.files.core.space.Residues
import com.ghostpanter.files.core.transfer.TransferOp
import com.ghostpanter.files.core.space.SpaceFilter
import com.ghostpanter.files.core.space.SpaceNode
import com.ghostpanter.files.core.space.SpacePolicy
import com.ghostpanter.files.core.space.SpaceQueries
import com.ghostpanter.files.core.space.SpaceTree
import com.ghostpanter.files.core.space.TRect
import com.ghostpanter.files.core.space.TreemapLayout
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.fs.ShizukuRunner
import com.ghostpanter.files.privilege.ElevatedAccess
import com.ghostpanter.files.space.DupBatch
import com.ghostpanter.files.space.ResidueScan
import com.ghostpanter.files.space.SpaceNotice
import com.ghostpanter.files.space.SpaceSessions
import com.ghostpanter.files.space.SpaceSummary
import com.ghostpanter.files.space.SpaceTarget
import com.ghostpanter.files.space.SpaceTier
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ====================================================================== plain UI model

enum class SpaceTab { MAP, TOP, DUPS, EMPTY, RESIDUE }

/** Everything the analysis page shows (renderable in Paparazzi from a fake tree). */
class SpaceUi(
    val scopeLabel: String,
    val tier: SpaceTier,
    val tree: SpaceTree?,
    val current: SpaceNode?,
    val selected: SpaceNode?,
    val filter: SpaceFilter,
    val tab: SpaceTab,
    val version: Long,
    val summary: SpaceSummary? = null,
    val scanning: Boolean = false,
    val background: Boolean = false,
    val files: Long = 0,
    val bytes: Long = 0,
    val currentPath: String = "",
    val scannedAt: Long = 0,
    val error: String? = null,
    val notices: List<SpaceNotice> = emptyList(),
    val dups: List<DuplicateFinder.Group>? = null,
    val dupProgress: Pair<Int, Int>? = null,
    val now: Long = System.currentTimeMillis(),
    /** App label + icon of a package (null = unknown / not loaded). */
    val app: (String) -> AppMeta? = { null },
    /** Kept copy of a duplicate group (user's pick, else [DupRanker]). */
    val keepOf: (DuplicateFinder.Group) -> String = { g -> DupRanker.best(g.paths) { p -> tree?.find(p)?.mtime ?: 0 } },
    /** Thumbnails / APK icons for list rows (null = type icons only). */
    val thumbs: SpaceThumbs? = null,
    /** Initial horizontal offset of path lines (screenshots of a scrolled path). */
    val pathScroll: Int = 0,
    /** 大文件 multi-select (tree paths); empty = not selecting. */
    val topSelected: Set<String> = emptySet(),
    /** 卸载残留 scan result (null = not scanned yet). */
    val residues: ResidueScan? = null,
    val residueScanning: Boolean = false,
    val residueError: String? = null,
    /** Chosen leftover paths. */
    val residueSelected: Set<String> = emptySet(),
    /** Label + icon of an uninstalled package (remembered from when it was installed). */
    val residueApp: (String) -> AppMeta? = { null },
)

/** Row thumbnails (reuses the panes' thumbnail + APK icon pipeline). */
interface SpaceThumbs {
    fun cached(path: String, dir: Boolean): android.graphics.Bitmap?
    /** Blocking; called off the main thread. */
    fun load(path: String, dir: Boolean): android.graphics.Bitmap?
}

class SpaceCallbacks(
    val back: () -> Unit = {},
    val open: (SpaceNode) -> Unit = {},
    val select: (SpaceNode) -> Unit = {},
    val long: (SpaceNode) -> Unit = {},
    val crumb: (SpaceNode) -> Unit = {},
    val filter: (SpaceFilter) -> Unit = {},
    val tab: (SpaceTab) -> Unit = {},
    val refresh: () -> Unit = {},
    val cancel: () -> Unit = {},
    val notice: (String) -> Unit = {},
    val findDups: () -> Unit = {},
    val openPath: (String) -> Unit = {},
    val more: () -> Unit = {},
    val scope: () -> Unit = {},
    /** Tap on a duplicate row: keep this copy instead. */
    val keep: (DuplicateFinder.Group, String) -> Unit = { _, _ -> },
    val longPath: (String) -> Unit = {},
    /** 「保留最优，删除其他」 for these groups (one, or all). */
    val dedupe: (List<DuplicateFinder.Group>) -> Unit = {},
    /** 大文件: tap toggles while selecting; swipe = MT range select over [visible]. */
    val topToggle: (String) -> Unit = {},
    val topSwipe: (String, List<String>) -> Unit = { _, _ -> },
    val topAll: (List<String>) -> Unit = {},
    val topClear: () -> Unit = {},
    /** "delete" / "share" / "copy" / "move". */
    val topAction: (String) -> Unit = {},
    val residueScan: () -> Unit = {},
    /** Turn these leftover paths on / off. */
    val residueSet: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    val residueClean: () -> Unit = {},
    val residueOpen: (Residue) -> Unit = {},
)

// ====================================================================== stateful screen

@Composable
fun SpaceScreen(vm: MainViewModel, s: Screen.Space) {
    val ctx = LocalContext.current
    val target = remember(s) { SpaceTarget(s.fsId, s.path ?: if (s.fsId == LocalFs.ID) vm.fsm.storageRoot else runCatching { vm.fsm.get(s.fsId).homePath }.getOrDefault("/"), s.whole) }
    val session = remember(target) { SpaceSessions.get(ctx, vm.fsm, target) }
    LaunchedEffect(session) { session.open() }
    val tree = session.tree
    var currentNode by remember(session) { mutableStateOf<SpaceNode?>(null) }
    var selected by remember(session) { mutableStateOf<SpaceNode?>(null) }
    var filter by remember(session) { mutableStateOf(SpaceFilter()) }
    var tab by remember(session) { mutableStateOf(SpaceTab.MAP) }
    var deleting by remember { mutableStateOf<SpaceNode?>(null) }
    var scopePicker by remember { mutableStateOf(false) }
    var dedupeAsk by remember { mutableStateOf<List<DuplicateFinder.Group>?>(null) }
    // 大文件 multi-select (tree paths) + MT swipe anchor.
    var topSel by remember(session) { mutableStateOf<Set<String>>(emptySet()) }
    var topAnchor by remember(session) { mutableStateOf<String?>(null) }
    var topDeleteAsk by remember { mutableStateOf<List<String>?>(null) }
    // 卸载残留 selection (defaults to everything but the guessed storage-root folders).
    var resSel by remember(session) { mutableStateOf<Set<String>>(emptySet()) }
    var resAsk by remember { mutableStateOf<List<Residue>?>(null) }
    val remembered = remember { mutableStateMapOf<String, AppMeta?>() }
    LaunchedEffect(session.residues) {
        val r = session.residues ?: return@LaunchedEffect
        resSel = Residues.defaultSelection(r.groups)
        val miss = r.groups.map { it.pkg }.filter { it !in remembered }
        if (session.adbSerial == null && miss.isNotEmpty()) {
            val got = withContext(Dispatchers.IO) { miss.associateWith { AppIconCache.remembered(ctx, it) } }
            remembered.putAll(got)
        }
    }
    val scope = rememberCoroutineScope()
    // Tree swapped (refresh finished): keep the same folder by path.
    val cur = remember(tree, currentNode, session.version) { resolveNode(tree, currentNode) }
    val apps = remember { mutableStateMapOf<String, AppMeta?>() }
    val appScope = if (session.adbSerial != null) "adb:${session.adbSerial}" else AppIconCache.LOCAL
    val appOf: (String) -> AppMeta? = { pkg ->
        if (pkg !in apps) {
            apps[pkg] = AppIconCache.peek(appScope, pkg)?.takeUnless { AppIconCache.isMissing(it) }
            scope.launch { apps[pkg] = AppIconCache.load(ctx, appScope, pkg) }
        }
        apps[pkg]
    }

    fun goUp(): Boolean {
        val p = cur?.parent ?: return false
        currentNode = p; selected = null; return true
    }
    // Files gone from the tree (deleted / moved anywhere) leave the selection right away.
    val topLive = remember(topSel, tree, session.version) { if (tree == null) emptySet() else synchronized(tree.lock) { topSel.filterTo(LinkedHashSet()) { tree.find(it) != null } } }
    BackHandler {
        when {
            topLive.isNotEmpty() && tab == SpaceTab.TOP -> { topSel = emptySet(); topAnchor = null }
            tab != SpaceTab.MAP -> tab = SpaceTab.MAP
            !goUp() -> vm.screen = Screen.Main
        }
    }

    fun openInPane(n: SpaceNode) {
        val (fsId, p) = session.locate(n)
        if (n.kind == NodeKind.DIR) { vm.openFs(fsId, p); return }
        vm.openFs(fsId, VPath.parent(p))
        scope.launch {
            val e = withContext(Dispatchers.IO) { runCatching { vm.fsm.get(fsId).stat(p) }.getOrNull() } ?: return@launch
            vm.openEntry(vm.activeTab, e)
        }
    }
    fun locateInPane(n: SpaceNode) {
        val (fsId, p) = session.locate(if (n.kind == NodeKind.SMALL_FILES) n.parent ?: n else n)
        if (n.kind == NodeKind.SMALL_FILES) { vm.openFs(fsId, p); return }
        vm.openFs(fsId, VPath.parent(p))
        vm.activeTab.highlight = p
        vm.activeTab.scrollRequest++
    }
    fun appDetails(pkg: String) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val canClean = session.rooted || ShizukuRunner.granted() || RootRunner.granted() || session.tier == SpaceTier.ADB
    fun menuFor(n: SpaceNode) {
        val a = ArrayList<MenuAction>()
        fun add(label: String, icon: ImageVector, hint: String? = null, run: () -> Unit) = a.add(MenuAction(label, icon, hint) { vm.menu = null; run() })
        val pkg = n.pkg
        when (n.kind) {
            NodeKind.FILE, NodeKind.DIR -> {
                if (n.kind == NodeKind.DIR) add(t("进入", "Drill in"), Icons.Filled.ZoomIn) { currentNode = n; selected = null }
                add(if (n.kind == NodeKind.DIR) t("在面板中打开", "Open in pane") else t("打开", "Open"), Icons.Filled.FolderOpen) { openInPane(n) }
                add(t("在面板中定位", "Show in pane"), Icons.Filled.MyLocation) { locateInPane(n) }
                if (tab == SpaceTab.TOP && n.kind == NodeKind.FILE) add(t("多选", "Select"), Icons.Filled.Checklist, t("横滑也能多选", "or swipe a row")) {
                    val p = n.path(); topSel = topLive + p; topAnchor = p
                }
                if (pkg != null && session.adbSerial == null) add(t("应用详情", "App info"), Icons.Filled.Info) { appDetails(pkg) }
                if (pkg != null && canClean && !n.has(NodeFlags.RESIDUE)) add(t("清理此应用缓存", "Clear this app's cache"), Icons.Filled.CleaningServices) {
                    session.clearCaches(pkg) { vm.toast(it); session.refresh(false) }
                }
                add(t("删除", "Delete"), Icons.Filled.Delete, if (n.has(NodeFlags.RESIDUE)) t("卸载残留", "leftover") else null) { deleting = n }
                add(t("复制路径", "Copy path"), Icons.Filled.Link) { vm.copyText(session.locate(n).second) }
            }
            NodeKind.SMALL_FILES -> add(t("在面板中打开所在文件夹", "Open folder in pane"), Icons.Filled.FolderOpen) { locateInPane(n) }
            NodeKind.AGGREGATE -> {
                if (n.drillable) add(t("展开", "Expand"), Icons.Filled.ZoomIn) { currentNode = n; selected = null }
                if (pkg != null) {
                    add(t("应用详情（可清理缓存）", "App info (clear cache)"), Icons.AutoMirrored.Filled.OpenInNew) { appDetails(pkg) }
                    if (canClean) add(t("清理缓存", "Clear cache"), Icons.Filled.CleaningServices) { session.clearCaches(pkg) { vm.toast(it); session.refresh(false) } }
                }
            }
            NodeKind.NOTICE -> {}
        }
        if (a.isEmpty()) return
        val sub = buildString {
            append(formatSize(n.size))
            if (n.kind == NodeKind.DIR) append(" · " + t("${n.count} 个文件", "${n.count} files"))
            if (n.kind == NodeKind.FILE && n.mtime > 0) append(" · " + formatTime(n.mtime))
            n.detail?.let { append("\n$it") }
            if (n.has(NodeFlags.RESIDUE)) append("\n" + t("应用已卸载，这是残留文件夹", "App not installed: leftover folder"))
        }
        vm.menu = ActionMenu(nodeLabel(n, appOf), sub, a)
    }

    val ui = SpaceUi(
        scopeLabel = scopeLabel(vm, target), tier = session.tier, tree = tree, current = cur, selected = selected,
        filter = filter, tab = tab, version = session.version, summary = session.summary,
        scanning = session.scanning, background = session.background, files = session.files, bytes = session.bytes,
        currentPath = session.current, scannedAt = tree?.scannedAt ?: 0, error = session.error, notices = session.notices,
        dups = session.dups, dupProgress = session.dupProgress, app = appOf,
        keepOf = { g -> DupGroups.keepOf(g, session.keepChosen, session::mtimeOf) },
        topSelected = topLive,
        residues = session.residues, residueScanning = session.residueScanning, residueError = session.residueError,
        residueSelected = resSel,
        residueApp = { pkg -> remembered[pkg] ?: if (session.adbSerial != null) appOf(pkg) else null },
        thumbs = remember(session) { object : SpaceThumbs {
            private fun entry(path: String, dir: Boolean): Pair<String, FileEntry> {
                val (fsId, p) = session.locate(SpaceNode(path, NodeKind.FILE))
                val n = session.tree?.find(path)
                return fsId to FileEntry(p, VPath.name(p), dir, n?.size ?: 0, n?.mtime ?: 0)
            }
            override fun cached(path: String, dir: Boolean) = entry(path, dir).let { (f, e) -> vm.cachedThumbnail(f, e, 96) }
            override fun load(path: String, dir: Boolean) = entry(path, dir).let { (f, e) -> vm.thumbnail(f, e, 96) }
        } },
    )
    val cb = SpaceCallbacks(
        back = { if (!goUp()) vm.screen = Screen.Main },
        open = { n -> if (n.drillable) { currentNode = n; selected = null } else if (n.kind == NodeKind.FILE) { selected = n } else menuFor(n) },
        select = { n -> selected = n },
        long = { n -> menuFor(n) },
        crumb = { n -> currentNode = n; selected = null },
        filter = { filter = it },
        tab = {
            tab = it
            if (it == SpaceTab.DUPS && session.dups == null && session.dupProgress == null) session.findDuplicates()
            if (it == SpaceTab.RESIDUE && session.residues == null && !session.residueScanning) session.scanResidues()
        },
        refresh = { session.refresh(full = true) },
        cancel = { session.cancel() },
        notice = { id ->
            when (id) {
                "usage" -> runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "shizuku" -> ShizukuRunner.request { ok ->
                    if (ok) { session.refresh(false); if (tab == SpaceTab.RESIDUE) session.scanResidues() }
                    else {
                        vm.toast(t("未授权。可安装 Shizuku，或在设置里启用「内置提权」。", "Not granted. Install Shizuku, or enable Built-in privilege in settings."))
                        vm.screen = Screen.EmbeddedPrivilege
                    }
                }
            }
        },
        findDups = { session.findDuplicates() },
        openPath = { p -> vm.openFs(session.locate(SpaceNode(p, NodeKind.FILE)).first, VPath.parent(session.locate(SpaceNode(p, NodeKind.FILE)).second)) },
        more = {
            val a = ArrayList<MenuAction>()
            fun add(label: String, icon: ImageVector, hint: String? = null, run: () -> Unit) = a.add(MenuAction(label, icon, hint) { vm.menu = null; run() })
            add(t("重新完整扫描", "Full rescan"), Icons.Filled.Refresh) { session.refresh(full = true) }
            add(t("切换分析范围", "Change scope"), Icons.Filled.SwapHoriz) { scopePicker = true }
            if (canClean) add(t("一键清理所有应用缓存", "Clear all app caches"), Icons.Filled.CleaningServices,
                if (session.rooted || RootRunner.granted()) "Root" else if (session.adbSerial != null) "ADB" else "Shizuku · pm trim-caches") {
                session.clearCaches(null) { vm.toast(it); session.refresh(false) }
            } else a.add(MenuAction(t("清理缓存（需 Root 或 Shizuku）", "Clear caches (needs root or Shizuku)"), Icons.Filled.CleaningServices, enabled = false) {})
            if (session.isStorageRoot && !session.usageAccess) add(t("授予「使用情况访问」", "Grant usage access"), Icons.Filled.QueryStats) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            vm.menu = ActionMenu(t("空间分析", "Space analysis"), scopeLabel(vm, target) + " · " + session.tier.label(), a)
        },
        scope = { scopePicker = true },
        keep = { g, p -> session.chooseKeep(g, p) },
        longPath = { p -> session.tree?.find(p)?.let { menuFor(it) } ?: run {
            val (fsId, lp) = session.locate(SpaceNode(p, NodeKind.FILE)); vm.openFs(fsId, VPath.parent(lp))
        } },
        dedupe = { groups -> if (session.batch == null) dedupeAsk = groups },
        topToggle = { p -> topSel = if (p in topLive) topLive - p else topLive + p },
        topSwipe = { p, visible -> swipeSelect(topLive, topAnchor, visible, p).let { r -> topSel = r.selected; topAnchor = r.anchor } },
        topAll = { visible -> topSel = topLive + visible },
        topClear = { topSel = emptySet(); topAnchor = null },
        topAction = { action ->
            val paths = topLive.toList()
            val located = paths.map { p -> val (fsId, lp) = session.locate(SpaceNode(p, NodeKind.FILE)); Triple(p, fsId, lp) }
            fun entries() = located.map { (p, _, lp) -> val n = session.tree?.find(p); FileEntry(lp, VPath.name(lp), false, n?.size ?: 0, n?.mtime ?: 0) }
            val oneFs = located.map { it.second }.distinct().singleOrNull()
            when (action) {
                "delete" -> if (session.batch == null && paths.isNotEmpty()) topDeleteAsk = paths
                "share", "copy", "move" -> if (oneFs == null) vm.toast(t("所选文件在不同位置，请分开操作", "Selected files are on different backends; handle them separately"))
                else if (action == "share") vm.share(TabState(oneFs, VPath.parent(located.first().third)), entries())
                else {
                    val op = if (action == "copy") TransferOp.COPY else TransferOp.MOVE
                    vm.clip = Clip(op, oneFs, entries())
                    topSel = emptySet(); topAnchor = null
                    vm.toast(t("已${if (op == TransferOp.COPY) "复制" else "剪切"} ${paths.size} 项，到目标目录点粘贴", "${paths.size} item(s) on clipboard; paste in the target folder"))
                }
            }
        },
        residueScan = { session.scanResidues() },
        residueSet = { paths, on -> resSel = if (on) resSel + paths else resSel - paths.toSet() },
        residueClean = {
            val items = session.residues?.groups?.flatMap { it.items }?.filter { it.path in resSel }.orEmpty()
            if (items.isNotEmpty() && session.batch == null) resAsk = items
        },
        residueOpen = { r ->
            val fm = com.ghostpanter.files.fs.FsManager
            val (fsId, p) = when {
                session.adbSerial != null -> session.target.fsId to r.path
                !r.path.startsWith("/storage/") && !com.ghostpanter.files.core.space.PathCanon.isUserStorage(r.path) -> fm.ROOT to r.path
                (r.kind == ResidueKind.DATA || r.kind == ResidueKind.OBB) && android.os.Build.VERSION.SDK_INT >= 30 ->
                    if (RootRunner.granted()) fm.ROOT to com.ghostpanter.files.core.space.PathCanon.canonical(r.path) else fm.SHIZUKU to r.shown
                else -> LocalFs.ID to r.shown
            }
            vm.openFs(fsId, p)
        },
    )
    // Returning from the usage-access page: recompute aggregates.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, session) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && session.isStorageRoot && !session.usageAccess && session.hasUsageAccess() && !session.running) session.refresh(false)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    SpaceContent(ui, cb)

    deleting?.let { n -> SpaceDeleteDialog(n, vm.g.settings.recycleBin && session.locate(n).first == LocalFs.ID, dismiss = { deleting = null }) { trash ->
        deleting = null
        if (selected == n) selected = null
        session.deleteNode(n, trash, vm.trash) { err -> vm.toast(err?.let { t("删除失败: ", "Delete failed: ") + it } ?: t("已删除 ", "Deleted ") + n.name) }
    } }
    dedupeAsk?.let { groups ->
        val plan = remember(groups, session.keepChosen, session.version) { DupGroups.plan(groups, session.keepChosen, session::mtimeOf) }
        val local = plan.delete.all { session.locate(SpaceNode(it, NodeKind.FILE)).first == LocalFs.ID }
        val trash = vm.g.settings.recycleBin
        DupConfirmDialog(
            DupConfirm(all = groups === session.dups,
                groups = plan.groups, files = plan.delete.size, bytes = plan.bytes,
                trash = if (!trash) DupConfirm.Trash.OFF else if (local) DupConfirm.Trash.ON else DupConfirm.Trash.PARTIAL,
                names = plan.delete.map { VPath.name(it) }, keeps = groups.take(3).map { VPath.name(ui.keepOf(it)) }),
            dismiss = { dedupeAsk = null },
        ) {
            dedupeAsk = null
            val sizes = HashMap<String, Long>(); groups.forEach { g -> g.paths.forEach { sizes[it] = g.size } }
            selected = null
            session.deleteDuplicates(plan.delete, sizes, trash, vm.trash) { vm.toast(it) }
        }
    }
    topDeleteAsk?.let { paths ->
        val trashOn = vm.g.settings.recycleBin
        val local = paths.all { session.locate(SpaceNode(it, NodeKind.FILE)).first == LocalFs.ID }
        val sizes = remember(paths) { paths.associateWith { session.tree?.find(it)?.size ?: 0L } }
        TopDeleteDialog(paths, sizes.values.sum(), if (!trashOn) DupConfirm.Trash.OFF else if (local) DupConfirm.Trash.ON else DupConfirm.Trash.PARTIAL, dismiss = { topDeleteAsk = null }) {
            topDeleteAsk = null
            // System read-only partitions are never batch-deleted.
            val del = paths.filter { SpacePolicy.protection(it) != Protection.SYSTEM_READ_ONLY }
            if (del.size < paths.size) vm.toast(t("已跳过系统只读分区里的文件", "Skipped files on read-only system partitions"))
            if (selected?.path() in del) selected = null
            topSel = emptySet(); topAnchor = null
            session.deleteDuplicates(del, sizes, trashOn, vm.trash, title = t("正在删除所选文件…", "Deleting selected files…")) { vm.toast(it) }
        }
    }
    resAsk?.let { items ->
        ResidueConfirmDialog(items, dismiss = { resAsk = null }) {
            resAsk = null
            session.deleteResidues(items) { vm.toast(it) }
        }
    }
    session.batch?.let { b -> DupProgressDialog(b) { session.cancelBatch() } }
    if (scopePicker) SpaceScopeDialog(vm, dismiss = { scopePicker = false }) { fsId, whole ->
        scopePicker = false
        vm.screen = Screen.Space(fsId, null, whole)
    }
}

private fun resolveNode(tree: SpaceTree?, old: SpaceNode?): SpaceNode? {
    tree ?: return null
    old ?: return tree.root
    if (old.chain().first() === tree.root) return old
    return tree.find(old.path()) ?: tree.root.children?.firstOrNull { it.name == old.name && it.kind == old.kind } ?: tree.root
}

private fun scopeLabel(vm: MainViewModel, t0: SpaceTarget): String = when {
    t0.wholeDevice -> t("整机", "Whole device")
    t0.fsId == LocalFs.ID && VPath.normalize(t0.path) == vm.fsm.storageRoot -> t("内部存储", "Internal storage")
    else -> vm.fsm.label(t0.fsId) + "  " + t0.path
}

fun nodeLabel(n: SpaceNode, app: (String) -> AppMeta?): String = when {
    n.kind == NodeKind.SMALL_FILES -> t("${n.count} 个小文件", "${n.count} small files")
    n.kind == NodeKind.DIR && n.parent == null -> VPath.name(n.name).ifEmpty { "/" }
    n.name.startsWith("/") -> when {
        n.name == "/data" -> t("数据分区 /data", "Data /data")
        n.parent?.name == "" && com.ghostpanter.files.core.space.PathCanon.isUserStorage(n.name) -> t("用户文件", "User files")
        else -> n.name
    }
    n.parent?.parent == null && n.parent?.name == "/" -> "/" + n.name
    n.kind == NodeKind.DIR && n.path() == "/data/media" -> t("media · 内部存储", "media · internal storage")
    n.name.isEmpty() -> n.detail ?: "/"
    n.pkg != null && n.kind == NodeKind.DIR -> app(n.pkg!!)?.label?.let { "$it · ${n.name}" } ?: n.name
    else -> n.name
}

// ====================================================================== stateless content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceContent(ui: SpaceUi, cb: SpaceCallbacks, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = {
                    Column {
                        Text(t("空间分析", "Space analysis"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = { IconButton(onClick = cb.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (ui.scanning) IconButton(onClick = cb.cancel) { Icon(Icons.Filled.Close, t("取消扫描", "Cancel")) }
                    else IconButton(onClick = cb.refresh) { Icon(Icons.Filled.Refresh, t("重新扫描", "Rescan")) }
                    IconButton(onClick = cb.more) { Icon(Icons.Filled.MoreVert, null) }
                },
            )
        },
        bottomBar = {
            if (ui.tab == SpaceTab.TOP && ui.topSelected.isNotEmpty()) TopSelectBar(ui, cb)
            else if (ui.tab == SpaceTab.RESIDUE && ui.residueSelected.isNotEmpty() && !ui.residueScanning) ResidueCleanBar(ui, cb)
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth >= 720.dp
            if (tablet) Row(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                Column(Modifier.weight(1.35f).fillMaxHeight()) {
                    Header(ui, cb)
                    Spacer(Modifier.height(10.dp))
                    MapCard(ui, cb, Modifier.fillMaxWidth().weight(1f))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TabsRow(ui, cb)
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.fillMaxSize()) { tabItems(ui, cb, tablet = true) }
                }
            } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                item { Header(ui, cb) }
                item { Spacer(Modifier.height(10.dp)); TabsRow(ui, cb); Spacer(Modifier.height(10.dp)) }
                if (ui.tab == SpaceTab.MAP) item {
                    val w = this@BoxWithConstraints.maxWidth - 28.dp
                    MapCard(ui, cb, Modifier.fillMaxWidth().height((w * 0.92f).coerceAtMost(this@BoxWithConstraints.maxHeight * 0.62f)))
                    Spacer(Modifier.height(10.dp))
                }
                tabItems(ui, cb, tablet = false)
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}

@Composable
private fun NoticeCard(n: SpaceNotice, cb: SpaceCallbacks) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = cs.primary)
        Spacer(Modifier.width(8.dp))
        Text(n.text, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        if (n.action != null) TextButton(onClick = { n.actionId?.let(cb.notice) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(n.action, fontSize = 13.sp) }
    }
}

@Composable
private fun Header(ui: SpaceUi, cb: SpaceCallbacks) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = cb.scope), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.scopeLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = cs.outline)
            }
            Spacer(Modifier.width(8.dp))
            TierPill(ui.tier)
        }
        val s = ui.summary
        val total = s?.total ?: 0
        val used = if (s != null) (s.total - s.free).coerceAtLeast(0) else ui.current?.chain()?.first()?.size ?: 0
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatSize(used), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            if (s != null) Text("  / " + formatSize(total) + t(" 已用", " used"), fontSize = 13.sp, color = cs.outline, modifier = Modifier.padding(bottom = 4.dp))
            else Text(t("  已扫描", "  scanned"), fontSize = 13.sp, color = cs.outline, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (s != null && total > 0) {
            Spacer(Modifier.height(10.dp))
            UsageBar(ui)
            Spacer(Modifier.height(8.dp))
            Legend(ui)
        } else PartsBar(ui)
        if (ui.scanning || ui.error != null) {
            Spacer(Modifier.height(10.dp))
            if (ui.scanning) {
                val frac = if (used > 0 && ui.bytes > 0 && s != null) (ui.bytes.toFloat() / used).coerceIn(0f, 1f) else null
                if (frac != null) LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), trackColor = cs.surfaceVariant)
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), trackColor = cs.surfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (ui.background) t("后台刷新中 · ", "Refreshing · ") else t("扫描中 · ", "Scanning · ")) +
                        t("${"%,d".format(ui.files)} 个文件 · ", "${"%,d".format(ui.files)} files · ") + formatSize(ui.bytes),
                    fontSize = 12.sp, color = cs.onSurfaceVariant,
                )
                if (ui.currentPath.isNotEmpty()) Text(ui.currentPath, fontSize = 11.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ui.error?.let { Text(it, fontSize = 12.sp, color = cs.error) }
        } else if (ui.scannedAt > 0) {
            Spacer(Modifier.height(6.dp))
            Text(t("上次扫描 ", "Scanned ") + formatTime(ui.scannedAt), fontSize = 11.sp, color = cs.outline)
        }
        ui.notices.forEach { n ->
            Spacer(Modifier.height(10.dp))
            NoticeCard(n, cb)
        }
        Spacer(Modifier.height(12.dp))
        Crumbs(ui, cb)
        Spacer(Modifier.height(8.dp))
        Filters(ui, cb)
    }
}

@Composable
private fun TierPill(tier: SpaceTier) {
    val cs = MaterialTheme.colorScheme
    val g = Ghost.colors
    val c = when (tier) { SpaceTier.ROOT, SpaceTier.ADB_ROOT -> g.pdf; SpaceTier.SHIZUKU -> g.apk; else -> cs.primary }
    Text(tier.label(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c,
        modifier = Modifier.clip(CircleShape).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Category share bar: images / video / audio / apps / system / other files / free. */
@Composable
private fun UsageBar(ui: SpaceUi) {
    val cs = MaterialTheme.colorScheme
    val parts = summaryParts(ui)
    val total = ui.summary?.total?.toFloat()?.coerceAtLeast(1f) ?: 1f
    Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(cs.surfaceVariant)) {
        parts.forEach { (_, c, v) -> if (v > 0) Box(Modifier.weight((v / total).coerceAtLeast(0.004f)).fillMaxHeight().background(c)) }
        val rest = (total - parts.sumOf { it.third.toDouble() }.toFloat()) / total
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun summaryParts(ui: SpaceUi): List<Triple<String, Color, Float>> {
    val g = Ghost.colors
    val s = ui.summary ?: return emptyList()
    val c = s.categories
    val root = ui.tree?.root
    val appsBlock = root?.children?.firstOrNull { it.kind == NodeKind.AGGREGATE && it.flags == 0 && it.pkg == null && !it.children.isNullOrEmpty() }?.size
    val sys = root?.children?.firstOrNull { it.has(NodeFlags.SYSTEM) }?.size
    val used = (s.total - s.free).coerceAtLeast(0).toFloat()
    val out = ArrayList<Triple<String, Color, Float>>()
    if (c != null) {
        out.add(Triple(t("图片", "Images"), g.image, c.images.toFloat()))
        out.add(Triple(t("视频", "Video"), g.video, c.videos.toFloat()))
        out.add(Triple(t("音频", "Audio"), g.audio, c.audio.toFloat()))
    }
    if (appsBlock != null) out.add(Triple(t("应用", "Apps"), g.apk, appsBlock.toFloat()))
    if (sys != null) out.add(Triple(t("系统", "System"), g.other, sys.toFloat()))
    val known = out.sumOf { it.third.toDouble() }.toFloat()
    if (used - known > 0) out.add(Triple(t("其他文件", "Other files"), g.folder, used - known))
    return out
}

/** No system totals (Root whole device, folders, ADB): share of the top-level blocks of the scan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartsBar(ui: SpaceUi) {
    val root = ui.tree?.root ?: return
    val g = Ghost.colors
    val cs = MaterialTheme.colorScheme
    val palette = listOf(g.folder, g.video, g.archive, g.apk, g.image, g.audio)
    val kids = synchronized(ui.tree.lock) { root.children?.filter { it.size > 0 }?.sortedByDescending { it.size }?.take(6) ?: emptyList() }
    if (kids.size < 2 || root.size <= 0) return
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(cs.surfaceVariant)) {
        kids.forEachIndexed { i, k -> Box(Modifier.weight((k.size.toFloat() / root.size).coerceAtLeast(0.004f)).fillMaxHeight().background(palette[i % palette.size])) }
        val rest = 1f - kids.sumOf { it.size.toDouble() }.toFloat() / root.size
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        kids.forEachIndexed { i, k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(palette[i % palette.size]))
                Spacer(Modifier.width(5.dp))
                Text(nodeLabel(k, ui.app).substringBefore(" · ") + " " + formatSize(k.size), fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(ui: SpaceUi) {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        summaryParts(ui).forEach { (label, c, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(5.dp))
                Text("$label ${formatSize(v.toLong())}", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
        }
        ui.summary?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(cs.surfaceVariant).border(1.dp, cs.outlineVariant, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(t("可用 ", "Free ") + formatSize(s.free), fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Crumbs(ui: SpaceUi, cb: SpaceCallbacks) {
    val cs = MaterialTheme.colorScheme
    val chain = ui.current?.chain() ?: return
    val sc = rememberScrollState()
    LaunchedEffect(chain.size) { sc.scrollTo(sc.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(sc), verticalAlignment = Alignment.CenterVertically) {
        chain.forEachIndexed { i, n ->
            if (i > 0) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = cs.outline)
            val last = i == chain.size - 1
            Text(
                if (i == 0) rootLabel(ui, n) else nodeLabel(n, ui.app).substringBefore(" · "),
                fontSize = 13.sp, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) cs.onSurface else cs.primary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { cb.crumb(n) }.padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
    }
}

private val DAY = 86_400_000L

/** Name of the analysis root: the scope ("内部存储", "整机") or the analyzed folder's name. */
fun rootLabel(ui: SpaceUi, n: SpaceNode): String =
    if (n.parent != null) nodeLabel(n, ui.app).substringBefore(" · ")
    else if (ui.scopeLabel.contains("  ")) VPath.name(n.name).ifEmpty { "/" } else ui.scopeLabel

@Composable
private fun Filters(ui: SpaceUi, cb: SpaceCallbacks) {
    val f = ui.filter
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val cats = listOf<Pair<FileCategory?, String>>(null to t("全部类型", "All types")) + FileCategory.entries.map { it to catLabel(it) }
        FilterMenu(f.category?.let { catLabel(it) } ?: t("类型", "Type"), f.category != null, cats.map { it.second }) { i -> cb.filter(f.copy(category = cats[i].first)) }
        val sizes = listOf(0L to t("任意大小", "Any size"), (1L shl 20) to "≥ 1 MB", (10L shl 20) to "≥ 10 MB", (100L shl 20) to "≥ 100 MB", (1L shl 30) to "≥ 1 GB")
        FilterMenu(sizes.firstOrNull { it.first == f.minBytes && it.first > 0 }?.second ?: t("大小", "Size"), f.minBytes > 0, sizes.map { it.second }) { i -> cb.filter(f.copy(minBytes = sizes[i].first)) }
        val times = listOf(
            Triple(0L, 0L, t("任意时间", "Any time")),
            Triple(ui.now - 7 * DAY, 0L, t("7 天内", "Last 7 days")),
            Triple(ui.now - 30 * DAY, 0L, t("30 天内", "Last 30 days")),
            Triple(0L, ui.now - 180 * DAY, t("半年未改", "Older than 6 months")),
            Triple(0L, ui.now - 365 * DAY, t("一年未改", "Older than 1 year")),
        )
        val ti = times.indexOfFirst { (n, o) -> n > 0 && kotlin.math.abs(n - f.newerThan) < DAY || o > 0 && kotlin.math.abs(o - f.olderThan) < DAY }.takeIf { it > 0 }
        FilterMenu(ti?.let { times[it].third } ?: t("修改时间", "Modified"), f.newerThan > 0 || f.olderThan > 0, times.map { it.third }) { i -> cb.filter(f.copy(newerThan = times[i].first, olderThan = times[i].second)) }
        if (f.active) GhostFilterChip(selected = false, onClick = { cb.filter(SpaceFilter()) }, label = { Text(t("清除", "Clear")) }, leadingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) })
    }
}

@Composable
private fun FilterMenu(label: String, selected: Boolean, options: List<String>, pick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GhostFilterChip(selected = selected, onClick = { open = true }, label = { Text(label) },
            leadingIcon = null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; pick(i) }) }
        }
    }
}

fun catLabel(c: FileCategory) = when (c) {
    FileCategory.IMAGE -> t("图片", "Images"); FileCategory.VIDEO -> t("视频", "Video"); FileCategory.AUDIO -> t("音频", "Audio")
    FileCategory.DOC -> t("文档", "Docs"); FileCategory.ARCHIVE -> t("压缩包/镜像", "Archives"); FileCategory.APK -> t("安装包", "APKs")
    FileCategory.OTHER -> t("其他", "Other")
}

@Composable
private fun TabsRow(ui: SpaceUi, cb: SpaceCallbacks) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(cs.surfaceVariant).padding(3.dp)) {
        listOf(SpaceTab.MAP to t("目录", "Folders"), SpaceTab.TOP to t("大文件", "Largest"), SpaceTab.DUPS to t("重复", "Duplicates"), SpaceTab.EMPTY to t("空文件夹", "Empty"), SpaceTab.RESIDUE to t("残留", "Leftovers")).forEach { (tab, label) ->
            val on = ui.tab == tab
            Box(
                Modifier.weight(1f).height(32.dp).clip(CircleShape).background(if (on) cs.surface else Color.Transparent).clickable { cb.tab(tab) },
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) cs.onSurface else cs.onSurfaceVariant) }
        }
    }
}

// ---------------------------------------------------------------------- lists

private fun LazyListScope.tabItems(ui: SpaceUi, cb: SpaceCallbacks, tablet: Boolean) {
    when (ui.tab) {
        SpaceTab.MAP -> {
            val cur = ui.current ?: return
            val rows = rowsOf(ui, cur)
            val total = rows.sumOf { it.second }.coerceAtLeast(1)
            item { CardTop(t("${rows.size} 项", "${rows.size} items") + " · " + formatSize(total)) }
            items(rows.take(300), key = { System.identityHashCode(it.first) }) { (n, b) -> NodeRow(ui, cb, n, b, total, last = n === rows.take(300).last().first) }
            if (rows.isEmpty()) item { EmptyCard(if (ui.scanning) t("正在扫描…", "Scanning…") else if (cur.has(NodeFlags.UNREADABLE)) t("无法读取此文件夹", "This folder cannot be read") else t("没有内容", "Nothing here")) }
        }
        SpaceTab.TOP -> {
            val root = ui.current ?: return
            val top = synchronized(ui.tree?.lock ?: Any()) { SpaceQueries.topFiles(root, 100, ui.filter) }
            val max = top.firstOrNull()?.size ?: 1
            val share = (if (ui.filter.active) synchronized(ui.tree?.lock ?: Any()) { ui.filter.apply(root) } else root.size).coerceAtLeast(max)
            val visible = top.map { it.path() }
            val selecting = ui.topSelected.isNotEmpty()
            item {
                if (selecting) SelectTop(t("已选 ${ui.topSelected.size} 项", "${ui.topSelected.size} selected"), all = ui.topSelected.containsAll(visible), toggleAll = { if (ui.topSelected.containsAll(visible)) cb.topClear() else cb.topAll(visible) })
                else CardTop(t("最大的 ${top.size} 个文件", "Largest ${top.size} files") + " · " + rootLabel(ui, root) + t(" · 横滑多选", " · swipe to select"))
            }
            items(top, key = { System.identityHashCode(it) }) { n ->
                val p = n.path()
                NodeRow(ui, cb, n, n.size, share, showPath = true, last = n === top.last(),
                    checked = if (selecting) p in ui.topSelected else null,
                    onClick = if (selecting) ({ cb.topToggle(p) }) else null,
                    onSwipe = { cb.topSwipe(p, visible) })
            }
            if (top.isEmpty()) item { EmptyCard(t("没有文件", "No files")) }
        }
        SpaceTab.DUPS -> {
            val p = ui.dupProgress
            val d = ui.dups
            if (p != null) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    Text(t("正在比对大小相同的文件… ${p.first}/${p.second}", "Comparing same-size files… ${p.first}/${p.second}"), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (p.second > 0) p.first.toFloat() / p.second else 0f }, Modifier.fillMaxWidth().clip(CircleShape))
                }
            } else if (d == null) item { EmptyCard(t("先按大小分组，再对同大小的文件做哈希比对（≥ 1 MB）", "Groups by size, then hashes same-size files (≥ 1 MB)"), action = t("查找重复文件", "Find duplicates"), onAction = cb.findDups) }
            else {
                val wasted = d.sumOf { it.wasted }
                item(key = "dups-top") { DupTop(d.size, wasted, enabled = d.isNotEmpty()) { cb.dedupe(d) } }
                val shown = d.take(200)
                shown.forEach { g ->
                    item(key = "g" + g.size + g.paths.first()) { DupGroup(ui, cb, g, last = g === shown.last()) }
                }
                if (d.isEmpty()) item { EmptyCard(t("没有找到重复文件", "No duplicates found")) }
            }
        }
        SpaceTab.RESIDUE -> residueItems(ui, cb)
        SpaceTab.EMPTY -> {
            val root = ui.current ?: return
            val e = synchronized(ui.tree?.lock ?: Any()) { SpaceQueries.emptyDirs(root) }
            item { CardTop(t("${e.size} 个空文件夹", "${e.size} empty folders")) }
            items(e, key = { System.identityHashCode(it) }) { n -> NodeRow(ui, cb, n, 0, 1, showPath = true, last = n === e.last()) }
            if (e.isEmpty()) item { EmptyCard(t("没有空文件夹", "No empty folders")) }
        }
    }
}

fun rowsOf(ui: SpaceUi, cur: SpaceNode): List<Pair<SpaceNode, Long>> {
    val tree = ui.tree ?: return emptyList()
    return synchronized(tree.lock) {
        if (ui.filter.active) ui.filter.apply(cur)
        (cur.children ?: return emptyList()).map { it to (if (ui.filter.active) it.shown else it.size) }
            .filter { it.second > 0 || (!ui.filter.active && (it.first.has(NodeFlags.UNREADABLE) || it.first.kind == NodeKind.NOTICE)) }
            .sortedByDescending { it.second }
    }
}

@Composable
private fun CardTop(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(text, fontSize = 12.sp, color = cs.outline, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(cs.surface).padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun EmptyCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)).background(cs.surface).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 13.sp, color = cs.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(8.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    ui: SpaceUi, cb: SpaceCallbacks, n: SpaceNode, bytes: Long, total: Long, showPath: Boolean = false, last: Boolean = false,
    /** Multi-select checkbox state (null = not selecting). */
    checked: Boolean? = null, onClick: (() -> Unit)? = null, onSwipe: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val sel = checked ?: (ui.selected === n)
    val color = blockColor(n)
    val shape = if (last) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp)
    val swipePx = with(LocalDensity.current) { 40.dp.toPx() }
    val latestSwipe by rememberUpdatedState(onSwipe)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (sel) cs.primaryContainer else cs.surface)
            // MT: a horizontal swipe selects the row; a second swipe selects the range in between.
            .then(if (onSwipe == null) Modifier else Modifier.pointerInput(n) {
                var acc = 0f; var fired = false
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f; fired = false },
                    onHorizontalDrag = { change, d ->
                        acc += d
                        if (!fired && kotlin.math.abs(acc) > swipePx) { fired = true; latestSwipe?.invoke() }
                        change.consume()
                    },
                )
            })
            .combinedClickable(onClick = { if (onClick != null) onClick() else cb.open(n) }, onLongClick = { cb.long(n) })
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = n.pkg?.let { ui.app(it) }?.icon
        if (showPath && icon == null) RowThumb(ui, n.path(), n, color)
        else Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (icon != null) androidx.compose.foundation.Image(icon.asImageBitmap(), null, Modifier.size(26.dp))
            else Icon(nodeIcon(n), null, Modifier.size(19.dp), tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nodeLabel(n, ui.app), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (n.has(NodeFlags.RESIDUE)) Badge(t("残留", "Leftover"), Ghost.colors.archive)
                if (n.has(NodeFlags.READ_ONLY)) Badge(t("只读", "Read-only"), cs.outline)
                if (n.has(NodeFlags.UNREADABLE)) Badge(t("无权限", "No access"), cs.outline)
            }
            if (showPath) PathLine(displayPath(n.path()), ui.pathScroll, if (sel) cs.primaryContainer else cs.surface)
            val sub = when {
                showPath -> ""
                n.detail != null -> n.detail!!
                n.kind == NodeKind.DIR -> t("${n.count} 个文件", "${n.count} files") + (if (n.mtime > 0) " · " + formatTimeCompact(n.mtime, ui.now) else "")
                n.kind == NodeKind.FILE -> formatTimeCompact(n.mtime, ui.now)
                n.kind == NodeKind.SMALL_FILES -> t("每个小于 128 KB", "each under 128 KB")
                else -> ""
            }
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 1 && bytes > 0) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((bytes.toFloat() / total).coerceIn(0.01f, 1f)).fillMaxHeight().background(color.copy(alpha = 0.8f)))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatSize(bytes.takeIf { it > 0 } ?: n.size), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (total > 1 && bytes > 0) Text("%.1f%%".format(bytes * 100.0 / total), fontSize = 11.sp, color = cs.outline)
        }
        if (checked != null) SelectMark(checked, Modifier.padding(start = 10.dp))
    }
}

/** Round check like the panes' multi-select. */
@Composable
private fun SelectMark(on: Boolean, modifier: Modifier = Modifier, partial: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.size(22.dp).clip(CircleShape).background(if (on || partial) cs.primary else Color.Transparent)
        .border(1.5.dp, if (on || partial) cs.primary else cs.outline, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = cs.onPrimary)
        else if (partial) Icon(Icons.Rounded.Remove, null, Modifier.size(15.dp), tint = cs.onPrimary)
    }
}

@Composable
private fun SelectTop(text: String, all: Boolean, toggleAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(cs.surface).padding(start = 16.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = toggleAll, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (all) t("全不选", "Select none") else t("全选", "Select all"), fontSize = 12.sp) }
    }
}

/** Bottom actions for the selected largest files. */
@Composable
private fun TopSelectBar(ui: SpaceUi, cb: SpaceCallbacks) {
    val cs = MaterialTheme.colorScheme
    val bytes = remember(ui.topSelected, ui.version) { synchronized(ui.tree?.lock ?: Any()) { ui.topSelected.sumOf { ui.tree?.find(it)?.size ?: 0 } } }
    Surface(color = cs.surface, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("已选 ${ui.topSelected.size} 项 · ", "${ui.topSelected.size} selected · ") + formatSize(bytes), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = cb.topClear, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, t("退出多选", "Done"), Modifier.size(18.dp)) }
            }
            Row(Modifier.fillMaxWidth()) {
                BarAction(Icons.Rounded.Delete, t("删除", "Delete"), cs.error) { cb.topAction("delete") }
                BarAction(Icons.Rounded.Share, t("分享", "Share")) { cb.topAction("share") }
                BarAction(Icons.Rounded.ContentCopy, t("复制", "Copy")) { cb.topAction("copy") }
                BarAction(Icons.Rounded.DriveFileMove, t("移动", "Move")) { cb.topAction("move") }
            }
        }
    }
}

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val c = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, Modifier.size(22.dp), tint = c)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = c)
    }
}

// ---------------------------------------------------------------------- 卸载残留

fun residueKindLabel(k: ResidueKind) = when (k) {
    ResidueKind.DATA -> "data"; ResidueKind.OBB -> "obb"; ResidueKind.MEDIA -> "media"
    ResidueKind.PRIVATE -> t("私有数据", "private"); ResidueKind.PRIVATE_DE -> t("私有数据 DE", "private DE")
    ResidueKind.PUBLIC -> t("根目录", "storage root")
}

private fun LazyListScope.residueItems(ui: SpaceUi, cb: SpaceCallbacks) {
    val r = ui.residues
    if (ui.residueScanning && r == null) {
        item {
            CardTop(t("正在查找已卸载应用留下的文件夹…", "Looking for folders left by uninstalled apps…"))
            ResidueProgress()
        }
        return
    }
    if (r == null) {
        item {
            CardTop(t("卸载残留", "Uninstall leftovers"))
            EmptyCard(ui.residueError ?: t("查找 Android/data、obb、media 和根目录里已卸载应用的文件夹；Root 下还会查 /data/data", "Finds folders of uninstalled apps in Android/data, obb, media and the storage root; /data/data too with root"),
                action = if (ui.residueError != null) t("重试", "Retry") else t("查找残留", "Find leftovers"), onAction = cb.residueScan)
        }
        return
    }
    val all = r.groups.flatMap { g -> g.items.map { it.path } }
    val total = r.groups.sumOf { it.bytes }
    if (r.limited) item(key = "res-limited") {
        NoticeCard(SpaceNotice(t("未授权 Root / Shizuku：只能查看根目录的包名文件夹，Android/data、obb 和 /data/data 里的残留看不到。", "No root / Shizuku: only package folders in the storage root are checked; Android/data, obb and /data/data are skipped."),
            t("用 Shizuku", "Use Shizuku"), "shizuku"), cb)
        Spacer(Modifier.height(10.dp))
    }
    item(key = "res-top") {
        val cs = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(cs.surface).padding(start = 16.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("${r.groups.size} 个已卸载应用 · ", "${r.groups.size} uninstalled apps · ") + formatSize(total) + (r.via?.let { " · $it" } ?: ""),
                fontSize = 12.sp, color = cs.outline, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (all.isNotEmpty()) TextButton(onClick = { cb.residueSet(all, !ui.residueSelected.containsAll(all)) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (ui.residueSelected.containsAll(all)) t("全不选", "Select none") else t("全选", "Select all"), fontSize = 12.sp)
            }
            IconButton(onClick = cb.residueScan, enabled = !ui.residueScanning, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Refresh, t("重新查找", "Rescan"), Modifier.size(18.dp)) }
        }
    }
    if (ui.residueScanning) item(key = "res-busy") { ResidueProgress(square = true) }
    val shown = r.groups.take(300)
    shown.forEach { g -> item(key = "res-" + g.pkg) { ResidueGroupRow(ui, cb, g, last = g === shown.last()) } }
    if (r.groups.isEmpty()) item { EmptyCard(t("没有发现卸载残留", "No leftovers found")) }
}

@Composable
private fun ResidueProgress(square: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().then(if (square) Modifier else Modifier.clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))).background(cs.surface).padding(horizontal = 16.dp, vertical = 12.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), trackColor = cs.surfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResidueGroupRow(ui: SpaceUi, cb: SpaceCallbacks, g: ResidueGroup, last: Boolean) {
    val cs = MaterialTheme.colorScheme
    val paths = g.items.map { it.path }
    val on = ui.residueSelected.containsAll(paths)
    val some = !on && paths.any { it in ui.residueSelected }
    val meta = ui.residueApp(g.pkg)
    val shape = if (last) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(cs.surface).padding(bottom = 6.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth().clickable { cb.residueSet(paths, !on) }.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = Ghost.colors.archive
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                val icon = meta?.icon
                if (icon != null) androidx.compose.foundation.Image(icon.asImageBitmap(), null, Modifier.size(26.dp))
                else Icon(Icons.Rounded.FolderDelete, null, Modifier.size(19.dp), tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta?.label?.takeIf { it.isNotEmpty() && it != g.pkg } ?: g.pkg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (meta?.label?.takeIf { it != g.pkg } != null) g.pkg else t("已卸载", "Uninstalled") + " · " + t("${g.items.size} 处", "${g.items.size} folders"),
                    fontSize = 11.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(formatSize(g.bytes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            SelectMark(on, Modifier.padding(start = 10.dp), partial = some)
        }
        g.items.forEach { it ->
            val sel = it.path in ui.residueSelected
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { cb.residueSet(listOf(it.path), !sel) }, onLongClick = { cb.residueOpen(it) })
                .padding(start = 60.dp, end = 14.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val kc = if (it.kind == ResidueKind.PUBLIC) cs.outline else if (it.kind == ResidueKind.PRIVATE || it.kind == ResidueKind.PRIVATE_DE) cs.error else cs.primary
                Text(residueKindLabel(it.kind), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = kc, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(kc.copy(alpha = 0.13f)).padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(displayPath(it.shown), fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(formatSize(it.bytes), fontSize = 11.sp, color = cs.outline)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { SelectMark(sel, Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun ResidueCleanBar(ui: SpaceUi, cb: SpaceCallbacks) {
    val cs = MaterialTheme.colorScheme
    val chosen = ui.residues?.groups?.flatMap { it.items }?.filter { it.path in ui.residueSelected }.orEmpty()
    Surface(color = cs.surface, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(onClick = cb.residueClean, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(t("清理所选 ${chosen.size} 处 · ", "Remove ${chosen.size} · ") + formatSize(chosen.sumOf { it.bytes }))
            }
        }
    }
}

/** 清理残留 confirmation (direct delete, no recycle bin). */
@Composable
fun ResidueConfirmDialog(items: List<Residue>, dismiss: () -> Unit, confirm: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val apps = items.map { it.pkg }.distinct()
    val private = items.any { it.kind == ResidueKind.PRIVATE || it.kind == ResidueKind.PRIVATE_DE }
    val public = items.any { it.kind == ResidueKind.PUBLIC }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Rounded.CleaningServices, null, tint = cs.error) },
        title = { Text(t("清理卸载残留", "Remove leftovers")) },
        text = {
            Column {
                Text(t("${apps.size} 个已卸载应用，${items.size} 个文件夹，共 ", "${apps.size} uninstalled apps, ${items.size} folders, ") + formatSize(items.sumOf { it.bytes }), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(apps.take(4).joinToString("、") + if (apps.size > 4) t(" 等", " …") else "", fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text(t("将直接删除，不进回收站，不可恢复。", "Deleted directly (no recycle bin); this cannot be undone."), fontSize = 13.sp, color = cs.error)
                if (private) Text(t("含 /data/data 私有数据：重装这些应用后不会再恢复登录和设置。", "Includes /data/data private data: reinstalling won't bring back logins or settings."), fontSize = 12.sp, color = cs.onSurfaceVariant)
                if (public) Text(t("「根目录」项是按文件夹名推断的，可能是别的应用写的，请确认。", "\"Storage root\" items are guessed from the folder name; check them."), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = confirm) { Text(t("删除", "Delete"), color = cs.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

/** Batch delete of the selected largest files. */
@Composable
fun TopDeleteDialog(paths: List<String>, bytes: Long, trash: DupConfirm.Trash, dismiss: () -> Unit, confirm: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val risky = paths.any { SpacePolicy.protection(it) == Protection.CAUTION }
    var ack by remember { mutableStateOf(false) }
    val ok = !risky || ack
    AlertDialog(
        onDismissRequest = dismiss,
        icon = if (risky) ({ Icon(Icons.Rounded.WarningAmber, null, tint = cs.error) }) else null,
        title = { Text(t("删除 ${paths.size} 个文件", "Delete ${paths.size} files")) },
        text = {
            Column {
                Text(formatSize(bytes) + " · " + paths.take(3).joinToString("、") { VPath.name(it) } + if (paths.size > 3) t(" 等", " …") else "", fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val (ti, tc) = when (trash) {
                    DupConfirm.Trash.ON -> t("移入回收站，可在回收站恢复", "Moved to the recycle bin (restorable)") to cs.primary
                    DupConfirm.Trash.PARTIAL -> t("部分位置不支持回收站，这些文件将直接删除", "Some locations have no recycle bin; those files are deleted for good") to cs.error
                    DupConfirm.Trash.OFF -> t("将直接删除，不可恢复", "Deleted for good") to cs.error
                }
                Text(ti, fontSize = 12.sp, color = tc)
                if (risky) {
                    Spacer(Modifier.height(8.dp))
                    Text(t("其中有应用或系统数据目录里的文件，删除可能导致应用异常。", "Some are in app or system data; deleting them can break apps."), color = cs.error, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ack = !ack }) {
                        Checkbox(ack, { ack = it }); Text(t("我了解风险", "I understand the risk"), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = ok) { Text(t("删除", "Delete"), color = if (ok) cs.error else cs.outline) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
private fun Badge(text: String, c: Color) {
    Spacer(Modifier.width(6.dp))
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = c, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun DupTop(groups: Int, wasted: Long, enabled: Boolean, all: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(cs.surface).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("$groups 组重复 · 可省 ", "$groups groups · ") + formatSize(wasted) + t("", " reclaimable"), fontSize = 12.sp, color = cs.outline, modifier = Modifier.weight(1f))
        if (enabled) FilledTonalButton(onClick = all, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
            Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(t("全部一键处理", "Clean up all"), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DupGroup(ui: SpaceUi, cb: SpaceCallbacks, g: DuplicateFinder.Group, last: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val keep = ui.keepOf(g)
    val shape = if (last) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(cs.surface).padding(top = 6.dp, bottom = 4.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("${g.paths.size} 个相同 · 每个 ", "${g.paths.size} copies · ") + formatSize(g.size) + t(" · 可省 ", " · save ") + formatSize(g.wasted),
                fontSize = 12.sp, color = cs.outline, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { cb.dedupe(listOf(g)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(t("保留最优，删除其他", "Keep best, delete rest"), fontSize = 12.sp)
            }
        }
        g.paths.forEach { p ->
            val node = ui.tree?.find(p)
            val isKeep = p == keep
            val bg = if (isKeep) cs.primary.copy(alpha = 0.07f).compositeOver(cs.surface) else cs.surface
            Row(
                Modifier.fillMaxWidth().background(bg)
                    .combinedClickable(onClick = { cb.keep(g, p) }, onLongClick = { cb.longPath(p) })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val n = node ?: SpaceNode(p, NodeKind.FILE).also { it.category = FileCategory.of(p) }
                RowThumb(ui, p, n, blockColor(n))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(VPath.name(p), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            color = if (isKeep) cs.onSurface else cs.onSurface.copy(alpha = 0.86f))
                        if (isKeep) Badge(t("保留", "Keep"), cs.primary)
                    }
                    PathLine(displayPath(p), ui.pathScroll, bg)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (isKeep) Icon(Icons.Rounded.CheckCircle, t("保留", "Keep"), Modifier.size(20.dp), tint = cs.primary)
                    else Icon(Icons.Rounded.DeleteOutline, t("将删除", "Will delete"), Modifier.size(20.dp), tint = cs.outline.copy(alpha = 0.7f))
                    val m = node?.mtime ?: 0
                    if (m > 0) Text(formatTimeCompact(m, ui.now), fontSize = 10.sp, color = cs.outline)
                }
            }
        }
    }
}

/** 「内部存储/…」 instead of /storage/emulated/0 (and its aliases). */
fun displayPath(p: String): String {
    val m = Regex("^(/storage/emulated/0|/data/media/0|/sdcard|/storage/self/primary)(?=/|$)").find(p) ?: return p
    return t("内部存储", "Internal") + p.substring(m.range.last + 1)
}

/**
 * Full path on one line, scrolled sideways inside the row (the scroll consumes horizontal drags first,
 * so nothing outside sees them; taps and long-presses still reach the row). Edge fades show there is more.
 */
@Composable
fun PathLine(path: String, initial: Int = 0, bg: Color) {
    val cs = MaterialTheme.colorScheme
    val st = rememberScrollState(initial)
    Box(Modifier.fillMaxWidth().padding(top = 1.dp)) {
        Text(path, fontSize = 11.sp, color = cs.outline, maxLines = 1, softWrap = false, modifier = Modifier.horizontalScroll(st))
        if (st.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).width(28.dp).height(16.dp)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bg.copy(alpha = 0f), bg))))
        if (st.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).width(18.dp).height(16.dp)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0f)))))
    }
}

/** 34 dp thumbnail: image/video preview, APK icon, else the type icon. */
@Composable
private fun RowThumb(ui: SpaceUi, path: String, n: SpaceNode, color: Color) {
    val wants = n.kind == NodeKind.FILE && n.category in setOf(FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.APK)
    val src = ui.thumbs
    var bmp by remember(path, src) { mutableStateOf(if (wants && src != null) runCatching { src.cached(path, false) }.getOrNull() else null) }
    if (wants && src != null && bmp == null) LaunchedEffect(path, src) {
        bmp = withContext(Dispatchers.IO) { runCatching { src.load(path, false) }.getOrNull() }
    }
    val apk = n.category == FileCategory.APK
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (bmp != null && apk) Color.Transparent else color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), null,
            if (apk) Modifier.size(30.dp) else Modifier.fillMaxSize(),
            contentScale = if (apk) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(nodeIcon(n), null, Modifier.size(19.dp), tint = color)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: () -> Unit) = this.combinedClickable(onClick = onClick, onLongClick = onLong)

fun nodeIcon(n: SpaceNode): ImageVector = when (n.kind) {
    NodeKind.DIR -> if (n.has(NodeFlags.UNREADABLE)) Icons.Rounded.Lock else Icons.Rounded.Folder
    NodeKind.SMALL_FILES -> Icons.Rounded.Layers
    NodeKind.NOTICE -> Icons.Rounded.Info
    NodeKind.AGGREGATE -> when {
        n.has(NodeFlags.SYSTEM) -> Icons.Rounded.Android
        n.has(NodeFlags.FREE) -> Icons.Rounded.CheckBoxOutlineBlank
        else -> Icons.Rounded.Apps
    }
    NodeKind.FILE -> when (n.category) {
        FileCategory.IMAGE -> Icons.Rounded.Image; FileCategory.VIDEO -> Icons.Rounded.Movie; FileCategory.AUDIO -> Icons.Rounded.MusicNote
        FileCategory.DOC -> Icons.Rounded.Description; FileCategory.ARCHIVE -> Icons.Rounded.FolderZip; FileCategory.APK -> Icons.Rounded.Android
        FileCategory.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
fun blockColor(n: SpaceNode): Color {
    val g = Ghost.colors
    return when {
        n.has(NodeFlags.RESIDUE) -> g.archive
        n.has(NodeFlags.READ_ONLY) -> g.text
        n.kind == NodeKind.DIR -> g.folder
        n.kind == NodeKind.SMALL_FILES -> g.other
        n.kind == NodeKind.AGGREGATE -> when { n.has(NodeFlags.SYSTEM) -> g.other; n.has(NodeFlags.FREE) -> g.other; else -> g.apk }
        else -> when (n.category) {
            FileCategory.IMAGE -> g.image; FileCategory.VIDEO -> g.video; FileCategory.AUDIO -> g.audio; FileCategory.DOC -> g.text
            FileCategory.ARCHIVE -> g.archive; FileCategory.APK -> g.apk; FileCategory.OTHER -> g.other
        }
    }
}

// ---------------------------------------------------------------------- treemap

@Composable
private fun MapCard(ui: SpaceUi, cb: SpaceCallbacks, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(cs.surface).padding(8.dp)) {
        val cur = ui.current
        if (cur == null || ui.tree == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(10.dp)); Text(t("正在准备…", "Preparing…"), fontSize = 13.sp, color = cs.outline)
            }
        } else SpaceTreemap(ui, cur, cb, Modifier.fillMaxSize())
    }
}

@Composable
fun SpaceTreemap(ui: SpaceUi, cur: SpaceNode, cb: SpaceCallbacks, modifier: Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val cs = MaterialTheme.colorScheme
    val g = Ghost.colors
    val dark = g.dark
    // Resolve colors per kind/category once (Canvas draw scope is not composable).
    val palette = remember(g) { g }
    BoxWithConstraints(modifier) {
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
        val header = with(density) { 18.dp.toPx() }; val pad = with(density) { 3.dp.toPx() }; val nest = with(density) { 64.dp.toPx() }
        val placed = remember(cur, ui.version, ui.filter, wPx, hPx, ui.tree) {
            val tree = ui.tree!!
            synchronized(tree.lock) {
                val f = ui.filter
                if (f.active) f.apply(cur)
                fun kids(n: SpaceNode) = (n.children ?: emptyList()).map { it to if (f.active) it.shown else it.size }.filter { it.second > 0 }.sortedByDescending { it.second }
                TreemapLayout.layout(kids(cur), TRect(0f, 0f, wPx, hPx), header, pad, nest, { kids(it) })
            }
        }
        val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        val subStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)
        val selected = ui.selected
        val surface = cs.surface
        val primary = cs.primary
        val labels = remember(placed, ui.version) { placed.associateWith { p -> nodeLabelPlain(p.node, ui.app) } }
        Canvas(
            Modifier.fillMaxSize().pointerInput(placed) {
                detectTapGestures(
                    onTap = { o -> TreemapLayout.hit(placed, o.x, o.y)?.let { h -> cb.open(if (h.depth == 1 && h.node.kind != NodeKind.DIR) h.node.parent ?: h.node else h.node); if (h.depth == 1 && h.node.kind == NodeKind.FILE) cb.select(h.node) } },
                    onLongPress = { o -> TreemapLayout.hit(placed, o.x, o.y)?.let { cb.long(it.node) } },
                )
            },
        ) {
            val r0 = 7.dp.toPx(); val r1 = 4.dp.toPx(); val gap = 1.5.dp.toPx()
            for (p in placed) {
                val rect = p.rect
                if (rect.w < 2 || rect.h < 2) continue
                val c = colorOf(p.node, palette)
                val x = rect.x + gap / 2; val y = rect.y + gap / 2; val w = rect.w - gap; val h = rect.h - gap
                if (w <= 0 || h <= 0) continue
                val alpha = when {
                    p.node.kind == NodeKind.DIR && p.depth == 0 -> if (dark) 0.16f else 0.10f
                    p.node.kind == NodeKind.DIR -> if (dark) 0.30f else 0.22f
                    p.node.has(NodeFlags.FREE) -> 0.06f
                    else -> if (dark) 0.42f else 0.32f
                }
                val fill = c.copy(alpha = alpha).compositeOver(surface)
                val r = if (p.depth == 0) r0 else r1
                drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(r, r))
                if (p.node.has(NodeFlags.RESIDUE)) drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r, r), style = Stroke(1.5.dp.toPx()))
                if (p.node === selected) drawRoundRect(primary, Offset(x, y), Size(w, h), CornerRadius(r, r), style = Stroke(2.dp.toPx()))
                // Labels: folder header strip on depth 0; name + size inside blocks with room.
                val minW = 36.dp.toPx(); val minH = 16.dp.toPx()
                if (w >= minW && h >= minH) {
                    val label = labels[p] ?: ""
                    val maxW = (w - 8.dp.toPx()).toInt().coerceAtLeast(1)
                    val showSize = h >= 30.dp.toPx() || (p.depth == 0 && p.node.drillable && h >= header + 4)
                    clipRect(x, y, x + w, y + h) {
                        val tl = measurer.measure(label, labelStyle.copy(fontSize = if (p.depth == 0) 11.sp else 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxW))
                        drawText(tl, topLeft = Offset(x + 4.dp.toPx(), y + 3.dp.toPx()))
                        if (showSize) {
                            val sl = measurer.measure(formatSize(p.bytes), subStyle, maxLines = 1, constraints = Constraints(maxWidth = maxW))
                            val headerRow = p.depth == 0 && p.node.drillable && h >= nest + header
                            if (headerRow) {
                                // Size at the right of the header only when it does not run into the name.
                                if (tl.size.width + sl.size.width + 14.dp.toPx() <= w) drawText(sl, topLeft = Offset(x + w - sl.size.width - 4.dp.toPx(), y + 3.dp.toPx()))
                            } else drawText(sl, topLeft = Offset(x + 4.dp.toPx(), y + 3.dp.toPx() + tl.size.height))
                        }
                    }
                }
            }
        }
    }
}

private fun nodeLabelPlain(n: SpaceNode, app: (String) -> AppMeta?): String {
    if (n.kind == NodeKind.DIR && n.pkg != null) app(n.pkg!!)?.label?.let { return if (n.has(NodeFlags.RESIDUE)) "$it · 残留" else it }
    if (n.has(NodeFlags.RESIDUE)) return t("残留 ", "Leftover ") + n.name
    return nodeLabel(n, app)
}

private fun colorOf(n: SpaceNode, g: GhostColors): Color = when {
    n.has(NodeFlags.RESIDUE) -> g.archive
    n.has(NodeFlags.READ_ONLY) -> g.text
    n.kind == NodeKind.DIR -> g.folder
    n.kind == NodeKind.SMALL_FILES -> g.other
    n.kind == NodeKind.AGGREGATE -> if (n.has(NodeFlags.SYSTEM) || n.has(NodeFlags.FREE)) g.other else g.apk
    else -> when (n.category) {
        FileCategory.IMAGE -> g.image; FileCategory.VIDEO -> g.video; FileCategory.AUDIO -> g.audio; FileCategory.DOC -> g.text
        FileCategory.ARCHIVE -> g.archive; FileCategory.APK -> g.apk; FileCategory.OTHER -> g.other
    }
}

// ---------------------------------------------------------------------- dialogs

@Composable
fun SpaceDeleteDialog(n: SpaceNode, trash: Boolean, dismiss: () -> Unit, confirm: (useTrash: Boolean) -> Unit) {
    val prot = SpacePolicy.protection(n.path())
    var ack by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val word = t("确认删除", "DELETE")
    val ok = when (prot) { Protection.NONE -> true; Protection.CAUTION -> ack; Protection.SYSTEM_READ_ONLY -> typed.trim() == word }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = if (prot != Protection.NONE) ({ Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error) }) else null,
        title = { Text(t("删除", "Delete") + " " + n.name) },
        text = {
            Column {
                Text(formatSize(n.size) + (if (n.kind == NodeKind.DIR) " · " + t("${n.count} 个文件", "${n.count} files") else "") +
                    "\n" + if (trash && prot == Protection.NONE) t("将移入回收站", "Will be moved to the recycle bin") else t("此操作不可恢复", "This cannot be undone"))
                when (prot) {
                    Protection.CAUTION -> {
                        Spacer(Modifier.height(10.dp))
                        Text(t("这是应用或系统数据目录，删除可能导致应用异常或数据丢失。", "This is app or system data; deleting it can break apps or lose data."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ack = !ack }) {
                            Checkbox(ack, { ack = it }); Text(t("我了解风险", "I understand the risk"), fontSize = 13.sp)
                        }
                    }
                    Protection.SYSTEM_READ_ONLY -> {
                        Spacer(Modifier.height(10.dp))
                        Text(t("系统分区默认只读。删除系统文件可能导致无法开机；需先用「更多 → Root 挂载分区读写」重新挂载。输入「$word」继续：", "System partitions are read-only. Deleting system files can brick the device; remount read-write first. Type \"$word\" to continue:"), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        GhostTextField(typed, { typed = it }, singleLine = true)
                    }
                    Protection.NONE -> {}
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm(trash && prot == Protection.NONE) }, enabled = ok) { Text(t("删除", "Delete"), color = if (ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

/** What a 「保留最优，删除其他」 confirmation shows. */
data class DupConfirm(
    val all: Boolean, val groups: Int, val files: Int, val bytes: Long, val trash: Trash,
    val names: List<String>, val keeps: List<String>,
) { enum class Trash { ON, OFF, PARTIAL } }

@Composable
fun DupConfirmDialog(c: DupConfirm, dismiss: () -> Unit, confirm: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) { DupConfirmCard(c, dismiss, confirm) }
}

/** Dialog body (also rendered on its own in screenshots). */
@Composable
fun DupConfirmCard(c: DupConfirm, dismiss: () -> Unit = {}, confirm: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh).padding(24.dp)) {
        Icon(Icons.Rounded.AutoFixHigh, null, Modifier.align(Alignment.CenterHorizontally).size(26.dp), tint = cs.primary)
        Spacer(Modifier.height(12.dp))
        Text(if (c.all) t("全部一键处理", "Clean up all duplicates") else t("保留最优，删除其他", "Keep best, delete the rest"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ConfirmStat(c.files.toString(), t("个文件将删除", "files deleted"), cs.error)
            ConfirmStat(formatSize(c.bytes), t("可释放", "freed"), cs.primary)
            ConfirmStat(c.groups.toString(), t("组，每组保留 1 份", "groups, 1 kept each"), cs.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        val keepHint = if (c.groups == 1) t("保留：", "Keep: ") + (c.keeps.firstOrNull() ?: "") else t("每组保留标「保留」的那一份（可先点行改选）", "Each group keeps the one marked Keep (tap a row to change)")
        Text(keepHint, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        val shown = c.names.take(4)
        Text(t("删除：", "Delete: ") + shown.joinToString("、") + if (c.names.size > shown.size) t(" 等 ${c.names.size} 个", " and ${c.names.size - shown.size} more") else "",
            fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        val (ti, tc) = when (c.trash) {
            DupConfirm.Trash.ON -> t("移入回收站，可在回收站恢复", "Moved to the recycle bin (restorable)") to cs.primary
            DupConfirm.Trash.PARTIAL -> t("部分位置不支持回收站，这些文件将直接删除", "Some locations have no recycle bin; those files are deleted for good") to cs.error
            DupConfirm.Trash.OFF -> t("回收站已关闭：将直接删除，不可恢复", "Recycle bin is off: deleted for good") to cs.error
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c.trash == DupConfirm.Trash.ON) Icons.Rounded.RestoreFromTrash else Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = tc)
            Spacer(Modifier.width(6.dp)); Text(ti, fontSize = 12.sp, color = tc)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = confirm, enabled = c.files > 0, colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                Text(t("删除 ${c.files} 个", "Delete ${c.files}"))
            }
        }
    }
}

@Composable
private fun ConfirmStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 110.dp)) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun DupProgressDialog(b: DupBatch, stop: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh).padding(24.dp)) {
            Text(b.title.ifEmpty { t("正在删除重复文件…", "Deleting duplicates…") }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { if (b.total > 0) b.done.toFloat() / b.total else 0f }, Modifier.fillMaxWidth().clip(CircleShape))
            Spacer(Modifier.height(8.dp))
            Text("${b.done}/${b.total} · " + t("已释放 ", "freed ") + formatSize(b.bytes) + (if (b.failed > 0) t(" · 失败 ${b.failed}", " · ${b.failed} failed") else ""), fontSize = 13.sp, color = cs.onSurfaceVariant)
            if (b.current.isNotEmpty()) Text(b.current, fontSize = 12.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = stop) { Text(t("停止", "Stop")) } }
        }
    }
}

@Composable
private fun SpaceScopeDialog(vm: MainViewModel, dismiss: () -> Unit, pick: (fsId: String, whole: Boolean) -> Unit) {
    val adb = com.ghostpanter.files.ui.adb.rememberSavedDevices()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("分析范围", "Scope")) },
        text = {
            Column {
                ScopeRow(Icons.Rounded.PhoneAndroid, t("内部存储", "Internal storage"), when {
                    RootRunner.granted() -> t("Root：直接读 /data/media，更快", "Root: reads /data/media directly")
                    ShizukuRunner.granted() -> ElevatedAccess.label() + t("：含 Android/data、obb", ": includes Android/data, obb")
                    else -> t("非 Root", "No root")
                }) { pick(LocalFs.ID, false) }
                ScopeRow(Icons.Rounded.Security, t("整机（Root）", "Whole device (root)"), "/data · /system · /vendor · /product …") { pick(LocalFs.ID, true) }
                adb.forEach { serial ->
                    ScopeRow(Icons.Rounded.Devices, com.ghostpanter.files.ui.adb.adbLabel(serial), "ADB · /sdcard") { pick(com.ghostpanter.adb.AdbFs.idFor(serial, false), false) }
                    ScopeRow(Icons.Rounded.Devices, com.ghostpanter.files.ui.adb.adbLabel(serial) + " (Root)", "ADB su · /") { pick(com.ghostpanter.adb.AdbFs.idFor(serial, true), false) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(t("关闭", "Close")) } },
    )
}

@Composable
private fun ScopeRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column { Text(title, fontSize = 15.sp); Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
    }
}
