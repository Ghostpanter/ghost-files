// ADB client core shared by the file manager (AdbFs, tools) and the embedded scrcpy mirror:
// identity + pairing (code / QR), mDNS discovery, saved devices, connection pool, sync, remote helpers.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ghostpanter.adb"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
}

dependencies {
    api(project(":libadb"))
    api(project(":core"))
    api("org.bouncycastle:bcprov-jdk18on:1.84")
    api("org.bouncycastle:bcpkix-jdk18on:1.84")
    api("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
