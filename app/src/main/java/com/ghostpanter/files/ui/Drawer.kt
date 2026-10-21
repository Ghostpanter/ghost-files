package com.ghostpanter.files.ui

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.BuildConfig
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.Protocol
import com.ghostpanter.files.core.ServerConfig
import com.ghostpanter.files.fs.FsManager
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.fs.ShizukuRunner
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Volume(val label: String, val path: String, val total: Long, val free: Long)

fun storageVolumes(ctx: Context): List<Volume> {
    val sm = ctx.getSystemService(StorageManager::class.java)
    val out = ArrayList<Volume>()
    for (v in sm.storageVolumes) {
        val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else runCatching {
            v.javaClass.getMethod("getPathFile").invoke(v) as File
        }.getOrNull()
        if (dir == null || !dir.exists()) continue
        val st = runCatching { StatFs(dir.path) }.getOrNull()
        out.add(Volume(v.getDescription(ctx) ?: dir.name, dir.path, st?.totalBytes ?: 0, st?.availableBytes ?: 0))
    }
    if (out.isEmpty()) {
        val p = android.os.Environment.getExternalStorageDirectory()
        val st = StatFs(p.path)
        out.add(Volume(t("内部存储", "Internal storage"), p.path, st.totalBytes, st.availableBytes))
    }
    return out
}

/** Everything the drawer shows, as plain values (renderable in Paparazzi with fake state). */
data class DrawerUiState(
    val version: String,
    val volumes: List<Volume>,
    val rootGranted: Boolean,
    val showSafAndroidData: Boolean,
    val safTrees: List<Pair<String, String>>,          // uri to label
    val bookmarks: List<com.ghostpanter.files.data.Bookmark>,
    val servers: List<ServerConfig>,
    val adbDevices: List<Pair<String, String>>,       // serial to label
    val showHidden: Boolean,
    val thumbnails: Boolean,
    val language: String,
    val themeMode: String,
    val oledBlack: Boolean,
    val layoutMode: String,
    val listDensity: String,
    val gridView: Boolean,
    val tabletPreview: Boolean,
    val foldersFirst: Boolean,
    val recycleBin: Boolean,
    val jobs: Int,
    val jobsPerServer: Int,
)

/** Drawer callbacks. Defaults are no-ops so previews / snapshots only pass what they need. */
class DrawerActions(
    val openVolume: (Volume) -> Unit = {},
    val openRoot: () -> Unit = {},
    val openDownloads: () -> Unit = {},
    val requestRoot: () -> Unit = {},
    val shizukuData: () -> Unit = {},
    val shizukuObb: () -> Unit = {},
    val safAndroidData: () -> Unit = {},
    val addSaf: () -> Unit = {},
    val openSaf: (String) -> Unit = {},
    val removeSaf: (String) -> Unit = {},
    val openBookmark: (com.ghostpanter.files.data.Bookmark) -> Unit = {},
    val removeBookmark: (com.ghostpanter.files.data.Bookmark) -> Unit = {},
    val openServer: (ServerConfig) -> Unit = {},
    val editServer: (ServerConfig?) -> Unit = {},
    val lanScan: () -> Unit = {},
    val hosted: () -> Unit = {},
    val openAdb: (String) -> Unit = {},
    val adbTools: (String) -> Unit = {},
    val devices: () -> Unit = {},
    val trash: () -> Unit = {},
    val apps: () -> Unit = {},
    val transfers: () -> Unit = {},
    val setShowHidden: (Boolean) -> Unit = {},
    val setThumbnails: (Boolean) -> Unit = {},
    val setLanguage: (String) -> Unit = {},
    val setThemeMode: (String) -> Unit = {},
    val setOled: (Boolean) -> Unit = {},
    val setLayoutMode: (String) -> Unit = {},
    val setListDensity: (String) -> Unit = {},
    val setGrid: (Boolean) -> Unit = {},
    val setTabletPreview: (Boolean) -> Unit = {},
    val setFoldersFirst: (Boolean) -> Unit = {},
    val setRecycleBin: (Boolean) -> Unit = {},
    val setJobs: (Int) -> Unit = {},
    val setJobsPerServer: (Int) -> Unit = {},
    val exportBackup: () -> Unit = {},
    val importBackup: () -> Unit = {},
)

@Composable
fun DrawerContent(vm: MainViewModel, activity: MainActivity, close: () -> Unit, editServer: (ServerConfig?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val volumes = remember { storageVolumes(ctx) }
    val settings = vm.g.settings
    var safTrees by remember { mutableStateOf(settings.safTrees()) }
    var rootGranted by remember { mutableStateOf(RootRunner.granted()) }
    var jobs by remember { mutableIntStateOf(settings.concurrentJobs) }
    var perServer by remember { mutableIntStateOf(settings.jobsPerServer) }
    val adb = com.ghostpanter.files.ui.adb.rememberSavedDevices()

    fun go(fsId: String, path: String? = null) { vm.openFs(fsId, path); close() }
    fun askRoot(thenOpen: Boolean) {
        scope.launch {
            if (thenOpen && RootRunner.granted()) { go(FsManager.ROOT, "/"); return@launch }
            vm.busy = t("请求 Root 授权…", "Requesting root…")
            val ok = withContext(Dispatchers.IO) { RootRunner.check() }
            vm.busy = null
            rootGranted = ok
            if (ok) {
                // Flip any LocalFs panes stuck on / or /storage/emulated over to ShellFs.
                vm.onRootGranted()
                go(FsManager.ROOT, "/")
            } else vm.toast(t("未获得 Root。请在 Magisk/KernelSU 中允许本应用后重试。", "Root not granted. Allow this app in Magisk/KernelSU, then retry."))
        }
    }
    fun downloads() = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

    val state = DrawerUiState(
        version = BuildConfig.VERSION_NAME,
        volumes = volumes,
        rootGranted = rootGranted,
        showSafAndroidData = Build.VERSION.SDK_INT in 30..32,
        safTrees = safTrees.map { it to vm.fsm.label("saf:$it") },
        bookmarks = vm.g.bookmarks.items.toList(),
        servers = vm.g.servers.servers.toList(),
        adbDevices = adb.map { it to com.ghostpanter.files.ui.adb.adbLabel(it) },
        showHidden = settings.showHidden,
        thumbnails = settings.showThumbnails,
        language = settings.language,
        themeMode = settings.themeMode,
        oledBlack = settings.oledBlack,
        layoutMode = settings.layoutMode,
        listDensity = settings.listDensity,
        gridView = settings.gridView,
        tabletPreview = settings.tabletPreviewPanel,
        foldersFirst = settings.foldersFirst,
        recycleBin = settings.recycleBin,
        jobs = jobs,
        jobsPerServer = perServer,
    )
    val actions = DrawerActions(
        openVolume = { go(LocalFs.ID, it.path) },
        // `/` is not readable via LocalFs; always request/use Root ShellFs.
        openRoot = { askRoot(thenOpen = true) },
        openDownloads = { go(LocalFs.ID, vm.fsm.storageRoot + "/Download") },
        requestRoot = { askRoot(thenOpen = false) },
        shizukuData = {
            ShizukuRunner.request { ok ->
                if (ok) go(FsManager.SHIZUKU, vm.fsm.storageRoot + "/Android/data")
                else vm.toast(t("Shizuku 未运行或未授权", "Shizuku not running / not granted"))
            }
        },
        shizukuObb = {
            ShizukuRunner.request { ok ->
                if (ok) go(FsManager.SHIZUKU, vm.fsm.storageRoot + "/Android/obb")
                else vm.toast(t("Shizuku 未运行或未授权", "Shizuku not running / not granted"))
            }
        },
        safAndroidData = { activity.pickSafTree(true); close() },
        addSaf = { activity.pickSafTree(false); close() },
        openSaf = { go("saf:$it", "/") },
        removeSaf = { settings.removeSafTree(it); safTrees = settings.safTrees() },
        openBookmark = { go(it.fsId, it.path) },
        removeBookmark = { vm.g.bookmarks.remove(it) },
        openServer = { go(it.fsId) },
        editServer = { editServer(it); close() },
        lanScan = { vm.screen = Screen.LanScan; close() },
        hosted = { vm.screen = Screen.Servers; close() },
        openAdb = { go(com.ghostpanter.adb.AdbFs.idFor(it, false)) },
        adbTools = { vm.screen = Screen.AdbTools(it); close() },
        devices = { vm.screen = Screen.Devices; close() },
        trash = { vm.screen = Screen.Trash; close() },
        apps = { vm.screen = Screen.Apps; close() },
        transfers = { vm.screen = Screen.Transfers; close() },
        setShowHidden = { settings.showHidden = it },
        setThumbnails = { settings.showThumbnails = it },
        setLanguage = { v -> settings.language = v; close(); (ctx as? android.app.Activity)?.recreate() },
        setThemeMode = { settings.themeMode = it },
        setOled = { settings.oledBlack = it },
        setLayoutMode = { settings.layoutMode = it },
        setListDensity = { settings.listDensity = it },
        setGrid = { settings.viewMode = if (it) "GRID" else "LIST" },
        setTabletPreview = { settings.tabletPreviewPanel = it },
        setFoldersFirst = { settings.foldersFirst = it },
        setRecycleBin = { settings.recycleBin = it },
        setJobs = { jobs = it; settings.concurrentJobs = it },
        setJobsPerServer = { perServer = it; settings.jobsPerServer = it },
        exportBackup = {
            val dest = downloads(); dest?.mkdirs()
            vm.exportBackup(java.io.File(dest, "ghost-files-backup.gfbackup").absolutePath); close()
        },
        importBackup = {
            val f = java.io.File(downloads(), "ghost-files-backup.gfbackup")
            if (f.isFile) { vm.importBackup(f.absolutePath); close() }
            else vm.toast(t("请将备份放到 Download/ghost-files-backup.gfbackup", "Place backup at Download/ghost-files-backup.gfbackup"))
        },
    )
    DrawerBody(state, actions)
}

/** Stateless drawer / side panel body (D 「HyperOS 清爽」: titled white groups on the gray canvas). */
@Composable
fun DrawerBody(s: DrawerUiState, a: DrawerActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(cs.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(22.dp), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(t("双栏文件管理器", "TwinPane Files"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("v${s.version}", fontSize = 12.sp, color = cs.outline)
            }
        }

        Group(t("本地存储", "Local storage")) {
            s.volumes.forEach { v ->
                Column(Modifier.fillMaxWidth().clickable { a.openVolume(v) }.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBox(Icons.Rounded.PhoneAndroid)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (v.total > 0) Text(t("可用 ${formatSize(v.free)} · 共 ${formatSize(v.total)}", "${formatSize(v.free)} free of ${formatSize(v.total)}"), fontSize = 11.sp, color = cs.outline)
                        }
                    }
                    if (v.total > 0) LinearProgressIndicator(
                        progress = { 1f - v.free.toFloat() / v.total },
                        modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 8.dp).height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                        trackColor = cs.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp,
                    )
                }
                Hair()
            }
            Item(Icons.Rounded.AccountTree, t("系统根目录 /", "System root /"), onClick = a.openRoot)
            Hair()
            Item(Icons.Rounded.Download, t("下载", "Downloads"), onClick = a.openDownloads)
        }

        Group(t("提权访问", "Elevated access")) {
            // Always clickable — never greyed out. Granting Magisk/KernelSU refreshes the subtitle.
            Item(Icons.Rounded.Security, "Root", if (s.rootGranted) t("已授权 · 用 Root 后端浏览 /", "Granted · Root backend for /") else t("点此请求 su 授权", "Tap to request su"), onClick = a.requestRoot)
            Hair()
            Item(Icons.Rounded.Shield, "Shizuku", "Android/data", onClick = a.shizukuData)
            Hair()
            Item(Icons.Rounded.Shield, "Shizuku", "Android/obb", onClick = a.shizukuObb)
            if (s.showSafAndroidData) { Hair(); Item(Icons.Rounded.FolderSpecial, t("SAF 授权 Android/data", "SAF: grant Android/data"), onClick = a.safAndroidData) }
            Hair()
            Item(Icons.Rounded.Usb, t("添加 SAF 目录", "Add SAF folder"), t("U 盘 / SD 卡", "USB drive / SD card"), onClick = a.addSaf)
            s.safTrees.forEach { (uri, label) ->
                Hair()
                Item(Icons.Rounded.FolderOpen, label, onClick = { a.openSaf(uri) }, trailing = { CloseBtn { a.removeSaf(uri) } })
            }
        }

        Group(t("书签", "Bookmarks")) {
            if (s.bookmarks.isEmpty()) Text(t("长按文件夹或在「更多」中添加书签", "Long-press a folder → Bookmark"), fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            s.bookmarks.forEachIndexed { i, b ->
                if (i > 0) Hair()
                Item(Icons.Rounded.Bookmark, b.label, onClick = { a.openBookmark(b) }, trailing = { CloseBtn { a.removeBookmark(b) } })
            }
        }

        Group(t("网络", "Network")) {
            s.servers.forEach { sv ->
                Item(protocolIcon(sv.protocol), sv.name, sv.protocol.label, onClick = { a.openServer(sv) },
                    trailing = { IconButton(onClick = { a.editServer(sv) }) { Icon(Icons.Rounded.Edit, t("编辑", "Edit"), Modifier.size(18.dp), tint = cs.outline) } })
                Hair()
            }
            Item(Icons.Rounded.Radar, t("扫描局域网", "Scan LAN"), t("自动发现 SMB / FTP / WebDAV 共享", "Discover shares automatically"), onClick = a.lanScan)
            Hair()
            Item(Icons.Rounded.CloudUpload, t("本机服务", "Hosted services"), "FTP / WebDAV / SFTP / SMB", onClick = a.hosted)
            Hair()
            Item(Icons.Rounded.Add, t("添加服务器", "Add server"), onClick = { a.editServer(null) })
        }

        Group(t("ADB 设备", "ADB devices")) {
            s.adbDevices.forEach { (serial, label) ->
                Item(if (com.ghostpanter.adb.usb.UsbAdb.isUsbSerial(serial)) Icons.Rounded.Usb else Icons.Rounded.PhoneAndroid, label, onClick = { a.openAdb(serial) },
                    trailing = { IconButton(onClick = { a.adbTools(serial) }) { Icon(Icons.Rounded.Build, t("工具", "Tools"), Modifier.size(18.dp), tint = cs.outline) } })
                Hair()
            }
            Item(Icons.Rounded.Cast, t("设备 / 投屏", "Devices / mirror"), t("配对、USB、投屏、工具", "Pair, USB, mirror, tools"), onClick = a.devices)
        }

        Group(t("工具", "Tools")) {
            Item(Icons.Rounded.DeleteSweep, t("回收站", "Recycle bin"), onClick = a.trash)
            Hair()
            Item(Icons.Rounded.Apps, t("应用管理", "App manager"), onClick = a.apps)
            Hair()
            Item(Icons.Rounded.SwapVert, t("传输队列", "Transfers"), onClick = a.transfers)
        }

        Group(t("外观", "Appearance")) {
            ChipPicker(t("主题", "Theme"), listOf("system" to t("跟随系统", "System"), "light" to t("浅色", "Light"), "dark" to t("深色", "Dark")), s.themeMode, onPick = a.setThemeMode)
            Hair(14.dp)
            Toggle(t("OLED 纯黑", "OLED black"), s.oledBlack, t("深色模式使用 #000000 背景", "Pure #000000 background in dark mode"), a.setOled)
            Hair(14.dp)
            ChipPicker(t("语言", "Language"), listOf("system" to t("跟随系统", "System"), "zh" to "简体中文", "en" to "English"), s.language, onPick = a.setLanguage)
            Hair(14.dp)
            ChipPicker(
                t("布局模式", "Layout mode"),
                listOf("auto" to t("自动", "Auto"), "phone" to t("手机", "Phone"), "tablet" to t("平板", "Tablet")),
                s.layoutMode,
                hint = t("自动：平板和展开的折叠屏（宽 ≥ 600dp 且高 ≥ 480dp）用平板布局，手机横竖屏都保持原样", "Auto: tablet layout on tablets and unfolded foldables (width ≥ 600dp, height ≥ 480dp); phones keep the phone layout in both orientations"),
                onPick = a.setLayoutMode,
            )
            Hair(14.dp)
            ChipPicker(t("列表密度", "List density"), listOf("compact" to t("紧凑", "Compact"), "normal" to t("标准", "Normal"), "large" to t("宽松", "Large")), s.listDensity, onPick = a.setListDensity)
            Hair(14.dp)
            Toggle(t("网格视图", "Grid view"), s.gridView, null, a.setGrid)
            Hair(14.dp)
            Toggle(t("平板详情 / 预览面板", "Tablet details panel"), s.tabletPreview, t("超宽屏右侧显示", "Shown on very wide screens"), a.setTabletPreview)
        }

        Group(t("文件", "Files")) {
            Toggle(t("显示隐藏文件", "Show hidden files"), s.showHidden, null, a.setShowHidden)
            Hair(14.dp)
            Toggle(t("列表缩略图", "List thumbnails"), s.thumbnails, null, a.setThumbnails)
            Hair(14.dp)
            Toggle(t("文件夹优先", "Folders first"), s.foldersFirst, null, a.setFoldersFirst)
            Hair(14.dp)
            Toggle(t("删除时移入回收站", "Delete to recycle bin"), s.recycleBin, t("仅本机存储", "Local storage only"), a.setRecycleBin)
            Hair(14.dp)
            Stepper(t("同时传输任务数", "Parallel jobs"), t("重启生效", "After restart"), s.jobs, 1..4, a.setJobs)
            Hair(14.dp)
            Stepper(t("同一服务器任务数", "Jobs per server"), t("重启生效", "After restart"), s.jobsPerServer, 1..4, a.setJobsPerServer)
        }

        Group(t("备份", "Backup")) {
            Item(Icons.Rounded.Upload, t("导出配置", "Export settings"), t("书签 / 服务器 / 设置 → Download", "Bookmarks / servers / settings → Download"), onClick = a.exportBackup)
            Hair()
            Item(Icons.Rounded.Download, t("导入配置备份", "Import backup"), onClick = a.importBackup)
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun protocolIcon(p: Protocol): ImageVector = when (p) {
    Protocol.SMB -> Icons.Filled.Lan
    Protocol.FTP, Protocol.FTPS -> Icons.Filled.Dns
    Protocol.SFTP -> Icons.Filled.Terminal
    Protocol.WEBDAV, Protocol.WEBDAVS -> Icons.Filled.Cloud
}

/** A titled, rounded white group (HyperOS settings style). */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 26.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) { Column(Modifier.padding(vertical = 2.dp), content = content) }
}

/** Hairline divider inside a group, inset past the icon box. */
@Composable
private fun Hair(start: androidx.compose.ui.unit.Dp = 56.dp) =
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun IconBox(icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = cs.primary)
    }
}

@Composable
private fun Item(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        trailing?.invoke()
    }
}

@Composable
private fun CloseBtn(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(Icons.Rounded.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
}

@Composable
private fun Toggle(label: String, value: Boolean, sub: String?, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!value) }.padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (sub != null) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        GhostSwitch(value, onChange, modifier = Modifier.scale(0.85f))
    }
}

@Composable
private fun Stepper(label: String, sub: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            Text(sub, fontSize = 11.sp, color = cs.outline)
        }
        Row(Modifier.clip(CircleShapeLocal).background(cs.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - 1).coerceIn(range)) }, Modifier.size(34.dp)) { Icon(Icons.Rounded.Remove, null, Modifier.size(16.dp)) }
            Text("$value", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, Modifier.size(34.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) }
        }
    }
}

private val CircleShapeLocal = androidx.compose.foundation.shape.RoundedCornerShape(50)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipPicker(title: String, opts: List<Pair<String, String>>, current: String, hint: String? = null, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 14.sp)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEach { (v, label) ->
                GhostFilterChip(selected = current == v, onClick = { if (current != v) onPick(v) }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        hint?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp)) }
    }
}
