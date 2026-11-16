package com.ghostpanter.files.ui.adb

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.adb.DeviceInfo
import com.ghostpanter.adb.fastboot.FastbootClient
import com.ghostpanter.adb.usb.UsbFastboot
import com.ghostpanter.files.t
import com.ghostpanter.files.ui.GhostTextField
import com.ghostpanter.files.ui.MainViewModel
import com.ghostpanter.files.ui.Screen
import com.ghostpanter.files.ui.ghostTopBarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FastbootScreen(vm: MainViewModel, s: Screen.Fastboot) {
    val ctx = LocalContext.current
    FastbootContent(
        serial = s.serial,
        onBack = { vm.screen = Screen.Devices },
        toast = { vm.toast(it) },
        open = { key -> UsbFastboot.open(ctx, key) },
        cacheDir = ctx.cacheDir,
        resolver = { uri, tmp ->
            ctx.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                ?: throw java.io.IOException(t("无法读取所选文件", "Cannot read the selected file"))
        },
        displayName = { uri ->
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FastbootContent(
    serial: String,
    onBack: () -> Unit,
    toast: (String) -> Unit = {},
    open: ((String) -> FastbootClient)? = null,
    cacheDir: File? = null,
    resolver: ((Uri, File) -> Unit)? = null,
    displayName: (Uri) -> String? = { null },
    preview: FastbootClient.Info? = null,
) {
    val key = UsbFastboot.keyOfSerial(serial)
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(preview) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showVars by remember { mutableStateOf(false) }
    var partition by remember { mutableStateOf("boot") }
    var customPart by remember { mutableStateOf("") }
    var partMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var flashProg by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var flashName by remember { mutableStateOf<String?>(null) }

    suspend fun <T> runFb(block: (FastbootClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        val opener = open ?: return@withContext Result.failure(IllegalStateException("no fastboot transport"))
        runCatching { opener(key).use { block(it) } }
    }

    if (preview == null) LaunchedEffect(serial, reload) {
        error = null
        busy = t("正在读取设备信息…", "Reading device info…")
        runFb { it.info() }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: it.toString() }
        busy = null
    }

    fun go(label: String, block: (FastbootClient) -> Unit, after: (Result<Unit>) -> Unit = {}) {
        if (busy != null) return
        scope.launch {
            busy = label
            val r = runFb(block)
            busy = null
            r.onFailure { toast(it.message ?: it.toString()) }
            after(r)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val part = customPart.trim().ifBlank { partition }
        val name = displayName(uri) ?: "image.img"
        confirm = t(
            "即将把「$name」刷入分区「$part」。刷错分区或镜像与机型不符可能导致无法开机，操作不可撤销。请确认镜像匹配、已备份，且设备已解锁。",
            "Will flash “$name” to partition “$part”. A wrong image or partition can brick the device. This cannot be undone. Confirm the image matches, you have a backup, and the device is unlocked.",
        ) to {
            scope.launch {
                busy = t("正在刷写 $part…", "Flashing $part…")
                flashProg = 0L to 1L
                flashName = name
                val r = runFb { client ->
                    val dir = cacheDir ?: throw java.io.IOException("no cache")
                    val tmp = File(dir, "fastboot-${stamp()}-${safeName(name)}").also { it.parentFile?.mkdirs() }
                    try {
                        (resolver ?: throw java.io.IOException("no resolver"))(uri, tmp)
                        client.flash(part, tmp, tmpDir = dir) { done, total ->
                            runBlocking(Dispatchers.Main.immediate) { flashProg = done to total }
                        }
                    } finally {
                        tmp.delete()
                    }
                }
                busy = null; flashProg = null; flashName = null
                r.onSuccess { toast(t("刷写完成：", "Flash done: ") + part) }
                    .onFailure { toast(t("刷写失败：", "Flash failed: ") + (it.message ?: it.toString())) }
            }
        }
    }

    val cs = MaterialTheme.colorScheme
    val locked = info?.unlocked != true
    Scaffold(
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = { Text(info?.displayName() ?: t("有线 Fastboot", "Wired Fastboot")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { reload++ }, enabled = busy == null) {
                        Icon(Icons.Filled.Refresh, t("刷新", "Refresh"))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(cs.surfaceVariant, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Usb, null, Modifier.size(24.dp), tint = cs.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(info?.displayName() ?: t("Fastboot 设备", "Fastboot device"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(info?.serial ?: UsbFastboot.keyOfSerial(serial), fontSize = 12.sp, color = cs.outline)
                        }
                        val badge = when {
                            info == null && error != null -> t("未连接", "Offline")
                            info == null -> t("读取中", "Reading")
                            locked -> t("已锁定", "Locked")
                            else -> t("已解锁", "Unlocked")
                        }
                        val on = info != null && !locked
                        Text(
                            badge, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (on) cs.primary else if (info != null && locked) cs.error else cs.outline,
                            modifier = Modifier.background(
                                if (on) cs.primaryContainer else if (info != null && locked) cs.errorContainer else cs.surfaceVariant,
                                CircleShape,
                            ).padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                    val bits = buildList {
                        info?.mode?.let { add(if (it == "fastbootd") "fastbootd" else t("Bootloader", "Bootloader")) }
                        info?.currentSlot?.let { add(t("槽位 ", "Slot ") + it.uppercase()) }
                        info?.hardware?.let { add(it) }
                        info?.bootloader?.let { add("BL " + it) }
                    }
                    if (bits.isNotEmpty()) Text(bits.joinToString(" · "), fontSize = 13.sp, color = cs.onSurfaceVariant)
                    if (locked && info != null) {
                        Text(
                            t("设备未解锁，已禁止刷写、擦除、格式化和切换槽位。本应用不会执行解锁或绕过操作。",
                                "Device is locked: flash, erase, format and slot switch are blocked. This app will not unlock or bypass the bootloader."),
                            fontSize = 12.sp, color = cs.error,
                        )
                    }
                    error?.let { Text(it, fontSize = 12.sp, color = cs.error) }
                    busy?.let { Text(it, fontSize = 12.sp, color = cs.primary) }
                    flashProg?.let { (done, total) ->
                        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                        LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                        Text(
                            t("刷写 ", "Flashing ") + (flashName ?: "") + " · ~$pct% · " + DeviceInfo.human(done) + " / " + DeviceInfo.human(total),
                            fontSize = 12.sp, color = cs.outline,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("重启", "Reboot"), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun reboot(target: FastbootClient.RebootTarget, label: String) {
                            go(t("正在发送重启命令…", "Sending reboot…"), { it.reboot(target) }) {
                                it.onSuccess { toast(t("已发送重启命令，设备即将断开。", "Reboot sent; the device will disconnect.")) }
                            }
                        }
                        SoftChip(t("系统", "System"), Icons.Rounded.Usb) { reboot(FastbootClient.RebootTarget.SYSTEM, "system") }
                        SoftChip(t("Bootloader", "Bootloader"), Icons.Rounded.Usb) { reboot(FastbootClient.RebootTarget.BOOTLOADER, "bootloader") }
                        SoftChip(t("Recovery", "Recovery"), Icons.Rounded.Usb) { reboot(FastbootClient.RebootTarget.RECOVERY, "recovery") }
                        SoftChip(t("Fastbootd", "Fastbootd"), Icons.Rounded.Usb) { reboot(FastbootClient.RebootTarget.FASTBOOTD, "fastbootd") }
                        SoftChip(t("继续启动", "Continue"), Icons.Rounded.Usb) { reboot(FastbootClient.RebootTarget.CONTINUE, "continue") }
                    }
                    Text(t("重启后本页会断开，请待设备重新出现后再操作。", "This page disconnects after reboot; wait for the device to reappear."), fontSize = 12.sp, color = cs.outline)
                }
            }

            if ((info?.slotCount ?: 0) > 1 || info?.currentSlot != null) {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t("活动槽位", "Active slot"), fontWeight = FontWeight.SemiBold)
                        Text(
                            t("当前：", "Current: ") + (info?.currentSlot?.uppercase() ?: t("未知", "unknown")),
                            fontSize = 13.sp, color = cs.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("a", "b").forEach { slot ->
                                SoftChip(t("切换到 ", "Set active ") + slot.uppercase(), Icons.Rounded.Usb, accent = slot == info?.currentSlot) {
                                    if (locked) toast(t("设备未解锁，无法切换槽位。", "Device is locked; cannot switch slot."))
                                    else confirm = t(
                                        "将活动槽位切换为 ${slot.uppercase()}？下次启动将使用该槽位。",
                                        "Switch the active slot to ${slot.uppercase()}? The next boot will use that slot.",
                                    ) to {
                                        go(t("正在切换槽位…", "Switching slot…"), { it.setActive(slot) }) {
                                            it.onSuccess { toast(t("已切换到槽位 ", "Active slot set to ") + slot.uppercase()); reload++ }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("刷写 / 擦除", "Flash / erase"), fontWeight = FontWeight.SemiBold)
                    Text(t("选择分区，再选本地镜像刷入；或擦除 / 格式化该分区。均为破坏性操作。",
                        "Pick a partition, then a local image to flash, or erase / format it. These are destructive."),
                        fontSize = 12.sp, color = cs.outline)
                    Box {
                        SoftChip(t("分区：", "Partition: ") + (customPart.trim().ifBlank { partition }), Icons.Rounded.Usb) { partMenu = true }
                        DropdownMenu(partMenu, { partMenu = false }) {
                            FastbootClient.COMMON_PARTITIONS.forEach { p ->
                                DropdownMenuItem({ Text(p) }, { partMenu = false; partition = p; customPart = "" })
                            }
                        }
                    }
                    GhostTextField(
                        value = customPart,
                        onValueChange = { customPart = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("自定义分区名（可空）", "Custom partition (optional)")) },
                        singleLine = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftChip(t("选择镜像并刷写", "Pick image and flash"), Icons.Rounded.Usb, accent = true) {
                            if (locked) toast(t("设备未解锁，无法刷写。", "Device is locked; cannot flash."))
                            else picker.launch(arrayOf("application/octet-stream", "*/*"))
                        }
                        SoftChip(t("擦除分区", "Erase"), Icons.Rounded.Usb) {
                            val part = customPart.trim().ifBlank { partition }
                            if (locked) toast(t("设备未解锁，无法擦除。", "Device is locked; cannot erase."))
                            else confirm = t(
                                "即将擦除分区「$part」。数据会丢失，且可能导致无法开机。确定继续？",
                                "This will erase partition “$part”. Data will be lost and the device may not boot. Continue?",
                            ) to {
                                go(t("正在擦除 $part…", "Erasing $part…"), { it.erase(part) }) {
                                    it.onSuccess { toast(t("已擦除：", "Erased: ") + part) }
                                }
                            }
                        }
                        SoftChip(t("格式化分区", "Format"), Icons.Rounded.Usb) {
                            val part = customPart.trim().ifBlank { partition }
                            if (locked) toast(t("设备未解锁，无法格式化。", "Device is locked; cannot format."))
                            else confirm = t(
                                "即将格式化分区「$part」。数据会丢失。确定继续？",
                                "This will format partition “$part”. Data will be lost. Continue?",
                            ) to {
                                go(t("正在格式化 $part…", "Formatting $part…"), { it.format(part) }) {
                                    it.onSuccess { toast(t("已格式化：", "Formatted: ") + part) }
                                }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("设备变量", "Device variables"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showVars = !showVars }) {
                            Text(if (showVars) t("收起", "Hide") else t("getvar all", "getvar all"))
                        }
                    }
                    if (showVars) {
                        val vars = info?.vars.orEmpty()
                        if (vars.isEmpty()) Text(t("暂无变量，请点右上角刷新。", "No variables yet. Tap refresh."), fontSize = 12.sp, color = cs.outline)
                        else SelectionContainer {
                            Text(
                                vars.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (msg, act) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(t("确认操作", "Confirm")) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { confirm = null; act() }) {
                    Text(t("确认", "Confirm"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("取消", "Cancel")) } },
        )
    }
}
