package com.ghostpanter.files.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ghostpanter.files.core.apk.ApkBundle
import com.ghostpanter.files.core.apk.ApkBundle.Kind
import com.ghostpanter.files.core.apk.InstallSource
import com.ghostpanter.files.install.AppSource
import com.ghostpanter.files.install.BundleInstaller
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A bundle the user tapped: a .apks / .xapk / .apkm file or a folder of splits. */
data class BundleRequest(val file: File, val name: String, val tab: TabState? = null)

/** Everything the sheet shows; stateless so Paparazzi can render it. */
data class InstallUi(
    val title: String,
    val packageName: String?,
    val version: String?,
    val installedVersion: String?,
    val icon: ImageBitmap?,
    val format: String,
    val splits: List<ApkBundle.Split>,
    val selected: Set<String>,
    val auto: Set<String>,
    val deviceLine: String,
    val obbLine: String?,
    val silentVia: String?,
    val silent: Boolean,
    val progress: Pair<Long, Long>? = null,
    val result: Pair<Boolean, String>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Install-source choices for the pm path (empty = system installer, no choice). */
    val sourceOptions: List<SourceOptionUi> = emptyList(),
    val source: InstallSource.Choice? = null,
    /** "备份记录的原来源：Google Play" / "原来源未知". */
    val sourceLine: String? = null,
    /** Android 14+ update-ownership caveat. */
    val sourceWarn: String? = null,
    val issues: List<ApkBundle.SplitIssue> = emptyList(),
    /** "备份自 Xiaomi 14（arm64-v8a · 440dpi · zh-CN）" when it differs from this phone. */
    val backupDevice: String? = null,
    /** System-installer path: the app may check it came from Google Play. */
    val playHint: Boolean = false,
    val canOpenPlay: Boolean = false,
    /** After install: did Android record the source we asked for. */
    val resultNote: Pair<Boolean, String>? = null,
)

data class SourceOptionUi(val choice: InstallSource.Choice, val title: String, val sub: String)

class InstallActions(
    val toggle: (String) -> Unit = {},
    val resetAuto: () -> Unit = {},
    val setSilent: (Boolean) -> Unit = {},
    val install: () -> Unit = {},
    val extract: (() -> Unit)? = null,
    val open: (() -> Unit)? = null,
    val close: () -> Unit = {},
    val setSource: (InstallSource.Choice) -> Unit = {},
    val openPlay: () -> Unit = {},
)

private class Loaded(val bundle: ApkBundle, val info: PackageInfo?, val installed: PackageInfo?, val icon: Bitmap?, val label: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallSheet(req: BundleRequest, vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(req) { mutableStateOf<Loaded?>(null) }
    var error by remember(req) { mutableStateOf<String?>(null) }
    var selected by remember(req) { mutableStateOf(setOf<String>()) }
    var auto by remember(req) { mutableStateOf(setOf<String>()) }
    val elevated = remember { BundleInstaller.elevated() }
    var silent by remember { mutableStateOf(elevated != null) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val device = remember { BundleInstaller.device(ctx) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selfPkg = ctx.packageName
    val playPresent = remember { AppSource.playPresent(ctx) }
    var choice by remember(req) { mutableStateOf<InstallSource.Choice?>(null) }
    var current by remember(req) { mutableStateOf<InstallSource.Record?>(null) }
    var resultNote by remember(req) { mutableStateOf<Pair<Boolean, String>?>(null) }

    LaunchedEffect(req) {
        runCatching { withContext(Dispatchers.IO) { load(ctx, req.file) } }
            .onSuccess { l ->
                loaded = l; auto = l.bundle.select(device); selected = auto
                val pkg = l.info?.packageName ?: l.bundle.packageName
                current = pkg?.let { withContext(Dispatchers.IO) { AppSource.best(ctx, it, elevated?.second) } }
                choice = InstallSource.defaultChoice(l.bundle.meta?.source?.installer, selfPkg, playPresent, elevated != null) { AppSource.installed(ctx, it) }
            }
            .onFailure { error = it.message ?: it.toString() }
    }
    val original = loaded?.bundle?.meta?.source?.installer
    fun plannedInstaller(): String? {
        val c = choice
        return if (silent && elevated != null && c != null) InstallSource.installerFor(c, original, selfPkg) else null
    }

    fun install() {
        val l = loaded ?: return
        if (progress != null) return
        val shell = if (silent) elevated?.second else null
        if (shell == null && !BundleInstaller.canRequestInstalls(ctx)) {
            vm.toast(t("请允许「安装未知应用」后再点安装", "Allow “Install unknown apps”, then tap Install again"))
            runCatching { ctx.startActivity(BundleInstaller.permissionIntent(ctx)) }
            return
        }
        result = null
        progress = 0L to 1L
        scope.launch {
            val chosen = l.bundle.splits.filter { it.entry in selected }
            val installer = if (shell != null) plannedInstaller() else null
            resultNote = null
            val r = runCatching {
                BundleInstaller.install(ctx, l.bundle, chosen, shell, { d, tot -> progress = d to tot }, installer)
                val obbs = withContext(Dispatchers.IO) { BundleInstaller.copyObbs(l.bundle, elevated?.second) { d, tot -> progress = d to tot } }
                obbs
            }
            progress = null
            val pkg = l.info?.packageName ?: l.bundle.packageName
            if (r.isSuccess && pkg != null) {
                // Check what Android actually recorded; the system installer path always records this app.
                val expected = installer ?: if (shell == null) selfPkg else null
                val actual = withContext(Dispatchers.IO) { AppSource.best(ctx, pkg, shell) }
                current = actual
                val got = AppSource.label(ctx, actual?.installer)
                resultNote = when {
                    expected == null -> true to t("安装来源：", "Install source: ") + got
                    actual?.installer == expected -> true to t("安装来源已记录为 ", "Install source recorded as ") + got
                    else -> false to t("实际安装来源是 $got，不是所选的 ${AppSource.label(ctx, expected)}", "Android recorded $got, not ${AppSource.label(ctx, expected)}")
                }
            }
            result = r.fold(
                { obbs -> true to (t("安装成功", "Installed") + if (obbs.isNotEmpty()) t("，OBB 已复制到 ", "; OBB copied to ") + File(obbs[0]).parent else "") },
                { false to (it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    val l = loaded
    val pm = ctx.packageManager
    val ui = InstallUi(
        title = l?.label ?: req.name,
        packageName = l?.info?.packageName ?: l?.bundle?.packageName,
        version = l?.let { versionOf(it.info) ?: it.bundle.versionName?.let { v -> v + (it.bundle.versionCode?.let { c -> " ($c)" } ?: "") } },
        installedVersion = l?.installed?.let { versionOf(it) },
        icon = l?.icon?.asImageBitmap(),
        format = l?.bundle?.format?.name ?: "",
        splits = l?.bundle?.splits.orEmpty(),
        selected = selected, auto = auto,
        deviceLine = device.abis.firstOrNull().orEmpty() + " · ${device.densityDpi}dpi · " + device.languages.take(2).joinToString("/"),
        obbLine = l?.bundle?.obbs?.takeIf { it.isNotEmpty() }?.let { o ->
            t("附带 ${o.size} 个 OBB（${formatSize(o.sumOf { it.size })}），安装后复制到 Android/obb", "${o.size} OBB file(s) (${formatSize(o.sumOf { it.size })}) copied to Android/obb after install")
        },
        silentVia = elevated?.first, silent = silent,
        progress = progress, result = result,
        loading = l == null && error == null, error = error,
        sourceOptions = InstallSource.options(original, selfPkg, playPresent, elevated != null) { AppSource.installed(ctx, it) }.map { c ->
            when (c) {
                InstallSource.Choice.ORIGINAL -> SourceOptionUi(c, t("保持原来源：", "Keep original: ") + AppSource.label(ctx, original), t("备份时记录的安装来源 ", "Recorded at backup · ") + original)
                InstallSource.Choice.PLAY -> SourceOptionUi(c, "Google Play" + if (original == InstallSource.PLAY) t("（原来源）", " (original)") else "",
                    t("记为从 Play 安装，可避免「从 Play 获取此应用」提示", "Recorded as a Play install; avoids “Get this app from Play”"))
                InstallSource.Choice.SELF -> SourceOptionUi(c, t("双栏文件管理器", "Ghost Files") + if (original == selfPkg) t("（原来源）", " (original)") else "",
                    t("记为由本应用安装", "Recorded as installed by this app"))
            }
        },
        source = choice,
        sourceLine = l?.let {
            if (original != null) t("备份记录的原来源：", "Original source (from backup): ") + AppSource.label(ctx, original)
            else t("原来源未知（第三方安装包没有记录）", "Original source unknown (not recorded in this package)")
        },
        sourceWarn = run {
            val planned = if (silent && elevated != null) plannedInstaller() else selfPkg
            val owner = current?.updateOwner
            if (InstallSource.ownershipConflict(current, planned, Build.VERSION.SDK_INT) && owner != null) t(
                "Android 14+：此应用的更新所有者是 ${AppSource.label(ctx, owner)}。用其他来源覆盖安装时，系统可能弹出确认或直接拒绝；选 ${AppSource.label(ctx, owner)} 来源最稳妥。",
                "Android 14+: ${AppSource.label(ctx, owner)} owns updates for this app. Installing as another source may ask for confirmation or fail; choosing ${AppSource.label(ctx, owner)} is safest.")
            else null
        },
        issues = l?.bundle?.integrity(device, selected).orEmpty(),
        backupDevice = l?.bundle?.meta?.let { m ->
            val other = (m.abis.firstOrNull() != null && m.abis.firstOrNull() != device.abis.firstOrNull()) || (m.densityDpi > 0 && m.densityDpi != device.densityDpi)
            if (!other) null else t("备份自 ", "Backed up on ") + listOfNotNull(m.deviceModel, m.abis.firstOrNull(), m.densityDpi.takeIf { it > 0 }?.let { "${it}dpi" }, m.locales.firstOrNull()).joinToString(" · ")
        },
        playHint = l != null && !(silent && elevated != null) && (original == null || original == InstallSource.PLAY),
        canOpenPlay = playPresent && ui0Pkg(l) != null,
        resultNote = resultNote,
    )
    val launch = ui.packageName?.takeIf { result?.first == true }?.let { pkg -> pm.getLaunchIntentForPackage(pkg) }
    ModalBottomSheet(onDismissRequest = { if (progress == null) onDismiss() }, sheetState = sheet) {
        InstallSheetContent(ui, InstallActions(
            toggle = { e ->
                val s = loaded?.bundle?.splits?.firstOrNull { it.entry == e }
                if (s != null && s.kind != Kind.BASE) selected = if (e in selected) selected - e else selected + e
            },
            resetAuto = { selected = auto },
            setSilent = { silent = it },
            install = { install() },
            extract = req.tab?.takeIf { req.file.isFile }?.let { tab -> { onDismiss(); vm.extractApksBundle(tab, req.file) } },
            open = launch?.let { i -> { runCatching { ctx.startActivity(i) }; onDismiss() } },
            close = { if (progress == null) onDismiss() },
            setSource = { choice = it },
            openPlay = { ui.packageName?.let { p -> AppSource.playIntent(ctx, p)?.let { i -> runCatching { ctx.startActivity(i) } } } },
        ))
    }
}

private fun ui0Pkg(l: Loaded?): String? = l?.info?.packageName ?: l?.bundle?.packageName

private fun versionOf(p: PackageInfo?): String? {
    p ?: return null
    @Suppress("DEPRECATION")
    val vc = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    return "${p.versionName ?: "?"} ($vc)"
}

private fun load(ctx: Context, file: File): Loaded {
    val b = ApkBundle.read(file)
    val base = b.base ?: throw java.io.IOException(t("没有找到 base APK", "No base APK found"))
    val pm = ctx.packageManager
    val baseFile = if (b.format == ApkBundle.Format.FOLDER) File(file, base.entry)
    else File(ctx.cacheDir, "bundle-base-${System.nanoTime()}.apk").also { f -> b.open(base.entry).use { i -> f.outputStream().use { i.copyTo(it) } } }
    try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(baseFile.path, 0)?.also {
            it.applicationInfo?.sourceDir = baseFile.path
            it.applicationInfo?.publicSourceDir = baseFile.path
        }
        val label = info?.applicationInfo?.loadLabel(pm)?.toString()?.takeIf { it != info.packageName } ?: b.label
        val icon = runCatching { info?.applicationInfo?.loadIcon(pm)?.toBitmap(144, 144) }.getOrNull()
            ?: b.iconEntry?.let { e -> runCatching { b.open(e).use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        val pkg = info?.packageName ?: b.packageName
        val installed = pkg?.let { runCatching { pm.getPackageInfo(it, 0) }.getOrNull() }
        return Loaded(b, info, installed, icon, label)
    } finally {
        if (b.format != ApkBundle.Format.FOLDER) baseFile.delete()
    }
}

fun kindLabel(k: Kind): String = when (k) {
    Kind.BASE -> t("主包", "Base")
    Kind.FEATURE -> t("功能模块", "Feature")
    Kind.ABI -> t("架构", "ABI")
    Kind.DENSITY -> t("屏幕密度", "Density")
    Kind.LANGUAGE -> t("语言", "Language")
    Kind.OTHER -> t("其他", "Other")
}

@Composable
fun InstallSheetContent(s: InstallUi, a: InstallActions) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
                if (s.icon != null) Image(s.icon, null, Modifier.size(52.dp))
                else Icon(Icons.Rounded.Android, null, tint = cs.primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(s.version, s.packageName).joinToString(" · "), fontSize = 12.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                s.installedVersion?.let { Text(t("已安装 ", "Installed ") + it, fontSize = 12.sp, color = cs.outline) }
            }
        }
        if (s.loading) { Spacer(Modifier.height(16.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        s.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = cs.error, fontSize = 13.sp); return@Column }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("分包 ", "Splits ") + "${s.selected.size}/${s.splits.size}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(t("按本机：", "For this device: ") + s.deviceLine, fontSize = 12.sp, color = cs.outline, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.selected != s.auto) TextButton(a.resetAuto) { Text(t("自动选择", "Auto")) }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.ghostCard().heightIn(max = if (s.issues.isEmpty()) 300.dp else 220.dp).verticalScroll(rememberScrollState())) {
            s.splits.forEachIndexed { i, sp ->
                if (i > 0) GhostRowDivider(start = 52.dp)
                val on = sp.entry in s.selected
                Row(Modifier.fillMaxWidth().clickable(enabled = sp.kind != Kind.BASE && s.progress == null) { a.toggle(sp.entry) }.padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { a.toggle(sp.entry) }, enabled = sp.kind != Kind.BASE && s.progress == null)
                    Column(Modifier.weight(1f)) {
                        Text(sp.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(kindLabel(sp.kind) + (sp.qualifier?.let { " · $it" } ?: "") + (if (sp.owner != "base") " · ${sp.owner}" else ""),
                            fontSize = 12.sp, color = cs.outline)
                    }
                    Text(formatSize(sp.size), fontSize = 12.sp, color = cs.outline)
                }
            }
        }
        if (s.issues.isNotEmpty() || s.backupDevice != null) { Spacer(Modifier.height(10.dp)); SplitIssuesCard(s.issues, s.backupDevice) }
        s.obbLine?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.sp, color = cs.outline) }
        s.silentVia?.let { via ->
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clickable { a.setSilent(!s.silent) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("静默安装（$via）", "Silent install ($via)"), fontSize = 14.sp)
                    Text(t("通过 pm install-create / write / commit，无需确认", "pm install-create / write / commit, no prompt"), fontSize = 12.sp, color = cs.outline)
                }
                GhostSwitch(s.silent, a.setSilent)
            }
        }
        if (s.silent && s.silentVia != null && s.sourceOptions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SourcePicker(s, a)
        }
        if (s.playHint) { Spacer(Modifier.height(10.dp)); PlayHintCard(s.canOpenPlay, a.openPlay) }
        s.progress?.let { (d, tot) ->
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator({ if (tot > 0) (d.toFloat() / tot).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
            Text(formatSize(d) + " / " + formatSize(tot) + t("  正在写入安装会话…", "  writing install session…"), fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(top = 4.dp))
        }
        s.result?.let { (ok, msg) ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) cs.primary else cs.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(msg, fontSize = 13.sp, color = if (ok) cs.onSurface else cs.error)
            }
        }
        s.resultNote?.let { (ok, msg) ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.Verified else Icons.Rounded.WarningAmber, null, tint = if (ok) cs.primary else cs.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(msg, fontSize = 13.sp, color = if (ok) cs.onSurface else cs.error)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            a.extract?.let { TextButton(it, enabled = s.progress == null) { Text(t("仅解包", "Extract only")) } }
            Spacer(Modifier.weight(1f))
            OutlinedButton(a.close, enabled = s.progress == null) { Text(if (s.result?.first == true) t("完成", "Done") else t("取消", "Cancel")) }
            if (s.result?.first == true && a.open != null) Button(a.open) { Text(t("打开", "Open")) }
            else Button(a.install, enabled = s.progress == null && s.splits.isNotEmpty()) {
                Text(if (s.installedVersion != null) t("更新安装", "Update") else t("安装", "Install"))
            }
        }
    }
}

@Composable
private fun SplitIssuesCard(issues: List<ApkBundle.SplitIssue>, backupDevice: String?) {
    val cs = MaterialTheme.colorScheme
    val serious = issues.any { it.severity != ApkBundle.Severity.INFO }
    val tint = if (issues.any { it.severity == ApkBundle.Severity.ERROR }) cs.error else if (serious) Ghost.colors.archive else cs.primary
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.10f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (serious) Icons.Rounded.WarningAmber else Icons.Rounded.Info, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (serious) t("分包可能不完整", "Split set may be incomplete") else t("分包提示", "About the splits"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = tint)
        }
        backupDevice?.let { Text(it, fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(top = 4.dp)) }
        issues.forEach { i ->
            Text("• " + i.text, fontSize = 13.sp, color = if (i.severity == ApkBundle.Severity.ERROR) cs.error else cs.onSurface, modifier = Modifier.padding(top = 4.dp))
        }
        if (serious) Text(t("缺少本机需要的分包时，应用可能闪退，或弹出「从 Play 获取此应用」。仍可继续安装。", "Without the splits this phone needs the app may crash or show “Get this app from Play”. You can still install."),
            fontSize = 12.sp, color = cs.outline, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SourcePicker(s: InstallUi, a: InstallActions) {
    val cs = MaterialTheme.colorScheme
    Text(t("安装来源", "Install source"), fontWeight = FontWeight.Medium, fontSize = 14.sp)
    s.sourceLine?.let { Text(it, fontSize = 12.sp, color = cs.outline) }
    Spacer(Modifier.height(6.dp))
    Column(Modifier.ghostCard()) {
        s.sourceOptions.forEachIndexed { i, o ->
            if (i > 0) GhostRowDivider(start = 52.dp)
            Row(Modifier.fillMaxWidth().clickable(enabled = s.progress == null) { a.setSource(o.choice) }.padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.source == o.choice, { a.setSource(o.choice) }, enabled = s.progress == null)
                Column(Modifier.weight(1f)) {
                    Text(o.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(o.sub, fontSize = 12.sp, color = cs.outline)
                }
            }
        }
    }
    s.sourceWarn?.let { Text(it, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(top = 6.dp)) }
    Text(t("部分应用还会在服务器核对账号是否拥有该应用，改安装来源不保证一定通过。", "Some apps also check with their server that your account owns the app; changing the source is not a guarantee."),
        fontSize = 11.sp, color = cs.outline, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun PlayHintCard(canOpenPlay: Boolean, openPlay: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.primary.copy(alpha = 0.08f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shop, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("该应用可能检查是否来自 Google Play", "This app may check it came from Google Play"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(t("通过系统安装器安装时，安装来源会记为双栏文件管理器，没有 Root/Shizuku 无法更改。如果打开后提示「从 Play 获取此应用」，可在 Play 中打开它并点「安装」或「更新」：签名与 Play 版一致时会直接覆盖，数据不会丢失。部分应用还会在服务器核对账号是否拥有该应用。",
            "The system installer records this app as the source; without Root/Shizuku that can't be changed. If the app says “Get this app from Play”, open it in Play and tap Install/Update: with the same signature it replaces in place and keeps your data. Some apps also check account ownership on their server."),
            fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (canOpenPlay) TextButton(openPlay, Modifier.align(Alignment.End)) { Text(t("在 Play 中打开", "Open in Play")) }
    }
}
