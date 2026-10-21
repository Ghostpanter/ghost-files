package com.ghostpanter.files.core.space

import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.tempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

class SpaceTreeTest {
    private val day = 86_400_000L

    @Test fun sizesPropagateAndSmallFilesFold() {
        val b = SpaceTreeBuilder("/s", smallBytes = 100, maxSmallPerDir = 3)
        b.addFile("/s/a/big.mkv", 1000, 5)
        repeat(3) { b.addFile("/s/a/t$it.jpg", 10, 1) }
        assertEquals(4, b.root.child("a")!!.children!!.size)
        b.addFile("/s/a/t3.txt", 20, 9)
        val a = b.root.child("a")!!
        assertEquals(2, a.children!!.size)
        val small = a.children!!.first { it.kind == NodeKind.SMALL_FILES }
        assertEquals(4, small.count); assertEquals(50, small.size); assertEquals(9, small.mtime)
        assertEquals(30, small.catSizes!![FileCategory.IMAGE.ordinal]); assertEquals(20, small.catSizes!![FileCategory.DOC.ordinal])
        b.addFile("/s/a/t4.png", 5, 1)
        assertEquals(5, small.count)
        assertEquals(1055, a.size); assertEquals(6, a.count)
        assertEquals(1055, b.root.size); assertEquals(6, b.root.count)
        assertEquals(6, b.files)
    }

    @Test fun hardLinksCountOnceAndAliasDirsAreSkipped() {
        val b = SpaceTreeBuilder("/")
        b.addDir("/data", 0, dev = 1, ino = 2)
        b.addDir("/data/a", 0, dev = 1, ino = 10)
        b.addFile("/data/a/x.bin", 500_000, 0, dev = 1, ino = 100, nlink = 2)
        b.addFile("/data/a/y.bin", 500_000, 0, dev = 1, ino = 100, nlink = 2)
        b.addFile("/data/a/z.bin", 500_000, 0, dev = 1, ino = 101, nlink = 1)
        b.addDir("/data/b", 0, dev = 1, ino = 10)
        b.addDir("/data/b/sub", 0, dev = 1, ino = 11)
        b.addFile("/data/b/x.bin", 500_000, 0, dev = 1, ino = 100, nlink = 2)
        b.addFile("/data/b/sub/q.bin", 700_000, 0, dev = 1, ino = 300)
        assertEquals(1_000_000, b.root.size)
        assertNull(b.root.child("data")!!.child("b"))
        assertEquals(500_000, b.dedupedBytes)
        b.addFile("/data/a/w.bin", 500_000, 0, dev = 2, ino = 100, nlink = 2)
        assertEquals(1_500_000, b.root.size)
    }

    @Test fun pathsAndFind() {
        val b = SpaceTreeBuilder("/storage/emulated/0")
        b.addFile("/storage/emulated/0/DCIM/Camera/1.jpg", 1 shl 20, 0)
        val cam = b.tree.find("/storage/emulated/0/DCIM/Camera")!!
        assertEquals("/storage/emulated/0/DCIM/Camera", cam.path())
        assertEquals(listOf("/storage/emulated/0", "DCIM", "Camera"), cam.chain().map { it.name })
        assertNull(b.tree.find("/storage/emulated/0/Nope"))
        assertNull(b.tree.find("/other"))
        b.addFile("/elsewhere/x", 10, 0)
        assertEquals(1L shl 20, b.root.size)
        val r = SpaceTreeBuilder("/")
        r.addFile("/data/x.bin", 1, 0)
        assertEquals("/data/x.bin", r.root.child("data")!!.child("x.bin")!!.path())
        assertEquals("/data", r.tree.find("/data")!!.path())
    }

    @Test fun filtersByTypeSizeAndTime() {
        val now = 1_800_000_000_000L
        val b = SpaceTreeBuilder("/s", smallBytes = 100, maxSmallPerDir = 1)
        b.addFile("/s/v/a.mp4", 5_000, now - 2 * day)
        b.addFile("/s/v/b.mp4", 50_000, now - 400 * day)
        b.addFile("/s/p/c.jpg", 3_000, now - 1 * day)
        b.addFile("/s/p/s1.jpg", 10, now)
        b.addFile("/s/p/s2.jpg", 10, now)
        val root = b.root
        assertEquals(58_020, SpaceFilter().apply(root))
        assertEquals(55_000, SpaceFilter(category = FileCategory.VIDEO).apply(root))
        assertEquals(0, root.child("p")!!.shown)
        assertEquals(3_020, SpaceFilter(category = FileCategory.IMAGE).apply(root))
        assertEquals(50_000, SpaceFilter(minBytes = 10_000).apply(root))
        assertEquals(50_000, SpaceFilter(olderThan = now - 365 * day).apply(root))
        assertEquals(8_000, SpaceFilter(newerThan = now - 7 * day).apply(root))
        assertEquals(5_000, SpaceFilter(category = FileCategory.VIDEO, newerThan = now - 7 * day).apply(root))
        val top = SpaceQueries.topFiles(root, 2)
        assertEquals(listOf("b.mp4", "a.mp4"), top.map { it.name })
        assertEquals(listOf("c.jpg"), SpaceQueries.topFiles(root, 5, SpaceFilter(category = FileCategory.IMAGE)).map { it.name })
    }

    @Test fun emptyDirsAreTopmostOnly() {
        val b = SpaceTreeBuilder("/s")
        b.addDir("/s/e1", 0); b.addDir("/s/e1/e2", 0); b.addDir("/s/e1/e2/e3", 0)
        b.addDir("/s/full", 0); b.addFile("/s/full/f.txt", 10, 0); b.addDir("/s/full/hole", 0)
        assertEquals(listOf("/s/e1", "/s/full/hole"), SpaceQueries.emptyDirs(b.root).map { it.path() }.sorted())
    }

    @Test fun detachSubtractsFromAncestors() {
        val b = SpaceTreeBuilder("/s")
        b.addFile("/s/a/b/x.bin", 1_000_000, 0); b.addFile("/s/a/y.bin", 500_000, 0)
        val bDir = b.tree.find("/s/a/b")!!
        SpaceQueries.detach(bDir)
        assertEquals(500_000, b.root.size); assertEquals(1, b.root.count)
        assertNull(b.tree.find("/s/a/b"))
    }

    @Test fun cacheRoundTrip() {
        val b = SpaceTreeBuilder("/s", smallBytes = 100, maxSmallPerDir = 1)
        b.addFile("/s/a/x.mp4", 5000, 7); b.addFile("/s/a/s1", 1, 1); b.addFile("/s/a/s2", 2, 2)
        b.root.child("a")!!.pkg = "com.x.y"; b.root.child("a")!!.flags = NodeFlags.RESIDUE
        b.tree.scannedAt = 123
        val bo = ByteArrayOutputStream()
        SpaceCache.write(DataOutputStream(bo), b.tree, mapOf("tier" to "root"))
        val l = SpaceCache.read(DataInputStream(ByteArrayInputStream(bo.toByteArray())))
        assertEquals("root", l.meta["tier"]); assertEquals(123, l.tree.scannedAt)
        val a = l.tree.find("/s/a")!!
        assertEquals(5003, a.size); assertEquals("com.x.y", a.pkg); assertTrue(a.has(NodeFlags.RESIDUE))
        assertEquals(3, a.children!!.first { it.kind == NodeKind.SMALL_FILES }.size)
        assertEquals(FileCategory.VIDEO, a.child("x.mp4")!!.category)
        assertEquals(a, a.child("x.mp4")!!.parent)
    }

    @Test fun pathCanonDedupesStorageAliases() {
        assertEquals("/data/media/0/DCIM", PathCanon.canonical("/sdcard/DCIM"))
        assertEquals("/data/media/0", PathCanon.canonical("/storage/emulated/0/"))
        assertEquals("/data/media/0/x", PathCanon.canonical("/storage/self/primary/x"))
        assertEquals("/data/media/10/a", PathCanon.canonical("/mnt/user/0/emulated/10/a"))
        assertEquals("/storage/ABCD-1234", PathCanon.canonical("/storage/ABCD-1234"))
        assertEquals("/storage/emulated/0/Download", PathCanon.userVisible("/data/media/0/Download"))
        assertEquals("/data/data", PathCanon.userVisible("/data/data"))
        assertEquals(
            listOf("/data", "/system"),
            PathCanon.dedupeRoots(listOf("/data", "/sdcard", "/storage/emulated/0", "/data/media/0", "/system", "/data/data")),
        )
        assertEquals(listOf("/data/media/0", "/data/app"), PathCanon.dedupeRoots(listOf("/sdcard", "/data/media/0/DCIM", "/storage/emulated/0", "/data/app")))
    }

    @Test fun protectionLevels() {
        assertEquals(Protection.NONE, SpacePolicy.protection("/storage/emulated/0/Download/a.zip"))
        assertEquals(Protection.NONE, SpacePolicy.protection("/data/media/0/DCIM"))
        assertEquals(Protection.CAUTION, SpacePolicy.protection("/data/data/com.x/cache"))
        assertEquals(Protection.CAUTION, SpacePolicy.protection("/data/app/~~a==/com.x-1"))
        assertEquals(Protection.CAUTION, SpacePolicy.protection("/data/log"))
        assertEquals(Protection.SYSTEM_READ_ONLY, SpacePolicy.protection("/system/app/Foo.apk"))
        assertEquals(Protection.SYSTEM_READ_ONLY, SpacePolicy.protection("/vendor"))
        assertEquals(Protection.SYSTEM_READ_ONLY, SpacePolicy.protection("/my_product/app"))
        assertEquals(Protection.SYSTEM_READ_ONLY, SpacePolicy.protection("/"))
        assertTrue(SpacePolicy.readOnlyPartition("/product/overlay"))
        assertFalse(SpacePolicy.readOnlyPartition("/data/app"))
    }

    @Test fun aggregatesAddUpToUsedSpace() {
        val b = SpaceTreeBuilder("/storage/emulated/0")
        b.addFile("/storage/emulated/0/DCIM/a.jpg", 10_000_000, 0)
        b.addFile("/storage/emulated/0/Android/media/com.a/x.mp4", 2_000_000, 0)
        b.addFile("/storage/emulated/0/Android/data/com.b/files/y.bin", 3_000_000, 0)
        val scan = b.root
        val apps = listOf(AppStat("com.a", "A", 40_000_000, 12_000_000, 1_000_000), AppStat("com.b", "B", 20_000_000, 8_000_000, 0))
        val specific = StorageAggregate.appSpecificBytes(scan)
        assertEquals(5_000_000, specific)
        val r = StorageAggregate.compose("内部存储", total = 256_000_000, free = 100_000_000, scan = scan, scannedAppSpecific = specific, apps = apps, appsLabel = "应用", systemLabel = "系统")
        assertEquals(156_000_000, r.used)
        assertEquals(15_000_000, r.scanned)
        assertEquals(75_000_000, r.appsBlock)
        assertEquals(66_000_000, r.systemBlock)
        assertEquals(r.used, r.root.size)
        assertEquals(r.used, r.root.children!!.sumOf { it.size })
        val group = r.root.children!!.first { it.name == "应用" }
        assertEquals(75_000_000, group.children!!.sumOf { it.size })
        assertTrue(group.children!!.all { it.has(NodeFlags.APP) && it.pkg != null })
        assertEquals(10_000_000, SpaceFilter(category = FileCategory.IMAGE).apply(r.root))
        val r2 = StorageAggregate.compose("x", 256_000_000, 100_000_000, scan, specific, null, "应用", "系统", freeLabel = "可用")
        assertEquals(141_000_000, r2.systemBlock)
        assertEquals(r2.used, r2.root.size)
        assertNotNull(r2.root.children!!.firstOrNull { it.has(NodeFlags.FREE) })
    }

    @Test fun localWalkerSkipsSymlinksAndRefreshesIncrementally() {
        val d = tempDir("space")
        File(d, "a/b").mkdirs()
        File(d, "a/one.bin").writeBytes(ByteArray(300_000))
        File(d, "a/b/two.bin").writeBytes(ByteArray(200_000))
        File(d, "top.txt").writeBytes(ByteArray(1234))
        Files.createSymbolicLink(File(d, "loop").toPath(), d.toPath())
        Files.createSymbolicLink(File(d, "a/link.bin").toPath(), File(d, "a/one.bin").toPath())
        val b1 = SpaceTreeBuilder(d.path)
        val w1 = LocalWalker(threads = 3)
        w1.walk(d.path, b1, ScanControl())
        assertEquals(501_234, b1.root.size)
        assertNull(b1.root.child("loop"))
        assertEquals(3, b1.root.count)
        File(d, "a/b/three.bin").writeBytes(ByteArray(100_000))
        File(d, "a/b").setLastModified(System.currentTimeMillis() + 5000)
        val b2 = SpaceTreeBuilder(d.path)
        val w2 = LocalWalker(threads = 2)
        w2.walk(d.path, b2, ScanControl(), previous = b1.root)
        assertEquals(601_234, b2.root.size)
        assertTrue("reused ${w2.reusedDirs.get()}", w2.reusedDirs.get() >= 2)
        assertEquals(1, w2.listedDirs.get())
    }

    @Test fun walkerCancels() {
        val d = tempDir("space")
        repeat(30) { File(d, "d$it/e").mkdirs(); File(d, "d$it/e/f").writeText("x") }
        val ctl = ScanControl(); ctl.cancel()
        try { LocalWalker().walk(d.path, SpaceTreeBuilder(d.path), ctl); throw AssertionError("not cancelled") } catch (_: ScanCancelled) {}
    }

    private val sh = ShellRunner { script -> ProcessBuilder("sh", "-c", script).start() }

    @Test fun shellStreamScanParsesFindOutputWithHardLinks() {
        val d = tempDir("space")
        File(d, "x/y").mkdirs()
        File(d, "x/a.mp4").writeBytes(ByteArray(400_000))
        File(d, "x/y/b with space|pipe.jpg").writeBytes(ByteArray(150_000))
        Files.createLink(File(d, "x/y/hard.mp4").toPath(), File(d, "x/a.mp4").toPath())
        Files.createSymbolicLink(File(d, "x/sym").toPath(), File(d, "x").toPath())
        val b = SpaceTreeBuilder(d.path)
        assertTrue(ShellStreamScan.run(sh, listOf(d.path), b, ScanControl()))
        assertEquals(550_000, b.root.size)
        assertEquals(400_000, b.dedupedBytes)
        assertNotNull(b.tree.find("${d.path}/x/y/b with space|pipe.jpg"))
        assertNull(b.tree.find("${d.path}/x/sym"))
        assertEquals(FileCategory.IMAGE, b.tree.find("${d.path}/x/y/b with space|pipe.jpg")!!.category)
    }

    @Test fun shellStreamScanFallsBackToStat() {
        val l = ShellStreamScan.parse("64768|1234|1|4096|1700000000|41f9|/data/data")!!
        assertTrue(l.dir); assertEquals(1_700_000_000_000L, l.mtimeMs)
        val f = ShellStreamScan.parse("64768|99|2|10|1700000000|81a4|/data/a|b.txt")!!
        assertTrue(f.file); assertEquals("/data/a|b.txt", f.path); assertEquals(2, f.nlink)
        val p = ShellStreamScan.parse("2049|7|1|5|1700000000.5000000000|f|/x/y")!!
        assertEquals(1_700_000_000_500L, p.mtimeMs)
        val link = ShellStreamScan.parse("1|2|1|5|1|a1ff|/x/l")!!
        assertFalse(link.dir || link.file)
        assertNull(ShellStreamScan.parse("find: permission denied"))
        // Force the stat branch: the -printf probe fails.
        val d = tempDir("space"); File(d, "q").mkdirs(); File(d, "q/z.bin").writeBytes(ByteArray(777))
        val noPrintf = ShellRunner { script -> ProcessBuilder("sh", "-c", script.replaceFirst("find / -maxdepth 0 -printf", "find / -maxdepth 0 -printf-nope")).start() }
        val b = SpaceTreeBuilder(d.path)
        assertTrue(ShellStreamScan.run(noPrintf, listOf(d.path), b, ScanControl()))
        assertEquals(777, b.root.size)
    }

    @Test fun walkerAndShellStreamShareOneTree() {
        // Shizuku tier: local walk skips Android/data, a shell stream fills it into the same tree.
        val d = tempDir("space")
        File(d, "Android/data/com.a.b/cache").mkdirs(); File(d, "Android/media").mkdirs()
        File(d, "Android/data/com.a.b/cache/c.bin").writeBytes(ByteArray(300_000))
        File(d, "Android/media/m.mp4").writeBytes(ByteArray(200_000))
        File(d, "DCIM").mkdirs(); File(d, "DCIM/p.jpg").writeBytes(ByteArray(150_000))
        val b = SpaceTreeBuilder(d.path)
        val data = "${d.path}/Android/data"
        val t = Thread { ShellStreamScan.run(sh, listOf(data), b, ScanControl()) }.also { it.start() }
        LocalWalker().walk(d.path, b, ScanControl(), skip = { it == data })
        t.join()
        assertEquals(1, b.root.children!!.count { it.name == "Android" })
        val android = b.root.child("Android")!!
        assertEquals(1, android.children!!.count { it.name == "data" })
        assertEquals(650_000, b.root.size)
        assertEquals(500_000, android.size)
        assertEquals(300_000, StorageAggregate.appSpecificBytes(b.root) - 200_000)
    }

    @Test fun duplicatesBySizeThenHash() {
        val files = mapOf(
            "/a" to ByteArray(300_000) { 1 }, "/b" to ByteArray(300_000) { 1 },
            "/c" to ByteArray(300_000) { 1 }.also { it[150_000] = 2 },
            "/d" to ByteArray(2_000) { 3 }, "/e" to ByteArray(2_000) { 3 },
            "/f" to ByteArray(5_000) { 4 },
        )
        val reads = HashMap<String, Int>()
        val f = DuplicateFinder({ p -> reads.merge(p, 1, Int::plus); ByteArrayInputStream(files[p]!!) })
        val groups = f.find(files.map { it.key to it.value.size.toLong() }, minBytes = 1000)
        assertEquals(2, groups.size)
        assertEquals(listOf("/a", "/b"), groups[0].paths); assertEquals(300_000, groups[0].wasted)
        assertEquals(listOf("/d", "/e"), groups[1].paths)
        assertNull(reads["/f"])
    }
}
