package com.ghostpanter.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.LocalFs
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, activity: MainActivity) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var editingServer by remember { mutableStateOf<com.ghostpanter.files.core.ServerConfig?>(null) }
    var serverEditorOpen by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = vm.screen == Screen.Main,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 340.dp)) {
                DrawerContent(vm, activity, close = { scope.launch { drawer.close() } }, editServer = {
                    editingServer = it; serverEditorOpen = true
                })
            }
        },
    ) {
        when (val s = vm.screen) {
            Screen.Main -> MainContent(vm, activity, openDrawer = { scope.launch { drawer.open() } }, showDialog = { dialog = it })
            Screen.Transfers -> TransfersScreen(vm)
            Screen.Trash -> TrashScreen(vm)
            is Screen.Editor -> EditorScreen(vm, s)
            is Screen.Image -> ImageScreen(vm, s)
            is Screen.Apk -> ApkScreen(vm, activity, s)
            is Screen.Search -> SearchScreen(vm, s)
        }
    }

    if (vm.screen != Screen.Main) BackHandler { vm.screen = Screen.Main }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    dialog?.let { d -> EntryDialogs(vm, d) { dialog = null } }
    vm.pendingTransfer?.let { ConflictDialog(it) { policy -> vm.confirmTransfer(policy) } }
    vm.pendingExtract?.let { (tab, e) -> ExtractDialog(e, onDismiss = { vm.pendingExtract = null }) { sub, pw ->
        vm.pendingExtract = null; vm.extract(tab, e, sub, pw)
    } }
    vm.busy?.let { BusyDialog(it) { vm.cancelBusy() } }
    if (serverEditorOpen) ServerEditor(vm, editingServer) { serverEditorOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(vm: MainViewModel, activity: MainActivity, openDrawer: () -> Unit, showDialog: (DialogState) -> Unit) {
    val settings = vm.g.settings
    val jobs by vm.g.transfers.jobs.collectAsState()
    val activeJobs = jobs.count { !it.state.finished }
    var menu by remember { mutableStateOf(false) }
    val pane = vm.pane(vm.activePane)
    val tab = pane.tab

    BackHandler(enabled = vm.screen == Screen.Main) {
        if (tab.selecting) vm.clearSelection(tab) else if (!vm.back(tab)) activity.moveTaskToBack(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab.selecting) t("已选 ${tab.selected.size} 项", "${tab.selected.size} selected") else t("Ghost 文件", "Ghost Files"),
                        maxLines = 1,
                    )
                },
                navigationIcon = { IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, null) } },
                actions = {
                    IconButton(onClick = { vm.screen = Screen.Search(tab.fsId, tab.path) }) { Icon(Icons.Filled.Search, t("搜索", "Search")) }
                    IconButton(onClick = { vm.screen = Screen.Transfers }) {
                        BadgedBox(badge = { if (activeJobs > 0) Badge { Text("$activeJobs") } }) { Icon(Icons.Filled.SwapVert, t("传输", "Transfers")) }
                    }
                    IconButton(onClick = { showDialog(DialogState.Sort) }) { Icon(Icons.AutoMirrored.Filled.Sort, t("排序", "Sort")) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (settings.showHidden) t("隐藏隐藏文件", "Hide hidden files") else t("显示隐藏文件", "Show hidden files")) },
                            leadingIcon = { Icon(if (settings.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) },
                            onClick = { settings.showHidden = !settings.showHidden; menu = false },
                        )
                        DropdownMenuItem(text = { Text(t("新建标签页", "New tab")) }, leadingIcon = { Icon(Icons.Filled.Tab, null) },
                            onClick = { vm.newTab(pane); menu = false })
                        DropdownMenuItem(text = { Text(t("添加书签", "Bookmark folder")) }, leadingIcon = { Icon(Icons.Filled.Bookmark, null) },
                            onClick = { vm.addBookmark(tab); menu = false })
                        DropdownMenuItem(text = { Text(t("两栏同步到此目录", "Sync other pane here")) }, leadingIcon = { Icon(Icons.Filled.SyncAlt, null) },
                            onClick = { vm.navigate(vm.other(pane).tab, tab.fsId, tab.path); menu = false })
                        DropdownMenuItem(text = { Text(t("刷新", "Refresh")) }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { vm.refresh(tab); menu = false })
                    }
                },
            )
        },
        bottomBar = { ActionBar(vm, pane, showDialog) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!activity.hasStorageAccess()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("需要“所有文件访问权限”才能浏览存储", "All-files access is required"), Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { activity.requestStorageAccess() }) { Text(t("去授权", "Grant")) }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 600.dp) {
                    Row(Modifier.fillMaxSize()) {
                        PaneView(vm, vm.pane(0), Modifier.weight(1f))
                        VerticalDivider()
                        PaneView(vm, vm.pane(1), Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PaneSwitcher(vm)
                        PaneView(vm, vm.pane(vm.activePane), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneSwitcher(vm: MainViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..1) {
            val p = vm.pane(i)
            val sel = vm.activePane == i
            FilterChip(
                selected = sel,
                onClick = { vm.activePane = i },
                label = { Text((if (i == 0) t("左: ", "L: ") else t("右: ", "R: ")) + p.tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
        IconButton(onClick = { vm.activePane = 1 - vm.activePane }) { Icon(Icons.Filled.SwapHoriz, t("切换栏", "Switch pane")) }
    }
}

@Composable
private fun ActionBar(vm: MainViewModel, pane: PaneState, showDialog: (DialogState) -> Unit) {
    val tab = pane.tab
    var more by remember { mutableStateOf(false) }
    val sel = tab.selectedEntries()
    val visible = visibleEntries(vm, tab)
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sel.isNotEmpty()) {
                ActionButton(Icons.Filled.FileCopy, t("复制到对面", "Copy →")) { vm.transferToOther(pane, TransferOp.COPY) }
                ActionButton(Icons.AutoMirrored.Filled.DriveFileMove, t("移动到对面", "Move →")) { vm.transferToOther(pane, TransferOp.MOVE) }
                ActionButton(Icons.Filled.ContentCopy, t("复制", "Copy")) { vm.setClip(tab, TransferOp.COPY) }
                ActionButton(Icons.Filled.ContentCut, t("剪切", "Cut")) { vm.setClip(tab, TransferOp.MOVE) }
                ActionButton(Icons.Filled.Delete, t("删除", "Delete")) { showDialog(DialogState.Delete(pane.index, sel)) }
                if (sel.size == 1) ActionButton(Icons.Filled.DriveFileRenameOutline, t("重命名", "Rename")) { showDialog(DialogState.Rename(pane.index, sel[0])) }
                ActionButton(Icons.Filled.SelectAll, t("全选", "All")) { vm.selectAll(tab, visible) }
                Box {
                    ActionButton(Icons.Filled.MoreHoriz, t("更多", "More")) { more = true }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        @Composable fun item(label: String, icon: ImageVector, act: () -> Unit) =
                            DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { more = false; act() })
                        item(t("反选", "Invert"), Icons.Filled.Deselect) { vm.invertSelection(tab, visible) }
                        item(t("压缩为 ZIP", "Compress (zip)"), Icons.Filled.FolderZip) { showDialog(DialogState.Compress(pane.index, sel)) }
                        if (sel.size == 1) {
                            item(t("属性", "Properties"), Icons.Filled.Info) { showDialog(DialogState.Properties(pane.index, sel[0])) }
                            if (vm.hasCap(tab, Capability.CHMOD)) item(t("权限 / 所有者", "Permissions"), Icons.Filled.Lock) { showDialog(DialogState.Chmod(pane.index, sel[0])) }
                            if (!sel[0].isDirectory) {
                                item(t("打开方式…", "Open with…"), Icons.AutoMirrored.Filled.OpenInNew) { vm.openEntry(tab, sel[0], chooser = true); vm.clearSelection(tab) }
                                item(t("用文本编辑器打开", "Open as text"), Icons.Filled.EditNote) { vm.openEntry(tab, sel[0], asText = true); vm.clearSelection(tab) }
                            } else {
                                item(t("加入书签", "Bookmark"), Icons.Filled.Bookmark) { vm.addBookmark(tab, sel[0]); vm.clearSelection(tab) }
                            }
                        }
                        if (sel.any { !it.isDirectory }) item(t("分享", "Share"), Icons.Filled.Share) { vm.share(tab, sel); vm.clearSelection(tab) }
                    }
                }
            } else {
                ActionButton(Icons.Filled.CreateNewFolder, t("新建文件夹", "New folder")) { showDialog(DialogState.Create(pane.index, true)) }
                ActionButton(Icons.AutoMirrored.Filled.NoteAdd, t("新建文件", "New file")) { showDialog(DialogState.Create(pane.index, false)) }
                vm.clip?.let { c ->
                    ActionButton(Icons.Filled.ContentPaste, t("粘贴 ${c.entries.size} 项", "Paste ${c.entries.size}")) { vm.paste(tab) }
                    ActionButton(Icons.Filled.Clear, t("清空剪贴板", "Clear clip")) { vm.clip = null }
                }
                ActionButton(Icons.Filled.ArrowUpward, t("上级", "Up")) { vm.up(tab) }
                ActionButton(Icons.Filled.SelectAll, t("全选", "All")) { vm.selectAll(tab, visible) }
                ActionButton(Icons.Filled.Refresh, t("刷新", "Refresh")) { vm.refresh(tab) }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp).widthIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun visibleEntries(vm: MainViewModel, tab: TabState): List<FileEntry> {
    val s = vm.g.settings
    return remember(tab.entries, tab.filter, s.showHidden, s.sortBy, s.ascending, s.foldersFirst) {
        val f = tab.filter.trim()
        val base = tab.entries.filter { (s.showHidden || !it.isHidden) && (f.isEmpty() || it.name.contains(f, true)) }
        sortEntries(base, s.sortBy, s.ascending, s.foldersFirst)
    }
}

@Composable
fun PaneView(vm: MainViewModel, pane: PaneState, modifier: Modifier) {
    val active = vm.activePane == pane.index
    val borderColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier.fillMaxHeight().border(BorderStroke(2.dp, borderColor))
    ) {
        if (pane.tabs.size > 1) {
            ScrollableTabRow(selectedTabIndex = pane.activeTab, edgePadding = 4.dp, modifier = Modifier.height(36.dp)) {
                pane.tabs.forEachIndexed { i, tb ->
                    Tab(selected = i == pane.activeTab, onClick = { pane.activeTab = i; vm.activePane = pane.index; vm.saveTabs() }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(tb.title, maxLines = 1, fontSize = 13.sp, modifier = Modifier.widthIn(max = 120.dp), overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.Close, t("关闭", "Close"), Modifier.size(16.dp).clickable { vm.closeTab(pane, i) })
                        }
                    }
                }
            }
        }
        val tab = pane.tab
        PathBar(vm, pane, tab)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FileList(vm, pane, tab)
        }
    }
}

@Composable
private fun PathBar(vm: MainViewModel, pane: PaneState, tab: TabState) {
    var filtering by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab.path) { scroll.scrollTo(scroll.maxValue) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                IconButton(onClick = { vm.activePane = pane.index; vm.up(tab) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ArrowUpward, t("上级", "Up"), Modifier.size(18.dp))
                }
                Text(
                    vm.fsm.label(tab.fsId), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1,
                )
                Row(Modifier.weight(1f).horizontalScroll(scroll).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val parts = tab.path.trim('/').split('/').filter { it.isNotEmpty() }
                    Text("/", Modifier.clickable { vm.activePane = pane.index; vm.navigate(tab, tab.fsId, "/") }.padding(4.dp), fontSize = 13.sp)
                    var acc = ""
                    parts.forEachIndexed { i, part ->
                        acc += "/$part"
                        val target = acc
                        Text(
                            part + if (i < parts.size - 1) " ›" else "",
                            Modifier.clickable { vm.activePane = pane.index; vm.navigate(tab, tab.fsId, target) }.padding(horizontal = 3.dp, vertical = 4.dp),
                            fontSize = 13.sp, maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = { filtering = !filtering; if (!filtering) tab.filter = "" }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.FilterList, t("筛选", "Filter"), Modifier.size(18.dp))
                }
            }
            if (filtering) {
                OutlinedTextField(
                    value = tab.filter, onValueChange = { tab.filter = it }, singleLine = true,
                    placeholder = { Text(t("筛选当前目录", "Filter this folder")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FileList(vm: MainViewModel, pane: PaneState, tab: TabState) {
    val entries = visibleEntries(vm, tab)
    val state = tab.listState
    LaunchedEffect(tab.highlight, entries.size) {
        val h = tab.highlight ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it.path == h }
        if (idx >= 0 && state.firstVisibleItemIndex == 0) state.scrollToItem((idx - 3).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(entries.size, key = { entries[it].path }) { i ->
                val e = entries[i]
                FileRow(
                    e = e,
                    selected = e.path in tab.selected,
                    highlighted = e.path == tab.highlight,
                    onClick = {
                        vm.activePane = pane.index
                        if (tab.selecting) vm.toggle(tab, e) else vm.openEntry(tab, e)
                    },
                    onLongClick = { vm.activePane = pane.index; vm.toggle(tab, e) },
                )
            }
        }
        when {
            tab.loading && entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            tab.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(tab.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (tab.fsId == LocalFs.ID && tab.path.contains("/Android/data") || tab.path.contains("/Android/obb")) {
                    Text(t("Android 11+ 需通过 Shizuku / Root / SAF 访问此目录（侧边栏）", "Use Shizuku / Root / SAF from the drawer for this folder"), fontSize = 12.sp)
                }
                TextButton(onClick = { vm.refresh(tab) }) { Text(t("重试", "Retry")) }
            }
            !tab.loading && entries.isEmpty() -> Text(t("空目录", "Empty"), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
        }
        if (tab.loading && entries.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(e: FileEntry, selected: Boolean, highlighted: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        highlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = iconFor(e)
        Box {
            Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
            if (e.isLink) Icon(Icons.Filled.Link, null, Modifier.size(14.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.tertiary)
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp).align(Alignment.TopEnd), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                color = if (e.isHidden) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface,
            )
            Row {
                Text(formatTime(e.lastModified), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                e.permissions?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp)) }
                if (!e.isDirectory) Text(formatSize(e.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun iconFor(e: FileEntry): Pair<ImageVector, Color> {
    if (e.isDirectory) return Icons.Filled.Folder to Color(0xFFF2B732)
    return when (e.extension) {
        in IMAGE_EXT -> Icons.Filled.Image to Color(0xFF4CAF50)
        in VIDEO_EXT -> Icons.Filled.Movie to Color(0xFFE53935)
        in AUDIO_EXT -> Icons.Filled.MusicNote to Color(0xFF8E24AA)
        "apk", "apks", "xapk" -> Icons.Filled.Android to Color(0xFF3DDC84)
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "tgz" -> Icons.Filled.FolderZip to Color(0xFF795548)
        in TEXT_EXT -> Icons.Filled.Description to Color(0xFF1E88E5)
        "pdf" -> Icons.Filled.PictureAsPdf to Color(0xFFD32F2F)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.outline
    }
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
