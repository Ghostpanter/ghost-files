plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Screenshot tests of the dual-pane UI (JVM, no emulator): ./gradlew :app:recordPaparazziDebug
    id("app.cash.paparazzi")
}

android {
    namespace = "com.ghostpanter.files"
    // ColorOS compatibility: compile/target 34 (never 35+).
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.ghostpanter.files"
        minSdk = 28
        targetSdk = 34
        versionCode = 36
        versionName = "0.7.3-test1"
        // Universal APK: every ABI shipped by conscrypt-android.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    val signingReady = listOf("KEYSTORE_PATH", "KEYSTORE_PASS", "KEY_ALIAS", "KEY_PASS").all { !System.getenv(it).isNullOrEmpty() }
    signingConfigs {
        create("release") {
            if (signingReady) {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASS")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        // ColorOS installers reject extractNativeLibs=false: compress + extract on install.
        jniLibs { useLegacyPackaging = true; pickFirsts += "**/libc++_shared.so" }
        resources {
            excludes += listOf(
                "com/sun/jna/**",
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/services/**", "META-INF/*.version", "META-INF/*.properties", "META-INF/*.txt", "META-INF/*.prof", "META-INF/com/android/build/gradle/**", "META-INF/version-control-info.textproto", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/spring.*",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/io.netty.versions.properties",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC",
                "META-INF/BCKEY.*", "META-INF/BC1024KE.*", "META-INF/BC2048KE.*",
                "org/bouncycastle/pqc/**/*.properties", "org/bouncycastle/x509/*.properties",
                "**/*.md", "DebugProbesKt.bin",
            )
        }
    }
}

tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("AarMetadata")) {
        enabled = false
    }
}

dependencies {
    implementation(project(":core"))
    // Embedded scrcpy-android + ADB (M1–M3 of the device integration)
    implementation(project(":adb-core"))
    implementation(project(":scrcpy"))
    implementation(project(":terminal"))

    val composeBom = enforcedPlatform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Privilege
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Crypto providers: full BouncyCastle for sshj; Conscrypt for modern TLS (FTPS/HTTPS WebDAV).
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("org.conscrypt:conscrypt-android:2.6.1")

    // Media player (local + network streams)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // Built-in video player (16 KB page-aligned .so since 3.6)
    implementation("org.videolan.android:libvlc-all:3.7.2")
    // QR for HTTP remote URL
    implementation("com.google.zxing:core:3.5.3")

    // Image viewer: Coil 3 + Telephoto (zoom / tiled sub-sampling) + GIF/SVG/AVIF + EXIF
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-gif:3.2.0")
    implementation("io.coil-kt.coil3:coil-svg:3.2.0")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // libavif JNI (4 ABI, 16KB) for AVIF on API 28–30; API 31+ uses system ImageDecoder via Coil
    implementation("org.aomedia.avif.android:avif:1.3.0.841110fd")

    // PDF: PdfiumAndroidKt (Apache-2.0, text search, 4 ABI, 16KB); fallback = system PdfRenderer
    implementation("io.legere:pdfiumandroid:1.0.32")


    debugImplementation("androidx.compose.ui:ui-tooling")
}
// Note: META-INF/services/** is excluded because ColorOS-style V1 verification flags
// unsigned META-INF entries. R8 rewrites the coroutines ServiceLoader lookups
// (Dispatchers.Main) into direct instantiation, so nothing at runtime needs them;
// slf4j falls back to its NOP logger and security providers are registered in GhostApp.
