package com.ghostpanter.files.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.apk.ApkBundle
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.fs.ShizukuRunner
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Installs split sets: PackageInstaller session (user confirms), or silently through `pm` with Root/Shizuku. */
object BundleInstaller {
    class NeedsInstallPermission : IOException("REQUEST_INSTALL_PACKAGES not granted")

    fun device(ctx: Context): ApkBundle.Device {
        val locales = ctx.resources.configuration.locales.let { l -> (0 until l.size()).map { l[it].toLanguageTag() } }
        return ApkBundle.Device(Build.SUPPORTED_ABIS.toList(), ctx.resources.displayMetrics.densityDpi, locales)
    }

    /** Root first, then Shizuku; null when neither is granted. */
    fun elevated(): Pair<String, ShellRunner>? = when {
        RootRunner.granted() -> "Root" to RootRunner
        ShizukuRunner.granted() -> "Shizuku" to ShizukuRunner
        else -> null
    }

    fun canRequestInstalls(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Installs [entries] of [bundle]. [onProgress] gets (done, total) bytes. Returns normally on success,
     * throws with the installer's reason otherwise.
     */
    suspend fun install(
        ctx: Context, bundle: ApkBundle, entries: List<ApkBundle.Split>, shell: ShellRunner?,
        onProgress: (Long, Long) -> Unit,
    ) {
        require(entries.any { it.kind == ApkBundle.Kind.BASE }) { "base APK required" }
        if (shell != null) withContext(Dispatchers.IO) { installWithPm(shell, bundle, entries, onProgress) }
        else {
            if (!canRequestInstalls(ctx)) throw NeedsInstallPermission()
            installWithSession(ctx, bundle, entries, onProgress)
        }
    }

    // ---------------------------------------------------------------- PackageInstaller session

    private suspend fun installWithSession(ctx: Context, bundle: ApkBundle, entries: List<ApkBundle.Split>, onProgress: (Long, Long) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val total = entries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        val sessionId = withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            bundle.packageName?.let { params.setAppPackageName(it) }
            params.setSize(total)
            params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            try {
                pi.openSession(id).use { s ->
                    var done = 0L
                    for ((i, e) in entries.withIndex()) {
                        bundle.open(e.entry).use { ins ->
                            s.openWrite("${i}_" + safe(e.name), 0, if (e.size > 0) e.size else -1).use { out ->
                                copy(ins, out) { n -> done += n; onProgress(done, total); s.setStagingProgress(done.toFloat() / total) }
                                s.fsync(out)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                runCatching { pi.abandonSession(id) }
                throw t
            }
            id
        }
        commit(ctx, pi, sessionId)
    }

    private suspend fun commit(ctx: Context, pi: PackageInstaller, sessionId: Int) = suspendCancellableCoroutine<Unit> { cont ->
        val action = ctx.packageName + ".INSTALL_RESULT." + sessionId
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm != null) runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { done(c, this); if (cont.isActive) cont.resumeWithException(it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { done(c, this); if (cont.isActive) cont.resume(Unit) }
                    else -> {
                        done(c, this)
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        if (cont.isActive) cont.resumeWithException(IOException(statusText(status) + (msg?.let { " · $it" } ?: "")))
                    }
                }
            }
        }
        ContextCompat.registerReceiver(ctx.applicationContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation { runCatching { ctx.applicationContext.unregisterReceiver(receiver) }; runCatching { pi.abandonSession(sessionId) } }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(ctx, sessionId, Intent(action).setPackage(ctx.packageName), flags)
        try {
            pi.openSession(sessionId).use { it.commit(pending.intentSender) }
        } catch (e: Exception) {
            runCatching { ctx.applicationContext.unregisterReceiver(receiver) }
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private fun done(c: Context, r: BroadcastReceiver) { runCatching { c.applicationContext.unregisterReceiver(r) } }

    fun statusText(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> t("已取消", "Cancelled")
        PackageInstaller.STATUS_FAILURE_BLOCKED -> t("被系统或策略阻止", "Blocked by the system or policy")
        PackageInstaller.STATUS_FAILURE_CONFLICT -> t("与已安装的应用冲突（签名不一致或降级）", "Conflicts with the installed app (signature or downgrade)")
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> t("与本机不兼容（架构或系统版本）", "Incompatible with this device (ABI or SDK)")
        PackageInstaller.STATUS_FAILURE_INVALID -> t("安装包无效或分包不完整", "Invalid package or incomplete split set")
        PackageInstaller.STATUS_FAILURE_STORAGE -> t("存储空间不足", "Not enough storage")
        else -> t("安装失败", "Install failed")
    }

    // ---------------------------------------------------------------- pm over Root / Shizuku

    private fun installWithPm(sh: ShellRunner, bundle: ApkBundle, entries: List<ApkBundle.Split>, onProgress: (Long, Long) -> Unit) {
        val total = entries.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        val created = run(sh, "pm install-create -r -S $total")
        val sid = Regex("""\[(\d+)]""").find(created)?.groupValues?.get(1)
            ?: throw IOException(t("pm 无法创建安装会话：", "pm install-create failed: ") + created.trim())
        try {
            var done = 0L
            for ((i, e) in entries.withIndex()) {
                val p = sh.start("pm install-write -S ${e.size} $sid ${i}_${safe(e.name)} -")
                bundle.open(e.entry).use { ins -> p.outputStream.use { out -> copy(ins, out) { n -> done += n; onProgress(done, total) } } }
                val out = finish(p)
                if (!out.contains("Success")) throw IOException("install-write: " + out.trim())
            }
            val r = run(sh, "pm install-commit $sid")
            if (!r.contains("Success")) throw IOException(r.trim().ifEmpty { "install-commit failed" })
        } catch (t: Throwable) {
            runCatching { run(sh, "pm install-abandon $sid") }
            throw t
        }
    }

    private fun run(sh: ShellRunner, cmd: String): String {
        val p = sh.start(cmd)
        p.outputStream.close()
        return finish(p)
    }

    private fun finish(p: Process): String {
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("pm timed out") }
        return out
    }

    // ---------------------------------------------------------------- OBB

    /** Copies XAPK expansion files to shared storage; falls back to the elevated shell. Returns where they went. */
    fun copyObbs(bundle: ApkBundle, shell: ShellRunner?, onProgress: (Long, Long) -> Unit = { _, _ -> }): List<String> {
        if (bundle.obbs.isEmpty()) return emptyList()
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val total = bundle.obbs.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        var done = 0L
        val out = ArrayList<String>()
        for (o in bundle.obbs) {
            val dst = File(root, o.target)
            val direct = runCatching {
                dst.parentFile?.mkdirs()
                bundle.open(o.entry).use { ins -> dst.outputStream().use { copy(ins, it) { n -> done += n; onProgress(done, total) } } }
                if (o.size >= 0 && dst.length() != o.size) throw IOException("short write")
            }
            if (direct.isFailure) {
                runCatching { dst.delete() }
                val sh = shell ?: throw IOException(t("无法写入 ", "Cannot write ") + dst.parent + t("（需要 Root 或 Shizuku）", " (needs Root or Shizuku)"))
                val q = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
                val p = sh.start("mkdir -p ${q(dst.parent!!)} && cat > ${q(dst.path)}")
                bundle.open(o.entry).use { ins -> p.outputStream.use { copy(ins, it) { n -> done += n; onProgress(done, total) } } }
                finish(p)
                if (p.exitValue() != 0) throw IOException("OBB copy failed: " + dst.path)
            }
            out += dst.path
        }
        return out
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private inline fun copy(ins: InputStream, out: java.io.OutputStream, onChunk: (Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            onChunk(n)
        }
    }
}
