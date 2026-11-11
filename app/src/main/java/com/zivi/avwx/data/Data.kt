package com.zivi.avwx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.zivi.avwx.wx.Merc
import com.zivi.avwx.wx.Minima
import com.zivi.avwx.wx.Upper
import com.zivi.avwx.wx.parseMetar
import com.zivi.avwx.wx.parseTaf
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// ---------------------------------------------------------------- models

data class Airport(
    val icao: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val runways: String,   // e.g. "18/36"
)

data class StationWx(
    val icao: String,
    val metarRaw: String?,
    val tafRaw: String?,
    val fetchedAt: Long,
)

object Defaults {
    /** The Maldivian airports with a met office. Runway idents are editable in the app. */
    val airports = listOf(
        Airport("VRMM", "Velana Intl (Malé)", 4.1918, 73.5291, "18/36"),
        Airport("VRMG", "Gan Intl", -0.6933, 73.1556, "10/28"),
        Airport("VRMH", "Hanimaadhoo Intl", 6.7442, 73.1705, "03/21"),
        Airport("VRDA", "Maafaru Intl", 5.8183, 73.4703, "18/36"),
        Airport("VRMK", "Kadhdhoo", 1.8592, 73.5219, "03/21"),
        Airport("VRMT", "Kaadedhdhoo", 0.4881, 72.9969, "15/33"),
    )
}

// ---------------------------------------------------------------- network

object Net {
    private const val UA = "AviationWeatherApp/1.0 (personal Android app)"

    fun getBytes(url: String, maxBytes: Int = 8_000_000, timeoutMs: Int = 20_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code == 204) return ByteArray(0)
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total >= maxBytes) break
                }
                return out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    fun getText(url: String, maxBytes: Int = 2_000_000): String = String(getBytes(url, maxBytes), Charsets.UTF_8)
}

// ---------------------------------------------------------------- settings + airport list

class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("avwx", Context.MODE_PRIVATE)

    /** The saved list. Maafaru (VRDA) is added once to lists saved before it became a default. */
    fun loadAirports(): List<Airport> {
        val list = loadSavedAirports()
        if (!prefs.getBoolean("vrdaAdded", false)) {
            prefs.edit().putBoolean("vrdaAdded", true).apply()
            if (list.none { it.icao == "VRDA" }) {
                val out = list + Defaults.airports.first { it.icao == "VRDA" }
                saveAirports(out)
                return out
            }
        }
        return list
    }

    private fun loadSavedAirports(): List<Airport> {
        val s = prefs.getString("airports", null) ?: return Defaults.airports
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Airport(
                    icao = o.getString("icao"),
                    name = o.optString("name", ""),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lon = if (o.has("lon")) o.getDouble("lon") else null,
                    runways = o.optString("rwy", ""),
                )
            }
        } catch (e: Exception) {
            Defaults.airports
        }
    }

    fun saveAirports(list: List<Airport>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("icao", a.icao)
            o.put("name", a.name)
            if (a.lat != null) o.put("lat", a.lat)
            if (a.lon != null) o.put("lon", a.lon)
            o.put("rwy", a.runways)
            arr.put(o)
        }
        prefs.edit().putString("airports", arr.toString()).apply()
    }

    fun loadWx(): Map<String, StationWx> {
        val s = prefs.getString("wx", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = HashMap<String, StationWx>()
            for (k in obj.keys()) {
                val o = obj.getJSONObject(k)
                out[k] = StationWx(
                    icao = k,
                    metarRaw = o.optString("m", "").ifEmpty { null },
                    tafRaw = o.optString("t", "").ifEmpty { null },
                    fetchedAt = o.optLong("at", 0L),
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveWx(map: Map<String, StationWx>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            val o = JSONObject()
            o.put("m", v.metarRaw ?: "")
            o.put("t", v.tafRaw ?: "")
            o.put("at", v.fetchedAt)
            obj.put(k, o)
        }
        prefs.edit().putString("wx", obj.toString()).apply()
    }

    var nightMode: Boolean
        get() = prefs.getBoolean("night", false)
        set(v) = prefs.edit().putBoolean("night", v).apply()

    var enhanced: Boolean
        get() = prefs.getBoolean("enhanced", true)
        set(v) = prefs.edit().putBoolean("enhanced", v).apply()

    /** Extra satellite layer: "none", "rain" or "cth". */
    var overlay: String
        get() = prefs.getString("overlay", "none") ?: "none"
        set(v) = prefs.edit().putString("overlay", v).apply()

    var showIcing: Boolean
        get() = prefs.getBoolean("icing", false)
        set(v) = prefs.edit().putBoolean("icing", v).apply()

    var showWind: Boolean
        get() = prefs.getBoolean("windArrows", false)
        set(v) = prefs.edit().putBoolean("windArrows", v).apply()

    /** True = plain arrows with numbers instead of the moving wind lines. */
    var windAsArrows: Boolean
        get() = prefs.getBoolean("windStyleArrows", false)
        set(v) = prefs.edit().putBoolean("windStyleArrows", v).apply()

    var gridLevel: Int
        get() = prefs.getInt("gridLevel", 0)
        set(v) = prefs.edit().putInt("gridLevel", v).apply()

    var showLightRain: Boolean
        get() = prefs.getBoolean("lightRain", false)
        set(v) = prefs.edit().putBoolean("lightRain", v).apply()

    /** Cloud tops below this flight level are hidden in the Tops layer. */
    var topsFl: Int
        get() = prefs.getInt("topsFl", 250)
        set(v) = prefs.edit().putInt("topsFl", v).apply()

    var showSigmet: Boolean
        get() = prefs.getBoolean("sigmet", true)
        set(v) = prefs.edit().putBoolean("sigmet", v).apply()

    var loopHours: Int
        get() = prefs.getInt("loopHours", 1)
        set(v) = prefs.edit().putInt("loopHours", v).apply()

    /** 0..1, higher = colour starts on warmer (lower) cloud tops. */
    var sensitivity: Float
        get() = prefs.getFloat("sens", 0.5f)
        set(v) = prefs.edit().putFloat("sens", v).apply()

    var notify: Boolean
        get() = prefs.getBoolean("notify", false)
        set(v) = prefs.edit().putBoolean("notify", v).apply()

    var destMinima: Minima
        get() = Minima(prefs.getInt("minDestCeil", 500), prefs.getInt("minDestVis", 2000))
        set(v) = prefs.edit().putInt("minDestCeil", v.ceilingFt).putInt("minDestVis", v.visM).apply()

    var altnMinima: Minima
        get() = Minima(prefs.getInt("minAltnCeil", 800), prefs.getInt("minAltnVis", 3000))
        set(v) = prefs.edit().putInt("minAltnCeil", v.ceilingFt).putInt("minAltnVis", v.visM).apply()

    /** Sector: dep, dest, altn, etd, eta. */
    fun loadSector(): List<String> {
        val s = prefs.getString("sector", null) ?: return listOf("", "", "", "", "")
        val parts = s.split("|")
        return if (parts.size == 5) parts else listOf("", "", "", "", "")
    }

    fun saveSector(parts: List<String>) {
        prefs.edit().putString("sector", parts.joinToString("|") { it.replace("|", "") }).apply()
    }

    fun loadRecents(): List<String> =
        (prefs.getString("recents", "") ?: "").split(",").filter { it.length == 4 }

    fun saveRecents(list: List<String>) {
        prefs.edit().putString("recents", list.take(8).joinToString(",")).apply()
    }
}

// ---------------------------------------------------------------- METAR / TAF fetch

class StationInfo(val name: String?, val lat: Double?, val lon: Double?)

class WxResult(val wx: Map<String, StationWx>, val info: Map<String, StationInfo>)

object WxApi {
    private const val BASE = "https://aviationweather.gov/api/data"

    /** Latest METAR and TAF for each station. Stations with no report are still returned, with nulls. */
    suspend fun fetch(icaos: List<String>): WxResult = withContext(Dispatchers.IO) {
        val ids = icaos.joinToString(",")
        val now = System.currentTimeMillis()
        val metars = HashMap<String, String>()
        val tafs = HashMap<String, String>()
        val info = HashMap<String, StationInfo>()

        val mText = Net.getText("$BASE/metar?ids=$ids&format=json")
        if (mText.isNotBlank()) {
            val arr = JSONArray(mText)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("icaoId", "")
                val raw = o.optString("rawOb", "")
                if (id.isEmpty() || raw.isEmpty()) continue
                if (!metars.containsKey(id)) metars[id] = raw   // first = newest
                info[id] = StationInfo(
                    name = o.optString("name", "").ifEmpty { null },
                    lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
                    lon = if (o.has("lon") && !o.isNull("lon")) o.optDouble("lon") else null,
                )
            }
        }
        // A TAF failure must not throw away good METARs.
        try {
            val tText = Net.getText("$BASE/taf?ids=$ids&format=json")
            if (tText.isNotBlank()) {
                val arr = JSONArray(tText)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("icaoId", "")
                    val raw = o.optString("rawTAF", "")
                    if (id.isEmpty() || raw.isEmpty()) continue
                    if (!tafs.containsKey(id)) tafs[id] = raw
                }
            }
        } catch (e: Exception) {
            if (metars.isEmpty()) throw e
        }
        val out = HashMap<String, StationWx>()
        for (id in icaos) out[id] = StationWx(id, metars[id], tafs[id], now)
        WxResult(out, info)
    }
}

/** Combines reports for one station from two fetches or two sources, keeping whichever report is newer. */
object WxMerge {
    fun pick(old: StationWx?, new: StationWx): StationWx {
        if (old == null) return new
        val metar = newer(old.metarRaw, old.fetchedAt, new.metarRaw, new.fetchedAt) { raw, ref -> parseMetar(raw, ref)?.obsTime }
        val taf = newer(old.tafRaw, old.fetchedAt, new.tafRaw, new.fetchedAt) { raw, ref -> parseTaf(raw, ref)?.issued }
        return StationWx(new.icao, metar, taf, maxOf(old.fetchedAt, new.fetchedAt))
    }

    private fun newer(a: String?, aAt: Long, b: String?, bAt: Long, time: (String, Instant) -> Instant?): String? {
        if (b == null) return a
        if (a == null || a == b) return b
        val ta = runCatching { time(a, Instant.ofEpochMilli(aAt)) }.getOrNull()
        val tb = runCatching { time(b, Instant.ofEpochMilli(bAt)) }.getOrNull()
        return if (ta != null && tb != null && ta.isAfter(tb)) a else b
    }
}

// ---------------------------------------------------------------- live traffic

class Aircraft(
    val hex: String,
    val flight: String,
    val reg: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val altFt: Int?,
    val onGround: Boolean,
    val speedKt: Double?,
    val track: Double?,
    val vsFpm: Int?,
    val squawk: String,
    val seenSec: Double,
) {
    val label: String get() = flight.ifEmpty { reg.ifEmpty { hex.uppercase() } }
}

// ---------------------------------------------------------------- METAR history, SIGMET, winds aloft

class Sigmet(
    val fir: String,
    val firName: String,
    val hazard: String,
    val qualifier: String,
    val from: Long,      // epoch seconds
    val to: Long,
    val raw: String,
    val baseFt: Int?,
    val topFt: Int?,
    val coords: List<DoubleArray>,   // [lat, lon]
)

class AloftLevel(
    val hPa: Int,
    val label: String,
    val ft: Int,
    val speedKt: List<Double?>,
    val dirDeg: List<Double?>,
    val tempC: List<Double?>,
    val rh: List<Double?>,
)

/** Model forecast over one point. Levels run from the lowest to the highest. */
class Aloft(
    val times: List<Instant>,
    val levels: List<AloftLevel>,
    val freezingFt: List<Double?>,
    val cape: List<Double?>,
    val precip: List<Double?>,
    val code: List<Double?>,
    val fetchedAt: Long,
) {
    /** Index of the forecast hour nearest to [epochSec]. */
    fun indexAt(epochSec: Long): Int {
        var idx = 0
        var best = Long.MAX_VALUE
        for (i in times.indices) {
            val d = kotlin.math.abs(times[i].epochSecond - epochSec)
            if (d < best) { best = d; idx = i }
        }
        return idx
    }

    fun icing(level: Int, i: Int): Int = Upper.icing(levels[level].tempC.getOrNull(i), levels[level].rh.getOrNull(i))

    /** Turbulence risk at a level, from the stronger of the wind shears to the levels below and above. */
    fun turbulence(level: Int, i: Int): Int {
        var worst = 0.0
        for (other in intArrayOf(level - 1, level + 1)) {
            if (other < 0 || other >= levels.size) continue
            val a = levels[level]
            val b = levels[other]
            val s1 = a.speedKt.getOrNull(i) ?: continue
            val d1 = a.dirDeg.getOrNull(i) ?: continue
            val s2 = b.speedKt.getOrNull(i) ?: continue
            val d2 = b.dirDeg.getOrNull(i) ?: continue
            worst = maxOf(worst, Upper.shear(s1, d1, a.ft, s2, d2, b.ft))
        }
        return Upper.turbulence(worst)
    }

    fun thunder(i: Int): Int = Upper.thunder(cape.getOrNull(i), precip.getOrNull(i), code.getOrNull(i))
    fun icingBand(i: Int): String? = Upper.band(levels.indices.map { icing(it, i) })
    fun turbulenceBand(i: Int): String? = Upper.band(levels.indices.map { turbulence(it, i) })
}

enum class GridKind(val cols: Int, val rows: Int) {
    WIND(14, 20),     // wind at every level
    ICING(10, 12),    // temperature and humidity at the upper levels
}

/**
 * Forecast values on an even latitude / longitude grid, for the map layers. Two values per point:
 * for wind, [a] is the eastward and [b] the northward part in knots; for icing, temperature (C) and humidity (%).
 * Indexed [level][hour][point], points row by row from the north-west corner. NaN where missing.
 */
class FcGrid(
    val key: String,
    val kind: GridKind,
    val west: Double, val south: Double, val east: Double, val north: Double,
    val times: LongArray,
    val a: Array<Array<FloatArray>>,
    val b: Array<Array<FloatArray>>,
    val fetchedAt: Long,
) {
    val cols = kind.cols
    val rows = kind.rows
    val dLon = (east - west) / cols
    val dLat = (north - south) / rows

    /** Index of the forecast hour nearest to [epochSec]. */
    fun indexAt(epochSec: Long): Int {
        var idx = 0
        var best = Long.MAX_VALUE
        for (i in times.indices) {
            val d = kotlin.math.abs(times[i] - epochSec)
            if (d < best) { best = d; idx = i }
        }
        return idx
    }

    /** Value at a position, blended from the four nearest grid points. False when outside the grid or missing. */
    fun sample(level: Int, ti: Int, lat: Double, lon: Double, out: FloatArray): Boolean {
        val fx = (lon - west) / dLon - 0.5
        val fy = (north - lat) / dLat - 0.5
        if (fx < -0.5 || fy < -0.5 || fx > cols - 0.5 || fy > rows - 0.5) return false
        val x = fx.coerceIn(0.0, cols - 1.0)
        val y = fy.coerceIn(0.0, rows - 1.0)
        val x0 = x.toInt().coerceAtMost(cols - 2)
        val y0 = y.toInt().coerceAtMost(rows - 2)
        val tx = (x - x0).toFloat()
        val ty = (y - y0).toFloat()
        val i = y0 * cols + x0
        val pa = a[level][ti]
        val pb = b[level][ti]
        val top = pa[i] + (pa[i + 1] - pa[i]) * tx
        val bot = pa[i + cols] + (pa[i + cols + 1] - pa[i + cols]) * tx
        out[0] = top + (bot - top) * ty
        val top2 = pb[i] + (pb[i + 1] - pb[i]) * tx
        val bot2 = pb[i + cols] + (pb[i + cols + 1] - pb[i + cols]) * tx
        out[1] = top2 + (bot2 - top2) * ty
        return !out[0].isNaN() && !out[1].isNaN()
    }
}

/** Keeps requests to the free forecast service under its per-minute allowance. */
object OmBudget {
    private val used = ArrayList<LongArray>()   // [time, weight]

    suspend fun take(weight: Int) {
        while (true) {
            val now = System.currentTimeMillis()
            val free = synchronized(used) {
                used.removeAll { now - it[0] > 61_000L }
                val sum = used.sumOf { it[1] }
                if (sum + weight <= 560 || used.isEmpty()) { used += longArrayOf(now, weight.toLong()); true } else false
            }
            if (free) return
            kotlinx.coroutines.delay(2500)
        }
    }
}

object ExtraApi {
    private const val BASE = "https://aviationweather.gov/api/data"

    /** Raw METARs of the last [hours], oldest first, each with its observation time. */
    suspend fun history(icao: String, hours: Int): List<Pair<Instant, String>> = withContext(Dispatchers.IO) {
        val text = Net.getText("$BASE/metar?ids=$icao&format=json&hours=$hours")
        if (text.isBlank()) return@withContext emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<Pair<Instant, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val raw = o.optString("rawOb", "")
            val t = o.optLong("obsTime", 0L)
            if (raw.isEmpty() || t <= 0L) continue
            out += Instant.ofEpochSecond(t) to raw
        }
        out.sortedBy { it.first }
    }

    /** International SIGMETs currently valid, limited to the wider Indian Ocean region. */
    suspend fun sigmets(): List<Sigmet> = withContext(Dispatchers.IO) {
        val text = Net.getText("$BASE/isigmet?format=json")
        if (text.isBlank()) return@withContext emptyList()
        val arr = JSONArray(text)
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Sigmet>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cs = o.optJSONArray("coords") ?: continue
            val coords = ArrayList<DoubleArray>()
            for (k in 0 until cs.length()) {
                val c = cs.optJSONObject(k) ?: continue
                val lat = c.optDouble("lat", Double.NaN)
                val lon = c.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) coords += doubleArrayOf(lat, lon)
            }
            if (coords.size < 3) continue
            if (coords.none { it[0] in -35.0..35.0 && it[1] in 30.0..110.0 }) continue
            val to = o.optLong("validTimeTo", 0L)
            if (to in 1 until now) continue
            out += Sigmet(
                fir = o.optString("firId", ""),
                firName = o.optString("firName", ""),
                hazard = o.optString("hazard", ""),
                qualifier = o.optString("qualifier", ""),
                from = o.optLong("validTimeFrom", 0L),
                to = to,
                raw = o.optString("rawSigmet", ""),
                baseFt = if (o.has("base") && !o.isNull("base")) o.optInt("base") else null,
                topFt = if (o.has("top") && !o.isNull("top")) o.optInt("top") else null,
                coords = coords,
            )
        }
        out
    }

    /** Aircraft within [radiusNm] of a point, from community ADS-B receivers. Tries a second network if the first fails. */
    suspend fun aircraft(lat: Double, lon: Double, radiusNm: Int): List<Aircraft> = withContext(Dispatchers.IO) {
        val us = java.util.Locale.US
        val la = "%.3f".format(us, lat)
        val lo = "%.3f".format(us, lon)
        val urls = listOf(
            "https://api.airplanes.live/v2/point/$la/$lo/$radiusNm",
            "https://api.adsb.lol/v2/lat/$la/lon/$lo/dist/$radiusNm",
        )
        var failure: Exception? = null
        for (url in urls) {
            try {
                val arr = JSONObject(Net.getText(url, maxBytes = 3_000_000)).optJSONArray("ac") ?: JSONArray()
                val out = ArrayList<Aircraft>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pLat = o.optDouble("lat", Double.NaN)
                    val pLon = o.optDouble("lon", Double.NaN)
                    if (pLat.isNaN() || pLon.isNaN()) continue
                    val alt = o.opt("alt_baro")
                    val ground = alt is String
                    val track = o.optDouble("track", o.optDouble("true_heading", Double.NaN))
                    val gs = o.optDouble("gs", Double.NaN)
                    out += Aircraft(
                        hex = o.optString("hex", ""),
                        flight = o.optString("flight", "").trim(),
                        reg = o.optString("r", "").trim(),
                        type = o.optString("t", "").trim(),
                        lat = pLat,
                        lon = pLon,
                        altFt = if (alt is Number) alt.toInt() else null,
                        onGround = ground,
                        speedKt = if (gs.isNaN()) null else gs,
                        track = if (track.isNaN()) null else track,
                        vsFpm = if (o.has("baro_rate")) o.optInt("baro_rate") else if (o.has("geom_rate")) o.optInt("geom_rate") else null,
                        squawk = o.optString("squawk", ""),
                        seenSec = o.optDouble("seen_pos", o.optDouble("seen", 0.0)),
                    )
                }
                return@withContext out
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: java.io.IOException("no traffic source")
    }

    private fun parseTimes(h: JSONObject): List<Instant> {
        val tArr = h.getJSONArray("time")
        return (0 until tArr.length()).map { LocalDateTime.parse(tArr.getString(it)).toInstant(ZoneOffset.UTC) }
    }

    /** Forecast wind, temperature, humidity, freezing level and storm indicators over a point (Open-Meteo). */
    suspend fun aloft(lat: Double, lon: Double): Aloft = withContext(Dispatchers.IO) {
        val vars = Upper.LEVELS_HPA.joinToString(",") { p ->
            "wind_speed_${p}hPa,wind_direction_${p}hPa,temperature_${p}hPa,relative_humidity_${p}hPa"
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=$vars" +
            ",freezing_level_height,cape,precipitation,weather_code&wind_speed_unit=kn&forecast_days=2&timezone=GMT"
        val h = JSONObject(Net.getText(url)).getJSONObject("hourly")
        val times = parseTimes(h)
        fun col(name: String): List<Double?> {
            val a = h.optJSONArray(name) ?: return List(times.size) { null }
            return (0 until a.length()).map { if (a.isNull(it)) null else a.optDouble(it) }
        }
        val levels = Upper.LEVELS_HPA.mapIndexed { i, p ->
            AloftLevel(
                p, Upper.label(i), Upper.LEVELS_FT[i],
                col("wind_speed_${p}hPa"), col("wind_direction_${p}hPa"), col("temperature_${p}hPa"), col("relative_humidity_${p}hPa"),
            )
        }
        Aloft(
            times, levels,
            freezingFt = col("freezing_level_height").map { it?.times(3.28084) },
            cape = col("cape"), precip = col("precipitation"), code = col("weather_code"),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Forecast grid for an area, for every map level at once, so changing level or time needs no new download.
     * A copy is kept on the phone for an hour.
     */
    suspend fun grid(ctx: Context, kind: GridKind, west: Double, south: Double, east: Double, north: Double, key: String): FcGrid =
        withContext(Dispatchers.IO) {
            val cols = kind.cols
            val rows = kind.rows
            val n = cols * rows
            val dLon = (east - west) / cols
            val dLat = (north - south) / rows
            val us = java.util.Locale.US
            val lats = StringBuilder()
            val lons = StringBuilder()
            for (r in 0 until rows) for (c in 0 until cols) {
                if (lats.isNotEmpty()) { lats.append(','); lons.append(',') }
                lats.append("%.2f".format(us, north - (r + 0.5) * dLat))
                lons.append("%.2f".format(us, west + (c + 0.5) * dLon))
            }
            val levels = Upper.GRID_HPA
            fun names(level: Int): Pair<String, String>? {
                val p = levels[level]
                return when (kind) {
                    GridKind.WIND -> if (p == null) "wind_speed_10m" to "wind_direction_10m" else "wind_speed_${p}hPa" to "wind_direction_${p}hPa"
                    GridKind.ICING -> if (p == null) null else "temperature_${p}hPa" to "relative_humidity_${p}hPa"
                }
            }
            val vars = levels.indices.mapNotNull { names(it) }.flatMap { listOf(it.first, it.second) }

            val dir = File(ctx.cacheDir, "grid").apply { mkdirs() }
            val file = File(dir, "${kind.name}_$key.json")
            var fetchedAt = file.lastModified()
            val text = if (file.exists() && file.length() > 1000 && System.currentTimeMillis() - fetchedAt < 60 * 60_000L) {
                file.readText()
            } else {
                OmBudget.take((n * maxOf(1.0, vars.size / 10.0)).toInt())
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons&hourly=" + vars.joinToString(",") +
                    "&wind_speed_unit=kn&forecast_days=3&timeformat=unixtime&timezone=GMT"
                val t = Net.getText(url, maxBytes = 14_000_000).trim()
                if (!t.startsWith("[")) throw java.io.IOException("forecast grid refused")
                dir.listFiles()?.forEach { f -> if (System.currentTimeMillis() - f.lastModified() > 3 * 3600_000L) f.delete() }
                file.writeText(t)
                fetchedAt = System.currentTimeMillis()
                t
            }
            val arr = JSONArray(text)
            if (arr.length() < n) throw java.io.IOException("forecast grid incomplete")
            val tArr = arr.getJSONObject(0).getJSONObject("hourly").getJSONArray("time")
            val hours = tArr.length()
            val times = LongArray(hours) { tArr.optLong(it) }
            val a = Array(levels.size) { Array(hours) { FloatArray(n) { Float.NaN } } }
            val b = Array(levels.size) { Array(hours) { FloatArray(n) { Float.NaN } } }
            for (i in 0 until n) {
                val h = arr.getJSONObject(i).getJSONObject("hourly")
                for (level in levels.indices) {
                    val nm = names(level) ?: continue
                    val first = h.optJSONArray(nm.first) ?: continue
                    val second = h.optJSONArray(nm.second) ?: continue
                    for (t in 0 until minOf(hours, first.length(), second.length())) {
                        if (first.isNull(t) || second.isNull(t)) continue
                        val v1 = first.optDouble(t)
                        val v2 = second.optDouble(t)
                        if (kind == GridKind.WIND) {
                            // Speed and the direction the wind comes from -> eastward and northward parts.
                            val rad = Math.toRadians(v2)
                            a[level][t][i] = (-v1 * Math.sin(rad)).toFloat()
                            b[level][t][i] = (-v1 * Math.cos(rad)).toFloat()
                        } else {
                            a[level][t][i] = v1.toFloat()
                            b[level][t][i] = v2.toFloat()
                        }
                    }
                }
            }
            FcGrid(key, kind, west, south, east, north, times, a, b, fetchedAt)
        }
}

// ---------------------------------------------------------------- base map tiles

enum class TileSource(val key: String, val maxZoom: Int, val parallel: Int) {
    MAP("carto", 12, 4),    // street map without names; the app draws English names itself
    SAT("sat", 13, 4),
}

object Tiles {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "tiles").apply { mkdirs() }

    private fun urls(s: TileSource, z: Int, x: Int, y: Int): List<String> = when (s) {
        TileSource.MAP -> listOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png",
        )
        TileSource.SAT -> listOf(
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g/$z/$y/$x.jpg",
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/$z/$y/$x.jpg",
        )
    }

    /** One 256 px tile, from the phone if it was downloaded before. A second server is tried if the first fails. */
    suspend fun load(ctx: Context, s: TileSource, z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "${s.key}_${z}_${x}_$y")
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            if (f.exists() && f.length() > 100) {
                val saved = BitmapFactory.decodeFile(f.absolutePath, opts)
                if (saved != null) return@withContext saved
            }
            for (url in urls(s, z, x, y)) {
                val bytes = try { Net.getBytes(url, maxBytes = 1_000_000, timeoutMs = 12_000) } catch (e: Exception) { continue }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                f.writeBytes(bytes)
                return@withContext bmp
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Keeps the saved tiles to a few thousand files, dropping the ones used longest ago. */
    suspend fun trim(ctx: Context) = withContext(Dispatchers.IO) {
        val files = dir(ctx).listFiles() ?: return@withContext
        if (files.size > 4000) {
            files.sortedBy { it.lastModified() }.take(files.size - 3200).forEach { it.delete() }
        }
    }
}

// ---------------------------------------------------------------- satellite

/** Extra EUMETSAT products that can be laid over the cloud picture. */
enum class Overlay(val key: String, val guesses: List<String>, val match: Regex, val west: List<String>) {
    NONE("none", emptyList(), Regex("$^"), emptyList()),
    RAIN("rain", listOf("msg_iodc:h63", "msg_iodc:mpe", "msg_iodc:h60b"), Regex("(^|:)(h63|h60b?|mpe)$"), listOf("msg_fes:h60b", "msg_fes:h60")),
    TOPS("cth", listOf("msg_iodc:cth"), Regex("(^|:)cth$"), listOf("msg_fes:cth"));

    companion object {
        fun of(key: String): Overlay = values().firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * One weather satellite. [lon] is where it sits over the equator. EUMETSAT pictures come as plain grey
 * infrared ([irLayers]); NASA's ([gibs]) come already coloured.
 */
class SatSource(val id: Int, val lon: Double, val gibs: String?, val irLayers: List<String>)

/**
 * Satellite pictures, fetched as 256 px map tiles so that parts already downloaded are reused when the map
 * is moved or zoomed. Five satellites cover the globe; each tile comes from the one nearest to it.
 */
object Sat {
    const val STEP_MIN = 15L
    const val MAX_ZOOM = 6          // about 2.4 km per pixel, as fine as the satellites see

    val SOURCES = listOf(
        SatSource(0, 45.5, null, listOf("msg_iodc:ir108")),                       // Meteosat, Indian Ocean
        SatSource(1, 0.0, null, listOf("msg_fes:ir108", "mtg_fd:ir105_hrfi")),    // Meteosat, Africa and Europe
        SatSource(2, -75.2, "GOES-East_ABI_Band13_Clean_Infrared", emptyList()),
        SatSource(3, -137.2, "GOES-West_ABI_Band13_Clean_Infrared", emptyList()),
        SatSource(4, 140.7, "Himawari_AHI_Band13_Clean_Infrared", emptyList()),
    )

    private const val WMS = "https://view.eumetsat.int/geoserver/wms"
    private val ISO_RE = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z")
    private val NAME_RE = Regex("<Name>([^<]+)</Name>")

    private val resolved = java.util.concurrent.ConcurrentHashMap<Overlay, String>()
    private val missing = java.util.concurrent.ConcurrentHashMap<Overlay, Boolean>()
    private val noData = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val irLayerIdx = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val westFails = java.util.concurrent.ConcurrentHashMap<Overlay, Int>()
    private val gibsLatest = java.util.concurrent.ConcurrentHashMap<Int, Instant>()
    private val gibsProbedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    @Volatile private var serverLayers: List<String>? = null
    @Volatile private var latestCached: Instant? = null
    @Volatile private var latestAt = 0L

    fun unavailable(o: Overlay): Boolean = missing[o] == true

    /** The satellite a tile is taken from: the one sitting nearest to the tile's middle. */
    fun sourceFor(z: Int, x: Int): SatSource {
        val lon = (x + 0.5) / (1 shl z) * 360.0 - 180.0
        var best = SOURCES[0]
        var bestD = 999.0
        for (s in SOURCES) {
            val d0 = kotlin.math.abs(lon - s.lon)
            val d = if (d0 > 180.0) 360.0 - d0 else d0
            if (d < bestD) { bestD = d; best = s }
        }
        return best
    }

    fun sourcesIn(z: Int, x0: Int, x1: Int): Set<SatSource> {
        val out = HashSet<SatSource>()
        for (x in x0..x1) out += sourceFor(z, x)
        return out
    }

    fun latestOf(src: SatSource): Instant? = gibsLatest[src.id]

    /**
     * The picture time a satellite has for a loop frame. NASA's satellites take pictures every 10 minutes and
     * can run behind, so the frame time is matched to their nearest earlier picture. Null until that is known.
     */
    fun timeFor(src: SatSource, frame: Instant): Instant? {
        if (src.gibs == null) return frame
        val latest = gibsLatest[src.id] ?: return null
        val t = Instant.ofEpochSecond(frame.epochSecond / 600 * 600)
        return if (t.isAfter(latest)) latest else t
    }

    /** Layer names the server lists for the Indian Ocean satellite, read once. */
    private fun listLayers(): List<String> {
        serverLayers?.let { return it }
        val names = try {
            val xml = Net.getText(
                "https://view.eumetsat.int/geoserver/msg_iodc/wms?service=WMS&version=1.1.1&request=GetCapabilities",
                maxBytes = 3_000_000,
            )
            NAME_RE.findAll(xml).map { it.groupValues[1].trim() }.toList()
        } catch (e: Exception) {
            return emptyList()
        }
        serverLayers = names
        return names
    }

    /** Names to try for an overlay: what the server lists first, then the usual names. */
    private fun candidates(o: Overlay): List<String> {
        resolved[o]?.let { return listOf(it) }
        val found = listLayers().filter { o.match.containsMatchIn(it.lowercase()) }
            .map { if (it.contains(":")) it else "msg_iodc:$it" }
        return (found + o.guesses).distinct()
    }

    private fun floorToStep(t: Instant): Instant {
        val step = STEP_MIN * 60
        return Instant.ofEpochSecond(t.epochSecond / step * step)
    }

    /** Time of the newest image the server lists; falls back to an estimate if the listing can't be read. */
    suspend fun latestTime(): Instant = withContext(Dispatchers.IO) {
        val cached = latestCached
        if (cached != null && System.currentTimeMillis() - latestAt < 90_000L) return@withContext cached
        val t = fetchLatestTime()
        latestCached = t
        latestAt = System.currentTimeMillis()
        t
    }

    private fun fetchLatestTime(): Instant {
        val now = Instant.now()
        val estimate = floorToStep(now.minusSeconds(20 * 60))
        try {
            // (plain function: runs on the caller's background thread)
            val xml = Net.getText(
                "https://view.eumetsat.int/geoserver/msg_iodc/ir108/wms?service=WMS&version=1.1.1&request=GetCapabilities",
                maxBytes = 600_000,
            )
            val limit = now.plusSeconds(300)
            val floor = now.minusSeconds(6 * 3600)
            var best: Instant? = null
            for (m in ISO_RE.findAll(xml)) {
                val t = try { Instant.parse(m.value) } catch (e: Exception) { continue }
                if (t.isAfter(limit) || t.isBefore(floor)) continue
                if (best == null || t.isAfter(best)) best = t
            }
            return best?.let { floorToStep(it) } ?: estimate
        } catch (e: Exception) {
            return estimate
        }
    }

    /** Oldest first. */
    fun frameTimes(latest: Instant, hours: Int): List<Instant> {
        val n = (hours * 60 / STEP_MIN).toInt()
        return (n downTo 0).map { latest.minusSeconds(it * STEP_MIN * 60) }
    }

    private fun wmsTileUrl(layerEnc: String, format: String, time: Instant, transparent: Boolean, z: Int, x: Int, y: Int): String {
        val n = (1 shl z).toDouble()
        val sb = StringBuilder(WMS)
        sb.append("?service=WMS&version=1.1.1&request=GetMap&layers=").append(layerEnc)
        sb.append("&styles=&format=").append(format)
        sb.append("&srs=EPSG%3A3857&bbox=")
        sb.append("%.2f,%.2f,%.2f,%.2f".format(
            java.util.Locale.US, Merc.mx(x / n), Merc.my((y + 1) / n), Merc.mx((x + 1) / n), Merc.my(y / n)))
        sb.append("&width=256&height=256")
        if (transparent) sb.append("&transparent=true")
        sb.append("&time=").append(time.toString().replace(":", "%3A"))
        return sb.toString()
    }

    private fun gibsUrl(src: SatSource, time: Instant, z: Int, x: Int, y: Int): String =
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/${src.gibs}/default/$time/GoogleMapsCompatible_Level6/$z/$y/$x.png"

    private fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "sat").apply { mkdirs() }

    /** Removes saved tiles older than 8 hours, and the oldest ones beyond a fixed count. */
    suspend fun trimCache(ctx: Context) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 8 * 3600_000L
        val files = cacheDir(ctx).listFiles() ?: return@withContext
        if (files.size < 300) return@withContext
        files.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        files.filter { it.exists() }.sortedByDescending { it.lastModified() }.drop(6000).forEach { it.delete() }
    }

    /** Megabytes of satellite and forecast data saved on the phone. */
    suspend fun cacheMb(ctx: Context): Int = withContext(Dispatchers.IO) {
        var bytes = 0L
        for (name in listOf("sat", "grid")) File(ctx.cacheDir, name).listFiles()?.forEach { bytes += it.length() }
        ((bytes + 524_288) / 1_048_576).toInt()
    }

    suspend fun clearCache(ctx: Context) = withContext(Dispatchers.IO) {
        for (name in listOf("sat", "grid")) File(ctx.cacheDir, name).listFiles()?.forEach { it.delete() }
        noData.clear()
    }

    /** Greyscale infrared tile from a EUMETSAT satellite, from the phone if already downloaded. */
    suspend fun loadIrTile(ctx: Context, src: SatSource, time: Instant, z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val name = "ir${src.id}_${time.epochSecond}_${z}_${x}_$y"
        if (noData.containsKey(name)) return@withContext null
        val f = File(cacheDir(ctx), name)
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            if (f.exists() && f.length() > 300) {
                val saved = BitmapFactory.decodeFile(f.absolutePath, opts)
                if (saved != null) return@withContext saved
            }
            // A satellite may be listed under more than one layer name; the first that answers is remembered.
            val known = irLayerIdx[src.id]
            val tryList = if (known != null) listOf(known) else src.irLayers.indices.toList()
            var answered = false
            for (i in tryList) {
                val bytes = try {
                    Net.getBytes(wmsTileUrl(src.irLayers[i].replace(":", "%3A"), "image%2Fjpeg", time, false, z, x, y), maxBytes = 2_000_000)
                } catch (e: Exception) {
                    continue
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                answered = true
                if (isFlat(bmp)) { bmp.recycle(); continue }
                irLayerIdx[src.id] = i
                f.writeBytes(bytes)
                return@withContext bmp
            }
            if (answered) noData[name] = true
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Coloured infrared tile from one of NASA's satellites, from the phone if already downloaded. */
    suspend fun loadGibsTile(ctx: Context, src: SatSource, time: Instant, z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(cacheDir(ctx), "ir${src.id}_${time.epochSecond}_${z}_${x}_$y")
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            if (f.exists() && f.length() > 100) {
                val saved = BitmapFactory.decodeFile(f.absolutePath, opts)
                if (saved != null) return@withContext saved
            }
            val bytes = Net.getBytes(gibsUrl(src, time, z, x, y), maxBytes = 2_000_000)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            f.writeBytes(bytes)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds the newest picture one of NASA's satellites has, by asking for one tile at recent times.
     * Asked again at most every 5 minutes.
     */
    suspend fun probeLatest(src: SatSource): Instant? = withContext(Dispatchers.IO) {
        if (src.gibs == null) return@withContext null
        val now = System.currentTimeMillis()
        val last = gibsProbedAt[src.id]
        if (last != null && now - last < 5 * 60_000L) return@withContext gibsLatest[src.id]
        gibsProbedAt[src.id] = now
        val x = (((src.lon + 180.0) / 360.0) * 4).toInt().coerceIn(0, 3)
        var t = (now / 1000 - 15 * 60) / 600 * 600
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        for (step in 0 until 16) {
            try {
                val bytes = Net.getBytes(gibsUrl(src, Instant.ofEpochSecond(t), 2, x, 1), maxBytes = 2_000_000, timeoutMs = 10_000)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) {
                    val ok = hasPicture(bmp)
                    bmp.recycle()
                    if (ok) {
                        gibsLatest[src.id] = Instant.ofEpochSecond(t)
                        return@withContext gibsLatest[src.id]
                    }
                }
            } catch (e: Exception) {
                // "HTTP 404" means no picture at that time yet: try an earlier one. Anything else: no connection.
                if (e.message?.startsWith("HTTP") != true) break
            }
            t -= 600
        }
        gibsLatest[src.id]
    }

    private fun hasPicture(b: Bitmap): Boolean {
        val step = b.width / 8
        if (step == 0) return false
        var y = step / 2
        while (y < b.height) {
            var x = step / 2
            while (x < b.width) {
                if ((b.getPixel(x, y) ushr 24) > 128) return true
                x += step
            }
            y += step
        }
        return false
    }

    /** Finds which layer name the server uses for an overlay and returns that layer's colour key. */
    suspend fun resolve(o: Overlay): Bitmap? = withContext(Dispatchers.IO) {
        if (o == Overlay.NONE) return@withContext null
        for (layer in candidates(o)) {
            val legend = try {
                val bytes = Net.getBytes(
                    "$WMS?service=WMS&version=1.1.1&request=GetLegendGraphic&format=image%2Fpng&layer=" + layer.replace(":", "%3A"),
                    maxBytes = 500_000,
                )
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            } ?: continue
            resolved[o] = layer
            return@withContext legend
        }
        // Not remembered as a failure: a dropped connection looks the same, so the next load tries again.
        null
    }

    fun isResolved(o: Overlay): Boolean = resolved[o] != null

    private val ovLatest = java.util.concurrent.ConcurrentHashMap<Overlay, Instant>()
    private val ovCheckedAt = java.util.concurrent.ConcurrentHashMap<Overlay, Long>()

    /** The overlay being shown, so frame times can be matched to it while drawing. */
    @Volatile var activeOverlay: Overlay = Overlay.NONE

    /** Newest time the overlay product has reached, or null when not known. */
    fun overlayLatest(o: Overlay): Instant? = ovLatest[o]

    /**
     * Rain and cloud-top products are worked out after the infrared picture, so they lag behind it.
     * A frame newer than the product's newest time uses that newest time instead.
     */
    fun overlayTime(frame: Instant): Instant {
        val l = ovLatest[activeOverlay] ?: return frame
        return if (frame.isAfter(l)) l else frame
    }

    /** Reads how far an overlay product has got from the server's listing for that layer. Asked at most every 2 minutes. */
    suspend fun checkOverlayLatest(o: Overlay): Instant? = withContext(Dispatchers.IO) {
        val layer = resolved[o] ?: return@withContext null
        val now = System.currentTimeMillis()
        val last = ovCheckedAt[o]
        if (last != null && now - last < 2 * 60_000L) return@withContext ovLatest[o]
        ovCheckedAt[o] = now
        val parts = layer.split(":")
        if (parts.size != 2) return@withContext null
        try {
            val xml = Net.getText(
                "https://view.eumetsat.int/geoserver/${parts[0]}/${parts[1]}/wms?service=WMS&version=1.1.1&request=GetCapabilities",
                maxBytes = 1_500_000,
            )
            val limit = Instant.ofEpochMilli(now).plusSeconds(300)
            val floor = Instant.ofEpochMilli(now).minusSeconds(12 * 3600)
            var best: Instant? = null
            for (m in ISO_RE.findAll(xml)) {
                val t = try { Instant.parse(m.value) } catch (e: Exception) { continue }
                if (t.isAfter(limit) || t.isBefore(floor)) continue
                if (best == null || t.isAfter(best)) best = t
            }
            if (best != null) ovLatest[o] = Instant.ofEpochSecond(best.epochSecond / (STEP_MIN * 60) * (STEP_MIN * 60))
        } catch (e: Exception) {
            // Keep what was known.
        }
        ovLatest[o]
    }

    /** An overlay tile (transparent where there is nothing to show) in the server's own colours. */
    suspend fun loadOverlayTile(ctx: Context, o: Overlay, src: SatSource, time: Instant, z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (src.gibs != null) return@withContext null
        val layers = if (src.id == 0) listOfNotNull(resolved[o]) else if ((westFails[o] ?: 0) > 5) emptyList() else o.west
        val f = File(cacheDir(ctx), "${o.key}${src.id}_${time.epochSecond}_${z}_${x}_$y")
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            if (f.exists() && f.length() > 60) {
                val saved = BitmapFactory.decodeFile(f.absolutePath, opts)
                if (saved != null) return@withContext saved
            }
            for (layer in layers) {
                val bytes = try {
                    Net.getBytes(wmsTileUrl(layer.replace(":", "%3A"), "image%2Fpng", time, true, z, x, y), maxBytes = 2_000_000)
                } catch (e: Exception) {
                    continue
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: continue
                if (src.id != 0) westFails[o] = 0
                // An empty tile may only mean the product isn't out yet, so it isn't kept on the phone.
                if (!isClear(bmp)) f.writeBytes(bytes)
                return@withContext bmp
            }
            if (src.id != 0) westFails[o] = (westFails[o] ?: 0) + 1
            null
        } catch (e: Exception) {
            null
        }
    }

    /** True when the tile is see-through everywhere sampled. */
    private fun isClear(b: Bitmap): Boolean {
        val step = (b.width / 16).coerceAtLeast(1)
        var y = step / 2
        while (y < b.height) {
            var x = step / 2
            while (x < b.width) {
                if ((b.getPixel(x, y) ushr 24) > 30) return false
                x += step
            }
            y += step
        }
        return true
    }

    /** True when every sampled pixel is the same (the server had no picture for that time or place). */
    private fun isFlat(b: Bitmap): Boolean {
        val first = b.getPixel(b.width / 2, b.height / 2) and 0xFF
        val step = b.width / 9
        if (step == 0) return true
        var y = step / 2
        while (y < b.height) {
            var x = step / 2
            while (x < b.width) {
                if (kotlin.math.abs((b.getPixel(x, y) and 0xFF) - first) > 2) return false
                x += step
            }
            y += step
        }
        return true
    }

    // ---- colour enhancement

    /** Stops of the enhancement scale, from the first coloured cloud tops to the coldest. */
    val SCALE = intArrayOf(
        0x3F7FE8,  // blue: mid-level cloud
        0x35B84B,  // green
        0xF2E03A,  // yellow
        0xF2952E,  // orange
        0xE5352B,  // red
        0xC21FB5,  // magenta
        0xF7EEFF,  // near white: coldest tops
    )

    /** Brightness (0..255) where colour begins for a given sensitivity. */
    fun threshold(sensitivity: Float): Int = (172 - 62 * sensitivity.coerceIn(0f, 1f)).toInt()

    /**
     * Lookup from image brightness to an overlay colour. Clear sea and land become transparent so the
     * map shows through; low cloud is drawn grey-white; colder tops take the colour scale when [colour] is on.
     */
    fun buildLut(sensitivity: Float, colour: Boolean): IntArray {
        val start = threshold(sensitivity)
        val cloudFrom = 78       // darker than this is treated as clear
        val lut = IntArray(256)
        for (g in 0..255) {
            if (g < cloudFrom) { lut[g] = 0; continue }
            if (!colour || g < start) {
                val top = if (colour) start else 215
                val t = ((g - cloudFrom).toFloat() / (top - cloudFrom).coerceAtLeast(1)).coerceIn(0f, 1f)
                val a = (t * 0.86f * 255).toInt()
                val v = (190 + 60 * t).toInt().coerceAtMost(255)
                lut[g] = (a shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                val p = (g - start).toFloat() / (255 - start).coerceAtLeast(1) * (SCALE.size - 1)
                val i = p.toInt().coerceIn(0, SCALE.size - 2)
                lut[g] = (0xEE shl 24) or mix(SCALE[i], SCALE[i + 1], p - i)
            }
        }
        return lut
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * tt).toInt()
        val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * tt).toInt()
        val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * tt).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    /** Turns a greyscale tile into a see-through cloud tile using [lut]. */
    suspend fun colourise(grey: Bitmap, lut: IntArray): Bitmap = withContext(Dispatchers.Default) {
        val w = grey.width
        val h = grey.height
        val px = IntArray(w * h)
        grey.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val lum = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
            px[i] = lut[lum]
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Makes a NASA tile match the rest of the map: its grey (warmer) parts go through [lut] like any other cloud,
     * and its coloured (cold-top) parts keep NASA's colours, or turn white when colour is off.
     */
    suspend fun styleGibs(raw: Bitmap, lut: IntArray, colour: Boolean): Bitmap = withContext(Dispatchers.Default) {
        val w = raw.width
        val h = raw.height
        val px = IntArray(w * h)
        raw.getPixels(px, 0, w, 0, 0, w, h)
        val white = lut[235]
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 128) { px[i] = 0; continue }
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            px[i] = if (mx - mn < 28) lut[(r * 3 + g * 6 + b) / 10]
            else if (colour) (0xEE shl 24) or (p and 0xFFFFFF)
            else white
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Removes the palest (lightest-rain) colours from a rain tile. */
    suspend fun hideLightRain(raw: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val w = raw.width
        val h = raw.height
        val px = IntArray(w * h)
        raw.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 40 || ((p shr 16 and 0xFF) > 215 && (p shr 8 and 0xFF) > 235)) px[i] = 0
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- cloud top height

    private const val TOPS_MIN_M = 320.0
    private const val TOPS_MAX_M = 16000.0

    /** The usual colours of the cloud-top-height product, lowest to highest, used if the server's key can't be read. */
    private val TOPS_FALLBACK = intArrayOf(
        0x000000, 0x5A1E1E, 0x7B2CBF, 0x2B3FD6, 0x3FA9F5, 0x38D9C5, 0x3CCB4A,
        0xB5E12F, 0xF5E02F, 0xF59A23, 0xE53222, 0x9E9E9E, 0xFFFFFF,
    )

    /** The colour bar inside the server's colour key: the longest unbroken run of non-white pixels. */
    private fun rampFrom(legend: Bitmap?): IntArray? {
        if (legend == null) return null
        var bestY = -1
        var bestX = 0
        var bestLen = 0
        val row = IntArray(legend.width)
        for (y in 0 until legend.height) {
            legend.getPixels(row, 0, legend.width, 0, y, legend.width, 1)
            var runStart = -1
            for (x in 0..row.size) {
                var solid = false
                if (x < row.size) {
                    val p = row[x]
                    solid = (p ushr 24) > 128 && !((p shr 16 and 0xFF) > 244 && (p shr 8 and 0xFF) > 244 && (p and 0xFF) > 244)
                }
                if (solid) {
                    if (runStart < 0) runStart = x
                } else if (runStart >= 0) {
                    val len = x - runStart
                    if (len > bestLen) { bestLen = len; bestX = runStart; bestY = y }
                    runStart = -1
                }
            }
        }
        if (bestLen < 40) return null
        legend.getPixels(row, 0, legend.width, 0, bestY, legend.width, 1)
        val colours = IntArray(bestLen) { row[bestX + it] and 0xFFFFFF }
        // A real colour bar has many different colours; a line of text or a border does not.
        return if (colours.toSet().size >= 12) colours else null
    }

    /**
     * Table from a (coarsened) picture colour to a cloud-top height, in steps of 1/255 of the product's range.
     * Built from the server's own colour key when it can be read.
     */
    suspend fun buildTopsTable(legend: Bitmap?): ByteArray = withContext(Dispatchers.Default) {
        val bar = rampFrom(legend)
        val n = 96
        val ramp = IntArray(n) { i ->
            val f = i / (n - 1f)
            if (bar != null) bar[(f * (bar.size - 1)).toInt()]
            else {
                val p = f * (TOPS_FALLBACK.size - 1)
                val k = p.toInt().coerceAtMost(TOPS_FALLBACK.size - 2)
                mix(TOPS_FALLBACK[k], TOPS_FALLBACK[k + 1], p - k)
            }
        }
        val table = ByteArray(32768)
        for (idx in 0 until 32768) {
            val r = (idx shr 10 and 31) * 8 + 4
            val g = (idx shr 5 and 31) * 8 + 4
            val b = (idx and 31) * 8 + 4
            var best = 0
            var bestD = Int.MAX_VALUE
            for (k in 0 until n) {
                val c = ramp[k]
                val dr = r - (c shr 16 and 0xFF)
                val dg = g - (c shr 8 and 0xFF)
                val db = b - (c and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < bestD) { bestD = d; best = k }
            }
            table[idx] = (best * 255 / (n - 1)).toByte()
        }
        table
    }

    /**
     * Recolours a cloud-top-height tile. A top is shown only when it is above [fromFl] and the infrared picture
     * ([grey], same tile) is at least [minBright] there: thick, very cold cloud. Thin high cloud lets warmth
     * through, looks darker on infrared, and is left out.
     */
    suspend fun recolourTops(src: Bitmap, table: ByteArray, fromFl: Int, grey: Bitmap, minBright: Int): Bitmap = withContext(Dispatchers.Default) {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mask = if (grey.width == w && grey.height == h) IntArray(w * h).also { grey.getPixels(it, 0, w, 0, 0, w, h) } else null
        val fromFt = fromFl * 100.0
        val scale = Upper.TOPS_SCALE
        // Colour for each of the 256 table steps, worked out once.
        val out = IntArray(256)
        for (k in 0..255) {
            val ft = (TOPS_MIN_M + (TOPS_MAX_M - TOPS_MIN_M) * k / 255.0) * 3.28084
            if (ft < fromFt) { out[k] = 0; continue }
            val p = ((ft - fromFt) / (50000.0 - fromFt)).coerceIn(0.0, 1.0).toFloat() * (scale.size - 1)
            val i = p.toInt().coerceAtMost(scale.size - 2)
            out[k] = (0xE6 shl 24) or mix(scale[i], scale[i + 1], p - i)
        }
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 60 || (mask != null && (mask[i] shr 8 and 0xFF) < minBright)) { px[i] = 0; continue }
            val idx = ((p shr 19 and 31) shl 10) or ((p shr 11 and 31) shl 5) or (p shr 3 and 31)
            px[i] = out[table[idx].toInt() and 0xFF]
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
