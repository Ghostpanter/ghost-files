package com.ghostpanter.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.ServerConfig
import com.ghostpanter.files.core.transfer.ConflictPolicy
import com.ghostpanter.files.core.transfer.TransferOp
import com.ghostpanter.files.data.SortBy
import com.ghostpanter.files.t
import kotlinx.coroutines.launch

/** Which per-entry dialog is open. */
sealed interface DialogState {
    data class Delete(val pane: Int, val entries: List<FileEntry>) : DialogState
    data class Rename(val pane: Int, val entry: FileEntry) : DialogState
    data class Create(val pane: Int, val dir: Boolean) : DialogState
    data class Compress(val pane: Int, val entries: List<FileEntry>) : DialogState
    data class Properties(val pane: Int, val entry: FileEntry) : DialogState
    data class Chmod(val pane: Int, val entry: FileEntry) : DialogState
    data object Sort : DialogState
}

@Composable
fun MainScreen(vm: MainViewModel, activity: MainActivity) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var serverEditorOpen by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Only close-by-swipe: an edge swipe must not steal the MT "swipe a row to select" gesture.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp), drawerShape = androidx.compose.ui.graphics.RectangleShape) {
                DrawerContent(vm, activity, close = { scope.launch { drawer.close() } }, editServer = {
                    editingServer = it; serverEditorOpen = true
                })
            }
        },
    ) {
        when (val s = vm.screen) {
            Screen.Main -> MainContent(vm, activity, openDrawer = { scope.launch { drawer.open() } })
            Screen.Transfers -> TransfersScreen(vm)
            Screen.Trash -> TrashScreen(vm)
            is Screen.Editor -> EditorScreen(vm, s)
            is Screen.Image -> ImageScreen(vm, s)
            is Screen.Apk -> ApkScreen(vm, activity, s)
            is Screen.Search -> SearchScreen(vm, s)
            Screen.LanScan -> LanScanScreen(vm, onEditServer = { editingServer = it; serverEditorOpen = true })
            Screen.Servers -> ServersScreen(vm)
        }
    }

    if (vm.screen != Screen.Main) BackHandler { vm.screen = Screen.Main }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    vm.dialog?.let { d -> EntryDialogs(vm, d) { vm.dialog = null } }
    vm.menu?.let { m -> ActionMenuDialog(m) { vm.menu = null } }
    vm.pathJump?.let { p ->
        NameDialog(t("跳转到路径", "Go to path"), p.tab.path, dismiss = { vm.pathJump = null }, selectAll = true) { path ->
            if (path.isNotBlank()) vm.navigate(p.tab, p.tab.fsId, if (path.startsWith("/")) path else com.ghostpanter.files.core.VPath.join(p.tab.path, path))
        }
    }
    vm.pendingTransfer?.let { TransferConfirmDialog(it) { policy -> vm.confirmTransfer(policy) } }
    vm.pendingExtract?.let { (tab, e) -> ExtractDialog(e, onDismiss = { vm.pendingExtract = null }) { sub, pw ->
        vm.pendingExtract = null; vm.extract(tab, e, sub, pw)
    } }
    vm.busy?.let { BusyDialog(it) { vm.cancelBusy() } }
    if (serverEditorOpen) ServerEditor(vm, editingServer) { serverEditorOpen = false }
}

@Composable
private fun MainContent(vm: MainViewModel, activity: MainActivity, openDrawer: () -> Unit) {
    val jobs by vm.g.transfers.jobs.collectAsState()
    val activeJobs = jobs.count { !it.state.finished }
    val pane = vm.pane(vm.activePane)
    val tab = pane.tab
    var ratio by remember { mutableFloatStateOf(vm.g.settings.paneRatio) }

    BackHandler(enabled = vm.screen == Screen.Main) {
        if (tab.selecting) vm.clearSelection(tab) else if (!vm.back(tab)) activity.moveTaskToBack(true)
    }

    MainFrame(
        host = vm,
        activeJobs = activeJobs,
        ratio = ratio,
        onRatio = { ratio = it },
        onRatioDone = { vm.g.settings.paneRatio = ratio },
        canForward = tab.forward.isNotEmpty(),
        onDrawer = openDrawer,
        onSearch = { vm.screen = Screen.Search(tab.fsId, tab.path) },
        onTransfers = { vm.screen = Screen.Transfers },
        onSwap = { vm.swapPanes() },
        onTool = { tool -> onTool(vm, pane, tool) },
        onToolLong = { tool ->
            when (tool) {
                Tool.UP -> vm.pathJump = pane
                Tool.SYNC -> { vm.syncOther(vm.other(pane)) }
                else -> {}
            }
        },
        banner = if (!activity.hasStorageAccess()) ({
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("需要“所有文件访问权限”才能浏览存储", "All-files access is required"), Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = { activity.requestStorageAccess() }) { Text(t("去授权", "Grant")) }
                }
            }
        }) else null,
    )
}

private fun onTool(vm: MainViewModel, pane: PaneState, tool: Tool) {
    val tab = pane.tab
    val visible = computeVisible(vm.prefs, tab)
    when (tool) {
        Tool.BACK -> vm.back(tab)
        Tool.FORWARD -> if (!vm.forward(tab)) vm.toast(t("没有可前进的记录", "Nothing to go forward to"))
        Tool.REFRESH -> vm.refresh(tab)
        Tool.SYNC -> vm.syncOther(pane)
        Tool.MULTI -> { tab.multiSelect = true }
        Tool.NEW -> vm.menu = ActionMenu(t("新建", "New"), vm.fsm.label(tab.fsId) + "  " + tab.path, listOf(
            MenuAction(t("文件夹", "Folder"), Icons.Filled.CreateNewFolder) { vm.menu = null; vm.dialog = DialogState.Create(pane.index, true) },
            MenuAction(t("文件", "File"), Icons.AutoMirrored.Filled.NoteAdd) { vm.menu = null; vm.dialog = DialogState.Create(pane.index, false) },
            MenuAction(t("新标签页", "Tab"), Icons.Filled.Tab) { vm.menu = null; vm.newTab(pane) },
        ))
        Tool.UP -> vm.up(tab)
        Tool.MORE -> vm.menu = vm.buildMoreMenu(pane)
        Tool.SELECT_ALL -> vm.selectAll(tab, visible)
        Tool.INVERT -> vm.invertSelection(tab, visible)
        Tool.SELECT_TYPE -> vm.selectSameType(tab, visible)
        Tool.COPY_OTHER -> vm.transferToOther(pane, TransferOp.COPY)
        Tool.MOVE_OTHER -> vm.transferToOther(pane, TransferOp.MOVE)
        Tool.DELETE -> tab.selectedEntries().takeIf { it.isNotEmpty() }?.let { vm.dialog = DialogState.Delete(pane.index, it) }
        Tool.SEL_MORE -> if (tab.selected.isEmpty()) vm.toast(t("未选择文件", "Nothing selected")) else vm.menu = vm.buildSelectionMenu(pane)
        Tool.DONE -> vm.clearSelection(tab)
        Tool.PASTE -> vm.paste(tab)
    }
}

/**
 * The whole MT-style main frame: compact top bar, two always-visible panes and the shared bottom
 * toolbar. Stateless (driven by [PaneHost]) so screenshot tests can render it with fake data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainFrame(
    host: PaneHost,
    activeJobs: Int,
    ratio: Float,
    onRatio: (Float) -> Unit,
    onRatioDone: () -> Unit,
    canForward: Boolean,
    onDrawer: () -> Unit,
    onSearch: () -> Unit,
    onTransfers: () -> Unit,
    onSwap: () -> Unit,
    onTool: (Tool) -> Unit,
    onToolLong: (Tool) -> Unit,
    banner: (@Composable () -> Unit)? = null,
) {
    val tab = host.panes[host.activePane].tab
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDrawer) { Icon(Icons.Filled.Menu, t("菜单", "Menu")) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (tab.selecting) t("已选 ${tab.selected.size} 项", "${tab.selected.size} selected") else t("Ghost 文件", "Ghost Files"),
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        )
                        Text(
                            (if (host.activePane == 0) t("左窗口", "Left") else t("右窗口", "Right")) + " · " + host.fsLabel(tab.fsId),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, t("搜索", "Search")) }
                    IconButton(onClick = onSwap) { Icon(Icons.Filled.SwapHoriz, t("交换窗口", "Swap panes")) }
                    IconButton(onClick = onTransfers) {
                        BadgedBox(badge = { if (activeJobs > 0) Badge { Text("$activeJobs") } }) { Icon(Icons.Filled.SwapVert, t("传输队列", "Transfers")) }
                    }
                }
            }
        },
        bottomBar = { BottomToolbar(tab.selecting, tab.history.isNotEmpty() || tab.path != "/", canForward, onTool, onToolLong) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            banner?.invoke()
            DualPane(host, ratio, onRatio, onRatioDone, Modifier.weight(1f))
        }
    }
}

private data class ToolSpec(val tool: Tool, val icon: ImageVector, val label: String, val enabled: Boolean = true)

@Composable
fun BottomToolbar(selecting: Boolean, canBack: Boolean, canForward: Boolean, onTool: (Tool) -> Unit, onToolLong: (Tool) -> Unit) {
    val specs = if (!selecting) listOf(
        ToolSpec(Tool.BACK, Icons.AutoMirrored.Filled.ArrowBack, t("后退", "Back"), canBack),
        ToolSpec(Tool.FORWARD, Icons.AutoMirrored.Filled.ArrowForward, t("前进", "Fwd"), canForward),
        ToolSpec(Tool.REFRESH, Icons.Filled.Refresh, t("刷新", "Refresh")),
        ToolSpec(Tool.SYNC, Icons.Filled.SyncAlt, t("同步", "Sync")),
        ToolSpec(Tool.MULTI, Icons.Filled.Checklist, t("多选", "Select")),
        ToolSpec(Tool.NEW, Icons.Filled.Add, t("新建", "New")),
        ToolSpec(Tool.UP, Icons.Filled.ArrowUpward, t("上级", "Up")),
        ToolSpec(Tool.MORE, Icons.Filled.MoreHoriz, t("更多", "More")),
    ) else listOf(
        ToolSpec(Tool.SELECT_ALL, Icons.Filled.SelectAll, t("全选", "All")),
        ToolSpec(Tool.INVERT, Icons.Filled.Flip, t("反选", "Invert")),
        ToolSpec(Tool.SELECT_TYPE, Icons.Filled.Category, t("类选", "Type")),
        ToolSpec(Tool.COPY_OTHER, Icons.Filled.FileCopy, t("复制→", "Copy→")),
        ToolSpec(Tool.MOVE_OTHER, Icons.AutoMirrored.Filled.DriveFileMove, t("移动→", "Move→")),
        ToolSpec(Tool.DELETE, Icons.Filled.Delete, t("删除", "Delete")),
        ToolSpec(Tool.SEL_MORE, Icons.Filled.MoreHoriz, t("更多", "More")),
        ToolSpec(Tool.DONE, Icons.Filled.Close, t("取消", "Done")),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            specs.forEach { s -> ToolButton(s, Modifier.weight(1f), { onTool(s.tool) }, { onToolLong(s.tool) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(s: ToolSpec, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Column(
        modifier.fillMaxHeight().combinedClickable(enabled = s.enabled, onClick = onClick, onLongClick = onLong),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(s.icon, s.label, Modifier.size(21.dp), tint = color)
        Text(s.label, fontSize = 10.sp, maxLines = 1, color = color)
    }
}

/** MT-style list menu (used for long-press and "more"). */
@Composable
fun ActionMenuDialog(menu: ActionMenu, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
        title = {
            Column {
                Text(menu.title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                menu.subtitle?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                menu.actions.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = a.enabled, onClick = a.run).padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(a.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, fontSize = 14.sp)
                            a.hint?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        },
    )
}

/** Copy/move confirmation showing the target directory (MT style), merged with conflict handling. */
@Composable
fun TransferConfirmDialog(p: PendingTransfer, onChoose: (ConflictPolicy?) -> Unit) {
    var policy by remember(p.conflicts) { mutableStateOf(if (p.conflicts.isEmpty()) ConflictPolicy.RENAME else ConflictPolicy.RESUME) }
    val verb = if (p.op == TransferOp.COPY) t("复制", "Copy") else t("移动", "Move")
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(t("$verb ${p.names.size} 项到另一窗口", "$verb ${p.names.size} item(s)")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(p.names.take(4).joinToString("\n") + if (p.names.size > 4) "\n…" else "", fontSize = 13.sp, maxLines = 6)
                Spacer(Modifier.height(8.dp))
                Text(t("目标目录", "Target"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Text(p.destLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                if (p.checking) {
                    Spacer(Modifier.height(6.dp))
                    Text(t("正在检查同名文件…", "Checking for conflicts…"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
                if (p.conflicts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(t("目标已存在 ${p.conflicts.size} 项：", "${p.conflicts.size} already exist:"), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Text(p.conflicts.take(3).joinToString("、") + if (p.conflicts.size > 3) " …" else "", fontSize = 11.sp)
                    for ((pol, label) in listOf(
                        ConflictPolicy.RESUME to t("续传 / 跳过相同（推荐）", "Resume / skip identical"),
                        ConflictPolicy.OVERWRITE to t("全部覆盖", "Overwrite all"),
                        ConflictPolicy.SKIP to t("全部跳过", "Skip all"),
                        ConflictPolicy.RENAME to t("保留两者（自动重命名）", "Keep both (rename)"),
                    )) {
                        Row(Modifier.fillMaxWidth().clickable { policy = pol }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(policy == pol, { policy = pol }, Modifier.size(36.dp)); Text(label, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(policy) }, enabled = !p.checking) { Text(t("确定", "OK")) } },
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun SortDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val s = vm.g.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("完成", "Done")) } },
        title = { Text(t("排序", "Sort")) },
        text = {
            Column {
                for ((by, label) in listOf(SortBy.NAME to t("名称", "Name"), SortBy.SIZE to t("大小", "Size"), SortBy.TIME to t("修改时间", "Modified"), SortBy.TYPE to t("类型", "Type"))) {
                    Row(Modifier.fillMaxWidth().clickable { s.sortBy = by }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.sortBy == by, onClick = { s.sortBy = by }); Text(label)
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.ascending, { s.ascending = it }); Text(t("升序", "Ascending")) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.foldersFirst, { s.foldersFirst = it }); Text(t("文件夹优先", "Folders first")) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.showHidden, { s.showHidden = it }); Text(t("显示隐藏文件", "Show hidden")) }
            }
        },
    )
}
