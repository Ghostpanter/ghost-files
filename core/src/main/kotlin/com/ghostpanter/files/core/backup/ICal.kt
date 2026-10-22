package com.ghostpanter.files.core.backup

import java.io.Reader
import java.io.Writer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One calendar event as written to / read from an .ics (RFC 5545 subset). */
data class VEvent(
    val uid: String,
    val title: String,
    val start: Long,
    /** Exclusive end; for all-day events midnight UTC of the day after. */
    val end: Long? = null,
    val allDay: Boolean = false,
    val timezone: String? = null,
    val description: String = "",
    val location: String = "",
    val rrule: String = "",
    /** RFC 5545 duration (recurring events carry this instead of [end]). */
    val duration: String = "",
    /** Minutes before start. */
    val reminders: List<Int> = emptyList(),
)

object ICal {
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace(",", "\\,").replace(";", "\\;")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) { val c = s[i]; if (c == '\\' && i + 1 < s.length) { val n = s[i + 1]; sb.append(if (n == 'n' || n == 'N') '\n' else n); i += 2 } else { sb.append(c); i++ } }
        return sb.toString()
    }

    private fun line(w: Writer, s: String) {
        var bytes = 0; var first = true; val sb = StringBuilder()
        for (ch in s) {
            val n = ch.toString().toByteArray().size
            if (bytes + n > (if (first) 75 else 74)) { w.write(sb.toString()); w.write("\r\n "); sb.setLength(0); bytes = 0; first = false }
            sb.append(ch); bytes += n
        }
        w.write(sb.toString()); w.write("\r\n")
    }

    fun begin(w: Writer) {
        line(w, "BEGIN:VCALENDAR"); line(w, "VERSION:2.0"); line(w, "PRODID:-//Ghostpanter//双栏文件管理器//ZH"); line(w, "CALSCALE:GREGORIAN")
    }
    fun end(w: Writer) { line(w, "END:VCALENDAR"); w.flush() }

    fun utc(ms: Long): String = UTC.format(Instant.ofEpochMilli(ms))

    fun write(w: Writer, e: VEvent, stamp: Long) {
        line(w, "BEGIN:VEVENT")
        line(w, "UID:" + e.uid)
        line(w, "DTSTAMP:" + utc(stamp))
        if (e.allDay) {
            line(w, "DTSTART;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC)))
            e.end?.let { line(w, "DTEND;VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))) }
        } else {
            line(w, "DTSTART:" + utc(e.start))
            if (e.duration.isEmpty()) e.end?.let { line(w, "DTEND:" + utc(it)) }
        }
        if (e.duration.isNotEmpty()) line(w, "DURATION:" + e.duration)
        if (e.timezone != null && !e.allDay) line(w, "X-GHOST-TZID:" + e.timezone)
        line(w, "SUMMARY:" + esc(e.title))
        if (e.description.isNotEmpty()) line(w, "DESCRIPTION:" + esc(e.description))
        if (e.location.isNotEmpty()) line(w, "LOCATION:" + esc(e.location))
        if (e.rrule.isNotEmpty()) line(w, "RRULE:" + e.rrule)
        for (m in e.reminders) {
            line(w, "BEGIN:VALARM"); line(w, "ACTION:DISPLAY"); line(w, "DESCRIPTION:" + esc(e.title)); line(w, "TRIGGER:-PT${m}M"); line(w, "END:VALARM")
        }
        line(w, "END:VEVENT")
    }

    /** "PT1H30M", "P1D", "-PT15M" → milliseconds (negative allowed). */
    fun durationMs(s: String): Long? = runCatching {
        val neg = s.startsWith("-"); val body = s.trimStart('-', '+')
        val weeks = Regex("^P(\\d+)W$").find(body)?.groupValues?.get(1)?.toLong()
        val d = if (weeks != null) Duration.ofDays(weeks * 7) else Duration.parse(body)
        (if (neg) -1 else 1) * d.toMillis()
    }.getOrNull()

    private fun parseTime(value: String, params: Map<String, String>, defaultZone: ZoneId): Pair<Long, Boolean>? = runCatching {
        if (params["VALUE"] == "DATE" || (value.length == 8 && value.all { it.isDigit() })) {
            LocalDate.parse(value, DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        } else if (value.endsWith("Z")) {
            Instant.from(UTC.parse(value)).toEpochMilli() to false
        } else {
            val zone = params["TZID"]?.let { runCatching { ZoneId.of(it.trim('"')) }.getOrNull() } ?: defaultZone
            LocalDateTime.parse(value, LOCAL).atZone(zone).toInstant().toEpochMilli() to false
        }
    }.getOrNull()

    fun read(r: Reader, defaultZone: ZoneId = ZoneId.systemDefault()): List<VEvent> {
        val out = ArrayList<VEvent>()
        var inEvent = false; var inAlarm = false
        var f = HashMap<String, Pair<String, Map<String, String>>>()
        val alarms = ArrayList<Int>()
        for (l in VCard.lines(r)) {
            val colon = l.indexOf(':'); if (colon < 0) continue
            val head = l.substring(0, colon); val value = l.substring(colon + 1)
            val hp = head.split(';')
            val name = hp[0].uppercase()
            val params = hp.drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
            when {
                name == "BEGIN" && value == "VEVENT" -> { inEvent = true; f = HashMap(); alarms.clear() }
                name == "BEGIN" && value == "VALARM" -> inAlarm = true
                name == "END" && value == "VALARM" -> inAlarm = false
                name == "END" && value == "VEVENT" -> {
                    inEvent = false
                    val (sv, sp) = f["DTSTART"] ?: continue
                    val (start, allDay) = parseTime(sv, sp, defaultZone) ?: continue
                    val end = f["DTEND"]?.let { (v, p) -> parseTime(v, p, defaultZone)?.first }
                    val dur = f["DURATION"]?.first ?: ""
                    out.add(VEvent(
                        uid = f["UID"]?.first ?: "", title = unesc(f["SUMMARY"]?.first ?: ""), start = start,
                        end = end ?: if (dur.isEmpty() && allDay) start + 86_400_000L else if (dur.isNotEmpty() && f["RRULE"] == null) durationMs(dur)?.let { start + it } else null,
                        allDay = allDay, timezone = f["X-GHOST-TZID"]?.first ?: sp["TZID"]?.trim('"'),
                        description = unesc(f["DESCRIPTION"]?.first ?: ""), location = unesc(f["LOCATION"]?.first ?: ""),
                        rrule = f["RRULE"]?.first ?: "", duration = if (f["RRULE"] != null) dur.ifEmpty { end?.let { e -> "PT${(e - start) / 60_000}M" } ?: "" } else "",
                        reminders = alarms.toList(),
                    ))
                }
                inAlarm && name == "TRIGGER" -> durationMs(value)?.let { if (it <= 0) alarms.add((-it / 60_000).toInt()) }
                inEvent && !inAlarm -> f[name] = value to params
            }
        }
        return out
    }
}
