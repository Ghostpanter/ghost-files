package com.ghostpanter.files.core.space

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ResiduesTest {
    private val sr = "/storage/emulated/0"
    private val parents = Residues.parents(sr, root = true)

    @Test fun listingKeepsOnlyPackageFoldersOfKnownParents() {
        val out = """
            $sr/Android/data/com.tencent.mm
            $sr/Android/data/.nomedia
            $sr/Android/obb/com.miHoYo.Yuanshen
            $sr/DCIM
            $sr/com.gone.app
            $sr/Download/com.not.parent
            /data/data/com.gone.app
            /data/user_de/0/com.gone.app
            garbage
        """.trimIndent()
        val r = Residues.parseListing(out, parents)
        assertEquals(listOf(
            ResidueKind.DATA, ResidueKind.OBB, ResidueKind.PUBLIC, ResidueKind.PRIVATE, ResidueKind.PRIVATE_DE,
        ), r.map { it.kind })
        assertEquals("com.gone.app", r[2].pkg)
    }

    @Test fun installedSelfAndPrefixedPublicAreNeverLeftovers() {
        val c = listOf(
            Residue("com.tencent.mm", "$sr/Android/data/com.tencent.mm", ResidueKind.DATA),
            Residue("com.gone.app", "$sr/Android/data/com.gone.app", ResidueKind.DATA),
            Residue("com.ghostpanter.files", "$sr/Android/data/com.ghostpanter.files", ResidueKind.DATA),
            Residue("com.tencent.mm.backup", "$sr/com.tencent.mm.backup", ResidueKind.PUBLIC),
        )
        val l = Residues.leftovers(c, setOf("com.tencent.mm", "android"), "com.ghostpanter.files")
        assertEquals(listOf("com.gone.app"), l.map { it.pkg })
        // Package list unreadable: flag nothing.
        assertTrue(Residues.leftovers(c, emptySet(), "x.y").isEmpty())
    }

    @Test fun parsesPmAndDu() {
        val pkgs = Residues.parsePackages("package:com.a.b\npackage:/data/app/x/base.apk=com.c.d\npackage:com.e.f uid:10001\nError: x\n")
        assertEquals(setOf("com.a.b", "com.c.d", "com.e.f"), pkgs)
        val sizes = Residues.parseSizes("12\t/data/data/com.a.b\n2048 $sr/Android/obb/com.c.d\n")
        assertEquals(12L * 1024, sizes["/data/data/com.a.b"])
        assertEquals(2048L * 1024, sizes["$sr/Android/obb/com.c.d"])
    }

    @Test fun groupsAndDefaultSelection() {
        val g = Residues.group(listOf(
            Residue("com.a.b", "$sr/com.a.b", ResidueKind.PUBLIC, 10),
            Residue("com.a.b", "$sr/Android/data/com.a.b", ResidueKind.DATA, 5),
            Residue("com.c.d", "$sr/Android/obb/com.c.d", ResidueKind.OBB, 100),
        ))
        assertEquals(listOf("com.c.d", "com.a.b"), g.map { it.pkg })
        assertEquals(ResidueKind.DATA, g[1].items.first().kind)
        assertEquals(setOf("$sr/Android/obb/com.c.d", "$sr/Android/data/com.a.b"), Residues.defaultSelection(g))
    }

    @Test fun deleteGuard() {
        assertTrue(Residues.deletable("$sr/Android/data/com.gone.app", parents))
        assertTrue(Residues.deletable("/data/data/com.gone.app", parents))
        assertFalse(Residues.deletable("$sr/Android/data", parents))
        assertFalse(Residues.deletable("/data/data/com.gone.app/files", parents))
        assertFalse(Residues.deletable("$sr/DCIM", parents))
        assertFalse(Residues.deletable("/system/app/com.foo.bar", parents))
        val s = Residues.deleteScript(listOf("/data/data/com.a.b"))
        assertTrue(s.contains("rm -rf -- '/data/data/com.a.b'"))
        assertEquals(setOf("/data/data/com.a.b"), Residues.parseLeft("LEFT /data/data/com.a.b\nok\n"))
    }
}
