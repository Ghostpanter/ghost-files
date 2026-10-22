package com.ghostpanter.files.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.privilege.PairPhase
import com.ghostpanter.files.core.privilege.PairState
import com.ghostpanter.files.core.privilege.PrivilegeKind
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.privilege.ElevatedAccess
import com.ghostpanter.files.privilege.EmbeddedPrivilege
import com.ghostpanter.files.t

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivilegeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(EmbeddedPrivilege.state()) }
    var auto by remember { mutableStateOf(EmbeddedPrivilege.autoStart()) }
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val l: (PairState) -> Unit = { state = it; tick++ }
        EmbeddedPrivilege.addListener(l)
        onDispose { EmbeddedPrivilege.removeListener(l) }
    }
    // Re-read alive/kind when tick changes
    val kind = remember(tick, state) { ElevatedAccess.kind() }
    val alive = remember(tick, state) { EmbeddedPrivilege.alive() }
    val secure = remember(tick) { EmbeddedPrivilege.hasSecureSettings(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = { Text(t("内置提权", "Built-in privilege"), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Main }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(kind, alive, state, secure)

            CardGroup(t("操作", "Actions")) {
                when {
                    Build.VERSION.SDK_INT >= 30 -> {
                        Button(
                            onClick = { EmbeddedPrivilege.beginPairing(ctx) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            enabled = state.phase != PairPhase.PAIRING && state.phase != PairPhase.STARTING_SERVER,
                        ) { Text(t("配对并启动（通知栏输入配对码）", "Pair & start (enter code in notification)")) }
                        OutlinedButton(
                            onClick = { EmbeddedPrivilege.start(ctx) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        ) { Text(t("已配对 · 直接启动", "Already paired · start")) }
                    }
                    else -> {
                        Text(
                            t("Android 9/10 无系统无线调试配对。请用电脑或另一台手机（本应用 USB/OTG ADB）执行下面的启动命令，或使用 Root。",
                                "Android 9/10 has no wireless-debug pairing. Run the starter command from a PC or another phone (this app’s USB/OTG ADB), or use Root."),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                if (RootRunner.binaryPresent()) {
                    OutlinedButton(
                        onClick = { EmbeddedPrivilege.startViaRoot(ctx) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    ) { Text(t("用 Root 启动", "Start via Root")) }
                }
                TextButton(
                    onClick = { EmbeddedPrivilege.stop(); tick++ },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(t("停止服务", "Stop service")) }
            }

            CardGroup(t("开机自动拉起", "Start on boot")) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t("有 Wi‑Fi 时自动打开无线调试并启动", "Auto-enable wireless debugging on Wi‑Fi"), fontSize = 14.sp)
                        Text(
                            if (secure) t("已获得 WRITE_SECURE_SETTINGS", "WRITE_SECURE_SETTINGS granted")
                            else t("尚未获得 WRITE_SECURE_SETTINGS（授权成功后会自动尝试授予）",
                                "WRITE_SECURE_SETTINGS not granted yet (requested after a successful start)"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = {
                        auto = it; EmbeddedPrivilege.setAutoStart(it)
                    })
                }
            }

            CardGroup(t("手动启动命令", "Manual starter")) {
                val cmd = remember { EmbeddedPrivilege.manualStarterCommand(ctx) }
                Text(
                    t("在电脑或另一台已用本应用连上的手机上执行：", "Run on a PC or another phone already linked in this app:"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Text(
                    "adb shell \"$cmd\"",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            CardGroup(t("说明", "Notes")) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp, end = 10.dp))
                    Text(
                        t(
                            "内置提权通过系统「无线调试」以 shell 身份运行服务，效果接近 Shizuku。已安装并授权外置 Shizuku 时优先使用外置。\n\n" +
                                "开启开发者选项 / 无线调试后，部分银行类应用可能提示环境风险，不用时可以关掉无线调试或点「停止服务」。\n\n" +
                                "若 ROM 不允许应用改写安全设置，请手动打开「开发者选项 → 无线调试」。",
                            "Built-in privilege runs a shell-uid service through Wireless debugging (similar to Shizuku). External Shizuku is preferred when installed and granted.\n\n" +
                                "Developer options / Wireless debugging may trigger warnings in banking apps — turn wireless debugging off or tap Stop when idle.\n\n" +
                                "If the ROM blocks secure-settings writes, enable Developer options → Wireless debugging manually.",
                        ),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(kind: PrivilegeKind, alive: Boolean, state: PairState, secure: Boolean) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("状态", "Status"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            val line = when {
                kind == PrivilegeKind.EXTERNAL_SHIZUKU -> t("当前使用外置 Shizuku（优先）", "Using external Shizuku (preferred)")
                alive || state.phase == PairPhase.READY -> t("内置提权服务运行中", "Built-in privilege service running")
                state.phase == PairPhase.FAILED -> t("失败：", "Failed: ") + state.detail
                state.phase == PairPhase.WAITING_CODE -> t("等待通知栏配对码…", "Waiting for pairing code in notification…")
                state.phase == PairPhase.PAIRING || state.phase == PairPhase.CONNECTING || state.phase == PairPhase.STARTING_SERVER ->
                    t("进行中：", "In progress: ") + state.detail
                else -> t("未运行", "Not running")
            }
            Text(line, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (state.connectPort > 0) {
                Text(t("端口 ", "Port ") + state.connectPort, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun CardGroup(title: String, content: @Composable () -> Unit) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}
