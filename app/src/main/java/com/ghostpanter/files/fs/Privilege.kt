package com.ghostpanter.files.fs

import android.content.pm.PackageManager
import com.ghostpanter.files.core.ShellRunner
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** Root via `su -c`. Each command is its own su process, so a hung command can't wedge the others. */
object RootRunner : ShellRunner {
    override fun start(script: String): Process = ProcessBuilder("su", "-c", script).start()

    /** Blocks until the su prompt is answered (or times out). */
    fun check(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        p.outputStream.close()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroy(); return false }
        p.inputStream.bufferedReader().readText().contains("uid=0")
    }.getOrDefault(false)
}

/** adb-shell-level access through Shizuku (no root needed): /Android/data, /Android/obb, /data/local/tmp... */
object ShizukuRunner : ShellRunner {
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

    /** Returns true if already granted; otherwise triggers the Shizuku permission prompt. */
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
