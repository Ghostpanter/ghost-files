// Vendored libadb-android (MuntashirAkon, GPL-3.0-or-later OR Apache-2.0; used under Apache-2.0).
// TCP / TLS / RSA transports only (no USB, no shell_v2). Copied from scrcpy-android vendor/libadb-android.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    // One BouncyCastle for the whole app: jdk18on (same artifacts sshj / sshd / pairing use).
    api("org.bouncycastle:bcprov-jdk18on:1.84")
    api("org.conscrypt:conscrypt-android:2.6.1")
    implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1") {
        exclude(group = "androidx.annotation", module = "annotation")
    }
    testImplementation("junit:junit:4.13.2")
}
