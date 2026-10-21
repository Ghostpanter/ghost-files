package com.ghostpanter.files.core.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DupRulesTest {
    private val s = "/storage/emulated/0"

    @Test fun placesRankRegularFoldersFirst() {
        assertEquals(DupRanker.PREFERRED, DupRanker.place("$s/Pictures/Screenshots/a.png"))
        assertEquals(DupRanker.PREFERRED, DupRanker.place("$s/DCIM/Camera/a.jpg"))
        assertEquals(DupRanker.PREFERRED, DupRanker.place("/data/media/0/Music/x.flac"))
        assertEquals(DupRanker.TEMP, DupRanker.place("$s/Download/Quick Share/a.png"))
        assertEquals(DupRanker.TEMP, DupRanker.place("$s/MT2/a.png"))
        assertEquals(DupRanker.TEMP, DupRanker.place("$s/Android/data/com.x/files/a.png"))
        assertEquals(DupRanker.TEMP, DupRanker.place("$s/Pictures/.thumbnails/a.jpg"))
        assertEquals(DupRanker.TEMP, DupRanker.place("$s/Tencent/imagecache/a.jpg"))
        assertEquals(DupRanker.TEMP, DupRanker.place("$s/foo/tmp/a.jpg"))
        assertEquals(DupRanker.NEUTRAL, DupRanker.place("$s/Telegram/a.jpg"))
        assertEquals(DupRanker.NEUTRAL, DupRanker.place("$s/a.jpg"))
    }

    @Test fun copySuffixes() {
        for (n in listOf("a (1).png", "a(2).jpg", "a_copy.apk", "a-copy.txt", "a - 副本.docx", "a 副本.png", "a_copy (2).png", "a（1）.png")) assertTrue(n, DupRanker.hasCopySuffix(n))
        for (n in listOf("a.png", "IMG_2026_1.jpg", "copyright.txt", "Screenshot_20260212-132113.png", "v1.2.apk")) assertFalse(n, DupRanker.hasCopySuffix(n))
    }

    @Test fun bestFollowsTheRuleOrder() {
        // User's case: Quick Share + MT2 + Pictures/Screenshots -> keep Pictures.
        val g = listOf("$s/Download/Quick Share/Screenshot_1.png", "$s/MT2/Screenshot_1.png", "$s/Pictures/Screenshots/Screenshot_1.png")
        assertEquals("$s/Pictures/Screenshots/Screenshot_1.png", DupRanker.best(g))
        // Same place: older wins, then shorter, then no copy suffix.
        val mt = mapOf("$s/a/x.apk" to 2000L, "$s/b/x.apk" to 1000L)
        assertEquals("$s/b/x.apk", DupRanker.best(mt.keys.toList()) { mt[it]!! })
        assertEquals("$s/a/x.apk", DupRanker.best(listOf("$s/aa/x.apk", "$s/a/x.apk")))
        assertEquals("$s/d/abc.apk", DupRanker.best(listOf("$s/d/ab (1).apk", "$s/d/abc.apk")))
        // Unknown mtime loses to a known one; location still beats age.
        assertEquals("$s/b/x.apk", DupRanker.best(listOf("$s/a/x.apk", "$s/b/x.apk")) { if (it.contains("/b/")) 5 else 0 })
        assertEquals("$s/DCIM/x.jpg", DupRanker.best(listOf("$s/Download/x.jpg", "$s/DCIM/x.jpg")) { if (it.contains("Download")) 1 else 9 })
        // Same folder, same age, same length: the name without "(1)" wins.
        val q = listOf("$s/Download/Quick Share/墨迹天气 (1).apk", "$s/Download/Quick Share/墨迹天气_v9.apk")
        assertEquals("$s/Download/Quick Share/墨迹天气_v9.apk", DupRanker.best(q))
    }

    private fun g(size: Long, vararg p: String) = DuplicateFinder.Group(size, p.toList())

    @Test fun removalShrinksAndDropsGroups() {
        val groups = listOf(g(80, "/a/1.apk", "/b/1.apk"), g(4, "/a/s.png", "/m/s.png", "/p/s.png"))
        val r = DupGroups.remove(groups, listOf("/b/1.apk"))
        assertEquals(1, r.size)
        assertEquals(8, r[0].wasted)
        val r2 = DupGroups.remove(r, listOf("/m"))   // a deleted folder takes its files along
        assertEquals(listOf("/a/s.png", "/p/s.png"), r2.single().paths); assertEquals(4, r2.single().wasted)
        assertTrue(DupGroups.remove(r2, listOf("/p/s.png")).isEmpty())
        assertEquals(groups, DupGroups.remove(groups, listOf("/a/1.ap"))) // no partial-name prefix match
        assertEquals(listOf(g(4, "/a/s.png", "/p/s.png")), DupGroups.retain(groups) { it != "/b/1.apk" && it != "/m/s.png" })
    }

    @Test fun renameKeepsGroups() {
        val groups = listOf(g(4, "/a/s.png", "/m/s.png"))
        assertEquals(listOf("/a/s.png", "/n/s.png"), DupGroups.rename(groups, "/m", "/n").single().paths)
        assertEquals(listOf("/a/s.png", "/m/t.png"), DupGroups.rename(groups, "/m/s.png", "/m/t.png").single().paths)
    }

    @Test fun planUsesChoiceOrBest() {
        val groups = listOf(g(10, "$s/Download/a.jpg", "$s/DCIM/a.jpg"), g(3, "$s/x/b.png", "$s/y/b.png", "$s/z/b.png"))
        val p = DupGroups.plan(groups, emptySet()) { 0 }
        assertEquals(listOf("$s/Download/a.jpg", "$s/y/b.png", "$s/z/b.png"), p.delete)
        assertEquals(16, p.bytes); assertEquals(2, p.groups)
        val p2 = DupGroups.plan(groups, setOf("$s/Download/a.jpg", "$s/z/b.png")) { 0 }
        assertEquals(listOf("$s/DCIM/a.jpg", "$s/x/b.png", "$s/y/b.png"), p2.delete)
    }

    @Test fun cacheEncoding() {
        val groups = listOf(g(80, "/a/墨迹 1.apk", "/b/1.apk"), g(4, "/x", "/y", "/z"))
        assertEquals(groups, DupGroups.decode(DupGroups.encode(groups)))
        assertEquals(emptyList<DuplicateFinder.Group>(), DupGroups.decode(""))
        assertNull(DupGroups.decode(null))
    }
}

class DupRefreshTest {
    private fun tree(): SpaceTree {
        val b = SpaceTreeBuilder("/s")
        b.addDir("/s/Download/Quick Share", 0)
        b.addDir("/s/Pictures", 0)
        b.addDir("/s/MT2", 0)
        b.addFile("/s/Download/Quick Share/moji.apk", 40_000_000, 1)
        b.addFile("/s/Download/Quick Share/moji (1).apk", 40_000_000, 2)
        b.addFile("/s/Pictures/a.png", 2_000_000, 1)
        b.addFile("/s/MT2/a.png", 2_000_000, 2)
        return b.tree
    }

    @Test fun deleteUpdatesTreeListsAndGroups() {
        val t = tree()
        var groups = listOf(
            DuplicateFinder.Group(40_000_000, listOf("/s/Download/Quick Share/moji (1).apk", "/s/Download/Quick Share/moji.apk")),
            DuplicateFinder.Group(2_000_000, listOf("/s/MT2/a.png", "/s/Pictures/a.png")),
        )
        val before = t.root.size
        // The user's case: deleting one of the two identical APKs.
        val gone = listOf("/s/Download/Quick Share/moji (1).apk")
        assertEquals(40_000_000, SpaceEdits.remove(t, gone))
        groups = DupGroups.remove(groups, gone)
        assertEquals(1, groups.size)
        assertEquals(2_000_000, groups.sumOf { it.wasted })
        assertEquals(before - 40_000_000, t.root.size)
        assertEquals(null, t.find(gone[0]))
        assertEquals(listOf("/s/Download/Quick Share/moji.apk", "/s/Pictures/a.png", "/s/MT2/a.png"), SpaceQueries.topFiles(t.root, 10).map { it.path() })
        // Deleting the last file of MT2 makes it an empty folder and removes the group.
        SpaceEdits.remove(t, listOf("/s/MT2/a.png")); groups = DupGroups.remove(groups, listOf("/s/MT2/a.png"))
        assertTrue(groups.isEmpty())
        assertTrue(SpaceQueries.emptyDirs(t.root).any { it.path() == "/s/MT2" })
    }

    @Test fun moveAndRenameFollowTheNode() {
        val t = tree()
        assertTrue(SpaceEdits.move(t, "/s/MT2/a.png", "/s/MT2/b.png"))
        assertEquals(2_000_000, t.find("/s/MT2/b.png")!!.size)
        assertTrue(SpaceEdits.move(t, "/s/MT2/b.png", "/s/Pictures/b.png"))
        assertEquals(4_000_000, t.find("/s/Pictures")!!.size)
        assertEquals(0, t.find("/s/MT2")!!.size)
        assertFalse(SpaceEdits.move(t, "/s/Pictures/b.png", "/elsewhere/b.png"))
        assertEquals(2_000_000, t.find("/s/Pictures")!!.size)
    }
}
