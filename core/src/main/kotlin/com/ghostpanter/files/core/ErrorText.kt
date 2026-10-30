package com.ghostpanter.files.core

import java.io.EOFException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import com.ghostpanter.files.core.net.LanVpnPolicy

/** Turns wrapped library exceptions (smbj wraps 3–4 levels deep) into a readable reason. */
object ErrorText {
    fun chain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var c: Throwable? = t
        while (c != null && c !in out && out.size < 12) { out.add(c); c = c.cause }
        return out
    }

    fun root(t: Throwable): Throwable = chain(t).last()

    /** Short Chinese reason for the root cause, followed by the root exception. */
    fun describe(t: Throwable): String {
        if (t is FsException) return t.message ?: tr("错误", "Error")
        val all = chain(t)
        val r = all.last()
        val reason = when {
            all.any { it is SocketTimeoutException || it is TimeoutException } -> tr("连接超时（服务器长时间无响应）", "Connection timed out")
            all.any { it is EOFException } -> tr("服务器关闭了连接", "Server closed the connection")
            all.any { it is ConnectException } -> tr("无法连接服务器", "Cannot connect to server")
            all.any { it is NoRouteToHostException } -> tr("网络不可达（Wi-Fi 是否断开？）", "Network unreachable (Wi-Fi off?)")
            all.any { it is UnknownHostException } -> tr("找不到主机", "Host not found")
            all.any { it is SocketException && (it.message ?: "").contains("reset", true) } -> tr("连接被重置", "Connection reset")
            all.any { it is SocketException } -> tr("网络连接中断", "Network connection lost")
            all.any { (it.message ?: "").contains("credits", true) } -> tr("SMB 服务器忙（信用不足）", "SMB server busy (out of credits)")
            all.any { (it.message ?: "").contains("already been closed", true) } -> tr("连接已断开", "Connection closed")
            else -> null
        }
        val rootText = r.javaClass.simpleName + (r.message?.takeIf { it.isNotBlank() && it.length < 200 }?.let { ": $it" } ?: "")
        val base = listOfNotNull(reason, rootText).joinToString("：")
        // Only decorate reachability failures — not SMB status / credit errors mid-transfer.
        val reach = reason != null && (
            all.any { it is SocketTimeoutException || it is TimeoutException || it is ConnectException ||
                it is NoRouteToHostException || it is UnknownHostException || it is SocketException }
        )
        return if (reach) base + LanVpnPolicy.connectHint() else base
    }

    /** Full stack trace incl. causes, for "复制错误" and the log file. */
    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
}
