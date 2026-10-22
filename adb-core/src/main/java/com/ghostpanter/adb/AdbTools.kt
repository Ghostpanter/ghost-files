package com.ghostpanter.adb

import com.ghostpanter.files.core.tr
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Small, testable building blocks for the device tools (info, apps, processes, logcat, activation). */

data class InfoGroup(val title: String, val rows: List<Pair<String, String>>)

object DeviceInfo {
    /** One round trip; sections are delimited by `@@name` lines. */
    val SCRIPT = listOf(
        "echo @@prop; getprop",
        "echo @@battery; dumpsys battery 2>/dev/null",
        "echo @@cpu; cat /proc/cpuinfo 2>/dev/null; echo cores=\$(ls -d /sys/devices/system/cpu/cpu[0-9]* 2>/dev/null | wc -l); " +
            "for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do echo maxfreq=\$(cat \$f 2>/dev/null); done",
        "echo @@mem; cat /proc/meminfo 2>/dev/null",
        "echo @@df; df -k /data 2>/dev/null",
        "echo @@wm; wm size 2>/dev/null; wm density 2>/dev/null",
        "echo @@net; ip -o addr 2>/dev/null",
        "echo @@uptime; cat /proc/uptime 2>/dev/null",
        "echo @@end",
    ).joinToString("\n")

    fun sections(out: String): Map<String, String> {
        val m = LinkedHashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (line in out.lineSequence()) {
            if (line.startsWith("@@")) { cur = StringBuilder().also { m[line.removePrefix("@@").trim()] = it }; continue }
            cur?.append(line)?.append('\n')
        }
        return m.mapValues { it.value.toString() }
    }

    fun props(text: String): Map<String, String> {
        val r = Regex("^\\[(.+?)]: \\[(.*)]$")
        return text.lineSequence().mapNotNull { r.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }.toMap()
    }

    private fun kv(text: String, sep: Char = ':'): Map<String, String> = text.lineSequence()
        .mapNotNull { l -> val i = l.indexOf(sep); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim() }
        .toMap()

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0; var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun parse(out: String): List<InfoGroup> {
        val s = sections(out)
        val p = props(s["prop"].orEmpty())
        val groups = ArrayList<InfoGroup>()
        fun g(title: String, vararg rows: Pair<String, String?>) {
            val r = rows.filter { !it.second.isNullOrBlank() }.map { it.first to it.second!! }
            if (r.isNotEmpty()) groups += InfoGroup(title, r)
        }
        g(tr("设备", "Device"),
            tr("型号", "Model") to listOfNotNull(p["ro.product.brand"], p["ro.product.model"]).joinToString(" ").ifBlank { null },
            tr("厂商", "Manufacturer") to p["ro.product.manufacturer"],
            tr("代号", "Codename") to (p["ro.product.device"] ?: p["ro.product.name"]),
            "Android" to p["ro.build.version.release"]?.let { r -> "$r (API ${p["ro.build.version.sdk"].orEmpty()})" },
            tr("安全补丁", "Security patch") to p["ro.build.version.security_patch"],
            tr("版本号", "Build") to p["ro.build.display.id"],
            tr("指纹", "Fingerprint") to p["ro.build.fingerprint"],
            tr("内核 ABI", "ABIs") to (p["ro.product.cpu.abilist"] ?: p["ro.product.cpu.abi"]),
        )
        val bat = kv(s["battery"].orEmpty())
        val status = when (bat["status"]) { "2" -> tr("充电中", "Charging"); "3" -> tr("放电", "Discharging"); "4" -> tr("未充电", "Not charging"); "5" -> tr("已充满", "Full"); else -> bat["status"] }
        val health = when (bat["health"]) { "2" -> tr("良好", "Good"); "3" -> tr("过热", "Overheat"); "4" -> tr("损坏", "Dead"); "5" -> tr("过压", "Over voltage"); "7" -> tr("过冷", "Cold"); else -> bat["health"] }
        g(tr("电池", "Battery"),
            tr("电量", "Level") to bat["level"]?.let { "$it%" },
            tr("状态", "Status") to status,
            tr("健康", "Health") to health,
            tr("温度", "Temperature") to bat["temperature"]?.toIntOrNull()?.let { String.format(Locale.US, "%.1f °C", it / 10.0) },
            tr("电压", "Voltage") to bat["voltage"]?.toIntOrNull()?.let { "$it mV" },
            tr("供电", "Powered") to listOf("AC", "USB", "Wireless").filter { bat["$it powered"] == "true" }.joinToString(", ").ifBlank { null },
        )
        val cpu = s["cpu"].orEmpty()
        val cores = Regex("^cores=(\\d+)", RegexOption.MULTILINE).find(cpu)?.groupValues?.get(1)
        val maxFreq = Regex("^maxfreq=(\\d+)", RegexOption.MULTILINE).findAll(cpu).mapNotNull { it.groupValues[1].toLongOrNull() }.maxOrNull()
        val hw = kv(cpu)["Hardware"] ?: p["ro.soc.model"]?.let { listOfNotNull(p["ro.soc.manufacturer"], it).joinToString(" ") } ?: p["ro.board.platform"]
        g("CPU",
            tr("芯片", "SoC") to hw,
            tr("核心数", "Cores") to cores,
            tr("最高频率", "Max frequency") to maxFreq?.let { String.format(Locale.US, "%.2f GHz", it / 1_000_000.0) },
        )
        val mem = kv(s["mem"].orEmpty())
        fun kb(k: String) = mem[k]?.removeSuffix("kB")?.trim()?.toLongOrNull()?.times(1024)
        val total = kb("MemTotal"); val avail = kb("MemAvailable")
        g(tr("内存", "Memory"),
            tr("总计", "Total") to total?.let { human(it) },
            tr("可用", "Available") to avail?.let { human(it) },
            "Swap" to kb("SwapTotal")?.takeIf { it > 0 }?.let { t -> "${human(kb("SwapFree") ?: 0)} / ${human(t)}" },
        )
        val dfLine = s["df"].orEmpty().lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+"))
        if (dfLine != null && dfLine.size >= 4) {
            val t = dfLine[1].toLongOrNull()?.times(1024); val u = dfLine[2].toLongOrNull()?.times(1024); val a = dfLine[3].toLongOrNull()?.times(1024)
            g(tr("存储（/data）", "Storage (/data)"),
                tr("总计", "Total") to t?.let { human(it) }, tr("已用", "Used") to u?.let { human(it) }, tr("可用", "Free") to a?.let { human(it) })
        }
        val wm = kv(s["wm"].orEmpty())
        g(tr("屏幕", "Display"),
            tr("分辨率", "Resolution") to (wm["Override size"]?.let { o -> "$o (${tr("物理", "physical")} ${wm["Physical size"].orEmpty()})" } ?: wm["Physical size"]),
            "DPI" to (wm["Override density"]?.let { o -> "$o (${tr("物理", "physical")} ${wm["Physical density"].orEmpty()})" } ?: wm["Physical density"]),
        )
        val addrs = s["net"].orEmpty().lineSequence().mapNotNull { l ->
            val m = Regex("^\\d+:\\s+(\\S+)\\s+(inet6?)\\s+(\\S+)").find(l.trim()) ?: return@mapNotNull null
            val iface = m.groupValues[1]; val a = m.groupValues[3].substringBefore('/')
            if (iface == "lo" || a.startsWith("fe80")) null else iface to a
        }.toList()
        g(tr("网络", "Network"), *addrs.map { it.first to it.second }.toTypedArray<Pair<String, String?>>(),
            tr("WLAN 主机名", "Hostname") to p["net.hostname"])
        val up = s["uptime"].orEmpty().trim().substringBefore(' ').toDoubleOrNull()?.toLong()
        if (up != null) g(tr("系统", "System"), tr("已运行", "Uptime") to String.format(Locale.US, "%dd %02d:%02d", up / 86400, (up % 86400) / 3600, (up % 3600) / 60))
        return groups
    }

    fun model(out: String): String? {
        val p = props(sections(out)["prop"].orEmpty())
        return p["ro.product.model"]?.let { m -> p["ro.product.brand"]?.let { b -> if (m.startsWith(b, true)) m else "$b $m" } ?: m }
    }
}

// ---------------- remote apps ----------------

data class RemoteApp(
    val pkg: String,
    val apkPath: String,
    val versionCode: Long,
    val uid: Int,
    val system: Boolean,
    val disabled: Boolean,
    /** Uninstalled for user 0 but still present on the system image (restorable). */
    val uninstalled: Boolean = false,
)

object RemoteApps {
    val LIST_SCRIPT = listOf(
        "echo @@all; pm list packages -f -U --show-versioncode 2>/dev/null || pm list packages -f",
        "echo @@user; pm list packages -3",
        "echo @@disabled; pm list packages -d",
        "echo @@any; pm list packages -u",
        "echo @@end",
    ).joinToString("\n")

    fun parse(out: String): List<RemoteApp> {
        val s = DeviceInfo.sections(out)
        fun names(k: String) = s[k].orEmpty().lineSequence().map { it.trim().removePrefix("package:") }.filter { it.isNotEmpty() }.toSet()
        val user = names("user"); val disabled = names("disabled")
        val apps = LinkedHashMap<String, RemoteApp>()
        for (raw in s["all"].orEmpty().lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            var body = line.removePrefix("package:")
            var vc = 0L; var uid = -1
            Regex("\\s+uid:(\\d+)").find(body)?.let { uid = it.groupValues[1].toInt(); body = body.removeRange(it.range) }
            Regex("\\s+versionCode:(\\d+)").find(body)?.let { vc = it.groupValues[1].toLong(); body = body.removeRange(it.range) }
            body = body.trim()
            val eq = body.lastIndexOf('=')
            if (eq <= 0) continue
            val pkg = body.substring(eq + 1); val path = body.substring(0, eq)
            apps[pkg] = RemoteApp(pkg, path, vc, uid, system = pkg !in user, disabled = pkg in disabled)
        }
        for (pkg in names("any")) if (pkg !in apps) apps[pkg] = RemoteApp(pkg, "", 0, -1, system = true, disabled = false, uninstalled = true)
        return apps.values.toList()
    }

    fun q(s: String) = AdbDevice.q(s)
    fun uninstall(a: RemoteApp) = if (a.system) "pm uninstall -k --user 0 ${q(a.pkg)}" else "pm uninstall ${q(a.pkg)}"
    fun uninstallUser0(pkg: String) = "pm uninstall -k --user 0 ${q(pkg)}"
    fun restore(pkg: String) = "cmd package install-existing --user 0 ${q(pkg)} 2>/dev/null || pm install-existing ${q(pkg)}"
    fun disable(pkg: String) = "pm disable-user --user 0 ${q(pkg)}"
    fun enable(pkg: String) = "pm enable --user 0 ${q(pkg)} 2>/dev/null || pm enable ${q(pkg)}"
    fun clear(pkg: String) = "pm clear ${q(pkg)}"
    fun forceStop(pkg: String) = "am force-stop ${q(pkg)}"
    fun launch(pkg: String) = "c=\$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${q(pkg)} 2>/dev/null | tail -n 1); " +
        "case \"\$c\" in */*) am start -n \"\$c\" ;; *) monkey -p ${q(pkg)} -c android.intent.category.LAUNCHER 1 ;; esac"
    fun paths(pkg: String) = "pm path ${q(pkg)}"
    fun parsePaths(out: String) = out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toList()
    /** Install source of [pkg] on the target (dumpsys). */
    fun source(dev: AdbDevice, pkg: String): com.ghostpanter.files.core.apk.InstallSource.Record? =
        com.ghostpanter.files.core.apk.InstallSource.parseDumpsys(dev.sh(com.ghostpanter.files.core.apk.InstallSource.dumpsys(pkg), timeoutMs = 30_000).stdout, pkg)

    fun hasPlay(dev: AdbDevice): Boolean = dev.sh("pm path ${com.ghostpanter.files.core.apk.InstallSource.PLAY}", timeoutMs = 15_000).stdout.contains("package:")

    fun versionName(pkg: String) = "dumpsys package ${q(pkg)} | grep -m1 versionName"

    class Apk(val name: String, val size: Long, val open: () -> InputStream)

    /**
     * Installs one APK, or a split set through a pm session, after pushing to /data/local/tmp.
     * [progress] gets (bytesDone, bytesTotal); returns pm's output.
     */
    fun install(dev: AdbDevice, apks: List<Apk>, progress: (Long, Long) -> Unit = { _, _ -> }, installer: String? = null): String {
        require(apks.isNotEmpty())
        val dir = "/data/local/tmp/gf-install-" + java.lang.Long.toHexString(System.nanoTime())
        val total = apks.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        try {
            dev.text("mkdir -p $dir")
            val remote = apks.mapIndexed { i, a ->
                val r = "$dir/${i}_" + a.name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".apk")) it else "$it.apk" }
                a.open().use { src ->
                    dev.openSend(r).use { out ->
                        val b = ByteArray(64 * 1024)
                        while (true) { val n = src.read(b); if (n < 0) break; out.write(b, 0, n); done += n; progress(done, total) }
                    }
                }
                r
            }
            val out = if (remote.size == 1) {
                dev.sh(com.ghostpanter.files.core.apk.InstallSource.pmInstall(remote[0], installer), timeoutMs = 600_000).stdout
            } else {
                val created = dev.sh(com.ghostpanter.files.core.apk.InstallSource.pmCreate(total, installer, allowTest = true), timeoutMs = 60_000).stdout
                val sid = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1) ?: throw IOException(created.trim())
                for ((i, r) in remote.withIndex()) {
                    val w = dev.sh("pm install-write -S ${apks[i].size} $sid split_$i.apk ${q(r)}", timeoutMs = 300_000)
                    if (!w.stdout.contains("Success")) { dev.sh("pm install-abandon $sid"); throw IOException(w.stdout.trim()) }
                }
                dev.sh("pm install-commit $sid", timeoutMs = 600_000).stdout
            }
            if (!out.contains("Success")) {
                val msg = out.trim().ifEmpty { "install failed" }
                throw IOException(com.ghostpanter.files.core.apk.InstallSource.explain(msg)?.let { "$it\n$msg" } ?: msg)
            }
            return out.trim()
        } finally {
            runCatching { dev.sh("rm -rf $dir", timeoutMs = 15_000) }
        }
    }
}

// ---------------- processes ----------------

data class ProcInfo(val pid: Int, val ppid: Int, val user: String, val rssKb: Long, val cpu: Float, val state: String, val name: String) {
    /** App package for force-stop (app uids only). */
    val pkg: String? get() = if (Regex("^u\\d+_a\\d+$").matches(user) || user.startsWith("u0_i")) name.substringBefore(':').takeIf { it.contains('.') } else null
}

object Processes {
    const val SCRIPT = "ps -A -o PID,PPID,USER,RSS,%CPU,S,NAME 2>/dev/null || ps -A 2>/dev/null || ps"

    fun parse(out: String): List<ProcInfo> {
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        val head = lines[0].split(Regex("\\s+")).map { it.uppercase(Locale.US) }
        fun col(vararg n: String) = n.firstNotNullOfOrNull { k -> head.indexOf(k).takeIf { it >= 0 } } ?: -1
        val iPid = col("PID"); val iPpid = col("PPID"); val iUser = col("USER", "UID"); val iRss = col("RSS")
        val iCpu = col("%CPU", "CPU%"); val iS = col("S", "STAT"); val iName = col("NAME", "CMD", "COMMAND", "ARGS")
        if (iPid < 0) return emptyList()
        return lines.drop(1).mapNotNull { l ->
            val f = l.split(Regex("\\s+"))
            if (f.size < head.size - 1) return@mapNotNull null
            val name = if (iName >= 0 && iName < f.size) f.subList(iName, f.size).joinToString(" ") else f.last()
            val pid = f.getOrNull(iPid)?.toIntOrNull() ?: return@mapNotNull null
            ProcInfo(pid, f.getOrNull(iPpid)?.toIntOrNull() ?: 0, f.getOrNull(iUser).orEmpty(),
                f.getOrNull(iRss)?.toLongOrNull() ?: 0, f.getOrNull(iCpu)?.toFloatOrNull() ?: 0f, f.getOrNull(iS).orEmpty(), name)
        }.filter { it.name != "ps" }
    }

    fun kill(pid: Int, signal: Int = 9) = "kill -$signal $pid"
}

// ---------------- logcat ----------------

data class LogLine(val time: String, val pid: Int, val tid: Int, val level: Char, val tag: String, val msg: String)

object Logcat {
    const val LEVELS = "VDIWEF"
    private val RX = Regex("^(\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFS])\\s+(.*?)\\s*: (.*)$")
    fun command(tail: Int = 2000) = "exec logcat -v threadtime -T $tail"
    fun parse(line: String): LogLine? = RX.find(line)?.let { m ->
        LogLine(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4][0], m.groupValues[5], m.groupValues[6])
    }
    fun matches(l: LogLine, minLevel: Char, tag: String, pid: Int?, text: String): Boolean {
        if (LEVELS.indexOf(l.level).coerceAtLeast(0) < LEVELS.indexOf(minLevel).coerceAtLeast(0)) return false
        if (tag.isNotBlank() && !l.tag.contains(tag.trim(), ignoreCase = true)) return false
        if (pid != null && l.pid != pid) return false
        if (text.isNotBlank() && !l.msg.contains(text, ignoreCase = true)) return false
        return true
    }
    fun format(l: LogLine) = "${l.time} ${l.pid} ${l.tid} ${l.level} ${l.tag}: ${l.msg}"
}

// ---------------- one-tap activation ----------------

/**
 * Activation commands for well-known "ADB-privileged" apps. Only commands that are published by
 * the developers (docs, official site or source) are included; [sources] lists where each came from.
 */
data class Activation(
    val id: String,
    val name: String,
    val packages: List<String>,
    val commands: List<Pair<String, String>>,
    val sources: List<String>,
    val note: String = "",
)

object Activations {
    private fun shizukuStarter(): String =
        "p=\$(pm path moe.shizuku.privileged.api | head -n 1); p=\${p#package:}; d=\${p%/*}/lib; " +
            "for a in arm64 arm x86_64 x86; do [ -f \$d/\$a/libshizuku.so ] && exec \$d/\$a/libshizuku.so; done; " +
            "f=/sdcard/Android/data/moe.shizuku.privileged.api/start.sh; [ -f \$f ] && exec sh \$f; echo 'Shizuku starter not found'; exit 1"

    fun all(): List<Activation> = listOf(
        Activation("shizuku", "Shizuku", listOf("moe.shizuku.privileged.api"),
            listOf(tr("启动 Shizuku", "Start Shizuku") to shizukuStarter()),
            listOf("https://shizuku.rikka.app/guide/setup/ (v11.2–13.5: sh …/start.sh)",
                "https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/starter/Starter.kt (13.6+: <nativeLibraryDir>/libshizuku.so)"),
            tr("13.6 起官方移除了 start.sh，改为执行应用 native 库目录下的 libshizuku.so；此处自动定位。", "13.6+ removed start.sh; the starter is libshizuku.so in the app's native lib dir (located automatically).")),
        Activation("icebox", tr("冰箱 Ice Box", "Ice Box"), listOf("com.catchingnow.icebox"),
            listOf(tr("普通 ADB 模式（重启后需重新激活）", "Simple ADB mode (redo after reboot)") to "sh /sdcard/Android/data/com.catchingnow.icebox/files/start.sh",
                tr("设备管理员模式（需先删除所有账户 / 多用户）", "Device owner (remove all accounts / users first)") to "dpm set-device-owner com.catchingnow.icebox/.receiver.DPMReceiver"),
            listOf("https://iceboxdoc.catchingnow.com/ (github.com/heruoxin/Ice-Box-Docs)")),
        Activation("brevent", tr("黑阈 Brevent", "Brevent"), listOf("me.piebridge.brevent"),
            listOf(tr("启动黑阈服务", "Start Brevent server") to
                "output=\$(pm path me.piebridge.brevent); export CLASSPATH=\${output#*:}; app_process /system/bin me.piebridge.brevent.server.BreventServer bootstrap; /system/bin/sh /data/local/tmp/brevent.sh"),
            listOf("https://brevent.sh/")),
        Activation("installlion", tr("安装狮", "Install Lion"), listOf("com.modosa.apkinstaller", "com.modosa.dpmapkinstaller"),
            listOf(tr("设为设备管理员（安装狮）", "Set device owner (Install Lion)") to "dpm set-device-owner com.modosa.apkinstaller/.receiver.AdminReceiver",
                tr("设为设备管理员（安装狮-DPM）", "Set device owner (Install Lion DPM)") to "dpm set-device-owner com.modosa.dpmapkinstaller/.receiver.AdminReceiver"),
            listOf("https://github.com/dadaewq/Install-Lion", "https://github.com/dadaewq/Install-Lion-DPM")),
        Activation("scene", "Scene", listOf("com.omarea.vtools"), emptyList(), emptyList(),
            tr("未收录：Scene 未在官方文档或源码中公开激活命令，请按应用内提示操作（也可在 Scene 中选择 Shizuku 方式）。",
                "Not included: Scene has no officially documented command; follow the in-app instructions (or use its Shizuku mode).")),
        Activation("permissiondog", tr("权限狗", "Permission Dog"), listOf("com.web1n.permissiondog"), emptyList(),
            listOf("https://github.com/web1n/permissiondog_docs"),
            tr("未收录：官方帮助只说明支持 Root / 设备管理员 / 网络 ADB / Shizuku 模式，未公开命令。可先激活 Shizuku，或在「概览」开启网络 ADB（5555）。",
                "Not included: the official FAQ lists Root / device owner / network ADB / Shizuku modes without a command. Start Shizuku, or enable network ADB (5555) in Overview.")),
    )
}

// ---------------- reboot ----------------

object Reboot {
    /** label to ADB service. `fastboot` reboots into userspace fastbootd (Android 10+). */
    fun targets(): List<Pair<String, String>> = listOf(
        tr("重启系统", "Reboot") to "reboot:",
        tr("重启到 Recovery", "Reboot to recovery") to "reboot:recovery",
        tr("重启到 Bootloader", "Reboot to bootloader") to "reboot:bootloader",
        tr("重启到 Fastbootd", "Reboot to fastbootd") to "reboot:fastboot",
    )
}
