package com.ghostpanter.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.Protocol
import com.ghostpanter.files.core.ServerConfig
import com.ghostpanter.files.core.lan.LanHost
import com.ghostpanter.files.core.lan.LanService
import com.ghostpanter.files.core.lan.LanServiceKind
import com.ghostpanter.files.core.lan.LanSource
import com.ghostpanter.files.core.lan.SmbAuthRequiredException
import com.ghostpanter.files.core.lan.SmbShare
import com.ghostpanter.files.core.lan.SmbShares
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** "扫描局域网": live, cancellable discovery of SMB/FTP/SFTP/WebDAV servers grouped by host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScanScreen(vm: MainViewModel, onEditServer: (ServerConfig) -> Unit) {
    val st by vm.lan.state.collectAsState()
    var shareTarget by remember { mutableStateOf<Pair<LanHost, LanService>?>(null) }
    LaunchedEffect(Unit) { if (!st.running && st.hosts.isEmpty()) vm.lan.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(t("扫描局域网", "Scan LAN"), fontSize = 17.sp)
                        Text(
                            listOfNotNull(st.network, st.phase.takeIf { it.isNotEmpty() }, t("${st.hosts.size} 台设备", "${st.hosts.size} hosts")).joinToString(" · "),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (st.running) TextButton(onClick = { vm.lan.stop() }) { Icon(Icons.Filled.Stop, null); Text(t("停止", "Stop")) }
                    else TextButton(onClick = { vm.lan.start() }) { Icon(Icons.Filled.Refresh, null); Text(t("重新扫描", "Rescan")) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.running) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            Text(
                t("通过 mDNS、NetBIOS 和端口探测（445/139/21/22/80/443/5005/5006/8080）查找共享。点按服务即可自动识别共享名并添加。",
                    "Uses mDNS, NetBIOS and a port sweep. Tap a service to detect shares and add it."),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(st.hosts, key = { it.ip }) { h -> HostCard(vm, h, onSmb = { svc -> shareTarget = h to svc }, onOther = { svc -> onEditServer(prefill(h, svc)) }) }
                if (!st.running && st.hosts.isEmpty() && st.error == null) item {
                    Text(t("没有发现设备。确认手机与电脑/NAS 在同一 Wi-Fi，且对方已开启共享。", "Nothing found."),
                        Modifier.padding(24.dp), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    shareTarget?.let { (h, svc) -> SmbShareDialog(vm, h, svc) { shareTarget = null } }
}

private fun prefill(h: LanHost, s: LanService): ServerConfig {
    val proto = s.kind.protocol ?: Protocol.WEBDAV
    return ServerConfig(
        id = UUID.randomUUID().toString(), name = h.hostname ?: h.ip, protocol = proto, host = h.ip,
        port = if (s.port == proto.defaultPort) 0 else s.port,
        path = s.path ?: "",
    )
}

@Composable
private fun HostCard(vm: MainViewModel, h: LanHost, onSmb: (LanService) -> Unit, onOther: (LanService) -> Unit) {
    val saved = vm.g.servers.servers.filter { it.host == h.ip }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(h.ip, h.workgroup).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
                if (saved.isNotEmpty()) Text(t("已添加", "Saved"), fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }
            if (h.services.isEmpty()) Text(t("仅 NetBIOS 名称，未发现文件服务", "Name only"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            h.services.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (s.kind == LanServiceKind.SMB) onSmb(s) else onOther(s) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.kind.protocol?.let { protocolIcon(it) } ?: Icons.Filled.Cloud, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${s.kind.label} :${s.port}", fontSize = 13.sp)
                        val hint = when {
                            s.kind == LanServiceKind.HTTP || s.kind == LanServiceKind.HTTPS -> t("HTTP 服务，可能提供 WebDAV（需填写路径）", "HTTP – maybe WebDAV")
                            else -> listOfNotNull(s.detail, s.path, when (s.source) {
                                LanSource.MDNS -> "mDNS"; LanSource.NETBIOS -> "NetBIOS"; LanSource.PORT -> t("端口探测", "port"); LanSource.WSD -> "WS-Discovery"
                            }).joinToString(" · ")
                        }
                        Text(hint, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (s.kind == LanServiceKind.SMB) t("识别共享", "Shares") else t("添加", "Add"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * Enumerates SMB shares (guest/anonymous first; asks for credentials when refused), lets the user
 * pick, then saves a server entry with those shares and opens it in the active pane.
 */
@Composable
fun SmbShareDialog(vm: MainViewModel, h: LanHost, svc: LanService, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var needAuth by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shares by remember { mutableStateOf<List<SmbShare>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loginLabel by remember { mutableStateOf("") }
    val port = if (svc.port == 139) 445 else svc.port

    fun load() {
        loading = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SmbShares.list(h.ip, port, user.trim(), pass, domain.trim()) } }
            loading = false
            r.onSuccess { l ->
                shares = l.visible; picked = l.visible.map { it.name }.toSet(); needAuth = false
                loginLabel = when (l.login) {
                    com.ghostpanter.files.core.SmbLogin.GUEST -> t("来宾", "guest")
                    com.ghostpanter.files.core.SmbLogin.ANONYMOUS -> t("匿名", "anonymous")
                    com.ghostpanter.files.core.SmbLogin.USER -> user.trim()
                }
            }.onFailure { e ->
                if (e is SmbAuthRequiredException) { needAuth = true; error = e.message } else error = e.message ?: e.javaClass.simpleName
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Column {
                Text(t("SMB 共享 · ", "SMB shares · ") + h.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${h.ip}:$port", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(t("正在读取共享列表…", "Listing shares…"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                if (needAuth || user.isNotEmpty()) {
                    OutlinedTextField(user, { user = it }, label = { Text(t("用户名", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(t("密码", "Password")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(domain, { domain = it }, label = { Text(t("域（可选）", "Domain (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { load() }, enabled = !loading && user.isNotBlank()) { Text(t("登录并列出共享", "Sign in & list")) }
                }
                shares?.let { list ->
                    Text(t("以「$loginLabel」身份发现 ${list.size} 个共享，选择要添加的：", "${list.size} shares ($loginLabel):"), fontSize = 12.sp)
                    if (list.isEmpty()) Text(t("没有可浏览的共享（已隐藏 $ 结尾的管理共享）", "No browsable shares"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    list.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { picked = if (s.name in picked) picked - s.name else picked + s.name }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(s.name in picked, { picked = if (it) picked + s.name else picked - s.name })
                            Column {
                                Text(s.name, fontSize = 14.sp)
                                if (s.remark.isNotBlank()) Text(s.remark, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    if (!needAuth && user.isEmpty()) TextButton(onClick = { needAuth = true }) { Text(t("使用账号登录（查看更多共享）", "Sign in with an account")) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !shares.isNullOrEmpty() && picked.isNotEmpty(), onClick = {
                val existing = vm.g.servers.servers.firstOrNull { it.protocol == Protocol.SMB && it.host == h.ip && it.username == user.trim() }
                val cfg = ServerConfig(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = h.hostname ?: h.ip, protocol = Protocol.SMB, host = h.ip, port = if (port == 445) 0 else port,
                    username = user.trim(), password = pass, domain = domain.trim(),
                    path = shares!!.map { it.name }.filter { it in picked }.joinToString(","),
                    anonymous = user.isBlank(),
                )
                vm.g.servers.upsert(cfg)
                vm.fsm.invalidate(cfg.fsId)
                dismiss()
                vm.openFs(cfg.fsId)
                vm.toast(t("已添加 ${cfg.name}", "Added ${cfg.name}"))
            }) { Text(t("添加并打开", "Add & open")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}
