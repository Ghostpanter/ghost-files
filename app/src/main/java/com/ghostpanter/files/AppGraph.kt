package com.ghostpanter.files

import android.content.Context
import com.ghostpanter.files.core.transfer.TransferConfig
import com.ghostpanter.files.core.transfer.TransferEngine
import com.ghostpanter.files.data.Bookmarks
import com.ghostpanter.files.data.HostKeyPrefs
import com.ghostpanter.files.data.ServerStore
import com.ghostpanter.files.data.Settings
import com.ghostpanter.files.fs.FsManager

class AppGraph(val context: Context) {
    val settings = Settings(context)
    val servers = ServerStore(context)
    val bookmarks = Bookmarks(context)
    val hostKeys = HostKeyPrefs(context)
    val fs = FsManager(context, servers, hostKeys)
    val transfers = TransferEngine(
        resolve = { fs.get(it) },
        config = TransferConfig(maxConcurrentJobs = settings.concurrentJobs.coerceIn(1, 4)),
    )
}
