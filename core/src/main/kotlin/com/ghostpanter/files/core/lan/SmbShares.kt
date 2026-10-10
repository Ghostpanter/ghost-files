package com.ghostpanter.files.core.lan

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.NetTimeouts
import com.ghostpanter.files.core.SmbConnector
import com.ghostpanter.files.core.SmbLogin
import com.ghostpanter.files.core.SmbNoGuestException
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.Session
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories

/** One entry of a NetShareEnum (SHARE_INFO_1) reply. */
data class SmbShare(val name: String, val type: Int, val remark: String = "") {
    /** STYPE_* base type (low bits); STYPE_SPECIAL (0x80000000) marks admin shares. */
    val baseType: Int get() = type and 0x0FFFFFFF
    val isDisk: Boolean get() = baseType == STYPE_DISKTREE
    val isSpecial: Boolean get() = (type and STYPE_SPECIAL) != 0 || name.endsWith("$")
    /** Shares a user can browse: disk shares that are not hidden/admin ($) shares. */
    val isUserVisible: Boolean get() = isDisk && !isSpecial

    companion object {
        const val STYPE_DISKTREE = 0x0
        const val STYPE_PRINTQ = 0x1
        const val STYPE_DEVICE = 0x2
        const val STYPE_IPC = 0x3
        const val STYPE_SPECIAL = 0x80000000.toInt()
    }
}

data class ShareListing(val shares: List<SmbShare>, val login: SmbLogin) {
    val visible: List<SmbShare> get() = SmbShares.userVisible(shares)
}

/** Thrown when neither anonymous nor guest may enumerate shares: ask the user for credentials. */
class SmbAuthRequiredException(message: String) : FsException(message)

/**
 * Lists the shares of an SMB server with SRVSVC NetrShareEnum over the IPC$ named pipe
 * (what Windows Explorer does when you open \\server). Tries anonymous, then guest, unless
 * credentials are given.
 */
object SmbShares {
    fun userVisible(all: List<SmbShare>): List<SmbShare> =
        all.filter { it.isUserVisible }.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }

    fun list(
        host: String,
        port: Int = 445,
        username: String = "",
        password: String = "",
        domain: String = "",
        timeouts: NetTimeouts = NetTimeouts(),
    ): ShareListing {
        SmbConnector(timeouts).use { c ->
            val session = try {
                c.open(host, port, username, password, domain)
            } catch (e: SmbNoGuestException) {
                throw SmbAuthRequiredException("服务器不允许匿名/来宾列出共享，请输入用户名和密码")
            } catch (e: SMBApiException) {
                if (isAuthFailure(e)) throw SmbAuthRequiredException("用户名或密码错误")
                throw FsException("登录失败：${e.status.name}", e)
            } catch (e: java.io.IOException) {
                throw FsException("无法连接 $host:$port（${e.message}）", e)
            }
            try {
                return ShareListing(enumerate(session), c.lastLogin ?: SmbLogin.USER)
            } catch (e: SMBApiException) {
                if (isAuthFailure(e) && username.isEmpty()) throw SmbAuthRequiredException("来宾无权列出共享，请输入用户名和密码")
                throw FsException("列出共享失败：${e.status.name}", e)
            } finally {
                runCatching { session.close() }
            }
        }
    }

    private fun isAuthFailure(e: SMBApiException): Boolean = e.status.name.let {
        it.contains("LOGON") || it.contains("ACCESS_DENIED") || it.contains("WRONG_PASSWORD") || it.contains("ACCOUNT")
    }

    /** NetShareEnum level 1 (name, type, remark); falls back to level 0 (names only). */
    fun enumerate(session: Session): List<SmbShare> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        val srv = ServerService(transport)
        return try {
            srv.shares1.map { SmbShare(it.netName, it.type, it.remark ?: "") }
        } catch (e: java.io.IOException) {
            srv.shares0.map { SmbShare(it.netName, if (it.netName.endsWith("$")) SmbShare.STYPE_SPECIAL else 0) }
        }
    }
}
