package com.ghostpanter.files.core.space

import com.ghostpanter.files.core.AppFolders
import com.ghostpanter.files.core.VPath

/** Where an uninstall leftover sits. */
enum class ResidueKind {
    /** Android/data/<pkg> */ DATA,
    /** Android/obb/<pkg> */ OBB,
    /** Android/media/<pkg> */ MEDIA,
    /** /data/data/<pkg> (Root; app removed with 「保留数据」 or a stale restore). */ PRIVATE,
    /** /data/user_de/0/<pkg> (Root). */ PRIVATE_DE,
    /** Top-level folder of internal storage named after the package (e.g. /sdcard/com.foo.bar). */ PUBLIC,
}

/** One leftover folder ([path] as the shell sees it; [shown] as the user sees it). */
data class Residue(val pkg: String, val path: String, val kind: ResidueKind, val bytes: Long = 0, val shown: String = path)

/** Every leftover of one package. */
data class ResidueGroup(val pkg: String, val items: List<Residue>) {
    val bytes: Long get() = items.sumOf { it.bytes }
}

/**
 * 卸载残留: folders named after a package that is no longer installed (MT 管理器 / 国产清理工具 style).
 * Pure helpers (scripts + parsing) so the scan/cleanup logic is unit-tested on the JVM.
 */
object Residues {
    private fun q(s: String) = VPath.shellQuote(s)

    /**
     * Folders to list. [storage] is the user-0 internal storage as the runner sees it
     * (/storage/emulated/0, or /data/media/0 with Root); [root] adds /data/data and /data/user_de/0.
     */
    fun parents(storage: String, root: Boolean): List<Pair<String, ResidueKind>> {
        val s = storage.trimEnd('/')
        val out = arrayListOf(
            "$s/Android/data" to ResidueKind.DATA,
            "$s/Android/obb" to ResidueKind.OBB,
            "$s/Android/media" to ResidueKind.MEDIA,
            s to ResidueKind.PUBLIC,
        )
        if (root) { out.add("/data/data" to ResidueKind.PRIVATE); out.add("/data/user_de/0" to ResidueKind.PRIVATE_DE) }
        return out
    }

    /** Prints every child directory of [parents], one absolute path per line (no symlinks). */
    fun listScript(parents: List<String>): String = buildString {
        for (p in parents) append("for e in ${q(p)}/*; do [ -d \"\$e\" ] && [ ! -L \"\$e\" ] && echo \"\$e\"; done 2>/dev/null\n")
        append("exit 0\n")
    }

    /** Package folders found in a [listScript] output (paths outside [parents] or not package-named are ignored). */
    fun parseListing(out: String, parents: List<Pair<String, ResidueKind>>): List<Residue> {
        val byParent = parents.associate { VPath.normalize(it.first) to it.second }
        val seen = HashSet<String>()
        return out.lineSequence().map { it.trim() }.filter { it.startsWith("/") }.mapNotNull { line ->
            val p = VPath.normalize(line)
            val kind = byParent[VPath.parent(p)] ?: return@mapNotNull null
            val name = VPath.name(p)
            if (!AppFolders.isPackageName(name) || !seen.add(p)) return@mapNotNull null
            Residue(name, p, kind)
        }.toList()
    }

    /** `pm list packages` output → package names. */
    fun parsePackages(out: String): Set<String> =
        out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { val s = it.removePrefix("package:").substringBefore(' '); if ('=' in s) s.substringAfterLast('=') else s }
            .filter { AppFolders.isPackageName(it) }.toSet()

    /** Package list script: every user (so dual apps / work profiles stay safe), then the current user. */
    const val PACKAGES_SCRIPT = "pm list packages --user all 2>/dev/null; pm list packages 2>/dev/null; exit 0\n"

    /**
     * Candidates whose package is not installed. Never flags [self], the system (`android`), or anything
     * that has an installed package as a prefix of its name in the PUBLIC dir (e.g. com.tencent.mm.backup).
     */
    fun leftovers(candidates: List<Residue>, installed: Set<String>, self: String): List<Residue> {
        if (installed.isEmpty()) return emptyList()
        return candidates.filter { r ->
            val p = r.pkg
            p != self && p != "android" && p !in installed &&
                !(r.kind == ResidueKind.PUBLIC && installed.any { p.startsWith("$it.") })
        }
    }

    /** `du -sk` each path → "<kb>\t<path>" lines. */
    fun sizeScript(paths: List<String>): String = buildString {
        for (p in paths) append("du -sk ${q(p)} 2>/dev/null\n")
        append("exit 0\n")
    }

    fun parseSizes(out: String): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (line in out.lineSequence()) {
            val t = line.trim(); if (t.isEmpty()) continue
            val i = t.indexOfFirst { it == '\t' || it == ' ' }; if (i <= 0) continue
            val kb = t.substring(0, i).toLongOrNull() ?: continue
            m[VPath.normalize(t.substring(i).trim())] = kb * 1024
        }
        return m
    }

    /** Groups by package, largest first; inside a group the usual order data → obb → media → private → public. */
    fun group(items: List<Residue>): List<ResidueGroup> =
        items.groupBy { it.pkg }.map { (pkg, l) -> ResidueGroup(pkg, l.sortedBy { it.kind.ordinal }) }
            .sortedWith(compareByDescending<ResidueGroup> { it.bytes }.thenBy { it.pkg })

    /** Only package-named children of a known parent may be removed (guards against a bad path reaching rm). */
    fun deletable(path: String, parents: List<Pair<String, ResidueKind>>): Boolean {
        val p = VPath.normalize(path)
        if (p.contains("/../") || p.endsWith("/..")) return false
        val parent = VPath.parent(p)
        return parents.any { VPath.normalize(it.first) == parent } && AppFolders.isPackageName(VPath.name(p))
    }

    /** rm -rf each path, then prints `LEFT <path>` for whatever still exists. */
    fun deleteScript(paths: List<String>): String = buildString {
        for (p in paths) append("rm -rf -- ${q(p)} 2>/dev/null; [ -e ${q(p)} ] && echo \"LEFT \"${q(p)}\n")
        append("exit 0\n")
    }

    fun parseLeft(out: String): Set<String> =
        out.lineSequence().map { it.trim() }.filter { it.startsWith("LEFT ") }.map { VPath.normalize(it.removePrefix("LEFT ")) }.toSet()

    /** Residues the user most likely wants gone: everything except PUBLIC (a guess by folder name). */
    fun defaultSelection(groups: List<ResidueGroup>): Set<String> =
        groups.flatMap { g -> g.items.filter { it.kind != ResidueKind.PUBLIC }.map { it.path } }.toSet()
}
