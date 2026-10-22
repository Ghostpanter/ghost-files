package com.ghostpanter.files.ui.adb

import com.ghostpanter.files.ui.GhostFilterChip
import com.ghostpanter.files.ui.GhostSwitch
import com.ghostpanter.files.ui.GhostChip
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.ghostpanter.files.ui.GhostTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.adb.Activation
import com.ghostpanter.adb.Activations
import com.ghostpanter.adb.AdbDevice
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.DeviceInfo
import com.ghostpanter.adb.InfoGroup
import com.ghostpanter.adb.ProcInfo
import com.ghostpanter.adb.Processes
import com.ghostpanter.adb.Reboot
import com.ghostpanter.files.ui.MainViewModel
import com.ghostpanter.files.ui.Screen
import com.ghostpanter.files.t
import com.ghostpanter.scrcpy.Devices
import com.ghostpanter.scrcpy.Settings
import com.ghostpanter.scrcpy.MediaMuxerSink
import com.ghostpanter.scrcpy.Session
import com.ghostpanter.scrcpy.StreamRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

/** Public GhostFiles folder (Pictures / Movies / Download), app-private fallback. */
fun outDir(ctx: Context, kind: String): File {
    @Suppress("DEPRECATION")
    val d = File(Environment.getExternalStoragePublicDirectory(kind), "GhostFiles")
    if (d.isDirectory || d.mkdirs()) return d
    return (ctx.getExternalFilesDir(kind) ?: ctx.filesDir).also { it.mkdirs() }
}

/** Folder for screen recordings: the configured one, else Movies/双栏文件管理器. */
fun recordOutDir(ctx: Context): File {
    Settings.recordDir(ctx)?.let { File(it) }?.let { if (it.isDirectory || it.mkdirs()) return it }
    @Suppress("DEPRECATION")
    val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), MediaMuxerSink.DEFAULT_FOLDER)
    if (d.isDirectory || d.mkdirs()) return d
    return outDir(ctx, Environment.DIRECTORY_MOVIES)
}

/** The running mirror of this serial, if any — recording then comes straight from its stream. */
fun liveMirror(serial: String): Session? {
    val d = Devices.parseAddress(serial)
    if (d != null) return Session.liveFor(d.host, d.port)
    val host = serial.substringBeforeLast(':')
    return Session.liveFor(host, serial.substringAfterLast(':', "").toIntOrNull() ?: 0)
}

fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "device" }

val TOOL_TABS = listOf("概览" to "Overview", "应用" to "Apps", "进程" to "Processes", "激活" to "Activation", "日志" to "Logcat")

@Composable
fun AdbToolsScreen(vm: MainViewModel, s: Screen.AdbTools) {
    val ctx = LocalContext.current
    val serial = s.serial
    BackHandler { vm.screen = Screen.Devices }
    AdbToolsFrame(
        serial, s.tab,
        onBack = { vm.screen = Screen.Devices },
        onMirror = { AdbNav.mirror(ctx, serial) },
        onShell = { vm.termTarget = serial; vm.pendingTerminal = serial; vm.screen = Screen.Terminal },
    ) { tab ->
        when (tab) {
            0 -> OverviewPane(vm::toast, serial)
            1 -> RemoteAppsPane(vm::toast, serial)
            2 -> ProcessesPane(vm::toast, serial)
            3 -> ActivationPane(vm::toast, serial)
            else -> LogcatPane(vm::toast, serial)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbToolsFrame(
    serial: String,
    initialTab: Int,
    onBack: () -> Unit,
    onMirror: () -> Unit,
    onShell: () -> Unit,
    body: @Composable (Int) -> Unit,
) {
    var tab by remember(serial) { mutableIntStateOf(initialTab) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
                    title = {
                        Column {
                            Text(adbLabel(serial), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(serial, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    actions = {
                        IconButton(onClick = onMirror) { Icon(Icons.Filled.Cast, t("投屏控制", "Mirror")) }
                        IconButton(onClick = onShell) { Icon(Icons.Filled.Terminal, t("终端", "Shell")) }
                    },
                )
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
                    TOOL_TABS.forEachIndexed { i, (zh, en) -> Tab(tab == i, { tab = i }, text = { Text(t(zh, en)) }) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) { body(tab) }
    }
}

// ------------------------------------------------------------------ overview

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewPane(toast: (String) -> Unit, serial: String, previewInfo: List<InfoGroup>? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dev = remember(serial) { AdbPool.device(serial) }
    var info by remember(serial) { mutableStateOf(previewInfo) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableStateOf<Pair<File, ImageBitmap>?>(null) }
    var rec by remember { mutableStateOf<Process?>(null) }
    var recSec by remember { mutableIntStateOf(0) }
    var liveRec by remember { mutableStateOf<Session?>(null) }
    var recDir by remember { mutableStateOf(Settings.recordDir(ctx)) }
    var editRecDir by remember { mutableStateOf<String?>(null) }
    liveRec?.let { live ->
        LaunchedEffect(live) {
            while (isActive) {
                val since = live.recordingSince()
                if (since == 0L) { liveRec = null; break }
                recSec = ((System.currentTimeMillis() - since) / 1000).toInt()
                delay(500)
            }
        }
    }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var rebootMenu by remember { mutableStateOf(false) }
    var stayAwake by remember { mutableStateOf(Settings.stayAwake(ctx)) }
    var screenOff by remember { mutableStateOf(Settings.screenOff(ctx)) }
    var rootOn by remember { mutableStateOf(AdbPool.rootEnabled(serial)) }

    if (previewInfo == null) LaunchedEffect(serial, reload) {
        error = null
        io { dev.sh(DeviceInfo.SCRIPT, timeoutMs = 30_000).stdout }
            .onSuccess { out -> info = DeviceInfo.parse(out); DeviceInfo.model(out)?.let { AdbPool.setName(serial, it) } }
            .onFailure { error = it.message ?: it.toString() }
    }

    fun screenshot() {
        busy = t("截图中…", "Taking screenshot…")
        scope.launch {
            io {
                val remote = "/data/local/tmp/gf-shot-${System.nanoTime()}.png"
                dev.text("screencap -p $remote", timeoutMs = 30_000)
                val f = File(outDir(ctx, Environment.DIRECTORY_PICTURES), "screenshot-${safeName(adbLabel(serial))}-${stamp()}.png")
                try { f.outputStream().use { dev.pull(remote, it) } } finally { runCatching { dev.sh("rm -f $remote") } }
                MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
                f to BitmapFactory.decodeFile(f.path).asImageBitmap()
            }.onSuccess { shot = it; toast(t("已保存：", "Saved: ") + it.first.path) }
                .onFailure { toast(t("截图失败：", "Screenshot failed: ") + it.message) }
            busy = null
        }
    }

    fun startRecord() {
        // Mirroring this device: record its stream directly (no time limit, with sound).
        liveMirror(serial)?.let { live ->
            val started = try { live.startRecording(); true }
            catch (e: StreamRecorder.UnsupportedCodecException) {
                toast(t("当前投屏编码无法直接封装，改用 screenrecord", "Mirror codec can't be muxed here; using screenrecord")); false
            } catch (e: Exception) { toast(t("录屏失败：", "Recording failed: ") + e.message); return }
            if (started) {
                recSec = 0; liveRec = live
                toast(t("正在从投屏画面录制（含声音）", "Recording from the mirror stream (with sound)"))
                return
            }
        }
        val remote = "/data/local/tmp/gf-rec-${System.nanoTime()}.mp4"
        scope.launch {
            val p = io { dev.runner().start("exec screenrecord --bit-rate 8000000 --time-limit 180 $remote") }
                .getOrElse { toast(t("录屏启动失败：", "Recording failed: ") + it.message); return@launch }
            rec = p; recSec = 0
            val ticker = launch { while (isActive) { delay(1000); recSec++ } }
            io { com.ghostpanter.files.core.Proc.await(p, 200_000) }
            ticker.cancel()
            rec = null
            busy = t("正在取回录像…", "Pulling recording…")
            delay(600)
            io {
                val f = File(recordOutDir(ctx), "screenrecord-${safeName(adbLabel(serial))}-${stamp()}.mp4")
                try { f.outputStream().use { dev.pull(remote, it) } } finally { runCatching { dev.sh("rm -f $remote") } }
                if (f.length() == 0L) { f.delete(); throw java.io.IOException(com.ghostpanter.files.t("录像为空（目标可能不支持 screenrecord）", "Empty recording (screenrecord unsupported?)")) }
                MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
                f
            }.onSuccess { toast(t("录像已保存：", "Recording saved: ") + it.path) }
                .onFailure { toast(t("录屏失败：", "Recording failed: ") + it.message) }
            busy = null
        }
    }

    fun stopRecord() {
        liveRec?.let { live ->
            liveRec = null
            scope.launch {
                io { live.stopRecording() }
                    .onSuccess { files -> if (files.isNotEmpty()) toast(t("录像已保存：", "Recording saved: ") + files.joinToString("、")) }
                    .onFailure { toast(t("录屏失败：", "Recording failed: ") + it.message) }
            }
            return
        }
        scope.launch { io { dev.sh("pkill -INT screenrecord 2>/dev/null || kill -2 \$(pidof screenrecord)", timeoutMs = 10_000) } }
    }

    fun tcpip() {
        busy = t("切换中…", "Switching…")
        scope.launch {
            io {
                val out = dev.service("tcpip:5555")
                val d = Devices.parseAddress(serial)
                if (d != null) runCatching { Devices.upsert(ctx, Devices.Device(d.host, 5555)) }
                AdbPool.invalidate(serial)
                out
            }.onSuccess { toast(it.ifBlank { t("已请求开启 5555 端口", "Requested TCP port 5555") } + t("。已保存 ", ". Saved ") + serial.substringBeforeLast(':') + ":5555") }
                .onFailure { toast(t("失败：", "Failed: ") + it.message) }
            busy = null
        }
    }

    fun reboot(service: String) {
        scope.launch {
            io { dev.service(service, timeoutMs = 3_000); AdbPool.invalidate(serial) }
            toast(t("已发送重启指令", "Reboot sent"))
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(320.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("常用工具", "Quick tools"), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostChip({ screenshot() }, { Text(t("截图", "Screenshot")) }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) })
                        if (rec == null && liveRec == null) GhostChip({ startRecord() }, { Text(t("录屏", "Record")) }, leadingIcon = { Icon(Icons.Filled.FiberManualRecord, null) })
                        else GhostChip({ stopRecord() }, { Text(t("停止录屏 ", "Stop ") + "%d:%02d".format(recSec / 60, recSec % 60)) }, leadingIcon = { Icon(Icons.Filled.Stop, null) })
                        GhostChip({ confirm = t("开启网络 ADB（tcpip 5555）？目标的 adbd 会重启并监听 5555 端口，同一局域网内任何人都可尝试连接（仍需授权）。", "Enable network ADB (tcpip 5555)? adbd restarts listening on 5555; anyone on the LAN can try to connect (authorization still required).") to { tcpip() } },
                            { Text(t("无线 ADB 5555", "TCP/IP 5555")) }, leadingIcon = { Icon(Icons.Filled.Wifi, null) })
                        Box {
                            GhostChip({ rebootMenu = true }, { Text(t("高级重启", "Reboot…")) }, leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) })
                            DropdownMenu(rebootMenu, { rebootMenu = false }) {
                                Reboot.targets().forEach { (label, svc) ->
                                    DropdownMenuItem({ Text(label) }, { rebootMenu = false; confirm = "$label？" to { reboot(svc) } })
                                }
                            }
                        }
                        GhostChip({ reload++ }, { Text(t("刷新信息", "Refresh")) }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                    }
                    busy?.let { Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it, fontSize = 13.sp) } }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ToggleRow(t("控制时保持目标常亮", "Keep target awake while controlling"), stayAwake) { stayAwake = it; Settings.setStayAwake(ctx, it) }
                    ToggleRow(t("投屏时关闭目标屏幕", "Turn target screen off while mirroring"), screenOff) { screenOff = it; Settings.setScreenOff(ctx, it) }
                    ToggleRow(t("目标已 Root：进程 / 文件操作走 su", "Target rooted: use su for processes / files"), rootOn) { rootOn = it; AdbPool.setRootEnabled(serial, it) }
                    Row(Modifier.fillMaxWidth().clickable { editRecDir = recDir ?: "" }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("录屏保存位置", "Recordings folder"), Modifier.weight(1f), fontSize = 14.sp)
                        Text(recDir ?: MediaMuxerSink.defaultLocationLabel(), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                    }
                    shot?.let { (f, bmp) ->
                        Spacer(Modifier.size(8.dp))
                        Image(bmp, f.name, Modifier.heightIn(max = 240.dp).clickable { shot = null })
                        Text(f.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        error?.let { e -> item(span = StaggeredGridItemSpan.FullLine) { ErrorCard(e) { reload++ } } }
        if (info == null && error == null) item(span = StaggeredGridItemSpan.FullLine) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        info?.forEach { g ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(g.title, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.size(6.dp))
                        SelectionContainer {
                            Column {
                                g.rows.forEach { (k, v) ->
                                    Row(Modifier.padding(vertical = 2.dp)) {
                                        Text(k, Modifier.widthIn(min = 88.dp, max = 120.dp), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                                        Text(v, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editRecDir?.let { cur ->
        AlertDialog(onDismissRequest = { editRecDir = null },
            title = { Text(t("录屏保存位置", "Recordings folder")) },
            text = {
                Column {
                    GhostTextField(cur, { editRecDir = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("/sdcard/" + MediaMuxerSink.defaultLocationLabel()) })
                    Spacer(Modifier.size(6.dp))
                    Text(t("留空则保存到 ", "Leave empty for ") + MediaMuxerSink.defaultLocationLabel(), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = { TextButton({
                val v = cur.trim()
                Settings.setRecordDir(ctx, v); recDir = Settings.recordDir(ctx); editRecDir = null
            }) { Text(t("保存", "Save")) } },
            dismissButton = { TextButton({ editRecDir = null }) { Text(t("取消", "Cancel")) } })
    }
    confirm?.let { (msg, go) ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(msg) },
            confirmButton = { TextButton({ confirm = null; go() }) { Text(t("确定", "OK")) } },
            dismissButton = { TextButton({ confirm = null }) { Text(t("取消", "Cancel")) } })
    }
}

@Composable
fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        GhostSwitch(on, onChange)
    }
}

@Composable
fun ErrorCard(msg: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("无法连接目标设备", "Cannot reach the target"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text(msg, fontSize = 13.sp)
            Text(t("无线调试端口在目标重启或开关无线调试后会变化，可在「配对 / 连接」中长按设备 →「重新发现端口」。",
                "The wireless-debugging port changes after a reboot or toggle; in “Pair / connect”, long-press the device → rediscover port."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onRetry) { Text(t("重试", "Retry")) }
        }
    }
}

// ------------------------------------------------------------------ processes

@Composable
fun ProcessesPane(toast: (String) -> Unit, serial: String, preview: List<ProcInfo>? = null) {
    val scope = rememberCoroutineScope()
    val dev = remember(serial) { AdbPool.device(serial) }
    var list by remember(serial) { mutableStateOf(preview) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var appsOnly by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<ProcInfo?>(null) }
    val root = AdbPool.rootEnabled(serial)

    if (preview == null) LaunchedEffect(serial, reload, auto) {
        do {
            io { Processes.parse(dev.sh(Processes.SCRIPT, root = root, timeoutMs = 20_000).stdout) }
                .onSuccess { list = it; error = null }.onFailure { error = it.message }
            if (auto) delay(3000)
        } while (auto && isActive)
    }

    fun act(cmd: String, done: String) {
        scope.launch {
            io { dev.sh(cmd, root = root, timeoutMs = 15_000) }
                .onSuccess { r -> toast(if (r.ok) done else r.stdout.trim().ifEmpty { "exit ${r.code}" }); reload++ }
                .onFailure { toast(it.message ?: "error") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(t("搜索进程 / 包名 / PID", "Search name / package / PID")) })
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, t("刷新", "Refresh")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(t("CPU", "CPU"), t("内存", "Memory"), t("名称", "Name"), "PID").forEachIndexed { i, l -> GhostFilterChip(sort == i, { sort = i }, { Text(l) }) }
            GhostFilterChip(appsOnly, { appsOnly = !appsOnly }, { Text(t("仅应用", "Apps only")) })
            GhostFilterChip(auto, { auto = !auto }, { Text(t("自动刷新", "Auto refresh")) })
        }
        error?.let { ErrorCard(it) { reload++ } }
        val shown = list.orEmpty().filter { p ->
            (!appsOnly || p.pkg != null) && (query.isBlank() || p.name.contains(query, true) || p.pid.toString() == query.trim())
        }.let { l ->
            when (sort) { 0 -> l.sortedByDescending { it.cpu }; 1 -> l.sortedByDescending { it.rssKb }; 2 -> l.sortedBy { it.name.lowercase() }; else -> l.sortedBy { it.pid } }
        }
        Text(t("共 ${shown.size} 个进程", "${shown.size} processes") + if (root) " · su" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pid }) { p ->
                Box {
                    Row(Modifier.fillMaxWidth().clickable { menuFor = p }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            Text("PID ${p.pid} · PPID ${p.ppid} · ${p.user} · ${p.state}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("%.1f%%".format(p.cpu), fontSize = 13.sp)
                            Text(DeviceInfo.human(p.rssKb * 1024), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    DropdownMenu(menuFor == p, { menuFor = null }) {
                        p.pkg?.let { pkg -> DropdownMenuItem({ Text(t("强行停止 $pkg", "Force stop $pkg")) }, { menuFor = null; act("am force-stop ${AdbDevice.q(pkg)}", t("已强行停止", "Force-stopped")) }) }
                        DropdownMenuItem({ Text(t("结束进程（kill -9）", "Kill (kill -9)")) }, { menuFor = null; act(Processes.kill(p.pid), t("已结束 ${p.pid}", "Killed ${p.pid}")) })
                        DropdownMenuItem({ Text(t("发送 SIGTERM", "Send SIGTERM")) }, { menuFor = null; act(Processes.kill(p.pid, 15), t("已发送", "Sent")) })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ activation

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivationPane(toast: (String) -> Unit, serial: String, previewInstalled: Set<String>? = null) {
    val scope = rememberCoroutineScope()
    val dev = remember(serial) { AdbPool.device(serial) }
    var installed by remember(serial) { mutableStateOf(previewInstalled) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<Triple<Activation, String, String>?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    if (previewInstalled == null) LaunchedEffect(serial) {
        io { dev.sh("pm list packages", timeoutMs = 20_000).stdout.lineSequence().map { it.trim().removePrefix("package:") }.toSet() }
            .onSuccess { installed = it }.onFailure { installed = emptySet(); toast(it.message ?: "error") }
    }
    fun run(a: Activation, label: String, cmd: String) {
        running = a.id
        scope.launch {
            val r = io { dev.sh(cmd, timeoutMs = 90_000) }
            running = null
            output = "${a.name} · $label" to r.fold({ res -> res.stdout.trim().ifEmpty { if (res.ok) "OK" else "exit ${res.code}" } + if (res.ok) "" else "\n(exit ${res.code})" }, { it.message ?: it.toString() })
        }
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(340.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Text(t("一键激活：在目标设备上执行各应用官方公布的 ADB 命令（来源见每张卡片）。", "One-tap activation: runs each app's officially published ADB command on the target (sources on each card)."),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        items(Activations.all().size) { i ->
            val a = Activations.all()[i]
            val has = installed?.let { set -> a.packages.any { it in set } }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(when (has) { null -> "…"; true -> t("已安装", "Installed"); false -> t("未安装", "Not installed") }, fontSize = 12.sp,
                            color = if (has == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    if (a.note.isNotEmpty()) Text(a.note, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    a.commands.forEach { (label, cmd) ->
                        Spacer(Modifier.size(8.dp))
                        Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
                        OutlinedButton(onClick = { confirm = Triple(a, label, cmd) }, enabled = has == true && running == null) {
                            if (running == a.id) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                            Text(label)
                        }
                    }
                    if (a.sources.isNotEmpty()) {
                        Spacer(Modifier.size(6.dp))
                        a.sources.forEach { Text(t("来源：", "Source: ") + it, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline) }
                    }
                }
            }
        }
    }
    confirm?.let { (a, label, cmd) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("${a.name} · $label") },
            text = { Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = { TextButton({ confirm = null; run(a, label, cmd) }) { Text(t("执行", "Run")) } },
            dismissButton = { TextButton({ confirm = null }) { Text(t("取消", "Cancel")) } })
    }
    output?.let { (title, text) ->
        AlertDialog(onDismissRequest = { output = null }, title = { Text(title) },
            text = { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton({ output = null }) { Text(t("关闭", "Close")) } })
    }
}
