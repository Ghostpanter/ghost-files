package com.ghostpanter.files.ui

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.apk.ApkBundle
import com.ghostpanter.files.core.ops.AppBackupFiles
import com.ghostpanter.files.core.ops.AppBackupFiles.Part
import com.ghostpanter.files.core.ops.AppDataBackup
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.install.BundleInstaller
import com.ghostpanter.files.t
import kotlinx.coroutines.launch
import java.io.File

/** What one app backup / restore needs to know about the app. */
data class AppBackupTarget(
    val pkg: String,
    val label: String,
    val version: String = "",
    val versionCode: Long = 0,
    val apk: String = "",
    val splits: List<String> = emptyList(),
)

/** Per-part outcome; null = part not requested. */
data class PartResult(val apk: Result<String>? = null, val data: Result<String>? = null)

/** Shared backup / restore for the Apps screen and `*-data.tar` in the file list. */
object AppBackupOps {
    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GhostFiles/apks")

    fun partLabel(p: Part): String = when (p) {
        Part.APK -> t("APK", "APK")
        Part.DATA -> t("应用数据", "App data")
        Part.BOTH -> t("都要", "Both")
    }

    /**
     * Copies base.apk (or base + splits as an .apks zip) to the backup folder. The .apks carries ghost-app.json
     * with the original install source and this device's ABI / density / languages.
     */
    fun backupApk(ctx: Context, a: AppBackupTarget): String {
        val d = dir().apply { mkdirs() }
        if (a.splits.isEmpty()) {
            val out = File(d, AppBackupFiles.apkName(a.pkg, a.label, a.version, false))
            File(a.apk).inputStream().use { i -> out.outputStream().use { i.copyTo(it, 256 * 1024) } }
            return out.path
        }
        val out = File(d, AppBackupFiles.apkName(a.pkg, a.label, a.version, true))
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(com.ghostpanter.files.core.apk.BundleMeta.NAME))
            z.write(com.ghostpanter.files.install.AppSource.meta(ctx, a.pkg, a.version, a.versionCode).toJson().toByteArray())
            z.closeEntry()
            for (p in listOf(a.apk) + a.splits) {
                z.putNextEntry(java.util.zip.ZipEntry(File(p).name))
                File(p).inputStream().use { it.copyTo(z, 256 * 1024) }
                z.closeEntry()
            }
        }
        return out.path
    }

    fun backupData(vm: MainViewModel, pkg: String): String {
        if (!RootRunner.granted()) throw IllegalStateException(t("需要 Root", "Root required"))
        val out = File(dir().apply { mkdirs() }, AppBackupFiles.dataTarName(pkg))
        AppDataBackup.backup(vm.fsm.root, pkg, out)
        return out.path
    }

    /** Runs the chosen parts in order (APK first). [onPart] tells the UI which part is running. */
    fun backup(ctx: Context, vm: MainViewModel, a: AppBackupTarget, part: Part, onPart: (Part) -> Unit = {}): PartResult {
        val apk = if (part.apk) { onPart(Part.APK); runCatching { backupApk(ctx, a) } } else null
        val data = if (part.data) { onPart(Part.DATA); runCatching { backupData(vm, a.pkg) } } else null
        return PartResult(apk, data)
    }

    /** Package inside a backup file (ghost-app.json / XAPK manifest, or the APK itself). */
    fun packageOf(ctx: Context, f: File): String? = runCatching {
        if (f.name.endsWith(".apk", true)) {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageArchiveInfo(f.path, 0)?.packageName
        } else ApkBundle.read(f).packageName
    }.getOrNull()

    private fun candidates(folder: File) = folder.listFiles().orEmpty().filter { it.isFile }
        .map { AppBackupFiles.Candidate(it.name, it.lastModified()) }

    fun findApk(ctx: Context, pkg: String, folder: File = dir()): File? =
        AppBackupFiles.pickApk(candidates(folder), pkg) { n -> packageOf(ctx, File(folder, n)) }?.let { File(folder, it.name) }

    fun findData(pkg: String, folder: File = dir()): File? =
        File(folder, AppBackupFiles.dataTarName(pkg)).takeIf { it.isFile }

    fun installedUid(ctx: Context, pkg: String): Int? = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).uid
    }.getOrNull()

    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun restoreData(ctx: Context, vm: MainViewModel, pkg: String, tar: File): String {
        if (!RootRunner.granted()) throw IllegalStateException(t("需要 Root", "Root required"))
        val uid = installedUid(ctx, pkg) ?: throw IllegalStateException(t("应用未安装，请先恢复 APK", "App not installed; restore the APK first"))
        AppDataBackup.restore(vm.fsm.root, pkg, tar, uid)
        return tar.name
    }

    /** Silent install through Root/Shizuku. */
    suspend fun installSilently(ctx: Context, f: File): String {
        val sh = BundleInstaller.elevated()?.second ?: throw IllegalStateException(t("静默安装需要 Root 或 Shizuku", "Silent install needs Root or Shizuku"))
        BundleInstaller.installFile(ctx, f, sh)
        return f.name
    }

    /** APK first, then data (so the app uid exists). Data is skipped when the APK part failed. */
    suspend fun restore(ctx: Context, vm: MainViewModel, pkg: String, part: Part, apk: File?, tar: File?, onPart: (Part) -> Unit = {}): PartResult {
        val a = if (part.apk) {
            onPart(Part.APK)
            runCatching { installSilently(ctx, apk ?: throw IllegalStateException(t("没找到 APK 备份", "No APK backup found"))) }
        } else null
        val d = if (part.data) {
            onPart(Part.DATA)
            if (a?.isFailure == true) Result.failure(IllegalStateException(t("APK 未装上，跳过数据", "APK failed; data skipped")))
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { restoreData(ctx, vm, pkg, tar ?: throw IllegalStateException(t("没找到数据备份", "No data backup found"))) }
            }
        } else null
        return PartResult(a, d)
    }

    fun summary(r: PartResult): String = buildList {
        r.apk?.let { add("APK " + if (it.isSuccess) "✓" else "✗ " + (it.exceptionOrNull()?.message ?: "").take(160)) }
        r.data?.let { add(t("数据 ", "Data ") + if (it.isSuccess) "✓" else "✗ " + (it.exceptionOrNull()?.message ?: "").take(160)) }
    }.joinToString("\n")

    /** "APK 成功 3 失败 0 · 数据 成功 2 失败 1" over a batch. */
    fun batchSummary(rs: List<PartResult>): String = buildList {
        val ap = rs.mapNotNull { it.apk }
        val dp = rs.mapNotNull { it.data }
        if (ap.isNotEmpty()) add(t("APK 成功 ${ap.count { it.isSuccess }}，失败 ${ap.count { it.isFailure }}", "APK ${ap.count { it.isSuccess }} ok, ${ap.count { it.isFailure }} failed"))
        if (dp.isNotEmpty()) add(t("数据 成功 ${dp.count { it.isSuccess }}，失败 ${dp.count { it.isFailure }}", "Data ${dp.count { it.isSuccess }} ok, ${dp.count { it.isFailure }} failed"))
    }.joinToString("\n")
}

/** One row in the APK / 应用数据 / 都要 picker. */
data class PartOption(val part: Part, val enabled: Boolean, val sub: String)

/** Picker for backup or restore parts. Disabled rows show why (e.g. 需 Root). */
@Composable
fun AppPartDialog(
    title: String,
    options: List<PartOption>,
    note: String? = null,
    confirmLabel: String,
    extra: (@Composable () -> Unit)? = null,
    dismiss: () -> Unit,
    onPick: (Part) -> Unit,
) {
    var pick by remember { mutableStateOf(options.firstOrNull { it.enabled }?.part) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = o.enabled) { pick = o.part }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pick == o.part, onClick = { pick = o.part }, enabled = o.enabled)
                        Column(Modifier.weight(1f)) {
                            Text(AppBackupOps.partLabel(o.part), fontWeight = FontWeight.Medium,
                                color = if (o.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                            if (o.sub.isNotEmpty()) Text(o.sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(enabled = pick != null, onClick = { pick?.let { onPick(it); dismiss() } }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
    )
}

/** Running part + counters for single or batch backup / restore. */
data class AppOpProgress(val title: String, val index: Int, val total: Int, val label: String, val part: Part?)

@Composable
fun AppOpProgressDialog(p: AppOpProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(p.title) },
        text = {
            Column {
                if (p.total > 1) Text(t("正在处理 ${p.index} / ${p.total}", "Processing ${p.index} / ${p.total}"))
                Text(p.label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                p.part?.let {
                    Text(t("当前：", "Now: ") + when (it) {
                        Part.APK -> t("APK", "APK")
                        else -> t("应用数据（Root）", "App data (Root)")
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(8.dp))
                if (p.total > 1) LinearProgressIndicator(progress = { p.index.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

@Composable
fun AppOpResultDialog(title: String, body: String, onOpenFolder: (() -> Unit)?, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(body, fontSize = 13.sp) },
        confirmButton = { TextButton(onClick = dismiss) { Text(t("好", "OK")) } },
        dismissButton = onOpenFolder?.let { f -> { TextButton(onClick = { dismiss(); f() }) { Text(t("打开备份目录", "Open folder")) } } },
    )
}

/** Options for backing up: data rows need Root. */
fun backupOptions(rootOk: Boolean, splitCount: Int = 0): List<PartOption> {
    val noRoot = t("需要 Root", "Needs Root")
    return listOf(
        PartOption(Part.APK, true, if (splitCount > 0) t("含分包，存为 .apks", "Has splits, saved as .apks") else t("安装包", "Install package")),
        PartOption(Part.DATA, rootOk, if (rootOk) "/data/data → tar" else noRoot),
        PartOption(Part.BOTH, rootOk, if (rootOk) t("先 APK 再数据", "APK, then data") else noRoot),
    )
}

/**
 * Options for restoring. APK alone may use the system installer when [silentOk] is false (single app only);
 * data needs Root and an installed app (or the APK in the same run).
 */
fun restoreOptions(rootOk: Boolean, silentOk: Boolean, hasApk: Boolean, hasData: Boolean, installed: Boolean, allowSystemInstaller: Boolean): List<PartOption> {
    val apkWhy = when {
        !hasApk -> t("没找到 APK 备份", "No APK backup")
        !silentOk && !allowSystemInstaller -> t("批量需 Root/Shizuku 静默安装", "Batch needs Root/Shizuku")
        else -> ""
    }
    val dataWhy = when {
        !hasData -> t("没找到数据备份", "No data backup")
        !rootOk -> t("需要 Root", "Needs Root")
        else -> ""
    }
    val apkOk = apkWhy.isEmpty()
    val dataOk = dataWhy.isEmpty()
    return listOf(
        PartOption(Part.APK, apkOk, if (!apkOk) apkWhy else if (allowSystemInstaller) t("打开安装界面", "Open installer") else t("静默安装", "Silent install")),
        PartOption(Part.DATA, dataOk && installed, when {
            !dataOk -> dataWhy
            !installed -> t("应用未安装", "App not installed")
            else -> t("覆盖当前数据", "Overwrites current data")
        }),
        PartOption(Part.BOTH, hasApk && silentOk && dataOk, when {
            !hasApk -> apkWhy
            !dataOk -> dataWhy
            !silentOk -> t("需要 Root", "Needs Root")
            else -> t("先装 APK 再恢复数据", "Install APK, then data")
        }),
    )
}

/** `<pkg>-data.tar` opened from the file list. [file] is a local copy when the folder is remote. */
data class DataTarRequest(val tab: TabState, val entry: com.ghostpanter.files.core.FileEntry, val file: File, val pkg: String)

@Composable
fun DataTarRestoreDialog(vm: MainViewModel, r: DataTarRequest, dismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOk = remember { RootRunner.granted() }
    val silentOk = remember { BundleInstaller.elevated() != null }
    var state by remember(r) { mutableStateOf<Pair<Boolean, File?>?>(null) }
    var progress by remember { mutableStateOf<AppOpProgress?>(null) }
    var confirm by remember { mutableStateOf<Part?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r) {
        state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // APK next to the tar (local folders only; remote ones would need downloading the whole folder)
            val apk = if (r.tab.fsId == com.ghostpanter.files.core.LocalFs.ID) File(r.entry.path).parentFile?.let { AppBackupOps.findApk(ctx, r.pkg, it) } else null
            AppBackupOps.isInstalled(ctx, r.pkg) to apk
        }
    }
    val label = remember(r.pkg, state) {
        runCatching { ctx.packageManager.getApplicationInfo(r.pkg, 0).loadLabel(ctx.packageManager).toString() }.getOrNull() ?: r.pkg
    }
    val st = state
    when {
        result != null -> AppOpResultDialog(t("恢复 · ", "Restore · ") + label, result!!, null, dismiss)
        progress != null -> AppOpProgressDialog(progress!!)
        st == null -> AlertDialog(onDismissRequest = dismiss, title = { Text(r.entry.name) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
        confirm != null -> {
            val part = confirm!!
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(t("确认恢复", "Confirm restore")) },
                text = {
                    Text(
                        if (part == Part.BOTH) t("先覆盖安装「$label」，再强制停止并用备份覆盖全部数据，无法撤销。", "Reinstalls $label, then replaces all its data. This cannot be undone.")
                        else t("将强制停止「$label」，并用备份覆盖它当前的全部数据，无法撤销。", "Force-stops $label and replaces all its current data. This cannot be undone."),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        val title = t("恢复", "Restore") + " · " + AppBackupOps.partLabel(part)
                        progress = AppOpProgress(title, 1, 1, label, null)
                        scope.launch {
                            val res = AppBackupOps.restore(ctx, vm, r.pkg, part, st.second, r.file) { p -> progress = AppOpProgress(title, 1, 1, label, p) }
                            progress = null
                            result = AppBackupOps.summary(res)
                        }
                    }) { Text(t("恢复", "Restore"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(t("取消", "Cancel")) } },
            )
        }
        else -> {
            val (installed, apk) = st
            val opts = restoreOptions(rootOk, silentOk, hasApk = apk != null, hasData = true, installed = installed, allowSystemInstaller = false)
                .filter { it.part != Part.APK }
            AppPartDialog(
                title = t("恢复应用数据", "Restore app data"),
                options = opts,
                note = when {
                    !rootOk -> t("恢复应用数据需要 Root", "Restoring app data needs Root")
                    !installed && apk == null -> t("「${r.pkg}」未安装，同目录也没有它的 APK 备份", "${r.pkg} is not installed and no APK backup is next to it")
                    else -> null
                },
                confirmLabel = t("下一步", "Next"),
                extra = {
                    Spacer(Modifier.height(6.dp))
                    Text("$label\n${r.pkg}" + (apk?.let { "\nAPK：" + it.name } ?: ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    TextButton(onClick = { dismiss(); vm.pendingExtract = r.tab to r.entry }) { Text(t("改为解压", "Extract instead")) }
                },
                dismiss = { if (confirm == null) dismiss() },
            ) { part -> confirm = part }
        }
    }
}
