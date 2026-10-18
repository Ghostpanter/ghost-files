package com.ghostpanter.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

class AdbToolsTest {

    @Test fun deviceInfoParses() {
        val out = """
@@prop
[ro.product.brand]: [Xiaomi]
[ro.product.model]: [23049RAD8C]
[ro.build.version.release]: [14]
[ro.build.version.sdk]: [34]
@@battery
Current Battery Service state:
  AC powered: false
  USB powered: true
  status: 2
  health: 2
  level: 87
  voltage: 4312
  temperature: 312
@@cpu
processor	: 0
Hardware	: Qualcomm SM8550
cores=8
maxfreq=2016000
maxfreq=3187200
@@mem
MemTotal:       11788840 kB
MemAvailable:    5242880 kB
@@df
Filesystem      1K-blocks     Used Available Use% Mounted on
/dev/block/dm-48 229117412 81234567 147882845  36% /data
@@wm
Physical size: 1440x3200
Physical density: 560
@@net
1: lo    inet 127.0.0.1/8 scope host lo
47: wlan0    inet 192.168.1.23/24 brd 192.168.1.255 scope global wlan0
47: wlan0    inet6 fe80::1/64 scope link
@@uptime
93784.12 300000.00
@@end
""".trimStart()
        val g = DeviceInfo.parse(out).associateBy { it.title }
        val dev = g.values.first().rows.toMap()
        assertEquals("Xiaomi 23049RAD8C", dev.values.first())
        val all = g.values.flatMap { it.rows }.toMap()
        assertEquals("87%", all.values.first { it.endsWith("%") })
        assertTrue(all.values.any { it == "3.19 GHz" })
        assertTrue(all.values.any { it == "1440x3200" })
        assertTrue(all.values.any { it == "192.168.1.23" })
        assertTrue(all.values.any { it.contains("31.2") })
        assertTrue(all.values.any { it == "1d 02:03" })
        assertEquals("Xiaomi 23049RAD8C", DeviceInfo.model(out))
    }

    @Test fun remoteAppsParse() {
        val out = """
@@all
package:/data/app/~~ab==/com.foo-x9Q==/base.apk=com.foo versionCode:42 uid:10123
package:/system/app/Bar/Bar.apk=com.android.bar versionCode:34 uid:1000
package:/system/priv-app/Off/Off.apk=com.off versionCode:1 uid:10050
@@user
package:com.foo
@@disabled
package:com.off
@@any
package:com.foo
package:com.android.bar
package:com.off
package:com.gone
@@end
"""
        val apps = RemoteApps.parse(out).associateBy { it.pkg }
        assertEquals(4, apps.size)
        val foo = apps.getValue("com.foo")
        assertEquals("/data/app/~~ab==/com.foo-x9Q==/base.apk", foo.apkPath)
        assertEquals(42L, foo.versionCode); assertEquals(10123, foo.uid); assertFalse(foo.system)
        assertTrue(apps.getValue("com.android.bar").system)
        assertTrue(apps.getValue("com.off").disabled)
        assertTrue(apps.getValue("com.gone").uninstalled)
        assertEquals("pm uninstall -k --user 0 'com.android.bar'", RemoteApps.uninstall(apps.getValue("com.android.bar")).replace("\"", "'"))
        assertEquals(listOf("/a/base.apk", "/a/split_x.apk"), RemoteApps.parsePaths("package:/a/base.apk\npackage:/a/split_x.apk\n"))
    }

    @Test fun processesParse() {
        val out = """
  PID  PPID USER            RSS %CPU S NAME
    1     0 root          12000  0.0 S init
 4321   700 u0_a123      250000  3.5 S com.foo:remote
 999      1 shell          2000  0.0 R ps
"""
        val ps = Processes.parse(out)
        assertEquals(2, ps.size)
        val p = ps[1]
        assertEquals(4321, p.pid); assertEquals(250000L, p.rssKb); assertEquals(3.5f, p.cpu); assertEquals("com.foo", p.pkg)
        assertNull(ps[0].pkg)
    }

    @Test fun logcatParseAndFilter() {
        val l = Logcat.parse("10-18 10:20:30.123  1234  1250 W ActivityManager: Slow operation: took 300ms")
        assertNotNull(l); l!!
        assertEquals('W', l.level); assertEquals("ActivityManager", l.tag); assertEquals(1234, l.pid)
        assertTrue(Logcat.matches(l, 'I', "activity", null, ""))
        assertFalse(Logcat.matches(l, 'E', "", null, ""))
        assertFalse(Logcat.matches(l, 'V', "", 1, ""))
        assertNull(Logcat.parse("--------- beginning of main"))
    }

    @Test fun activationsOnlyVerified() {
        val a = Activations.all()
        assertTrue(a.filter { it.commands.isNotEmpty() }.all { it.sources.isNotEmpty() })
        assertTrue(a.first { it.id == "scene" }.commands.isEmpty())
    }

    @Test fun zipRangeReadsEntries() {
        val bo = ByteArrayOutputStream()
        val big = ByteArray(200_000) { (it % 251).toByte() }
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("manifest".toByteArray()); z.closeEntry()
            val st = ZipEntry("resources.arsc").apply { method = ZipEntry.STORED; size = big.size.toLong(); compressedSize = size; crc = CRC32().apply { update(big) }.value }
            z.putNextEntry(st); z.write(big); z.closeEntry()
            z.putNextEntry(ZipEntry("res/a.png")); z.write(ByteArray(5000) { 7 }); z.closeEntry()
        }
        val bytes = bo.toByteArray()
        var reads = 0
        val zr = ZipRange(bytes.size.toLong()) { off, len -> reads++; bytes.copyOfRange(off.toInt(), minOf(bytes.size, off.toInt() + len)) }
        assertEquals(setOf("AndroidManifest.xml", "resources.arsc", "res/a.png"), zr.entries.keys)
        assertArrayEquals("manifest".toByteArray(), zr.bytes("AndroidManifest.xml"))
        assertArrayEquals(big, zr.bytes("resources.arsc"))
        assertEquals(5000, zr.bytes("res/a.png")!!.size)
        assertNull(zr.bytes("missing"))
    }

    @Test fun storedAlignedZipIsValidAndAligned() {
        val f = File.createTempFile("mini", ".apk")
        val data = linkedMapOf("AndroidManifest.xml" to ByteArray(13) { 1 }, "resources.arsc" to ByteArray(1001) { 2 }, "res/x.png" to ByteArray(3) { 3 })
        f.outputStream().use { ZipRange.writeStoredAligned(data, it) }
        ZipFile(f).use { z ->
            for ((n, b) in data) assertArrayEquals(b, z.getInputStream(z.getEntry(n)).readBytes())
        }
        val all = f.readBytes()
        val zr = ZipRange(all.size.toLong()) { off, len -> all.copyOfRange(off.toInt(), off.toInt() + len) }
        for (e in zr.entries.values) {
            val nameLen = ZipRange.u16(all, e.localOffset.toInt() + 26); val extra = ZipRange.u16(all, e.localOffset.toInt() + 28)
            assertEquals(0L, (e.localOffset + 30 + nameLen + extra) % 4)
        }
        f.delete()
    }
}
