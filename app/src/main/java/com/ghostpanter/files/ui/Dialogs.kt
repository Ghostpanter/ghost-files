package com.ghostpanter.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.Protocol
import com.ghostpanter.files.core.ServerConfig
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.ops.DirStats
import com.ghostpanter.files.core.ops.FsStats
import com.ghostpanter.files.core.transfer.ConflictPolicy
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun EntryDialogs(vm: MainViewModel, d: DialogState, dismiss: () -> Unit) {
    when (d) {
        is DialogState.Delete -> {
            val tab = vm.pane(d.pane).tab
            val trashOk = vm.g.settings.recycleBin && tab.fsId == LocalFs.ID
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(t("删除", "Delete")) },
                text = {
                    Text(
                        (if (d.entries.size == 1) d.entries[0].name else t("${d.entries.size} 项", "${d.entries.size} items")) +
                            "\n" + if (trashOk) t("将移入回收站", "Will be moved to recycle bin") else t("此操作不可恢复", "This cannot be undone"),
                    )
                },
                confirmButton = { TextButton(onClick = { vm.delete(tab, d.entries, trashOk); dismiss() }) { Text(t("删除", "Delete")) } },
                dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
            )
        }
        is DialogState.Rename -> NameDialog(t("重命名", "Rename"), d.entry.name, dismiss) { vm.rename(vm.pane(d.pane).tab, d.entry, it) }
        is DialogState.Create -> NameDialog(if (d.dir) t("新建文件夹", "New folder") else t("新建文件", "New file"), "", dismiss) {
            vm.create(vm.pane(d.pane).tab, it, d.dir)
        }
        is DialogState.Compress -> NameDialog(
            t("压缩为 ZIP", "Compress to zip"),
            (if (d.entries.size == 1) d.entries[0].name.substringBeforeLast('.').ifEmpty { d.entries[0].name } else "archive") + ".zip",
            dismiss,
        ) { vm.compress(vm.pane(d.pane).tab, d.entries, it) }
        is DialogState.Properties -> PropertiesDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        is DialogState.Chmod -> ChmodDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        DialogState.Sort -> SortDialog(vm, dismiss)
    }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.substringBeforeLast('.').ifEmpty { initial }.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { onOk(text.text.trim()); dismiss() }) { Text(t("确定", "OK")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
private fun PropertiesDialog(vm: MainViewModel, tab: TabState, e0: FileEntry, dismiss: () -> Unit) {
    var e by remember { mutableStateOf(e0) }
    var stats by remember { mutableStateOf<DirStats?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(e0.path) {
        withContext(Dispatchers.IO) {
            val fs = vm.fs(tab)
            runCatching { fs.stat(e0.path) }.getOrNull()?.let { e = it }
            if (e0.isDirectory) {
                val ctx = coroutineContext
                stats = FsStats.measure(fs, e0, isCancelled = { !ctx.isActive }) { stats = it }
            }
            done = true
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("属性", "Properties")) },
        confirmButton = { TextButton(onClick = dismiss) { Text(t("关闭", "Close")) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun row(k: String, v: String?) {
                    if (v.isNullOrEmpty()) return
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, Modifier.width(76.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(v, fontSize = 13.sp) }
                    }
                }
                row(t("名称", "Name"), e.name)
                row(t("位置", "Location"), vm.fsm.label(tab.fsId) + "  " + e.path)
                row(t("类型", "Type"), if (e.isDirectory) t("文件夹", "Folder") else mimeOf(e.name))
                if (e.isDirectory) {
                    val s = stats
                    row(t("大小", "Size"), if (s == null) "…" else "${formatSize(s.bytes)} (${s.bytes} B)" + if (!done) " …" else "")
                    row(t("包含", "Contains"), s?.let { t("${it.files} 个文件, ${it.dirs} 个文件夹", "${it.files} files, ${it.dirs} folders") })
                } else row(t("大小", "Size"), "${formatSize(e.size)} (${e.size} B)")
                row(t("修改时间", "Modified"), formatTime(e.lastModified))
                row(t("权限", "Perms"), e.permissions)
                row(t("所有者", "Owner"), listOfNotNull(e.owner, e.group).joinToString(" : "))
                if (e.isLink) row(t("链接", "Link"), t("符号链接", "Symbolic link"))
                if (!e.isDirectory && e.size in 1..(512L shl 20)) {
                    var hash by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()
                    if (hash == null) TextButton(onClick = {
                        hash = "…"
                        scope.launch {
                            hash = withContext(Dispatchers.IO) {
                                runCatching {
                                    val md = java.security.MessageDigest.getInstance("SHA-256")
                                    vm.fs(tab).openRead(e.path).use { ins ->
                                        val buf = ByteArray(256 * 1024)
                                        while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                                    }
                                    md.digest().joinToString("") { "%02x".format(it) }
                                }.getOrElse { it.message ?: "error" }
                            }
                        }
                    }) { Text(t("计算 SHA-256", "Compute SHA-256")) } else row("SHA-256", hash)
                }
            }
        },
    )
}

@Composable
private fun ChmodDialog(vm: MainViewModel, tab: TabState, e: FileEntry, dismiss: () -> Unit) {
    val initial = e.permissions?.takeIf { it.length >= 10 }?.substring(1, 10) ?: "rw-r--r--"
    val bits = remember { mutableStateListOf(*initial.map { it != '-' }.toTypedArray()) }
    var recursive by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf(listOfNotNull(e.owner, e.group).joinToString(":")) }
    val isShell = vm.fs(tab) is ShellFs
    fun octal(): String = (0..2).joinToString("") { g ->
        ((if (bits[g * 3]) 4 else 0) + (if (bits[g * 3 + 1]) 2 else 0) + (if (bits[g * 3 + 2]) 1 else 0)).toString()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("权限", "Permissions") + "  " + octal()) },
        text = {
            Column {
                Row { Text("", Modifier.width(64.dp)); listOf("r", "w", "x").forEach { Text(it, Modifier.width(48.dp), fontFamily = FontFamily.Monospace) } }
                listOf(t("所有者", "Owner"), t("用户组", "Group"), t("其他", "Other")).forEachIndexed { gi, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(64.dp), fontSize = 13.sp)
                        for (b in 0..2) Checkbox(bits[gi * 3 + b], { bits[gi * 3 + b] = it }, Modifier.width(48.dp))
                    }
                }
                if (e.isDirectory) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(recursive, { recursive = it }); Text(t("应用到子项", "Recursive")) }
                if (isShell) OutlinedTextField(owner, { owner = it }, label = { Text(t("所有者:组 (chown)", "owner:group")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.chmod(tab, e, octal(), recursive)
                val orig = listOfNotNull(e.owner, e.group).joinToString(":")
                if (isShell && owner.isNotBlank() && owner != orig) vm.chown(tab, e, owner.trim(), recursive)
                dismiss()
            }) { Text(t("应用", "Apply")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun ConflictDialog(p: PendingTransfer, onChoose: (ConflictPolicy?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(t("目标已存在 ${p.conflicts.size} 项", "${p.conflicts.size} item(s) already exist")) },
        text = {
            Column {
                Text(p.conflicts.take(5).joinToString("\n") + if (p.conflicts.size > 5) "\n…" else "", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                for ((pol, label) in listOf(
                    ConflictPolicy.RESUME to t("续传 / 跳过相同（推荐）", "Resume / skip identical (recommended)"),
                    ConflictPolicy.OVERWRITE to t("全部覆盖", "Overwrite all"),
                    ConflictPolicy.SKIP to t("全部跳过", "Skip all"),
                    ConflictPolicy.RENAME to t("保留两者（自动重命名）", "Keep both (rename)"),
                )) {
                    OutlinedButton(onClick = { onChoose(pol) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun ExtractDialog(e: FileEntry, onDismiss: () -> Unit, onExtract: (Boolean, String?) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.name) },
        text = {
            Column {
                Text(t("解压到：", "Extract to:"))
                if (e.name.endsWith(".7z", true) || e.name.endsWith(".rar", true)) {
                    OutlinedTextField(pw, { pw = it }, label = { Text(t("密码（可选）", "Password (optional)")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onExtract(true, pw.ifEmpty { null }) }) { Text(t("同名子文件夹", "Sub-folder")) }
                TextButton(onClick = { onExtract(false, pw.ifEmpty { null }) }) { Text(t("当前目录", "Here")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun BusyDialog(text: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = null,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(text, maxLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(t("取消", "Cancel")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditor(vm: MainViewModel, existing: ServerConfig?, dismiss: () -> Unit) {
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SMB) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var path by remember { mutableStateOf(existing?.path ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var encoding by remember { mutableStateOf(existing?.encoding ?: "UTF-8") }
    var passive by remember { mutableStateOf(existing?.passiveMode ?: true) }
    var anonymous by remember { mutableStateOf(existing?.anonymous ?: false) }
    var key by remember { mutableStateOf(existing?.privateKey ?: "") }
    var testing by remember { mutableStateOf<String?>(null) }
    var protoMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun build() = ServerConfig(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { host }, protocol = protocol, host = host.trim(), port = port.toIntOrNull() ?: 0,
        username = user.trim(), password = pass, path = path.trim(), domain = domain.trim(),
        encoding = encoding.ifBlank { "UTF-8" }, passiveMode = passive, privateKey = key, anonymous = anonymous,
    )

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (existing == null) t("添加服务器", "Add server") else t("编辑服务器", "Edit server")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ExposedDropdownMenuBox(expanded = protoMenu, onExpandedChange = { protoMenu = it }) {
                    OutlinedTextField(
                        protocol.label, {}, readOnly = true, label = { Text(t("协议", "Protocol")) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protoMenu) },
                    )
                    ExposedDropdownMenu(expanded = protoMenu, onDismissRequest = { protoMenu = false }) {
                        Protocol.entries.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { protocol = p; protoMenu = false }) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(t("名称（可选）", "Name (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedTextField(host, { host = it }, label = { Text(t("主机 / IP", "Host / IP")) }, singleLine = true, modifier = Modifier.weight(2f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("${t("端口", "Port")} ${protocol.defaultPort}") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                if (protocol != Protocol.SFTP) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { anonymous = !anonymous }) {
                    Checkbox(anonymous, { anonymous = it }); Text(t("匿名 / 访客", "Anonymous / guest"))
                }
                if (!anonymous || protocol == Protocol.SFTP) {
                    OutlinedTextField(user, { user = it }, label = { Text(t("用户名", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text(if (protocol == Protocol.SFTP && key.isNotBlank()) t("私钥口令", "Key passphrase") else t("密码", "Password")) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    path, { path = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(when (protocol) {
                            Protocol.SMB -> t("共享名（多个用逗号分隔）", "Share name(s), comma separated")
                            Protocol.WEBDAV, Protocol.WEBDAVS -> t("路径，例如 /dav", "Base path, e.g. /dav")
                            else -> t("起始目录（可选）", "Start folder (optional)")
                        })
                    },
                )
                when (protocol) {
                    Protocol.SMB -> OutlinedTextField(domain, { domain = it }, label = { Text(t("域（可选）", "Domain (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Protocol.FTP, Protocol.FTPS -> {
                        OutlinedTextField(encoding, { encoding = it }, label = { Text(t("编码 (UTF-8 / GBK)", "Encoding")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(passive, { passive = it }); Text(t("被动模式 (PASV)", "Passive mode")) }
                    }
                    Protocol.SFTP -> OutlinedTextField(key, { key = it }, label = { Text(t("私钥 (可选，粘贴 OpenSSH/PEM)", "Private key (optional)")) },
                        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
                    else -> Unit
                }
                if (protocol == Protocol.SFTP && existing != null) {
                    TextButton(onClick = { vm.g.hostKeys.forget("${host.trim()}:${port.toIntOrNull()?.takeIf { it > 0 } ?: 22}"); vm.toast(t("已清除主机指纹", "Host key forgotten")) }) {
                        Text(t("重置主机指纹", "Forget host key"))
                    }
                }
                testing?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = host.isNotBlank(), onClick = {
                    testing = t("连接中…", "Connecting…")
                    scope.launch {
                        testing = withContext(Dispatchers.IO) {
                            val cfg = build()
                            runCatching {
                                com.ghostpanter.files.core.RemoteFsFactory.create(cfg, vm.g.hostKeys).use { fs ->
                                    val n = fs.list(fs.homePath).size
                                    t("连接成功，${fs.homePath} 下 $n 项", "OK, $n items in ${fs.homePath}")
                                }
                            }.getOrElse { t("失败: ", "Failed: ") + (it.message ?: it.javaClass.simpleName) }
                        }
                    }
                }) { Text(t("测试", "Test")) }
                TextButton(enabled = host.isNotBlank(), onClick = {
                    val cfg = build()
                    vm.g.servers.upsert(cfg)
                    vm.fsm.invalidate(cfg.fsId)
                    dismiss()
                }) { Text(t("保存", "Save")) }
            }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = {
                    vm.g.servers.remove(existing.id); vm.fsm.invalidate(existing.fsId); dismiss()
                }) { Text(t("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) }
            }
        },
    )
}

@Suppress("unused")
private fun capLabel(c: Capability) = c.name
