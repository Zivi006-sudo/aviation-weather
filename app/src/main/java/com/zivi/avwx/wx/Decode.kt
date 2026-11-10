package com.zivi.avwx.wx

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Pure Kotlin METAR / TAF decoding. No Android imports, so it can be tested on a plain JVM.
 */

enum class FlightCat { VFR, MVFR, IFR, LIFR, UNKNOWN }

data class CloudLayer(val cover: String, val baseFt: Int?, val type: String?)

data class Wind(
    val dir: Int?,            // degrees true, null when variable
    val variable: Boolean,
    val speedKt: Int,
    val gustKt: Int?,
    val varFrom: Int? = null,
    val varTo: Int? = null,
)

data class Conditions(
    val wind: Wind? = null,
    val visM: Int? = null,     // metres, 9999 = 10 km or more
    val cavok: Boolean = false,
    val wx: List<String> = emptyList(),
    val nsw: Boolean = false,
    val clouds: List<CloudLayer> = emptyList(),
    val cloudsGiven: Boolean = false,
) {
    /** Lowest BKN / OVC / VV base in feet, or null when there is no ceiling. */
    val ceilingFt: Int?
        get() = clouds.filter { it.cover == "BKN" || it.cover == "OVC" || it.cover == "VV" }
            .mapNotNull { it.baseFt }.minOrNull()

    val hasCb: Boolean get() = clouds.any { it.type == "CB" || it.type == "TCU" }
    val hasTs: Boolean get() = wx.any { it.contains("TS") }
}

data class Metar(
    val raw: String,
    val station: String,
    val obsTime: Instant?,
    val cond: Conditions,
    val tempC: Int?,
    val dewC: Int?,
    val qnhHpa: Int?,
    val trend: String?,
    val speci: Boolean,
    val auto: Boolean,
) {
    val category: FlightCat get() = flightCategory(cond)
}

enum class TafKind { BASE, FM, BECMG, TEMPO, PROB, PROB_TEMPO }

data class TafGroup(
    val kind: TafKind,
    val prob: Int?,
    val from: Instant?,
    val to: Instant?,
    val raw: String,
    val change: Conditions,      // only what the group itself states
    val effective: Conditions,   // merged with the prevailing forecast
) {
    val category: FlightCat get() = flightCategory(effective)
    val label: String
        get() = when (kind) {
            TafKind.BASE -> "Initial"
            TafKind.FM -> "From"
            TafKind.BECMG -> "Becoming"
            TafKind.TEMPO -> "Temporarily"
            TafKind.PROB -> "Probability $prob%"
            TafKind.PROB_TEMPO -> "Prob $prob% temporarily"
        }
}

data class Taf(
    val raw: String,
    val station: String,
    val issued: Instant?,
    val validFrom: Instant?,
    val validTo: Instant?,
    val amended: Boolean,
    val groups: List<TafGroup>,
)

// ---------------------------------------------------------------- categories

/**
 * US-style flight categories, applied to metric visibility:
 * LIFR < 500 ft or < 1600 m, IFR < 1000 ft or < 5000 m, MVFR <= 3000 ft or <= 8000 m.
 */
fun flightCategory(c: Conditions): FlightCat {
    if (c.cavok) return FlightCat.VFR
    val vis = c.visM
    val ceil = c.ceilingFt
    if (vis == null && ceil == null && !c.cloudsGiven) return FlightCat.UNKNOWN
    val v = vis ?: 9999
    val h = ceil ?: 99999
    return when {
        v < 1600 || h < 500 -> FlightCat.LIFR
        v < 5000 || h < 1000 -> FlightCat.IFR
        v <= 8000 || h <= 3000 -> FlightCat.MVFR
        else -> FlightCat.VFR
    }
}

// ---------------------------------------------------------------- tokens

private val WIND_RE = Regex("^(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?(KT|MPS)$")
private val WIND_VAR_RE = Regex("^(\\d{3})V(\\d{3})$")
private val VIS_RE = Regex("^(\\d{4})(?:NDV)?$")
private val VIS_SM_RE = Regex("^[PM]?(\\d{1,2})?(?:(\\d)/(\\d{1,2}))?SM$")
private val CLOUD_RE = Regex("^(FEW|SCT|BKN|OVC)(\\d{3}|///)(CB|TCU|///)?$")
private val VV_RE = Regex("^VV(\\d{3}|///)$")
private val TEMP_RE = Regex("^(M?\\d{2})/(M?\\d{2}|//)?$")
private val QNH_RE = Regex("^Q(\\d{4})$")
private val ALT_RE = Regex("^A(\\d{4})$")
private val TIME_RE = Regex("^(\\d{2})(\\d{2})(\\d{2})Z$")
private val VALID_RE = Regex("^(\\d{2})(\\d{2})/(\\d{2})(\\d{2})$")
private val FM_RE = Regex("^FM(\\d{2})(\\d{2})(\\d{2})$")
private val PROB_RE = Regex("^PROB(\\d{2})$")
private val WX_RE = Regex(
    "^(\\+|-|VC)?(MI|PR|BC|DR|BL|SH|TS|FZ)*" +
        "(DZ|RA|SN|SG|IC|PL|GR|GS|UP|BR|FG|FU|VA|DU|SA|HZ|PO|SQ|FC|SS|DS)*$"
)
private val NO_CLOUD = setOf("NSC", "SKC", "CLR", "NCD")

private class CondBuilder {
    var wind: Wind? = null
    var visM: Int? = null
    var cavok = false
    val wx = ArrayList<String>()
    var nsw = false
    val clouds = ArrayList<CloudLayer>()
    var cloudsGiven = false

    /** Returns true when the token was a weather element. */
    fun take(t: String): Boolean {
        WIND_RE.matchEntire(t)?.let { m ->
            val mps = m.groupValues[4] == "MPS"
            fun kt(s: String) = if (mps) (s.toInt() * 1.94384).roundToInt() else s.toInt()
            val vrb = m.groupValues[1] == "VRB"
            wind = Wind(
                dir = if (vrb) null else m.groupValues[1].toInt(),
                variable = vrb,
                speedKt = kt(m.groupValues[2]),
                gustKt = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { kt(it) },
            )
            return true
        }
        WIND_VAR_RE.matchEntire(t)?.let { m ->
            wind = wind?.copy(varFrom = m.groupValues[1].toInt(), varTo = m.groupValues[2].toInt())
            return true
        }
        if (t == "CAVOK") { cavok = true; visM = 9999; cloudsGiven = true; return true }
        if (t == "NSW") { nsw = true; return true }
        VIS_RE.matchEntire(t)?.let { m ->
            if (visM == null) visM = m.groupValues[1].toInt()
            return true
        }
        if (t.endsWith("SM")) {
            VIS_SM_RE.matchEntire(t)?.let { m ->
                val whole = m.groupValues[1].toIntOrNull() ?: 0
                val num = m.groupValues[2].toIntOrNull()
                val den = m.groupValues[3].toIntOrNull()
                val sm = whole + if (num != null && den != null && den != 0) num.toDouble() / den else 0.0
                visM = if (sm >= 6.0) 9999 else (sm * 1609.34).roundToInt()
                return true
            }
        }
        CLOUD_RE.matchEntire(t)?.let { m ->
            clouds += CloudLayer(
                cover = m.groupValues[1],
                baseFt = m.groupValues[2].toIntOrNull()?.times(100),
                type = m.groupValues[3].takeIf { it == "CB" || it == "TCU" },
            )
            cloudsGiven = true
            return true
        }
        VV_RE.matchEntire(t)?.let { m ->
            clouds += CloudLayer("VV", m.groupValues[1].toIntOrNull()?.times(100), null)
            cloudsGiven = true
            return true
        }
        if (t in NO_CLOUD) { cloudsGiven = true; return true }
        if (t.length >= 2 && t.any { it.isLetter() } && WX_RE.matches(t)) { wx += t; return true }
        return false
    }

    fun build() = Conditions(wind, visM, cavok, wx.toList(), nsw, clouds.toList(), cloudsGiven)
}

private fun tokens(raw: String): List<String> =
    raw.replace('\n', ' ').replace('\r', ' ').replace("=", " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

// ---------------------------------------------------------------- time

/** Resolve a day-of-month + hour (+ minute) to the instant closest to [ref]. Hour 24 is allowed. */
fun resolveDayTime(day: Int, hour: Int, minute: Int, ref: Instant): Instant? {
    if (day !in 1..31 || hour !in 0..24 || minute !in 0..59) return null
    val base = ZonedDateTime.ofInstant(ref, ZoneOffset.UTC)
    var best: Instant? = null
    for (off in -1L..1L) {
        val month = base.plusMonths(off).withDayOfMonth(1)
        if (day > month.toLocalDate().lengthOfMonth()) continue
        val candidate = month.withDayOfMonth(day).withHour(0).withMinute(0).withSecond(0).withNano(0)
            .plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant()
        if (best == null || abs(candidate.epochSecond - ref.epochSecond) < abs(best!!.epochSecond - ref.epochSecond)) {
            best = candidate
        }
    }
    return best
}

// ---------------------------------------------------------------- METAR

fun parseMetar(rawIn: String, ref: Instant = Instant.now()): Metar? {
    val raw = rawIn.trim()
    val tk = tokens(raw)
    if (tk.isEmpty()) return null
    var i = 0
    var speci = false
    var auto = false
    while (i < tk.size && (tk[i] == "METAR" || tk[i] == "SPECI" || tk[i] == "COR")) {
        if (tk[i] == "SPECI") speci = true
        i++
    }
    if (i >= tk.size) return null
    val station = tk[i++]
    var obs: Instant? = null
    val b = CondBuilder()
    var temp: Int? = null
    var dew: Int? = null
    var qnh: Int? = null
    var trend: String? = null
    while (i < tk.size) {
        val t = tk[i]
        if (t == "RMK") break
        if (t == "NOSIG" || t == "BECMG" || t == "TEMPO") {
            val end = tk.indexOf("RMK").let { if (it < 0) tk.size else it }
            trend = tk.subList(i, end).joinToString(" ")
            break
        }
        when {
            t == "AUTO" -> auto = true
            t == "COR" || t == "NIL" -> {}
            obs == null && TIME_RE.matches(t) -> {
                val m = TIME_RE.matchEntire(t)!!
                obs = resolveDayTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), ref)
            }
            TEMP_RE.matches(t) -> {
                val m = TEMP_RE.matchEntire(t)!!
                temp = signed(m.groupValues[1])
                dew = signed(m.groupValues[2])
            }
            QNH_RE.matches(t) -> qnh = QNH_RE.matchEntire(t)!!.groupValues[1].toInt()
            ALT_RE.matches(t) -> qnh = (ALT_RE.matchEntire(t)!!.groupValues[1].toInt() / 100.0 * 33.8639).roundToInt()
            else -> b.take(t)
        }
        i++
    }
    return Metar(raw, station, obs, b.build(), temp, dew, qnh, trend, speci, auto)
}

private fun signed(s: String): Int? {
    if (s.isEmpty() || s == "//") return null
    return if (s.startsWith("M")) -(s.drop(1).toIntOrNull() ?: return null) else s.toIntOrNull()
}

// ---------------------------------------------------------------- TAF

private fun merge(prev: Conditions, ch: Conditions): Conditions = Conditions(
    wind = ch.wind ?: prev.wind,
    visM = ch.visM ?: prev.visM,
    cavok = if (ch.cavok) true else if (ch.visM != null || ch.cloudsGiven || ch.wx.isNotEmpty()) false else prev.cavok,
    wx = when {
        ch.wx.isNotEmpty() -> ch.wx
        ch.nsw || ch.cavok -> emptyList()
        else -> prev.wx
    },
    nsw = ch.nsw,
    clouds = if (ch.cloudsGiven) ch.clouds else prev.clouds,
    cloudsGiven = ch.cloudsGiven || prev.cloudsGiven,
)

fun parseTaf(rawIn: String, ref: Instant = Instant.now()): Taf? {
    val raw = rawIn.trim()
    val tk = tokens(raw)
    if (tk.isEmpty()) return null
    var i = 0
    var amended = false
    while (i < tk.size && (tk[i] == "TAF" || tk[i] == "AMD" || tk[i] == "COR")) {
        if (tk[i] == "AMD" || tk[i] == "COR") amended = true
        i++
    }
    if (i >= tk.size) return null
    val station = tk[i++]
    var issued: Instant? = null
    if (i < tk.size) TIME_RE.matchEntire(tk[i])?.let { m ->
        issued = resolveDayTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), ref)
        i++
    }
    val anchor = issued ?: ref
    var validFrom: Instant? = null
    var validTo: Instant? = null
    if (i < tk.size) VALID_RE.matchEntire(tk[i])?.let { m ->
        validFrom = resolveDayTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 0, anchor)
        validTo = resolveDayTime(m.groupValues[3].toInt(), m.groupValues[4].toInt(), 0, validFrom ?: anchor)
        i++
    }

    val groups = ArrayList<TafGroup>()
    var prevailing = Conditions()

    var kind = TafKind.BASE
    var prob: Int? = null
    var from: Instant? = validFrom
    var to: Instant? = validTo
    var b = CondBuilder()
    val rawParts = ArrayList<String>()

    fun flush() {
        if (kind != TafKind.BASE && rawParts.isEmpty()) return
        val ch = b.build()
        val eff = when (kind) {
            TafKind.BASE, TafKind.FM -> ch
            else -> merge(prevailing, ch)
        }
        if (kind == TafKind.BASE || kind == TafKind.FM || kind == TafKind.BECMG) prevailing = eff
        groups += TafGroup(kind, prob, from, to, rawParts.joinToString(" "), ch, eff)
    }

    fun start(k: TafKind, p: Int?, f: Instant?, t: Instant?, head: String) {
        flush()
        kind = k; prob = p; from = f; to = t
        b = CondBuilder()
        rawParts.clear()
        rawParts += head
    }

    while (i < tk.size) {
        val t = tk[i]
        if (t == "RMK") break
        val fm = FM_RE.matchEntire(t)
        val pr = PROB_RE.matchEntire(t)
        when {
            fm != null -> {
                val f = resolveDayTime(fm.groupValues[1].toInt(), fm.groupValues[2].toInt(), fm.groupValues[3].toInt(), validFrom ?: anchor)
                start(TafKind.FM, null, f, validTo, t)
            }
            t == "BECMG" || t == "TEMPO" || pr != null -> {
                var k = if (t == "BECMG") TafKind.BECMG else if (t == "TEMPO") TafKind.TEMPO else TafKind.PROB
                val p = pr?.groupValues?.get(1)?.toIntOrNull()
                var head = t
                if (pr != null && i + 1 < tk.size && tk[i + 1] == "TEMPO") {
                    k = TafKind.PROB_TEMPO; head += " TEMPO"; i++
                }
                var f: Instant? = null
                var e: Instant? = null
                if (i + 1 < tk.size) VALID_RE.matchEntire(tk[i + 1])?.let { m ->
                    f = resolveDayTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 0, validFrom ?: anchor)
                    e = resolveDayTime(m.groupValues[3].toInt(), m.groupValues[4].toInt(), 0, f ?: anchor)
                    head += " " + tk[i + 1]
                    i++
                }
                start(k, p, f, e, head)
            }
            else -> {
                b.take(t)
                rawParts += t
            }
        }
        i++
    }
    flush()
    // FM groups end when the next FM (or the TAF) ends.
    return Taf(raw, station, issued, validFrom, validTo, amended, groups)
}

// ---------------------------------------------------------------- wind components

data class RunwayWind(val runway: String, val headwindKt: Int, val crosswindKt: Int, val fromRight: Boolean)

/** "03/21" or "18" -> list of runway idents with headings (designator x 10). */
fun parseRunways(text: String): List<Pair<String, Int>> =
    text.split('/', ',', ' ').mapNotNull { part ->
        val p = part.trim().uppercase()
        val num = p.takeWhile { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
        if (num !in 1..36) return@mapNotNull null
        p to num * 10
    }

/** Components for one runway end. Positive headwind = headwind, negative = tailwind. Uses gust when [useGust]. */
fun runwayWind(ident: String, headingDeg: Int, wind: Wind, useGust: Boolean = false): RunwayWind? {
    val dir = wind.dir ?: return null
    val spd = if (useGust) (wind.gustKt ?: wind.speedKt) else wind.speedKt
    val diff = Math.toRadians((dir - headingDeg).toDouble())
    val head = (spd * cos(diff)).roundToInt()
    val crossRaw = spd * sin(diff)
    return RunwayWind(ident, head, abs(crossRaw).roundToInt(), crossRaw > 0)
}

// ---------------------------------------------------------------- plain-language text

private val WX_DESC = mapOf(
    "MI" to "shallow", "PR" to "partial", "BC" to "patches of", "DR" to "drifting", "BL" to "blowing",
    "SH" to "showers of", "TS" to "thunderstorm", "FZ" to "freezing",
)
private val WX_PHEN = mapOf(
    "DZ" to "drizzle", "RA" to "rain", "SN" to "snow", "SG" to "snow grains", "IC" to "ice crystals",
    "PL" to "ice pellets", "GR" to "hail", "GS" to "small hail", "UP" to "unknown precipitation",
    "BR" to "mist", "FG" to "fog", "FU" to "smoke", "VA" to "volcanic ash", "DU" to "dust", "SA" to "sand",
    "HZ" to "haze", "PO" to "dust whirls", "SQ" to "squalls", "FC" to "funnel cloud", "SS" to "sandstorm",
    "DS" to "duststorm",
)

fun describeWx(code: String): String {
    var s = code
    val parts = ArrayList<String>()
    when {
        s.startsWith("+") -> { parts += "heavy"; s = s.drop(1) }
        s.startsWith("-") -> { parts += "light"; s = s.drop(1) }
        s.startsWith("VC") -> { s = s.drop(2) }
    }
    val vicinity = code.startsWith("VC")
    val descs = ArrayList<String>()
    val phens = ArrayList<String>()
    var k = 0
    while (k + 2 <= s.length) {
        val two = s.substring(k, k + 2)
        WX_DESC[two]?.let { descs += it } ?: WX_PHEN[two]?.let { phens += it }
        k += 2
    }
    val ts = descs.remove("thunderstorm")
    if (phens.isEmpty() && descs.remove("showers of")) phens += "showers"
    val body = (descs + phens).joinToString(" ")
    val text = when {
        ts && body.isEmpty() -> "thunderstorm"
        ts -> "thunderstorm with $body"
        else -> body
    }
    val out = (parts + text).joinToString(" ").trim() + if (vicinity) " in the vicinity" else ""
    return out.replaceFirstChar { it.uppercase() }.ifBlank { code }
}

fun describeWind(w: Wind?): String {
    if (w == null) return "Not reported"
    if (w.speedKt == 0 && !w.variable) return "Calm"
    val dir = if (w.variable) "Variable" else "%03d°".format(w.dir)
    val gust = w.gustKt?.let { " gusting $it" } ?: ""
    val varying = if (w.varFrom != null && w.varTo != null) " (varying %03d°–%03d°)".format(w.varFrom, w.varTo) else ""
    return "$dir at ${w.speedKt}$gust kt$varying"
}

fun describeVis(c: Conditions): String = when {
    c.cavok -> "CAVOK (10 km or more)"
    c.visM == null -> "Not reported"
    c.visM >= 9999 -> "10 km or more"
    c.visM >= 5000 -> "%.0f km".format(c.visM / 1000.0)
    c.visM >= 1000 -> "%.1f km".format(c.visM / 1000.0)
    else -> "${c.visM} m"
}

private val COVER = mapOf("FEW" to "Few", "SCT" to "Scattered", "BKN" to "Broken", "OVC" to "Overcast", "VV" to "Vertical visibility")

fun describeCloud(l: CloudLayer): String {
    val base = l.baseFt?.let { " $it ft" } ?: ""
    val type = when (l.type) { "CB" -> " cumulonimbus"; "TCU" -> " towering cumulus"; else -> "" }
    return (COVER[l.cover] ?: l.cover) + base + type
}

fun describeClouds(c: Conditions): String = when {
    c.cavok -> "No significant cloud"
    c.clouds.isEmpty() && c.cloudsGiven -> "No significant cloud"
    c.clouds.isEmpty() -> "Not reported"
    else -> c.clouds.joinToString(", ") { describeCloud(it) }
}

/** Short one-line summaries for list rows. */
fun shortWind(w: Wind?): String {
    if (w == null) return "—"
    if (w.speedKt == 0 && !w.variable) return "Calm"
    val dir = if (w.variable) "VRB" else "%03d".format(w.dir)
    return dir + "/" + w.speedKt + (w.gustKt?.let { "G$it" } ?: "") + " kt"
}

fun shortVis(c: Conditions): String = when {
    c.cavok -> "CAVOK"
    c.visM == null -> "—"
    c.visM >= 9999 -> "10 km+"
    c.visM >= 5000 -> "%.0f km".format(c.visM / 1000.0)
    c.visM >= 1000 -> "%.1f km".format(c.visM / 1000.0)
    else -> "${c.visM} m"
}

fun shortCloud(c: Conditions): String {
    if (c.cavok) return "NSC"
    val ceil = c.clouds.firstOrNull { it.cover == "BKN" || it.cover == "OVC" || it.cover == "VV" }
        ?: c.clouds.firstOrNull()
        ?: return if (c.cloudsGiven) "NSC" else "—"
    val h = ceil.baseFt?.let { "%03d".format(it / 100) } ?: "///"
    return ceil.cover + h + (ceil.type ?: "")
}
