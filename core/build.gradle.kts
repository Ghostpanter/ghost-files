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
    // LAN protocols
    api("com.hierynomus:smbj:0.13.0")
    api("commons-net:commons-net:3.11.1")
    api("com.hierynomus:sshj:0.39.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // Archives
    api("org.apache.commons:commons-compress:1.27.1")
    api("org.tukaani:xz:1.10")
    api("com.github.junrar:junrar:7.5.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    testImplementation("org.apache.sshd:sshd-core:2.13.2")
    testImplementation("org.apache.sshd:sshd-sftp:2.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.test {
    useJUnit()
    maxHeapSize = "768m"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Copy>("dumpDeps") {
    from(configurations.testRuntimeClasspath)
    into(layout.buildDirectory.dir("deps"))
}
