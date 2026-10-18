package com.ghostpanter.files.ui

import com.ghostpanter.files.t
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.files.fs.RootRunner
import com.ghostpanter.files.fs.ShizukuRunner
import com.ghostpanter.files.ui.adb.TargetSwitch
import com.ghostpanter.files.ui.adb.adbLabel
import com.ghostpanter.terminal.StreamShellBackend
import com.ghostpanter.terminal.TermBackend
import com.ghostpanter.terminal.TermSessions
import com.ghostpanter.terminal.TermTheme
import com.ghostpanter.terminal.TerminalPanel
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Process-wide terminal sessions (tabs survive leaving the screen) and backend factories. */
object TerminalHub {
    val sessions = TermSessions()

    private fun localUser(): String {
        val uid = android.os.Process.myUid()
        return "u${uid / 100000}_a${uid % 100000 - 10000}"
    }

    private fun hostName(): String = Build.MODEL.trim().replace(Regex("\\s+"), "-").ifEmpty { "android" }

    private fun runOnce(p: Process): String {
        p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(20, TimeUnit.SECONDS)
        return out
    }

    private fun processBackend(label: String, user: String, start: (String) -> Process, separateErr: Boolean): TermBackend {
        var proc: Process? = null
        return StreamShellBackend(
            label, user, hostName(), pty = false,
            open = {
                val p = start("exec sh")
                proc = p
                Triple(p.inputStream, p.outputStream, Closeable { p.destroy() })
            },
            execOnce = { cmd -> runCatching { runOnce(start(cmd)) }.getOrDefault("") },
            stderr = if (separateErr) ({ proc?.errorStream as InputStream? }) else null,
        )
    }

    fun local(): TermBackend = processBackend("sh", localUser(), { s ->
        ProcessBuilder("/system/bin/sh", "-c", s).redirectErrorStream(true).start()
    }, separateErr = false)

    fun root(): TermBackend = processBackend("root", "root", { s -> RootRunner.start(s) }, separateErr = false)

    fun shizuku(): TermBackend = processBackend("shizuku", "shell", { s -> ShizukuRunner.start(s) }, separateErr = true)

    fun adb(serial: String): TermBackend {
        val dev = AdbPool.device(serial)
        val host = (AdbPool.name(serial) ?: serial).trim().replace(Regex("\\s+"), "-")
        return StreamShellBackend(
            adbLabel(serial), "shell", host, pty = true,
            open = { val ch = dev.openPty(); Triple(ch.input, ch.output, ch) },
            execOnce = { cmd -> runCatching { dev.sh(cmd, timeoutMs = 20_000).stdout }.getOrDefault("") },
        )
    }

    /** Shells a new tab can open: local, Root / Shizuku when available, then every saved device. */
    fun factories(): List<Pair<String, () -> TermBackend>> = buildList {
        add(t("本机 sh", "Local sh") to { local() })
        if (RootRunner.granted()) add("Root" to { root() })
        if (ShizukuRunner.granted()) add("Shizuku" to { shizuku() })
        AdbPool.saved().forEach { d -> val s = AdbPool.serialOf(d); add("ADB · " + adbLabel(s) to { adb(s) }) }
    }
}

@Composable
fun rememberTermTheme(vm: MainViewModel): TermTheme {
    val st = vm.g.settings
    val sysDark = isSystemInDarkTheme()
    val dark = when (st.themeMode) { "dark" -> true; "light" -> false; else -> sysDark }
    return TermTheme.of(dark, dark && st.oledBlack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel) {
    val theme = rememberTermTheme(vm)
    val holder = remember { arrayOfNulls<TerminalPanel>(1) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("终端", "Terminal")) },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            TargetSwitch(
                current = vm.termTarget,
                onPick = { serial ->
                    vm.termTarget = serial
                    holder[0]?.openAndFocus(if (serial == null) TerminalHub.local() else TerminalHub.adb(serial))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            AndroidView(
                factory = { c ->
                    TerminalPanel(c, TerminalHub.sessions, { TerminalHub.factories() }, theme).also { p ->
                        holder[0] = p
                        vm.pendingTerminal?.let { serial -> vm.pendingTerminal = null; p.openAndFocus(TerminalHub.adb(serial)) }
                    }
                },
                update = { it.applyTheme(theme) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
