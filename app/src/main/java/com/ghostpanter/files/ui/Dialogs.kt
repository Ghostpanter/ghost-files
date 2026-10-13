package com.ghostpanter.files.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        is DialogState.Compress -> CompressDialog(vm, vm.pane(d.pane).tab, d.entries, dismiss)
        is DialogState.Hash -> HashDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        is DialogState.BatchRename -> BatchRenameDialog(vm, vm.pane(d.pane).tab, d.entries, dismiss)
        is DialogState.Properties -> PropertiesDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        is DialogState.Chmod -> ChmodDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        DialogState.Sort -> SortDialog(vm, dismiss)
        is DialogState.Symlink -> SymlinkDialog(vm, vm.pane(d.pane).tab, dismiss)
        is DialogState.Remount -> RemountDialog(vm, dismiss)
        is DialogState.ZipEdit -> ZipEditDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        is DialogState.SplitFile -> SplitFileDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
        is DialogState.ArchiveTest -> ArchiveTestDialog(vm, vm.pane(d.pane).tab, d.entry, dismiss)
    }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, selectAll: Boolean = false, onOk: (String) -> Unit) {
    val selEnd = if (selectAll) initial.length else initial.substringBeforeLast('.').ifEmpty { initial }.length
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, selEnd))) }
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
    var detected by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    val isNew = existing == null || vm.g.servers.get(existing.id) == null

    fun build() = ServerConfig(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { host }, protocol = protocol, host = host.trim(), port = port.toIntOrNull() ?: 0,
        username = user.trim(), password = pass, path = path.trim(), domain = domain.trim(),
        encoding = encoding.ifBlank { "UTF-8" }, passiveMode = passive, privateKey = key, anonymous = anonymous,
    )

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (isNew) t("添加服务器", "Add server") else t("编辑服务器", "Edit server")) },
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
                if (protocol == Protocol.SMB) {
                    TextButton(enabled = host.isNotBlank(), onClick = {
                        testing = t("正在读取共享列表…", "Listing shares…")
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.ghostpanter.files.core.lan.SmbShares.list(
                                        host.trim(), port.toIntOrNull()?.takeIf { it > 0 } ?: 445,
                                        if (anonymous) "" else user.trim(), if (anonymous) "" else pass, domain.trim(),
                                    ).visible.map { it.name }
                                }
                            }
                            r.onSuccess { detected = it; testing = t("发现 ${it.size} 个共享，点按添加", "${it.size} shares found") }
                                .onFailure { testing = t("失败: ", "Failed: ") + (it.message ?: it.javaClass.simpleName) }
                        }
                    }) { Text(t("自动识别共享名", "Detect shares")) }
                    detected?.let { list ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            list.forEach { name ->
                                val cur = path.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                FilterChip(
                                    selected = name in cur,
                                    onClick = { path = (if (name in cur) cur - name else cur + name).joinToString(",") },
                                    label = { Text(name) }, modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }
                    }
                }
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
                if (existing != null && !isNew) TextButton(onClick = {
                    vm.g.servers.remove(existing.id); vm.fsm.invalidate(existing.fsId); dismiss()
                }) { Text(t("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) }
            }
        },
    )
}

@Suppress("unused")
private fun capLabel(c: Capability) = c.name

@Composable
fun SymlinkDialog(vm: MainViewModel, tab: TabState, dismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("创建软链接", "Create symlink")) },
        text = {
            Column {
                Text(t("在当前目录创建链接。本机普通目录可能无权限，失败时请用 Root/Shizuku。", "May need Root/Shizuku."), fontSize = 12.sp)
                OutlinedTextField(link, { link = it }, singleLine = true, label = { Text(t("链接名", "Link name")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(target, { target = it }, singleLine = true, label = { Text(t("目标路径", "Target path")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = link.isNotBlank() && target.isNotBlank(), onClick = { vm.createSymlink(tab, link, target); dismiss() }) { Text(t("创建", "Create")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun RemountDialog(vm: MainViewModel, dismiss: () -> Unit) {
    var mp by remember { mutableStateOf("/system") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("Root 重新挂载为读写", "Root remount rw")) },
        text = {
            Column {
                Text(t("危险操作：可能导致无法开机。改完文件后请尽快改回只读。", "Dangerous — remount ro afterwards."), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                OutlinedTextField(mp, { mp = it }, singleLine = true, label = { Text(t("挂载点", "Mount point")) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in listOf("/system", "/vendor", "/product", "/odm", "/data")) AssistChip({ mp = p }, { Text(p) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.remountRw(mp); dismiss() }) { Text(t("挂载读写", "Remount rw")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun ZipEditDialog(vm: MainViewModel, tab: TabState, entry: FileEntry, dismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry.path, password) {
        entries = null; err = null
        withContext(Dispatchers.IO) {
            runCatching {
                val local = vm.fsm.localFile(tab.fsId, entry.path)
                val file = local ?: run {
                    val tmp = File(vm.getApplication<android.app.Application>().cacheDir, "zipedit/${entry.name}")
                    tmp.parentFile?.mkdirs()
                    vm.fsm.get(tab.fsId).openRead(entry.path).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    tmp
                }
                com.ghostpanter.files.core.ops.ZipEdit.listEntries(file, password.ifBlank { null })
            }.onSuccess { entries = it }.onFailure { err = it.message }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("编辑 ZIP 内文件", "Edit ZIP contents")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(t("密码（如有）", "Password")) }, modifier = Modifier.fillMaxWidth())
                when {
                    err != null -> Text(err!!, color = MaterialTheme.colorScheme.error)
                    entries == null -> CircularProgressIndicator()
                    else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(entries!!) { name ->
                            Row(Modifier.fillMaxWidth().clickable {
                                selected = if (name in selected) selected - name else selected + name
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(name in selected, { checked -> selected = if (checked) selected + name else selected - name })
                                Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text(t("删除选中条目不会整包重压（zip4j）。添加请用对面栏选文件后点下方按钮。", "Delete uses zip4j in-place."), fontSize = 11.sp)
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val cache = vm.getApplication<android.app.Application>().cacheDir
                                com.ghostpanter.files.core.ops.ZipEdit.withLocalZip(vm.fsm.get(tab.fsId), entry.path, cache, password.ifBlank { null }) { f ->
                                    com.ghostpanter.files.core.ops.ZipEdit.removeEntries(f, selected.toList(), password.ifBlank { null })
                                }
                            }.onSuccess { vm.toast(t("已删除 ${selected.size} 项", "Removed")); vm.refresh(tab) }
                                .onFailure { vm.toast(it.message ?: "err") }
                        }
                        dismiss()
                    }
                }) { Text(t("删除选中", "Delete")) }
                TextButton(onClick = {
                    val add = vm.other(vm.panes[1 - vm.activePane]).tab.selectedEntries().filter { !it.isDirectory }
                    if (add.isEmpty()) { vm.toast(t("请先在对面栏选中要加入的文件", "Select files in other pane")); return@TextButton }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val cache = vm.getApplication<android.app.Application>().cacheDir
                                com.ghostpanter.files.core.ops.ZipEdit.withLocalZip(vm.fsm.get(tab.fsId), entry.path, cache, password.ifBlank { null }) { zf ->
                                    for (a in add) {
                                        val local = vm.fsm.localFile(vm.panes[1 - vm.activePane].tab.fsId, a.path) ?: run {
                                            val tmp = File(cache, "zipadd/${a.name}"); tmp.parentFile?.mkdirs()
                                            vm.fsm.get(vm.panes[1 - vm.activePane].tab.fsId).openRead(a.path).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                                            tmp
                                        }
                                        com.ghostpanter.files.core.ops.ZipEdit.addOrUpdate(zf, local, a.name, password.ifBlank { null })
                                    }
                                }
                            }.onSuccess { vm.toast(t("已添加 ${add.size} 项", "Added")); vm.refresh(tab) }
                                .onFailure { vm.toast(it.message ?: "err") }
                        }
                        dismiss()
                    }
                }) { Text(t("从对面添加", "Add from other")) }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("关闭", "Close")) } },
    )
}


@Composable
fun SplitFileDialog(vm: MainViewModel, tab: TabState, entry: FileEntry, dismiss: () -> Unit) {
    var mb by remember { mutableStateOf("100") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("分割文件", "Split file")) },
        text = {
            Column {
                Text(entry.name, fontSize = 12.sp)
                OutlinedTextField(mb, { mb = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text(t("每卷大小（MB）", "Part size (MB)")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = (mb.toIntOrNull() ?: 0) >= 1, onClick = {
                vm.splitFile(tab, entry, mb.toInt()); dismiss()
            }) { Text(t("分割", "Split")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
fun ArchiveTestDialog(vm: MainViewModel, tab: TabState, entry: FileEntry, dismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("测试压缩包", "Test archive")) },
        text = {
            Column {
                Text(entry.name)
                OutlinedTextField(password, { password = it }, singleLine = true,
                    label = { Text(t("密码（如有）", "Password")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.testArchive(tab, entry, password.ifBlank { null }); dismiss() }) { Text(t("开始测试", "Test")) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}
