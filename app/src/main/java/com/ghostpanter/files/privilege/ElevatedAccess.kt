package com.ghostpanter.files.privilege

import android.content.Context
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.privilege.PrivilegeInputs
import com.ghostpanter.files.core.privilege.PrivilegeKind
import com.ghostpanter.files.core.privilege.privilegeLabel
import com.ghostpanter.files.core.privilege.selectPrivilege
import com.ghostpanter.files.fs.ExternalShizuku
import com.ghostpanter.files.fs.IFileService
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified elevated (shell-uid) access: prefers granted external Shizuku, else a live embedded
 * PrivilegeServer binder (+ AdbShellRunner). Root stays on RootRunner.
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

    fun kind(): PrivilegeKind = selectPrivilege(inputs())

    fun granted(): Boolean = kind() != PrivilegeKind.NONE

    fun label(en: Boolean = false): String = privilegeLabel(kind(), en)

    fun externalInstalled(ctx: Context): Boolean =
        runCatching {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        }.getOrDefault(false)

    fun runner(): ShellRunner = when (kind()) {
        PrivilegeKind.EXTERNAL_SHIZUKU -> ExternalShizuku
        PrivilegeKind.EMBEDDED -> EmbeddedPrivilege.shellRunner()
            ?: throw IOException("embedded privilege not running")
        PrivilegeKind.NONE -> throw IOException("no elevated access")
    }

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
}
