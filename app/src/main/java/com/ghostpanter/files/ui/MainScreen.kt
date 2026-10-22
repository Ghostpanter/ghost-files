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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    data class Hash(val pane: Int, val entry: FileEntry) : DialogState
    data class BatchRename(val pane: Int, val entries: List<FileEntry>) : DialogState
    data class Symlink(val pane: Int) : DialogState
    data class Remount(val pane: Int) : DialogState
    data class ZipEdit(val pane: Int, val entry: FileEntry) : DialogState
    data class SplitFile(val pane: Int, val entry: FileEntry) : DialogState
    data class ArchiveTest(val pane: Int, val entry: FileEntry) : DialogState
    data class PasswordOp(val pane: Int, val entry: FileEntry, val op: String) : DialogState
}

@Composable
fun MainScreen(vm: MainViewModel, activity: MainActivity) {
    val settings = vm.g.settings
    val layout = rememberWindowLayout(settings.layoutMode, settings.tabletSidePanel)
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var serverEditorOpen by remember { mutableStateOf(false) }
    val editServer: (ServerConfig?) -> Unit = { editingServer = it; serverEditorOpen = true }
    SideEffect { vm.tabletUi = layout.tablet }

    CompositionLocalProvider(LocalDensitySpec provides densitySpec(settings.listDensity)) {
        if (layout.tablet) TabletShell(vm, activity, layout, editServer) else PhoneShell(vm, activity, editServer)
    }

    // The space analysis page handles back itself (drill up first).
    if (vm.screen != Screen.Main && vm.screen !is Screen.Space) BackHandler { vm.screen = Screen.Main }

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
    vm.bundleInstall?.let { r -> InstallSheet(r, vm) { vm.bundleInstall = null } }
    vm.busy?.let { BusyDialog(it) { vm.cancelBusy() } }
    if (serverEditorOpen) ServerEditor(vm, editingServer) { serverEditorOpen = false }
}

/** Phone (compact) layout: modal drawer + MT frame with bottom toolbar. Unchanged from 0.6.8. */
@Composable
private fun PhoneShell(vm: MainViewModel, activity: MainActivity, editServer: (ServerConfig?) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawer,
        // Only close-by-swipe: an edge swipe must not steal the MT "swipe a row to select" gesture.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp),
                drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
            ) {
                DrawerContent(vm, activity, close = { scope.launch { drawer.close() } }, editServer = { editServer(it); scope.launch { drawer.close() } })
            }
        },
    ) {
        ScreenHost(vm, activity, tablet = null, openDrawer = { scope.launch { drawer.open() } }, editServer = editServer)
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
}

/**
 * Tablet / foldable / landscape layout: navigation rail (room for the phase-2 「投屏/设备」 entry),
 * drawer content as a persistent side panel on wide windows (modal drawer otherwise), and the
 * selected destination on the right.
 */
@Composable
private fun TabletShell(vm: MainViewModel, activity: MainActivity, layout: WindowLayout, editServer: (ServerConfig?) -> Unit) {
    val settings = vm.g.settings
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val jobs by vm.g.transfers.jobs.collectAsState()
    val activeJobs = jobs.count { !it.state.finished }
    val panelShown = layout.permanentPanel && settings.tabletSidePanel
    LaunchedEffect(layout.permanentPanel) { if (layout.permanentPanel) drawer.close() }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 340.dp),
                drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
            ) {
                DrawerContent(vm, activity, close = { scope.launch { drawer.close() } }, editServer = { editServer(it); scope.launch { drawer.close() } })
            }
        },
    ) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!vm.screen.immersive()) {
                GhostNavRail(
                    current = vm.screen, activeJobs = activeJobs, panelOpen = panelShown || drawer.isOpen,
                    onMenu = {
                        if (layout.permanentPanel) settings.tabletSidePanel = !settings.tabletSidePanel
                        else scope.launch { if (drawer.isOpen) drawer.close() else drawer.open() }
                    },
                    onPick = { vm.screen = it },
                )
                if (panelShown) {
                    Surface(Modifier.width(SIDE_PANEL_WIDTH_DP.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.statusBarsPadding().navigationBarsPadding()) {
                            DrawerContent(vm, activity, close = {}, editServer = editServer)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ScreenHost(vm, activity, tablet = layout, openDrawer = { scope.launch { drawer.open() } }, editServer = editServer)
            }
        }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
}

@Composable
private fun ScreenHost(vm: MainViewModel, activity: MainActivity, tablet: WindowLayout?, openDrawer: () -> Unit, editServer: (ServerConfig?) -> Unit) {
    when (val s = vm.screen) {
        Screen.Main -> MainContent(vm, activity, openDrawer = openDrawer, tablet = tablet)
        Screen.Transfers -> TransfersScreen(vm)
        Screen.Trash -> TrashScreen(vm)
        is Screen.Editor -> EditorScreen(vm, s)
        is Screen.Image -> ImageScreen(vm, s)
        is Screen.Apk -> ApkScreen(vm, activity, s)
        is Screen.Search -> SearchScreen(vm, s)
        Screen.LanScan -> LanScanScreen(vm, onEditServer = editServer)
        Screen.Servers -> ServersScreen(vm)
        is Screen.Hex -> HexScreen(vm, s)
        Screen.Apps -> AppsScreen(vm)
        is Screen.Player -> if (s.audioOnly) PlayerScreen(vm, s) else VlcPlayerScreen(vm, s)
        is Screen.ContentSearch -> ContentSearchScreen(vm, s)
        is Screen.Compare -> CompareScreen(vm, s)
        is Screen.Preview -> PreviewScreen(vm, s)
        Screen.Terminal -> TerminalScreen(vm)
        is Screen.MusicTag -> MusicTagScreen(vm, s)
        Screen.Devices -> com.ghostpanter.files.ui.adb.DevicesScreen(vm)
        is Screen.AdbTools -> com.ghostpanter.files.ui.adb.AdbToolsScreen(vm, s)
        is Screen.Space -> SpaceScreen(vm, s)
        is Screen.Backup -> BackupScreen(vm, s)
    }
}

@Composable
private fun MainContent(vm: MainViewModel, activity: MainActivity, openDrawer: () -> Unit, tablet: WindowLayout?) {
    val jobs by vm.g.transfers.jobs.collectAsState()
    val activeJobs = jobs.count { !it.state.finished }
    val pane = vm.pane(vm.activePane)
    val tab = pane.tab
    var ratio by remember { mutableFloatStateOf(vm.g.settings.paneRatio) }
    val focus = remember { FocusRequester() }

    BackHandler(enabled = vm.screen == Screen.Main) {
        if (tab.selecting) vm.clearSelection(tab) else if (!vm.back(tab)) activity.moveTaskToBack(true)
    }
    // Hardware keyboard: keep the frame focused so shortcuts work (not while a text field has focus).
    LaunchedEffect(vm.screen, vm.dialog, vm.menu, vm.pendingTransfer, PaneUi.rootFocusRequest) {
        if (vm.screen == Screen.Main && !PaneUi.textFocused) runCatching { focus.requestFocus() }
    }

    val onToolLong: (Tool) -> Unit = { tool ->
        when (tool) {
            Tool.UP -> vm.pathJump = pane
            Tool.SYNC -> { vm.syncOther(vm.other(pane)) }
            else -> {}
        }
    }
    val banner: (@Composable () -> Unit)? = if (!activity.hasStorageAccess()) ({
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("需要“所有文件访问权限”才能浏览存储", "All-files access is required"), Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = { activity.requestStorageAccess() }) { Text(t("去授权", "Grant")) }
            }
        }
    }) else null

    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { handleShortcut(vm, it) }) {
        if (tablet != null) {
            val settings = vm.g.settings
            var previewW by remember { mutableFloatStateOf(settings.previewWidthDp) }
            TabletMainFrame(
                host = vm,
                ratio = ratio,
                onRatio = { ratio = it },
                onRatioDone = { vm.g.settings.paneRatio = ratio },
                canForward = tab.forward.isNotEmpty(),
                hasClip = vm.clip != null,
                showPreview = tablet.previewAllowed && settings.tabletPreviewPanel,
                previewWidth = previewW,
                onPreviewWidth = { previewW = it },
                onPreviewWidthDone = { settings.previewWidthDp = previewW },
                onSearch = { vm.screen = Screen.Search(tab.fsId, tab.path) },
                onSwap = { vm.swapPanes() },
                onTool = { tool -> onTool(vm, pane, tool) },
                onToolLong = onToolLong,
                banner = banner,
            )
        } else MainFrame(
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
            onToolLong = onToolLong,
            banner = banner,
        )
    }
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDrawer) { Icon(Icons.Rounded.Menu, t("菜单", "Menu")) }
                    FrameTitle(tab, Modifier.weight(1f))
                    if (!tab.selecting) {
                        val filterOn = PaneUi.filterOpen || tab.filter.isNotEmpty()
                        IconButton(onClick = {
                            val open = !filterOn
                            PaneUi.filterOpen = open
                            if (!open) host.panes.forEach { p -> p.tab.filter = "" }
                        }) {
                            Icon(Icons.Rounded.FilterList, t("筛选", "Filter"), tint = if (filterOn) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                    }
                    IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, t("搜索", "Search")) }
                    IconButton(onClick = onSwap) { Icon(Icons.Rounded.SwapHoriz, t("交换窗口", "Swap panes")) }
                    IconButton(onClick = onTransfers) {
                        BadgedBox(badge = { if (activeJobs > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$activeJobs") } }) {
                            Icon(Icons.Rounded.SwapVert, t("传输队列", "Transfers"))
                        }
                    }
                }
                TopPathBar(host, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            }
        },
        bottomBar = {
            if (tab.selecting) SelectionBar(tab, onTool)
            else BottomToolbar(false, tab.history.isNotEmpty() || tab.path != "/", canForward, onTool, onToolLong)
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            banner?.invoke()
            DualPane(host, ratio, onRatio, onRatioDone, Modifier.weight(1f).padding(horizontal = 8.dp).padding(bottom = 6.dp))
        }
    }
}

/** Big bold page title; while selecting it becomes the selection count. */
@Composable
private fun FrameTitle(tab: TabState, modifier: Modifier) {
    Text(
        if (tab.selecting) t("已选 ${tab.selected.size} 项", "${tab.selected.size} selected") else t("文件", "Files"),
        style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 4.dp),
    )
}

/**
 * Selection action bar (direction D): count + size on the left with the select helpers, then the
 * four main actions as soft pills (复制 / 移动 / 删除 / 更多). Used by the phone and tablet frames.
 */
@Composable
fun SelectionBar(tab: TabState, onTool: (Tool) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sel = tab.selectedEntries()
    val bytes = sel.filter { !it.isDirectory }.sumOf { it.size }
    Surface(color = cs.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = cs.outlineVariant)
            Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("已选 ${tab.selected.size} 项", "${tab.selected.size} selected") + if (bytes > 0) "  ·  " + formatSize(bytes) else "",
                    fontSize = 12.sp, color = cs.outline, modifier = Modifier.weight(1f), maxLines = 1,
                )
                SmallTextAction(t("全选", "All")) { onTool(Tool.SELECT_ALL) }
                SmallTextAction(t("反选", "Invert")) { onTool(Tool.INVERT) }
                SmallTextAction(t("类选", "Type")) { onTool(Tool.SELECT_TYPE) }
                SmallTextAction(t("完成", "Done"), accent = true) { onTool(Tool.DONE) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillAction(t("复制", "Copy"), Icons.Rounded.ContentCopy, Modifier.weight(1f), accent = true) { onTool(Tool.COPY_OTHER) }
                PillAction(t("移动", "Move"), Icons.AutoMirrored.Rounded.DriveFileMove, Modifier.weight(1f)) { onTool(Tool.MOVE_OTHER) }
                PillAction(t("删除", "Delete"), Icons.Rounded.DeleteOutline, Modifier.weight(1f)) { onTool(Tool.DELETE) }
                PillAction(t("更多", "More"), Icons.Rounded.MoreHoriz, Modifier.weight(1f)) { onTool(Tool.SEL_MORE) }
            }
        }
    }
}

@Composable
private fun SmallTextAction(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun PillAction(label: String, icon: ImageVector, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (accent) cs.primary else cs.onSurface
    Row(
        modifier.height(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(if (accent) cs.primaryContainer else cs.surfaceVariant).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

private data class ToolSpec(val tool: Tool, val icon: ImageVector, val label: String, val enabled: Boolean = true)

@Composable
fun BottomToolbar(selecting: Boolean, canBack: Boolean, canForward: Boolean, onTool: (Tool) -> Unit, onToolLong: (Tool) -> Unit) {
    val specs = if (!selecting) listOf(
        ToolSpec(Tool.BACK, Icons.AutoMirrored.Rounded.ArrowBack, t("后退", "Back"), canBack),
        ToolSpec(Tool.FORWARD, Icons.AutoMirrored.Rounded.ArrowForward, t("前进", "Fwd"), canForward),
        ToolSpec(Tool.REFRESH, Icons.Rounded.Refresh, t("刷新", "Refresh")),
        ToolSpec(Tool.SYNC, Icons.Rounded.SyncAlt, t("同步", "Sync")),
        ToolSpec(Tool.MULTI, Icons.Rounded.Checklist, t("多选", "Select")),
        ToolSpec(Tool.NEW, Icons.Rounded.Add, t("新建", "New")),
        ToolSpec(Tool.UP, Icons.Rounded.ArrowUpward, t("上级", "Up")),
        ToolSpec(Tool.MORE, Icons.Rounded.MoreHoriz, t("更多", "More")),
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
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            specs.forEach { s -> ToolButton(s, Modifier.weight(1f), { onTool(s.tool) }, { onToolLong(s.tool) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(s: ToolSpec, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val color = if (s.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Column(
        modifier.fillMaxHeight().padding(vertical = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .combinedClickable(enabled = s.enabled, onClick = onClick, onLongClick = onLong),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(s.icon, s.label, Modifier.size(22.dp), tint = color)
        Spacer(Modifier.height(2.dp))
        Text(s.label, fontSize = 10.sp, maxLines = 1, color = color, fontWeight = FontWeight.Medium)
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
                Text(menu.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                menu.subtitle?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                menu.actions.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable(enabled = a.enabled, onClick = a.run).padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(a.icon, null, Modifier.size(20.dp), tint = if (a.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(Modifier.width(16.dp))
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

/**
 * Tablet main frame: no bottom bar. The toolbar actions (acting on the focused pane) sit in one
 * top bar; every pane has its own path header; optional details panel on the right.
 * Stateless like [MainFrame] so screenshot tests can render it.
 */
@Composable
fun TabletMainFrame(
    host: PaneHost,
    ratio: Float,
    onRatio: (Float) -> Unit,
    onRatioDone: () -> Unit,
    canForward: Boolean,
    hasClip: Boolean,
    showPreview: Boolean,
    previewWidth: Float,
    onPreviewWidth: (Float) -> Unit,
    onPreviewWidthDone: () -> Unit,
    onSearch: () -> Unit,
    onSwap: () -> Unit,
    onTool: (Tool) -> Unit,
    onToolLong: (Tool) -> Unit,
    banner: (@Composable () -> Unit)? = null,
) {
    val tab = host.panes[host.activePane].tab
    val canBack = tab.history.isNotEmpty() || tab.path != "/"
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FrameTitle(tab, Modifier.widthIn(min = 72.dp).padding(end = 12.dp))
                // Narrow windows (forced tablet mode on a phone, split screen): the tool group scrolls
                // horizontally so the right-hand group (More / Done) never gets pushed off.
                val toolScroll = rememberScrollState()
                Row(
                    Modifier.weight(1f).horizontalScroll(toolScroll, reverseScrolling = true),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                ) {
                    if (tab.selecting) {
                        BarTool(Icons.Rounded.SelectAll, t("全选 (Ctrl+A)", "Select all (Ctrl+A)")) { onTool(Tool.SELECT_ALL) }
                        BarTool(Icons.Rounded.Flip, t("反选", "Invert")) { onTool(Tool.INVERT) }
                        BarTool(Icons.Rounded.Category, t("类选", "Same type")) { onTool(Tool.SELECT_TYPE) }
                    } else {
                        BarTool(Icons.AutoMirrored.Rounded.ArrowBack, t("后退 (Alt+←)", "Back (Alt+←)"), enabled = canBack) { onTool(Tool.BACK) }
                        BarTool(Icons.AutoMirrored.Rounded.ArrowForward, t("前进 (Alt+→)", "Forward (Alt+→)"), enabled = canForward) { onTool(Tool.FORWARD) }
                        BarTool(Icons.Rounded.ArrowUpward, t("上级 (Backspace，长按跳转)", "Up (Backspace, hold: go to)"), onLong = { onToolLong(Tool.UP) }) { onTool(Tool.UP) }
                        BarTool(Icons.Rounded.Refresh, t("刷新 (F5)", "Refresh (F5)")) { onTool(Tool.REFRESH) }
                        BarDivider()
                        BarTool(Icons.Rounded.Add, t("新建", "New")) { onTool(Tool.NEW) }
                        BarTool(Icons.Rounded.Checklist, t("多选", "Select")) { onTool(Tool.MULTI) }
                        if (hasClip) BarTool(Icons.Rounded.ContentPaste, t("粘贴 (Ctrl+V)", "Paste (Ctrl+V)")) { onTool(Tool.PASTE) }
                        BarTool(Icons.Rounded.SyncAlt, t("同步到对面（长按反向）", "Sync other pane (hold: reverse)"), onLong = { onToolLong(Tool.SYNC) }) { onTool(Tool.SYNC) }
                        BarTool(Icons.Rounded.SwapHoriz, t("交换窗口", "Swap panes")) { onSwap() }
                        BarDivider()
                    }
                }
                if (tab.selecting) {
                    BarTool(Icons.Rounded.Close, t("取消选择 (Esc)", "Done (Esc)")) { onTool(Tool.DONE) }
                } else {
                    val filterOn = PaneUi.filterOpen || tab.filter.isNotEmpty()
                    BarTool(Icons.Rounded.FilterList, t("筛选 (Ctrl+F)", "Filter (Ctrl+F)"), tint = if (filterOn) MaterialTheme.colorScheme.primary else null) {
                        val open = !filterOn
                        PaneUi.filterOpen = open
                        if (!open) host.panes.forEach { p -> p.tab.filter = "" } else PaneUi.filterFocusPending = true
                    }
                    BarTool(Icons.Rounded.Search, t("搜索", "Search")) { onSearch() }
                    BarTool(Icons.Rounded.MoreVert, t("更多", "More")) { onTool(Tool.MORE) }
                }
            }
            TopPathBar(host, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
        }
        banner?.invoke()
        Row(
            Modifier.weight(1f).fillMaxWidth().then(if (tab.selecting) Modifier else Modifier.navigationBarsPadding())
                .padding(start = 8.dp, end = 12.dp, bottom = 10.dp),
        ) {
            DualPane(host, ratio, onRatio, onRatioDone, Modifier.weight(1f), perPaneHeader = false, dragAndDrop = true)
            if (showPreview) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                SplitHandle(
                    onDelta = { d -> onPreviewWidth((previewWidth - with(density) { d.toDp().value }).coerceIn(220f, 520f)) },
                    onDone = onPreviewWidthDone,
                    onReset = { onPreviewWidth(300f); onPreviewWidthDone() },
                    width = 8.dp,
                )
                DetailsPanel(host, Modifier.width(previewWidth.dp))
            }
        }
        if (tab.selecting) SelectionBar(tab, onTool)
    } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BarTool(icon: ImageVector, label: String, enabled: Boolean = true, tint: androidx.compose.ui.graphics.Color? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        val color = tint ?: if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        Box(
            Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(22.dp), tint = color) }
    }
}

@Composable
private fun BarDivider() {
    VerticalDivider(Modifier.height(20.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
