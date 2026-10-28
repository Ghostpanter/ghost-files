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
import com.ghostpanter.files.core.privilege.PrivilegeStarter
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
            if (binderRef.compareAndSet(svc, null)) emit(PairEvent.Fail("内置提权服务已退出（无线调试关闭或进程被系统结束），请重新启动"))
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
                connectAndStartServer(ctx, savedPort().takeIf { it > 0 }, allowRediscover = true)
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
                    emit(PairEvent.Fail("未获得 Root 授权")); return@Thread
                }
                stateRef.set(PairState(PairPhase.STARTING_SERVER, "starting via root"))
                listeners.forEach { it(stateRef.get()) }
                launchAndAwait(ctx, RootRunner, "Root")
            } catch (t: Throwable) {
                emit(PairEvent.Fail(t.message ?: t.javaClass.simpleName))
            }
        }, "embed-root").start()
    }

    /** Command the user runs from a PC / OTG phone on Android 9/10 (or as a manual fallback). */
    fun manualStarterCommand(ctx: Context): String = starterCommand(ctx)

    /**
     * Writes a starter script to the app's external files dir (readable by `adb shell`) and returns the
     * device path, e.g. /storage/emulated/0/Android/data/<pkg>/files/start-priv.sh. Regenerated every time
     * because the APK path changes on each update.
     */
    fun writeStarterFile(ctx: Context): String? = runCatching {
        val dir = ctx.getExternalFilesDir(null) ?: return null
        val f = File(dir, "start-priv.sh")
        f.writeText(starterScript(ctx, showLog = true))
        f.absolutePath.replace(Regex("^/storage/emulated/0/"), "/sdcard/")
    }.getOrNull()

    fun starterScript(ctx: Context, showLog: Boolean = false): String =
        PrivilegeStarter.script(ctx.applicationInfo.sourceDir, ctx.packageName, android.os.Process.myUid(), showLog)

    private fun pairAndStart(ctx: Context, code: String) {
        val endpoints = AdbDiscovery.discoverPairing(ctx, 8_000)
        if (endpoints.isEmpty()) {
            emit(PairEvent.Fail("找不到配对服务：请在「无线调试 → 使用配对码配对设备」弹窗打开时输入配对码")); return
        }
        val ep = endpoints[0]
        val adb = Adb.getInstance(ctx)
        adb.pairDevice(ep.host, ep.port, code)
        emit(PairEvent.PairSucceeded)
        PairingNotifier.cancel(ctx)
        connectAndStartServer(ctx, null)
    }

    private fun connectAndStartServer(ctx: Context, preferredPort: Int?, allowRediscover: Boolean = false) {
        emit(PairEvent.ConnectPort(preferredPort ?: 0))
        var port = preferredPort ?: 0
        if (port <= 0) {
            port = discoverConnectPort(ctx) ?: run {
                emit(PairEvent.Fail("找不到无线调试连接端口：请确认「开发者选项 → 无线调试」已打开且连着 Wi‑Fi")); return
            }
        }
        emit(PairEvent.ConnectPort(port))
        try {
            Adb.getInstance(ctx).connect(LOCAL_HOST, port)
        } catch (t: Throwable) {
            // Wireless-debug port changes every time it is toggled; a saved port goes stale.
            val fresh = if (allowRediscover) discoverConnectPort(ctx)?.takeIf { it != port } else null
            if (fresh == null) {
                emit(PairEvent.Fail("连接无线调试端口 $port 失败：${t.message ?: t.javaClass.simpleName}。未配对请点「配对并启动」"))
                return
            }
            port = fresh
            emit(PairEvent.ConnectPort(port))
            Adb.getInstance(ctx).connect(LOCAL_HOST, port)
        }
        setSavedPort(port)
        val serial = Devices.Device(LOCAL_HOST, port).toString()
        runCatching { Devices.upsert(ctx, Devices.Device(LOCAL_HOST, port)) }
        adbSerial.set(serial)
        emit(PairEvent.Connected)
        val runner = AdbShellRunner(transport = { AdbPool.transport(serial) }, root = false) {
            AdbPool.invalidate(serial)
        }
        launchAndAwait(ctx, runner, "无线调试")
    }

    private fun discoverConnectPort(ctx: Context): Int? =
        (AdbDiscovery.discoverConnect(ctx, LOCAL_HOST, 10_000)
            ?: AdbDiscovery.discoverConnect(ctx, null, 5_000))?.port

    /**
     * Runs the detached starter through [runner] (adb shell or su), waits for the binder and, on failure,
     * reads ghost-priv.log back through the same runner so the UI can show what actually happened.
     */
    private fun launchAndAwait(ctx: Context, runner: ShellRunner, via: String) {
        val out = runCatching {
            com.ghostpanter.files.core.Proc.collect(runner.start(starterScript(ctx)), 10_000).stdout
        }.getOrElse { t ->
            emit(PairEvent.Fail("$via 执行启动脚本失败：${t.message ?: t.javaClass.simpleName}")); return
        }
        if (!out.contains(PrivilegeStarter.STARTED)) {
            emit(PairEvent.Fail("$via 启动脚本未执行完：${out.trim().ifEmpty { "无输出（shell 会话被中断）" }.take(300)}"))
            return
        }
        emit(PairEvent.ServerStarted)
        waitForBinder(25_000)
        if (alive()) return
        val diag = runCatching {
            com.ghostpanter.files.core.Proc.collect(runner.start(PrivilegeStarter.diagnoseScript()), 6_000).stdout
        }.getOrNull()
        val d = PrivilegeStarter.diagnose(diag, via)
        emit(PairEvent.Fail(d.message, d.logTail))
    }

    private fun waitForBinder(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (alive()) return
            Thread.sleep(200)
        }
    }

    /** Foreground command (shows errors directly in a PC terminal). The trailing number is this app's UID, not a port. */
    fun starterCommand(ctx: Context): String =
        PrivilegeStarter.foregroundCommand(ctx.applicationInfo.sourceDir, ctx.packageName, android.os.Process.myUid())

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
