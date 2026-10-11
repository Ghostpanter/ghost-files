package com.ghostpanter.files.core.transfer

/** A path on a specific backend. */
data class Loc(val fsId: String, val path: String)

enum class TransferOp { COPY, MOVE }

enum class ConflictPolicy {
    /** Replace the existing file. */
    OVERWRITE,
    /** Keep the existing file and skip. */
    SKIP,
    /** Keep both: write "name (1).ext". */
    RENAME,
    /** Same size = already done; smaller = continue from its end; larger = overwrite. */
    RESUME,
}

enum class JobState {
    QUEUED, PREPARING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    val finished: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class JobSnapshot(
    val id: Long,
    val op: TransferOp,
    val title: String,
    val sources: List<Loc>,
    val dest: Loc,
    val policy: ConflictPolicy,
    val state: JobState,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val skippedFiles: Int = 0,
    val currentName: String? = null,
    val currentDone: Long = 0,
    val currentSize: Long = 0,
    val bytesPerSec: Long = 0,
    val retries: Int = 0,
    val serverSideFiles: Int = 0,
    val error: String? = null,
    val failures: List<String> = emptyList(),
    /** Why the last retry happened (shown on the queue card while retrying). */
    val lastError: String? = null,
    /** Full cause chain + stack trace of [lastError], for "复制错误". */
    val lastErrorDetail: String? = null,
    /** Files whose data was copied but whose source could not be deleted (MOVE only). */
    val keptSources: Int = 0,
) {
    val progress: Float get() = when {
        totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        totalFiles > 0 -> doneFiles.toFloat() / totalFiles
        state == JobState.COMPLETED -> 1f
        else -> 0f
    }
}

data class TransferConfig(
    /** Bounded copy buffer: memory per running transfer stays constant. */
    val bufferSize: Int = 256 * 1024,
    val maxRetries: Int = 5,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 20_000,
    /** No bytes for this long => streams are torn down and the file is retried/resumed. */
    val stallTimeoutMs: Long = 45_000,
    val maxConcurrentJobs: Int = 2,
    /**
     * Max jobs running at once against the same remote server (0 = no extra limit). Parallel
     * streams from one NAS mostly add seeks and contention, not speed.
     */
    val maxJobsPerRemote: Int = 0,
    val progressIntervalMs: Long = 250,
    val preserveTimestamps: Boolean = true,
)
