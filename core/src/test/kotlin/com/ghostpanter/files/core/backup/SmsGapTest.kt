package com.ghostpanter.files.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsGapTest {
    private fun d(
        rom: SmsRom, sms: Int, mms: Int = 0, threads: Int = 0, sum: Int = -1,
        notif: Boolean? = null, def: String? = "com.google.android.apps.messaging",
        bySub: Map<Int, Int> = emptyMap(),
    ) = SmsDiag(rom, rom.name, sms, mapOf("inbox" to sms), mms, threads, sum, sms, notif, def, bySub)

    @Test fun miuiDeniedWins() {
        assertEquals(SmsGap.Verdict.MIUI_NOTIFICATION_SMS_DENIED, SmsGap.assess(d(SmsRom.MIUI, 19, notif = false)))
    }

    @Test fun threadSumGapMeansHidden() {
        assertEquals(SmsGap.Verdict.HIDDEN_BY_FILTER, SmsGap.assess(d(SmsRom.OTHER, 19, threads = 40, sum = 3200)))
    }

    @Test fun manyThreadsFewRowsMeansHidden() {
        assertEquals(SmsGap.Verdict.HIDDEN_BY_FILTER, SmsGap.assess(d(SmsRom.COLOROS, 19, threads = 35, def = "com.android.mms")))
    }

    @Test fun smallButConsistentOnVendorRomIsSuspect() {
        assertEquals(SmsGap.Verdict.SUSPECT_ROM_FILTER, SmsGap.assess(d(SmsRom.COLOROS, 19, threads = 4, sum = 19, def = "com.android.mms")))
    }

    @Test fun migratedHistoryOnlyInGoogleMessages() {
        // Dual-SIM, ~19 rows in Telephony, conversations agree — extras live in Messages.
        val x = d(SmsRom.OTHER, 19, threads = 16, sum = 19, bySub = mapOf(1 to 10, 2 to 9))
        assertEquals(SmsGap.Verdict.NOT_IN_PROVIDER, SmsGap.assess(x))
        val a = SmsGap.advice(x)!!
        assertTrue(a, a.contains("系统短信库") && a.contains("Google") && a.contains("迁移") && a.contains("不要为了读这些记录而把本应用设为默认"))
        assertFalse(a.contains("通知类短信"))
    }

    @Test fun completeStoreOnAospWithoutGoogleMessagesIsOk() {
        assertEquals(SmsGap.Verdict.OK, SmsGap.assess(d(SmsRom.OTHER, 4000, mms = 20, threads = 300, sum = 4020, def = "com.android.mms")))
        assertNull(SmsGap.advice(SmsGap.Verdict.OK, SmsRom.MIUI))
    }

    @Test fun completeStoreWithGoogleMessagesStillNotesRcs() {
        val x = d(SmsRom.OTHER, 4000, mms = 20, threads = 300, sum = 4020)
        assertEquals(SmsGap.Verdict.NOT_IN_PROVIDER, SmsGap.assess(x))
        assertTrue(SmsGap.advice(x)!!.contains("RCS"))
    }

    @Test fun adviceMentionsTheRightSwitch() {
        assertTrue(SmsGap.advice(SmsGap.Verdict.MIUI_NOTIFICATION_SMS_DENIED, SmsRom.MIUI)!!.contains("读取通知类短信"))
        assertTrue(SmsGap.advice(SmsGap.Verdict.HIDDEN_BY_FILTER, SmsRom.COLOROS)!!.contains("sub_id"))
        assertNotNull(SmsGap.advice(SmsGap.Verdict.HIDDEN_BY_FILTER, SmsRom.OTHER))
    }

    @Test fun summaryShowsSourcesAndSubId() {
        val s = d(SmsRom.MIUI, 19, mms = 2, threads = 40, sum = 3200, notif = false, bySub = mapOf(1 to 12, -1 to 7)).summary()
        assertTrue(s, s.contains("短信 19") && s.contains("彩信 2") && s.contains("会话 40") && s.contains("3200") && s.contains("未允许") && s.contains("无卡/已恢复 7"))
    }
}
