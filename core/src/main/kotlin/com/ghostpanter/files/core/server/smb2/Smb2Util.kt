package com.ghostpanter.files.core.server.smb2

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

internal object NtStatus {
    const val SUCCESS = 0L
    const val MORE_PROCESSING_REQUIRED = 0xC0000016L
    const val NO_MORE_FILES = 0x80000006L
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034L
    const val OBJECT_PATH_NOT_FOUND = 0xC000003AL
    const val OBJECT_NAME_COLLISION = 0xC0000035L
    const val ACCESS_DENIED = 0xC0000022L
    const val INVALID_PARAMETER = 0xC000000DL
    const val FILE_IS_A_DIRECTORY = 0xC00000BAL
    const val NOT_A_DIRECTORY = 0xC0000103L
    const val END_OF_FILE = 0xC0000011L
    const val MEDIA_WRITE_PROTECTED = 0xC00000A2L
    const val NOT_SUPPORTED = 0xC00000BBL
    const val NETWORK_NAME_DELETED = 0xC00000C9L
    const val USER_SESSION_DELETED = 0xC0000203L
    const val BAD_NETWORK_NAME = 0xC00000CCL
    const val LOGON_FAILURE = 0xC000006DL
    const val BUFFER_OVERFLOW = 0x80000005L
    const val PENDING = 0x00000103L
    const val CANCELLED = 0xC0000120L
    const val DELETE_PENDING = 0xC0000056L
    const val DIRECTORY_NOT_EMPTY = 0xC0000101L
    const val SHARING_VIOLATION = 0xC0000043L
    const val INVALID_INFO_CLASS = 0xC0000003L
    const val OBJECT_NAME_INVALID = 0xC0000033L
    const val NO_SUCH_FILE = 0xC000000FL
    const val FILE_CLOSED = 0xC0000128L
    const val INVALID_HANDLE = 0xC0000008L
    const val INFO_LENGTH_MISMATCH = 0xC0000004L
}

internal object Smb2Cmd {
    const val NEGOTIATE: Int = 0
    const val SESSION_SETUP: Int = 1
    const val LOGOFF: Int = 2
    const val TREE_CONNECT: Int = 3
    const val TREE_DISCONNECT: Int = 4
    const val CREATE: Int = 5
    const val CLOSE: Int = 6
    const val FLUSH: Int = 7
    const val READ: Int = 8
    const val WRITE: Int = 9
    const val LOCK: Int = 10
    const val IOCTL: Int = 11
    const val CANCEL: Int = 12
    const val ECHO: Int = 13
    const val QUERY_DIRECTORY: Int = 14
    const val CHANGE_NOTIFY: Int = 15
    const val QUERY_INFO: Int = 16
    const val SET_INFO: Int = 17
}

internal object Smb2Dialect {
    const val SMB202: Int = 0x0202
    const val SMB210: Int = 0x0210
    const val SMB300: Int = 0x0300
    const val SMB302: Int = 0x0302
    const val SMB311: Int = 0x0311
}

internal object Smb2Flags {
    const val SERVER_TO_REDIR = 0x00000001
    const val ASYNC = 0x00000002
    const val RELATED = 0x00000004
    const val SIGNED = 0x00000008
}

internal fun ByteArray.getU16(o: Int): Int =
    (this[o].toInt() and 0xff) or ((this[o + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.getU32(o: Int): Long =
    (this[o].toInt() and 0xff).toLong() or
        ((this[o + 1].toInt() and 0xff).toLong() shl 8) or
        ((this[o + 2].toInt() and 0xff).toLong() shl 16) or
        ((this[o + 3].toInt() and 0xff).toLong() shl 24)

internal fun ByteArray.getU64(o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((this[o + i].toInt() and 0xff).toLong() shl (8 * i))
    return v
}

internal fun ByteArray.putU16(o: Int, v: Int) {
    this[o] = (v and 0xff).toByte()
    this[o + 1] = ((v ushr 8) and 0xff).toByte()
}

internal fun ByteArray.putU32(o: Int, v: Long) {
    this[o] = (v and 0xff).toByte()
    this[o + 1] = ((v ushr 8) and 0xff).toByte()
    this[o + 2] = ((v ushr 16) and 0xff).toByte()
    this[o + 3] = ((v ushr 24) and 0xff).toByte()
}

internal fun ByteArray.putU64(o: Int, v: Long) {
    for (i in 0 until 8) this[o + i] = ((v ushr (8 * i)) and 0xff).toByte()
}

internal fun utf16Le(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_16LE)

internal fun fromUtf16Le(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
    if (len <= 0) return ""
    return String(b, off, len, StandardCharsets.UTF_16LE)
}

/** Windows FILETIME (100-ns since 1601-01-01 UTC). */
internal fun fileTimeFromMillis(ms: Long): Long {
    // 11644473600000 ms between 1601 and 1970
    return (ms + 11_644_473_600_000L) * 10_000L
}

internal fun fileTimeNow(): Long = fileTimeFromMillis(System.currentTimeMillis())

internal class IdGen {
    private val n = AtomicLong(1)
    fun next(): Long = n.getAndIncrement()
}

internal fun ByteBuffer.le(): ByteBuffer = order(ByteOrder.LITTLE_ENDIAN)

internal fun grow(dst: ByteArray, need: Int): ByteArray {
    if (dst.size >= need) return dst
    var n = dst.size.coerceAtLeast(256)
    while (n < need) n *= 2
    return dst.copyOf(n)
}
