package com.ghostpanter.files.fs

import android.content.pm.PackageManager
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.privilege.PrivilegeKind
import com.ghostpanter.files.privilege.ElevatedAccess
import com.ghostpanter.files.privilege.EmbeddedPrivilege
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Root via `su`. Each command is its own su process, so a hung command can't wedge the others.
 *
 * Magisk / KernelSU / ColorOS often put `su` outside the app PATH, so we probe several
 * well-known locations and both `su -c` and Magisk-style `su 0 sh -c` invocations.
 */
object RootRunner : ShellRunner {
    /** How to invoke su once a working form is found: either "-c" or "0" (Magisk uid form). */
    private data class SuForm(val binary: String, val mode: String)

    private val resolved = AtomicReference<SuForm?>(null)
    private val grantedFlag = AtomicBoolean(false)

    /** True after the last successful [check]. */
    fun granted(): Boolean = grantedFlag.get()

    /**
     * True if any known su binary is present on disk (PATH "su" always counts as maybe).
     * Does not prompt Magisk.
     */
    fun binaryPresent(): Boolean = candidateBinaries().any { path ->
        if (path == "su") true else File(path).let { it.isFile && it.canExecute() }
    }

    /** PATH `su` first (Magisk/KernelSU usual entry), then absolute paths for ColorOS PATH quirks. */
    private fun candidateBinaries(): List<String> = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
    )

    private fun forms(): List<SuForm> =
        candidateBinaries().flatMap { b -> listOf(SuForm(b, "-c"), SuForm(b, "0")) }

    private fun processFor(form: SuForm, script: String): Process =
        when (form.mode) {
            "0" -> ProcessBuilder(form.binary, "0", "sh", "-c", script).redirectErrorStream(true).start()
            else -> ProcessBuilder(form.binary, "-c", script).redirectErrorStream(true).start()
        }

    override fun start(script: String): Process {
        val form = resolved.get()
            ?: run {
                if (!check(timeoutSec = 8)) throw IOException("su not available")
                resolved.get() ?: throw IOException("su not available")
            }
        return processFor(form, script)
    }

    /**
     * Blocks until the Magisk/KernelSU prompt is answered (or [timeoutSec] elapses).
     * On success, caches the working su form for later [start] calls.
     */
    fun check(timeoutSec: Long = 25): Boolean {
        val cached = resolved.get()
        if (cached != null) {
            if (tryForm(cached, timeoutSec.coerceAtLeast(1))) {
                grantedFlag.set(true)
                return true
            }
            resolved.set(null)
        }
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec.coerceAtLeast(1))
        var firstLive = true
        for (form in forms()) {
            if (form.binary.startsWith('/') && !File(form.binary).exists()) continue
            val leftSec = TimeUnit.NANOSECONDS.toSeconds(deadlineNs - System.nanoTime())
            if (leftSec < 1) break
            // First live candidate gets the Magisk-prompt budget; later ones stay short.
            val slice = if (firstLive) leftSec else leftSec.coerceAtMost(3)
            firstLive = false
            if (tryForm(form, slice)) {
                resolved.set(form)
                grantedFlag.set(true)
                return true
            }
        }
        grantedFlag.set(false)
        return false
    }

    private fun tryForm(form: SuForm, timeoutSec: Long): Boolean = runCatching {
        val r = com.ghostpanter.files.core.Proc.collect(processFor(form, "id"), TimeUnit.SECONDS.toMillis(timeoutSec.coerceAtLeast(1)))
        r.code == 0 && r.stdout.contains("uid=0")
    }.getOrDefault(false)
}

/**
 * External Shizuku only (libshizuku newProcess). Used by [ElevatedAccess] when external is selected;
 * [ShizukuRunner] is the public facade that also routes through embedded privilege.
 */
internal object ExternalShizuku : ShellRunner {
    const val REQUEST_CODE = 4127

    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    override fun start(script: String): Process =
        newProcess.invoke(null, arrayOf("sh", "-c", script), null, null) as Process

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = running() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun request(onResult: (Boolean) -> Unit) {
        if (!running()) { onResult(false); return }
        if (granted()) { onResult(true); return }
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        Shizuku.requestPermission(REQUEST_CODE)
    }
}

/**
 * Elevated shell facade: external Shizuku when granted, otherwise embedded self-ADB privilege.
 * Call sites keep using this name; selection is in [ElevatedAccess].
 */
object ShizukuRunner : ShellRunner {
    const val REQUEST_CODE = ExternalShizuku.REQUEST_CODE

    override fun start(script: String): Process = when (ElevatedAccess.kind()) {
        PrivilegeKind.EXTERNAL_SHIZUKU -> ExternalShizuku.start(script)
        PrivilegeKind.EMBEDDED -> EmbeddedPrivilege.shellRunner()?.start(script)
            ?: throw IOException("embedded privilege not running")
        PrivilegeKind.NONE -> throw IOException("Shizuku / embedded privilege not available")
    }

    fun running(): Boolean = when (ElevatedAccess.kind()) {
        PrivilegeKind.EXTERNAL_SHIZUKU -> true
        PrivilegeKind.EMBEDDED -> EmbeddedPrivilege.alive()
        PrivilegeKind.NONE -> ExternalShizuku.running() || EmbeddedPrivilege.alive()
    }

    fun granted(): Boolean = ElevatedAccess.granted()

    /** Returns true if already granted; otherwise triggers Shizuku permission or reports false (open 内置提权). */
    fun request(onResult: (Boolean) -> Unit) = ElevatedAccess.request(onResult)
}
