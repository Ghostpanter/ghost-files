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

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(t("Ghost 文件", "Ghost Files"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(16.dp, 6.dp))
        Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))

        Section(t("本地存储", "Local storage"))
        volumes.forEach { v ->
            Column(Modifier.fillMaxWidth().clickable { go(LocalFs.ID, v.path) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null); Spacer(Modifier.width(12.dp))
                    Text(v.label, Modifier.weight(1f))
                }
                if (v.total > 0) {
                    LinearProgressIndicator(progress = { 1f - v.free.toFloat() / v.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(t("已用 ${formatSize(v.total - v.free)} / ${formatSize(v.total)}，可用 ${formatSize(v.free)}",
                        "${formatSize(v.total - v.free)} used of ${formatSize(v.total)}"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Item(Icons.Filled.Folder, t("系统根目录 /", "System root /")) { go(LocalFs.ID, "/") }
        Item(Icons.Filled.Download, t("下载", "Downloads")) { go(LocalFs.ID, vm.fsm.storageRoot + "/Download") }

        Section(t("提权访问（隐藏 / 系统文件）", "Elevated access"))
        Item(Icons.Filled.Security, t("Root（/ 根目录，需 su）", "Root (su)")) {
            scope.launch {
                vm.busy = t("请求 Root 授权…", "Requesting root…")
                val ok = withContext(Dispatchers.IO) { RootRunner.check() }
                vm.busy = null
                if (ok) go(FsManager.ROOT, "/") else vm.toast(t("未获得 Root 权限", "Root not available"))
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

        Section(t("书签", "Bookmarks"))
        if (vm.g.bookmarks.items.isEmpty()) Text(t("长按文件夹或在“更多”中添加书签", "Long-press a folder → Bookmark"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
        vm.g.bookmarks.items.toList().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Item(Icons.Filled.Bookmark, b.label) { go(b.fsId, b.path) } }
                IconButton(onClick = { vm.g.bookmarks.remove(b) }) { Icon(Icons.Filled.Close, null) }
            }
        }

        Section(t("网络（局域网 / 远程）", "Network"))
        vm.g.servers.servers.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Item(protocolIcon(s.protocol), "${s.name}  ·  ${s.protocol.label}") { go(s.fsId) }
                }
                IconButton(onClick = { editServer(s); close() }) { Icon(Icons.Filled.Edit, t("编辑", "Edit")) }
            }
        }
        Item(Icons.Filled.Radar, t("扫描局域网（自动发现共享）", "Scan LAN for shares")) { vm.screen = Screen.LanScan; close() }
        Item(Icons.Filled.CloudUpload, t("本机服务（FTP / WebDAV / SFTP）", "Hosted services (FTP / WebDAV / SFTP)")) { vm.screen = Screen.Servers; close() }
        Item(Icons.Filled.Add, t("手动添加服务器（SMB/FTP/SFTP/WebDAV）", "Add server")) { editServer(null); close() }

        Section(t("工具", "Tools"))
        Item(Icons.Filled.DeleteSweep, t("回收站", "Recycle bin")) { vm.screen = Screen.Trash; close() }
        Item(Icons.Filled.SwapVert, t("传输队列", "Transfers")) { vm.screen = Screen.Transfers; close() }

        Section(t("设置", "Settings"))
        Toggle(t("显示隐藏文件", "Show hidden files"), settings.showHidden) { settings.showHidden = it }
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
        Spacer(Modifier.height(24.dp))
    }
}

fun protocolIcon(p: Protocol): ImageVector = when (p) {
    Protocol.SMB -> Icons.Filled.Lan
    Protocol.FTP, Protocol.FTPS -> Icons.Filled.Dns
    Protocol.SFTP -> Icons.Filled.Terminal
    Protocol.WEBDAV, Protocol.WEBDAVS -> Icons.Filled.Cloud
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp))
}

@Composable
private fun Item(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}
