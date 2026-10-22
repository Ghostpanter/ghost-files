package com.ghostpanter.files.core

/** Turns exceptions into a short sentence for the UI; the raw text stays available as the detail. */
object FriendlyError {
    data class Msg(val text: String, val detail: String?)

    private fun raw(e: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 4) {
            if (depth > 0) sb.append("\n← ")
            sb.append(c.javaClass.name).append(": ").append(c.message ?: "")
            c = c.cause?.takeIf { it !== c }
            depth++
        }
        return sb.toString()
    }

    private fun chain(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(6)

    /** [backend] is "Shizuku", "Root", … when known; used to name what failed. */
    fun describe(e: Throwable, backend: String? = null): Msg {
        val all = chain(e).toList()
        val msgs = all.mapNotNull { it.message }.joinToString(" | ")
        val m = msgs.lowercase()
        val via = backend ?: tr("后台服务", "the service")
        val text = when {
            all.any { it is ProcTimeoutException } || "timed out" in m || "超时" in msgs ->
                tr("$via 响应超时，请稍后重试；如果一直这样，请重启 $via。", "$via did not answer in time. Try again; restart $via if it keeps happening.")
            "process hasn't exited" in m ->
                tr("$via 命令还没执行完就读取了结果，请重试。", "Read the result before the $via command finished. Please retry.")
            all.any { it.javaClass.name == "android.os.DeadObjectException" } || "binder" in m && ("died" in m || "dead" in m) || "shizuku" in m && "not running" in m ->
                tr("与 $via 的连接已断开，请确认 $via 正在运行后重试。", "Lost the connection to $via. Make sure it is running, then retry.")
            "su not available" in m || "root not granted" in m ->
                tr("未获得 Root 授权，请在 Magisk / KernelSU 中允许本应用。", "Root not granted. Allow this app in Magisk / KernelSU.")
            all.any { it is java.io.FileNotFoundException } || "no such file" in m || "enoent" in m ->
                tr("文件或文件夹不存在，可能已被删除或移动。", "The file or folder no longer exists.")
            all.any { it is SecurityException } || "permission denied" in m || "eacces" in m || "operation not permitted" in m || "eperm" in m ->
                tr("没有权限访问这里。可以试试用 Root 或 Shizuku 打开。", "No permission here. Try opening it with Root or Shizuku.")
            "no space" in m || "enospc" in m -> tr("存储空间不足。", "Not enough storage space.")
            "read-only file system" in m || "erofs" in m -> tr("这里是只读的，不能修改。", "This location is read-only.")
            // Our own messages are already readable (FsException texts are localized).
            e is FsException && !e.message.isNullOrBlank() -> e.message!!
            else -> tr("操作失败，请重试。", "Something went wrong. Please retry.")
        }
        val detail = raw(e)
        return Msg(text, detail.takeIf { it.isNotBlank() && it != text })
    }
}
