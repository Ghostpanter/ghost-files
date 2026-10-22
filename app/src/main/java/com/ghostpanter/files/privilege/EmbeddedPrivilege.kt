package com.ghostpanter.files.privilege

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.Settings
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.AdbShellRunner
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.privilege.PairEvent
import com.ghostpanter.files.core.privilege.PairPhase
import com.ghostpanter.files.core.privilege.PairState
import com.ghostpanter.files.core.privilege.reducePair
import com.ghostpanter.files.fs.IFileService
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.scrcpy.Adb
import com.ghostpanter.scrcpy.AdbDiscovery
import com.ghostpanter.scrcpy.Devices
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-hosted shell-uid privilege via wireless debugging / root / manual starter.
 * Holds the [IPrivilegeService] binder and an optional local [AdbShellRunner].
 */
object EmbeddedPrivilege {
    private const val PREFS = "embedded_privilege"
    private const val KEY_PORT = "connect_port"
    private const val KEY_AUTO = "auto_start"
    private const val LOCAL_HOST = "127.0.0.1"

    private val ctxRef = AtomicReference<Context?>(null)
    private val binderRef = AtomicReference<IPrivilegeService?>(null)
    private val adbSerial = AtomicReference<String?>(null)
    private val stateRef = AtomicReference(PairState())
    private val listeners = CopyOnWriteArrayList<(PairState) -> Unit>()

    fun init(ctx: Context) {
        ctxRef.set(ctx.applicationContext)
        // Re-attach death recipient if we somehow still hold a binder (process restart clears it).
    }

    fun state(): PairState = stateRef.get()

    fun addListener(l: (PairState) -> Unit) { listeners.add(l) }
    fun removeListener(l: (PairState) -> Unit) { listeners.remove(l) }

    private fun emit(event: PairEvent) {
        val next = reducePair(stateRef.get(), event)
        stateRef.set(next)
        listeners.forEach { runCatching { it(next) } }
    }

    fun alive(): Boolean {
        val s = binderRef.get() ?: return false
        val ok = runCatching { s.asBinder().pingBinder() }.getOrDefault(false)
        if (!ok) binderRef.set(null)
        return ok
    }

    fun service(): IPrivilegeService? = binderRef.get()?.takeIf { alive() }

    fun shellRunner(): ShellRunner? {
        // Prefer the live local ADB shell (streaming, exit codes) when connected.
        val serial = adbSerial.get()
        if (serial != null) {
            return AdbShellRunner(transport = { AdbPool.transport(serial) }, root = false) {
                AdbPool.invalidate(serial)
            }
        }
        service()?.let { svc -> return PrivilegeExecRunner(svc) }
        return null
    }

    fun asFileService(): IFileService? {
        val s = service() ?: return null
        return PrivilegeAsFileService(s)
    }

    fun onBinderReceived(b: IBinder) {
        val svc = IPrivilegeService.Stub.asInterface(b)
        b.linkToDeath({
            binderRef.compareAndSet(svc, null)
            emit(PairEvent.Fail("privilege binder died"))
        }, 0)
        binderRef.set(svc)
        emit(PairEvent.BinderReady)
        ctxRef.get()?.let { grantSecureSettings(it) }
    }

    fun prefs(): SharedPreferences? = ctxRef.get()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedPort(): Int = prefs()?.getInt(KEY_PORT, 0) ?: 0
    fun setSavedPort(port: Int) { prefs()?.edit()?.putInt(KEY_PORT, port)?.apply() }
    fun autoStart(): Boolean = prefs()?.getBoolean(KEY_AUTO, true) ?: true
    fun setAutoStart(v: Boolean) { prefs()?.edit()?.putBoolean(KEY_AUTO, v)?.apply() }

    fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Best-effort: grant ourselves WRITE_SECURE_SETTINGS through the live elevated shell. */
    fun grantSecureSettings(ctx: Context) {
        if (hasSecureSettings(ctx)) return
        val pkg = ctx.packageName
        val cmd = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
        runCatching {
            shellRunner()?.let { r ->
                com.ghostpanter.files.core.Proc.collect(r.start(cmd), 8_000)
            }
        }
    }

    fun enableWirelessDebug(ctx: Context): Boolean {
        if (!hasSecureSettings(ctx)) return false
        return runCatching {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    fun wifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Android 11+: show pairing-code notification and wait. Call [submitPairingCode] when the user replies.
     */
    fun beginPairing(ctx: Context) {
        emit(PairEvent.StartWaitCode)
        PairingNotifier.show(ctx)
    }

    fun submitPairingCode(code: String) {
        val ctx = ctxRef.get() ?: return
        emit(PairEvent.CodeEntered(code))
        if (stateRef.get().phase != PairPhase.PAIRING) return
        Thread({
            try {
                pairAndStart(ctx, code)
            } catch (t: Throwable) {
                emit(PairEvent.Fail(t.message ?: t.javaClass.simpleName))
            }
        }, "embed-pair").start()
    }

    /** Connect using a saved / discovered port and start the server (already paired). */
    fun start(ctx: Context) {
        Thread({
            try {
                stateRef.set(PairState(PairPhase.CONNECTING, "connecting"))
                listeners.forEach { it(stateRef.get()) }
                connectAndStartServer(ctx, savedPort().takeIf { it > 0 })
            } catch (t: Throwable) {
                emit(PairEvent.Fail(t.message ?: t.javaClass.simpleName))
            }
        }, "embed-start").start()
    }

    fun stop() {
        runCatching { service()?.destroy() }
        binderRef.set(null)
        adbSerial.get()?.let { AdbPool.invalidate(it) }
        adbSerial.set(null)
        emit(PairEvent.Stop)
        PairingNotifier.cancel(ctxRef.get())
    }

    /** Root: start PrivilegeServer via su without wireless debugging. */
    fun startViaRoot(ctx: Context) {
        Thread({
            try {
                if (!RootRunner.check(timeoutSec = 20)) {
                    emit(PairEvent.Fail("root not granted")); return@Thread
                }
                stateRef.set(PairState(PairPhase.STARTING_SERVER, "starting via root"))
                listeners.forEach { it(stateRef.get()) }
                val cmd = starterCommand(ctx)
                val r = com.ghostpanter.files.core.Proc.collect(
                    RootRunner.start("($cmd) >/data/local/tmp/ghost-priv.log 2>&1 &"),
                    5_000,
                )
                emit(PairEvent.ServerStarted)
                waitForBinder(15_000)
                if (!alive()) emit(PairEvent.Fail("binder not received (root). see /data/local/tmp/ghost-priv.log"))
            } catch (t: Throwable) {
                emit(PairEvent.Fail(t.message ?: t.javaClass.simpleName))
            }
        }, "embed-root").start()
    }

    /** Command the user runs from a PC / OTG phone on Android 9/10 (or as a manual fallback). */
    fun manualStarterCommand(ctx: Context): String = starterCommand(ctx)

    private fun pairAndStart(ctx: Context, code: String) {
        val endpoints = AdbDiscovery.discoverPairing(ctx, 8_000)
        if (endpoints.isEmpty()) {
            emit(PairEvent.Fail("no pairing service — open 无线调试 → 使用配对码配对设备")); return
        }
        val ep = endpoints[0]
        val adb = Adb.getInstance(ctx)
        adb.pairDevice(ep.host, ep.port, code)
        emit(PairEvent.PairSucceeded)
        PairingNotifier.cancel(ctx)
        connectAndStartServer(ctx, null)
    }

    private fun connectAndStartServer(ctx: Context, preferredPort: Int?) {
        emit(PairEvent.ConnectPort(preferredPort ?: 0))
        var port = preferredPort ?: 0
        if (port <= 0) {
            // Prefer 127.0.0.1 connect endpoint.
            val ep = AdbDiscovery.discoverConnect(ctx, LOCAL_HOST, 10_000)
                ?: AdbDiscovery.discoverConnect(ctx, null, 5_000)
            if (ep == null) {
                emit(PairEvent.Fail("no wireless-debug connect port — is 无线调试 on?")); return
            }
            port = ep.port
        }
        emit(PairEvent.ConnectPort(port))
        setSavedPort(port)
        val serial = Devices.Device(LOCAL_HOST, port).toString()
        runCatching { Devices.upsert(ctx, Devices.Device(LOCAL_HOST, port)) }
        Adb.getInstance(ctx).connect(LOCAL_HOST, port)
        adbSerial.set(serial)
        emit(PairEvent.Connected)
        // Start server through the ADB shell.
        val cmd = starterCommand(ctx)
        val runner = AdbShellRunner(transport = { AdbPool.transport(serial) }, root = false) {
            AdbPool.invalidate(serial)
        }
        // Background the process on the device.
        com.ghostpanter.files.core.Proc.collect(
            runner.start("pkill -f ghost-priv 2>/dev/null; ($cmd) >/data/local/tmp/ghost-priv.log 2>&1 & echo STARTED"),
            8_000,
        )
        emit(PairEvent.ServerStarted)
        waitForBinder(20_000)
        if (!alive()) emit(PairEvent.Fail("binder not received — check 无线调试 / see /data/local/tmp/ghost-priv.log"))
    }

    private fun waitForBinder(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (alive()) return
            Thread.sleep(200)
        }
    }

    fun starterCommand(ctx: Context): String {
        val apk = ctx.applicationInfo.sourceDir
        val pkg = ctx.packageName
        val uid = android.os.Process.myUid()
        // app_process with CLASSPATH; nice-name helps pkill.
        return "CLASSPATH=$apk app_process /system/bin --nice-name=ghost-priv " +
            "com.ghostpanter.files.privilege.PrivilegeServer $pkg $uid"
    }

    /** Called from boot receiver when Wi-Fi is up and auto-start is on. */
    fun tryAutoStart(ctx: Context) {
        if (!autoStart()) return
        if (alive()) return
        if (ElevatedAccess.inputs().let { it.externalInstalled && it.externalRunning && it.externalGranted }) return
        init(ctx)
        if (RootRunner.binaryPresent() && RootRunner.granted()) {
            startViaRoot(ctx); return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (hasSecureSettings(ctx)) {
                if (!wifiConnected(ctx)) return
                if (!enableWirelessDebug(ctx)) {
                    // ROM blocked — leave a clear state for the settings page.
                    emit(PairEvent.Fail("ROM 不允许自动打开无线调试，请手动开启"))
                    return
                }
                Thread.sleep(1500)
            }
            start(ctx)
        }
    }
}

/** ShellRunner that uses IPrivilegeService.exec (no streaming stdin; fine for short scripts). */
private class PrivilegeExecRunner(private val svc: IPrivilegeService) : ShellRunner {
    override fun start(script: String): Process {
        // Materialize exec output into a tiny Process-like wrapper so Proc.collect works.
        val out = runCatching { svc.exec(script) }.getOrElse { "error: ${it.message}" }
        return object : Process() {
            private val input = out.toByteArray().inputStream()
            override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
            override fun getInputStream() = input
            override fun getErrorStream() = ByteArray(0).inputStream()
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() {}
            override fun isAlive(): Boolean = false
        }
    }
}

/** Adapt IPrivilegeService to the existing IFileService AIDL used by ShizukuFs. */
private class PrivilegeAsFileService(private val s: IPrivilegeService) : IFileService.Stub() {
    override fun destroy() { s.destroy() }
    override fun version(): Int = s.version()
    override fun list(path: String): ParcelFileDescriptor = s.list(path)
    override fun stat(path: String): String = s.stat(path)
    override fun openRead(path: String): ParcelFileDescriptor? = s.openRead(path)
    override fun openWrite(path: String, offset: Long): ParcelFileDescriptor? = s.openWrite(path, offset)
    override fun mkdirs(path: String): String = s.mkdirs(path)
    override fun remove(path: String, recursive: Boolean): String = s.remove(path, recursive)
    override fun rename(from: String, to: String): String = s.rename(from, to)
}
