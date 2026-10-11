package com.ghostpanter.files.ui

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.ops.FsSearch
import com.ghostpanter.files.core.ops.RecycleBin
import com.ghostpanter.files.core.transfer.JobSnapshot
import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = actions,
            )
        },
        content = content,
    )
}

// ------------------------------------------------------------------ transfers

@Composable
fun TransfersScreen(vm: MainViewModel) {
    val jobs by vm.g.transfers.jobs.collectAsState()
    val engine = vm.g.transfers
    SubScaffold(t("传输队列", "Transfers"), { vm.screen = Screen.Main }, actions = {
        TextButton(onClick = { engine.clearFinished() }) { Text(t("清除已完成", "Clear finished")) }
    }) { pad ->
        if (jobs.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("没有传输任务", "No transfers"), color = MaterialTheme.colorScheme.outline)
            }
        } else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(jobs.reversed(), key = { it.id }) { j -> JobCard(vm, j) }
        }
    }
}

@Composable
private fun JobCard(vm: MainViewModel, j: JobSnapshot) {
    val engine = vm.g.transfers
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(8.dp, 4.dp).clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateLabel(j.state), fontSize = 12.sp, color = when (j.state) {
                    JobState.FAILED -> MaterialTheme.colorScheme.error
                    JobState.COMPLETED -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                })
            }
            Text(
                "${vm.fsm.label(j.sources.first().fsId)} → ${vm.fsm.label(j.dest.fsId)}  ${j.dest.path}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (j.state == JobState.PREPARING) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatSize(j.doneBytes)} / ${formatSize(j.totalBytes)} · ${j.doneFiles}/${j.totalFiles} " + t("个文件", "files") +
                    (if (j.bytesPerSec > 0) " · ${formatSize(j.bytesPerSec)}/s" else "") +
                    (if (j.retries > 0) t(" · 重试 ${j.retries}", " · retries ${j.retries}") else "") +
                    (if (j.serverSideFiles > 0) t(" · 服务器端 ${j.serverSideFiles}", " · server-side ${j.serverSideFiles}") else "") +
                    (if (j.skippedFiles > 0) t(" · 跳过 ${j.skippedFiles}", " · skipped ${j.skippedFiles}") else "") +
                    (if (j.keptSources > 0) t(" · 源未删除 ${j.keptSources}", " · sources kept ${j.keptSources}") else ""),
                fontSize = 12.sp,
            )
            j.currentName?.let {
                Text("$it  ${formatSize(j.currentDone)} / ${formatSize(j.currentSize)}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
            }
            // Why the last retry / failure happened (root cause unwrapped); tap the card for the full chain.
            if (j.lastError != null && (!j.state.finished || j.state == JobState.FAILED || j.retries > 0)) {
                Text(
                    t("最近错误：", "Last error: ") + j.lastError, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                    maxLines = if (expanded) 6 else 3, overflow = TextOverflow.Ellipsis,
                )
                if (!expanded && j.lastErrorDetail != null) Text(t("点按展开完整错误", "Tap for full error"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            if (expanded && j.lastErrorDetail != null) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(6.dp),
                ) {
                    Text(j.lastErrorDetail!!, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
                }
            }
            j.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            if (j.failures.isNotEmpty()) {
                val shown = if (expanded) j.failures.take(50) else j.failures.take(2)
                shown.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis) }
                if (!expanded && j.failures.size > 2) Text(t("点按查看全部 ${j.failures.size} 条", "Tap to show all ${j.failures.size}"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (j.lastErrorDetail != null || j.error != null || j.failures.isNotEmpty()) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = { copyJobError(ctx, j) }) { Text(t("复制错误", "Copy error")) }
                    if (expanded) TextButton(onClick = {
                        val log = vm.g.transferLog.read().takeLast(400_000)
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("transfer.log", log.ifEmpty { "(empty)" }))
                        android.widget.Toast.makeText(ctx, t("已复制传输日志", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(t("复制日志", "Copy log")) }
                }
                when {
                    j.state == JobState.PAUSED -> TextButton(onClick = { engine.resume(j.id) }) { Text(t("继续", "Resume")) }
                    !j.state.finished -> TextButton(onClick = { engine.pause(j.id) }) { Text(t("暂停", "Pause")) }
                }
                if (!j.state.finished) TextButton(onClick = { engine.cancel(j.id) }) { Text(t("取消", "Cancel")) }
                if (j.state == JobState.FAILED || j.state == JobState.CANCELLED) TextButton(onClick = {
                    engine.retry(j.id); com.ghostpanter.files.transfer.TransferService.start(vm.getApplication())
                }) { Text(t("重试（续传）", "Retry (resume)")) }
                if (j.state.finished) TextButton(onClick = { engine.remove(j.id) }) { Text(t("移除", "Remove")) }
            }
        }
    }
}

/** Full error report of a job (state, cause chain + stack trace, failures) to the clipboard. */
private fun copyJobError(ctx: android.content.Context, j: JobSnapshot) {
    val text = buildString {
        appendLine("Ghost 文件 ${com.ghostpanter.files.BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("${j.title} · ${j.state} · ${j.doneBytes}/${j.totalBytes} B · ${j.doneFiles}/${j.totalFiles} · 重试 ${j.retries}")
        j.error?.let { appendLine("错误：$it") }
        if (j.failures.isNotEmpty()) { appendLine("失败文件："); j.failures.forEach { appendLine("  $it") } }
        appendLine()
        append(j.lastErrorDetail ?: j.lastError ?: "")
    }
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Ghost 文件 error", text))
    android.widget.Toast.makeText(ctx, t("已复制错误详情（日志：files/logs/transfer.log）", "Error copied"), android.widget.Toast.LENGTH_SHORT).show()
}

private fun stateLabel(s: JobState) = when (s) {
    JobState.QUEUED -> t("排队中", "Queued")
    JobState.PREPARING -> t("扫描中", "Scanning")
    JobState.RUNNING -> t("传输中", "Running")
    JobState.PAUSED -> t("已暂停", "Paused")
    JobState.COMPLETED -> t("完成", "Done")
    JobState.FAILED -> t("失败", "Failed")
    JobState.CANCELLED -> t("已取消", "Cancelled")
}

// ------------------------------------------------------------------ recycle bin

@Composable
fun TrashScreen(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RecycleBin.Item>?>(null) }
    fun reload() { scope.launch { items = withContext(Dispatchers.IO) { runCatching { vm.trash.list() }.getOrDefault(emptyList()) } } }
    LaunchedEffect(Unit) { reload() }
    fun act(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { runCatching(block).onFailure { vm.toast(it.message ?: "error") } }
        reload()
    }
    SubScaffold(t("回收站", "Recycle bin"), { vm.screen = Screen.Main }, actions = {
        TextButton(onClick = { act { vm.trash.empty() } }) { Text(t("清空", "Empty")) }
    }) { pad ->
        val list = items
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list.isEmpty() -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("回收站为空（在设置里开启“删除时移入回收站”）", "Empty (enable in settings)"), color = MaterialTheme.colorScheme.outline)
            }
            else -> LazyColumn(Modifier.padding(pad)) {
                items(list, key = { it.trashPath }) { item ->
                    ListItem(
                        headlineContent = { Text(VPath.name(item.originalPath)) },
                        supportingContent = { Text("${item.originalPath}\n${formatTime(item.deletedAt)}", fontSize = 11.sp) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { act { vm.trash.restore(item) } }) { Icon(Icons.Filled.Restore, t("恢复", "Restore")) }
                                IconButton(onClick = { act { vm.trash.purge(item) } }) { Icon(Icons.Filled.DeleteForever, t("彻底删除", "Delete")) }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ text editor

private val GBK: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)

private fun decodeText(bytes: ByteArray): Pair<String, Charset> {
    val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    return try { dec.decode(ByteBuffer.wrap(bytes)).toString() to Charsets.UTF_8 } catch (e: Exception) { String(bytes, GBK) to GBK }
}

@Composable
fun EditorScreen(vm: MainViewModel, s: Screen.Editor) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }
    var charset by remember { mutableStateOf(Charsets.UTF_8) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(s) {
        withContext(Dispatchers.IO) {
            runCatching {
                val fs = vm.fsm.get(s.fsId)
                val st = fs.stat(s.path)
                if (st != null && st.size > 8L * 1024 * 1024) error(t("文件过大（>8MB），请用其他应用打开", "File too large (>8MB)"))
                val (str, cs) = decodeText(fs.openRead(s.path).use { it.readBytes() })
                text = str; original = str; charset = cs
            }.onFailure { error = it.message }
        }
    }
    val dirty = text != null && text != original
    fun exit() { if (dirty) confirmExit = true else vm.screen = Screen.Main }
    androidx.activity.compose.BackHandler { exit() }
    SubScaffold(VPath.name(s.path) + if (dirty) " *" else "", { exit() }, actions = {
        Text(charset.name(), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        IconButton(onClick = { wrap = !wrap }) { Icon(Icons.AutoMirrored.Filled.WrapText, t("自动换行", "Wrap")) }
        IconButton(enabled = dirty && !saving, onClick = {
            saving = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching { vm.fsm.get(s.fsId).openWrite(s.path).use { it.write(text!!.toByteArray(charset)) } }
                }
                saving = false
                r.onSuccess { original = text!!; vm.toast(t("已保存", "Saved")) }.onFailure { vm.toast(t("保存失败: ", "Save failed: ") + it.message) }
            }
        }) { Icon(Icons.Filled.Save, t("保存", "Save")) }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when {
                error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                text == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    val base = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)
                    BasicTextField(
                        value = text!!, onValueChange = { text = it }, textStyle = style,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = if (wrap) base else base.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        text = { Text(t("有未保存的修改，确定放弃？", "Discard unsaved changes?")) },
        confirmButton = { TextButton(onClick = { confirmExit = false; vm.screen = Screen.Main }) { Text(t("放弃", "Discard")) } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(t("取消", "Cancel")) } },
    )
}

// ------------------------------------------------------------------ image viewer

@Composable
fun ImageScreen(vm: MainViewModel, s: Screen.Image) {
    var index by remember { mutableIntStateOf(s.siblings.indexOf(s.path).coerceAtLeast(0)) }
    val path = s.siblings.getOrNull(index) ?: s.path
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    var err by remember(path) { mutableStateOf<String?>(null) }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            runCatching {
                val fs = vm.fsm.get(s.fsId)
                val bytes = fs.openRead(path).use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / sample > 4096 || o.outHeight / sample > 4096) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error(t("无法解码图片", "Cannot decode image"))
            }.onSuccess { bmp = it }.onFailure { err = it.message }
        }
    }
    SubScaffold("${VPath.name(path)}  (${index + 1}/${s.siblings.size.coerceAtLeast(1)})", { vm.screen = Screen.Main }) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize().background(Color.Black)
                .pointerInput(path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                }
                .pointerInput(path) {
                    detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = androidx.compose.ui.geometry.Offset.Zero }, onTap = { pos ->
                        if (scale > 1f) return@detectTapGestures
                        if (pos.x < size.width / 3f && index > 0) index--
                        else if (pos.x > size.width * 2 / 3f && index < s.siblings.size - 1) index++
                    })
                },
        ) {
            val b = bmp
            when {
                b != null -> Image(
                    b.asImageBitmap(), null,
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
                err != null -> Text(err!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

// ------------------------------------------------------------------ APK info

@Composable
fun ApkScreen(vm: MainViewModel, activity: MainActivity, s: Screen.Apk) {
    val pm = activity.packageManager
    val info: PackageInfo? = remember(s.file) {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(s.file.path, PackageManager.GET_PERMISSIONS)?.also {
            it.applicationInfo?.sourceDir = s.file.path
            it.applicationInfo?.publicSourceDir = s.file.path
        }
    }
    val installed: PackageInfo? = remember(info) { info?.let { runCatching { pm.getPackageInfo(it.packageName, 0) }.getOrNull() } }
    SubScaffold(s.label, { vm.screen = Screen.Main }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (info == null) { Text(t("无法解析 APK", "Cannot parse APK"), color = MaterialTheme.colorScheme.error); return@Column }
            val ai = info.applicationInfo
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = remember(info) { runCatching { ai?.loadIcon(pm)?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull() }
                icon?.let { Image(it, null, Modifier.size(64.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(ai?.loadLabel(pm)?.toString() ?: info.packageName, style = MaterialTheme.typography.titleLarge)
                    Text(info.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(12.dp))
            @Suppress("DEPRECATION")
            val vc = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            @Composable fun kv(k: String, v: String) = Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, Modifier.width(110.dp), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp); Text(v, fontSize = 13.sp)
            }
            kv(t("版本", "Version"), "${info.versionName} ($vc)")
            kv("minSdk", (ai?.minSdkVersion ?: 0).toString())
            kv("targetSdk", (ai?.targetSdkVersion ?: 0).toString())
            kv(t("大小", "Size"), formatSize(s.file.length()))
            @Suppress("DEPRECATION")
            installed?.let { kv(t("已安装版本", "Installed"), "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()})") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { activity.installApk(s.file) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (installed != null) t("更新安装", "Update") else t("安装", "Install"))
            }
            val perms = info.requestedPermissions?.toList().orEmpty()
            if (perms.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(t("权限 (${perms.size})", "Permissions (${perms.size})"), style = MaterialTheme.typography.titleSmall)
                perms.forEach { Text(it.removePrefix("android.permission."), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

// ------------------------------------------------------------------ search

@Composable
fun SearchScreen(vm: MainViewModel, s: Screen.Search) {
    var query by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<FileEntry>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun start() {
        job?.cancel()
        results.clear()
        if (query.isBlank()) return
        running = true
        job = scope.launch {
            withContext(Dispatchers.IO) {
                val ctx = coroutineContext
                FsSearch.search(vm.fsm.get(s.fsId), s.root, query, recursive, vm.g.settings.showHidden, isCancelled = { !ctx.isActive }) { e ->
                    scope.launch { results.add(e) }
                }
            }
            running = false
        }
    }
    SubScaffold(t("搜索：", "Search: ") + s.root, { job?.cancel(); vm.screen = Screen.Main }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text(t("文件名，支持 * ? 通配", "Name, supports * ?")) })
                IconButton(onClick = { start() }) { Icon(Icons.Filled.Search, null) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Checkbox(recursive, { recursive = it }); Text(t("包含子目录", "Recursive"))
                Spacer(Modifier.weight(1f))
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { job?.cancel(); running = false }) { Text(t("停止", "Stop")) }
                }
                Text(t("${results.size} 个结果", "${results.size} results"), fontSize = 12.sp)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results.toList(), key = { it.path }) { e ->
                    val (icon, tint) = iconFor(e)
                    ListItem(
                        leadingContent = { Icon(icon, null, tint = tint) },
                        headlineContent = { Text(e.name) },
                        supportingContent = { Text(VPath.parent(e.path), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable {
                            job?.cancel()
                            val tab = vm.activeTab
                            vm.screen = Screen.Main
                            if (e.isDirectory) vm.navigate(tab, s.fsId, e.path)
                            else { vm.navigate(tab, s.fsId, VPath.parent(e.path)); tab.highlight = e.path }
                        },
                    )
                }
            }
        }
    }
}
