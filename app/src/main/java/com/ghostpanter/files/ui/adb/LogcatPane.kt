package com.ghostpanter.files.ui.adb

import com.ghostpanter.files.ui.GhostFilterChip
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.adb.AdbChannel
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.LogLine
import com.ghostpanter.adb.Logcat
import com.ghostpanter.files.ui.MainViewModel
import com.ghostpanter.files.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val MAX_LINES = 20_000

fun levelColor(l: Char): Color = when (l) {
    'V' -> Color(0xFF8E8E93); 'D' -> Color(0xFF0A84FF); 'I' -> Color(0xFF30D158)
    'W' -> Color(0xFFFF9F0A); 'E', 'F' -> Color(0xFFFF453A); else -> Color(0xFF8E8E93)
}

@Composable
fun LogcatPane(toast: (String) -> Unit, serial: String, preview: List<LogLine>? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val buffer = remember(serial) { ArrayDeque<LogLine>().apply { preview?.let { addAll(it) } } }
    var rev by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var minLevel by remember { mutableStateOf('V') }
    var tag by remember { mutableStateOf("") }
    var pid by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var reconnect by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val channel = remember { arrayOfNulls<AdbChannel>(1) }

    if (preview == null) LaunchedEffect(serial, reconnect) {
        status = t("连接中…", "Connecting…")
        val pending = ArrayList<LogLine>()
        val lock = Any()
        val reader = launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val ch = AdbPool.device(serial).open("shell:" + Logcat.command())
                channel[0] = ch
                status = null
                ch.input.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (!isActive) break
                        val p = Logcat.parse(l) ?: if (l.isNotBlank() && !l.startsWith("-----")) LogLine("", 0, 0, 'I', "", l) else null
                        if (p != null) synchronized(lock) { pending += p }
                    }
                }
                status = t("日志流已结束", "Log stream ended")
            }.onFailure { status = t("日志流断开：", "Log stream failed: ") + it.message }
        }
        try {
            while (isActive) {
                delay(250)
                val batch = synchronized(lock) { if (pending.isEmpty()) null else ArrayList(pending).also { pending.clear() } }
                if (batch != null) {
                    buffer.addAll(batch)
                    while (buffer.size > MAX_LINES) buffer.removeFirst()
                    if (!paused) rev++
                }
            }
        } finally {
            reader.cancel()
            channel[0]?.let { c -> Thread { runCatching { c.close() } }.start() }
        }
    }
    DisposableEffect(serial) { onDispose { channel[0]?.let { c -> Thread { runCatching { c.close() } }.start() } } }

    val pidN = pid.trim().toIntOrNull()
    val shown = remember(rev, minLevel, tag, pidN, text, paused) {
        buffer.filter { it.tag.isEmpty() || Logcat.matches(it, minLevel, tag, pidN, text) }
    }
    LaunchedEffect(shown.size, paused) { if (!paused && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1) }

    fun export() {
        scope.launch {
            io {
                val f = File(outDir(ctx, Environment.DIRECTORY_DOWNLOADS), "logcat-${safeName(adbLabel(serial))}-${stamp()}.txt")
                f.bufferedWriter().use { w -> shown.forEach { w.write(if (it.tag.isEmpty()) it.msg else Logcat.format(it)); w.newLine() } }
                f
            }.onSuccess { toast(t("已导出：", "Exported: ") + it.path) }.onFailure { toast(it.message ?: "error") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(tag, { tag = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Tag") })
            OutlinedTextField(pid, { pid = it.filter(Char::isDigit) }, Modifier.width(96.dp).padding(start = 6.dp), singleLine = true,
                placeholder = { Text("PID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).padding(start = 6.dp), singleLine = true, placeholder = { Text(t("内容", "Text")) })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Logcat.LEVELS.forEach { l -> GhostFilterChip(minLevel == l, { minLevel = l }, { Text(l.toString(), color = levelColor(l)) }) }
            IconButton(onClick = { paused = !paused; if (!paused) rev++ }) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) t("继续", "Resume") else t("暂停", "Pause"))
            }
            IconButton(onClick = { buffer.clear(); rev++ }) { Icon(Icons.Filled.DeleteSweep, t("清空", "Clear")) }
            IconButton(onClick = { export() }) { Icon(Icons.Filled.Save, t("导出", "Export")) }
            Text(t("${shown.size} 行", "${shown.size} lines") + (status?.let { " · $it" } ?: "") + if (paused) t(" · 已暂停", " · paused") else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            if (status != null && status != t("连接中…", "Connecting…")) androidx.compose.material3.TextButton(onClick = { reconnect++ }) { Text(t("重连", "Reconnect")) }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest), state = listState) {
                itemsIndexed(shown) { _, l ->
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 1.dp)) {
                        if (l.tag.isEmpty()) Text(l.msg, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        else {
                            Text("${l.time.substringAfter(' ').trim()} ${l.level} ", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = levelColor(l.level))
                            Text("${l.tag}(${l.pid}): ${l.msg}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
