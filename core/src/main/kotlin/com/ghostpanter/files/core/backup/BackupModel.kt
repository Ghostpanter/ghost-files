package com.ghostpanter.files.core.backup

/** What a backup can hold. */
enum class BackupKind(val id: String) {
    SMS("sms"), CALLS("calls"), CONTACTS("contacts"), CALENDAR("calendar"), WIFI("wifi");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** ghost-backup.json: what is inside a backup folder / zip. */
data class BackupManifest(
    val created: Long,
    val device: String,
    val app: String,
    val counts: Map<BackupKind, Int>,
    /** File name inside the backup for each kind. */
    val files: Map<BackupKind, String>,
    val sdk: Int = 0,
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"format\": \"ghost-backup\",\n  \"version\": 1,\n")
        append("  \"created\": $created,\n")
        append("  \"device\": ").append(q(device)).append(",\n")
        append("  \"app\": ").append(q(app)).append(",\n")
        append("  \"sdk\": $sdk,\n")
        append("  \"items\": [")
        append(counts.keys.joinToString(",") { k -> "\n    {\"kind\": ${q(k.id)}, \"count\": ${counts[k]}, \"file\": ${q(files[k] ?: "")}}" })
        append("\n  ]\n}\n")
    }

    companion object {
        const val NAME = "ghost-backup.json"

        private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        private fun str(json: String, key: String) = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\n", "\n")?.replace("\\\\", "\\")
        private fun num(json: String, key: String) = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLong()

        fun parse(json: String): BackupManifest? {
            if (str(json, "format") != "ghost-backup") return null
            val counts = LinkedHashMap<BackupKind, Int>(); val files = LinkedHashMap<BackupKind, String>()
            Regex("\\{[^{}]*\"kind\"[^{}]*\\}").findAll(json).forEach { m ->
                val k = BackupKind.of(str(m.value, "kind") ?: return@forEach) ?: return@forEach
                counts[k] = num(m.value, "count")?.toInt() ?: 0
                files[k] = str(m.value, "file") ?: ""
            }
            return BackupManifest(num(json, "created") ?: 0, str(json, "device") ?: "", str(json, "app") ?: "", counts, files, num(json, "sdk")?.toInt() ?: 0)
        }

        /** Default file name for each kind (SMS Backup & Restore style for sms / calls). */
        fun fileName(k: BackupKind, stamp: String) = when (k) {
            BackupKind.SMS -> "sms-$stamp.xml"
            BackupKind.CALLS -> "calls-$stamp.xml"
            BackupKind.CONTACTS -> "contacts-$stamp.vcf"
            BackupKind.CALENDAR -> "calendar-$stamp.ics"
            BackupKind.WIFI -> "wifi/WifiConfigStore.xml"
        }

        /** Which kind a loose file is (sms-*.xml, calls-*.xml, *.vcf, *.ics), null = not a backup file. */
        fun kindOf(name: String): BackupKind? {
            val n = name.lowercase()
            return when {
                n.startsWith("sms-") && n.endsWith(".xml") -> BackupKind.SMS
                n.startsWith("calls-") && n.endsWith(".xml") -> BackupKind.CALLS
                n.endsWith(".vcf") -> BackupKind.CONTACTS
                n.endsWith(".ics") -> BackupKind.CALENDAR
                n == "wificonfigstore.xml" || n == "wpa_supplicant.conf" -> BackupKind.WIFI
                else -> null
            }
        }

        /** A file the file manager opens in 备份与恢复 instead of its default viewer. */
        fun opensRestore(name: String): Boolean {
            val n = name.lowercase()
            return n == NAME || (n.startsWith("ghost-backup-") && n.endsWith(".zip")) ||
                (n.startsWith("sms-") && n.endsWith(".xml")) || (n.startsWith("calls-") && n.endsWith(".xml"))
        }
    }
}

/** Duplicate keys: an item whose key already exists on the device is not written again. */
object BackupDedupe {
    /** Digits only, without a leading 00 / +; long numbers keep their last 11 digits (+86 138… == 138…). */
    fun phone(s: String?): String {
        val d = (s ?: "").filter { it.isDigit() }.removePrefix("00")
        return if (d.length > 11) d.takeLast(11) else d
    }

    private fun body(s: String?) = (s ?: "").replace("\r\n", "\n").trim()

    /** SMS: same address, time (to the second), box and text. */
    fun sms(address: String?, dateMs: Long, type: String?, body: String?) = "s|" + phone(address) + "|" + dateMs / 1000 + "|" + (type ?: "") + "|" + body(body).hashCode()

    /** MMS: message id when present, else time (s) + box + subject. */
    fun mms(mId: String?, dateSec: Long, msgBox: String?, sub: String?) =
        if (!mId.isNullOrBlank() && mId != "null") "m|id|$mId|$dateSec" else "m|" + dateSec + "|" + (msgBox ?: "") + "|" + (sub ?: "")

    fun call(number: String?, dateMs: Long, duration: String?, type: String?) = "c|" + phone(number) + "|" + dateMs / 1000 + "|" + (duration ?: "0") + "|" + (type ?: "")

    /** Contact: display name + sorted phone numbers (or e-mails when there is no phone). */
    fun contact(name: String, phones: List<String>, emails: List<String>): String {
        val p = phones.map { phone(it) }.filter { it.isNotEmpty() }.sorted()
        val id = if (p.isNotEmpty()) p.joinToString(",") else emails.map { it.trim().lowercase() }.sorted().joinToString(",")
        return "p|" + name.trim().lowercase() + "|" + id
    }

    fun contact(c: VContact) = contact(c.displayName, c.phones.map { it.value }, c.emails.map { it.value })

    /** Event: title + start (+ end), so the same meeting is not inserted twice. */
    fun event(title: String, start: Long, end: Long?, allDay: Boolean) = "e|" + title.trim() + "|" + start / 1000 + "|" + ((end ?: 0) / 1000) + "|" + allDay
}
