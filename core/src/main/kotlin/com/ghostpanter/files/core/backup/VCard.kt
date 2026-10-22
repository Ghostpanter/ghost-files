package com.ghostpanter.files.core.backup

import java.io.Reader
import java.io.Writer
import java.util.Base64

/** One contact as stored in a .vcf (vCard 3.0 out; 2.1 / 3.0 / 4.0 in). */
data class VContact(
    var formatted: String = "",
    var family: String = "", var given: String = "", var middle: String = "", var prefix: String = "", var suffix: String = "",
    var nickname: String = "",
    val phones: MutableList<Typed> = ArrayList(),
    val emails: MutableList<Typed> = ArrayList(),
    val addresses: MutableList<VAddress> = ArrayList(),
    var org: String = "", var department: String = "", var title: String = "",
    var note: String = "",
    var birthday: String = "",
    val urls: MutableList<String> = ArrayList(),
    var photo: ByteArray? = null,
) {
    data class Typed(val type: String, val value: String)
    data class VAddress(val type: String, val pobox: String = "", val ext: String = "", val street: String = "", val city: String = "", val region: String = "", val postcode: String = "", val country: String = "")

    val displayName: String get() = formatted.ifBlank { listOf(prefix, family, given, middle, suffix).filter { it.isNotBlank() }.joinToString(" ") }.ifBlank { org }.ifBlank { phones.firstOrNull()?.value ?: emails.firstOrNull()?.value ?: "" }

    override fun equals(other: Any?) = other is VContact && other.copy(photo = null).toString() == copy(photo = null).toString() && (photo?.contentEquals(other.photo ?: ByteArray(0)) ?: (other.photo == null))
    override fun hashCode() = copy(photo = null).toString().hashCode()
}

object VCard {
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace(",", "\\,").replace(";", "\\;")

    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { val n = s[i + 1]; sb.append(when (n) { 'n', 'N' -> '\n'; else -> n }); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Splits on unescaped [sep]. */
    private fun split(s: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(c).append(s[i + 1]); i += 2; continue }
            if (c == sep) { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
            i++
        }
        out.add(sb.toString()); return out
    }

    /** Folds at 75 octets (RFC 6350 3.2), never inside a UTF-8 sequence. */
    private fun line(w: Writer, s: String) {
        var bytes = 0; var first = true
        val sb = StringBuilder()
        for (ch in s) {
            val n = ch.toString().toByteArray().size
            if (bytes + n > (if (first) 75 else 74)) { w.write(sb.toString()); w.write("\r\n "); sb.setLength(0); bytes = 0; first = false }
            sb.append(ch); bytes += n
        }
        w.write(sb.toString()); w.write("\r\n")
    }

    fun write(w: Writer, c: VContact) {
        line(w, "BEGIN:VCARD"); line(w, "VERSION:3.0")
        line(w, "FN:" + esc(c.displayName))
        line(w, "N:" + listOf(c.family, c.given, c.middle, c.prefix, c.suffix).joinToString(";") { esc(it) })
        if (c.nickname.isNotEmpty()) line(w, "NICKNAME:" + esc(c.nickname))
        for (p in c.phones) line(w, "TEL" + (if (p.type.isNotEmpty()) ";TYPE=${p.type.uppercase()}" else "") + ":" + esc(p.value))
        for (e in c.emails) line(w, "EMAIL" + (if (e.type.isNotEmpty()) ";TYPE=${e.type.uppercase()}" else "") + ":" + esc(e.value))
        for (a in c.addresses) line(w, "ADR" + (if (a.type.isNotEmpty()) ";TYPE=${a.type.uppercase()}" else "") + ":" + listOf(a.pobox, a.ext, a.street, a.city, a.region, a.postcode, a.country).joinToString(";") { esc(it) })
        if (c.org.isNotEmpty() || c.department.isNotEmpty()) line(w, "ORG:" + esc(c.org) + (if (c.department.isNotEmpty()) ";" + esc(c.department) else ""))
        if (c.title.isNotEmpty()) line(w, "TITLE:" + esc(c.title))
        if (c.birthday.isNotEmpty()) line(w, "BDAY:" + c.birthday)
        for (u in c.urls) line(w, "URL:" + esc(u))
        if (c.note.isNotEmpty()) line(w, "NOTE:" + esc(c.note))
        c.photo?.let { line(w, "PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.getEncoder().encodeToString(it)) }
        line(w, "END:VCARD")
    }

    /** Unfolded logical lines (RFC folding + vCard 2.1 quoted-printable soft breaks). */
    internal fun lines(r: Reader): Sequence<String> = sequence {
        val br = r.buffered()
        var cur: String? = null
        while (true) {
            val raw = br.readLine() ?: break
            if (cur != null && (raw.startsWith(" ") || raw.startsWith("\t"))) { cur += raw.substring(1); continue }
            if (cur != null && cur.endsWith("=") && cur.contains("QUOTED-PRINTABLE", ignoreCase = true)) { cur = cur.dropLast(1) + raw; continue }
            cur?.let { yield(it) }
            cur = raw
        }
        cur?.let { yield(it) }
    }

    private fun qp(s: String, charset: String): String {
        val out = java.io.ByteArrayOutputStream(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { out.write(h); i += 3; continue }
            }
            out.write(c.toString().toByteArray(charset(charset))); i++
        }
        return out.toString(charset)
    }

    fun read(r: Reader): List<VContact> {
        val out = ArrayList<VContact>()
        var c: VContact? = null
        for (l in lines(r)) {
            if (l.isBlank()) continue
            val colon = run {
                // first ':' not inside a quoted parameter value
                var q = false; var idx = -1
                for ((i, ch) in l.withIndex()) { if (ch == '"') q = !q; if (ch == ':' && !q) { idx = i; break } }
                idx
            }
            if (colon < 0) continue
            val head = l.substring(0, colon); var value = l.substring(colon + 1)
            val hp = split(head, ';')
            val name = hp[0].substringAfter('.').uppercase()   // "item1.TEL" -> TEL
            val params = hp.drop(1)
            val types = ArrayList<String>(); var enc = ""; var cs = "UTF-8"
            for (p in params) {
                val k = p.substringBefore('=', "").uppercase(); val v = p.substringAfter('=', p)
                when {
                    k == "TYPE" -> types.addAll(v.trim('"').split(',').map { it.lowercase() })
                    k == "ENCODING" -> enc = v.uppercase()
                    k == "CHARSET" -> cs = v
                    k.isEmpty() -> if (p.uppercase() == "QUOTED-PRINTABLE" || p.uppercase() == "BASE64" || p.uppercase() == "B") enc = p.uppercase() else types.add(p.lowercase())
                }
            }
            if (enc == "QUOTED-PRINTABLE") value = qp(value, cs)
            val type = types.firstOrNull { it !in setOf("pref", "voice", "internet", "x400") } ?: ""
            when (name) {
                "BEGIN" -> if (value.equals("VCARD", true)) c = VContact()
                "END" -> if (value.equals("VCARD", true)) { c?.let { out.add(it) }; c = null }
            }
            val x = c ?: continue
            when (name) {
                "FN" -> x.formatted = unesc(value)
                "N" -> { val f = split(value, ';').map { unesc(it) }; x.family = f.getOrElse(0) { "" }; x.given = f.getOrElse(1) { "" }; x.middle = f.getOrElse(2) { "" }; x.prefix = f.getOrElse(3) { "" }; x.suffix = f.getOrElse(4) { "" } }
                "NICKNAME" -> x.nickname = unesc(value)
                "TEL" -> x.phones.add(VContact.Typed(type, unesc(value.removePrefix("tel:"))))
                "EMAIL" -> x.emails.add(VContact.Typed(type, unesc(value)))
                "ADR" -> { val f = split(value, ';').map { unesc(it) }; x.addresses.add(VContact.VAddress(type, f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" }, f.getOrElse(4) { "" }, f.getOrElse(5) { "" }, f.getOrElse(6) { "" })) }
                "ORG" -> { val f = split(value, ';').map { unesc(it) }; x.org = f.getOrElse(0) { "" }; x.department = f.getOrElse(1) { "" } }
                "TITLE" -> x.title = unesc(value)
                "NOTE" -> x.note = unesc(value)
                "BDAY" -> x.birthday = value
                "URL" -> x.urls.add(unesc(value))
                "PHOTO" -> if (enc == "B" || enc == "BASE64" || value.startsWith("data:")) x.photo = runCatching { Base64.getMimeDecoder().decode(value.substringAfter("base64,")) }.getOrNull()
            }
        }
        return out
    }
}
