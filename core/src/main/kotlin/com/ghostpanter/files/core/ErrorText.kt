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
        if (t is FsException) return t.message ?: "错误"
        val all = chain(t)
        val r = all.last()
        val reason = when {
            all.any { it is SocketTimeoutException || it is TimeoutException } -> "连接超时（服务器长时间无响应）"
            all.any { it is EOFException } -> "服务器关闭了连接"
            all.any { it is ConnectException } -> "无法连接服务器"
            all.any { it is NoRouteToHostException } -> "网络不可达（Wi-Fi 是否断开？）"
            all.any { it is UnknownHostException } -> "找不到主机"
            all.any { it is SocketException && (it.message ?: "").contains("reset", true) } -> "连接被重置"
            all.any { it is SocketException } -> "网络连接中断"
            all.any { (it.message ?: "").contains("credits", true) } -> "SMB 服务器忙（信用不足）"
            all.any { (it.message ?: "").contains("already been closed", true) } -> "连接已断开"
            else -> null
        }
        val rootText = r.javaClass.simpleName + (r.message?.takeIf { it.isNotBlank() && it.length < 200 }?.let { ": $it" } ?: "")
        return listOfNotNull(reason, rootText).joinToString("：")
    }

    /** Full stack trace incl. causes, for "复制错误" and the log file. */
    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
}
