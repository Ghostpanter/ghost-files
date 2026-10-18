# Networking / crypto libraries use reflection & service lookup heavily: keep them whole.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class com.github.junrar.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class com.jcraft.jzlib.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn javax.el.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.lang.model.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.**
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.lang3.**
-dontwarn org.apache.commons.codec.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.**
-dontwarn org.jetbrains.annotations.**
-dontwarn edu.umd.cs.findbugs.**

# SRVSVC share enumeration (rapid7 dcerpc) and its helpers
-keep class com.rapid7.client.dcerpc.** { *; }
-dontwarn com.rapid7.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.apache.commons.lang3.**

# Hosted LAN servers
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.sshd.** { *; }
-keep class org.apache.mina.** { *; }
-keep class org.apache.commons.logging.** { *; }
-dontwarn org.apache.mina.**
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.sshd.**
-dontwarn org.apache.commons.logging.**
-dontwarn edu.umd.cs.findbugs.annotations.**

# v0.5.0 extras
-keep class net.lingala.zip4j.** { *; }
-keep class com.github.luben.zstd.** { *; }
-keep class net.jpountz.** { *; }
-keep class com.google.zxing.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn com.github.luben.zstd.**
-dontwarn net.jpountz.**
-dontwarn androidx.media3.**

# libVLC: JNI calls back into these classes by name
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**

# Embedded scrcpy-android / libadb (0.7.0)
# Conscrypt's pre-KitKat adapter references Harmony classes absent at runtime.
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn com.android.org.conscrypt.SSLParametersImpl
# SPAKE2 pairing bridge is native and resolves methods by JNI name.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-dontwarn io.github.muntashirakon.crypto.spake2.**
# Shizuku UserService is instantiated reflectively by class name.
-keep class com.ghostpanter.scrcpy.ShellUserService { *; }
-keep class com.ghostpanter.scrcpy.IShellUserService { *; }
-keep class com.ghostpanter.scrcpy.IShellUserService$* { *; }
# libadb: keep the (small) library intact; TLS/pairing code touches hidden APIs and BC/Conscrypt by name.
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**
