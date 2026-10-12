package com.ghostpanter.files.core.server

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Creates / loads a self-signed RSA keystore for FTPS and WebDAVs.
 * Clients must accept the certificate (TOFU); we never ship a fixed secret.
 */
object SelfSignedTls {
    private const val ALIAS = "ghost-files-hosted"
    private const val PASS = "ghost-files-tls"

    fun ensureKeyStore(file: File): KeyStore {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        if (file.exists()) {
            return KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, PASS.toCharArray()) }
            }
        }
        file.parentFile?.mkdirs()
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Ghost Files Hosted,O=Ghostpanter")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - 86_400_000L),
            Date(now + 3650L * 86_400_000L), subject, kp.public,
        )
        val cert: X509Certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)))
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, PASS.toCharArray())
        ks.setKeyEntry(ALIAS, kp.private, PASS.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, PASS.toCharArray()) }
        return ks
    }

    fun sslContext(ks: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS.toCharArray())
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        }
    }

    fun keyStorePassword(): CharArray = PASS.toCharArray()
    fun keyAlias(): String = ALIAS
}
