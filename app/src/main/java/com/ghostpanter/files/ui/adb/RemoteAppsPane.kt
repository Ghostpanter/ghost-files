package com.ghostpanter.files.ui.adb

import com.ghostpanter.files.ui.GhostFilterChip
import com.ghostpanter.files.ui.GhostChip
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.adb.AdbDevice
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.RemoteApp
import com.ghostpanter.adb.RemoteApps
import com.ghostpanter.adb.ZipRange
import com.ghostpanter.files.ui.MainViewModel
import com.ghostpanter.files.t
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Names and icons of apps on the target. Uses this device's PackageManager when the same package
 * is installed here; otherwise reads AndroidManifest.xml + resources.arsc + the icon files out of
 * the remote APK with ranged reads, builds a tiny local APK and lets PackageManager resolve them.
 * Cached on disk per package + versionCode.
 */
object RemoteIcons {
    class Meta(val label: String?, val icon: Bitmap?)
    private val mem = LruCache<String, Meta>(600)
    private val gate = Semaphore(2)

    fun cached(app: RemoteApp): Meta? = mem.get(key(app))
    private fun key(a: RemoteApp) = "${a.pkg}_${a.versionCode}"

    suspend fun load(ctx: Context, serial: String, app: RemoteApp): Meta {
        mem.get(key(app))?.let { return it }
        return gate.withPermit {
            mem.get(key(app)) ?: (io { compute(ctx, serial, app) }.getOrNull() ?: Meta(null, null)).also { mem.put(key(app), it) }
        }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "remote-apps").apply { mkdirs() }

    private fun toBitmap(d: Drawable, px: Int = 108): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b)); return b
    }

    private fun compute(ctx: Context, serial: String, app: RemoteApp): Meta {
        val pm = ctx.packageManager
        // 1) same package installed locally
        runCatching {
            val ai = pm.getApplicationInfo(app.pkg, 0)
            return Meta(ai.loadLabel(pm).toString(), toBitmap(ai.loadIcon(pm)))
        }
        // 2) disk cache
        val k = key(app)
        val png = File(dir(ctx), "$k.png"); val txt = File(dir(ctx), "$k.txt")
        if (txt.exists()) {
            return Meta(txt.readText().ifEmpty { null }, if (png.exists()) android.graphics.BitmapFactory.decodeFile(png.path) else null)
        }
        if (app.apkPath.isEmpty()) return Meta(null, null)
        // 3) remote APK, ranged
        val dev = AdbPool.device(serial)
        val base = dev.zip(app.apkPath)
        val files = LinkedHashMap<String, ByteArray>()
        files["AndroidManifest.xml"] = base.bytes("AndroidManifest.xml", 4L shl 20) ?: return Meta(null, null)
        base.bytes("resources.arsc", 40L shl 20)?.let { files["resources.arsc"] = it }
        val splits: List<ZipRange> by lazy {
            runCatching { RemoteApps.parsePaths(dev.text(RemoteApps.paths(app.pkg))).filter { it != app.apkPath }.map { dev.zip(it) } }.getOrDefault(emptyList())
        }
        var label: String? = null
        var icon: Bitmap? = null
        val work = File(dir(ctx), "work-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (attempt in 0 until 10) {
                val f = File(work, "m$attempt.apk")
                f.outputStream().use { ZipRange.writeStoredAligned(files, it) }
                @Suppress("DEPRECATION")
                val pi = pm.getPackageArchiveInfo(f.path, 0) ?: break
                val ai: ApplicationInfo = pi.applicationInfo ?: break
                ai.sourceDir = f.path; ai.publicSourceDir = f.path
                if (label == null) label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()?.takeIf { it != app.pkg }
                if (ai.icon == 0) break
                val res = runCatching { pm.getResourcesForApplication(ai) }.getOrNull() ?: break
                val r = runCatching { res.getDrawable(ai.icon, null) }
                if (r.isSuccess) { icon = r.getOrNull()?.let { toBitmap(it) }; break }
                var e: Throwable? = r.exceptionOrNull(); var missing: String? = null
                while (e != null && missing == null) { missing = e.message?.let { Regex("res/[^\\s\"',)]+").find(it)?.value }; e = e.cause }
                if (missing == null || missing in files) break
                val bytes = base.bytes(missing) ?: splits.firstNotNullOfOrNull { z -> runCatching { z.bytes(missing) }.getOrNull() } ?: break
                files[missing] = bytes
            }
        } finally { work.deleteRecursively() }
        txt.writeText(label.orEmpty())
        icon?.let { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return Meta(label, icon)
    }
}

private fun localApks(ctx: Context, uris: List<Uri>): List<RemoteApps.Apk> {
    val cr = ctx.contentResolver
    val out = ArrayList<RemoteApps.Apk>()
    for (u in uris) {
        var name = "app.apk"; var size = -1L
        cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
        }
        val lower = name.lowercase()
        if (lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") || lower.endsWith(".zip")) {
            val tmp = File(ctx.cacheDir, "bundle-${System.nanoTime()}.zip")
            cr.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val z = ZipFile(tmp)
            z.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }.forEach { e ->
                out += RemoteApps.Apk(e.name.substringAfterLast('/'), e.size) { z.getInputStream(e) }
            }
        } else {
            if (size < 0) size = cr.openFileDescriptor(u, "r")?.use { it.statSize } ?: 0
            out += RemoteApps.Apk(name, size) { cr.openInputStream(u)!! }
        }
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteAppsPane(toast: (String) -> Unit, serial: String, preview: List<RemoteApp>? = null, previewLabels: Map<String, String> = emptyMap()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dev = remember(serial) { AdbPool.device(serial) }
    var apps by remember(serial) { mutableStateOf(preview) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) } // 0 user, 1 system, 2 all, 3 disabled, 4 uninstalled
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<RemoteApp?>(null) }
    var progress by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var bytes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var installMenu by remember { mutableStateOf(false) }
    var pickLocal by remember { mutableStateOf(false) }
    val labels = remember(serial) { mutableStateOf(previewLabels) }

    if (preview == null) LaunchedEffect(serial, reload) {
        error = null
        io { RemoteApps.parse(dev.sh(RemoteApps.LIST_SCRIPT, timeoutMs = 60_000).stdout) }
            .onSuccess { apps = it }.onFailure { error = it.message }
    }

    fun install(list: List<RemoteApps.Apk>, title: String) {
        if (list.isEmpty()) return
        scope.launch {
            progress = Triple(t("安装 ", "Installing ") + title, 0, 1)
            io { RemoteApps.install(dev, list) { d, tot -> bytes = d to tot } }
                .onSuccess { toast(t("安装成功：", "Installed: ") + title) }
                .onFailure { toast(t("安装失败：", "Install failed: ") + it.message) }
            progress = null; bytes = null; reload++
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            io { localApks(ctx, uris) }.onSuccess { install(it, it.firstOrNull()?.name ?: "APK") }.onFailure { toast(it.message ?: "error") }
        }
    }

    fun batch(title: String, targets: List<RemoteApp>, cmd: (RemoteApp) -> String) {
        scope.launch {
            var ok = 0; var fail = 0
            for ((i, a) in targets.withIndex()) {
                progress = Triple(title + " · " + a.pkg, i + 1, targets.size)
                val r = io { dev.sh(cmd(a), timeoutMs = 60_000) }.getOrNull()
                if (r != null && r.ok && !r.stdout.contains("Failure", true) && !r.stdout.contains("Exception")) ok++ else fail++
            }
            progress = null; selected = emptySet()
            toast(t("$title：成功 $ok，失败 $fail", "$title: $ok ok, $fail failed")); reload++
        }
    }

    fun export(targets: List<RemoteApp>) {
        scope.launch {
            val dir = File(outDir(ctx, Environment.DIRECTORY_DOWNLOADS), "apk").apply { mkdirs() }
            var ok = 0
            for ((i, a) in targets.withIndex()) {
                progress = Triple(t("导出 APK · ", "Export APK · ") + a.pkg, i + 1, targets.size)
                io {
                    val paths = RemoteApps.parsePaths(dev.text(RemoteApps.paths(a.pkg)))
                    val name = safeName((labels.value[a.pkg] ?: a.pkg) + "_" + a.versionCode)
                    if (paths.size <= 1) {
                        File(dir, "$name.apk").outputStream().use { dev.pull(paths.first(), it) }
                    } else {
                        ZipOutputStream(File(dir, "$name.apks").outputStream()).use { z ->
                            paths.forEach { p -> z.putNextEntry(ZipEntry(p.substringAfterLast('/'))); dev.pull(p, z); z.closeEntry() }
                        }
                    }
                }.onSuccess { ok++ }
            }
            progress = null; selected = emptySet()
            toast(t("已导出 $ok 个 → ", "Exported $ok → ") + dir.path)
        }
    }

    val all = apps.orEmpty()
    val shown = all.filter { a ->
        when (filter) { 0 -> !a.system && !a.uninstalled; 1 -> a.system && !a.uninstalled; 2 -> !a.uninstalled; 3 -> a.disabled; else -> a.uninstalled }
    }.filter { a -> query.isBlank() || a.pkg.contains(query, true) || labels.value[a.pkg]?.contains(query, true) == true }
        .sortedBy { (labels.value[it.pkg] ?: it.pkg).lowercase() }
    val sel = shown.filter { it.pkg in selected }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(t("搜索名称或包名", "Search name or package")) })
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, t("刷新", "Refresh")) }
            Box {
                IconButton(onClick = { installMenu = true }) { Icon(Icons.Filled.InstallMobile, t("安装到目标", "Install to target")) }
                DropdownMenu(installMenu, { installMenu = false }) {
                    DropdownMenuItem({ Text(t("选择本地 APK / APKS / XAPK…", "Pick local APK / APKS / XAPK…")) }, { installMenu = false; picker.launch(arrayOf("*/*")) })
                    DropdownMenuItem({ Text(t("从本机已安装应用…", "From an app installed here…")) }, { installMenu = false; pickLocal = true })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(t("用户", "User"), t("系统", "System"), t("全部", "All"), t("已停用", "Disabled"), t("已卸载(可恢复)", "Uninstalled (restorable)"))
                .forEachIndexed { i, l -> GhostFilterChip(filter == i, { filter = i; selected = emptySet() }, { Text(l) }) }
        }
        progress?.let { (title, i, n) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("$title  ($i/$n)", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val b = bytes
                if (b != null) LinearProgressIndicator(progress = { (b.first.toFloat() / b.second.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { i.toFloat() / n.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (selected.isNotEmpty()) {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("已选 ${sel.size}", "${sel.size} selected"), Modifier.align(Alignment.CenterVertically), fontWeight = FontWeight.Bold)
                GhostChip({ selected = if (sel.size == shown.size) emptySet() else shown.map { it.pkg }.toSet() }, { Text(t("全选/取消", "All/none")) })
                GhostChip({ batch(t("强行停止", "Force stop"), sel) { RemoteApps.forceStop(it.pkg) } }, { Text(t("强行停止", "Force stop")) })
                GhostChip({ confirm = t("停用 ${sel.size} 个应用？", "Disable ${sel.size} apps?") to { batch(t("停用", "Disable"), sel) { RemoteApps.disable(it.pkg) } } }, { Text(t("停用", "Disable")) })
                GhostChip({ batch(t("启用", "Enable"), sel) { RemoteApps.enable(it.pkg) } }, { Text(t("启用", "Enable")) })
                GhostChip({ confirm = t("清除 ${sel.size} 个应用的数据？", "Clear data of ${sel.size} apps?") to { batch(t("清除数据", "Clear data"), sel) { RemoteApps.clear(it.pkg) } } }, { Text(t("清除数据", "Clear data")) })
                GhostChip({ confirm = t("卸载 ${sel.size} 个应用？系统应用仅对用户 0 卸载，可在「已卸载」中恢复。", "Uninstall ${sel.size} apps? System apps are removed for user 0 only and can be restored.") to { batch(t("卸载", "Uninstall"), sel) { RemoteApps.uninstall(it) } } }, { Text(t("卸载", "Uninstall")) })
                if (filter == 4) GhostChip({ batch(t("恢复", "Restore"), sel) { RemoteApps.restore(it.pkg) } }, { Text(t("恢复", "Restore")) })
                GhostChip({ export(sel) }, { Text(t("导出 APK", "Export APK")) })
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, null) }
            }
        }
        error?.let { ErrorCard(it) { reload++ } }
        if (apps == null && error == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Text(t("${shown.size} 个应用", "${shown.size} apps"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { a ->
                val meta by produceState(RemoteIcons.cached(a), a.pkg, a.versionCode) {
                    if (value == null && preview == null) value = RemoteIcons.load(ctx, serial, a).also { m -> m.label?.let { labels.value = labels.value + (a.pkg to it) } }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (selected.isNotEmpty()) selected = if (a.pkg in selected) selected - a.pkg else selected + a.pkg else detail = a },
                            onLongClick = { selected = selected + a.pkg },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected.isNotEmpty()) Checkbox(a.pkg in selected, { selected = if (it) selected + a.pkg else selected - a.pkg })
                    val bmp = meta?.icon
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(40.dp))
                    else Icon(Icons.Filled.Android, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta?.label ?: labels.value[a.pkg] ?: a.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(a.pkg + if (a.versionCode > 0) " · v${a.versionCode}" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (a.disabled) Badge(t("已停用", "Disabled"))
                    if (a.uninstalled) Badge(t("已卸载", "Removed"))
                    if (a.system && !a.uninstalled) Badge(t("系统", "System"))
                }
            }
        }
    }

    detail?.let { a ->
        val label = labels.value[a.pkg] ?: a.pkg
        fun one(title: String, cmd: String) { detail = null; batch(title, listOf(a)) { cmd } }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(label) },
            text = {
                Column {
                    Text(a.pkg, fontSize = 13.sp)
                    Text("versionCode ${a.versionCode} · uid ${a.uid}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    if (a.apkPath.isNotEmpty()) Text(a.apkPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (a.uninstalled) GhostChip({ one(t("恢复", "Restore"), RemoteApps.restore(a.pkg)) }, { Text(t("恢复安装", "Restore")) })
                        else {
                            GhostChip({ one(t("启动", "Launch"), RemoteApps.launch(a.pkg)) }, { Text(t("启动", "Launch")) })
                            GhostChip({ one(t("强行停止", "Force stop"), RemoteApps.forceStop(a.pkg)) }, { Text(t("强行停止", "Force stop")) })
                            if (a.disabled) GhostChip({ one(t("启用", "Enable"), RemoteApps.enable(a.pkg)) }, { Text(t("启用", "Enable")) })
                            else GhostChip({ detail = null; confirm = t("停用 $label？", "Disable $label?") to { batch(t("停用", "Disable"), listOf(a)) { RemoteApps.disable(it.pkg) } } }, { Text(t("停用", "Disable")) })
                            GhostChip({ detail = null; confirm = t("清除 $label 的全部数据？", "Clear all data of $label?") to { batch(t("清除数据", "Clear data"), listOf(a)) { RemoteApps.clear(it.pkg) } } }, { Text(t("清除数据", "Clear data")) })
                            GhostChip({ detail = null; confirm = (if (a.system) t("对用户 0 卸载系统应用 $label？（可恢复）", "Uninstall system app $label for user 0? (restorable)") else t("卸载 $label？", "Uninstall $label?")) to { batch(t("卸载", "Uninstall"), listOf(a)) { RemoteApps.uninstall(it) } } }, { Text(t("卸载", "Uninstall")) })
                            GhostChip({ detail = null; export(listOf(a)) }, { Text(t("导出 APK", "Export APK")) })
                        }
                    }
                }
            },
            confirmButton = { TextButton({ detail = null }) { Text(t("关闭", "Close")) } },
        )
    }
    confirm?.let { (msg, go) ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(msg) },
            confirmButton = { TextButton({ confirm = null; go() }) { Text(t("确定", "OK")) } },
            dismissButton = { TextButton({ confirm = null }) { Text(t("取消", "Cancel")) } })
    }
    if (pickLocal) LocalAppPicker(onDismiss = { pickLocal = false }) { ai, label ->
        pickLocal = false
        val files = (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).filterNotNull().map { File(it) }
        install(files.map { f -> RemoteApps.Apk(f.name, f.length()) { f.inputStream() } }, label)
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(start = 4.dp)) {
        Text(text, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun LocalAppPicker(onDismiss: () -> Unit, onPick: (ApplicationInfo, String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val apps by produceState<List<Pair<ApplicationInfo, String>>?>(null) {
        value = io {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it to it.loadLabel(pm).toString() }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("把本机应用安装到目标", "Install an app from this device")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(t("搜索", "Search")) }, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) CircularProgressIndicator(Modifier.padding(16.dp))
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list.filter { q.isBlank() || it.second.contains(q, true) || it.first.packageName.contains(q, true) }, key = { it.first.packageName }) { (ai, label) ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(ai, label) }.padding(vertical = 8.dp)) {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(ai.packageName + if (!ai.splitSourceDirs.isNullOrEmpty()) t(" · 含 ${ai.splitSourceDirs!!.size} 个拆分包", " · ${ai.splitSourceDirs!!.size} splits") else "",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(t("取消", "Cancel")) } },
    )
}

