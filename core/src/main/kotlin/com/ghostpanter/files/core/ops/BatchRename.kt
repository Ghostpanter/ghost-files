package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pattern batch rename (MT / MiXplorer style).
 *
 * Template tokens: {name} base name, {ext} extension (no dot), {n} counter,
 * {n:3} zero-padded counter, {date} yyyyMMdd of mtime, {time} HHmmss, {parent} folder name.
 * Optional find/replace (plain or regex) is applied to the result; case conversion last.
 */
object BatchRename {
    enum class Case { KEEP, LOWER, UPPER }

    data class Spec(
        val template: String = "{name}.{ext}",
        val find: String = "",
        val replace: String = "",
        val regex: Boolean = false,
        val start: Int = 1,
        val step: Int = 1,
        val case: Case = Case.KEEP,
    )

    data class Plan(val entry: FileEntry, val newName: String, val error: String? = null) {
        val changed get() = newName != entry.name
    }

    private val tokenRe = Regex("\\{(name|ext|n|date|time|parent)(?::(\\d{1,2}))?}")

    fun apply(spec: Spec, entry: FileEntry, index: Int): String {
        val ext = if (entry.isDirectory) "" else VPath.extension(entry.name).let { if (it.isEmpty()) "" else entry.name.substring(entry.name.length - it.length) }
        val base = if (ext.isEmpty()) entry.name else entry.name.dropLast(ext.length + 1)
        val counter = spec.start + index * spec.step
        var out = tokenRe.replace(spec.template) { m ->
            when (m.groupValues[1]) {
                "name" -> base
                "ext" -> ext
                "n" -> m.groupValues[2].toIntOrNull()?.let { counter.toString().padStart(it, '0') } ?: counter.toString()
                "date" -> SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(entry.lastModified))
                "time" -> SimpleDateFormat("HHmmss", Locale.US).format(Date(entry.lastModified))
                "parent" -> VPath.name(VPath.parent(entry.path))
                else -> m.value
            }
        }
        // "{name}.{ext}" on a file without extension must not leave a trailing dot.
        if (ext.isEmpty() && out.endsWith(".") && spec.template.endsWith(".{ext}")) out = out.dropLast(1)
        if (spec.find.isNotEmpty()) {
            out = if (spec.regex) out.replace(Regex(spec.find), spec.replace) else out.replace(spec.find, spec.replace)
        }
        out = when (spec.case) { Case.LOWER -> out.lowercase(); Case.UPPER -> out.uppercase(); Case.KEEP -> out }
        return out.trim()
    }

    /** Builds the rename plan and flags invalid names / clashes (within the batch and with [existingNames]). */
    fun plan(spec: Spec, entries: List<FileEntry>, existingNames: Set<String>): List<Plan> {
        val raw = entries.mapIndexed { i, e ->
            runCatching { apply(spec, e, i) }.fold({ Plan(e, it) }, { Plan(e, e.name, tr("正则错误: ${it.message}", "Regex error: ${it.message}")) })
        }
        val renamed = entries.map { it.name }.toSet()
        val seen = HashMap<String, Int>()
        raw.forEach { if (it.error == null) seen.merge(it.newName.lowercase(), 1, Int::plus) }
        return raw.map { p ->
            when {
                p.error != null -> p
                p.newName.isEmpty() || p.newName == "." || p.newName == ".." -> p.copy(error = tr("名称无效", "Invalid name"))
                p.newName.contains('/') || p.newName.contains('\u0000') -> p.copy(error = tr("名称含非法字符", "Name contains illegal characters"))
                (seen[p.newName.lowercase()] ?: 0) > 1 -> p.copy(error = tr("与本批次其他文件重名", "Duplicates another name in this batch"))
                p.changed && p.newName in existingNames && p.newName !in renamed &&
                    !p.newName.equals(p.entry.name, ignoreCase = true) -> p.copy(error = tr("目标已存在", "Target exists"))
                else -> p
            }
        }
    }

    /**
     * Executes [plans] via [rename] (from, to). Uses a temporary name first so swaps
     * like a->b, b->a work. Returns error texts (empty = all good).
     */
    fun execute(plans: List<Plan>, rename: (String, String) -> Unit): List<String> {
        val todo = plans.filter { it.changed && it.error == null }
        val errors = ArrayList<String>()
        val staged = ArrayList<Pair<Plan, String>>()
        for ((i, p) in todo.withIndex()) {
            val tmp = VPath.join(VPath.parent(p.entry.path), ".gf-ren-$i-" + System.nanoTime())
            try { rename(p.entry.path, tmp); staged.add(p to tmp) } catch (e: Exception) { errors.add("${p.entry.name}: ${e.message}") }
        }
        for ((p, tmp) in staged) {
            val dst = VPath.join(VPath.parent(p.entry.path), p.newName)
            try { rename(tmp, dst) } catch (e: Exception) {
                errors.add("${p.entry.name} → ${p.newName}: ${e.message}")
                runCatching { rename(tmp, p.entry.path) }
            }
        }
        return errors
    }
}
