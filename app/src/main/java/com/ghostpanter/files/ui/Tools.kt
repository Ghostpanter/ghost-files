package com.ghostpanter.files.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.ops.BatchRename
import com.ghostpanter.files.core.ops.Checksums
import com.ghostpanter.files.core.ops.AppBackupFiles.Part
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

// ------------------------------------------------------------------ hash / checksum

@Composable
fun HashDialog(vm: MainViewModel, tab: TabState, entry: FileEntry, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    var done by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<Map<Checksums.Algo, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expected by remember { mutableStateOf("") }
    LaunchedEffect(entry) {
        withContext(Dispatchers.IO) {
            runCatching {
                Checksums.compute(vm.fs(tab), entry.path, setOf(Checksums.Algo.CRC32, Checksums.Algo.MD5, Checksums.Algo.SHA1, Checksums.Algo.SHA256),
                    isCancelled = { !isActive }) { done = it }
            }.onSuccess { result = it }.onFailure { if (isActive) error = it.message ?: it.javaClass.simpleName }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("校验值", "Checksums") + " · " + entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val r = result
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    r == null -> {
                        LinearProgressIndicator(progress = { if (entry.size > 0) (done.toFloat() / entry.size).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
                        Text(formatSize(done) + " / " + formatSize(entry.size), fontSize = 12.sp)
                    }
                    else -> {
                        for ((a, v) in r) {
                            Text(a.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(Modifier.weight(1f)) { Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                                IconButton(onClick = { copyToClipboard(ctx, a.label, v); vm.toast(t("已复制 ${a.label}", "Copied")) }) { Icon(Icons.Filled.ContentCopy, null) }
                            }
                        }
                        GhostTextField(expected, { expected = it }, label = { Text(t("粘贴校验值进行比对", "Paste hash to compare")) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (expected.isNotBlank()) {
                            val m = Checksums.match(r, expected)
                            Text(if (m != null) t("✔ 一致（${m.label}）", "✔ Match (${m.label})") else t("✘ 不一致", "✘ No match"),
                                color = if (m != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val r = result
            if (r != null) TextButton(onClick = {
                vm.writeChecksumFile(tab, entry, r[Checksums.Algo.SHA256]!!); dismiss()
            }) { Text(t("保存 .sha256", "Save .sha256")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("关闭", "Close")) } },
    )
}

// ------------------------------------------------------------------ compress (format)

@Composable
fun CompressDialog(vm: MainViewModel, tab: TabState, entries: List<FileEntry>, dismiss: () -> Unit) {
    var kind by remember { mutableStateOf(Archives.Kind.ZIP) }
    val base = if (entries.size == 1) (if (entries[0].isDirectory) entries[0].name else entries[0].name.substringBeforeLast('.').ifEmpty { entries[0].name }) else "archive"
    var name by remember { mutableStateOf(base) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var volumeMb by remember { mutableStateOf("") } // empty = single file
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("压缩", "Compress")) },
        text = {
            Column {
                GhostTextField(name, { name = it }, singleLine = true, label = { Text(t("文件名", "Name")) },
                    suffix = { Text(Archives.extensionOf(kind)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in Archives.creatable) GhostFilterChip(kind == k, { kind = k }, label = { Text(Archives.extensionOf(k).removePrefix(".")) })
                }
                if (kind == Archives.Kind.ZIP || kind == Archives.Kind.SEVEN_Z) {
                    Spacer(Modifier.height(8.dp))
                    GhostTextField(
                        password, { password = it }, singleLine = true,
                        label = { Text(t("密码（留空=不加密）", "Password (empty=none)")) },
                        visualTransformation = if (showPw) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showPw = !showPw }) { Icon(if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (kind == Archives.Kind.ZIP && password.isNotEmpty()) Text(t("AES-256 加密 ZIP（zip4j）", "AES-256 ZIP"), fontSize = 11.sp)
                    if (kind == Archives.Kind.SEVEN_Z && password.isNotEmpty()) Text(t("AES 加密 7z", "AES 7z"), fontSize = 11.sp)
                }
                if (kind == Archives.Kind.ZIP) {
                    Spacer(Modifier.height(8.dp))
                    GhostTextField(
                        volumeMb, { volumeMb = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text(t("分卷大小 MB（留空=不分卷）", "Split size MB (empty=off)")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == Archives.Kind.SEVEN_Z) Text(t("7z 先在缓存目录生成再写入目标，需要等量空闲空间", "7z is built in cache first"), fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = volumeMb.toIntOrNull()
                if (kind == Archives.Kind.ZIP && mb != null && mb > 0) {
                    vm.compressVolume(tab, entries, name, mb, password.ifBlank { null })
                } else {
                    vm.compress(tab, entries, name, kind, password.ifBlank { null })
                }
                dismiss()
            }) { Text(t("压缩", "Compress")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

// ------------------------------------------------------------------ batch rename

@Composable
fun BatchRenameDialog(vm: MainViewModel, tab: TabState, entries: List<FileEntry>, dismiss: () -> Unit) {
    var template by remember { mutableStateOf("{name}.{ext}") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("1") }
    var case by remember { mutableStateOf(BatchRename.Case.KEEP) }
    val spec = BatchRename.Spec(template, find, replace, regex, start.toIntOrNull() ?: 1, 1, case)
    val existing = remember(tab.entries) { tab.entries.map { it.name }.toSet() }
    val plan = remember(spec, entries) { BatchRename.plan(spec, entries, existing) }
    val bad = plan.count { it.error != null }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("批量重命名（${entries.size} 项）", "Batch rename (${entries.size})")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GhostTextField(template, { template = it }, singleLine = true, label = { Text(t("模板", "Template")) }, modifier = Modifier.fillMaxWidth())
                Text(t("可用：{name} 文件名 {ext} 扩展名 {n} 序号 {n:3} 补零序号 {date} {time} {parent}", "Tokens: {name} {ext} {n} {n:3} {date} {time} {parent}"), fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, v) in listOf(t("原名", "Original") to "{name}.{ext}", t("序号", "Number") to "{n:3}.{ext}",
                        t("名称+序号", "Name+No.") to "{name}_{n:2}.{ext}", t("日期", "Date") to "{date}_{time}.{ext}")) {
                        GhostChip({ template = v }, { Text(label) })
                    }
                }
                Row {
                    GhostTextField(find, { find = it }, singleLine = true, label = { Text(t("查找", "Find")) }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    GhostTextField(replace, { replace = it }, singleLine = true, label = { Text(t("替换为", "Replace")) }, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(regex, { regex = it }); Text(t("正则", "Regex"))
                    Spacer(Modifier.width(8.dp))
                    GhostTextField(start, { start = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text(t("起始序号", "Start")) }, modifier = Modifier.width(110.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostFilterChip(case == BatchRename.Case.KEEP, { case = BatchRename.Case.KEEP }, { Text(t("原大小写", "Keep")) })
                    GhostFilterChip(case == BatchRename.Case.LOWER, { case = BatchRename.Case.LOWER }, { Text("abc") })
                    GhostFilterChip(case == BatchRename.Case.UPPER, { case = BatchRename.Case.UPPER }, { Text("ABC") })
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(t("预览", "Preview") + if (bad > 0) t("（$bad 项有问题，将跳过）", " ($bad skipped)") else "", fontSize = 12.sp)
                for (p in plan.take(60)) {
                    Text(p.entry.name + "  →  " + p.newName + (p.error?.let { "  ⚠ $it" } ?: ""), fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (p.error != null) MaterialTheme.colorScheme.error else if (p.changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                }
                if (plan.size > 60) Text("… +${plan.size - 60}", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = plan.any { it.changed && it.error == null }, onClick = { vm.batchRename(tab, plan); dismiss() }) { Text(t("重命名", "Rename")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

// ------------------------------------------------------------------ hex viewer / editor

private const val HEX_PAGE = 4096

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexScreen(vm: MainViewModel, s: Screen.Hex) {
    var size by remember { mutableLongStateOf(-1L) }
    var page by remember { mutableLongStateOf(0L) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf(false) }
    var editOffset by remember { mutableStateOf<Int?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmSave by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(s, page) {
        if (dirty && bytes != null) return@LaunchedEffect
        bytes = null
        withContext(Dispatchers.IO) {
            runCatching {
                val fs = vm.fsm.get(s.fsId)
                remote = fs.has(com.ghostpanter.files.core.Capability.REMOTE)
                if (size < 0) size = fs.stat(s.path)?.size ?: 0L
                val off = page * HEX_PAGE
                fs.openRead(s.path, off).use { input ->
                    val buf = ByteArray(minOf(HEX_PAGE.toLong(), (size - off).coerceAtLeast(0)).toInt())
                    var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    buf.copyOf(n)
                }
            }.onSuccess { bytes = it; error = null }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }
    val pages = if (size <= 0) 1L else (size + HEX_PAGE - 1) / HEX_PAGE
    fun save() {
        val b = bytes ?: return
        confirmSave = false
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val fs = vm.fsm.get(s.fsId)
                    val off = page * HEX_PAGE
                    // Read full file into temp for remote / non-resume write, patch page, write back.
                    val tmp = File(vm.getApplication<android.app.Application>().cacheDir, "hex-save.bin")
                    fs.openRead(s.path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    RandomAccessFile(tmp, "rw").use { raf ->
                        raf.seek(off)
                        raf.write(b)
                    }
                    fs.openWrite(s.path, 0, tmp.length()).use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                }.onSuccess {
                    dirty = false
                    size = -1
                    vm.toast(t("已保存（覆盖原文件）", "Saved (overwrote)"))
                }.onFailure { vm.toast(t("保存失败: ", "Save failed: ") + it.message) }
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
            title = { Text(VPath.name(s.path) + "  (HEX)" + if (dirty) " *" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(enabled = page > 0 && !dirty, onClick = { page-- }) { Icon(Icons.Filled.ChevronLeft, t("上一页", "Prev")) }
                Text("${page + 1}/$pages", fontSize = 12.sp)
                IconButton(enabled = page < pages - 1 && !dirty, onClick = { page++ }) { Icon(Icons.Filled.ChevronRight, t("下一页", "Next")) }
                IconButton(onClick = { jump = true }) { Icon(Icons.Filled.Directions, t("跳转", "Go")) }
                IconButton(enabled = dirty, onClick = { confirmSave = true }) { Icon(Icons.Filled.Save, t("保存", "Save")) }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val b = bytes
            when {
                error != null -> Text(error!!, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                b == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val rows = (b.size + 15) / 16
                    Column(Modifier.fillMaxSize()) {
                        if (remote) Text(t("远程文件：保存时会下载→修改→上传覆盖", "Remote: download-edit-upload on save"), fontSize = 11.sp, modifier = Modifier.padding(6.dp))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
                            items(rows) { r ->
                                val line = hexRow(b, r, page * HEX_PAGE)
                                Text(
                                    line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false,
                                    modifier = Modifier.clickable {
                                        editOffset = r * 16
                                        editValue = (0 until 16).mapNotNull { i ->
                                            val k = r * 16 + i
                                            if (k < b.size) "%02X".format(b[k].toInt() and 0xff) else null
                                        }.joinToString(" ")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (jump) {
        NameDialog(t("跳转到偏移（十进制或 0x 十六进制）", "Go to offset (dec or 0x hex)"), "0x", { jump = false }, selectAll = true) { v ->
            val off = v.trim().lowercase().let { if (it.startsWith("0x")) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
            if (off != null && off >= 0) { dirty = false; page = (off / HEX_PAGE).coerceAtMost(pages - 1) }
        }
    }
    if (editOffset != null) {
        AlertDialog(
            onDismissRequest = { editOffset = null },
            title = { Text(t("编辑字节（空格分隔十六进制）", "Edit bytes (hex)")) },
            text = { GhostTextField(editValue, { editValue = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    val start = editOffset!!
                    val parts = editValue.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val b = bytes?.copyOf() ?: return@TextButton
                    for ((i, p) in parts.withIndex()) {
                        val v = p.toIntOrNull(16) ?: continue
                        val idx = start + i
                        if (idx in b.indices) b[idx] = v.toByte()
                    }
                    bytes = b; dirty = true; editOffset = null
                }) { Text(t("应用", "Apply")) }
            },
            dismissButton = { TextButton(onClick = { editOffset = null }) { Text(t("取消", "Cancel")) } },
        )
    }
    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        text = { Text(t("确定覆盖保存？远程文件会整文件重传。", "Overwrite? Remote files are fully re-uploaded.")) },
        confirmButton = { TextButton(onClick = { save() }) { Text(t("覆盖保存", "Overwrite")) } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text(t("取消", "Cancel")) } },
    )
}

fun hexRow(b: ByteArray, row: Int, base: Long): String {
    val sb = StringBuilder()
    val start = row * 16
    sb.append("%08X  ".format(base + start))
    for (i in 0 until 16) {
        val k = start + i
        if (k < b.size) sb.append("%02X ".format(b[k].toInt() and 0xff)) else sb.append("   ")
        if (i == 7) sb.append(' ')
    }
    sb.append(' ')
    for (i in 0 until 16) {
        val k = start + i
        if (k >= b.size) break
        val c = b[k].toInt() and 0xff
        sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
    }
    return sb.toString()
}

// ------------------------------------------------------------------ app manager

private class AppRow(val label: String, val pkg: String, val version: String, val system: Boolean, val apk: String, val splits: List<String>, val size: Long, val info: ApplicationInfo,
    val versionCode: Long = 0, val installer: String? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalAppsScreen(vm: MainViewModel, header: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var showSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<AppRow?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selectedPkgs by remember { mutableStateOf(setOf<String>()) }
    var progress by remember { mutableStateOf<AppOpProgress?>(null) }
    var batchBusy by remember { mutableStateOf(false) }
    var backupPick by remember { mutableStateOf<List<AppRow>?>(null) }
    var restorePick by remember { mutableStateOf<List<AppRow>?>(null) }
    var restoreConfirm by remember { mutableStateOf<Pair<List<AppRow>, Part>?>(null) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    val rootOk = RootRunner.granted()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0).mapNotNull { pi ->
                val ai = pi.applicationInfo ?: return@mapNotNull null
                val splits = ai.splitSourceDirs?.toList().orEmpty()
                AppRow(
                    ai.loadLabel(pm).toString(), pi.packageName, pi.versionName ?: "",
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, ai.sourceDir ?: "", splits,
                    (listOf(ai.sourceDir) + splits).sumOf { p -> p?.let { File(it).length() } ?: 0L }, ai,
                    pi.longVersionCode, com.ghostpanter.files.install.AppSource.record(ctx, pi.packageName)?.installer,
                )
            }.sortedBy { it.label.lowercase() }
        }
    }

    fun exitSelect() {
        selecting = false
        selectedPkgs = emptySet()
    }

    fun togglePkg(pkg: String) {
        selectedPkgs = if (pkg in selectedPkgs) selectedPkgs - pkg else selectedPkgs + pkg
        if (selectedPkgs.isEmpty()) selecting = false
    }

    fun selectedApps(shown: List<AppRow>): List<AppRow> = shown.filter { it.pkg in selectedPkgs }

    fun runBackup(targets: List<AppRow>, part: Part) {
        if (targets.isEmpty() || batchBusy) return
        batchBusy = true
        val title = t("备份", "Backup") + " · " + AppBackupOps.partLabel(part)
        scope.launch {
            val rs = ArrayList<PartResult>()
            for ((i, a) in targets.withIndex()) {
                progress = AppOpProgress(title, i + 1, targets.size, a.label, null)
                rs += withContext(Dispatchers.IO) {
                    AppBackupOps.backup(ctx, vm, a.target(), part) { pt -> progress = AppOpProgress(title, i + 1, targets.size, a.label, pt) }
                }
            }
            progress = null
            batchBusy = false
            if (targets.size > 1) exitSelect()
            val body = if (targets.size == 1) AppBackupOps.summary(rs[0]) else AppBackupOps.batchSummary(rs)
            result = title to (body + "\n" + AppBackupOps.dir().path)
        }
    }

    fun runRestore(targets: List<AppRow>, part: Part) {
        if (targets.isEmpty() || batchBusy) return
        batchBusy = true
        val title = t("恢复", "Restore") + " · " + AppBackupOps.partLabel(part)
        scope.launch {
            val rs = ArrayList<PartResult>()
            for ((i, a) in targets.withIndex()) {
                progress = AppOpProgress(title, i + 1, targets.size, a.label, null)
                val (apk, tar) = withContext(Dispatchers.IO) {
                    (if (part.apk) AppBackupOps.findApk(ctx, a.pkg) else null) to (if (part.data) AppBackupOps.findData(a.pkg) else null)
                }
                rs += AppBackupOps.restore(ctx, vm, a.pkg, part, apk, tar) { pt -> progress = AppOpProgress(title, i + 1, targets.size, a.label, pt) }
            }
            progress = null
            batchBusy = false
            if (targets.size > 1) exitSelect()
            result = title to (if (targets.size == 1) AppBackupOps.summary(rs[0]) else AppBackupOps.batchSummary(rs))
        }
    }

    /** Single app, APK only: hand the backup to the normal install UI (source choice, silent toggle). */
    fun openApkInstaller(a: AppRow) {
        scope.launch {
            val f = withContext(Dispatchers.IO) { AppBackupOps.findApk(ctx, a.pkg) }
            if (f == null) {
                vm.toast(t("没找到 ${a.label} 的 APK 备份，请在备份目录中选择", "No APK backup for ${a.label}; pick one in the backup folder"))
                vm.openFs(com.ghostpanter.files.core.LocalFs.ID, AppBackupOps.dir().path)
                vm.screen = Screen.Main
                return@launch
            }
            if (f.name.endsWith(".apk", true)) vm.screen = Screen.Apk(f, f.name)
            else { vm.screen = Screen.Main; vm.bundleInstall = BundleRequest(f, f.name) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
                title = {
                    Text(
                        if (selecting) t("已选 ${selectedPkgs.size}", "${selectedPkgs.size} selected")
                        else t("应用管理", "Apps"),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selecting) exitSelect() else vm.screen = Screen.Main
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    if (selecting) {
                        val list = apps
                        val shown = list?.filter {
                            (showSystem || !it.system) &&
                                (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                        }.orEmpty()
                        TextButton(onClick = {
                            selectedPkgs = if (selectedPkgs.size >= shown.size) emptySet() else shown.map { it.pkg }.toSet()
                            if (selectedPkgs.isEmpty()) selecting = false
                        }) { Text(if (selectedPkgs.size >= shown.size && shown.isNotEmpty()) t("取消全选", "None") else t("全选", "All")) }
                    } else {
                        GhostFilterChip(showSystem, { showSystem = !showSystem }, { Text(t("系统应用", "System")) })
                        IconButton(onClick = { selecting = true }) {
                            Icon(Icons.Filled.Checklist, contentDescription = t("多选", "Select"))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting && selectedPkgs.isNotEmpty()) {
                BottomAppBar {
                    val list = apps
                    val shown = list?.filter {
                        (showSystem || !it.system) &&
                            (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                    }.orEmpty()
                    val targets = selectedApps(shown)
                    TextButton(enabled = !batchBusy && targets.isNotEmpty(), onClick = { backupPick = targets }) {
                        Text(t("备份（${targets.size}）", "Backup (${targets.size})"))
                    }
                    TextButton(enabled = !batchBusy && targets.isNotEmpty(), onClick = { restorePick = targets }) {
                        Text(t("恢复（${targets.size}）", "Restore (${targets.size})"))
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            header()
            GhostTextField(
                query, { query = it }, singleLine = true,
                placeholder = { Text(t("搜索名称或包名", "Search")) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            val list = apps
            if (list == null) Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            else {
                val shown = list.filter {
                    (showSystem || !it.system) &&
                        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                }
                Text(
                    t("${shown.size} 个应用 · 长按进入多选", "${shown.size} apps · long-press to multi-select"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                LazyColumn(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp).ghostCard()) {
                    itemsIndexed(shown, key = { _, it -> it.pkg }) { i, a ->
                        if (i > 0) GhostRowDivider()
                        val checked = a.pkg in selectedPkgs
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) togglePkg(a.pkg) else detail = a
                                    },
                                    onLongClick = {
                                        selecting = true
                                        selectedPkgs = selectedPkgs + a.pkg
                                    },
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting) {
                                Checkbox(checked, { togglePkg(a.pkg) })
                                Spacer(Modifier.width(4.dp))
                            }
                            val icon = remember(a.pkg) { runCatching { a.info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                            if (icon != null) Image(icon, null, Modifier.size(40.dp)) else Icon(Icons.Filled.Android, null, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(
                                    "${a.pkg}  ${a.version}  ${formatSize(a.size)}" +
                                        (if (a.splits.isNotEmpty()) "  +${a.splits.size} split" else "") +
                                        (if (!a.system) "  · " + sourceShort(ctx, a.installer) else ""),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { a ->
        val dismiss = { detail = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(a.label) },
            text = {
                Column {
                    SelectionContainer { Text("${a.pkg}\n${t("版本", "Version")} ${a.version}\n${a.apk}", fontSize = 12.sp) }
                    Spacer(Modifier.height(10.dp))
                    InstalledAppSource(vm, a.pkg)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { dismiss(); backupPick = listOf(a) }) { Text(t("备份…", "Backup…")) }
                    TextButton(onClick = { dismiss(); restorePick = listOf(a) }) { Text(t("恢复…", "Restore…")) }
                    TextButton(onClick = {
                        dismiss()
                        selecting = true
                        selectedPkgs = selectedPkgs + a.pkg
                    }) { Text(t("加入多选", "Add to selection")) }
                    TextButton(onClick = { dismiss(); runCatching { ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let(ctx::startActivity) } }) { Text(t("启动", "Launch")) }
                    TextButton(onClick = { dismiss(); runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))) } }) { Text(t("应用信息", "App info")) }
                    if (!a.system) TextButton(onClick = { dismiss(); runCatching { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))) } }) { Text(t("卸载", "Uninstall")) }
                    TextButton(onClick = {
                        dismiss()
                        vm.openFs(com.ghostpanter.files.core.LocalFs.ID, AppBackupOps.dir().path)
                        vm.screen = Screen.Main
                    }) { Text(t("打开备份目录", "Open backup folder")) }
                }
            },
        )
    }

    backupPick?.let { targets ->
        AppPartDialog(
            title = if (targets.size == 1) t("备份「${targets[0].label}」", "Back up ${targets[0].label}") else t("备份 ${targets.size} 个应用", "Back up ${targets.size} apps"),
            options = backupOptions(rootOk, targets.singleOrNull()?.splits?.size ?: 0),
            note = if (!rootOk) t("应用数据在 /data/data，需 Root 才能读取", "App data lives in /data/data and needs Root") else null,
            confirmLabel = t("开始备份", "Back up"),
            dismiss = { backupPick = null },
        ) { part -> runBackup(targets, part) }
    }

    restorePick?.let { targets ->
        val single = targets.singleOrNull()
        val silentOk = remember { com.ghostpanter.files.install.BundleInstaller.elevated() != null }
        var found by remember(targets) { mutableStateOf<Pair<File?, File?>?>(null) }
        if (single != null) LaunchedEffect(single.pkg) {
            found = withContext(Dispatchers.IO) { AppBackupOps.findApk(ctx, single.pkg) to AppBackupOps.findData(single.pkg) }
        }
        val f = found
        if (single != null && f == null) {
            AlertDialog(onDismissRequest = { restorePick = null }, title = { Text(t("查找备份…", "Looking for backups…")) },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
        } else {
            AppPartDialog(
                title = if (single != null) t("恢复「${single.label}」", "Restore ${single.label}") else t("恢复 ${targets.size} 个应用", "Restore ${targets.size} apps"),
                options = restoreOptions(
                    rootOk = rootOk, silentOk = silentOk,
                    hasApk = single == null || f?.first != null, hasData = single == null || f?.second != null,
                    installed = true, allowSystemInstaller = single != null,
                ),
                note = if (single == null) t("从备份目录按包名匹配，缺的会记为失败", "Matched by package in the backup folder; missing ones count as failed") else null,
                confirmLabel = t("下一步", "Next"),
                extra = if (single != null) { {
                    Spacer(Modifier.height(6.dp))
                    Text("APK：" + (f?.first?.name ?: t("无", "none")), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t("数据：", "Data: ") + (f?.second?.name ?: t("无", "none")), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (f?.first == null) TextButton(onClick = {
                        restorePick = null
                        vm.openFs(com.ghostpanter.files.core.LocalFs.ID, AppBackupOps.dir().path)
                        vm.screen = Screen.Main
                    }) { Text(t("去备份目录选择 APK", "Pick an APK in the backup folder")) }
                } } else null,
                dismiss = { restorePick = null },
            ) { part ->
                if (single != null && part == Part.APK) openApkInstaller(single)
                else restoreConfirm = targets to part
            }
        }
    }

    restoreConfirm?.let { (targets, part) ->
        val who = targets.singleOrNull()?.label?.let { "「$it」" } ?: t(" ${targets.size} 个应用", " ${targets.size} apps")
        AlertDialog(
            onDismissRequest = { restoreConfirm = null },
            title = { Text(t("确认恢复", "Confirm restore")) },
            text = {
                Text(
                    when (part) {
                        Part.APK -> t("将用备份覆盖安装$who 的 APK。", "Reinstall $who from the backup APK.")
                        Part.DATA -> t("将强制停止$who，并用备份覆盖它当前的全部数据，无法撤销。", "Force-stops $who and replaces all its current data. This cannot be undone.")
                        Part.BOTH -> t("先覆盖安装$who 的 APK，再强制停止并用备份覆盖全部数据，无法撤销。", "Reinstalls $who, then force-stops it and replaces all its data. This cannot be undone.")
                    },
                )
            },
            confirmButton = { TextButton(onClick = { restoreConfirm = null; runRestore(targets, part) }) { Text(t("恢复", "Restore"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { restoreConfirm = null }) { Text(t("取消", "Cancel")) } },
        )
    }

    progress?.let { AppOpProgressDialog(it) }

    result?.let { (title, body) ->
        AppOpResultDialog(title, body, onOpenFolder = {
            vm.openFs(com.ghostpanter.files.core.LocalFs.ID, AppBackupOps.dir().path)
            vm.screen = Screen.Main
        }) { result = null }
    }
}

private fun sourceShort(ctx: Context, installer: String?): String =
    com.ghostpanter.files.core.apk.InstallSource.knownLabel(installer, ctx.packageName) ?: installer!!

/** Source card in the app detail: shows what PackageManager / dumpsys recorded, and can switch it to Google Play. */
@Composable
private fun InstalledAppSource(vm: MainViewModel, pkg: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val elevated = remember { com.ghostpanter.files.install.BundleInstaller.elevated() }
    val play = remember { com.ghostpanter.files.install.AppSource.playPresent(ctx) }
    var rec by remember(pkg) { mutableStateOf<com.ghostpanter.files.core.apk.InstallSource.Record?>(null) }
    var loading by remember(pkg) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember(pkg) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var offerReinstall by remember(pkg) { mutableStateOf(false) }
    suspend fun reload() {
        rec = withContext(Dispatchers.IO) { com.ghostpanter.files.install.AppSource.best(ctx, pkg, elevated?.second) }
        loading = false
    }
    LaunchedEffect(pkg) { reload() }
    val PLAY = com.ghostpanter.files.core.apk.InstallSource.PLAY
    val r = rec
    val isPlay = r?.installer == PLAY
    fun label(p: String?) = com.ghostpanter.files.install.AppSource.label(ctx, p)
    AppSourceCard(
        AppSourceUi(
            installer = if (loading) "" else label(r?.installer), installerPkg = r?.installer,
            initiating = r?.initiating?.takeIf { it != r.installer }?.let { label(it) },
            updateOwner = r?.updateOwner?.let { label(it) },
            isPlay = isPlay, canSetPlay = elevated != null && play && !isPlay && !loading,
            canOpenPlay = play, busy = busy, note = note, offerReinstall = offerReinstall && elevated != null, loading = loading,
        ),
        onSetPlay = {
            val sh = elevated?.second ?: return@AppSourceCard
            busy = true
            scope.launch {
                val res = withContext(Dispatchers.IO) { com.ghostpanter.files.install.AppSource.setInstaller(sh, pkg, PLAY) }
                reload()
                busy = false
                note = when {
                    res.isSuccess && rec?.installer == PLAY -> true to t("已改为 Google Play 来源（${elevated.first}）", "Source set to Google Play (${elevated.first})")
                    res.isSuccess -> { offerReinstall = true; false to t("命令执行成功，但系统记录的来源仍是 ${label(rec?.installer)}", "Command ran, but Android still records ${label(rec?.installer)}") }
                    else -> {
                        offerReinstall = true
                        val raw = res.exceptionOrNull()?.message.orEmpty()
                        false to ((com.ghostpanter.files.core.apk.InstallSource.explain(raw) ?: t("修改失败", "Failed")) + "\n" + raw.lineSequence().take(2).joinToString(" ").take(160))
                    }
                }
            }
        },
        onOpenPlay = { com.ghostpanter.files.install.AppSource.playIntent(ctx, pkg)?.let { i -> runCatching { ctx.startActivity(i) } } },
        onReinstall = {
            val sh = elevated?.second ?: return@AppSourceCard
            busy = true
            scope.launch {
                val res = runCatching { com.ghostpanter.files.install.BundleInstaller.reinstallAs(ctx, sh, pkg, PLAY) }
                reload()
                busy = false
                note = if (res.isSuccess && rec?.installer == PLAY) { offerReinstall = false; true to t("已覆盖安装，来源为 Google Play，数据保留", "Reinstalled as a Google Play install; data kept") }
                else false to (t("覆盖安装后来源为 ${label(rec?.installer)}", "After reinstall the source is ${label(rec?.installer)}") + (res.exceptionOrNull()?.message?.let { "\n" + it.take(200) } ?: ""))
            }
        },
    )
}

private fun AppRow.target() = AppBackupTarget(pkg, label, version, versionCode, apk, splits)
