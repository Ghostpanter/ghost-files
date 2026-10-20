package com.ghostpanter.files.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.DeviceInfo
import com.ghostpanter.adb.LogLine
import com.ghostpanter.adb.ProcInfo
import com.ghostpanter.adb.RemoteApp
import com.ghostpanter.files.ui.adb.ActivationPane
import com.ghostpanter.files.ui.adb.AdbToolsFrame
import com.ghostpanter.files.ui.adb.DevState
import com.ghostpanter.files.ui.adb.DeviceCallbacks
import com.ghostpanter.files.ui.adb.DevicesContent
import com.ghostpanter.files.ui.adb.LogcatPane
import com.ghostpanter.files.ui.adb.OverviewPane
import com.ghostpanter.files.ui.adb.ProcessesPane
import com.ghostpanter.files.ui.adb.RemoteAppsPane
import com.ghostpanter.terminal.TermBackend
import com.ghostpanter.terminal.TermSessions
import com.ghostpanter.terminal.TermSink
import com.ghostpanter.terminal.TermTheme
import com.ghostpanter.terminal.TerminalPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Devices / ADB tools / terminal screens with fake data (no device, no emulator). */
@RunWith(Parameterized::class)
class AdbScreenshotTest(private val name: String, private val device: DeviceConfig) {

    companion object {
        init { java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> = listOf(
            arrayOf("phone", DeviceConfig.PIXEL_5.copy(locale = "zh-rCN")),
            arrayOf("phone_dark", DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NIGHT, locale = "zh-rCN")),
            arrayOf("tablet", DeviceConfig.PIXEL_C.copy(orientation = ScreenOrientation.LANDSCAPE, locale = "zh-rCN")),
        )

        const val S1 = "192.168.1.23:41235"
        const val S2 = "192.168.1.40:5555"
    }

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 1.0)

    private val dark get() = device.nightMode == NightMode.NIGHT
    private val tablet get() = name == "tablet"

    private val registryOwner = object : androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {}
        }
    }

    @Composable
    private fun Shell(current: Screen, content: @Composable () -> Unit) = GhostTheme(dark = dark) {
        AdbPool.setName(S1, "Xiaomi 23049RAD8C"); AdbPool.setName(S2, "Pixel 8 Pro")
        androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner) { Body(current, content) }
    }

    @Composable
    private fun Body(current: Screen, content: @Composable () -> Unit) {
        if (tablet) Row {
            GhostNavRail(current = current, activeJobs = 0, panelOpen = false, onMenu = {}, onPick = {})
            Box(Modifier.weight(1f)) { content() }
        } else content()
    }

    private fun tools(tab: Int, body: @Composable () -> Unit) = paparazzi.snapshot {
        Shell(Screen.AdbTools(S1, tab)) { AdbToolsFrame(S1, tab, {}, {}, {}) { body() } }
    }

    @Test fun devices() = paparazzi.snapshot {
        Shell(Screen.Devices) {
            DevicesContent(listOf(S1, S2), listOf(S1 to DevState.ONLINE, S2 to DevState.OFFLINE),
                DeviceCallbacks({}, {}, {}, { _, _, _ -> }, {}, {}))
        }
    }

    @Test fun overview() = tools(0) { OverviewPane({}, S1, previewInfo = DeviceInfo.parse(INFO)) }

    @Test fun apps() = tools(1) {
        RemoteAppsPane({}, S1, preview = listOf(
            RemoteApp("com.tencent.mm", "/data/app/~~a/com.tencent.mm-1/base.apk", 2860, 10231, false, false),
            RemoteApp("com.eg.android.AlipayGphone", "/data/app/~~b/x/base.apk", 784, 10198, false, false),
            RemoteApp("tv.danmaku.bili", "/data/app/~~c/x/base.apk", 8120300, 10260, false, false),
            RemoteApp("com.ss.android.ugc.aweme", "/data/app/~~d/x/base.apk", 310101, 10301, false, true),
            RemoteApp("com.netease.cloudmusic", "/data/app/~~e/x/base.apk", 9001, 10245, false, false),
            RemoteApp("moe.shizuku.privileged.api", "/data/app/~~f/x/base.apk", 1086, 10250, false, false),
        ), previewLabels = mapOf("com.tencent.mm" to "微信", "com.eg.android.AlipayGphone" to "支付宝", "tv.danmaku.bili" to "哔哩哔哩",
            "com.ss.android.ugc.aweme" to "抖音", "com.netease.cloudmusic" to "网易云音乐", "moe.shizuku.privileged.api" to "Shizuku"))
    }

    @Test fun processes() = tools(2) {
        ProcessesPane({}, S1, preview = listOf(
            ProcInfo(1, 0, "root", 12_000, 0f, "S", "init"),
            ProcInfo(1532, 700, "system", 380_000, 4.2f, "S", "system_server"),
            ProcInfo(4321, 700, "u0_a231", 512_000, 12.5f, "S", "com.tencent.mm"),
            ProcInfo(4388, 700, "u0_a231", 120_000, 0.8f, "S", "com.tencent.mm:push"),
            ProcInfo(5120, 700, "u0_a260", 300_000, 6.1f, "S", "tv.danmaku.bili"),
            ProcInfo(912, 1, "shell", 6_000, 0.3f, "S", "adbd"),
        ))
    }

    @Test fun activation() = tools(3) {
        ActivationPane({}, S1, previewInstalled = setOf("moe.shizuku.privileged.api", "com.catchingnow.icebox"))
    }

    @Test fun logcat() = tools(4) {
        LogcatPane({}, S1, preview = listOf(
            LogLine("10-18 10:20:30.123", 1532, 1550, 'I', "ActivityManager", "Start proc 4321:com.tencent.mm/u0a231 for activity"),
            LogLine("10-18 10:20:30.240", 4321, 4321, 'D', "MicroMsg", "onCreate"),
            LogLine("10-18 10:20:31.002", 1532, 1601, 'W', "WindowManager", "Slow dispatch took 320ms"),
            LogLine("10-18 10:20:31.420", 5120, 5133, 'E', "BiliPlayer", "decoder error -38, falling back to software"),
            LogLine("10-18 10:20:32.010", 912, 912, 'V', "adbd", "shell: opened"),
        ))
    }

    private class FakeBackend(val out: (String) -> Unit) : TermBackend {
        override val label = "Xiaomi 23049RAD8C"
        override var user = "shell"
        override var host = "Xiaomi-23049RAD8C"
        override val pty = true
        override fun start(sink: TermSink, cols: Int, rows: Int) {}
        override fun sendLine(line: String) {
            val r = when (line) {
                "ls" -> "Alarms   DCIM      Documents  Movies  Notifications  Podcasts\nAndroid  Download  Music      Pictures  Recordings     Ringtones\n"
                "getprop ro.product.model" -> "23049RAD8C\n"
                "pm list packages -3 | head -3" -> "package:com.tencent.mm\npackage:tv.danmaku.bili\npackage:moe.shizuku.privileged.api\n"
                "ls /data" -> "\u001b[31mls: /data: Permission denied\u001b[0m\n"
                else -> ""
            }
            out(r + "\u001eGFP" + (if (line == "ls /data") 1 else 0) + ";/sdcard\u001e")
        }
        override fun sendRaw(bytes: ByteArray) {}
        override fun interrupt() {}
        override fun eof() {}
        override fun exec(cmd: String, cwd: String?) = ""
        override fun close() {}
    }

    @Test fun terminal() = paparazzi.snapshot {
        Shell(Screen.Terminal) {
            val theme = TermTheme.of(dark, false)
            TerminalChrome(onBack = {}, target = {
                com.ghostpanter.files.ui.adb.TargetSwitch(current = S1, onPick = {}, modifier = Modifier.fillMaxWidth(), devices = listOf(S1))
            }) { m -> AndroidView({ c ->
                val sessions = TermSessions()
                var s: com.ghostpanter.terminal.TermSession? = null
                s = com.ghostpanter.terminal.TermSession(FakeBackend { s!!.feedNow(it) }, theme)
                sessions.add(s)
                s.feedNow("\u001eGFPID100\u001e\u001eGFP0;/sdcard\u001e")
                for (cmd in listOf("ls", "getprop ro.product.model", "pm list packages -3 | head -3", "ls /data")) { s.setInput(cmd); s.submit() }
                s.setInput("cd Dow")
                TerminalPanel(c, sessions, { emptyList() }, theme)
            }, m) }
        }
    }
}

private val INFO = """
@@prop
[ro.product.brand]: [Xiaomi]
[ro.product.model]: [23049RAD8C]
[ro.product.manufacturer]: [Xiaomi]
[ro.product.device]: [houji]
[ro.build.version.release]: [14]
[ro.build.version.sdk]: [34]
[ro.build.version.security_patch]: [2026-09-01]
[ro.build.display.id]: [UKQ1.231003.002]
[ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a,armeabi]
@@battery
  AC powered: false
  USB powered: true
  status: 2
  health: 2
  level: 87
  voltage: 4312
  temperature: 312
@@cpu
Hardware	: Qualcomm SM8550
cores=8
maxfreq=3187200
@@mem
MemTotal:       11788840 kB
MemAvailable:    5242880 kB
@@df
Filesystem      1K-blocks     Used Available Use% Mounted on
/dev/block/dm-48 229117412 81234567 147882845  36% /data
@@wm
Physical size: 1440x3200
Physical density: 560
@@net
47: wlan0    inet 192.168.1.23/24 brd 192.168.1.255 scope global wlan0
@@uptime
93784.12 300000.00
@@end
"""
