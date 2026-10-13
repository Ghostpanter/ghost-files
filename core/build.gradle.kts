plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // LAN client protocols
    api("com.hierynomus:smbj:0.13.0")
    api("com.rapid7.client:dcerpc:0.12.13") {
        exclude(group = "com.hierynomus", module = "smbj")
        exclude(group = "org.bouncycastle")
    }
    api("commons-net:commons-net:3.11.1")
    api("com.hierynomus:sshj:0.39.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Hosted LAN servers (no Root needed for high ports)
    // Hosted SMB2/3 is implemented in-process (see server/smb2); no JFileServer.
    api("org.apache.ftpserver:ftpserver-core:1.2.0")
    api("org.apache.sshd:sshd-core:2.13.2")
    api("org.apache.sshd:sshd-sftp:2.13.2")
    api("org.slf4j:slf4j-nop:2.0.13")
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // Archives
    api("org.apache.commons:commons-compress:1.27.1")
    api("org.tukaani:xz:1.10")
    api("com.github.junrar:junrar:7.5.5")
    // Encrypted ZIP + in-archive edit
    api("net.lingala.zip4j:zip4j:2.11.5")
    // Zstd / LZ4
    api("com.github.luben:zstd-jni:1.5.6-6")
    api("org.lz4:lz4-java:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.test {
    useJUnit()
    maxHeapSize = "768m"
    listOf("GF_SMB_PORT", "GF_SMB_PUBLIC_DIR", "GF_SMB_PASSWORD", "GF_SMB_BIG", "GF_SMB_DEBUG").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
    inputs.property("smbTestPort", System.getenv("GF_SMB_PORT") ?: "")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
