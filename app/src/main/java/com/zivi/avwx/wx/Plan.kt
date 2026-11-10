package com.zivi.avwx.wx

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/*
 * Flight-planning logic: what a TAF says for a time window, minima checks,
 * change detection for alerts, and text for sharing. Pure Kotlin.
 */

data class Minima(val ceilingFt: Int, val visM: Int)

fun belowMinima(c: Conditions, m: Minima): Boolean {
    val ceil = c.ceilingFt
    val vis = c.visM
    return (ceil != null && ceil < m.ceilingFt) || (!c.cavok && vis != null && vis < m.visM)
}

private fun rank(c: FlightCat): Int = when (c) {
    FlightCat.LIFR -> 0
    FlightCat.IFR -> 1
    FlightCat.MVFR -> 2
    FlightCat.VFR -> 3
    FlightCat.UNKNOWN -> 4
}

fun worstOf(cats: List<FlightCat>): FlightCat = cats.minByOrNull { rank(it) } ?: FlightCat.UNKNOWN

fun isWorse(now: FlightCat, before: FlightCat): Boolean =
    now != FlightCat.UNKNOWN && before != FlightCat.UNKNOWN && rank(now) < rank(before)

class TafWindow(
    val covered: Boolean,              // the TAF is valid for the whole window
    val prevailing: TafGroup?,         // what is forecast as the main condition
    val changes: List<TafGroup>,       // TEMPO / PROB / BECMG / FM groups touching the window
    val worst: FlightCat,
) {
    val all: List<TafGroup> get() = listOfNotNull(prevailing) + changes
    val thunder: Boolean get() = all.any { it.effective.hasTs }
    val cb: Boolean get() = all.any { it.effective.hasCb }
}

/** What the TAF forecasts between [from] and [to]. */
fun tafWindow(taf: Taf, from: Instant, to: Instant): TafWindow {
    val vf = taf.validFrom
    val vt = taf.validTo
    val covered = vf != null && vt != null && !from.isBefore(vf) && !to.isAfter(vt)
    var prevailing: TafGroup? = null
    val changes = ArrayList<TafGroup>()
    for (g in taf.groups) {
        val gf = g.from
        val gt = g.to
        when (g.kind) {
            TafKind.BASE -> prevailing = g
            TafKind.FM -> {
                if (gf != null && !gf.isAfter(from)) prevailing = g
                else if (gf != null && gf.isBefore(to)) changes += g
            }
            TafKind.BECMG -> {
                if (gt != null && !gt.isAfter(from)) prevailing = g          // change complete before the window
                else if (gf != null && gf.isBefore(to)) changes += g         // changing during the window
            }
            else -> if (gf != null && gt != null && gf.isBefore(to) && gt.isAfter(from)) changes += g
        }
    }
    val worst = worstOf((listOfNotNull(prevailing) + changes).map { it.category })
    return TafWindow(covered, prevailing, changes, worst)
}

/** "0730" or "07:30" -> the next time that clock time occurs in UTC (allowing up to 1 hour in the past). */
fun parseUtcTime(text: String, now: Instant): Instant? {
    val digits = text.filter { it.isDigit() }
    if (digits.length != 4) return null
    val h = digits.substring(0, 2).toInt()
    val m = digits.substring(2, 4).toInt()
    if (h > 23 || m > 59) return null
    val base = ZonedDateTime.ofInstant(now, ZoneOffset.UTC).withHour(h).withMinute(m).withSecond(0).withNano(0)
    var t = base.toInstant()
    if (t.isBefore(now.minusSeconds(3600))) t = base.plusDays(1).toInstant()
    return t
}

// ---------------------------------------------------------------- alerts

/** Things worth a notification when a station's reports change. */
fun wxEvents(icao: String, oldM: Metar?, newM: Metar?, oldT: Taf?, newT: Taf?): List<String> {
    val out = ArrayList<String>()
    if (newM != null && newM.raw != oldM?.raw) {
        val was = oldM?.category ?: FlightCat.UNKNOWN
        val isNow = newM.category
        if (isWorse(isNow, was)) out += "$icao now ${isNow.name} (was ${was.name})"
        if (newM.cond.hasTs && oldM?.cond?.hasTs != true) out += "$icao reporting thunderstorm"
        else if (newM.speci && out.isEmpty()) out += "$icao SPECI: " + shortWind(newM.cond.wind) + " " + shortVis(newM.cond) + " " + shortCloud(newM.cond)
    }
    if (newT != null && oldT != null && newT.raw != oldT.raw) {
        if (newT.amended) out += "$icao TAF amended"
        val oldTs = oldT.groups.any { it.effective.hasTs }
        val newTs = newT.groups.any { it.effective.hasTs }
        if (newTs && !oldTs) out += "$icao TAF now forecasts thunderstorms"
    }
    return out
}

// ---------------------------------------------------------------- sharing

fun shareText(icao: String, name: String?, metar: Metar?, taf: Taf?): String {
    val sb = StringBuilder()
    sb.append(icao)
    if (!name.isNullOrBlank() && name != icao) sb.append(" – ").append(name)
    if (metar != null) sb.append(" [").append(metar.category.name).append("]")
    sb.append('\n')
    sb.append(metar?.raw ?: "No METAR").append('\n')
    if (taf != null) sb.append('\n').append(taf.raw).append('\n')
    return sb.toString().trim()
}
