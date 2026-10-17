package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.FsException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Single-file encrypted container (AES-256-GCM).
 * Magic "GFENC1". Not EncFS/gocryptfs directory trees; not AES Crypt desktop wire-compat
 * (those remain deferred). Extension convention: `.gfenc` or `.aes`.
 */
object AesCrypt {
    private val MAGIC = "GFENC1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val TAG = 16
    private const val ITER = 120_000

    fun isContainer(file: File): Boolean = file.inputStream().use { ins ->
        val b = ByteArray(MAGIC.size)
        ins.read(b) == MAGIC.size && b.contentEquals(MAGIC)
    }

    fun encrypt(src: File, dst: File, password: String) {
        if (password.isEmpty()) throw FsException(tr("密码不能为空", "Password cannot be empty"))
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val key = derive(password, salt)
        val plain = src.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, iv))
        val cipher = c.doFinal(plain)
        dst.outputStream().use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            out.write(cipher)
        }
        key.fill(0)
    }

    fun decrypt(src: File, dst: File, password: String) {
        if (password.isEmpty()) throw FsException(tr("密码不能为空", "Password cannot be empty"))
        val all = src.readBytes()
        if (all.size < MAGIC.size + SALT + IV + TAG) throw FsException(tr("文件过短或损坏", "File too short or corrupted"))
        if (!all.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            if (all.size >= 3 && all[0] == 0x41.toByte() && all[1] == 0x45.toByte() && all[2] == 0x53.toByte()) {
                throw FsException(tr("检测到 AES Crypt 桌面格式，当前仅支持本应用 GFENC1 容器（.gfenc）", "AES Crypt desktop format detected; only this app's GFENC1 container (.gfenc) is supported"))
            }
            throw FsException(tr("不是受支持的加密容器", "Not a supported encrypted container"))
        }
        var o = MAGIC.size
        val salt = all.copyOfRange(o, o + SALT); o += SALT
        val iv = all.copyOfRange(o, o + IV); o += IV
        val cipherBytes = all.copyOfRange(o, all.size)
        val key = derive(password, salt)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, iv))
            dst.writeBytes(c.doFinal(cipherBytes))
        } catch (e: Exception) {
            throw FsException(tr("密码错误或文件损坏", "Wrong password or corrupted file"), e)
        } finally {
            key.fill(0)
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(password.toByteArray(Charsets.UTF_8), salt, ITER)
        return (gen.generateDerivedParameters(256) as KeyParameter).key
    }
}
