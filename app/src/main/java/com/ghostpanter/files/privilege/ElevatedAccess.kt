package com.ghostpanter.files.privilege

import android.content.Context
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.privilege.PrivilegeInputs
import com.ghostpanter.files.core.privilege.PrivilegeKind
import com.ghostpanter.files.core.privilege.PrivilegeTier
import com.ghostpanter.files.core.privilege.privilegeLabel
import com.ghostpanter.files.core.privilege.selectPrivilege
import com.ghostpanter.files.core.privilege.selectTier
import com.ghostpanter.files.core.privilege.tierLabel
import com.ghostpanter.files.fs.ExternalShizuku
import com.ghostpanter.files.fs.IFileService
import com.ghostpanter.files.fs.RootRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified elevated access with hierarchy Root > external Shizuku > embedded > none.
 * Shell selection (Shizuku layer) is [kind]; full stack including Root is [tier].
 */
object ElevatedAccess {
    private val appCtx = AtomicReference<Context?>(null)

    fun init(ctx: Context) { appCtx.set(ctx.applicationContext) }

    fun inputs(): PrivilegeInputs {
        val ctx = appCtx.get()
        val installed = ctx?.let { externalInstalled(it) } ?: false
        val running = ExternalShizuku.running()
        val granted = ExternalShizuku.granted()
        return PrivilegeInputs(installed, running, granted, EmbeddedPrivilege.alive())
    }

    /** Active shell-uid backend (ignores Root). */
    fun kind(): PrivilegeKind = selectPrivilege(inputs())

    /** Highest available tier: Root if granted, else shell kind. */
    fun tier(): PrivilegeTier = selectTier(RootRunner.granted(), kind())

    /** Shell elevated granted (Shizuku / 内置), not Root. */
    fun shellGranted(): Boolean = kind() != PrivilegeKind.NONE

    /** Shell elevated granted — same as [shellGranted]. Root is [RootRunner.granted] / [tier]. */
    fun granted(): Boolean = shellGranted()

    /** Any elevated access (Root or shell). */
    fun anyGranted(): Boolean = tier() != PrivilegeTier.NONE

    fun label(en: Boolean = false): String = when (val t = tier()) {
        PrivilegeTier.ROOT -> "Root"
        else -> privilegeLabel(kind(), en).takeIf { kind() != PrivilegeKind.NONE }
            ?: tierLabel(t, en)
    }

    fun shellLabel(en: Boolean = false): String = privilegeLabel(kind(), en)

    fun externalInstalled(ctx: Context): Boolean =
        runCatching {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        }.getOrDefault(false)

    /** Shell runner only (never Root). Prefer [effectiveRunner] for call sites. */
    fun runner(): ShellRunner = when (kind()) {
        PrivilegeKind.EXTERNAL_SHIZUKU -> ExternalShizuku
        PrivilegeKind.EMBEDDED -> EmbeddedPrivilege.shellRunner()
            ?: throw IOException("embedded privilege not running")
        PrivilegeKind.NONE -> throw IOException("no elevated access")
    }

    /** Highest available shell runner: Root when granted, else shell backend. */
    fun effectiveRunner(): ShellRunner =
        if (RootRunner.granted()) RootRunner else runner()

    fun fileService(): IFileService? = when (kind()) {
        PrivilegeKind.EMBEDDED -> EmbeddedPrivilege.asFileService()
        else -> null
    }

    fun embeddedService(): IPrivilegeService? =
        if (kind() == PrivilegeKind.EMBEDDED) EmbeddedPrivilege.service() else null

    fun request(onResult: (Boolean) -> Unit) {
        when {
            kind() != PrivilegeKind.NONE -> onResult(true)
            inputs().let { it.externalInstalled && it.externalRunning } -> ExternalShizuku.request(onResult)
            else -> onResult(false)
        }
    }

    /**
     * Re-authorize the shell layer: force Shizuku permission dialog, or restart embedded
     * PrivilegeServer. Does not touch Root (use [RootRunner.reauthorize]).
     */
    fun reauthorize(onResult: (Boolean) -> Unit) {
        val ctx = appCtx.get()
        when (kind()) {
            PrivilegeKind.EXTERNAL_SHIZUKU -> ExternalShizuku.request(onResult, force = true)
            PrivilegeKind.EMBEDDED -> {
                if (ctx == null) { onResult(false); return }
                Thread {
                    EmbeddedPrivilege.stop()
                    if (RootRunner.granted()) EmbeddedPrivilege.startViaRoot(ctx)
                    else EmbeddedPrivilege.start(ctx)
                    // Brief wait for binder handoff.
                    var tries = 0
                    while (!EmbeddedPrivilege.alive() && tries < 20) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                        tries++
                    }
                    onResult(EmbeddedPrivilege.alive())
                }.start()
            }
            PrivilegeKind.NONE -> {
                when {
                    inputs().let { it.externalInstalled && it.externalRunning } ->
                        ExternalShizuku.request(onResult, force = true)
                    else -> onResult(false)
                }
            }
        }
    }
}
