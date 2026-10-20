package com.ghostpanter.files.ui.adb

import androidx.compose.material3.TextButton
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
    DevicesContent(
        rememberSavedDevices(), null,
        DeviceCallbacks(
            back = { vm.screen = Screen.Main },
            pair = { AdbNav.pairPanel(ctx) },
            mirror = { AdbNav.mirror(ctx, it) },
            browse = { serial, pane, root -> if (pane != null) vm.activePane = pane; vm.openFs(AdbFs.idFor(serial, root), if (root) "/" else null) },
            tools = { vm.screen = Screen.AdbTools(it) },
            shell = { vm.termTarget = it; vm.pendingTerminal = it; vm.screen = Screen.Terminal },
        ),
    )
}

class DeviceCallbacks(
    val back: () -> Unit,
    val pair: () -> Unit,
    val mirror: (String) -> Unit,
    val browse: (serial: String, pane: Int?, root: Boolean) -> Unit,
    val tools: (String) -> Unit,
    val shell: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(saved: List<String>, preview: List<Pair<String, DevState>>?, cb: DeviceCallbacks) {
    var refresh by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = com.ghostpanter.files.ui.ghostTopBarColors(),
                title = { Text(t("设备 / 投屏", "Devices / Mirror")) },
                navigationIcon = { IconButton(onClick = cb.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
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
            if (saved.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyHint(cb.pair) }
            }
            items(saved, key = { it }) { serial ->
                var state by remember(serial) { mutableStateOf(preview?.firstOrNull { it.first == serial }?.second ?: DevState.CHECKING) }
                if (preview == null) LaunchedEffect(serial, refresh) {
                    state = DevState.CHECKING
                    state = if (probe(serial)) DevState.ONLINE else DevState.OFFLINE
                }
                DeviceCard(cb, serial, state)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    t("提示：本应用与独立版 scrcpy-android 的 ADB 密钥不同，首次使用需在目标设备的「无线调试」中重新配对。",
                        "Note: this app has its own ADB key (different from the standalone scrcpy-android), so pair the target again under Wireless debugging."),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(4.dp),
                )
            }
        }
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
private fun DeviceCard(cb: DeviceCallbacks, serial: String, state: DevState) {
    var paneMenu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(cs.surfaceVariant, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(24.dp), tint = cs.primary) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(AdbPool.name(serial) ?: t("未知设备", "Unknown device"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                    Text(serial, fontSize = 12.sp, color = cs.outline, maxLines = 1)
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
