package com.ghostpanter.files

import com.ghostpanter.files.core.ErrorText
import com.ghostpanter.files.core.transfer.JobSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends transfer errors (full cause chain) to filesDir/logs/transfer.log, rolling at ~1 MB. */
class TransferLog(private val dir: File, private val maxBytes: Long = 1_000_000) {
    val file: File get() = File(dir, "transfer.log")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(job: JobSnapshot, what: String, e: Throwable) {
        runCatching {
            dir.mkdirs()
            val f = file
            if (f.length() > maxBytes) {
                val old = File(dir, "transfer.log.1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(
                "[${fmt.format(Date())}] job#${job.id} ${job.title} | $what | 已传 ${job.doneBytes}/${job.totalBytes} | 重试 ${job.retries} | 自动重连 ${job.reconnects}\n" +
                    ErrorText.stackTrace(e) + "\n",
            )
        }
    }

    @Synchronized
    fun read(): String = runCatching {
        listOf(File(dir, "transfer.log.1"), file).filter { it.exists() }.joinToString("") { it.readText() }
    }.getOrDefault("")
}
