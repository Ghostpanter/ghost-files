package com.ghostpanter.files.core.cache

import com.ghostpanter.files.core.tempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheJanitorTest {
    private fun file(dir: File, name: String, size: Int, mtime: Long): File =
        File(dir, name).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(size)); it.setLastModified(mtime) }

    @Test fun sizeOfSumsNestedFiles() {
        val d = tempDir("cache")
        file(d, "a.bin", 1000, 1)
        file(d, "sub/b.bin", 2500, 2)
        assertEquals(3500, CacheJanitor.sizeOf(d))
        assertEquals(0, CacheJanitor.sizeOf(File(d, "missing")))
    }

    @Test fun lruTrimDeletesOldestUntilUnderCap() {
        val d = tempDir("cache")
        val a = file(d, "play/old.mp4", 4000, 1_000)
        val b = file(d, "play/mid.mp4", 4000, 2_000)
        val c = file(d, "play/new.mp4", 4000, 3_000)
        val freed = CacheJanitor.lruTrim(File(d, "play"), 5000)
        assertEquals(8000, freed)
        assertFalse(a.exists()); assertFalse(b.exists()); assertTrue(c.exists())
        assertEquals(4000, CacheJanitor.sizeOf(File(d, "play")))
    }

    @Test fun lruTrimNoopWhenAlreadyUnderCap() {
        val d = tempDir("cache")
        file(d, "x.bin", 100, 1)
        assertEquals(0, CacheJanitor.lruTrim(d, 1024))
        assertTrue(File(d, "x.bin").exists())
    }

    @Test fun deleteOlderThanRemovesStaleOnly() {
        val d = tempDir("cache")
        val now = 1_000_000L
        file(d, "old.bin", 500, now - 10_000)
        file(d, "new.bin", 500, now - 100)
        assertEquals(500, CacheJanitor.deleteOlderThan(d, 1_000, now))
        assertFalse(File(d, "old.bin").exists())
        assertTrue(File(d, "new.bin").exists())
    }

    @Test fun deleteTreeAndMatching() {
        val d = tempDir("cache")
        file(d, "arc123.tmp", 200, 1)
        file(d, "keep.txt", 50, 1)
        file(d, "bundle-9.zip", 300, 1)
        assertEquals(500, CacheJanitor.deleteMatching(d) { it.name.startsWith("arc") && it.name.endsWith(".tmp") || it.name.startsWith("bundle-") })
        assertTrue(File(d, "keep.txt").exists())
        assertEquals(50, CacheJanitor.deleteTree(d))
        assertFalse(d.exists())
    }
}
