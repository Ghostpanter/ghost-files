package com.ghostpanter.files.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.server.HostedKind
import com.ghostpanter.files.core.server.HostedRuntimeStatus
import com.ghostpanter.files.core.server.HostedServiceConfig
import com.ghostpanter.files.core.server.randomPassword
import com.ghostpanter.files.server.HostedServerService
import com.ghostpanter.files.server.LanAddresses
import com.ghostpanter.files.t
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val store = vm.g.hosted
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); tick++ }
    }
    val ip = remember(tick) { LanAddresses.primaryIpv4(ctx) }
    val statuses = remember(tick) {
        HostedKind.entries.associateWith { HostedServerService.Runtime.hub(ctx).status(it) }
    }
    var editing by remember { mutableStateOf<HostedKind?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(t("本机服务", "Hosted services"))
                        Text(
                            t("当前 IP：${ip ?: "未连接 Wi-Fi"}", "IP: ${ip ?: "no Wi-Fi"}"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Main }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { HostedServerService.refresh(ctx); tick++ }) {
                        Icon(Icons.Filled.Refresh, t("刷新", "Refresh"))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                t(
                    "开启后，局域网内其他设备可通过下列地址连接本机并读写共享目录。FTP 明文不安全，请优先用 SFTP / WebDAV(HTTPS) / FTPS。本机 SMB 为 SMB2（2.1）、默认端口 4455（免 Root，无需开 SMB1）；连 NAS/电脑请用侧栏「添加服务器」。",
                    "When enabled, other LAN devices can read/write this phone. Prefer SFTP / WebDAV(HTTPS) / FTPS. Hosted SMB is SMB2 (2.1) on port 4455 (no Root, no SMB1). To open a NAS/PC share, use drawer → Add server.",
                ),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            )
            // Present FTP(+FTPS toggle in editor), WebDAV, SFTP, SMB
            listOf(HostedKind.FTP, HostedKind.WEBDAV, HostedKind.SFTP, HostedKind.HTTP, HostedKind.SMB).forEach { kind ->
                val cfg = store.get(kind)
                val st = statuses[kind] ?: HostedRuntimeStatus(kind, false)
                // FTPS / WEBDAVS reflected via useTls on the base kind
                val effectiveKind = when {
                    kind == HostedKind.FTP && (cfg.useTls || st.kind == HostedKind.FTPS) -> HostedKind.FTPS
                    kind == HostedKind.WEBDAV && (cfg.useTls || st.kind == HostedKind.WEBDAVS) -> HostedKind.WEBDAVS
                    else -> kind
                }
                val running = when (kind) {
                    HostedKind.FTP -> statuses[HostedKind.FTP]?.running == true || statuses[HostedKind.FTPS]?.running == true
                    HostedKind.WEBDAV -> statuses[HostedKind.WEBDAV]?.running == true || statuses[HostedKind.WEBDAVS]?.running == true
                    else -> st.running
                }
                ServiceCard(
                    kind = effectiveKind,
                    cfg = cfg,
                    running = running,
                    status = st,
                    ip = ip,
                    onToggle = { on ->
                        if (on && !cfg.anonymous && cfg.password.isBlank()) {
                            store.upsert(cfg.copy(password = randomPassword()))
                        }
                        HostedServerService.set(ctx, kind, on)
                        tick++
                    },
                    onEdit = { editing = kind },
                    onCopy = { url ->
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", url))
                        Toast.makeText(ctx, t("已复制地址", "Address copied"), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    editing?.let { kind ->
        ServiceEditorDialog(
            kind = kind,
            initial = store.get(kind),
            defaultRoot = store.defaultRoot(),
            onDismiss = { editing = null },
            onSave = { c ->
                store.upsert(c)
                HostedServerService.Runtime.syncFromStore(ctx)
                // Restart if was running
                val was = statuses[kind]?.running == true ||
                    (kind == HostedKind.FTP && statuses[HostedKind.FTPS]?.running == true) ||
                    (kind == HostedKind.WEBDAV && statuses[HostedKind.WEBDAVS]?.running == true)
                if (was) {
                    HostedServerService.set(ctx, kind, true)
                }
                editing = null
                tick++
            },
        )
    }
}

@Composable
private fun ServiceCard(
    kind: HostedKind,
    cfg: HostedServiceConfig,
    running: Boolean,
    status: HostedRuntimeStatus,
    ip: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val url = ip?.let {
        cfg.copy(kind = kind).connectionUrl(it, includeAuth = false)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (kind) {
                        HostedKind.FTP, HostedKind.FTPS -> Icons.Filled.Dns
                        HostedKind.WEBDAV, HostedKind.WEBDAVS -> Icons.Filled.Cloud
                        HostedKind.SFTP -> Icons.Filled.Terminal
                        HostedKind.HTTP -> Icons.Filled.Language
                        HostedKind.SMB -> Icons.Filled.Lan
                    },
                    null, tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            running -> t("监听中 · ${ip ?: "?"}:${cfg.effectivePort}", "Listening · ${ip ?: "?"}:${cfg.effectivePort}")
                            status.error != null -> status.error!!
                            kind == HostedKind.SMB -> t("已停止 · SMB2 端口 ${cfg.effectivePort}", "Stopped · SMB2 port ${cfg.effectivePort}")
                            else -> t("已停止", "Stopped")
                        },
                        fontSize = 12.sp,
                        color = when {
                            running -> MaterialTheme.colorScheme.primary
                            status.error != null -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
                Switch(checked = running, onCheckedChange = onToggle)
            }
            Text(
                t("共享目录：${cfg.rootPath.ifBlank { "（默认下载目录）" }}", "Share root: ${cfg.rootPath.ifBlank { "(Downloads)" }}"),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2,
            )
            if (url != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(url, Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                    IconButton(onClick = { onCopy(url) }) { Icon(Icons.Filled.ContentCopy, t("复制", "Copy")) }
                }
                if (kind == HostedKind.HTTP && running) {
                    Text(t("用浏览器扫码打开远程管理页", "Scan to open remote page in browser"), fontSize = 11.sp)
                    QrImage(url, modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally))
                }
            }
            if (running && status.clientCount > 0) {
                Text(t("当前客户端：${status.clientCount}", "Clients: ${status.clientCount}"), fontSize = 12.sp)
            }
            if (!cfg.anonymous) {
                Text(
                    t("账号 ${cfg.username} · 密码已保存", "User ${cfg.username} · password saved"),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
            if (kind == HostedKind.FTP && !cfg.useTls) {
                Text(t("⚠ 明文 FTP：密码与文件均不加密", "⚠ Cleartext FTP: password and data are not encrypted"),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            if (kind == HostedKind.SMB) {
                Text(
                    t(
                        "连接：smbclient //${ip ?: "IP"}/share -p ${cfg.effectivePort} -U ${cfg.username}（SMB2）。Win11 24H2+：New-SmbMapping -RemotePath \\\\${ip ?: "IP"}\\share -TcpPort ${cfg.effectivePort} -UserName ${cfg.username}。免 Root；资源管理器默认只认 445。",
                        "Connect: smbclient //${ip ?: "IP"}/share -p ${cfg.effectivePort} -U ${cfg.username} (SMB2). Win11 24H2+: New-SmbMapping -TcpPort ${cfg.effectivePort}. No Root; Explorer still expects 445.",
                    ),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                )
            }
            Row {
                TextButton(onClick = onEdit) { Text(t("设置", "Settings")) }
            }
        }
    }
}

@Composable
private fun ServiceEditorDialog(
    kind: HostedKind,
    initial: HostedServiceConfig,
    defaultRoot: String,
    onDismiss: () -> Unit,
    onSave: (HostedServiceConfig) -> Unit,
) {
    var port by remember { mutableStateOf(initial.effectivePort.toString()) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    var anon by remember { mutableStateOf(initial.anonymous) }
    var root by remember { mutableStateOf(initial.rootPath.ifBlank { defaultRoot }) }
    var tls by remember { mutableStateOf(initial.useTls || kind == HostedKind.FTPS || kind == HostedKind.WEBDAVS) }
    var lanOnly by remember { mutableStateOf(initial.lanOnly) }
    var stopWifi by remember { mutableStateOf(initial.stopOnWifiLost) }
    var pasvStart by remember { mutableStateOf(initial.pasvPortStart.toString()) }
    var pasvEnd by remember { mutableStateOf(initial.pasvPortEnd.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("${kind.label} 设置", "${kind.label} settings")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(port, { port = it.filter { ch -> ch.isDigit() }.take(5) }, label = { Text(t("端口", "Port")) }, singleLine = true)
                OutlinedTextField(root, { root = it }, label = { Text(t("共享目录", "Share root")) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(anon, { anon = it }); Text(t("匿名访问（只建议在受信任局域网）", "Anonymous (trusted LAN only)"))
                }
                if (!anon) {
                    OutlinedTextField(user, { user = it }, label = { Text(t("用户名", "Username")) }, singleLine = true)
                    OutlinedTextField(pass, { pass = it }, label = { Text(t("密码", "Password")) }, singleLine = true)
                    TextButton(onClick = { pass = randomPassword() }) { Text(t("生成随机密码", "Generate password")) }
                }
                if (kind == HostedKind.FTP || kind == HostedKind.FTPS) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tls, { tls = it }); Text(t("启用 FTPS（TLS）", "Enable FTPS (TLS)"))
                    }
                    OutlinedTextField(pasvStart, { pasvStart = it.filter(Char::isDigit).take(5) }, label = { Text("PASV 起始端口") }, singleLine = true)
                    OutlinedTextField(pasvEnd, { pasvEnd = it.filter(Char::isDigit).take(5) }, label = { Text("PASV 结束端口") }, singleLine = true)
                }
                if (kind == HostedKind.WEBDAV || kind == HostedKind.WEBDAVS) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tls, { tls = it }); Text(t("启用 HTTPS（WebDAVs）", "Enable HTTPS (WebDAVs)"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(lanOnly, { lanOnly = it }); Text(t("仅允许局域网客户端", "LAN clients only"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(stopWifi, { stopWifi = it }); Text(t("Wi-Fi 断开时自动停止", "Stop when Wi-Fi lost"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        kind = kind,
                        port = port.toIntOrNull() ?: kind.defaultPort,
                        username = user.ifBlank { "ghost" },
                        password = if (anon) "" else pass.ifBlank { randomPassword() },
                        anonymous = anon,
                        rootPath = root.ifBlank { defaultRoot },
                        useTls = tls,
                        lanOnly = lanOnly,
                        stopOnWifiLost = stopWifi,
                        pasvPortStart = pasvStart.toIntOrNull() ?: 2122,
                        pasvPortEnd = pasvEnd.toIntOrNull() ?: 2130,
                    )
                )
            }) { Text(t("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消", "Cancel")) } },
    )
}
