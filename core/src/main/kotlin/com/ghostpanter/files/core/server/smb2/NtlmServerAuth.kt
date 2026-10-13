package com.ghostpanter.files.core.server.smb2

import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal SPNEGO + NTLMv2 acceptor for hosted SMB2.
 * Credentials are checked against a single username/password (or anonymous/guest).
 */
internal class NtlmServerAuth(
    private val expectedUser: String,
    private val expectedPassword: String,
    private val allowAnonymous: Boolean,
    private val serverName: String = "GHOSTFILES",
    private val domainName: String = "WORKGROUP",
) {
    private val random = SecureRandom()

    data class ChallengeState(
        val serverChallenge: ByteArray,
        val targetInfo: ByteArray,
    )

    data class AuthResult(
        val ok: Boolean,
        val sessionBaseKey: ByteArray? = null,
        val isGuest: Boolean = false,
        val userName: String = "",
    )

    fun buildNegotiateToken(): ByteArray {
        // GSS-API SPNEGO NegTokenInit advertising NTLMSSP only (RFC 4178).
        // 60 [ Application 0 ] { OID 1.3.6.1.5.5.2, [0] NegTokenInit }
        val ntlmOid = byteArrayOf(
            0x06, 0x0a, 0x2b, 0x06, 0x01, 0x04, 0x01, 0x82.toByte(), 0x37, 0x02, 0x02, 0x0a,
        ) // 1.3.6.1.4.1.311.2.2.10
        val mechTypesSeq = derSeq(0x30, ntlmOid)                 // SEQUENCE OF OID
        val mechTypes = derSeq(0xA0, mechTypesSeq)                // [0] mechTypes
        val negTokenInitBody = derSeq(0x30, mechTypes)            // NegTokenInit SEQUENCE
        val negTokenInit = derSeq(0xA0, negTokenInitBody)         // CHOICE [0] NegTokenInit
        val spnegoOid = byteArrayOf(0x06, 0x06, 0x2b, 0x06, 0x01, 0x05, 0x05, 0x02)
        return derSeq(0x60, spnegoOid + negTokenInit)
    }

    fun beginChallenge(): Pair<ByteArray, ChallengeState> {
        val challenge = ByteArray(8).also { random.nextBytes(it) }
        val targetInfo = buildTargetInfo()
        val ntlmType2 = buildType2(challenge, targetInfo)
        val spnego = wrapChallenge(ntlmType2)
        return spnego to ChallengeState(challenge, targetInfo)
    }

    fun authenticate(securityBlob: ByteArray, state: ChallengeState?): AuthResult {
        val ntlm = unwrapNtlm(securityBlob) ?: return AuthResult(false)
        if (ntlm.size < 12) return AuthResult(false)
        val type = ntlm.getU32(8).toInt()
        if (type == 1) {
            // Client sent Type1 unexpectedly in SessionSetup2 path — treat as restart
            return AuthResult(false)
        }
        if (type != 3 || state == null) return AuthResult(false)
        return verifyType3(ntlm, state)
    }

    /** Returns true if blob is NTLM Type1 (possibly SPNEGO-wrapped). */
    fun isType1(securityBlob: ByteArray): Boolean {
        val ntlm = unwrapNtlm(securityBlob) ?: return false
        return ntlm.size >= 12 && ntlm.getU32(8).toInt() == 1
    }

    fun isType3(securityBlob: ByteArray): Boolean {
        val ntlm = unwrapNtlm(securityBlob) ?: return false
        return ntlm.size >= 12 && ntlm.getU32(8).toInt() == 3
    }

    private fun verifyType3(msg: ByteArray, state: ChallengeState): AuthResult {
        // Parse NTLM AUTHENTICATE (Type 3) fields — MS-NLMP §2.2.1.3
        if (msg.size < 64) return AuthResult(false)
        fun field(lenOff: Int): ByteArray {
            val len = msg.getU16(lenOff)
            val off = msg.getU32(lenOff + 4).toInt()
            if (len < 0 || off < 0 || off + len > msg.size) return ByteArray(0)
            return msg.copyOfRange(off, off + len)
        }
        val lmResp = field(12)
        val ntResp = field(20)
        val domainBytes = field(28)
        val userBytes = field(36)
        val workstation = field(44)
        val encryptedRandomSessionKey = field(52)
        val flags = if (msg.size >= 64) msg.getU32(60).toInt() else 0
        val user = fromUtf16Le(userBytes)
        val domain = fromUtf16Le(domainBytes)

        if (allowAnonymous && (user.isBlank() || user.equals("guest", true))) {
            return AuthResult(ok = true, sessionBaseKey = ByteArray(16), isGuest = true, userName = user.ifBlank { "guest" })
        }
        if (!user.equals(expectedUser, ignoreCase = true)) {
            return AuthResult(false)
        }
        if (ntResp.size < 16) return AuthResult(false)

        val responseKeyNT = ntowfV2(expectedPassword, user, domain)
        val ntProof = ntResp.copyOfRange(0, 16)
        val temp = ntResp.copyOfRange(16, ntResp.size)
        val expectedProof = hmacMd5(responseKeyNT, state.serverChallenge + temp)
        if (!MessageDigest.isEqual(ntProof, expectedProof)) {
            // Also try empty domain / uppercase domain variants
            val altKeys = listOf(
                ntowfV2(expectedPassword, user, ""),
                ntowfV2(expectedPassword, user, domainName),
                ntowfV2(expectedPassword, user, domain.uppercase()),
            )
            val matched = altKeys.any { key ->
                MessageDigest.isEqual(ntProof, hmacMd5(key, state.serverChallenge + temp))
            }
            if (!matched) return AuthResult(false)
            val key = altKeys.first { MessageDigest.isEqual(ntProof, hmacMd5(it, state.serverChallenge + temp)) }
            var sessionKey = hmacMd5(key, ntProof)
            if ((flags and FLAG_KEY_EXCH) != 0 && encryptedRandomSessionKey.size == 16) {
                sessionKey = rc4(sessionKey, encryptedRandomSessionKey)
            }
            return AuthResult(true, sessionKey, false, user)
        }
        var sessionKey = hmacMd5(responseKeyNT, ntProof)
        if ((flags and FLAG_KEY_EXCH) != 0 && encryptedRandomSessionKey.size == 16) {
            sessionKey = rc4(sessionKey, encryptedRandomSessionKey)
        }
        return AuthResult(true, sessionKey, false, user)
    }

    private fun buildType2(challenge: ByteArray, targetInfo: ByteArray): ByteArray {
        val targetName = utf16Le(serverName)
        // Keep flags minimal so SessionBaseKey is used directly (no KEY_EXCH unwrap).
        val flags = FLAG_UNICODE or FLAG_REQUEST_TARGET or FLAG_NTLM or
            FLAG_EXTENDED or FLAG_TARGET_INFO or FLAG_128 or FLAG_VERSION or FLAG_TARGET_TYPE_SERVER
        // header 56 + version 8 optional; we include version → payload starts at 56
        val headerSize = 56
        val targetNameOff = headerSize
        val targetInfoOff = targetNameOff + targetName.size
        val total = targetInfoOff + targetInfo.size
        val out = ByteArray(total)
        // Signature
        "NTLMSSP".toByteArray(Charsets.US_ASCII).copyInto(out)
        out[7] = 0
        out.putU32(8, 2) // Type 2
        out.putU16(12, targetName.size)
        out.putU16(14, targetName.size)
        out.putU32(16, targetNameOff.toLong())
        out.putU32(20, flags.toLong())
        challenge.copyInto(out, 24)
        // reserved 8 bytes at 32 already 0
        out.putU16(40, targetInfo.size)
        out.putU16(42, targetInfo.size)
        out.putU32(44, targetInfoOff.toLong())
        // Version (8 bytes) at 48
        out[48] = 10; out[49] = 0; out[50] = 0; out[51] = 0 // major/minor/build
        out[52] = 0; out[53] = 0; out[54] = 0; out[55] = 15 // revision NTLMSSP_REVISION_W2K3
        targetName.copyInto(out, targetNameOff)
        targetInfo.copyInto(out, targetInfoOff)
        return out
    }

    private fun buildTargetInfo(): ByteArray {
        fun av(id: Int, data: ByteArray): ByteArray {
            val o = ByteArray(4 + data.size)
            o.putU16(0, id); o.putU16(2, data.size); data.copyInto(o, 4); return o
        }
        val nbComputer = utf16Le(serverName)
        val nbDomain = utf16Le(domainName)
        val dnsComputer = utf16Le(serverName.lowercase())
        val dnsDomain = utf16Le(domainName.lowercase())
        val parts = listOf(
            av(1, nbComputer), // MsvAvNbComputerName
            av(2, nbDomain),   // MsvAvNbDomainName
            av(3, dnsComputer),
            av(4, dnsDomain),
            av(7, ByteArray(8).also { // MsvAvTimestamp
                val ft = fileTimeNow()
                it.putU64(0, ft)
            }),
            av(0, ByteArray(0)), // MsvAvEOL
        )
        return parts.reduce { a, b -> a + b }
    }

    private fun wrapChallenge(ntlmType2: ByteArray): ByteArray {
        // NegotiationToken ::= CHOICE { negTokenResp [1] NegTokenResp }
        // NegTokenResp ::= SEQUENCE {
        //   negState [0] ENUMERATED OPTIONAL,   -- accept-incomplete (1)
        //   responseToken [2] OCTET STRING OPTIONAL
        // }
        val negState = derSeq(0xA0, byteArrayOf(0x0A, 0x01, 0x01)) // ENUMERATED 1
        val responseToken = derSeq(0xA2, derSeq(0x04, ntlmType2))
        val negTokenResp = derSeq(0x30, negState + responseToken)
        return derSeq(0xA1, negTokenResp)
    }

    /** Final SPNEGO accept-completed with empty/no token (after successful Type3). */
    fun buildAcceptCompleted(): ByteArray {
        val negState = derSeq(0xA0, byteArrayOf(0x0A, 0x01, 0x00)) // accept-completed
        val negTokenResp = derSeq(0x30, negState)
        return derSeq(0xA1, negTokenResp)
    }

    private fun unwrapNtlm(blob: ByteArray): ByteArray? {
        if (blob.size >= 8 && blob.copyOfRange(0, 7).toString(Charsets.US_ASCII) == "NTLMSSP") return blob
        // Search for NTLMSSP inside SPNEGO
        for (i in 0 until blob.size - 7) {
            if (blob[i] == 'N'.code.toByte() && blob[i + 1] == 'T'.code.toByte() &&
                blob[i + 2] == 'L'.code.toByte() && blob[i + 3] == 'M'.code.toByte() &&
                blob[i + 4] == 'S'.code.toByte() && blob[i + 5] == 'S'.code.toByte() &&
                blob[i + 6] == 'P'.code.toByte() && blob[i + 7] == 0.toByte()
            ) {
                return blob.copyOfRange(i, blob.size)
            }
        }
        return null
    }

    companion object {
        private const val FLAG_UNICODE = 0x00000001
        private const val FLAG_REQUEST_TARGET = 0x00000004
        private const val FLAG_SIGN = 0x00000010
        private const val FLAG_SEAL = 0x00000020
        private const val FLAG_NTLM = 0x00000200
        private const val FLAG_ALWAYS_SIGN = 0x00008000
        private const val FLAG_TARGET_TYPE_SERVER = 0x00020000
        private const val FLAG_EXTENDED = 0x00080000
        private const val FLAG_TARGET_INFO = 0x00800000
        private const val FLAG_VERSION = 0x02000000
        private const val FLAG_128 = 0x20000000
        private const val FLAG_KEY_EXCH = 0x40000000

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        fun ntowfV2(password: String, user: String, domain: String): ByteArray {
            val ntHash = md4(utf16Le(password))
            val identity = utf16Le(user.uppercase() + domain)
            return hmacMd5(ntHash, identity)
        }

        fun md4(data: ByteArray): ByteArray {
            val d = MD4Digest()
            d.update(data, 0, data.size)
            val out = ByteArray(d.digestSize)
            d.doFinal(out, 0)
            return out
        }

        fun hmacMd5(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(key, "HmacMD5"))
            for (p in parts) mac.update(p)
            return mac.doFinal()
        }

        /** RC4 decrypt/encrypt (same). */
        fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            val out = ByteArray(data.size)
            var i = 0; j = 0
            for (k in data.indices) {
                i = (i + 1) and 0xff
                j = (j + s[i]) and 0xff
                val t = s[i]; s[i] = s[j]; s[j] = t
                out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xff]).toByte()
            }
            return out
        }

        fun derSeq(tag: Int, content: ByteArray): ByteArray {
            val len = content.size
            val lenBytes = when {
                len < 0x80 -> byteArrayOf(len.toByte())
                len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
                else -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), (len and 0xff).toByte())
            }
            return byteArrayOf(tag.toByte()) + lenBytes + content
        }
    }
}
