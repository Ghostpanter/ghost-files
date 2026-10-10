package com.ghostpanter.files.core

/**
 * Helpers for the "/"-separated absolute paths every [VirtualFs] speaks.
 * Paths are always absolute and normalised (no trailing slash except root).
 */
object VPath {
    const val ROOT = "/"

    fun normalize(path: String): String {
        if (path.isEmpty()) return ROOT
        val parts = ArrayList<String>()
        for (p in path.replace('\\', '/').split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun join(parent: String, child: String): String {
        val c = child.trim('/')
        if (c.isEmpty()) return normalize(parent)
        return if (parent == ROOT || parent.isEmpty()) "/$c" else normalize(parent).trimEnd('/') + "/" + c
    }

    fun parent(path: String): String {
        val n = normalize(path)
        if (n == ROOT) return ROOT
        val i = n.lastIndexOf('/')
        return if (i <= 0) ROOT else n.substring(0, i)
    }

    fun name(path: String): String {
        val n = normalize(path)
        if (n == ROOT) return ""
        return n.substring(n.lastIndexOf('/') + 1)
    }

    /** True if [child] equals [ancestor] or lives somewhere below it. */
    fun isSameOrDescendant(ancestor: String, child: String): Boolean {
        val a = normalize(ancestor)
        val c = normalize(child)
        if (a == c) return true
        if (a == ROOT) return true
        return c.startsWith("$a/")
    }

    /** Relative path of [child] below [ancestor] ("" when equal). */
    fun relativize(ancestor: String, child: String): String {
        val a = normalize(ancestor)
        val c = normalize(child)
        if (a == c) return ""
        return if (a == ROOT) c.removePrefix("/") else c.removePrefix("$a/")
    }

    fun extension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase()
    }

    /** "a.txt" -> "a (1).txt", used to avoid name clashes. */
    fun numbered(name: String, n: Int): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0) "$name ($n)" else name.substring(0, i) + " ($n)" + name.substring(i)
    }

    /** POSIX single-quote escaping for shell commands. */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
