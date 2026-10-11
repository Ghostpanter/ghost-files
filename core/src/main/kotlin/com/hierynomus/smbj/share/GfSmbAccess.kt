package com.hierynomus.smbj.share

import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import java.util.concurrent.Future

/**
 * Bridge to smbj's package-private async read, so Ghost 文件 can wait on reads with its own
 * (abortable) deadline and keep two reads in flight.
 */
object GfSmbAccess {
    fun readAsync(file: File, offset: Long, length: Int): Future<SMB2ReadResponse> = file.readAsync(offset, length)
}
