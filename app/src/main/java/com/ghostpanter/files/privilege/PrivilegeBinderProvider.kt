package com.ghostpanter.files.privilege

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.ghostpanter.files.core.privilege.acceptBinderSenderUid

/**
 * Receives the shell-uid [IPrivilegeService] binder from [PrivilegeServer] via ContentProvider.call.
 * Only shell (2000) or root (0) may deliver the binder.
 */
class PrivilegeBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SEND) return null
        val uid = Binder.getCallingUid()
        if (!acceptBinderSenderUid(uid)) {
            throw SecurityException("binder sender uid $uid rejected")
        }
        val b: IBinder? = extras?.getBinder(KEY_BINDER)
            ?: extras?.let { androidx.core.os.BundleCompat.getBinder(it, KEY_BINDER) }
        if (b == null || !b.pingBinder()) {
            return Bundle().also { it.putBoolean("ok", false) }
        }
        EmbeddedPrivilege.onBinderReceived(b)
        return Bundle().also { it.putBoolean("ok", true) }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = "privilege"
        const val METHOD_SEND = "sendBinder"
        const val KEY_BINDER = "binder"
        fun authority(pkg: String) = "$pkg.$AUTHORITY_SUFFIX"
    }
}
