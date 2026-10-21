package com.ghostpanter.files.core.apk

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Split-APK bundles: our own backup (.apks with base.apk + split_*.apk at the root),
 * bundletool .apks (splits/…, toc.pb), XAPK (manifest.json, OBB expansions), APKM (info.json),
 * or a plain folder of split APKs.
 */
data class ApkBundle(
    val format: Format,
    val source: File,
    val splits: List<Split>,
    val obbs: List<Obb> = emptyList(),
    val packageName: String? = null,
    val label: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val iconEntry: String? = null,
) {
    enum class Format { APKS, XAPK, APKM, FOLDER }
    enum class Kind { BASE, FEATURE, ABI, DENSITY, LANGUAGE, OTHER }

    /** [entry] is the zip entry name (or the file name inside a folder). [owner] is "base" or the feature name. */
    data class Split(val entry: String, val name: String, val size: Long, val kind: Kind, val owner: String, val qualifier: String?)

    /** [target] is relative to the shared-storage root, e.g. "Android/obb/com.foo/main.12.com.foo.obb". */
    data class Obb(val entry: String, val size: Long, val target: String)

    val base: Split? get() = splits.firstOrNull { it.kind == Kind.BASE }

    /** Opens one entry's bytes. */
    fun open(entry: String): InputStream =
        if (format == Format.FOLDER) File(source, entry).inputStream()
        else {
            val z = ZipFile(source)
            val e = z.getEntry(entry) ?: run { z.close(); throw IOException("missing $entry") }
            object : java.io.FilterInputStream(z.getInputStream(e)) {
                override fun close() { try { super.close() } finally { z.close() } }
            }
        }

    /** What the device needs; see [select]. */
    data class Device(val abis: List<String>, val densityDpi: Int, val languages: List<String>)

    /**
     * Picks splits for [device]: base and feature modules always; per module the first matching ABI in the
     * device's preference order, the nearest density bucket (≥ device dpi, else the largest), and the
     * languages the user has configured (all language splits when none match). Unknown configs are kept.
     */
    fun select(device: Device): Set<String> {
        val out = LinkedHashSet<String>()
        val byOwner = splits.groupBy { it.owner }
        val wantAbis = device.abis.map { normAbi(it) }
        val wantLangs = device.languages.map { it.replace('-', '_').lowercase() }.filter { it.isNotEmpty() }
        for ((_, group) in byOwner) {
            group.filter { it.kind == Kind.BASE || it.kind == Kind.FEATURE || it.kind == Kind.OTHER }.forEach { out += it.entry }
            val abis = group.filter { it.kind == Kind.ABI }
            wantAbis.firstNotNullOfOrNull { a -> abis.firstOrNull { normAbi(it.qualifier!!) == a } }?.let { out += it.entry }
            val dens = group.filter { it.kind == Kind.DENSITY }
            bestDensity(dens, device.densityDpi)?.let { out += it.entry }
            val langs = group.filter { it.kind == Kind.LANGUAGE }
            val matched = langs.filter { s ->
                val q = s.qualifier!!.lowercase().removePrefix("b+").replace('+', '_')
                wantLangs.any { w -> w == q || w.substringBefore('_') == q.substringBefore('_') }
            }
            (matched.ifEmpty { langs }).forEach { out += it.entry }
        }
        return out
    }

    companion object {
        val ABIS = listOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips", "riscv64")
        val DENSITIES = linkedMapOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320,
            "xxhdpi" to 480, "xxxhdpi" to 640, "nodpi" to 0, "anydpi" to 0,
        )
        private val LANG = Regex("^(?:[a-z]{2,3}(?:_r?[A-Za-z]{2,4})?|b\\+[a-z]{2,3}(?:\\+[A-Za-z0-9]+)*)$")

        fun normAbi(s: String) = s.lowercase().replace('-', '_')

        fun isBundleName(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")
        }

        fun kindOf(qualifier: String): Kind = when {
            normAbi(qualifier) in ABIS -> Kind.ABI
            qualifier.lowercase() in DENSITIES -> Kind.DENSITY
            LANG.matches(qualifier) -> Kind.LANGUAGE
            else -> Kind.OTHER
        }

        /**
         * Classifies one APK by file name. Handles installed-split names (base.apk, split_config.arm64_v8a.apk,
         * split_feature.apk, split_feature.config.xxhdpi.apk), XAPK names (com.pkg.apk, config.en.apk) and
         * bundletool names (base-master.apk, base-arm64_v8a.apk, feature-master.apk, feature-xxhdpi.apk).
         */
        fun classify(entry: String, size: Long, packageName: String? = null, baseHint: String? = null): Split {
            val name = entry.substringAfterLast('/')
            val stem = name.removeSuffix(".apk").removeSuffix(".APK")
            fun s(kind: Kind, owner: String, q: String?) = Split(entry, name, size, kind, owner, q)
            if (baseHint != null && name == baseHint) return s(Kind.BASE, "base", null)
            if (stem == "base" || stem == "base-master" || (packageName != null && stem == packageName)) return s(Kind.BASE, "base", null)
            val split = stem.removePrefix("split_")
            if (split.startsWith("config.")) {
                val q = split.removePrefix("config.")
                return s(kindOf(q), "base", q)
            }
            if (split.contains(".config.")) {
                val owner = split.substringBefore(".config.")
                val q = split.substringAfter(".config.")
                return s(kindOf(q), owner, q)
            }
            if (stem.contains('-')) { // bundletool: <module>-<qualifier>
                val owner = stem.substringBefore('-')
                val q = stem.substringAfter('-')
                if (q == "master") return s(if (owner == "base") Kind.BASE else Kind.FEATURE, owner, null)
                val k = kindOf(q)
                if (k != Kind.OTHER) return s(k, owner, q)
            }
            if (stem.startsWith("split_")) return s(Kind.FEATURE, split, null)
            return s(Kind.OTHER, "base", null)
        }

        private fun bestDensity(list: List<Split>, dpi: Int): Split? {
            if (list.isEmpty()) return null
            val sized = list.map { it to (DENSITIES[it.qualifier!!.lowercase()] ?: 0) }
            val real = sized.filter { it.second > 0 }
            if (real.isEmpty()) return list.first()
            return real.filter { it.second >= dpi }.minByOrNull { it.second }?.first
                ?: real.maxByOrNull { it.second }!!.first
        }

        fun read(file: File): ApkBundle {
            if (file.isDirectory) return readFolder(file)
            ZipFile(file).use { z ->
                val entries = z.entries().asSequence().filter { !it.isDirectory }.toList()
                val names = entries.associateBy { it.name }
                fun text(n: String) = names[n]?.let { e -> z.getInputStream(e).bufferedReader().use { it.readText() } }
                val manifest = text("manifest.json")?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() }
                val info = text("info.json")?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() }
                val lower = file.name.lowercase()
                val format = when {
                    manifest != null && (manifest["package_name"] != null || manifest["split_apks"] != null) -> Format.XAPK
                    info != null || lower.endsWith(".apkm") -> Format.APKM
                    lower.endsWith(".xapk") -> Format.XAPK
                    else -> Format.APKS
                }
                val pkg = (manifest?.get("package_name") ?: info?.get("pname"))?.toString()
                val label = (manifest?.get("name") ?: info?.get("app_name"))?.toString()
                val vName = (manifest?.get("version_name") ?: info?.get("release_version"))?.toString()
                val vCode = (manifest?.get("version_code") ?: info?.get("versioncode"))?.toString()?.toDoubleOrNull()?.toLong()
                // XAPK names its base in split_apks: [{"file":"com.foo.apk","id":"base"}, ...]
                val baseHint = (manifest?.get("split_apks") as? List<*>)?.mapNotNull { it as? Map<*, *> }
                    ?.firstOrNull { it["id"] == "base" }?.get("file")?.toString()?.substringAfterLast('/')
                val apkEntries = entries.filter { it.name.endsWith(".apk", true) }
                // bundletool also ships standalone APKs for pre-L devices; they are not splits.
                val splitEntries = apkEntries.filterNot { it.name.startsWith("standalones/") }.ifEmpty { apkEntries }
                var splits = splitEntries.map { classify(it.name, it.size, pkg, baseHint) }
                if (splits.none { it.kind == Kind.BASE }) {
                    val loose = splits.filter { it.kind == Kind.OTHER || it.kind == Kind.FEATURE }
                    if (loose.size == 1 || splits.size == 1) {
                        val b = (loose.firstOrNull() ?: splits.first())
                        splits = splits.map { if (it === b) it.copy(kind = Kind.BASE, owner = "base", qualifier = null) else it }
                    }
                }
                if (splits.isEmpty()) throw IOException("no APK inside ${file.name}")
                val obbs = ArrayList<Obb>()
                (manifest?.get("expansions") as? List<*>)?.mapNotNull { it as? Map<*, *> }?.forEach { x ->
                    val f = x["file"]?.toString() ?: return@forEach
                    val e = names[f] ?: return@forEach
                    val target = x["install_path"]?.toString()?.trimStart('/')
                        ?: "Android/obb/${pkg ?: "unknown"}/${f.substringAfterLast('/')}"
                    obbs += Obb(f, e.size, target)
                }
                if (obbs.isEmpty() && pkg != null) entries.filter { it.name.endsWith(".obb", true) }.forEach { e ->
                    val tail = e.name.substringAfter("Android/obb/", "")
                    obbs += Obb(e.name, e.size, if (tail.isNotEmpty()) "Android/obb/$tail" else "Android/obb/$pkg/${e.name.substringAfterLast('/')}")
                }
                val icon = listOf("icon.png", "icon.webp").firstOrNull { it in names }
                return ApkBundle(format, file, order(splits), obbs, pkg, label, vName, vCode, icon)
            }
        }

        fun readFolder(dir: File): ApkBundle {
            val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }.orEmpty().toList()
            if (apks.isEmpty()) throw IOException("no APK in ${dir.name}")
            var splits = apks.map { classify(it.name, it.length()) }
            if (splits.none { it.kind == Kind.BASE }) {
                val loose = splits.filter { it.kind == Kind.OTHER }
                if (loose.size == 1) splits = splits.map { if (it === loose[0]) it.copy(kind = Kind.BASE, owner = "base") else it }
            }
            return ApkBundle(Format.FOLDER, dir, order(splits))
        }

        /** True if [dir] looks like a split set: a base plus at least one split. */
        fun isSplitFolder(dir: File): Boolean {
            val names = dir.list()?.filter { it.endsWith(".apk", true) }.orEmpty()
            if (names.size < 2) return false
            return names.any { it == "base.apk" || it == "base-master.apk" } && names.any { it.startsWith("split_") || it.startsWith("base-") && it != "base-master.apk" }
        }

        private fun order(list: List<Split>) = list.sortedWith(compareBy({ it.kind != Kind.BASE }, { it.owner != "base" }, { it.owner }, { it.kind.ordinal }, { it.name }))
    }
}

/** Just enough JSON for XAPK manifest.json / APKM info.json. */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("eof")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i++] == ']') return l
            }
        }
        fun str(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') {
                val c = s[i++]
                if (c == '\\') {
                    when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                } else b.append(c)
            }
            i++
            return b.toString()
        }
        fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
