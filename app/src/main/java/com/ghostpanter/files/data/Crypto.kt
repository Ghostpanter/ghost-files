package com.ghostpanter.files.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts saved server passwords with a non-exportable Android Keystore AES key. */
object Crypto {
    private const val ALIAS = "ghost_files_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrElse { "p:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP) }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            when {
                stored.startsWith("v1:") -> {
                    val (_, iv, data) = stored.split(':')
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                    String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
                }
                stored.startsWith("p:") -> String(Base64.decode(stored.substring(2), Base64.NO_WRAP))
                else -> stored
            }
        }.getOrDefault("")
    }
}
