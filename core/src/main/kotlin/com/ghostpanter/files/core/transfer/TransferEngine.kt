package com.ghostpanter.files.core.transfer

import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Background transfer queue.
 *
 * Design goals (the reasons other managers "freeze" on LAN copies):
 *  - Nothing runs on the caller's thread; jobs run on [Dispatchers.IO] with a
 *    global concurrency limit, UI only observes [jobs].
 *  - Streams use one bounded buffer per transfer; no whole-file buffering.
 *  - A watchdog tears down streams that make no progress for
 *    [TransferConfig.stallTimeoutMs] (blocking socket reads can't be
 *    interrupted otherwise). Cancel/pause close the streams the same way.
 *  - Failures are retried with exponential backoff; when both sides support
 *    it the retry continues from the bytes already written (offset resume).
 *  - Same-server copy/move use server-side COPY/rename instead of
 *    download + upload.
 */
class TransferEngine(
    private val resolve: (String) -> VirtualFs,
    private val config: TransferConfig = TransferConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _jobs = MutableStateFlow<List<JobSnapshot>>(emptyList())
    val jobs: StateFlow<List<JobSnapshot>> = _jobs.asStateFlow()

    private val _finished = MutableSharedFlow<JobSnapshot>(extraBufferCapacity = 64)
    /** Emits each job once when it reaches a terminal state. */
    val finished: SharedFlow<JobSnapshot> = _finished.asSharedFlow()

    private val nextId = AtomicLong(1)
    private val runners = java.util.concurrent.ConcurrentHashMap<Long, Runner>()
    private val permits = Semaphore(config.maxConcurrentJobs.coerceAtLeast(1))

    fun enqueue(
        op: TransferOp,
        sources: List<Loc>,
        dest: Loc,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
        ignoreMissingSources: Boolean = false,
    ): Long {
        require(sources.isNotEmpty()) { "no sources" }
        val id = nextId.getAndIncrement()
        val title = (if (op == TransferOp.COPY) "复制 " else "移动 ") +
            (if (sources.size == 1) VPath.name(sources[0].path) else "${VPath.name(sources[0].path)} 等 ${sources.size} 项")
        val r = Runner(JobSnapshot(id, op, title, sources, dest, policy, JobState.QUEUED), ignoreMissingSources)
        runners[id] = r
        _jobs.update { it + r.snap }
        r.job = scope.launch {
            permits.withPermit { r.run() }
        }
        return id
    }

    fun pause(id: Long) { runners[id]?.pause() }
    fun resume(id: Long) { runners[id]?.resume() }

    fun cancel(id: Long) { runners[id]?.cancel() }

    /** Re-run a failed/cancelled job; partially written files are resumed. */
    fun retry(id: Long): Long? {
        val s = _jobs.value.firstOrNull { it.id == id } ?: return null
        if (!s.state.finished) return null
        remove(id)
        return enqueue(s.op, s.sources, s.dest, ConflictPolicy.RESUME, ignoreMissingSources = true)
    }

    fun remove(id: Long) {
        val r = runners[id]
        if (r != null && !r.snap.state.finished) return
        runners.remove(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        val done = _jobs.value.filter { it.state.finished }.map { it.id }.toSet()
        done.forEach { runners.remove(it) }
        _jobs.update { list -> list.filterNot { it.id in done } }
    }

    val hasActiveJobs: Boolean get() = _jobs.value.any { !it.state.finished }

    suspend fun await(id: Long): JobSnapshot =
        _jobs.first { list -> list.firstOrNull { it.id == id }?.state?.finished != false }
            .firstOrNull { it.id == id } ?: error("job $id removed")

    // ---------------------------------------------------------------------

    private class Item(val src: FileEntry, val dstPath: String, val srcFs: VirtualFs)

    private class PauseSignal : IOException("paused")

    /** Holds the open streams of the current file so other threads can abort them. */
    private class StreamGuard {
        private val open = CopyOnWriteArrayList<Closeable>()
        fun <T : Closeable> add(c: T): T { open.add(c); return c }
        fun abort() { for (c in open) runCatching { c.close() }; open.clear() }
        fun forget(c: Closeable) { open.remove(c) }
    }

    private inner class Runner(initial: JobSnapshot, private val ignoreMissing: Boolean) {
        @Volatile var snap: JobSnapshot = initial
        var job: Job? = null
        @Volatile private var paused = false
        @Volatile private var cancelled = false
        @Volatile private var guard: StreamGuard? = null
        private val pausedFlow = MutableStateFlow(false)
        private var lastPublish = 0L
        private var speedMark = 0L
        private var speedBytes = 0L
        private var completedBytes = 0L
        private val failures = ArrayList<String>()

        private fun update(force: Boolean = true, f: (JobSnapshot) -> JobSnapshot) = synchronized(this) {
            snap = f(snap)
            val now = clock()
            if (!force && now - lastPublish < config.progressIntervalMs) return@synchronized
            if (now - speedMark >= 1000) {
                val dt = (now - speedMark).coerceAtLeast(1)
                val rate = if (speedMark == 0L) 0 else (snap.doneBytes - speedBytes) * 1000 / dt
                speedMark = now
                speedBytes = snap.doneBytes
                snap = snap.copy(bytesPerSec = rate.coerceAtLeast(0))
            }
            lastPublish = now
            val s = snap
            _jobs.update { list -> list.map { if (it.id == s.id) s else it } }
        }

        fun pause() {
            if (snap.state.finished) return
            paused = true
            pausedFlow.value = true
            guard?.abort()
            update { it.copy(state = JobState.PAUSED) }
        }

        fun resume() {
            if (!paused) return
            paused = false
            pausedFlow.value = false
            update { it.copy(state = if (it.totalFiles > 0) JobState.RUNNING else JobState.QUEUED) }
        }

        fun cancel() {
            if (snap.state.finished) return
            cancelled = true
            guard?.abort()
            pausedFlow.value = false
            val j = job
            if (j == null || !j.isActive || snap.state == JobState.QUEUED) {
                j?.cancel()
                finish(JobState.CANCELLED, null)
            } else j.cancel()
        }

        private fun finish(state: JobState, error: String?) {
            if (snap.state.finished) return
            update { it.copy(state = state, error = error, currentName = null, bytesPerSec = 0, failures = failures.toList()) }
            _finished.tryEmit(snap)
        }

        private suspend fun awaitNotPaused() {
            if (paused) {
                update { it.copy(state = JobState.PAUSED) }
                pausedFlow.first { !it }
                update { it.copy(state = JobState.RUNNING) }
            }
        }

        suspend fun run() {
            if (cancelled) { finish(JobState.CANCELLED, null); return }
            try {
                awaitNotPaused()
                update { it.copy(state = JobState.PREPARING) }
                val dstFs = resolve(snap.dest.fsId)
                val plan = prepare(dstFs)
                update { it.copy(state = JobState.RUNNING, totalFiles = plan.count { i -> !i.src.isDirectory }, totalBytes = plan.sumOf { i -> i.src.size }) }
                val dirsToRemove = ArrayList<Pair<VirtualFs, String>>()
                for (item in plan) {
                    coroutineContext.ensureActive()
                    awaitNotPaused()
                    if (item.src.isDirectory) {
                        withRetry("创建目录 ${item.dstPath}") { dstFs.mkdirs(item.dstPath) }
                        if (snap.op == TransferOp.MOVE) dirsToRemove.add(item.srcFs to item.src.path)
                    } else {
                        try {
                            transferFile(item, dstFs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failures.add("${item.src.path}: ${e.message ?: e.javaClass.simpleName}")
                            completedBytes += item.src.size
                            update { it.copy(doneBytes = completedBytes, failures = failures.toList()) }
                        }
                    }
                }
                // Move: remove now-empty source directories, deepest first. Never
                // recursive, so a directory with a failed file is kept.
                for ((fs, dir) in dirsToRemove.sortedByDescending { it.second.length }) {
                    runCatching { if (fs.list(dir).isEmpty()) fs.delete(dir, false) }
                }
                if (failures.isEmpty()) finish(JobState.COMPLETED, null)
                else finish(JobState.FAILED, "${failures.size} 个文件失败")
            } catch (e: CancellationException) {
                finish(JobState.CANCELLED, null)
            } catch (e: Exception) {
                if (cancelled) finish(JobState.CANCELLED, null)
                else finish(JobState.FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                guard = null
            }
        }

        private suspend fun prepare(dstFs: VirtualFs): List<Item> {
            val plan = ArrayList<Item>()
            val dest = snap.dest
            val sameRoot = HashSet<String>()
            for (src in snap.sources) {
                coroutineContext.ensureActive()
                val srcFs = resolve(src.fsId)
                val entry = withRetry("读取 ${src.path}") { srcFs.stat(src.path) }
                if (entry == null) {
                    if (!ignoreMissing) failures.add("${src.path}: 不存在")
                    continue
                }
                val sameFs = srcFs.id == dstFs.id
                if (sameFs && entry.isDirectory && VPath.isSameOrDescendant(entry.path, dest.path)) {
                    failures.add("${src.path}: 不能复制/移动到自身或其子目录")
                    continue
                }
                var target = VPath.join(dest.path, entry.name)
                if (sameFs && VPath.normalize(target) == VPath.normalize(entry.path)) {
                    if (snap.op == TransferOp.MOVE) continue
                    target = uniqueName(dstFs, target)
                }
                // Same-backend move: a single rename, no bytes moved.
                if (snap.op == TransferOp.MOVE && sameFs && srcFs.has(Capability.RENAME_MOVE)) {
                    var t = target
                    val existing = runCatching { dstFs.stat(t) }.getOrNull()
                    if (existing != null && snap.policy == ConflictPolicy.RENAME) t = uniqueName(dstFs, t)
                    if (existing == null || t != target) {
                        val ok = runCatching { withContext(Dispatchers.IO) { srcFs.rename(entry.path, t) } }.isSuccess
                        if (ok) {
                            update { it.copy(serverSideFiles = it.serverSideFiles + 1) }
                            sameRoot.add(entry.path)
                            continue
                        }
                    }
                }
                if (entry.isDirectory) {
                    if (snap.policy == ConflictPolicy.RENAME && runCatching { dstFs.stat(target) }.getOrNull()?.isDirectory == false) {
                        target = uniqueName(dstFs, target)
                    }
                    walk(srcFs, entry, target, plan)
                } else {
                    plan.add(Item(entry, target, srcFs))
                }
                update(force = false) { it.copy(totalFiles = plan.count { i -> !i.src.isDirectory }, totalBytes = plan.sumOf { i -> i.src.size }) }
            }
            return plan
        }

        private suspend fun walk(fs: VirtualFs, dir: FileEntry, target: String, out: MutableList<Item>) {
            coroutineContext.ensureActive()
            out.add(Item(dir, target, fs))
            val children = withRetry("列出 ${dir.path}") { fs.list(dir.path) }
            for (c in children) {
                val t = VPath.join(target, c.name)
                if (c.isDirectory && !c.isLink) walk(fs, c, t, out)
                else if (!c.isDirectory) out.add(Item(c, t, fs))
            }
            update(force = false) { it.copy(totalFiles = out.count { i -> !i.src.isDirectory }, totalBytes = out.sumOf { i -> i.src.size }) }
        }

        private fun uniqueName(fs: VirtualFs, path: String): String {
            val parent = VPath.parent(path)
            val name = VPath.name(path)
            for (n in 1..9999) {
                val candidate = VPath.join(parent, VPath.numbered(name, n))
                if (runCatching { fs.stat(candidate) }.getOrNull() == null) return candidate
            }
            throw FsException("无法生成不重名的文件名: $path")
        }

        private fun isFatal(e: Throwable): Boolean {
            val m = e.message ?: return false
            return listOf("拒绝访问", "无权限", "认证失败", "用户名或密码错误", "空间不足", "不支持续传", "HTTP 401", "HTTP 403", "HTTP 507")
                .any { m.contains(it) }
        }

        private suspend fun <T> withRetry(what: String, block: () -> T): T {
            var attempt = 0
            var backoff = config.initialBackoffMs
            while (true) {
                coroutineContext.ensureActive()
                try {
                    return withContext(Dispatchers.IO) { block() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) throw CancellationException("cancelled")
                    attempt++
                    if (attempt > config.maxRetries || isFatal(e)) throw e
                    update { it.copy(retries = it.retries + 1) }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(config.maxBackoffMs)
                }
            }
        }

        private suspend fun transferFile(item: Item, dstFs: VirtualFs) {
            val srcFs = item.srcFs
            val src = item.src
            val sameFs = srcFs.id == dstFs.id
            update { it.copy(currentName = src.name, currentDone = 0, currentSize = src.size) }

            var target = item.dstPath
            var resume = false
            val existing = withRetry("读取 $target") { dstFs.stat(target) }
            if (existing != null) {
                if (existing.isDirectory) throw FsException("目标是同名目录")
                when (snap.policy) {
                    ConflictPolicy.SKIP -> { markDone(src, skipped = true); return }
                    ConflictPolicy.RENAME -> target = uniqueName(dstFs, target)
                    ConflictPolicy.OVERWRITE -> Unit
                    ConflictPolicy.RESUME -> when {
                        existing.size == src.size -> {
                            if (snap.op == TransferOp.MOVE) withRetry("删除源 ${src.path}") { srcFs.delete(src.path, false) }
                            markDone(src, skipped = true); return
                        }
                        existing.size < src.size -> resume = true
                        else -> Unit
                    }
                }
            }

            // Same backend: rename (move) or server-side copy, no bytes through the phone.
            if (sameFs) {
                if (snap.op == TransferOp.MOVE && srcFs.has(Capability.RENAME_MOVE)) {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            if (existing != null && target == item.dstPath) dstFs.delete(target, false)
                            srcFs.rename(src.path, target)
                        }
                    }.isSuccess
                    if (ok) { update { it.copy(serverSideFiles = it.serverSideFiles + 1) }; markDone(src); return }
                }
                if (snap.op == TransferOp.COPY && srcFs.has(Capability.SERVER_COPY) && !resume) {
                    val ok = runCatching { withContext(Dispatchers.IO) { srcFs.serverCopy(src.path, target) } }.getOrDefault(false)
                    if (ok && runCatching { dstFs.stat(target)?.size }.getOrNull() == src.size) {
                        update { it.copy(serverSideFiles = it.serverSideFiles + 1) }
                        markDone(src); return
                    }
                }
            }

            var attempt = 0
            var backoff = config.initialBackoffMs
            val canResume = srcFs.has(Capability.RESUME_READ) && dstFs.has(Capability.RESUME_WRITE)
            while (true) {
                coroutineContext.ensureActive()
                awaitNotPaused()
                try {
                    var offset = 0L
                    if ((attempt > 0 || resume) && canResume) {
                        val cur = withContext(Dispatchers.IO) { runCatching { dstFs.stat(target)?.size }.getOrNull() } ?: 0L
                        if (cur in 1..src.size) offset = cur
                    }
                    copyOnce(srcFs, src, dstFs, target, offset)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) throw CancellationException("cancelled")
                    if (paused) continue // resume later from the written offset, not counted as a failure
                    attempt++
                    if (attempt > config.maxRetries || isFatal(e)) throw e
                    update { it.copy(retries = it.retries + 1, currentDone = 0) }
                    if (srcFs.has(Capability.REMOTE)) runCatching { srcFs.reset() }
                    if (dstFs.has(Capability.REMOTE)) runCatching { dstFs.reset() }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(config.maxBackoffMs)
                }
            }
            if (config.preserveTimestamps && src.lastModified > 0) {
                withContext(Dispatchers.IO) { runCatching { dstFs.setLastModified(target, src.lastModified) } }
            }
            if (snap.op == TransferOp.MOVE) withRetry("删除源 ${src.path}") { srcFs.delete(src.path, false) }
            markDone(src)
        }

        private fun markDone(src: FileEntry, skipped: Boolean = false) {
            completedBytes += src.size
            update {
                it.copy(
                    doneBytes = completedBytes, doneFiles = it.doneFiles + 1,
                    skippedFiles = it.skippedFiles + if (skipped) 1 else 0,
                    currentDone = src.size,
                )
            }
        }

        private suspend fun copyOnce(srcFs: VirtualFs, src: FileEntry, dstFs: VirtualFs, target: String, offset: Long) {
            val g = StreamGuard()
            guard = g
            val lastProgress = AtomicLong(clock())
            val watchdog = scope.launch {
                while (isActive) {
                    delay(1000)
                    if (clock() - lastProgress.get() > config.stallTimeoutMs) { g.abort(); break }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val input = g.add(srcFs.openRead(src.path, offset))
                    lastProgress.set(clock())
                    val output = g.add(dstFs.openWrite(target, offset, src.size))
                    lastProgress.set(clock())
                    val buf = ByteArray(config.bufferSize)
                    var done = offset
                    update { it.copy(currentDone = done, doneBytes = completedBytes + done) }
                    while (true) {
                        ensureActive()
                        if (paused) throw PauseSignal()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buf, 0, n)
                        done += n
                        lastProgress.set(clock())
                        update(force = false) { it.copy(currentDone = done, doneBytes = completedBytes + done) }
                    }
                    // Commit: close output first (flush / server confirmation), errors propagate.
                    g.forget(output)
                    output.close()
                    g.forget(input)
                    runCatching { input.close() }
                    if (src.size >= 0) {
                        val actual = runCatching { dstFs.stat(target)?.size }.getOrNull()
                        if (actual != null && actual != src.size) throw FsException("大小校验失败: 期望 ${src.size}，实际 $actual")
                    }
                }
            } finally {
                watchdog.cancel()
                g.abort()
                if (guard === g) guard = null
            }
        }
    }
}
