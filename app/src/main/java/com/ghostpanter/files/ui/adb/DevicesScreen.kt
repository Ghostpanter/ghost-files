package com.ghostpanter.files.ui.adb

import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.adb.AdbFs
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.usb.UsbAdb
import com.ghostpanter.files.ui.MainViewModel
import com.ghostpanter.files.ui.Screen
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class DevState { CHECKING, ONLINE, OFFLINE }

/** Probes a device (connects if needed) and caches its model name. */
suspend fun probe(serial: String): Boolean = withContext(Dispatchers.IO) {
    withTimeoutOrNull(15_000) {
        runCatching {
            val model = AdbPool.device(serial).text("getprop ro.product.model", timeoutMs = 12_000).trim()
            if (model.isNotEmpty()) AdbPool.setName(serial, model)
            true
        }.getOrDefault(false)
    } ?: false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    DevicesContent(
        rememberSavedDevices(), null,
        usb = rememberUsbTargets(),
        cb = DeviceCallbacks(
            back = { vm.screen = Screen.Main },
            pair = { AdbNav.pairPanel(ctx) },
            mirror = { AdbNav.mirror(ctx, it) },
            browse = { serial, pane, root -> if (pane != null) vm.activePane = pane; vm.openFs(AdbFs.idFor(serial, root), if (root) "/" else null) },
            tools = { vm.screen = Screen.AdbTools(it) },
            shell = { vm.termTarget = it; vm.pendingTerminal = it; vm.screen = Screen.Terminal },
            usbGrant = { serial ->
                val key = UsbAdb.keyOfSerial(serial)
                UsbAdb.find(ctx, key)?.let { UsbAdb.requestPermission(ctx, it) }
            },
            forget = { serial -> scope.launch(Dispatchers.IO) { AdbPool.forget(serial) } },
        ),
    )
}

/** One attached USB device with an ADB interface. */
data class UsbRow(val serial: String, val label: String, val granted: Boolean)

/** Attached USB ADB devices; refreshed on attach / detach / permission answers and on resume. */
@Composable
fun rememberUsbTargets(): List<UsbRow> {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        UsbAdb.init(ctx)
        val l: () -> Unit = { android.os.Handler(android.os.Looper.getMainLooper()).post { rev++ } }
        UsbAdb.addListener(l)
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) rev++ }
        owner.lifecycle.addObserver(obs)
        onDispose { UsbAdb.removeListener(l); owner.lifecycle.removeObserver(obs) }
    }
    return remember(rev) { runCatching { UsbAdb.targets(ctx).map { UsbRow(it.serial, it.label, it.hasPermission) } }.getOrDefault(emptyList()) }
}

class DeviceCallbacks(
    val back: () -> Unit,
    val pair: () -> Unit,
    val mirror: (String) -> Unit,
    val browse: (serial: String, pane: Int?, root: Boolean) -> Unit,
    val tools: (String) -> Unit,
    val shell: (String) -> Unit,
    val usbGrant: (String) -> Unit = {},
    /** Removes a saved wireless device from this app (not the pairing on the target). */
    val forget: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(savedAll: List<String>, preview: List<Pair<String, DevState>>?, cb: DeviceCallbacks, usb: List<UsbRow> = emptyList()) {
    // Serials removed on this page: hidden immediately (the saved list is otherwise only re-read on resume).
    var gone by remember { mutableStateOf(emptySet<String>()) }
    val saved = savedAll.filterNot { UsbAdb.isUsbSerial(it) || it in gone }
    var refresh by remember { mutableIntStateOf(0) }
    // Latest probe result per serial (hoisted so "clear offline" knows which ones are offline).
    val states = remember { mutableStateMapOf<String, DevState>() }
    var confirmForget by remember { mutableStateOf<String?>(null) }
    var confirmOffline by remember { mutableStateOf(false) }
    fun forget(serial: String) { gone = gone + serial; states.remove(serial); cb.forget(serial) }
    val offline = saved.filter { (states[it] ?: preview?.firstOrNull { p -> p.first == it }?.second) == DevState.OFFLINE }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
                title = { Text(t("设备 / 投屏", "Devices / Mirror")) },
                navigationIcon = { IconButton(onClick = cb.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (offline.isNotEmpty()) IconButton(onClick = { confirmOffline = true }) {
                        Icon(Icons.Rounded.DeleteSweep, t("清除离线设备", "Remove offline devices"))
                    }
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, t("刷新", "Refresh")) }
                    TextButton(onClick = cb.pair, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(t("配对 / 连接", "Pair / connect"))
                    }
                },
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (usb.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(t("USB 连接", "USB")) }
                items(usb, key = { "usb:" + it.serial }) { u ->
                    if (!u.granted) UsbGrantCard(u, cb)
                    else {
                        if (u.label.isNotBlank() && AdbPool.name(u.serial) == null) AdbPool.setName(u.serial, u.label)
                        var state by remember(u.serial) { mutableStateOf(preview?.firstOrNull { it.first == u.serial }?.second ?: DevState.CHECKING) }
                        if (preview == null) LaunchedEffect(u.serial, refresh) {
                            state = DevState.CHECKING
                            state = if (probe(u.serial)) DevState.ONLINE else DevState.OFFLINE
                        }
                        DeviceCard(cb, u.serial, state)
                    }
                }
                if (saved.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(t("无线连接", "Wireless")) }
            }
            if (saved.isEmpty() && usb.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint(cb.pair) }
            }
            items(saved, key = { it }) { serial ->
                val state = states[serial] ?: preview?.firstOrNull { it.first == serial }?.second ?: DevState.CHECKING
                if (preview == null) LaunchedEffect(serial, refresh) {
                    states[serial] = DevState.CHECKING
                    val ok = probe(serial)
                    if (serial !in gone) states[serial] = if (ok) DevState.ONLINE else DevState.OFFLINE
                }
                DeviceCard(cb, serial, state, onRemove = { confirmForget = serial })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    t("提示：本应用与独立版 scrcpy-android 的 ADB 密钥不同，首次使用需在目标设备的「无线调试」中重新配对。也可用 USB / OTG 线直连：目标开启「USB 调试」后插上即可，首次需在目标上点「允许 USB 调试」。",
                        "Note: this app has its own ADB key (different from the standalone scrcpy-android), so pair the target again under Wireless debugging. Or connect with a USB / OTG cable: enable USB debugging on the target, plug it in and tap “Allow USB debugging” there the first time."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
    confirmForget?.let { serial ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text(t("移除此设备？", "Remove this device?")) },
            text = {
                Column {
                    Text(AdbPool.name(serial) ?: t("未知设备", "Unknown device"), fontWeight = FontWeight.SemiBold)
                    Text(serial, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(12.dp))
                    Text(t("只删除本应用中的记录，不会取消目标设备上的配对。", "Only removes this app's record; the pairing on the target device is kept."))
                }
            },
            confirmButton = { TextButton(onClick = { confirmForget = null; forget(serial) }) { Text(t("移除", "Remove"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text(t("取消", "Cancel")) } },
        )
    }
    if (confirmOffline) {
        val list = offline
        AlertDialog(
            onDismissRequest = { confirmOffline = false },
            title = { Text(t("清除 ${list.size} 个离线设备？", "Remove ${list.size} offline device(s)?")) },
            text = {
                Column {
                    list.take(8).forEach { s ->
                        Text((AdbPool.name(s)?.let { "$it · " } ?: "") + s, fontSize = 13.sp, maxLines = 1)
                    }
                    if (list.size > 8) Text(t("…等 ${list.size} 个", "…${list.size} in total"), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(12.dp))
                    Text(t("仅移除检测为离线的已保存设备；只删除本应用中的记录，不会取消目标设备上的配对。",
                        "Only saved devices detected as offline are removed, and only from this app; pairings on the targets are kept."))
                }
            },
            confirmButton = { TextButton(onClick = { confirmOffline = false; list.forEach { forget(it) } }) { Text(t("清除", "Remove"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmOffline = false }) { Text(t("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun EmptyHint(onPair: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(t("还没有目标设备", "No target device yet"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.size(8.dp))
            Text(t("1. 在目标设备打开「开发者选项 → 无线调试」\n2. 点「使用配对码配对设备」，或扫码配对\n3. 回到这里点「配对 / 连接」完成配对\n\n配对后即可投屏控制、在双栏中浏览目标设备文件、管理应用 / 进程、看日志、开终端。",
                "1. On the target: Developer options → Wireless debugging\n2. Pair with a pairing code (or QR)\n3. Tap “Pair / connect” here\n\nThen mirror and control it, browse its files in either pane, manage apps / processes, read logcat and open a shell."))
            Spacer(Modifier.size(12.dp))
            Button(onClick = onPair) { Text(t("配对 / 连接", "Pair / connect")) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(cb: DeviceCallbacks, serial: String, state: DevState, onRemove: (() -> Unit)? = null) {
    var paneMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val longPress = if (onRemove != null) Modifier.pointerInput(serial) { detectTapGestures(onLongPress = { onRemove() }) } else Modifier
    Card(Modifier.fillMaxWidth().then(longPress), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(cs.surfaceVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (UsbAdb.isUsbSerial(serial)) Icons.Rounded.Usb else Icons.Rounded.PhoneAndroid, null, Modifier.size(24.dp), tint = cs.primary) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(AdbPool.name(serial) ?: t("未知设备", "Unknown device"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                    Text(if (UsbAdb.isUsbSerial(serial)) "USB · " + UsbAdb.keyOfSerial(serial) else serial, fontSize = 12.sp, color = cs.outline, maxLines = 1)
                }
                val online = state == DevState.ONLINE
                val l = when (state) {
                    DevState.ONLINE -> t("在线", "Online")
                    DevState.OFFLINE -> t("离线", "Offline")
                    DevState.CHECKING -> t("检测中", "Checking")
                }
                Text(
                    l, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (online) cs.primary else cs.outline,
                    modifier = Modifier.background(if (online) cs.primaryContainer else cs.surfaceVariant, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                )
                if (onRemove != null) Box {
                    IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.MoreVert, t("更多", "More"), Modifier.size(20.dp), tint = cs.outline)
                    }
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        DropdownMenuItem({ Text(t("移除设备", "Remove device")) }, { moreMenu = false; onRemove() },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                    }
                }
            }
            Spacer(Modifier.size(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftChip(t("投屏控制", "Mirror"), Icons.Rounded.Cast, accent = true) { cb.mirror(serial) }
                Box {
                    SoftChip(t("浏览文件", "Files"), Icons.Rounded.Folder) { paneMenu = true }
                    DropdownMenu(paneMenu, { paneMenu = false }) {
                        DropdownMenuItem({ Text(t("在左栏打开", "Open in left pane")) }, { paneMenu = false; cb.browse(serial, 0, false) })
                        DropdownMenuItem({ Text(t("在右栏打开", "Open in right pane")) }, { paneMenu = false; cb.browse(serial, 1, false) })
                        DropdownMenuItem({ Text(t("以 Root 浏览（目标需 su）", "Browse as root (target su)")) }, { paneMenu = false; cb.browse(serial, null, true) })
                    }
                }
                SoftChip(t("工具", "Tools"), Icons.Rounded.Build) { cb.tools(serial) }
                SoftChip(t("终端", "Shell"), Icons.Rounded.Terminal) { cb.shell(serial) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp, top = 4.dp))

@Composable
private fun UsbGrantCard(u: UsbRow, cb: DeviceCallbacks) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(cs.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Usb, null, Modifier.size(24.dp), tint = cs.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(u.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                    Text(t("已插入 · 需要 USB 访问授权", "Attached · USB access needed"), fontSize = 12.sp, color = cs.outline, maxLines = 1)
                }
            }
            Spacer(Modifier.size(14.dp))
            SoftChip(t("授权 USB 访问", "Allow USB access"), Icons.Rounded.Usb, accent = true) { cb.usbGrant(u.serial) }
        }
    }
}

/** Soft pill action (HyperOS style): tinted fill, no outline. */
@Composable
fun SoftChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg = if (accent) cs.primary else cs.onSurface
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(if (accent) cs.primaryContainer else cs.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = if (accent) cs.primary else cs.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}
