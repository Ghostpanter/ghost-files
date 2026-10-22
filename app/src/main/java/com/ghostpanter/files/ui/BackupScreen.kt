package com.ghostpanter.files.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.backup.BackupJobs
import com.ghostpanter.files.backup.BackupOutcome
import com.ghostpanter.files.backup.BackupProgress
import com.ghostpanter.files.backup.BackupProviders
import com.ghostpanter.files.backup.RestoreInventory
import com.ghostpanter.files.backup.RestoreSource
import com.ghostpanter.files.backup.SmsRoleMemory
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.backup.BackupKind
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ====================================================================== plain UI model

enum class BackupTab { BACKUP, RESTORE }

/** One selectable item; [count] null = not readable yet (needs permission / Root). */
data class BackupItemUi(val kind: BackupKind, val count: Int?, val checked: Boolean, val enabled: Boolean = true, val missing: String? = null)

data class RecentBackup(val title: String, val sub: String)

class BackupUi(
    val tab: BackupTab,
    val items: List<BackupItemUi>,
    val dest: String,
    val zip: Boolean,
    val restoreLabel: String? = null,
    val restoreSub: String? = null,
    val restoreItems: List<BackupItemUi> = emptyList(),
    val restoreLoading: Boolean = false,
    val restoreError: String? = null,
    val recent: List<RecentBackup> = emptyList(),
    val progress: BackupProgress? = null,
    val outcome: BackupOutcome? = null,
    /** This app currently holds the SMS role (show 「切回默认短信应用」). */
    val holdsSms: Boolean = false,
)

class BackupCallbacks(
    val back: () -> Unit = {},
    val tab: (BackupTab) -> Unit = {},
    val toggle: (BackupKind) -> Unit = {},
    val grant: (BackupKind) -> Unit = {},
    val zip: (Boolean) -> Unit = {},
    val dest: () -> Unit = {},
    val start: () -> Unit = {},
    val cancel: () -> Unit = {},
    val toggleRestore: (BackupKind) -> Unit = {},
    val pickRecent: (Int) -> Unit = {},
    val startRestore: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val openResult: () -> Unit = {},
    val giveBackSms: () -> Unit = {},
)

fun kindLabel(k: BackupKind) = when (k) {
    BackupKind.SMS -> t("短信和彩信", "SMS & MMS")
    BackupKind.CALLS -> t("通话记录", "Call log")
    BackupKind.CONTACTS -> t("联系人", "Contacts")
    BackupKind.CALENDAR -> t("日历", "Calendar")
    BackupKind.WIFI -> t("WiFi 配置和密码", "WiFi networks & passwords")
}

private fun kindFormat(k: BackupKind) = when (k) {
    BackupKind.SMS -> t("含彩信附件 · SMS Backup & Restore 格式", "with MMS attachments · SMS Backup & Restore XML")
    BackupKind.CALLS -> t("SMS Backup & Restore 格式", "SMS Backup & Restore XML")
    BackupKind.CONTACTS -> "vCard (.vcf)"
    BackupKind.CALENDAR -> "iCalendar (.ics)"
    BackupKind.WIFI -> t("Root · WifiConfigStore.xml", "Root · WifiConfigStore.xml")
}

private fun kindIcon(k: BackupKind): ImageVector = when (k) {
    BackupKind.SMS -> Icons.Rounded.Sms
    BackupKind.CALLS -> Icons.Rounded.Call
    BackupKind.CONTACTS -> Icons.Rounded.Contacts
    BackupKind.CALENDAR -> Icons.Rounded.CalendarMonth
    BackupKind.WIFI -> Icons.Rounded.Wifi
}

@Composable
private fun kindColor(k: BackupKind): Color = when (k) {
    BackupKind.SMS -> Ghost.colors.audio
    BackupKind.CALLS -> Ghost.colors.image
    BackupKind.CONTACTS -> Ghost.colors.folder
    BackupKind.CALENDAR -> Ghost.colors.archive
    BackupKind.WIFI -> Ghost.colors.apk
}

private fun countText(k: BackupKind, n: Int) = when (k) {
    BackupKind.SMS -> t("%,d 条".format(n), "%,d".format(n))
    BackupKind.CALLS -> t("%,d 条".format(n), "%,d".format(n))
    BackupKind.CONTACTS -> t("%,d 位".format(n), "%,d".format(n))
    BackupKind.CALENDAR -> t("%,d 个".format(n), "%,d".format(n))
    BackupKind.WIFI -> t("%,d 个网络".format(n), "%,d networks".format(n))
}

// ====================================================================== stateless content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupContent(ui: BackupUi, cb: BackupCallbacks, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = ghostTopBarColors(),
                title = { Text(t("备份与恢复", "Backup & restore"), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = cb.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth >= 720.dp
            Column(Modifier.fillMaxSize().padding(horizontal = if (tablet) 24.dp else 14.dp)) {
                BackupTabs(ui.tab, cb, Modifier.then(if (tablet) Modifier.widthIn(max = 520.dp) else Modifier.fillMaxWidth()))
                Spacer(Modifier.height(10.dp))
                if (tablet) Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState())) { MainColumn(ui, cb) }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState())) { SideColumn(ui, cb); Spacer(Modifier.height(16.dp)) }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Phones: what is happening now goes first, above the lists.
                    StatusCards(ui, cb)
                    MainColumn(ui, cb)
                    Spacer(Modifier.height(10.dp))
                    SideColumn(ui, cb, status = false)
                    Spacer(Modifier.navigationBarsPadding().height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BackupTabs(tab: BackupTab, cb: BackupCallbacks, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.clip(CircleShape).background(cs.surface).padding(4.dp)) {
        listOf(BackupTab.BACKUP to t("备份", "Back up"), BackupTab.RESTORE to t("恢复", "Restore")).forEach { (k, label) ->
            val sel = k == tab
            Box(Modifier.weight(1f).height(36.dp).clip(CircleShape).background(if (sel) cs.primaryContainer else Color.Transparent).clickable { cb.tab(k) }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) cs.primary else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Card(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(vertical = 6.dp)) {
        if (title != null) Text(title, fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
        content()
    }
}

@Composable
private fun MainColumn(ui: BackupUi, cb: BackupCallbacks) {
    if (ui.tab == BackupTab.BACKUP) {
        Card(t("备份内容", "What to back up")) {
            ui.items.forEach { ItemRow(it, onToggle = { cb.toggle(it.kind) }, onGrant = { cb.grant(it.kind) }) }
        }
        Spacer(Modifier.height(10.dp))
        Card(t("保存到", "Save to")) {
            Row(Modifier.fillMaxWidth().clickable(onClick = cb.dest).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Rounded.Folder, Ghost.colors.folder)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.dest, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (ui.zip) t("ghost-backup-日期.zip", "ghost-backup-<date>.zip") else t("按日期新建文件夹", "One folder per date"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                Text(t("更改", "Change"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().clickable { cb.zip(!ui.zip) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Rounded.FolderZip, Ghost.colors.archive)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("打包为一个 zip", "Pack into one zip"), fontSize = 14.sp)
                    Text(t("方便传输；不打包则是普通文件，可直接给 SMS Backup & Restore 导入", "Easier to move; unpacked files import straight into SMS Backup & Restore"), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                GhostSwitch(ui.zip, { cb.zip(it) })
            }
        }
    } else {
        Card(t("备份来源", "Backup")) {
            when {
                ui.restoreLoading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(t("正在读取备份…", "Reading backup…"), fontSize = 14.sp)
                }
                ui.restoreLabel != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBox(Icons.Rounded.Inventory2, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ui.restoreLabel, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ui.restoreSub?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
                    }
                }
                else -> Text(t("在下方选择一个备份，或在文件管理器里点开备份文件（ghost-backup.json、ghost-backup-*.zip、sms-*.xml、calls-*.xml）。", "Pick a backup below, or tap a backup file in the file manager (ghost-backup.json, ghost-backup-*.zip, sms-*.xml, calls-*.xml)."),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            ui.restoreError?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        }
        if (ui.restoreItems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Card(t("恢复内容", "What to restore")) {
                ui.restoreItems.forEach { ItemRow(it, onToggle = { cb.toggleRestore(it.kind) }, onGrant = {}) }
            }
        }
        if (ui.recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Card(t("最近的备份", "Recent backups")) {
                ui.recent.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().clickable { cb.pickRecent(i) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBox(if (r.title.endsWith(".zip")) Icons.Rounded.FolderZip else Icons.Rounded.Folder, if (r.title.endsWith(".zip")) Ghost.colors.archive else Ghost.colors.folder)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCards(ui: BackupUi, cb: BackupCallbacks) {
    val cs = MaterialTheme.colorScheme
    ui.progress?.let { ProgressCard(it, cb); Spacer(Modifier.height(10.dp)) }
    ui.outcome?.let { OutcomeCard(it, cb); Spacer(Modifier.height(10.dp)) }
    if (ui.holdsSms) {
        Tip(Icons.Rounded.SwapHoriz, cs.error, t("双栏文件管理器现在是默认短信应用。恢复已完成，请切回系统「信息」，以免漏收彩信。", "This app is the default SMS app now. Switch back to your messaging app so MMS keep arriving."),
            t("切回默认短信应用", "Switch back"), cb.giveBackSms)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SideColumn(ui: BackupUi, cb: BackupCallbacks, status: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    if (status) StatusCards(ui, cb)
    val p = ui.progress
    if (p != null) Unit
    else if (ui.tab == BackupTab.BACKUP) {
        val n = ui.items.count { it.checked && it.enabled }
        Button(onClick = cb.start, enabled = n > 0, modifier = Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
            Icon(Icons.Rounded.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t("开始备份（$n 项）", "Back up ($n)"), fontSize = 15.sp)
        }
    } else {
        val n = ui.restoreItems.count { it.checked }
        Button(onClick = cb.startRestore, enabled = n > 0, modifier = Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) {
            Icon(Icons.Rounded.SettingsBackupRestore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t("开始恢复（$n 项）", "Restore ($n)"), fontSize = 15.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    if (ui.tab == BackupTab.BACKUP) {
        Tip(Icons.Rounded.Info, cs.primary, t("ColorOS / HyperOS 开启「验证码短信保护」（或类似的隐私保护）时，验证码短信可能读不到，备份条数会比实际少。需要完整备份的话，先在 短信设置 → 隐私保护 里关闭再备份。", "With ColorOS / HyperOS \"verification code protection\" on, OTP messages may be hidden and the count will be lower. Turn it off in Messages settings for a complete backup."))
        Spacer(Modifier.height(8.dp))
        Tip(Icons.Rounded.SwapHoriz, cs.primary, t("短信和通话记录保存为 sms-*.xml / calls-*.xml，和 SMS Backup & Restore 互相通用。", "Messages and calls are saved as sms-*.xml / calls-*.xml, interchangeable with SMS Backup & Restore."))
    } else {
        Tip(Icons.Rounded.FilterAlt, cs.primary, t("已经存在的短信、通话记录、联系人、日程不会重复写入。", "Items already on this phone are skipped, so nothing is duplicated."))
        Spacer(Modifier.height(8.dp))
        Tip(Icons.Rounded.Sms, cs.primary, t("恢复短信时需要临时把本应用设为默认短信应用（系统要求），恢复完成后会引导你切回原来的应用。", "Restoring messages needs this app as the default SMS app for a moment (Android rule); you will be guided back afterwards."))
        if (ui.restoreItems.any { it.kind == BackupKind.WIFI }) {
            Spacer(Modifier.height(8.dp))
            Tip(Icons.Rounded.WarningAmber, cs.error, t("恢复 WiFi 会用备份替换当前的已保存网络（原文件另存为 .ghostbak），需要 Root，完成后建议重启手机。", "Restoring WiFi replaces the saved networks (old file kept as .ghostbak); needs root; reboot afterwards."))
        }
    }
}

@Composable
private fun IconBox(icon: ImageVector, color: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
    }
}

@Composable
private fun ItemRow(it: BackupItemUi, onToggle: () -> Unit, onGrant: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clickable = it.enabled
    Row(
        Modifier.fillMaxWidth().clickable(enabled = clickable) { if (it.missing != null) onGrant() else onToggle() }.padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(kindIcon(it.kind), if (it.enabled) kindColor(it.kind) else cs.outline)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindLabel(it.kind), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (it.enabled) cs.onSurface else cs.outline)
                if (it.count != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(countText(it.kind, it.count), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cs.primary.copy(alpha = 0.10f)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Text(it.missing ?: kindFormat(it.kind), fontSize = 12.sp, color = if (it.missing != null && it.enabled) cs.primary else cs.outline, maxLines = 2)
        }
        if (it.missing != null && it.enabled) Text(t("授权", "Grant"), fontSize = 13.sp, color = cs.primary, fontWeight = FontWeight.Medium)
        else Checkbox(checked = it.checked && it.enabled, onCheckedChange = if (it.enabled) ({ _ -> onToggle() }) else null, enabled = it.enabled)
    }
}

@Composable
private fun Tip(icon: ImageVector, color: Color, text: String, action: String? = null, onAction: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.07f).compositeOverSurface(cs.surface)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 18.sp)
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action, fontSize = 13.sp) }
        }
    }
}

private fun Color.compositeOverSurface(s: Color) = this.compositeOver(s)

@Composable
private fun ProgressCard(p: BackupProgress, cb: BackupCallbacks) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text((if (p.restore) t("正在恢复", "Restoring") else t("正在备份", "Backing up")) + " · " + (p.kind?.let { kindLabel(it) } ?: "") + "  (${p.step}/${p.steps})",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        val frac = if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f
        val overall = ((p.step - 1) + frac) / p.steps.coerceAtLeast(1)
        LinearProgressIndicator(progress = { overall }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        Spacer(Modifier.height(8.dp))
        Row {
            Text(if (p.total > 0) "%,d / %,d".format(p.done, p.total) else t("准备中…", "Preparing…"), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${(overall * 100).toInt()}%", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Text(p.target, fontSize = 11.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = cb.cancel, modifier = Modifier.align(Alignment.End), shape = CircleShape) { Text(t("取消", "Cancel")) }
    }
}

@Composable
private fun OutcomeCard(o: BackupOutcome, cb: BackupCallbacks) {
    val cs = MaterialTheme.colorScheme
    val bad = o.error != null || o.cancelled
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (bad) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, tint = if (bad) cs.error else cs.primary)
            Spacer(Modifier.width(8.dp))
            Text(when {
                o.cancelled -> t("已取消", "Cancelled")
                o.error != null -> t("失败", "Failed")
                o.restore -> t("恢复完成", "Restore finished")
                else -> t("备份完成", "Backup finished")
            }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        o.error?.let { Text(it, fontSize = 13.sp, color = cs.error, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(6.dp))
        o.lines.forEach { l ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(l.kind), null, Modifier.size(16.dp), tint = if (l.ok) kindColor(l.kind) else cs.error)
                Spacer(Modifier.width(8.dp))
                Text(kindLabel(l.kind), fontSize = 13.sp, modifier = Modifier.width(96.dp))
                Text(l.text, fontSize = 13.sp, color = if (l.ok) cs.onSurfaceVariant else cs.error)
            }
        }
        if (!o.restore && o.location != null) Text(o.location, fontSize = 11.sp, color = cs.outline, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            if (!o.restore && o.path != null) TextButton(onClick = cb.openResult) { Text(t("在文件管理器中查看", "Show in files")) }
            TextButton(onClick = cb.dismiss) { Text(t("好", "OK")) }
        }
    }
}

// ====================================================================== stateful screen

private fun fmtDate(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun BackupScreen(vm: MainViewModel, s: Screen.Backup) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prov = remember { BackupProviders(ctx.applicationContext) }
    val kinds = remember { BackupKind.entries.toList() }
    var tab by remember(s) { mutableStateOf(if (s.restorePath != null) BackupTab.RESTORE else BackupTab.BACKUP) }
    val counts = remember { mutableStateMapOf<BackupKind, Int?>() }
    var checked by remember { mutableStateOf(setOf(BackupKind.SMS, BackupKind.CALLS, BackupKind.CONTACTS, BackupKind.CALENDAR)) }
    var zip by remember { mutableStateOf(false) }
    var destFs by remember { mutableStateOf(LocalFs.ID) }
    var destDir by remember { mutableStateOf(BackupJobs.defaultDir(vm.fsm)) }
    var destPicker by remember { mutableStateOf(false) }
    var rooted by remember { mutableStateOf(RootRunner.granted()) }
    var inv by remember { mutableStateOf<RestoreInventory?>(null) }
    var invLoading by remember { mutableStateOf(false) }
    var invError by remember { mutableStateOf<String?>(null) }
    var restoreChecked by remember { mutableStateOf(setOf<BackupKind>()) }
    var recent by remember { mutableStateOf<List<RestoreSource>>(emptyList()) }
    var recentInfo by remember { mutableStateOf<List<RecentBackup>>(emptyList()) }
    var wifiConfirm by remember { mutableStateOf(false) }
    var holdsSms by remember { mutableStateOf(BackupJobs.isDefaultSms(ctx)) }
    var pendingRestore by remember { mutableStateOf(false) }

    fun reloadCounts() = scope.launch {
        rooted = RootRunner.granted()
        for (k in kinds) {
            if (k == BackupKind.WIFI && !rooted) { counts[k] = null; continue }
            counts[k] = withContext(Dispatchers.IO) { runCatching { prov.count(k) }.getOrNull() }
        }
    }
    fun load(src: RestoreSource) {
        invLoading = true; invError = null; inv = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BackupJobs.inspect(vm.fsm, src) } }
            invLoading = false
            r.onSuccess { i -> inv = i; restoreChecked = i.entries.map { it.kind }.filter { it != BackupKind.WIFI }.toSet() }
                .onFailure { invError = t("无法读取：", "Cannot read: ") + (it.message ?: it.toString()) }
        }
    }
    LaunchedEffect(Unit) {
        reloadCounts()
        val r = withContext(Dispatchers.IO) { BackupJobs.recent(vm.fsm) }
        recent = r
        recentInfo = r.map { src -> RecentBackup(VPath.name(src.path), fmtDate(java.io.File(src.path).lastModified()) + " · " + VPath.parent(src.path).removePrefix(vm.fsm.storageRoot).ifEmpty { "/" }.let { t("内部存储", "Internal") + it }) }
    }
    LaunchedEffect(s) { if (s.restoreFs != null && s.restorePath != null) load(RestoreSource(s.restoreFs, s.restorePath)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reloadCounts() }
    fun request(ks: Collection<BackupKind>, restore: Boolean): Boolean {
        val need = ks.flatMap { prov.permissions(it, restore) }.distinct().filterNot { prov.has(it) }
        if (need.isEmpty()) return true
        permLauncher.launch(need.toTypedArray()); return false
    }

    fun runRestore() {
        val i = inv ?: return
        BackupJobs.restore(ctx.applicationContext, vm.fsm, i, restoreChecked.toList())
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        holdsSms = BackupJobs.isDefaultSms(ctx)
        if (pendingRestore) {
            pendingRestore = false
            if (!holdsSms) { vm.toast(t("未设为默认短信应用，短信不会恢复", "Not the default SMS app: messages will be skipped")); restoreChecked = restoreChecked - BackupKind.SMS }
            runRestore()
        }
    }
    fun startRestore() {
        if (!request(restoreChecked, true)) return
        if (BackupKind.SMS in restoreChecked && !BackupJobs.isDefaultSms(ctx)) {
            SmsRoleMemory.remember(ctx)
            pendingRestore = true
            val intent = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
                else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
            runCatching { roleLauncher.launch(intent) }.onFailure { pendingRestore = false; vm.toast(t("无法申请默认短信应用", "Cannot request the SMS role")) }
            return
        }
        runRestore()
    }
    fun giveBack() {
        val prev = SmsRoleMemory.previous(ctx)
        val i = if (Build.VERSION.SDK_INT < 29 && prev != null) Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, prev)
            else Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        runCatching { roleLauncher.launch(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
    // Back from the settings page / the result of a restore: re-check the role.
    val outcome = BackupJobs.outcome
    LaunchedEffect(outcome) { holdsSms = BackupJobs.isDefaultSms(ctx); if (outcome != null) reloadCounts() }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { holdsSms = BackupJobs.isDefaultSms(ctx); reloadCounts() } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val items = kinds.map { k ->
        val wifi = k == BackupKind.WIFI
        val c = counts[k]
        BackupItemUi(k, c, k in checked && (c != null || !wifi), enabled = !wifi || rooted,
            missing = when {
                wifi && !rooted -> t("需要 Root", "Needs root")
                c == null && !wifi && prov.permissions(k, false).any { !prov.has(it) } -> t("需要授权才能读取，点按授权", "Permission needed — tap to grant")
                else -> null
            })
    }
    val i = inv
    val ui = BackupUi(
        tab = tab, items = items,
        dest = (if (destFs == LocalFs.ID && destDir.startsWith(vm.fsm.storageRoot)) t("内部存储", "Internal") + destDir.removePrefix(vm.fsm.storageRoot) else vm.fsm.label(destFs) + "  " + destDir),
        zip = zip,
        restoreLabel = i?.let { VPath.name(it.source.path) },
        restoreSub = i?.let { listOfNotNull(if (it.created > 0) fmtDate(it.created) else null, it.device.ifBlank { null }, vm.fsm.label(it.source.fsId)).joinToString(" · ") },
        restoreItems = i?.entries?.map { e -> BackupItemUi(e.kind, e.count, e.kind in restoreChecked, enabled = e.kind != BackupKind.WIFI || rooted, missing = if (e.kind == BackupKind.WIFI && !rooted) t("需要 Root", "Needs root") else null) } ?: emptyList(),
        restoreLoading = invLoading, restoreError = invError,
        recent = recentInfo, progress = BackupJobs.progress, outcome = outcome, holdsSms = holdsSms && BackupJobs.progress == null,
    )
    val cb = BackupCallbacks(
        back = { vm.screen = Screen.Main },
        tab = { tab = it },
        toggle = { k -> checked = if (k in checked) checked - k else { request(listOf(k), false); checked + k } },
        grant = { k -> request(listOf(k), false) },
        zip = { zip = it },
        dest = { destPicker = true },
        start = {
            val sel = items.filter { it.checked && it.enabled }.map { it.kind }
            if (request(sel.filter { it != BackupKind.WIFI }, false)) BackupJobs.backup(ctx.applicationContext, vm.fsm, sel, destFs, destDir, zip)
        },
        cancel = { BackupJobs.cancel() },
        toggleRestore = { k -> restoreChecked = if (k in restoreChecked) restoreChecked - k else restoreChecked + k },
        pickRecent = { idx -> recent.getOrNull(idx)?.let { load(it) } },
        startRestore = { if (BackupKind.WIFI in restoreChecked) wifiConfirm = true else startRestore() },
        dismiss = { BackupJobs.outcome = null },
        openResult = { outcome?.let { o -> if (o.fsId != null && o.path != null) { vm.screen = Screen.Main; vm.openFs(o.fsId, if (zip) VPath.parent(o.path) else o.path) } } },
        giveBackSms = { giveBack() },
    )
    BackupContent(ui, cb)

    if (destPicker) BackupDestDialog(vm, destFs, destDir, dismiss = { destPicker = false }) { f, d -> destFs = f; destDir = d; destPicker = false }
    if (wifiConfirm) WifiRestoreDialog(dismiss = { wifiConfirm = false }) { wifiConfirm = false; startRestore() }
}

@Composable
private fun BackupDestDialog(vm: MainViewModel, fsId: String, dir: String, dismiss: () -> Unit, pick: (String, String) -> Unit) {
    var custom by remember { mutableStateOf(if (fsId == LocalFs.ID) dir else BackupJobs.defaultDir(vm.fsm)) }
    val opts = buildList {
        add(Triple(t("内部存储（默认）", "Internal storage (default)"), LocalFs.ID, BackupJobs.defaultDir(vm.fsm)))
        vm.panes.forEachIndexed { i, p ->
            val tb = p.tab
            add(Triple((if (i == 0) t("左栏当前目录", "Left pane folder") else t("右栏当前目录", "Right pane folder")) + " · " + vm.fsm.label(tb.fsId), tb.fsId, tb.path))
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t("保存位置", "Save to")) },
        text = {
            Column {
                Text(t("要存到 SMB / WebDAV / SFTP 等远程位置，先在左栏或右栏打开那个文件夹，再在这里选它。", "For SMB / WebDAV / SFTP, open that folder in a pane first, then pick it here."), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                opts.forEach { (label, f, d) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pick(f, d) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = f == fsId && d == dir, onClick = { pick(f, d) })
                        Column { Text(label, fontSize = 14.sp); Text(d, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                GhostTextField(custom, { custom = it }, singleLine = true, label = { Text(t("本机路径", "Local path")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (custom.startsWith("/")) pick(LocalFs.ID, VPath.normalize(custom)) }) { Text(t("使用此路径", "Use path")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

@Composable
private fun WifiRestoreDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    var ack by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(t("用备份替换 WiFi 配置？", "Replace WiFi networks?")) },
        text = {
            Column {
                Text(t("将用 Root 覆盖系统的 WifiConfigStore.xml：当前已保存的网络会被备份里的列表替换（原文件另存为 WifiConfigStore.xml.ghostbak）。过程中会关闭再打开 WiFi；系统通常要重启手机后才会完整读取新配置。不同安卓版本之间恢复可能无效。",
                    "Root overwrites WifiConfigStore.xml: saved networks are replaced by the backup (old file kept as .ghostbak). WiFi is toggled; a reboot is usually needed. Restoring across Android versions may not work."), fontSize = 13.sp)
                Row(Modifier.clickable { ack = !ack }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ack, { ack = it }); Text(t("我了解，继续替换", "I understand"), fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = ack) { Text(t("替换并重启 WiFi", "Replace & restart WiFi"), color = if (ack) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}
