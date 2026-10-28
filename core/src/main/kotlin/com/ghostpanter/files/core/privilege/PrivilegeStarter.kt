package com.ghostpanter.files.core.privilege

/**
 * Builds the shell script that launches `app_process … PrivilegeServer` as shell/root, and turns the
 * server log into an actionable (Chinese-first) failure message.
 *
 * Why a script and not `pkill -f ghost-priv; (cmd) &`:
 *  - adbd runs `shell:<cmd>` as `sh -c "<cmd>"`, so that shell's own cmdline contains "ghost-priv";
 *    `pkill -f ghost-priv` killed its own parent shell before the server was ever launched.
 *  - A plain `&` child stays in the adb shell session and can be killed when the stream closes;
 *    the server is now detached with `setsid` (when present) + `nohup`, stdin from /dev/null.
 *  - A log left by a root start is root-owned; shell could not truncate it, so the redirect failed and
 *    nothing ran. The log is removed first (/data/local/tmp is shell-owned, so shell may unlink it).
 */
object PrivilegeStarter {
    const val LOG = "/data/local/tmp/ghost-priv.log"
    const val NICE_NAME = "ghost-priv"
    const val SERVER_CLASS = "com.ghostpanter.files.privilege.PrivilegeServer"
    const val STARTED = "GHOST_PRIV_STARTED"

    /** Single-quote for sh. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Foreground command (useful from a PC terminal to see errors directly). Contains no `$`. */
    fun foregroundCommand(apk: String, pkg: String, uid: Int): String =
        "CLASSPATH=$apk app_process /system/bin --nice-name=$NICE_NAME $SERVER_CLASS $pkg $uid"

    /**
     * Detached starter. [showLog] (manual use from a PC) waits a few seconds and prints the log so the user
     * sees the result in their terminal.
     */
    fun script(apk: String, pkg: String, uid: Int, showLog: Boolean = false): String = buildString {
        append("L=$LOG\n")
        // Kill an older server by process name (never by full cmdline: that matches this very shell).
        append("for p in \$(pidof $NICE_NAME 2>/dev/null); do kill \$p 2>/dev/null; done\n")
        append("rm -f \$L 2>/dev/null\n")
        append("echo \"starter: \$(id) sdk=\$(getprop ro.build.version.sdk) uid_arg=$uid\" >\$L 2>/dev/null\n")
        append("if [ ! -r ${q(apk)} ]; then echo \"starter: apk not readable: $apk\" >>\$L; ")
        append("echo 'ghost-priv: APK path not found (app updated? reopen 内置提权 page)'; exit 3; fi\n")
        append("S=; command -v setsid >/dev/null 2>&1 && S=setsid\n")
        append("\$S nohup env CLASSPATH=${q(apk)} app_process /system/bin --nice-name=$NICE_NAME ")
        append("$SERVER_CLASS ${q(pkg)} $uid </dev/null >>\$L 2>&1 &\n")
        append("echo $STARTED\n")
        if (showLog) append("sleep 3; echo '--- $LOG ---'; cat \$L\n")
    }

    /** Script that prints the log tail and whether the server process is alive. */
    fun diagnoseScript(lines: Int = 30): String =
        "tail -n $lines $LOG 2>&1; echo '#PIDS:'\$(pidof $NICE_NAME 2>/dev/null)"

    data class Diagnosis(val message: String, val logTail: String)

    /** Parses [diagnoseScript] output into a user-facing message. */
    fun diagnose(output: String?, via: String): Diagnosis {
        val raw = output.orEmpty()
        val idx = raw.lastIndexOf("#PIDS:")
        val log = (if (idx >= 0) raw.substring(0, idx) else raw).trim()
        val pids = if (idx >= 0) raw.substring(idx + 6).trim() else ""
        val running = pids.isNotEmpty()
        val head = when {
            output == null -> "未收到 binder，且无法读取服务日志（$via 会话已断开）"
            log.contains("apk not readable") || log.contains("ClassNotFoundException") ->
                "服务类加载失败：APK 路径已变化（刚更新过？）或不可读。请返回再进入本页后重试"
            log.contains("binder sent") ->
                "服务已把 binder 发出，但应用没有收到（应用进程可能被系统冻结/重启）。请保持本页在前台重试"
            log.contains("send binder failed") || log.contains("provider") ->
                "服务已启动，但系统拒绝把 binder 交回应用（ContentProvider 调用失败），详见下方日志"
            log.contains("No such file") && log.contains("tmp") ->
                "无法写入 /data/local/tmp（ROM 限制），服务未启动"
            running -> "服务进程在运行，但尚未交回 binder，详见下方日志"
            log.isEmpty() || !log.contains("PrivilegeServer") && !log.contains("ghost-priv") ->
                "服务进程没有启动或刚启动就被结束（ROM/系统拦截或$via 会话中断），详见下方日志"
            else -> "服务进程已退出，未交回 binder，详见下方日志"
        }
        return Diagnosis("$head（$via）", log.takeLast(4000))
    }
}
