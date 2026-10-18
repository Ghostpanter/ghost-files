// Embedded scrcpy-android (0.5.28-tablet, Java/View): mirror, sessions, tablet tools, settings.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.ghostpanter.scrcpy"
    compileSdk = 34
    defaultConfig { minSdk = 28 }
    buildFeatures { aidl = true; buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
}

dependencies {
    api(project(":adb-core"))
    api(project(":terminal"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
