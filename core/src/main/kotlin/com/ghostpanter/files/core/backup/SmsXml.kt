package com.ghostpanter.files.core.backup

import java.io.Reader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * SMS Backup & Restore compatible XML (sms-*.xml with <sms>/<mms>, calls-*.xml with <call>).
 * Records are attribute maps so fields we do not know survive a round trip.
 */
class SmsRec(val attrs: LinkedHashMap<String, String>) {
    operator fun get(k: String): String? = attrs[k]?.takeUnless { it == "null" }
}

class MmsPart(val attrs: LinkedHashMap<String, String>, val data: ByteArray?) {
    operator fun get(k: String): String? = attrs[k]?.takeUnless { it == "null" }
}

class MmsRec(val attrs: LinkedHashMap<String, String>, val parts: List<MmsPart>, val addrs: List<LinkedHashMap<String, String>>) {
    operator fun get(k: String): String? = attrs[k]?.takeUnless { it == "null" }
}

class CallRec(val attrs: LinkedHashMap<String, String>) {
    operator fun get(k: String): String? = attrs[k]?.takeUnless { it == "null" }
}

object SmsXml {
    private const val HEADER = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
    private const val STYLE_SMS = "<!--File Created By 双栏文件管理器 (SMS Backup & Restore compatible)-->\n"

    fun readableDate(ms: Long): String = SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA).format(Date(ms))

    /** SMS Backup & Restore file names: sms-20261022093000.xml / calls-20261022093000.xml. */
    fun stamp(ms: Long): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(ms))

    class Writer(private val w: java.io.Writer, private val root: String) : java.io.Closeable {
        private var closed = false
        fun header(count: Int, backupDate: Long, backupSet: String) {
            w.write(HEADER); w.write(STYLE_SMS)
            w.write("<$root count=\"$count\" backup_set=\"${MiniXml.escape(backupSet)}\" backup_date=\"$backupDate\" type=\"full\">\n")
        }
        fun sms(r: SmsRec) { MiniXml.startTag(w, "sms", r.attrs, 1); w.write(" />\n") }
        fun call(r: CallRec) { MiniXml.startTag(w, "call", r.attrs, 1); w.write(" />\n") }
        fun mms(r: MmsRec) {
            MiniXml.startTag(w, "mms", r.attrs, 1); w.write(">\n")
            w.write("    <parts>\n")
            for (p in r.parts) {
                val a = LinkedHashMap<String, String?>(p.attrs)
                if (p.data != null) a["data"] = Base64.getEncoder().encodeToString(p.data)
                MiniXml.startTag(w, "part", a, 3); w.write(" />\n")
            }
            w.write("    </parts>\n")
            w.write("    <addrs>\n")
            for (ad in r.addrs) { MiniXml.startTag(w, "addr", ad, 3); w.write(" />\n") }
            w.write("    </addrs>\n")
            w.write("  </mms>\n")
        }
        override fun close() { if (!closed) { closed = true; w.write("</$root>\n"); w.flush() } }
    }

    fun smsWriter(w: java.io.Writer) = Writer(w, "smses")
    fun callsWriter(w: java.io.Writer) = Writer(w, "calls")

    class Visitor(
        val sms: (SmsRec) -> Unit = {},
        val mms: (MmsRec) -> Unit = {},
        val call: (CallRec) -> Unit = {},
        /** Root element count attribute (progress), -1 when absent. */
        val count: (Int) -> Unit = {},
        val cancelled: () -> Boolean = { false },
    )

    /** Streams an sms-*.xml or calls-*.xml file. */
    fun read(r: Reader, v: Visitor) {
        val p = MiniXml.PullReader(r)
        var mms: LinkedHashMap<String, String>? = null
        var parts = ArrayList<MmsPart>(); var addrs = ArrayList<LinkedHashMap<String, String>>()
        while (true) {
            if (v.cancelled()) throw java.util.concurrent.CancellationException()
            val e = p.next() ?: break
            when (e) {
                is MiniXml.Start -> when (e.tag) {
                    "smses", "calls" -> v.count(e.attrs["count"]?.toIntOrNull() ?: -1)
                    "sms" -> v.sms(SmsRec(LinkedHashMap(e.attrs)))
                    "call" -> v.call(CallRec(LinkedHashMap(e.attrs)))
                    "mms" -> { mms = LinkedHashMap(e.attrs); parts = ArrayList(); addrs = ArrayList() }
                    "part" -> if (mms != null) {
                        val a = LinkedHashMap(e.attrs)
                        val data = a.remove("data")?.takeUnless { it == "null" || it.isEmpty() }?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
                        parts.add(MmsPart(a, data))
                    }
                    "addr" -> if (mms != null) addrs.add(LinkedHashMap(e.attrs))
                }
                is MiniXml.End -> if (e.tag == "mms" && mms != null) { v.mms(MmsRec(mms, parts, addrs)); mms = null }
            }
        }
    }

    /** Root `count` of a file without reading it all (null = not an SMS B&R file). */
    fun peekCount(head: String): Pair<String, Int>? {
        val m = Regex("<(smses|calls)\\b[^>]*?count=\"(\\d+)\"").find(head) ?: return null
        return m.groupValues[1] to m.groupValues[2].toInt()
    }
}

/** Android provider columns ↔ SMS Backup & Restore attributes (pure so it can be tested). */
object SmsSchema {
    /** Writable columns of content://sms we restore (others are display-only in the XML). */
    val SMS_COLUMNS = listOf("address", "date", "type", "subject", "body", "read", "status", "locked", "date_sent", "service_center", "protocol", "seen", "sub_id", "error_code", "reply_path_present")

    /** Order SMS Backup & Restore writes; unknown values become "null" like it does. */
    val SMS_ATTRS = listOf("protocol", "address", "date", "type", "subject", "body", "toa", "sc_toa", "service_center", "read", "status", "locked", "date_sent", "sub_id")

    val MMS_COLUMNS = listOf(
        "date", "date_sent", "msg_box", "read", "seen", "sub", "sub_cs", "ct_t", "ct_l", "ct_cls", "m_id", "m_type", "m_cls", "m_size",
        "exp", "pri", "rr", "rpt_a", "resp_st", "st", "tr_id", "retr_st", "retr_txt", "retr_txt_cs", "read_status", "d_rpt", "d_tm", "v",
        "locked", "sub_id", "text_only", "creator",
    )
    val PART_COLUMNS = listOf("seq", "ct", "name", "chset", "cd", "fn", "cid", "cl", "ctt_s", "ctt_t", "text")
    val CALL_COLUMNS = listOf("number", "duration", "date", "type", "presentation", "subscription_id", "post_dial_digits", "subscription_component_name", "new", "features", "countryiso", "geocoded_location")
    val CALL_ATTRS = listOf("number", "duration", "date", "type", "presentation", "subscription_id", "post_dial_digits", "subscription_component_name")

    /** Builds an <sms> record from a provider row (column -> value, null = SQL NULL). */
    fun smsFromRow(row: Map<String, String?>, contactName: String?): SmsRec {
        val a = LinkedHashMap<String, String>()
        for (k in SMS_ATTRS) a[k] = row[k] ?: when (k) { "protocol" -> "0"; "status" -> "-1"; "locked" -> "0"; "read" -> "1"; "sub_id" -> "-1"; else -> "null" }
        val date = row["date"]?.toLongOrNull() ?: 0
        a["readable_date"] = SmsXml.readableDate(date)
        a["contact_name"] = contactName ?: "(Unknown)"
        return SmsRec(a)
    }

    /** Provider values to insert for an <sms> record (only real columns, no "null"). */
    fun smsToValues(r: SmsRec): Map<String, String> = SMS_COLUMNS.mapNotNull { k -> r[k]?.let { k to it } }.toMap()

    /** content://mms stores dates in seconds; SMS Backup & Restore writes milliseconds. */
    fun mmsFromRow(row: Map<String, String?>, address: String?, parts: List<MmsPart>, addrs: List<LinkedHashMap<String, String>>, contactName: String?): MmsRec {
        val a = LinkedHashMap<String, String>()
        for (k in MMS_COLUMNS) {
            val v = row[k]
            a[k] = when (k) { "date" -> (v?.toLongOrNull() ?: 0).times(1000).toString(); "date_sent" -> ((v?.toLongOrNull() ?: 0) * 1000).toString(); else -> v ?: "null" }
        }
        a["address"] = address ?: "null"
        a["readable_date"] = SmsXml.readableDate(a["date"]!!.toLong())
        a["contact_name"] = contactName ?: "(Unknown)"
        return MmsRec(a, parts, addrs)
    }

    fun mmsToValues(r: MmsRec): Map<String, String> = MMS_COLUMNS.mapNotNull { k ->
        val v = r[k] ?: return@mapNotNull null
        k to when (k) { "date", "date_sent" -> ((v.toLongOrNull() ?: 0) / 1000).toString(); else -> v }
    }.toMap()

    fun partToValues(p: MmsPart): Map<String, String> = PART_COLUMNS.mapNotNull { k -> p[k]?.let { k to it } }.toMap()

    fun callFromRow(row: Map<String, String?>): CallRec {
        val a = LinkedHashMap<String, String>()
        for (k in CALL_ATTRS) a[k] = row[k] ?: when (k) { "presentation" -> "1"; "post_dial_digits" -> ""; else -> "null" }
        val date = row["date"]?.toLongOrNull() ?: 0
        a["readable_date"] = SmsXml.readableDate(date)
        a["contact_name"] = row["name"] ?: "(Unknown)"
        return CallRec(a)
    }

    fun callToValues(r: CallRec): Map<String, String> {
        val m = CALL_COLUMNS.mapNotNull { k -> r[k]?.let { k to it } }.toMap(LinkedHashMap())
        r["contact_name"]?.takeUnless { it == "(Unknown)" }?.let { m["name"] = it }
        return m
    }
}
