package com.ghostpanter.files.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.apk.BundleMeta
import com.ghostpanter.files.core.apk.InstallSource
import java.util.concurrent.TimeUnit

/** Install-source lookups on this device (PackageManager, or dumpsys through Root / Shizuku). */
object AppSource {
    fun installed(ctx: Context, pkg: String): Boolean = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun playPresent(ctx: Context) = installed(ctx, InstallSource.PLAY)

    /** getInstallSourceInfo on Android 11+, getInstallerPackageName before; null when the app is not installed. */
    fun record(ctx: Context, pkg: String): InstallSource.Record? = runCatching {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 30) {
            val i = pm.getInstallSourceInfo(pkg)
            InstallSource.Record(
                i.installingPackageName,
                runCatching { i.initiatingPackageName }.getOrNull(),
                runCatching { i.originatingPackageName }.getOrNull(),
                if (Build.VERSION.SDK_INT >= 34) runCatching { i.updateOwnerPackageName }.getOrNull() else null,
            )
        } else {
            @Suppress("DEPRECATION")
            InstallSource.Record(pm.getInstallerPackageName(pkg))
        }
    }.getOrNull()

    /** Fuller answer through the elevated shell (also sees initiating / update owner when PackageManager hides them). */
    fun recordViaShell(sh: ShellRunner, pkg: String): InstallSource.Record? = runCatching {
        InstallSource.parseDumpsys(run(sh, InstallSource.dumpsys(pkg)), pkg)
    }.getOrNull()

    fun best(ctx: Context, pkg: String, sh: ShellRunner?): InstallSource.Record? {
        val pmRec = record(ctx, pkg)
        val shRec = sh?.let { recordViaShell(it, pkg) } ?: return pmRec
        return if (pmRec == null) shRec else InstallSource.Record(
            pmRec.installer ?: shRec.installer, pmRec.initiating ?: shRec.initiating,
            pmRec.originating ?: shRec.originating, pmRec.updateOwner ?: shRec.updateOwner,
        )
    }

    fun label(ctx: Context, pkg: String?): String = InstallSource.knownLabel(pkg, ctx.packageName)
        ?: runCatching { ctx.packageManager.getApplicationInfo(pkg!!, 0).loadLabel(ctx.packageManager).toString() }.getOrNull()
        ?: pkg!!

    /** market://details in the Play Store app itself; null when Play is not installed. */
    fun playIntent(ctx: Context, pkg: String): Intent? = if (!playPresent(ctx)) null else
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(InstallSource.PLAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** `pm set-installer`; Result carries pm's own words on failure. */
    fun setInstaller(sh: ShellRunner, pkg: String, installer: String): Result<Unit> = runCatching {
        val out = run(sh, InstallSource.setInstaller(pkg, installer))
        if (!InstallSource.setInstallerOk(out)) throw java.io.IOException(out.trim().ifEmpty { "set-installer failed" })
    }

    /** ghost-app.json for a backup of [pkg]. */
    fun meta(ctx: Context, pkg: String, versionName: String?, versionCode: Long?): BundleMeta {
        val d = BundleInstaller.device(ctx)
        return BundleMeta(pkg, versionName, versionCode, record(ctx, pkg) ?: InstallSource.Record(null),
            (Build.MANUFACTURER + " " + Build.MODEL).trim(), d.abis, d.densityDpi, d.languages, System.currentTimeMillis())
    }

    private fun run(sh: ShellRunner, cmd: String): String {
        val p = sh.start(cmd)
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw java.io.IOException("timed out") }
        return out
    }
}
