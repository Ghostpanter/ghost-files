package com.ghostpanter.files.ui

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.foundation.clickable
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

@Composable
fun DrawerContent(vm: MainViewModel, activity: MainActivity, close: () -> Unit, editServer: (ServerConfig?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val volumes = remember { storageVolumes(ctx) }
    val settings = vm.g.settings
    var safTrees by remember { mutableStateOf(settings.safTrees()) }

    fun go(fsId: String, path: String? = null) { vm.openFs(fsId, path); close() }
    var rootGranted by remember { mutableStateOf(RootRunner.granted()) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp)) {
        Text(t("双栏文件管理器", "TwinPane File Manager"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp))
        Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp))

        Group(t("本地存储", "Local storage")) {
        volumes.forEach { v ->
            Column(Modifier.fillMaxWidth().clickable { go(LocalFs.ID, v.path) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
                    Text(v.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (v.total > 0) {
                    LinearProgressIndicator(
                        progress = { 1f - v.free.toFloat() / v.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp,
                    )
                    Text(t("已用 ${formatSize(v.total - v.free)} / ${formatSize(v.total)}，可用 ${formatSize(v.free)}",
                        "${formatSize(v.total - v.free)} used of ${formatSize(v.total)}"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // `/` is not readable via LocalFs; always request/use Root ShellFs.
        Item(Icons.Filled.Folder, t("系统根目录 /", "System root /")) {
            scope.launch {
                if (RootRunner.granted()) {
                    go(FsManager.ROOT, "/")
                    return@launch
                }
                vm.busy = t("请求 Root 授权…", "Requesting root…")
                val ok = withContext(Dispatchers.IO) { RootRunner.check() }
                vm.busy = null
                rootGranted = ok
                if (ok) {
                    vm.onRootGranted()
                    go(FsManager.ROOT, "/")
                } else {
                    vm.toast(
                        t(
                            t("打开 / 需要 Root。请在 Magisk/KernelSU 中允许本应用。", "Opening / needs root. Allow this app in Magisk/KernelSU."),
                            "Opening / needs Root. Allow this app in Magisk/KernelSU.",
                        ),
                    )
                }
            }
        }
        Item(Icons.Filled.Download, t("下载", "Downloads")) { go(LocalFs.ID, vm.fsm.storageRoot + "/Download") }

        }
        Group(t("提权访问（隐藏 / 系统文件）", "Elevated access")) {
        // Always clickable — never greyed out. Granting Magisk/KernelSU refreshes the subtitle.
        Item(
            Icons.Filled.Security,
            if (rootGranted) t("Root（已授权 · 浏览 / 用 Root 后端）", "Root (granted · use Root backend for /)")
            else t("Root（点此请求 su 授权）", "Root (tap to request su)"),
        ) {
            scope.launch {
                vm.busy = t("请求 Root 授权…", "Requesting root…")
                val ok = withContext(Dispatchers.IO) { RootRunner.check() }
                vm.busy = null
                rootGranted = ok
                if (ok) {
                    // Flip any LocalFs panes stuck on / or /storage/emulated over to ShellFs.
                    vm.onRootGranted()
                    go(FsManager.ROOT, "/")
                } else {
                    vm.toast(
                        t(
                            t("未获得 Root。请在 Magisk/KernelSU 中允许本应用后重试。", "Root not granted. Allow this app in Magisk/KernelSU and retry."),
                            "Root not available. Allow this app in Magisk/KernelSU, then retry.",
                        ),
                    )
                }
            }
        }
        Item(Icons.Filled.Shield, t("Shizuku：Android/data", "Shizuku: Android/data")) {
            ShizukuRunner.request { ok ->
                if (ok) go(FsManager.SHIZUKU, vm.fsm.storageRoot + "/Android/data")
                else vm.toast(t("Shizuku 未运行或未授权", "Shizuku not running / not granted"))
            }
        }
        Item(Icons.Filled.Shield, t("Shizuku：Android/obb", "Shizuku: Android/obb")) {
            ShizukuRunner.request { ok ->
                if (ok) go(FsManager.SHIZUKU, vm.fsm.storageRoot + "/Android/obb")
                else vm.toast(t("Shizuku 未运行或未授权", "Shizuku not running / not granted"))
            }
        }
        if (Build.VERSION.SDK_INT in 30..32) {
            Item(Icons.Filled.FolderSpecial, t("SAF 授权 Android/data", "SAF: grant Android/data")) { activity.pickSafTree(true); close() }
        }
        Item(Icons.Filled.Usb, t("添加 SAF 目录（U 盘 / SD 卡）", "Add SAF folder (USB / SD)")) { activity.pickSafTree(false); close() }
        safTrees.forEach { uri ->
            val id = "saf:$uri"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Item(Icons.Filled.FolderOpen, vm.fsm.label(id)) { go(id, "/") } }
                IconButton(onClick = { settings.removeSafTree(uri); safTrees = settings.safTrees() }) { Icon(Icons.Filled.Close, null) }
            }
        }

        }
        Group(t("书签", "Bookmarks")) {
        if (vm.g.bookmarks.items.isEmpty()) Text(t("长按文件夹或在“更多”中添加书签", "Long-press a folder → Bookmark"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
        vm.g.bookmarks.items.toList().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Item(Icons.Filled.Bookmark, b.label) { go(b.fsId, b.path) } }
                IconButton(onClick = { vm.g.bookmarks.remove(b) }) { Icon(Icons.Filled.Close, null) }
            }
        }

        }
        Group(t("网络（局域网 / 远程）", "Network")) {
        vm.g.servers.servers.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Item(protocolIcon(s.protocol), "${s.name}  ·  ${s.protocol.label}") { go(s.fsId) }
                }
                IconButton(onClick = { editServer(s); close() }) { Icon(Icons.Filled.Edit, t("编辑", "Edit")) }
            }
        }
        Item(Icons.Filled.Radar, t("扫描局域网（自动发现共享）", "Scan LAN for shares")) { vm.screen = Screen.LanScan; close() }
        Item(Icons.Filled.CloudUpload, t("本机服务（FTP / WebDAV / SFTP / SMB）", "Hosted services (FTP / WebDAV / SFTP / SMB)")) { vm.screen = Screen.Servers; close() }
        Item(Icons.Filled.Add, t("手动添加服务器（SMB/FTP/SFTP/WebDAV）", "Add server")) { editServer(null); close() }

        }
        Group(t("ADB 设备", "ADB devices")) {
        com.ghostpanter.files.ui.adb.rememberSavedDevices().forEach { serial ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Item(Icons.Filled.PhoneAndroid, com.ghostpanter.files.ui.adb.adbLabel(serial)) { go(com.ghostpanter.adb.AdbFs.idFor(serial, false)) }
                }
                IconButton(onClick = { vm.screen = Screen.AdbTools(serial); close() }) { Icon(Icons.Filled.Build, t("工具", "Tools")) }
            }
        }
        Item(Icons.Filled.Cast, t("设备 / 投屏（配对、投屏、工具）", "Devices / mirror (pair, mirror, tools)")) { vm.screen = Screen.Devices; close() }

        }
        Group(t("工具", "Tools")) {
        Item(Icons.Filled.DeleteSweep, t("回收站", "Recycle bin")) { vm.screen = Screen.Trash; close() }
        Item(Icons.Filled.Apps, t("应用管理", "App manager")) { vm.screen = Screen.Apps; close() }
        Item(Icons.Filled.SwapVert, t("传输队列", "Transfers")) { vm.screen = Screen.Transfers; close() }

        }
        Group(t("设置", "Settings")) {
        Toggle(t("显示隐藏文件", "Show hidden files"), settings.showHidden) { settings.showHidden = it }
        Toggle(t("列表缩略图", "List thumbnails"), settings.showThumbnails) { settings.showThumbnails = it }
        LanguagePicker(settings.language) { v ->
            settings.language = v
            close()
            (ctx as? android.app.Activity)?.recreate()
        }
        Toggle(t("OLED 纯黑（深色背景 #000000）", "OLED black (#000000)"), settings.oledBlack) { settings.oledBlack = it }
        ChipPicker(
            t("布局模式", "Layout mode"),
            listOf("auto" to t("自动", "Auto"), "phone" to t("手机", "Phone"), "tablet" to t("平板", "Tablet")),
            settings.layoutMode,
            hint = t("自动：平板和展开的折叠屏（宽 ≥ 600dp 且高 ≥ 480dp）用平板布局，手机横竖屏都保持原样", "Auto: tablet layout on tablets and unfolded foldables (width ≥ 600dp, height ≥ 480dp); phones keep the phone layout in both orientations"),
        ) { settings.layoutMode = it }
        ChipPicker(
            t("列表密度", "List density"),
            listOf("compact" to t("紧凑", "Compact"), "normal" to t("标准", "Normal"), "large" to t("宽松", "Large")),
            settings.listDensity,
        ) { settings.listDensity = it }
        Toggle(t("网格视图", "Grid view"), settings.gridView) { settings.viewMode = if (it) "GRID" else "LIST" }
        Toggle(t("平板：右侧详情/预览面板（超宽屏）", "Tablet: details / preview panel (very wide screens)"), settings.tabletPreviewPanel) { settings.tabletPreviewPanel = it }
        Item(Icons.Filled.Upload, t("导出配置 / 书签 / 服务器", "Export settings / bookmarks / servers")) {
            val dest = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dest?.mkdirs()
            val f = java.io.File(dest, "ghost-files-backup.gfbackup")
            vm.exportBackup(f.absolutePath); close()
        }
        Item(Icons.Filled.Download, t("导入配置备份", "Import backup")) {
            val f = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ghost-files-backup.gfbackup",
            )
            if (f.isFile) { vm.importBackup(f.absolutePath); close() }
            else vm.toast(t("请将备份放到 Download/ghost-files-backup.gfbackup", "Place backup at Download/ghost-files-backup.gfbackup"))
        }
        Toggle(t("文件夹优先", "Folders first"), settings.foldersFirst) { settings.foldersFirst = it }
        Toggle(t("删除时移入回收站（本机）", "Delete to recycle bin (local)"), settings.recycleBin) { settings.recycleBin = it }
        var jobs by remember { mutableIntStateOf(settings.concurrentJobs) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("同时传输任务数（重启生效）", "Parallel jobs (restart)"), Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = { jobs = (jobs - 1).coerceAtLeast(1); settings.concurrentJobs = jobs }) { Icon(Icons.Filled.Remove, null) }
            Text("$jobs")
            IconButton(onClick = { jobs = (jobs + 1).coerceAtMost(4); settings.concurrentJobs = jobs }) { Icon(Icons.Filled.Add, null) }
        }
        var perServer by remember { mutableIntStateOf(settings.jobsPerServer) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("同一服务器同时任务数（重启生效）", "Jobs per server (restart)"), Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = { perServer = (perServer - 1).coerceAtLeast(1); settings.jobsPerServer = perServer }) { Icon(Icons.Filled.Remove, null) }
            Text("$perServer")
            IconButton(onClick = { perServer = (perServer + 1).coerceAtMost(4); settings.jobsPerServer = perServer }) { Icon(Icons.Filled.Add, null) }
        }
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
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        GhostSwitch(value, onChange, modifier = Modifier.scale(0.85f))
    }
}


@Composable
private fun LanguagePicker(current: String, onPick: (String) -> Unit) {
    val opts = listOf("system" to t("跟随系统", "Follow system"), "zh" to "简体中文", "en" to "English")
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(t("语言 / Language", "Language / 语言"), fontSize = 14.sp)
        Row {
            opts.forEach { (v, label) ->
                GhostFilterChip(selected = current == v, onClick = { if (current != v) onPick(v) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
    }
}


@Composable
private fun ChipPicker(title: String, opts: List<Pair<String, String>>, current: String, hint: String? = null, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(title, fontSize = 14.sp)
        Row {
            opts.forEach { (v, label) ->
                GhostFilterChip(selected = current == v, onClick = { if (current != v) onPick(v) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        hint?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
    }
}
