package com.ghostpanter.files.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.time.ZoneId

class SmsXmlTest {
    /** Shape of a real SMS Backup & Restore 10.x export (attribute order, "null"s, entities, MMS with parts). */
    private val sbr = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<!--File Created By SMS Backup & Restore v10.20.002 on 22/10/2026 09:30:00-->
<?xml-stylesheet type="text/xsl" href="sms.xsl"?>
<smses count="3" backup_set="b0e1f2a3-4c5d-6e7f-8a9b-0c1d2e3f4a5b" backup_date="1792632600000" type="full">
  <sms protocol="0" address="+8613800138000" date="1792600000123" type="1" subject="null" body="【银行】验证码 123456，&#10;请勿泄露 &amp; &lt;转发&gt; &quot;谢谢&quot; 😀" toa="null" sc_toa="null" service_center="+8613800100500" read="1" status="-1" locked="0" date_sent="1792599999000" sub_id="1" readable_date="2026年10月21日 00:26:40" contact_name="张三" />
  <sms protocol="0" address="10086" date="1792600100000" type="2" subject="null" body='单引号 "属性" 也可以' toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="2026年10月21日 00:28:20" contact_name="(Unknown)" />
  <mms date="1792600200000" ct_t="application/vnd.wap.multipart.related" msg_box="1" rr="null" sub="照片" read_status="null" address="+8613900139000" m_id="mid-42@mms" read="1" m_size="12345" m_type="132" sub_id="1" text_only="0" readable_date="2026年10月21日 00:30:00" contact_name="李四">
    <parts>
      <part seq="-1" ct="application/smil" name="null" chset="null" cd="null" fn="null" cid="&lt;smil&gt;" cl="smil.xml" ctt_s="null" ctt_t="null" text='&lt;smil&gt;&lt;body&gt;&lt;/body&gt;&lt;/smil&gt;' />
      <part seq="0" ct="text/plain" name="null" chset="106" cd="null" fn="null" cid="&lt;text_0&gt;" cl="text_0.txt" ctt_s="null" ctt_t="null" text="看看这张" />
      <part seq="0" ct="image/jpeg" name="IMG.jpg" chset="null" cd="null" fn="null" cid="&lt;img&gt;" cl="IMG.jpg" ctt_s="null" ctt_t="null" data="/9j/4AAQSkZJRgABAQ==" />
    </parts>
    <addrs>
      <addr address="+8613900139000" type="137" charset="106" />
      <addr address="+8613800138000" type="151" charset="106" />
    </addrs>
  </mms>
</smses>
"""

    private fun parse(s: String): Triple<List<SmsRec>, List<MmsRec>, Int> {
        val sms = ArrayList<SmsRec>(); val mms = ArrayList<MmsRec>(); var count = -1
        SmsXml.read(StringReader(s), SmsXml.Visitor(sms = { sms.add(it) }, mms = { mms.add(it) }, count = { count = it }))
        return Triple(sms, mms, count)
    }

    @Test fun readsSmsBackupAndRestoreExport() {
        val (sms, mms, count) = parse(sbr)
        assertEquals(3, count)
        assertEquals(2, sms.size); assertEquals(1, mms.size)
        assertEquals("【银行】验证码 123456，\n请勿泄露 & <转发> \"谢谢\" 😀", sms[0]["body"])
        assertEquals("+8613800138000", sms[0]["address"])
        assertNull(sms[0]["subject"])                     // "null" means absent
        assertEquals("单引号 \"属性\" 也可以", sms[1]["body"])
        val m = mms[0]
        assertEquals(3, m.parts.size); assertEquals(2, m.addrs.size)
        assertEquals("<smil><body></body></smil>", m.parts[0]["text"])
        assertArrayEquals(java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQ=="), m.parts[2].data)
        assertEquals("137", m.addrs[0]["type"])
    }

    @Test fun ourOutputRoundTripsAndKeepsUnknownFields() {
        val (sms, mms, _) = parse(sbr)
        val w = StringWriter()
        SmsXml.smsWriter(w).use { x -> x.header(3, 1, "set"); sms.forEach { x.sms(it) }; mms.forEach { x.mms(it) } }
        val out = w.toString()
        assertTrue(out.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"))
        assertTrue(out.contains("<smses count=\"3\""))
        val (sms2, mms2, count2) = parse(out)
        assertEquals(3, count2)
        assertEquals(sms.map { it.attrs }, sms2.map { it.attrs })
        assertEquals(mms.map { it.attrs }, mms2.map { it.attrs })
        assertEquals(mms[0].parts.map { it.attrs }, mms2[0].parts.map { it.attrs })
        assertArrayEquals(mms[0].parts[2].data, mms2[0].parts[2].data)
        assertEquals(mms[0].addrs, mms2[0].addrs)
        assertEquals("smses" to 3, SmsXml.peekCount(out.take(600)))
    }

    @Test fun callsRoundTrip() {
        val src = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<calls count="2" backup_set="x" backup_date="1" type="full">
  <call number="+8613800138000" duration="65" date="1792600000000" type="1" presentation="1" subscription_id="89860" post_dial_digits="" subscription_component_name="com.android.phone/com.android.services.telephony.TelephonyConnectionService" readable_date="2026年10月21日 00:26:40" contact_name="张三" />
  <call number="-2" duration="0" date="1792600100000" type="3" presentation="2" subscription_id="null" post_dial_digits="" subscription_component_name="null" readable_date="x" contact_name="(Unknown)" />
</calls>"""
        val calls = ArrayList<CallRec>()
        SmsXml.read(StringReader(src), SmsXml.Visitor(call = { calls.add(it) }))
        assertEquals(2, calls.size)
        val v = SmsSchema.callToValues(calls[0])
        assertEquals("65", v["duration"]); assertEquals("张三", v["name"]); assertEquals("", v["post_dial_digits"])
        assertFalse(SmsSchema.callToValues(calls[1]).containsKey("name"))
        val w = StringWriter()
        SmsXml.callsWriter(w).use { x -> x.header(2, 1, "x"); calls.forEach { x.call(it) } }
        val back = ArrayList<CallRec>(); SmsXml.read(StringReader(w.toString()), SmsXml.Visitor(call = { back.add(it) }))
        assertEquals(calls.map { it.attrs }, back.map { it.attrs })
        assertEquals("calls" to 2, SmsXml.peekCount(w.toString()))
    }

    @Test fun providerRowsMapToTheSbrShape() {
        val row = mapOf("address" to "10086", "date" to "1792600000123", "type" to "1", "body" to "hi", "read" to "0", "subject" to null, "thread_id" to "9", "_id" to "5")
        val r = SmsSchema.smsFromRow(row, null)
        assertEquals(listOf("protocol", "address", "date", "type", "subject", "body", "toa", "sc_toa", "service_center", "read", "status", "locked", "date_sent", "sub_id", "readable_date", "contact_name"), r.attrs.keys.toList())
        assertEquals("null", r.attrs["subject"]); assertEquals("(Unknown)", r.attrs["contact_name"])
        val v = SmsSchema.smsToValues(r)
        assertFalse(v.containsKey("thread_id")); assertFalse(v.containsKey("subject")); assertEquals("0", v["read"])
        // MMS: seconds in the provider, milliseconds in the file.
        val m = SmsSchema.mmsFromRow(mapOf("date" to "1792600200", "date_sent" to "1792600199", "msg_box" to "2", "m_id" to "x"), "+86139", emptyList(), emptyList(), "李四")
        assertEquals("1792600200000", m.attrs["date"])
        assertEquals("1792600200", SmsSchema.mmsToValues(m)["date"])
        assertEquals("1792600199", SmsSchema.mmsToValues(m)["date_sent"])
        assertFalse(SmsSchema.mmsToValues(m).containsKey("address"))
    }

    @Test fun xmlReaderEdgeCases() {
        val p = MiniXml.PullReader(StringReader("<!-- c <x/> --><a k='1' j = \"2&#x4E2D;\"><![CDATA[<b/>]]><b/></a>"))
        val e = generateSequence { p.next() }.toList()
        assertEquals(listOf("Start a", "Start b", "End b", "End a"), e.map { if (it is MiniXml.Start) "Start ${it.tag}" else "End ${(it as MiniXml.End).tag}" })
        assertEquals(mapOf("k" to "1", "j" to "2中"), (e[0] as MiniXml.Start).attrs)
        assertEquals("a&#10;b &amp; &lt;&quot;&apos;", MiniXml.escape("a\nb & <\"'"))
    }
}

class VCardTest {
    @Test fun roundTrip() {
        val c = VContact(formatted = "张三", family = "张", given = "三", nickname = "老张",
            org = "幽灵科技; 有限公司", department = "研发", title = "工程师", note = "第一行\n第二行，含逗号；分号\\反斜杠 " + "很长".repeat(60), birthday = "1990-05-01",
            photo = byteArrayOf(1, 2, 3, -1))
        c.phones += VContact.Typed("cell", "+86 138-0013-8000"); c.phones += VContact.Typed("work", "010-12345678")
        c.emails += VContact.Typed("home", "zhang@example.com")
        c.addresses += VContact.VAddress("home", street = "长安街 1 号", city = "北京", country = "中国")
        c.urls += "https://example.com/a,b"
        val w = StringWriter(); VCard.write(w, c); VCard.write(w, VContact(given = "Bob", family = "Lee"))
        val text = w.toString()
        assertTrue(text.lines().all { it.toByteArray().size <= 75 })   // folded
        val back = VCard.read(StringReader(text))
        assertEquals(2, back.size)
        assertEquals(c, back[0])
        assertEquals("Lee Bob", back[1].displayName)
        assertEquals("Bob", back[1].given)
    }

    @Test fun readsAndroidVcard21QuotedPrintable() {
        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=9D=8E;=E5=9B=9B;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=9D=8E=E5=9B=9B=E7=9A=84=E5=BE=88=E9=95=BF=E7=9A=84=E5=90=8D=E5=AD=97=E9=9C=80=E8=A6=81=\r\n=E6=8D=A2=E8=A1=8C\r\n" +
            "TEL;CELL;PREF:13900139000\r\nitem1.TEL:+1 650 555 0100\r\nitem1.X-ABLabel:Office\r\nEMAIL;HOME:li@example.com\r\nEND:VCARD\r\n"
        val c = VCard.read(StringReader(v21)).single()
        assertEquals("李", c.family); assertEquals("四", c.given)
        assertEquals("李四的很长的名字需要换行", c.formatted)
        assertEquals(listOf(VContact.Typed("cell", "13900139000"), VContact.Typed("", "+1 650 555 0100")), c.phones)
        assertEquals("home", c.emails.single().type)
    }
}

class ICalTest {
    @Test fun roundTrip() {
        val ev = listOf(
            VEvent("u1@ghost", "周会，讨论; 计划", 1792600200000, 1792603800000, description = "第一行\n第二行", location = "会议室 A", reminders = listOf(10, 60), timezone = "Asia/Shanghai"),
            VEvent("u2@ghost", "国庆", 1790726400000, 1790726400000 + 7 * 86_400_000L, allDay = true),
            VEvent("u3@ghost", "每周跑步", 1792600200000, null, rrule = "FREQ=WEEKLY;BYDAY=TU,TH", duration = "PT45M"),
        )
        val w = StringWriter(); ICal.begin(w); ev.forEach { ICal.write(w, it, 1) }; ICal.end(w)
        val back = ICal.read(StringReader(w.toString()), ZoneId.of("UTC"))
        assertEquals(ev, back)
    }

    @Test fun readsTzidAndDurations() {
        val s = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:午饭\r\nDTSTART;TZID=Asia/Shanghai:20261022T120000\r\nDURATION:PT1H30M\r\n" +
            "BEGIN:VALARM\r\nTRIGGER:-P1D\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val e = ICal.read(StringReader(s), ZoneId.of("UTC")).single()
        assertEquals(java.time.ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), e.start)
        assertEquals(e.start + 90 * 60_000L, e.end)
        assertEquals(listOf(1440), e.reminders)
        assertEquals(-15 * 60_000L, ICal.durationMs("-PT15M")); assertEquals(14 * 86_400_000L, ICal.durationMs("P2W"))
    }
}

class BackupDedupeTest {
    @Test fun phonesAndKeys() {
        assertEquals("13800138000", BackupDedupe.phone("+86 138-0013-8000"))
        assertEquals("13800138000", BackupDedupe.phone("0086 13800138000"))
        assertEquals("10086", BackupDedupe.phone("10086"))
        assertEquals(BackupDedupe.sms("+8613800138000", 1792600000123, "1", "hi\r\n"), BackupDedupe.sms("13800138000", 1792600000999, "1", "hi"))
        assertNotEquals(BackupDedupe.sms("13800138000", 1792600000123, "1", "hi"), BackupDedupe.sms("13800138000", 1792600000123, "2", "hi"))
        assertEquals(BackupDedupe.mms("id1", 5, "1", "a"), BackupDedupe.mms("id1", 5, "2", "b"))
        assertEquals(BackupDedupe.call("+86 138 0013 8000", 1000, "5", "1"), BackupDedupe.call("13800138000", 1500, "5", "1"))
        assertEquals(BackupDedupe.contact("张三", listOf("010-1", "+86 13800138000"), emptyList()), BackupDedupe.contact(" 张三 ", listOf("13800138000", "0101"), listOf("a@b")))
        assertEquals(BackupDedupe.contact("A", emptyList(), listOf("X@y.com")), BackupDedupe.contact("a", emptyList(), listOf("x@Y.com ")))
        assertEquals(BackupDedupe.event("会", 1000, 2000, false), BackupDedupe.event("会 ", 1999, 2500, false))
    }

    /** Restoring the same file twice writes nothing the second time. */
    @Test fun secondRestoreIsANoOp() {
        val device = HashSet<String>()
        val incoming = listOf("a" to 1000L, "b" to 2000L, "a" to 1000L)
        fun restore() = incoming.count { (body, d) -> device.add(BackupDedupe.sms("10086", d, "1", body)) }
        assertEquals(2, restore()); assertEquals(0, restore())
    }
}

class BackupManifestTest {
    /** 0.7.1-test1 could write a Root WiFi item; it is no longer supported and must be ignored without error. */
    @Test fun legacyWifiItemIsIgnored() {
        val json = """{"format": "ghost-backup", "version": 1, "created": 1, "device": "d", "app": "0.7.1-test1", "sdk": 34, "items": [
            {"kind": "sms", "count": 3, "file": "sms-1.xml"},
            {"kind": "wifi", "count": 8, "file": "wifi/WifiConfigStore.xml"},
            {"kind": "contacts", "count": 2, "file": "contacts-1.vcf"}]}"""
        val m = BackupManifest.parse(json)!!
        assertEquals(listOf(BackupKind.SMS, BackupKind.CONTACTS), m.counts.keys.toList())
        assertEquals(listOf(BackupKind.SMS, BackupKind.CONTACTS), m.files.keys.toList())
        assertNull(BackupManifest.kindOf("WifiConfigStore.xml"))
        assertNull(BackupKind.of("wifi"))
        assertEquals(4, BackupKind.entries.size)
    }

    @Test fun jsonRoundTripAndFileKinds() {
        val m = BackupManifest(1792600000000, "Xiaomi \"14\"", "0.7.1-test1",
            linkedMapOf(BackupKind.SMS to 1200, BackupKind.CALLS to 300, BackupKind.CALENDAR to 8),
            linkedMapOf(BackupKind.SMS to "sms-1.xml", BackupKind.CALLS to "calls-1.xml", BackupKind.CALENDAR to "calendar-1.ics"), sdk = 34)
        assertEquals(m, BackupManifest.parse(m.toJson()))
        assertNull(BackupManifest.parse("{\"format\":\"other\"}"))
        assertEquals(BackupKind.SMS, BackupManifest.kindOf("sms-20261022093000.xml"))
        assertEquals(BackupKind.CALLS, BackupManifest.kindOf("calls-20261022093000.xml"))
        assertEquals(BackupKind.CONTACTS, BackupManifest.kindOf("通讯录.VCF"))
        assertNull(BackupManifest.kindOf("notes.xml"))
        assertTrue(BackupManifest.opensRestore("ghost-backup.json")); assertTrue(BackupManifest.opensRestore("ghost-backup-20261022-0930.zip"))
        assertTrue(BackupManifest.opensRestore("sms-20261022093000.xml")); assertFalse(BackupManifest.opensRestore("photos.zip"))
        assertFalse(BackupManifest.opensRestore("contacts.vcf"))
    }
}
