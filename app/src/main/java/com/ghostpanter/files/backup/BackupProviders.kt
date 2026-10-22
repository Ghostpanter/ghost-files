package com.ghostpanter.files.backup

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.ghostpanter.files.core.backup.BackupDedupe
import com.ghostpanter.files.core.backup.BackupKind
import com.ghostpanter.files.core.backup.CallRec
import com.ghostpanter.files.core.backup.ICal
import com.ghostpanter.files.core.backup.MmsPart
import com.ghostpanter.files.core.backup.MmsRec
import com.ghostpanter.files.core.backup.SmsRec
import com.ghostpanter.files.core.backup.SmsSchema
import com.ghostpanter.files.core.backup.SmsXml
import com.ghostpanter.files.core.backup.VCard
import com.ghostpanter.files.core.backup.VContact
import com.ghostpanter.files.core.backup.VEvent
import java.io.Reader
import java.io.Writer
import java.util.concurrent.CancellationException

/** Progress sink: (done, total). [cancelled] is polled between items. */
class Tick(val cancelled: () -> Boolean, val progress: (Int, Int) -> Unit) {
    fun check() { if (cancelled()) throw CancellationException() }
}

/** Reads / writes SMS, MMS, call log, contacts, calendar through the system providers. */
class BackupProviders(private val ctx: Context) {
    private val cr = ctx.contentResolver

    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Runtime permissions needed to back up / restore [k] (SMS writes need the default SMS role instead). */
    fun permissions(k: BackupKind, restore: Boolean): List<String> = when (k) {
        BackupKind.SMS -> listOf(Manifest.permission.READ_SMS) + (if (!restore) listOf(Manifest.permission.READ_CONTACTS) else emptyList())
        BackupKind.CALLS -> listOf(Manifest.permission.READ_CALL_LOG) + if (restore) listOf(Manifest.permission.WRITE_CALL_LOG) else emptyList()
        BackupKind.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS) + if (restore) listOf(Manifest.permission.WRITE_CONTACTS) else emptyList()
        BackupKind.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR) + if (restore) listOf(Manifest.permission.WRITE_CALENDAR) else emptyList()
    }

    private fun count(uri: Uri, sel: String? = null): Int? = runCatching { cr.query(uri, arrayOf("_id"), sel, null, null)?.use { it.count } }.getOrNull()

    /** Items on the device (null = no permission / unreadable). */
    fun count(k: BackupKind): Int? = when (k) {
        BackupKind.SMS -> if (!has(Manifest.permission.READ_SMS)) null else (count(Telephony.Sms.CONTENT_URI) ?: 0) + (count(Telephony.Mms.CONTENT_URI) ?: 0)
        BackupKind.CALLS -> if (!has(Manifest.permission.READ_CALL_LOG)) null else count(CallLog.Calls.CONTENT_URI)
        BackupKind.CONTACTS -> if (!has(Manifest.permission.READ_CONTACTS)) null else count(ContactsContract.Contacts.CONTENT_URI)
        BackupKind.CALENDAR -> if (!has(Manifest.permission.READ_CALENDAR)) null else count(CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events.DELETED}=0")
    }

    private fun row(c: Cursor): Map<String, String?> {
        val m = HashMap<String, String?>(c.columnCount * 2)
        for (i in 0 until c.columnCount) {
            m[c.getColumnName(i)] = when (c.getType(i)) { Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_BLOB -> null; else -> c.getString(i) }
        }
        return m
    }

    // ------------------------------------------------------------------ SMS / MMS

    private val names = HashMap<String, String?>()
    private fun contactName(address: String?): String? {
        if (address.isNullOrBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        return names.getOrPut(address) {
            runCatching {
                cr.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)), arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }
    }

    fun exportSms(w: Writer, t: Tick): Int {
        val smsTotal = count(Telephony.Sms.CONTENT_URI) ?: 0
        val mmsTotal = count(Telephony.Mms.CONTENT_URI) ?: 0
        val total = smsTotal + mmsTotal
        var done = 0
        val x = SmsXml.smsWriter(w)
        x.header(total, System.currentTimeMillis(), java.util.UUID.randomUUID().toString())
        cr.query(Telephony.Sms.CONTENT_URI, null, null, null, "date ASC")?.use { c ->
            while (c.moveToNext()) {
                t.check()
                val r = row(c)
                x.sms(SmsSchema.smsFromRow(r, contactName(r["address"])))
                if (++done % 50 == 0) t.progress(done, total)
            }
        }
        cr.query(Telephony.Mms.CONTENT_URI, null, null, null, "date ASC")?.use { c ->
            while (c.moveToNext()) {
                t.check()
                val r = row(c)
                val id = r["_id"] ?: continue
                runCatching { x.mms(mms(id, r)) }
                if (++done % 10 == 0) t.progress(done, total)
            }
        }
        x.close()
        t.progress(done, total)
        return done
    }

    private fun mms(id: String, r: Map<String, String?>): MmsRec {
        val addrs = ArrayList<LinkedHashMap<String, String>>()
        cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type", "charset"), null, null, null)?.use { c ->
            while (c.moveToNext()) addrs.add(linkedMapOf("address" to (c.getString(0) ?: "null"), "type" to (c.getString(1) ?: "null"), "charset" to (c.getString(2) ?: "106")))
        }
        val incoming = r["msg_box"] == "1"
        val party = addrs.filter { a -> a["type"] == (if (incoming) "137" else "151") && a["address"] != "insert-address-token" }.map { it["address"]!! }
        val parts = ArrayList<MmsPart>()
        cr.query(Uri.parse("content://mms/part"), null, "mid=?", arrayOf(id), "seq ASC")?.use { c ->
            while (c.moveToNext()) {
                val p = row(c)
                val a = LinkedHashMap<String, String>()
                for (k in SmsSchema.PART_COLUMNS) a[k] = p[k] ?: "null"
                val ct = p["ct"] ?: ""
                val data = if (p["_data"] != null && ct != "text/plain" && ct != "application/smil") runCatching {
                    cr.openInputStream(Uri.parse("content://mms/part/" + p["_id"]))?.use { it.readBytes() }
                }.getOrNull() else null
                parts.add(MmsPart(a, data))
            }
        }
        return SmsSchema.mmsFromRow(r, party.joinToString("~").ifEmpty { null }, parts, addrs, contactName(party.firstOrNull()))
    }

    class RestoreCount(var added: Int = 0, var skipped: Int = 0, var failed: Int = 0)

    /** Needs the default SMS role. Existing messages (same address, second, box, text) are skipped. */
    fun restoreSms(r: Reader, t: Tick): RestoreCount {
        val res = RestoreCount()
        val have = HashSet<String>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "date", "type", "body"), null, null, null)?.use { c ->
            while (c.moveToNext()) have.add(BackupDedupe.sms(c.getString(0), c.getLong(1), c.getString(2), c.getString(3)))
        }
        cr.query(Telephony.Mms.CONTENT_URI, arrayOf("m_id", "date", "msg_box", "sub"), null, null, null)?.use { c ->
            while (c.moveToNext()) have.add(BackupDedupe.mms(c.getString(0), c.getLong(1), c.getString(2), c.getString(3)))
        }
        var total = -1; var done = 0
        val batch = ArrayList<ContentValues>()
        fun flush() {
            if (batch.isEmpty()) return
            val n = runCatching { cr.bulkInsert(Telephony.Sms.CONTENT_URI, batch.toTypedArray()) }.getOrElse { 0 }
            res.added += n; res.failed += batch.size - n; batch.clear()
        }
        SmsXml.read(r, SmsXml.Visitor(
            count = { total = it },
            cancelled = t.cancelled,
            sms = { s ->
                val key = BackupDedupe.sms(s["address"], s["date"]?.toLongOrNull() ?: 0, s["type"], s["body"])
                if (!have.add(key)) res.skipped++ else {
                    batch.add(ContentValues().apply { SmsSchema.smsToValues(s).forEach { (k, v) -> put(k, v) } })
                    if (batch.size >= 200) flush()
                }
                if (++done % 50 == 0) t.progress(done, total)
            },
            mms = { m ->
                flush()
                val dateSec = (m["date"]?.toLongOrNull() ?: 0) / 1000
                if (!have.add(BackupDedupe.mms(m["m_id"], dateSec, m["msg_box"], m["sub"]))) res.skipped++
                else if (runCatching { insertMms(m) }.isSuccess) res.added++ else res.failed++
                if (++done % 10 == 0) t.progress(done, total)
            },
        ))
        flush()
        t.progress(done, total)
        return res
    }

    private fun insertMms(m: MmsRec) {
        val party = (m["address"] ?: "").split('~').filter { it.isNotBlank() }.toSet()
        val values = ContentValues().apply { SmsSchema.mmsToValues(m).forEach { (k, v) -> put(k, v) } }
        if (party.isNotEmpty()) values.put("thread_id", Telephony.Threads.getOrCreateThreadId(ctx, party))
        val uri = cr.insert(Telephony.Mms.CONTENT_URI, values) ?: throw java.io.IOException("mms insert")
        val id = uri.lastPathSegment ?: throw java.io.IOException("mms id")
        for (a in m.addrs) cr.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
            put("address", a["address"]); put("type", a["type"]); put("charset", a["charset"] ?: "106"); put("msg_id", id)
        })
        for (p in m.parts) {
            val pv = ContentValues().apply { SmsSchema.partToValues(p).forEach { (k, v) -> put(k, v) }; put("mid", id) }
            val pu = cr.insert(Uri.parse("content://mms/$id/part"), pv) ?: continue
            p.data?.let { d -> cr.openOutputStream(pu)?.use { it.write(d) } }
        }
    }

    // ------------------------------------------------------------------ call log

    fun exportCalls(w: Writer, t: Tick): Int {
        val total = count(CallLog.Calls.CONTENT_URI) ?: 0
        var done = 0
        val x = SmsXml.callsWriter(w)
        x.header(total, System.currentTimeMillis(), java.util.UUID.randomUUID().toString())
        cr.query(CallLog.Calls.CONTENT_URI, null, null, null, "date ASC")?.use { c ->
            while (c.moveToNext()) { t.check(); x.call(SmsSchema.callFromRow(row(c))); if (++done % 100 == 0) t.progress(done, total) }
        }
        x.close(); t.progress(done, total)
        return done
    }

    fun restoreCalls(r: Reader, t: Tick): RestoreCount {
        val res = RestoreCount()
        val have = HashSet<String>()
        cr.query(CallLog.Calls.CONTENT_URI, arrayOf("number", "date", "duration", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) have.add(BackupDedupe.call(c.getString(0), c.getLong(1), c.getString(2), c.getString(3)))
        }
        val batch = ArrayList<ContentValues>()
        fun flush() {
            if (batch.isEmpty()) return
            val n = runCatching { cr.bulkInsert(CallLog.Calls.CONTENT_URI, batch.toTypedArray()) }.getOrElse { 0 }
            res.added += n; res.failed += batch.size - n; batch.clear()
        }
        var total = -1; var done = 0
        SmsXml.read(r, SmsXml.Visitor(count = { total = it }, cancelled = t.cancelled, call = { c: CallRec ->
            if (!have.add(BackupDedupe.call(c["number"], c["date"]?.toLongOrNull() ?: 0, c["duration"], c["type"]))) res.skipped++
            else { batch.add(ContentValues().apply { SmsSchema.callToValues(c).forEach { (k, v) -> put(k, v) } }); if (batch.size >= 200) flush() }
            if (++done % 100 == 0) t.progress(done, total)
        }))
        flush(); t.progress(done, total)
        return res
    }

    // ------------------------------------------------------------------ contacts

    private val phoneTypes = mapOf(1 to "home", 2 to "cell", 3 to "work", 4 to "fax", 5 to "fax", 6 to "pager", 7 to "other", 17 to "work")
    private val emailTypes = mapOf(1 to "home", 2 to "work", 3 to "other", 4 to "cell")
    private val addrTypes = mapOf(1 to "home", 2 to "work", 3 to "other")

    fun readContacts(t: Tick): List<VContact> {
        val byId = LinkedHashMap<Long, VContact>()
        cr.query(ContactsContract.Data.CONTENT_URI, null, null, null, "${ContactsContract.Data.CONTACT_ID} ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID); val iMime = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            fun s(n: Int) = c.getString(c.getColumnIndexOrThrow("data$n")) ?: ""
            fun i(n: Int) = c.getInt(c.getColumnIndexOrThrow("data$n"))
            while (c.moveToNext()) {
                t.check()
                val x = byId.getOrPut(c.getLong(iId)) { VContact() }
                when (c.getString(iMime)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> if (x.formatted.isEmpty()) {
                        x.formatted = s(1); x.given = s(2); x.family = s(3); x.prefix = s(4); x.middle = s(5); x.suffix = s(6)
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (s(1).isNotBlank() && x.phones.none { it.value == s(1) }) x.phones.add(VContact.Typed(phoneTypes[i(2)] ?: "", s(1)))
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (s(1).isNotBlank() && x.emails.none { it.value == s(1) }) x.emails.add(VContact.Typed(emailTypes[i(2)] ?: "", s(1)))
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> x.addresses.add(VContact.VAddress(addrTypes[i(2)] ?: "", pobox = s(5), street = s(4).ifEmpty { s(1) }, city = s(7), region = s(8), postcode = s(9), country = s(10)))
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> { if (x.org.isEmpty()) x.org = s(1); if (x.title.isEmpty()) x.title = s(4); if (x.department.isEmpty()) x.department = s(5) }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> if (x.note.isEmpty()) x.note = s(1)
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> if (x.nickname.isEmpty()) x.nickname = s(1)
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> if (i(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) x.birthday = s(1)
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> if (s(1).isNotBlank()) x.urls.add(s(1))
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> if (x.photo == null) x.photo = runCatching { c.getBlob(c.getColumnIndexOrThrow("data15")) }.getOrNull()
                }
            }
        }
        return byId.values.filter { it.displayName.isNotBlank() }
    }

    fun exportContacts(w: Writer, t: Tick): Int {
        val all = readContacts(t)
        all.forEachIndexed { i, c -> t.check(); VCard.write(w, c); if (i % 20 == 0) t.progress(i, all.size) }
        w.flush(); t.progress(all.size, all.size)
        return all.size
    }

    fun restoreContacts(r: Reader, t: Tick): RestoreCount {
        val res = RestoreCount()
        val incoming = VCard.read(r)
        val have = readContacts(Tick({ false }) { _, _ -> }).mapTo(HashSet()) { BackupDedupe.contact(it) }
        val ops = ArrayList<ContentProviderOperation>()
        var pending = 0
        fun flush() {
            if (ops.isEmpty()) return
            if (runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess) res.added += pending else res.failed += pending
            ops.clear(); pending = 0
        }
        val revPhone = phoneTypes.entries.associate { it.value to it.key }; val revEmail = emailTypes.entries.associate { it.value to it.key }; val revAddr = addrTypes.entries.associate { it.value to it.key }
        incoming.forEachIndexed { idx, c ->
            t.check()
            if (!have.add(BackupDedupe.contact(c))) { res.skipped++; return@forEachIndexed }
            val back = ops.size
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            fun data(mime: String, vararg kv: Pair<String, Any?>) {
                val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back).withValue(ContactsContract.Data.MIMETYPE, mime)
                kv.forEach { (k, v) -> b.withValue(k, v) }
                ops.add(b.build())
            }
            data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "data1" to c.displayName, "data2" to c.given.ifEmpty { null }, "data3" to c.family.ifEmpty { null }, "data4" to c.prefix.ifEmpty { null }, "data5" to c.middle.ifEmpty { null }, "data6" to c.suffix.ifEmpty { null })
            c.phones.forEach { p -> data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "data1" to p.value, "data2" to (revPhone[p.type] ?: 7)) }
            c.emails.forEach { e -> data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "data1" to e.value, "data2" to (revEmail[e.type] ?: 3)) }
            c.addresses.forEach { a -> data(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, "data2" to (revAddr[a.type] ?: 3), "data4" to a.street, "data5" to a.pobox, "data7" to a.city, "data8" to a.region, "data9" to a.postcode, "data10" to a.country) }
            if (c.org.isNotEmpty() || c.title.isNotEmpty()) data(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, "data1" to c.org, "data4" to c.title, "data5" to c.department)
            if (c.note.isNotEmpty()) data(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "data1" to c.note)
            if (c.nickname.isNotEmpty()) data(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, "data1" to c.nickname)
            if (c.birthday.isNotEmpty()) data(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, "data1" to c.birthday, "data2" to ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
            c.urls.forEach { u -> data(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, "data1" to u) }
            c.photo?.let { ph -> data(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, "data15" to ph) }
            pending++
            if (ops.size > 300) flush()
            if (idx % 20 == 0) t.progress(idx, incoming.size)
        }
        flush(); t.progress(incoming.size, incoming.size)
        return res
    }

    // ------------------------------------------------------------------ calendar

    fun readEvents(t: Tick): List<VEvent> {
        val out = ArrayList<VEvent>()
        val reminders = HashMap<Long, MutableList<Int>>()
        runCatching {
            cr.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES), null, null, null)?.use { c ->
                while (c.moveToNext()) reminders.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getInt(1))
            }
        }
        val proj = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.RRULE, CalendarContract.Events.DURATION, CalendarContract.Events.UID_2445)
        cr.query(CalendarContract.Events.CONTENT_URI, proj, "${CalendarContract.Events.DELETED}=0", null, "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext()) {
                t.check()
                val id = c.getLong(0)
                val end = if (c.isNull(3)) null else c.getLong(3)
                out.add(VEvent(
                    uid = c.getString(10)?.takeIf { it.isNotBlank() } ?: "ghost-$id-${c.getLong(2)}@${Build.MODEL.replace(' ', '_')}",
                    title = c.getString(1) ?: "", start = c.getLong(2), end = end, allDay = c.getInt(4) == 1, timezone = c.getString(5),
                    description = c.getString(6) ?: "", location = c.getString(7) ?: "", rrule = c.getString(8) ?: "", duration = c.getString(9) ?: "",
                    reminders = reminders[id].orEmpty().sorted(),
                ))
            }
        }
        return out
    }

    fun exportCalendar(w: Writer, t: Tick): Int {
        val ev = readEvents(t)
        ICal.begin(w)
        val now = System.currentTimeMillis()
        ev.forEachIndexed { i, e -> t.check(); ICal.write(w, e, now); if (i % 50 == 0) t.progress(i, ev.size) }
        ICal.end(w); t.progress(ev.size, ev.size)
        return ev.size
    }

    /** First writable visible calendar (primary first), else a new local one. */
    private fun targetCalendar(): Long {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        var best: Pair<Long, Int>? = null
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(3) == 0) continue
                val score = if (c.getInt(2) == 1) 2 else 1
                if (best == null || score > best!!.second) best = c.getLong(0) to score
            }
        }
        best?.let { return it.first }
        val name = "双栏文件管理器"
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, name); put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, name); put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "恢复的日程")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2F80ED.toInt()); put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, name); put(CalendarContract.Calendars.VISIBLE, 1); put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        return cr.insert(uri, v)?.lastPathSegment?.toLong() ?: throw java.io.IOException("no writable calendar")
    }

    fun restoreCalendar(r: Reader, t: Tick): RestoreCount {
        val res = RestoreCount()
        val incoming = ICal.read(r)
        val have = readEvents(Tick({ false }) { _, _ -> }).mapTo(HashSet()) { BackupDedupe.event(it.title, it.start, it.end, it.allDay) }
        val cal = targetCalendar()
        incoming.forEachIndexed { i, e ->
            t.check()
            if (!have.add(BackupDedupe.event(e.title, e.start, e.end, e.allDay))) { res.skipped++; return@forEachIndexed }
            val ok = runCatching {
                val v = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, cal)
                    put(CalendarContract.Events.TITLE, e.title)
                    put(CalendarContract.Events.DTSTART, e.start)
                    if (e.rrule.isNotEmpty()) { put(CalendarContract.Events.RRULE, e.rrule); put(CalendarContract.Events.DURATION, e.duration.ifEmpty { if (e.allDay) "P1D" else "PT1H" }) }
                    else put(CalendarContract.Events.DTEND, e.end ?: (e.start + if (e.allDay) 86_400_000L else 3_600_000L))
                    put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else e.timezone ?: java.util.TimeZone.getDefault().id)
                    if (e.description.isNotEmpty()) put(CalendarContract.Events.DESCRIPTION, e.description)
                    if (e.location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, e.location)
                    if (e.uid.isNotEmpty()) put(CalendarContract.Events.UID_2445, e.uid)
                    if (e.reminders.isNotEmpty()) put(CalendarContract.Events.HAS_ALARM, 1)
                }
                val id = cr.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment?.toLong() ?: throw java.io.IOException("event")
                for (m in e.reminders) cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, m); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }.isSuccess
            if (ok) res.added++ else res.failed++
            if (i % 20 == 0) t.progress(i, incoming.size)
        }
        t.progress(incoming.size, incoming.size)
        return res
    }
}
