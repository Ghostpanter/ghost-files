package com.ghostpanter.files.core.server.smb2

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Direct-TCP SMB2/SMB3 file server (no NetBIOS). Dialects: 2.0.2 / 2.1 / 3.0 / 3.0.2.
 * Encryption (SMB3 AES-CCM/GCM) is not advertised; signing is supported when the client signs.
 * Bind a high port (e.g. 4455) — no Root required.
 */
class Smb2Server(
    private val shareRoot: File,
    private val shareName: String = "share",
    private val username: String = "ghost",
    private val password: String = "",
    private val anonymous: Boolean = false,
    private val serverName: String = "GHOSTFILES",
    private val bindAddress: InetAddress? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "gf-smb2").apply { isDaemon = true }
    }
    private val sessionCounter = AtomicInteger(0)
    @Volatile var activeClients: Int = 0
        private set
    @Volatile var listenPort: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private val serverGuid: ByteArray = ByteArray(16).also { b ->
        val u = UUID.nameUUIDFromBytes("ghost-files-smb2".toByteArray())
        b.putU64(0, u.mostSignificantBits)
        b.putU64(8, u.leastSignificantBits)
    }

    fun start(port: Int) {
        stop()
        lastError = null
        if (!shareRoot.isDirectory && !shareRoot.mkdirs()) {
            lastError = "共享目录不可用: ${shareRoot.absolutePath}"
            throw IOException(lastError)
        }
        val ss = if (bindAddress != null) ServerSocket(port, 50, bindAddress) else ServerSocket(port)
        serverSocket = ss
        listenPort = ss.localPort
        running.set(true)
        acceptThread = Thread({
            try {
                while (running.get()) {
                    val sock = try { ss.accept() } catch (_: SocketException) { break } catch (_: IOException) { break }
                    pool.execute { handleClient(sock) }
                }
            } catch (e: Exception) {
                if (running.get()) lastError = e.message?.take(200)
            }
        }, "gf-smb2-accept").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        listenPort = 0
    }

    override fun close() {
        stop()
        pool.shutdownNow()
    }

    private fun handleClient(sock: Socket) {
        activeClients++
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 300_000
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
            val conn = Connection(sock, input, output)
            while (running.get() && !sock.isClosed) {
                val frame = readFrame(input) ?: break
                val responses = conn.dispatch(frame)
                for (resp in responses) {
                    writeFrame(output, resp)
                }
                output.flush()
            }
        } catch (_: Exception) {
            // client disconnect / parse error — drop
        } finally {
            activeClients--
            runCatching { sock.close() }
        }
    }

    private fun readFrame(input: BufferedInputStream): ByteArray? {
        val hdr = ByteArray(4)
        if (!readFully(input, hdr)) return null
        val len = ((hdr[1].toInt() and 0xff) shl 16) or
            ((hdr[2].toInt() and 0xff) shl 8) or
            (hdr[3].toInt() and 0xff)
        // zero NB type nibble in first byte for Direct TCP
        if (len <= 0 || len > 16 * 1024 * 1024) return null
        val body = ByteArray(len)
        if (!readFully(input, body)) return null
        return body
    }

    private fun writeFrame(output: BufferedOutputStream, body: ByteArray) {
        val hdr = ByteArray(4)
        val len = body.size
        hdr[0] = 0
        hdr[1] = ((len ushr 16) and 0xff).toByte()
        hdr[2] = ((len ushr 8) and 0xff).toByte()
        hdr[3] = (len and 0xff).toByte()
        output.write(hdr)
        output.write(body)
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private inner class Connection(
        private val sock: Socket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
    ) {
        var dialect: Int = 0
        var sessionId: Long = 0
        var sessionKey: ByteArray? = null
        var signingKey: ByteArray? = null
        var authenticated = false
        var isGuest = false
        var challenge: NtlmServerAuth.ChallengeState? = null
        val trees = ConcurrentHashMap<Int, Tree>()
        val opens = ConcurrentHashMap<Long, OpenFile>()
        private val treeIds = AtomicInteger(1)
        private val openIds = IdGen()
        private val ntlm = NtlmServerAuth(username, password, anonymous, serverName)

        fun dispatch(msg: ByteArray): List<ByteArray> {
            if (msg.size < 64) return emptyList()
            // SMB1 negotiate probe (0xFF 'S' 'M' 'B') — reply with SMB2 negotiate
            if (msg[0] == 0xFF.toByte() && msg[1] == 'S'.code.toByte()) {
                return listOf(buildNegotiateResponse(
                    creditCharge = 1, credit = 1, messageId = 0, sessionId = 0,
                    // Prefer SMB 2.1 for SMB1-negotiate clients; they'll upgrade
                    chosenDialect = Smb2Dialect.SMB210,
                ))
            }
            if (msg[0] != 0xFE.toByte()) return emptyList()
            val cmd = msg.getU16(12)
            val creditCharge = msg.getU16(6).coerceAtLeast(1)
            val creditsReq = msg.getU16(14).coerceAtLeast(1)
            val flags = msg.getU32(16).toInt()
            val messageId = msg.getU64(24)
            val treeId = msg.getU32(36).toInt()
            val reqSessionId = msg.getU64(40)

            // Multi-credit replies: grant what was asked (cap)
            val creditGrant = creditsReq.coerceIn(1, 8192)

            val body = when (cmd) {
                Smb2Cmd.NEGOTIATE -> handleNegotiate(msg, creditCharge, creditGrant, messageId)
                Smb2Cmd.SESSION_SETUP -> handleSessionSetup(msg, creditCharge, creditGrant, messageId, reqSessionId)
                Smb2Cmd.LOGOFF -> handleLogoff(creditCharge, creditGrant, messageId, reqSessionId)
                Smb2Cmd.TREE_CONNECT -> handleTreeConnect(msg, creditCharge, creditGrant, messageId, reqSessionId)
                Smb2Cmd.TREE_DISCONNECT -> handleTreeDisconnect(creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.CREATE -> handleCreate(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.CLOSE -> handleClose(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.FLUSH -> handleFlush(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.READ -> handleRead(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.WRITE -> handleWrite(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.QUERY_DIRECTORY -> handleQueryDirectory(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.QUERY_INFO -> handleQueryInfo(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.SET_INFO -> handleSetInfo(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.ECHO -> handleEcho(creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.IOCTL -> handleIoctl(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.CHANGE_NOTIFY -> errorResponse(cmd, creditCharge, creditGrant, messageId, reqSessionId, treeId, NtStatus.NOT_SUPPORTED)
                Smb2Cmd.LOCK -> handleLock(msg, creditCharge, creditGrant, messageId, reqSessionId, treeId)
                Smb2Cmd.CANCEL -> errorResponse(cmd, creditCharge, creditGrant, messageId, reqSessionId, treeId, NtStatus.CANCELLED)
                else -> errorResponse(cmd, creditCharge, creditGrant, messageId, reqSessionId, treeId, NtStatus.NOT_SUPPORTED)
            }
            return listOf(maybeSign(body))
        }

        private fun maybeSign(packet: ByteArray): ByteArray {
            val key = signingKey ?: return packet
            if (!authenticated) return packet
            // SMB2: HMAC-SHA256; SMB3: AES-CMAC (see sign())
            packet.putU32(16, (packet.getU32(16).toInt() or Smb2Flags.SIGNED).toLong())
            for (i in 48 until 64) packet[i] = 0
            val sig = sign(key, packet)
            sig.copyInto(packet, 48, 0, 16)
            return packet
        }

        private fun sign(key: ByteArray, packet: ByteArray): ByteArray {
            return if (dialect >= Smb2Dialect.SMB300) {
                aesCmac(key, packet)
            } else {
                hmacSha256Trunc16(key, packet)
            }
        }

        private fun handleNegotiate(msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long): ByteArray {
            // SMB2 NEGOTIATE Request: StructureSize 36 at offset 64
            if (msg.size < 64 + 36) {
                return errorResponse(Smb2Cmd.NEGOTIATE, creditCharge, credit, messageId, 0, 0, NtStatus.INVALID_PARAMETER)
            }
            val dialectCount = msg.getU16(64 + 2)
            val dialects = mutableListOf<Int>()
            var off = 64 + 36
            // If NegotiateContextOffset present (SMB 3.1.1), dialects still immediately follow structure
            for (i in 0 until dialectCount) {
                if (off + 2 > msg.size) break
                dialects.add(msg.getU16(off))
                off += 2
            }
            // Stable path: SMB 2.1 / 2.0.2. Fall back to SMB 3.0.2 only if client offers no 2.x.
            dialect = when {
                Smb2Dialect.SMB210 in dialects -> Smb2Dialect.SMB210
                Smb2Dialect.SMB202 in dialects -> Smb2Dialect.SMB202
                Smb2Dialect.SMB302 in dialects -> Smb2Dialect.SMB302
                Smb2Dialect.SMB300 in dialects -> Smb2Dialect.SMB300
                else -> Smb2Dialect.SMB210
            }
            return buildNegotiateResponse(creditCharge, credit, messageId, 0, dialect)
        }

        private fun handleSessionSetup(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long,
        ): ByteArray {
            val structSize = msg.getU16(64)
            if (structSize < 25 || msg.size < 64 + 24) {
                return errorResponse(Smb2Cmd.SESSION_SETUP, creditCharge, credit, messageId, reqSessionId, 0, NtStatus.INVALID_PARAMETER)
            }
            val secOff = msg.getU16(64 + 12)
            val secLen = msg.getU16(64 + 14)
            val blob = if (secLen > 0 && secOff + secLen <= msg.size) msg.copyOfRange(secOff, secOff + secLen) else ByteArray(0)

            // Already authenticated — ignore empty follow-ups (some clients re-touch session setup).
            if (authenticated && blob.isEmpty()) {
                return buildSessionSetupResponse(
                    creditCharge, credit, messageId, sessionId,
                    NtStatus.SUCCESS, ByteArray(0), guest = isGuest,
                )
            }

            val startChallenge = ntlm.isType1(blob) || (challenge == null && !ntlm.isType3(blob))
            if (startChallenge) {
                val (token, st) = ntlm.beginChallenge()
                challenge = st
                if (sessionId == 0L) {
                    sessionId = (sessionCounter.incrementAndGet().toLong() and 0xffffffffL) or 0x100000000L
                }
                return buildSessionSetupResponse(
                    creditCharge, credit, messageId, sessionId,
                    NtStatus.MORE_PROCESSING_REQUIRED, token, guest = false,
                )
            }
            val result = ntlm.authenticate(blob, challenge)
            challenge = null
            if (!result.ok) {
                return errorResponse(Smb2Cmd.SESSION_SETUP, creditCharge, credit, messageId, sessionId, 0, NtStatus.LOGON_FAILURE)
            }
            authenticated = true
            isGuest = result.isGuest
            sessionKey = result.sessionBaseKey?.copyOf(16) ?: ByteArray(16)
            signingKey = deriveSigningKey(sessionKey!!, dialect)
            return buildSessionSetupResponse(
                creditCharge, credit, messageId, sessionId,
                NtStatus.SUCCESS, ntlm.buildAcceptCompleted(), guest = isGuest,
            )
        }

        private fun handleLogoff(creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long): ByteArray {
            trees.clear()
            opens.values.forEach { it.closeQuietly() }
            opens.clear()
            authenticated = false
            sessionKey = null
            signingKey = null
            val body = ByteArray(4).also { it.putU16(0, 4) }
            return buildPacket(Smb2Cmd.LOGOFF, creditCharge, credit, messageId, reqSessionId, 0, NtStatus.SUCCESS, body)
        }

        private fun handleTreeConnect(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long,
        ): ByteArray {
            if (!authenticated) {
                return errorResponse(Smb2Cmd.TREE_CONNECT, creditCharge, credit, messageId, reqSessionId, 0, NtStatus.ACCESS_DENIED)
            }
            val pathOff = msg.getU16(64 + 4)
            val pathLen = msg.getU16(64 + 6)
            val path = if (pathLen > 0 && pathOff + pathLen <= msg.size) fromUtf16Le(msg, pathOff, pathLen) else ""
            // path like \\host\share or \share
            val share = path.substringAfterLast('\\').substringAfterLast('/').trim().trim('\u0000')
            if (!authenticated) {
                return errorResponse(Smb2Cmd.TREE_CONNECT, creditCharge, credit, messageId, reqSessionId, 0, NtStatus.ACCESS_DENIED)
            }
            if (share.isNotEmpty() && !share.equals(shareName, true) && !share.equals("IPC$", true)) {
                return errorResponse(Smb2Cmd.TREE_CONNECT, creditCharge, credit, messageId, reqSessionId, 0, NtStatus.BAD_NETWORK_NAME)
            }
            val tid = treeIds.getAndIncrement()
            val isIpc = share.equals("IPC$", true)
            trees[tid] = Tree(tid, isIpc)
            // TREE_CONNECT response StructureSize 16
            val body = ByteArray(16)
            body.putU16(0, 16)
            body.putU16(2, 0) // ShareType: 0 disk, 2 pipe — we use 1=disk per MS (ShareType 0x01)
            body[2] = if (isIpc) 0x02 else 0x01
            body.putU32(4, 0) // ShareFlags
            body.putU32(8, 0) // Capabilities
            body.putU32(12, 0x001F01FF) // MaximalAccess (GENERIC_ALL-ish)
            return buildPacket(Smb2Cmd.TREE_CONNECT, creditCharge, credit, messageId, reqSessionId, tid, NtStatus.SUCCESS, body)
        }

        private fun handleTreeDisconnect(
            creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            trees.remove(treeId)
            val body = ByteArray(4).also { it.putU16(0, 4) }
            return buildPacket(Smb2Cmd.TREE_DISCONNECT, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleCreate(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val tree = trees[treeId]
                ?: return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.NETWORK_NAME_DELETED)
            if (tree.isIpc) {
                return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED)
            }
            val nameOff = msg.getU16(64 + 44)
            val nameLen = msg.getU16(64 + 46)
            val name = if (nameLen > 0 && nameOff + nameLen <= msg.size) fromUtf16Le(msg, nameOff, nameLen) else ""
            val disposition = msg.getU32(64 + 36).toInt() // CreateDisposition
            val createOptions = msg.getU32(64 + 40).toInt()
            val desiredDir = (createOptions and 0x00000001) != 0 // FILE_DIRECTORY_FILE
            val nonDir = (createOptions and 0x00000040) != 0 // FILE_NON_DIRECTORY_FILE
            val deleteOnClose = (createOptions and 0x00001000) != 0

            val rel = normalizeRel(name)
            val file = resolve(rel) ?: return errorResponse(
                Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.OBJECT_NAME_INVALID,
            )
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.OBJECT_PATH_NOT_FOUND)
            }

            var action = 1 // FILE_OPENED
            try {
                when (disposition) {
                    0 -> { // SUPERSEDE
                        if (file.exists()) file.deleteRecursively()
                        if (desiredDir) file.mkdirs() else { parent?.mkdirs(); file.writeBytes(ByteArray(0)) }
                        action = 2 // FILE_CREATED / SUPERSEDED → 0 supersede
                        action = 0
                    }
                    1 -> { // OPEN
                        if (!file.exists()) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.OBJECT_NAME_NOT_FOUND)
                        action = 1
                    }
                    2 -> { // CREATE
                        if (file.exists()) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.OBJECT_NAME_COLLISION)
                        if (desiredDir) { if (!file.mkdirs()) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED) }
                        else { parent?.mkdirs(); if (!file.createNewFile()) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED) }
                        action = 2
                    }
                    3 -> { // OPEN_IF
                        if (file.exists()) action = 1
                        else {
                            if (desiredDir) file.mkdirs() else { parent?.mkdirs(); file.createNewFile() }
                            action = 2
                        }
                    }
                    4 -> { // OVERWRITE
                        if (!file.exists()) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.OBJECT_NAME_NOT_FOUND)
                        if (file.isDirectory) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_IS_A_DIRECTORY)
                        RandomAccessFile(file, "rw").use { it.setLength(0) }
                        action = 3 // FILE_OVERWRITTEN
                    }
                    5 -> { // OVERWRITE_IF
                        if (file.exists()) {
                            if (file.isDirectory) return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_IS_A_DIRECTORY)
                            RandomAccessFile(file, "rw").use { it.setLength(0) }
                            action = 3
                        } else {
                            parent?.mkdirs(); file.createNewFile(); action = 2
                        }
                    }
                    else -> return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INVALID_PARAMETER)
                }
            } catch (e: Exception) {
                return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED)
            }

            if (desiredDir && file.exists() && !file.isDirectory) {
                return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.NOT_A_DIRECTORY)
            }
            if (nonDir && file.exists() && file.isDirectory) {
                return errorResponse(Smb2Cmd.CREATE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_IS_A_DIRECTORY)
            }

            val fid = openIds.next()
            val raf = if (file.isFile) {
                try { RandomAccessFile(file, "rw") } catch (_: Exception) {
                    try { RandomAccessFile(file, "r") } catch (_: Exception) { null }
                }
            } else null
            opens[fid] = OpenFile(fid, file, raf, deleteOnClose, file.isDirectory)

            return buildCreateResponse(creditCharge, credit, messageId, reqSessionId, treeId, fid, file, action)
        }

        private fun handleClose(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val fid = fileIdFrom(msg, 64 + 8)
            val open = opens.remove(fid)
                ?: return errorResponse(Smb2Cmd.CLOSE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_CLOSED)
            open.closeQuietly()
            if (open.deleteOnClose) {
                runCatching { if (open.file.isDirectory) open.file.deleteRecursively() else open.file.delete() }
            }
            val body = ByteArray(60)
            body.putU16(0, 60)
            fillTimes(body, 8, open.file)
            body.putU64(40, if (open.file.isFile) open.file.length() else 0L) // AllocationSize approx
            body.putU64(48, if (open.file.isFile) open.file.length() else 0L)
            body.putU32(56, attrsOf(open.file).toLong())
            return buildPacket(Smb2Cmd.CLOSE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleFlush(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val fid = fileIdFrom(msg, 64 + 8)
            opens[fid]?.raf?.fd?.sync()
            val body = ByteArray(4).also { it.putU16(0, 4) }
            return buildPacket(Smb2Cmd.FLUSH, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleRead(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val length = msg.getU32(64 + 4).toInt()
            val offset = msg.getU64(64 + 8)
            val fid = fileIdFrom(msg, 64 + 16)
            val open = opens[fid]
                ?: return errorResponse(Smb2Cmd.READ, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_CLOSED)
            if (open.isDir) return errorResponse(Smb2Cmd.READ, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INVALID_PARAMETER)
            val raf = open.raf ?: return errorResponse(Smb2Cmd.READ, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED)
            val max = length.coerceIn(0, 8 * 1024 * 1024)
            val buf = ByteArray(max)
            val n = synchronized(raf) {
                raf.seek(offset)
                raf.read(buf)
            }
            if (n <= 0) {
                return errorResponse(Smb2Cmd.READ, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.END_OF_FILE)
            }
            val data = buf.copyOf(n)
            // READ response: StructureSize 17, DataOffset, DataLength, DataRemaining, Reserved, Buffer
            val dataOffset = 64 + 16
            val body = ByteArray(16 + data.size)
            body.putU16(0, 17)
            body[2] = (dataOffset and 0xff).toByte() // DataOffset (from header start)
            body.putU32(4, data.size.toLong())
            body.putU32(8, 0) // DataRemaining
            data.copyInto(body, 16)
            return buildPacket(Smb2Cmd.READ, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleWrite(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val dataOff = msg.getU16(64 + 2)
            val length = msg.getU32(64 + 4).toInt()
            val offset = msg.getU64(64 + 8)
            val fid = fileIdFrom(msg, 64 + 16)
            val open = opens[fid]
                ?: return errorResponse(Smb2Cmd.WRITE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_CLOSED)
            if (open.isDir) return errorResponse(Smb2Cmd.WRITE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INVALID_PARAMETER)
            val raf = open.raf ?: return errorResponse(Smb2Cmd.WRITE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED)
            if (dataOff + length > msg.size) {
                return errorResponse(Smb2Cmd.WRITE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INVALID_PARAMETER)
            }
            val data = msg.copyOfRange(dataOff, dataOff + length)
            synchronized(raf) {
                raf.seek(offset)
                raf.write(data)
            }
            val body = ByteArray(16)
            body.putU16(0, 17)
            body.putU32(4, length.toLong()) // Count
            body.putU32(8, 0) // Remaining
            return buildPacket(Smb2Cmd.WRITE, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleQueryDirectory(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val infoClass = msg[64 + 2].toInt() and 0xff
            val flags = msg[64 + 3].toInt() and 0xff
            val fileIndex = msg.getU32(64 + 4).toInt()
            val fid = fileIdFrom(msg, 64 + 8)
            val outBufLen = msg.getU32(64 + 24).toInt().coerceIn(0, 8 * 1024 * 1024)
            val nameOff = msg.getU16(64 + 28)
            val nameLen = msg.getU16(64 + 30)
            val pattern = if (nameLen > 0 && nameOff + nameLen <= msg.size) fromUtf16Le(msg, nameOff, nameLen) else "*"

            val open = opens[fid]
                ?: return errorResponse(Smb2Cmd.QUERY_DIRECTORY, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_CLOSED)
            if (!open.isDir) {
                return errorResponse(Smb2Cmd.QUERY_DIRECTORY, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INVALID_PARAMETER)
            }
            // Only RESTART_SCANS (0x1) / REOPEN (0x10) rebuild the list. fileIndex==0 is common
            // on follow-up queries and must NOT restart, or clients loop forever on `ls`.
            val restart = (flags and 0x01) != 0 || (flags and 0x10) != 0
            if (restart) {
                open.dirEnum = null
                open.dirDone = false
            }
            if (open.dirDone) {
                return errorResponse(Smb2Cmd.QUERY_DIRECTORY, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.NO_MORE_FILES)
            }
            if (open.dirEnum == null) {
                val files = open.file.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
                val matched = files.filter { matchGlob(it.name, pattern) }
                // Include . and .. for wildcard
                val withDots = buildList {
                    if (matchGlob(".", pattern)) add(DirEntry(".", open.file, isDot = true))
                    if (matchGlob("..", pattern)) add(DirEntry("..", open.file.parentFile ?: open.file, isDot = true))
                    matched.forEach { add(DirEntry(it.name, it, false)) }
                }
                open.dirEnum = withDots.toMutableList()
            }
            val enumList = open.dirEnum!!
            if (enumList.isEmpty()) {
                open.dirDone = true
                return errorResponse(Smb2Cmd.QUERY_DIRECTORY, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.NO_MORE_FILES)
            }

            val out = ArrayList<Byte>()
            var written = 0
            while (enumList.isNotEmpty()) {
                val e = enumList.first()
                val chunk = encodeDirInfo(infoClass, e) ?: break
                // align to 8
                val pad = (8 - (out.size % 8)) % 8
                if (written + pad + chunk.size > outBufLen && written > 0) break
                if (written + pad + chunk.size > outBufLen && written == 0) {
                    // single entry too big
                    return errorResponse(Smb2Cmd.QUERY_DIRECTORY, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INFO_LENGTH_MISMATCH)
                }
                repeat(pad) { out.add(0) }
                // NextEntryOffset placeholder at start of this entry — fix previous
                if (written > 0) {
                    // find start of previous entry: lastNextPos
                }
                val entryStart = out.size
                chunk.forEach { out.add(it) }
                // set NextEntryOffset of previous entry
                if (written > 0) {
                    val prevStart = open.lastDirEntryStart
                    val nextOff = entryStart - prevStart
                    out[prevStart] = (nextOff and 0xff).toByte()
                    out[prevStart + 1] = ((nextOff ushr 8) and 0xff).toByte()
                    out[prevStart + 2] = ((nextOff ushr 16) and 0xff).toByte()
                    out[prevStart + 3] = ((nextOff ushr 24) and 0xff).toByte()
                }
                open.lastDirEntryStart = entryStart
                written = out.size
                enumList.removeAt(0)
                // SINGLE entry flag
                if ((flags and 0x02) != 0) break
            }
            // last entry NextEntryOffset = 0 already

            if (enumList.isEmpty()) open.dirDone = true
            val data = out.toByteArray()
            val dataOffset = 64 + 8
            val body = ByteArray(8 + data.size)
            body.putU16(0, 9)
            body.putU16(2, dataOffset)
            body.putU32(4, data.size.toLong())
            data.copyInto(body, 8)
            return buildPacket(Smb2Cmd.QUERY_DIRECTORY, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleQueryInfo(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val infoType = msg[64 + 2].toInt() and 0xff
            val fileInfoClass = msg[64 + 3].toInt() and 0xff
            val outBufLen = msg.getU32(64 + 4).toInt()
            val fid = fileIdFrom(msg, 64 + 24) // FileId at SMB2 QUERY_INFO body offset 24
            val open = opens[fid]
            if (open == null) {
                return errorResponse(Smb2Cmd.QUERY_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_CLOSED)
            }

            val data = when (infoType) {
                1 -> encodeFileInfo(fileInfoClass, open.file) // SMB2_0_INFO_FILE
                2 -> encodeFsInfo(fileInfoClass) // SMB2_0_INFO_FILESYSTEM
                else -> null
            } ?: return errorResponse(Smb2Cmd.QUERY_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.INVALID_INFO_CLASS)

            if (data.size > outBufLen && outBufLen > 0) {
                // truncate with BUFFER_OVERFLOW
                val trunc = data.copyOf(outBufLen)
                val dataOffset = 64 + 8
                val body = ByteArray(8 + trunc.size)
                body.putU16(0, 9)
                body.putU16(2, dataOffset)
                body.putU32(4, trunc.size.toLong())
                trunc.copyInto(body, 8)
                return buildPacket(Smb2Cmd.QUERY_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.BUFFER_OVERFLOW, body)
            }
            val dataOffset = 64 + 8
            val body = ByteArray(8 + data.size)
            body.putU16(0, 9)
            body.putU16(2, dataOffset)
            body.putU32(4, data.size.toLong())
            data.copyInto(body, 8)
            return buildPacket(Smb2Cmd.QUERY_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleSetInfo(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val infoType = msg[64 + 2].toInt() and 0xff
            val fileInfoClass = msg[64 + 3].toInt() and 0xff
            val bufLen = msg.getU32(64 + 4).toInt()
            val bufOff = msg.getU16(64 + 8)
            val fid = fileIdFrom(msg, 64 + 16)
            val open = opens[fid]
                ?: return errorResponse(Smb2Cmd.SET_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.FILE_CLOSED)
            val buf = if (bufLen > 0 && bufOff + bufLen <= msg.size) msg.copyOfRange(bufOff, bufOff + bufLen) else ByteArray(0)

            when {
                infoType == 1 && fileInfoClass == 0x14 -> { // FileDispositionInformation
                    if (buf.isNotEmpty() && buf[0].toInt() != 0) open.deleteOnClose = true
                }
                infoType == 1 && fileInfoClass == 0x13 -> { // FileEndOfFileInformation
                    if (buf.size >= 8 && open.raf != null) {
                        val eof = buf.getU64(0)
                        synchronized(open.raf!!) { open.raf!!.setLength(eof) }
                    }
                }
                infoType == 1 && fileInfoClass == 0x14 -> {}
                infoType == 1 && fileInfoClass == 10 -> { // FileRenameInformation
                    // ignore complex rename for now if buffer short
                    if (buf.size >= 20) {
                        val replace = buf[0].toInt() != 0
                        val nameLen = buf.getU32(16).toInt()
                        if (20 + nameLen <= buf.size) {
                            val newName = fromUtf16Le(buf, 20, nameLen).replace('\\', '/').trim('/')
                            val dest = resolve(newName)
                            if (dest != null) {
                                if (dest.exists() && !replace) {
                                    return errorResponse(Smb2Cmd.SET_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.OBJECT_NAME_COLLISION)
                                }
                                open.closeQuietly()
                                if (!open.file.renameTo(dest)) {
                                    return errorResponse(Smb2Cmd.SET_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.ACCESS_DENIED)
                                }
                                open.file = dest
                                open.raf = if (dest.isFile) RandomAccessFile(dest, "rw") else null
                            }
                        }
                    }
                }
                else -> { /* accept no-op for unsupported classes */ }
            }
            val body = ByteArray(2).also { it.putU16(0, 2) }
            return buildPacket(Smb2Cmd.SET_INFO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleEcho(
            creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val body = ByteArray(4).also { it.putU16(0, 4) }
            return buildPacket(Smb2Cmd.ECHO, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun handleIoctl(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            val ctlCode = msg.getU32(64 + 8).toInt()
            // FSCTL_PIPE_TRANSCEIVE etc. — return not supported for most
            // VALIDATE_NEGOTIATE_INFO = 0x00140204 — return NOT_SUPPORTED so client continues unsigned
            return errorResponse(Smb2Cmd.IOCTL, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.NOT_SUPPORTED)
        }

        private fun handleLock(
            msg: ByteArray, creditCharge: Int, credit: Int, messageId: Long, reqSessionId: Long, treeId: Int,
        ): ByteArray {
            // No-op success (many clients use locks optionally)
            val body = ByteArray(4).also { it.putU16(0, 4) }
            return buildPacket(Smb2Cmd.LOCK, creditCharge, credit, messageId, reqSessionId, treeId, NtStatus.SUCCESS, body)
        }

        // ---- builders ----

        fun buildNegotiateResponse(
            creditCharge: Int, credit: Int, messageId: Long, sessionId: Long, chosenDialect: Int,
        ): ByteArray {
            dialect = chosenDialect
            val spnego = ntlm.buildNegotiateToken()
            val structSize = 65
            val securityBufferOffset = 64 + 64 // after fixed 64-byte negotiate body (StructureSize..NegotiateContextOffset)
            // Negotiate response fixed part is 64 bytes (StructureSize 65 means 64 + buffer)
            val fixed = 64
            val body = ByteArray(fixed + spnego.size)
            body.putU16(0, structSize)
            body.putU16(2, 0x0001) // SIGNING_ENABLED — smbclient expects signed replies when set
            body.putU16(4, chosenDialect)
            // NegotiateContextCount / Reserved = 0
            serverGuid.copyInto(body, 8)
            // Capabilities: LARGE_MTU only (no encryption)
            body.putU32(24, 0x00000004)
            body.putU32(28, 8 * 1024 * 1024L) // MaxTransactSize
            body.putU32(32, 8 * 1024 * 1024L) // MaxReadSize
            body.putU32(36, 8 * 1024 * 1024L) // MaxWriteSize
            val now = fileTimeNow()
            body.putU64(40, now)
            body.putU64(48, now)
            body.putU16(56, securityBufferOffset)
            body.putU16(58, spnego.size)
            body.putU32(60, 0) // NegotiateContextOffset
            spnego.copyInto(body, fixed)
            return buildPacket(Smb2Cmd.NEGOTIATE, creditCharge, credit, messageId, sessionId, 0, NtStatus.SUCCESS, body)
        }

        private fun buildSessionSetupResponse(
            creditCharge: Int, credit: Int, messageId: Long, sessionId: Long,
            status: Long, securityBlob: ByteArray, guest: Boolean,
        ): ByteArray {
            val secOff = 64 + 8
            val body = ByteArray(8 + securityBlob.size)
            body.putU16(0, 9)
            body.putU16(2, if (guest) 1 else 0) // SessionFlags
            body.putU16(4, secOff)
            body.putU16(6, securityBlob.size)
            securityBlob.copyInto(body, 8)
            return buildPacket(Smb2Cmd.SESSION_SETUP, creditCharge, credit, messageId, sessionId, 0, status, body)
        }

        private fun buildCreateResponse(
            creditCharge: Int, credit: Int, messageId: Long, sessionId: Long, treeId: Int,
            fid: Long, file: File, action: Int,
        ): ByteArray {
            // MS-SMB2 CREATE response fixed part is 88 bytes; StructureSize field = 89.
            val body = ByteArray(88)
            body.putU16(0, 89)
            body[2] = 0 // OplockLevel none
            body[3] = 0 // Flags
            body.putU32(4, action.toLong())
            fillTimes(body, 8, file) // 8..40
            val len = if (file.isFile) file.length() else 0L
            body.putU64(40, len) // AllocationSize
            body.putU64(48, len) // EndofFile
            body.putU32(56, attrsOf(file).toLong())
            body.putU32(60, 0) // Reserved2
            putFileId(body, 64, fid) // Persistent+Volatile
            body.putU32(80, 0) // CreateContextsOffset
            body.putU32(84, 0) // CreateContextsLength
            return buildPacket(Smb2Cmd.CREATE, creditCharge, credit, messageId, sessionId, treeId, NtStatus.SUCCESS, body)
        }

        private fun buildPacket(
            cmd: Int, creditCharge: Int, credit: Int, messageId: Long, sessionId: Long, treeId: Int,
            status: Long, body: ByteArray,
        ): ByteArray {
            val out = ByteArray(64 + body.size)
            out[0] = 0xFE.toByte(); out[1] = 'S'.code.toByte(); out[2] = 'M'.code.toByte(); out[3] = 'B'.code.toByte()
            out.putU16(4, 64)
            out.putU16(6, creditCharge)
            out.putU32(8, status)
            out.putU16(12, cmd)
            out.putU16(14, credit)
            out.putU32(16, Smb2Flags.SERVER_TO_REDIR.toLong())
            out.putU32(20, 0)
            out.putU64(24, messageId)
            out.putU32(32, 0) // ProcessId
            out.putU32(36, treeId.toLong())
            out.putU64(40, sessionId)
            // signature 48..64 zero
            body.copyInto(out, 64)
            return out
        }

        private fun errorResponse(
            cmd: Int, creditCharge: Int, credit: Int, messageId: Long, sessionId: Long, treeId: Int, status: Long,
        ): ByteArray {
            // Error response body StructureSize 9, ErrorContextCount 0, ByteCount 0
            val body = ByteArray(8)
            body.putU16(0, 9)
            return buildPacket(cmd, creditCharge, credit, messageId, sessionId, treeId, status, body)
        }
    }

    private data class Tree(val id: Int, val isIpc: Boolean)
    private class OpenFile(
        val id: Long,
        var file: File,
        var raf: RandomAccessFile?,
        var deleteOnClose: Boolean,
        val isDir: Boolean,
        var dirEnum: MutableList<DirEntry>? = null,
        var lastDirEntryStart: Int = 0,
        var dirDone: Boolean = false,
    ) {
        fun closeQuietly() { runCatching { raf?.close() }; raf = null }
    }
    private data class DirEntry(val name: String, val file: File, val isDot: Boolean)

    private fun normalizeRel(name: String): String {
        var s = name.replace('/', '\\')
        while (s.startsWith("\\")) s = s.substring(1)
        return s
    }

    private fun resolve(rel: String): File? {
        if (rel.isEmpty()) return shareRoot
        val parts = rel.split('\\').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        var f = shareRoot
        for (p in parts) f = File(f, p)
        val rootCanon = shareRoot.canonicalFile
        val canon = try { f.canonicalFile } catch (_: Exception) { return null }
        if (!canon.path.startsWith(rootCanon.path)) return null
        return f
    }

    companion object {
        private fun fileIdFrom(msg: ByteArray, off: Int): Long = msg.getU64(off) // use persistent low 8 as id

        private fun putFileId(dst: ByteArray, off: Int, id: Long) {
            dst.putU64(off, id)
            dst.putU64(off + 8, id)
        }

        private fun attrsOf(f: File): Int {
            var a = 0
            if (f.isDirectory) a = a or 0x10
            if (f.isFile) a = a or 0x80 // NORMAL
            if (f.isHidden || f.name.startsWith(".")) a = a or 0x02
            if (!f.canWrite()) a = a or 0x01
            if (a == 0) a = 0x80
            return a
        }

        private fun fillTimes(dst: ByteArray, off: Int, f: File) {
            val t = fileTimeFromMillis(f.lastModified())
            dst.putU64(off, t)      // Creation
            dst.putU64(off + 8, t)  // LastAccess
            dst.putU64(off + 16, t) // LastWrite
            dst.putU64(off + 24, t) // Change
        }

        private fun matchGlob(name: String, pattern: String): Boolean {
            if (pattern.isEmpty() || pattern == "*" || pattern == "*.*") return true
            // Simple * and ? matching
            val regex = buildString {
                append('^')
                for (c in pattern) when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> { append('\\'); append(c) }
                    else -> append(c)
                }
                append('$')
            }
            return name.matches(Regex(regex, RegexOption.IGNORE_CASE))
        }

        private fun encodeDirInfo(infoClass: Int, e: DirEntry): ByteArray? {
            val nameBytes = utf16Le(e.name)
            // FILE_BOTH_DIRECTORY_INFORMATION = 0x03 (common); also 0x01, 0x02, 0x25
            // Use FILE_ID_BOTH_DIRECTORY_INFORMATION (37) layout-compatible subset via BOTH (3)
            val base = when (infoClass) {
                0x01, 0x02, 0x03, 0x25, 0x26, 0x0c -> infoClass
                else -> 0x03
            }
            // BOTH_DIR_INFO: NextEntryOffset(4)+FileIndex(4)+times(32)+EndOfFile(8)+AllocationSize(8)+
            // FileAttributes(4)+FileNameLength(4)+EaSize(4)+ShortNameLength(1)+Reserved(1)+ShortName(24)+FileName
            // ID_BOTH adds FileId(8) before FileName (after short name)
            val hasFileId = base == 0x25 || base == 0x26
            val namesOnly = base == 0x0c
            if (namesOnly) {
                val size = 12 + nameBytes.size
                val aligned = (size + 7) and 7.inv()
                val o = ByteArray(aligned)
                o.putU32(4, 0) // FileIndex
                o.putU32(8, nameBytes.size.toLong())
                nameBytes.copyInto(o, 12)
                return o
            }
            val fixed = if (hasFileId) 104 else 94
            // BOTH = 94 before filename? Actually: 8+32+8+8+4+4+4+1+1+24 = 94 yes; ID_BOTH = 94+8=102... MS says 105 structure with FileId
            // FILE_ID_BOTH_DIRECTORY_INFORMATION fixed before name = 104
            val beforeName = if (hasFileId) 104 else 94
            val size = beforeName + nameBytes.size
            val aligned = (size + 7) and 7.inv()
            val o = ByteArray(aligned)
            // NextEntryOffset = 0 for now
            o.putU32(4, 0)
            fillTimes(o, 8, e.file)
            val len = if (e.file.isFile) e.file.length() else 0L
            o.putU64(40, len)
            o.putU64(48, len)
            o.putU32(56, attrsOf(e.file).toLong())
            o.putU32(60, nameBytes.size.toLong())
            o.putU32(64, 0) // EaSize
            o[68] = 0 // ShortNameLength
            // short name 24 bytes at 70
            if (hasFileId) {
                o.putU64(96, e.file.absolutePath.hashCode().toLong() and 0xffffffffL)
                nameBytes.copyInto(o, 104)
            } else {
                nameBytes.copyInto(o, 94)
            }
            return o
        }

        private fun encodeFileInfo(fileInfoClass: Int, f: File): ByteArray? {
            return when (fileInfoClass) {
                0x04 -> { // FileBasicInformation
                    ByteArray(40).also {
                        fillTimes(it, 0, f)
                        it.putU32(32, attrsOf(f).toLong())
                    }
                }
                0x05 -> { // FileStandardInformation
                    ByteArray(24).also {
                        val len = if (f.isFile) f.length() else 0L
                        it.putU64(0, len)
                        it.putU64(8, len)
                        it.putU32(16, 1) // NumberOfLinks
                        it[20] = 0 // DeletePending
                        it[21] = if (f.isDirectory) 1 else 0
                    }
                }
                0x06 -> { // FileInternalInformation
                    ByteArray(8).also { it.putU64(0, f.absolutePath.hashCode().toLong() and 0xffffffffL) }
                }
                0x07 -> ByteArray(8) // FileEaInformation EaSize=0
                0x09 -> { // FileNameInformation
                    val nb = utf16Le(f.name)
                    ByteArray(4 + nb.size).also {
                        it.putU32(0, nb.size.toLong())
                        nb.copyInto(it, 4)
                    }
                }
                0x12 -> { // FileAllInformation — concatenate several
                    val basic = encodeFileInfo(0x04, f)!!
                    val standard = encodeFileInfo(0x05, f)!!
                    val internal = encodeFileInfo(0x06, f)!!
                    val ea = encodeFileInfo(0x07, f)!!
                    val access = ByteArray(4).also { it.putU32(0, 0x001F01FF) }
                    val position = ByteArray(8)
                    val mode = ByteArray(4)
                    val alignment = ByteArray(4)
                    val name = encodeFileInfo(0x09, f)!!
                    basic + standard + internal + ea + access + position + mode + alignment + name
                }
                0x22 -> { // FileNetworkOpenInformation
                    ByteArray(56).also {
                        fillTimes(it, 0, f)
                        val len = if (f.isFile) f.length() else 0L
                        it.putU64(32, len)
                        it.putU64(40, len)
                        it.putU32(48, attrsOf(f).toLong())
                    }
                }
                else -> null
            }
        }

        private fun encodeFsInfo(fsInfoClass: Int): ByteArray? {
            return when (fsInfoClass) {
                1 -> { // FileFsVolumeInformation
                    val label = utf16Le("GHOST")
                    ByteArray(18 + label.size).also {
                        it.putU64(0, fileTimeNow())
                        it.putU32(8, 0x12345678)
                        it.putU32(12, label.size.toLong())
                        it[16] = 0 // SupportsObjects
                        label.copyInto(it, 18)
                    }
                }
                3 -> { // FileFsSizeInformation
                    ByteArray(24).also {
                        it.putU64(0, 1_000_000) // TotalAllocationUnits
                        it.putU64(8, 500_000)
                        it.putU32(16, 1) // SectorsPerAllocationUnit
                        it.putU32(20, 512) // BytesPerSector
                    }
                }
                4 -> { // FileFsDeviceInformation
                    ByteArray(8).also {
                        it.putU32(0, 0x00000007) // FILE_DEVICE_DISK
                        it.putU32(4, 0)
                    }
                }
                5 -> { // FileFsAttributeInformation
                    val name = utf16Le("NTFS")
                    ByteArray(12 + name.size).also {
                        it.putU32(0, 0x00000003) // CasePreserved|CaseSensitive? use 0x03
                        it.putU32(4, 255)
                        it.putU32(8, name.size.toLong())
                        name.copyInto(it, 12)
                    }
                }
                6 -> { // FileFsFullSizeInformation
                    ByteArray(32).also {
                        it.putU64(0, 1_000_000)
                        it.putU64(8, 500_000)
                        it.putU64(16, 500_000)
                        it.putU32(24, 1)
                        it.putU32(28, 512)
                    }
                }
                7 -> ByteArray(8) // FileFsObjectIdInformation zeros
                else -> null
            }
        }

        private fun deriveSigningKey(sessionKey: ByteArray, dialect: Int): ByteArray {
            return if (dialect >= Smb2Dialect.SMB300) {
                // SP800-108 KDF in Counter Mode with HMAC-SHA256
                // Label=SMB2AESCMAC, Context=SmbSign (NUL separator added inside sp800108)
                sp800108(sessionKey, "SMB2AESCMAC".toByteArray(Charsets.US_ASCII), "SmbSign".toByteArray(Charsets.US_ASCII), 16)
            } else {
                sessionKey.copyOf(16)
            }
        }

        private fun sp800108(key: ByteArray, label: ByteArray, context: ByteArray, outLen: Int): ByteArray {
            // K(i) = HMAC-SHA256(key, i || label || 0x00 || context || L)
            val L = outLen * 8
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update(byteArrayOf(0, 0, 0, 1)) // i = 1
            mac.update(label)
            mac.update(0) // SP800-108 separator between Label and Context
            mac.update(context)
            mac.update(byteArrayOf(((L ushr 24) and 0xff).toByte(), ((L ushr 16) and 0xff).toByte(), ((L ushr 8) and 0xff).toByte(), (L and 0xff).toByte()))
            return mac.doFinal().copyOf(outLen)
        }

        private fun hmacSha256Trunc16(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data).copyOf(16)
        }

        private fun aesCmac(key: ByteArray, data: ByteArray): ByteArray {
            val cmac = CMac(AESEngine())
            cmac.init(KeyParameter(key.copyOf(16)))
            cmac.update(data, 0, data.size)
            val out = ByteArray(cmac.macSize)
            cmac.doFinal(out, 0)
            return out.copyOf(16)
        }
    }
}
