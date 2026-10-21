package com.ghostpanter.files.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFoldersTest {
    @Test fun sharedStorage() {
        assertEquals("com.tencent.mm", AppFolders.packageOf("/storage/emulated/0/Android/data", "com.tencent.mm"))
        assertEquals("com.tencent.mm", AppFolders.packageOf("/storage/emulated/0/Android/obb/", "com.tencent.mm"))
        assertEquals("com.tencent.mm", AppFolders.packageOf("/sdcard/Android/media", "com.tencent.mm"))
        assertEquals("com.tencent.mm", AppFolders.packageOf("/data/media/0/Android/data", "com.tencent.mm"))
        assertEquals("com.a.b", AppFolders.packageOf("/Android/data", "com.a.b"))          // SAF / ADB relative form
        assertNull(AppFolders.packageOf("/storage/emulated/0/Android/data", ".nomedia"))
        assertNull(AppFolders.packageOf("/storage/emulated/0/Android/data", "cache"))
        assertNull(AppFolders.packageOf("/storage/emulated/0/Download", "com.tencent.mm"))
        assertNull(AppFolders.packageOf("/storage/emulated/0/Android/data/com.tencent.mm", "files"))
    }

    @Test fun rootPaths() {
        assertEquals("com.android.chrome", AppFolders.packageOf("/data/data", "com.android.chrome"))
        assertEquals("com.android.chrome", AppFolders.packageOf("/data/user/0", "com.android.chrome"))
        assertEquals("com.android.chrome", AppFolders.packageOf("/data/user/10", "com.android.chrome"))
        assertEquals("com.android.chrome", AppFolders.packageOf("/data/user_de/0", "com.android.chrome"))
        assertEquals("com.tencent.mm", AppFolders.packageOf("/data/app/~~Xy3_Qw==", "com.tencent.mm-AbC_dEf12==" ))
        assertEquals("com.tencent.mm", AppFolders.packageOf("/data/app", "com.tencent.mm-1"))
        assertNull(AppFolders.packageOf("/data/app", "~~Xy3_Qw=="))
        assertNull(AppFolders.packageOf("/data/user", "0"))
        assertTrue(AppFolders.isAppParent("/data/app/~~abc=="))
        assertFalse(AppFolders.isAppParent("/data/app/~~abc==/com.x-1"))
        assertFalse(AppFolders.isPackageName("1abc.def"))
    }
}
