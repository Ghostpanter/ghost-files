package com.ghostpanter.files.core.transfer

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.Abortable
import com.ghostpanter.files.core.ErrorText
import com.ghostpanter.files.core.RecoveringStream
import com.ghostpanter.files.core.SmbFs
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
    /**
     * Opens a private instance of a remote filesystem for one job (its own TCP connection /
     * session, not shared with browsing or other jobs); null = use [resolve]. Closed at job end.
     */
    private val openDedicated: (String) -> VirtualFs? = { null },
    /** Called for every error the engine records (retry, file failure, job failure); for logging. */
    private val onError: (job: JobSnapshot, what: String, error: Throwable) -> Unit = { _, _, _ -> },
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
        val title = (if (op == TransferOp.COPY) tr("复制 ", "Copy ") else tr("移动 ", "Move ")) +
            (if (sources.size == 1) VPath.name(sources[0].path) else tr("${VPath.name(sources[0].path)} 等 ${sources.size} 项", "${VPath.name(sources[0].path)} and ${sources.size - 1} more"))
        val r = Runner(JobSnapshot(id, op, title, sources, dest, policy, JobState.QUEUED), ignoreMissingSources)
        runners[id] = r
        _jobs.update { it + r.snap }
        val keys = if (config.maxJobsPerRemote > 0) remoteKeys(sources, dest) else emptyList()
        r.job = scope.launch {
            withRemoteSlots(keys) { permits.withPermit { r.run() } }
        }
        return id
    }

    private val remoteSlots = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

    private fun remoteKeys(sources: List<Loc>, dest: Loc): List<String> =
        (sources.map { it.fsId } + dest.fsId).distinct().filter { id ->
            runCatching { resolve(id).has(Capability.REMOTE) }.getOrDefault(false)
        }.sorted()

    /** Acquires per-server slots in a fixed order (no deadlock), before the global permit. */
    private suspend fun withRemoteSlots(keys: List<String>, block: suspend () -> Unit) {
        if (keys.isEmpty()) return block()
        val sem = remoteSlots.getOrPut(keys[0]) { Semaphore(config.maxJobsPerRemote) }
        sem.withPermit { withRemoteSlots(keys.drop(1), block) }
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
        fun abort() {
            for (c in open) runCatching { if (c is Abortable) c.abort() else c.close() }
            open.clear()
        }
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
        private val owned = java.util.concurrent.ConcurrentHashMap<String, VirtualFs>()

        /** Dedicated instance for remote filesystems (one connection per job), shared otherwise. */
        private fun fs(id: String): VirtualFs {
            owned[id]?.let { return it }
            val d = runCatching { openDedicated(id) }.getOrNull() ?: return resolve(id)
            return owned.putIfAbsent(id, d)?.also { runCatching { d.close() } } ?: d
        }

        private fun msg(e: Throwable) = (e as? FsException)?.message ?: ErrorText.describe(e)

        private fun recordError(what: String, e: Throwable, text: String) {
            update { it.copy(lastError = text, lastErrorDetail = "$text\n\n${ErrorText.stackTrace(e)}") }
            runCatching { onError(snap, what, e) }
        }

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
                val dstFs = fs(snap.dest.fsId)
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
                            failures.add("${item.src.path}: ${msg(e)}")
                            recordError("文件失败 ${item.src.path}", e, msg(e))
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
                val kept = snap.keptSources
                when {
                    failures.isEmpty() -> finish(JobState.COMPLETED, null)
                    failures.size == kept -> finish(JobState.FAILED, tr("$kept 个文件已复制到目标，但源文件删除失败（未丢失数据）", "$kept file(s) copied but source not deleted (no data lost)"))
                    kept > 0 -> finish(JobState.FAILED, tr("${failures.size - kept} 个文件失败；另有 $kept 个已复制但源文件未删除", "${failures.size - kept} file(s) failed; $kept copied but source not deleted"))
                    else -> finish(JobState.FAILED, tr("${failures.size} 个文件失败", "${failures.size} file(s) failed"))
                }
            } catch (e: CancellationException) {
                finish(JobState.CANCELLED, null)
            } catch (e: Exception) {
                if (cancelled) finish(JobState.CANCELLED, null)
                else {
                    recordError(tr("任务失败", "Job failed"), e, msg(e))
                    finish(JobState.FAILED, msg(e))
                }
            } finally {
                guard = null
                for (d in owned.values) runCatching { d.close() }
                owned.clear()
            }
        }

        private suspend fun prepare(dstFs: VirtualFs): List<Item> {
            val plan = ArrayList<Item>()
            val dest = snap.dest
            val sameRoot = HashSet<String>()
            for (src in snap.sources) {
                coroutineContext.ensureActive()
                val srcFs = fs(src.fsId)
                val entry = withRetry("读取 ${src.path}") { srcFs.stat(src.path) }
                if (entry == null) {
                    if (!ignoreMissing) failures.add(tr("${src.path}: 不存在", "${src.path}: not found"))
                    continue
                }
                val sameFs = srcFs.id == dstFs.id
                if (sameFs && entry.isDirectory && VPath.isSameOrDescendant(entry.path, dest.path)) {
                    failures.add(tr("${src.path}: 不能复制/移动到自身或其子目录", "${src.path}: cannot copy/move into itself"))
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
            throw FsException(tr("无法生成不重名的文件名: $path", "Cannot find a free file name: $path"))
        }

        private fun isFatal(e: Throwable): Boolean {
            val m = e.message ?: return false
            return listOf("拒绝访问", "无权限", "认证失败", "用户名或密码错误", "空间不足", "不支持续传", "HTTP 401", "HTTP 403", "HTTP 507")
                .any { m.contains(it) }
        }

        private suspend fun <T> withRetry(what: String, maxRetries: Int = config.maxRetries, block: () -> T): T {
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
                    if (attempt > maxRetries || isFatal(e)) throw e
                    update { it.copy(retries = it.retries + 1) }
                    recordError(what, e, "$what: ${msg(e)}")
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
                if (existing.isDirectory) throw FsException(tr("目标是同名目录", "Target is a directory with the same name"))
                when (snap.policy) {
                    ConflictPolicy.SKIP -> { markDone(src, skipped = true); return }
                    ConflictPolicy.RENAME -> target = uniqueName(dstFs, target)
                    ConflictPolicy.OVERWRITE -> Unit
                    ConflictPolicy.RESUME -> when {
                        existing.size == src.size -> {
                            deleteSourceAfterCopy(srcFs, src)
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
            var lastFailAt = 0L
            var backoff = config.initialBackoffMs
            val canResume = srcFs.has(Capability.RESUME_READ) && dstFs.has(Capability.RESUME_WRITE)
            while (true) {
                coroutineContext.ensureActive()
                awaitNotPaused()
                try {
                    var offset = 0L
                    if (attempt > 0 || resume) {
                        val cur = withContext(Dispatchers.IO) { runCatching { dstFs.stat(target)?.size }.getOrNull() } ?: 0L
                        // A previous attempt already wrote every byte (it failed while closing /
                        // verifying / later): don't copy again and never "resume" past the end.
                        if (attempt > 0 && cur == src.size && src.size > 0) break
                        if (canResume && cur in 1 until src.size) offset = cur
                    }
                    copyOnce(srcFs, src, dstFs, target, offset)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (cancelled) throw CancellationException("cancelled")
                    if (paused) continue // resume later from the written offset, not counted as a failure
                    // An attempt that moved the file forward (>= 8 MB) starts the retry budget over:
                    // only repeated failures without progress may exhaust it.
                    val reached = snap.currentDone
                    if (reached - lastFailAt >= 8L * 1024 * 1024) attempt = 0
                    lastFailAt = reached
                    attempt++
                    if (attempt > config.maxRetries || isFatal(e)) throw e
                    update { it.copy(retries = it.retries + 1) }
                    recordError("传输 ${src.path}", e, msg(e))
                    // Drop a possibly broken connection - but never a shared SMB connection that
                    // browsing / other jobs are using (SmbFs reconnects by itself when needed).
                    for (f in listOf(srcFs, dstFs).distinct()) {
                        if ((f.has(Capability.REMOTE) && f !is SmbFs) || owned.containsValue(f)) runCatching { f.reset() }
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(config.maxBackoffMs)
                }
            }
            if (config.preserveTimestamps && src.lastModified > 0) {
                withContext(Dispatchers.IO) { runCatching { dstFs.setLastModified(target, src.lastModified) } }
            }
            // The data is safely at the destination from here on: a failing source delete must
            // never send the file through the copy loop again.
            deleteSourceAfterCopy(srcFs, src)
            markDone(src)
        }

        /** MOVE only: removes the source of a verified copy; failures are reported, not retried as transfers. */
        private suspend fun deleteSourceAfterCopy(srcFs: VirtualFs, src: FileEntry) {
            if (snap.op != TransferOp.MOVE) return
            try {
                withRetry("删除源文件 ${src.name}", maxRetries = 2) { srcFs.delete(src.path, false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = msg(e)
                recordError("删除源文件 ${src.path}", e, tr("已复制，但删除源文件失败：$why", "Copied, but deleting source failed: $why"))
                failures.add(tr("${src.path}: 已复制，但删除源文件失败：$why", "${src.path}: copied, but deleting source failed: $why"))
                update { it.copy(keptSources = it.keptSources + 1, failures = failures.toList()) }
            }
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
            // After the last byte, closing can legitimately take long (server flush, Android's
            // MediaProvider scanning a multi-GB video on close): allow much more time there.
            val finishing = java.util.concurrent.atomic.AtomicBoolean(false)
            // Never cut a request the backend itself is still allowed to wait for (SMB: 90 s).
            val base = maxOf(config.stallTimeoutMs, srcFs.stallHintMs, dstFs.stallHintMs)
            val watchdog = scope.launch {
                while (isActive) {
                    delay(1000)
                    val limit = if (finishing.get()) base * 8 else base
                    if (clock() - lastProgress.get() > limit) { g.abort(); break }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    require(offset in 0..src.size.coerceAtLeast(0)) { "bad resume offset $offset for size ${src.size}" }
                    val input = g.add(srcFs.openRead(src.path, offset))
                    lastProgress.set(clock())
                    val output = g.add(dstFs.openWrite(target, offset, src.size))
                    lastProgress.set(clock())
                    // Self-healing streams: a reconnect is activity (watchdog) and is counted as
                    // "自动重连", not as a job retry.
                    for (st in listOf<Any>(input, output)) (st as? RecoveringStream)?.onRecovery = { cause, n ->
                        lastProgress.set(clock())
                        if (cause != null) {
                            update { it.copy(reconnects = it.reconnects + 1) }
                            recordError("自动重连（第 $n 次）${src.path}", cause, "自动重连：${msg(cause)}")
                        }
                    }
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
                    finishing.set(true)
                    lastProgress.set(clock())
                    g.forget(output)
                    output.close()
                    g.forget(input)
                    runCatching { input.close() }
                    if (src.size >= 0) {
                        val actual = runCatching { dstFs.stat(target)?.size }.getOrNull()
                        if (actual != null && actual != src.size) {
                            // The source may have changed while copying: accept it if the source now matches.
                            val now = runCatching { srcFs.stat(src.path)?.size }.getOrNull()
                            if (now != actual) throw FsException(tr("大小校验失败: 期望 ${src.size}，实际 $actual", "Size check failed: expected ${src.size}, got $actual"))
                        }
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
