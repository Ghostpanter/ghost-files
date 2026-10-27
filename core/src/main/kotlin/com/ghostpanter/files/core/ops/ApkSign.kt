package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.FsException
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.security.MessageDigest
import java.util.Base64

/**
 * MVP APK signing: JAR / APK Signature Scheme v1 (META-INF SF/RSA (JAR signing)) using a
 * generated or provided PKCS12 keystore. v2/v3 signing and zipalign are deferred.
 */
object ApkSign {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun ensureDebugKeystore(ksFile: File, password: String = "android", alias: String = "ghost"): KeyStore {
        if (ksFile.isFile) {
            return KeyStore.getInstance("PKCS12").also {
                FileInputStream(ksFile).use { ins -> it.load(ins, password.toCharArray()) }
            }
        }
        ksFile.parentFile?.mkdirs()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password.toCharArray())
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Ghostpanter"),
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 3650L * 86_400_000L),
            X500Name("CN=Ghostpanter"),
            kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider("BC").build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
        ks.setKeyEntry(alias, kp.private, password.toCharArray(), arrayOf(cert))
        FileOutputStream(ksFile).use { ks.store(it, password.toCharArray()) }
        return ks
    }

    fun signV1(inputApk: File, outputApk: File, ksFile: File, password: String = "android", alias: String = "ghost") {
        if (!inputApk.isFile) throw FsException(tr("APK 不存在", "APK not found"))
        val ks = ensureDebugKeystore(ksFile, password, alias)
        val key = ks.getKey(alias, password.toCharArray()) as java.security.PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val digests = LinkedHashMap<String, String>()
        JarFile(inputApk).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                if (e.name.startsWith("META-INF/", ignoreCase = true)) continue
                val md = MessageDigest.getInstance("SHA-256")
                jar.getInputStream(e).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n)
                    }
                }
                digests[e.name] = Base64.getEncoder().encodeToString(md.digest())
            }
        }

        val manifest = Manifest()
        manifest.mainAttributes.putValue("Manifest-Version", "1.0")
        manifest.mainAttributes.putValue("Created-By", "Dual-Pane File Manager")
        for ((name, dig) in digests) {
            val attrs = java.util.jar.Attributes()
            attrs.putValue("SHA-256-Digest", dig)
            manifest.entries[name] = attrs
        }

        // Build SF
        val sf = Manifest()
        sf.mainAttributes.putValue("Signature-Version", "1.0")
        sf.mainAttributes.putValue("Created-By", "Dual-Pane File Manager")
        val manBytes = java.io.ByteArrayOutputStream().also { manifest.write(it) }.toByteArray()
        sf.mainAttributes.putValue("SHA-256-Digest-Manifest", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(manBytes)))
        for ((name, attrs) in manifest.entries) {
            val baos = java.io.ByteArrayOutputStream()
            // individual section digest
            val section = "Name: $name\r\nSHA-256-Digest: ${attrs.getValue("SHA-256-Digest")}\r\n\r\n"
            val d = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(section.toByteArray(Charsets.UTF_8)))
            val a = java.util.jar.Attributes(); a.putValue("SHA-256-Digest", d)
            sf.entries[name] = a
        }
        val sfBytes = java.io.ByteArrayOutputStream().also { sf.write(it) }.toByteArray()

        // Sign SF with RSA
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(key)
        sig.update(sfBytes)
        val signature = sig.sign()
        // PKCS7 detached would be ideal; for MVP write raw .RSA + cert chain via BC CMS
        val cms = org.bouncycastle.cms.CMSSignedDataGenerator().also { gen ->
            val sha = org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
            gen.addSignerInfoGenerator(
                org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(sha)
                    .build(JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(key), cert)
            )
            gen.addCertificate(org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(cert))
        }
        val signed = cms.generate(org.bouncycastle.cms.CMSProcessableByteArray(sfBytes), false).encoded

        JarFile(inputApk).use { jar ->
            JarOutputStream(FileOutputStream(outputApk)).use { out ->
                out.putNextEntry(JarEntry("META-INF/MANIFEST.MF")); out.write(manBytes); out.closeEntry()
                out.putNextEntry(JarEntry("META-INF/CERT.SF")); out.write(sfBytes); out.closeEntry()
                out.putNextEntry(JarEntry("META-INF/CERT.RSA")); out.write(signed); out.closeEntry()
                val entries = jar.entries()
                val buf = ByteArray(64 * 1024)
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.startsWith("META-INF/", ignoreCase = true)) continue
                    val je = JarEntry(e.name)
                    out.putNextEntry(je)
                    if (!e.isDirectory) jar.getInputStream(e).use { ins ->
                        while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    }
                    out.closeEntry()
                }
            }
        }
    }
}
