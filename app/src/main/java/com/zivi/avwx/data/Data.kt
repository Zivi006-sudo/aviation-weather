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
import com.zivi.avwx.wx.GeoBounds
import com.zivi.avwx.wx.Merc
import com.zivi.avwx.wx.Minima
import com.zivi.avwx.wx.Upper
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
    /** The five Maldivian airports with a met office. Runway idents are editable in the app. */
    val airports = listOf(
        Airport("VRMM", "Velana Intl (Malé)", 4.1918, 73.5291, "18/36"),
        Airport("VRMG", "Gan Intl", -0.6933, 73.1556, "10/28"),
        Airport("VRMH", "Hanimaadhoo Intl", 6.7442, 73.1705, "03/21"),
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

    fun loadAirports(): List<Airport> {
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

/** Forecast at one level on a grid of points, for the map layers. Values are NaN where missing. */
class WxGrid(
    val key: String,
    val level: Int,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val dLat: Double,
    val dLon: Double,
    val times: List<Instant>,
    val speed: Array<FloatArray>,   // [point][hour]
    val dir: Array<FloatArray>,
    val temp: Array<FloatArray>,
    val rh: Array<FloatArray>,
    val fetchedAt: Long,
) {
    fun indexAt(epochSec: Long): Int {
        var idx = 0
        var best = Long.MAX_VALUE
        for (i in times.indices) {
            val d = kotlin.math.abs(times[i].epochSecond - epochSec)
            if (d < best) { best = d; idx = i }
        }
        return idx
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

    /** Wind, temperature and humidity at one level on a grid of points covering [b]. */
    suspend fun grid(b: GeoBounds, level: Int, key: String): WxGrid = withContext(Dispatchers.IO) {
        val cols = 6
        val rows = 8
        val dLon = (b.east - b.west) / cols
        val dLat = (b.north - b.south) / rows
        val n = cols * rows
        val lats = DoubleArray(n)
        val lons = DoubleArray(n)
        for (r in 0 until rows) for (c in 0 until cols) {
            lats[r * cols + c] = b.north - (r + 0.5) * dLat
            lons[r * cols + c] = b.west + (c + 0.5) * dLon
        }
        val p = Upper.LEVELS_HPA[level]
        val us = java.util.Locale.US
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + lats.joinToString(",") { "%.2f".format(us, it) } +
            "&longitude=" + lons.joinToString(",") { "%.2f".format(us, it) } +
            "&hourly=wind_speed_${p}hPa,wind_direction_${p}hPa,temperature_${p}hPa,relative_humidity_${p}hPa" +
            "&wind_speed_unit=kn&forecast_days=2&timezone=GMT"
        val text = Net.getText(url, maxBytes = 4_000_000).trim()
        val arr = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        if (arr.length() < n) throw java.io.IOException("grid incomplete")
        var times: List<Instant> = emptyList()
        fun col(h: JSONObject, name: String, size: Int): FloatArray {
            val a = h.optJSONArray(name)
            return FloatArray(size) { i -> if (a == null || i >= a.length() || a.isNull(i)) Float.NaN else a.optDouble(i).toFloat() }
        }
        val speed = ArrayList<FloatArray>()
        val dir = ArrayList<FloatArray>()
        val temp = ArrayList<FloatArray>()
        val rh = ArrayList<FloatArray>()
        for (i in 0 until n) {
            val h = arr.getJSONObject(i).getJSONObject("hourly")
            if (i == 0) times = parseTimes(h)
            speed += col(h, "wind_speed_${p}hPa", times.size)
            dir += col(h, "wind_direction_${p}hPa", times.size)
            temp += col(h, "temperature_${p}hPa", times.size)
            rh += col(h, "relative_humidity_${p}hPa", times.size)
        }
        WxGrid(key, level, lats, lons, dLat, dLon, times, speed.toTypedArray(), dir.toTypedArray(), temp.toTypedArray(), rh.toTypedArray(), System.currentTimeMillis())
    }
}

// ---------------------------------------------------------------- base map tiles

object Tiles {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "tiles").apply { mkdirs() }

    /** One 256 px map tile, from the phone if it was downloaded before. */
    suspend fun load(ctx: Context, z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "${z}_${x}_$y.png")
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            if (f.exists() && f.length() > 100) {
                val saved = BitmapFactory.decodeFile(f.absolutePath, opts)
                if (saved != null) return@withContext saved
            }
            // Two map servers: if the first refuses or is down, the second is tried.
            for (url in listOf(
                "https://tile.openstreetmap.org/$z/$x/$y.png",
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png",
            )) {
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
}

// ---------------------------------------------------------------- satellite

/** The area and picture size a set of satellite images was requested for. */
class SatRequest(val bounds: GeoBounds, val width: Int, val height: Int) {
    val key: String = "%.2f_%.2f_%.2f_%.2f_%dx%d".format(bounds.west, bounds.south, bounds.east, bounds.north, width, height)
        .replace('-', 'm').replace('.', 'p')
}

class SatFrame(val time: Instant, val bitmap: Bitmap, val overlay: Bitmap?, val bounds: GeoBounds)

/** Extra EUMETSAT products that can be laid over the cloud picture. */
enum class Overlay(val key: String, val guesses: List<String>, val match: Regex) {
    NONE("none", emptyList(), Regex("$^")),
    RAIN("rain", listOf("msg_iodc:h63", "msg_iodc:mpe", "msg_iodc:h60b"), Regex("(^|:)(h63|h60b?|mpe)$")),
    TOPS("cth", listOf("msg_iodc:cth"), Regex("(^|:)cth$"));

    companion object {
        fun of(key: String): Overlay = values().firstOrNull { it.key == key } ?: NONE
    }
}

object Sat {
    const val STEP_MIN = 15L
    const val MAX_DIM = 1024

    private const val WMS = "https://view.eumetsat.int/geoserver/wms"
    private const val LAYER = "msg_iodc%3Air108"
    private val ISO_RE = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z")
    private val NAME_RE = Regex("<Name>([^<]+)</Name>")

    private val resolved = java.util.concurrent.ConcurrentHashMap<Overlay, String>()
    private val missing = java.util.concurrent.ConcurrentHashMap<Overlay, Boolean>()
    @Volatile private var serverLayers: List<String>? = null
    @Volatile private var latestCached: Instant? = null
    @Volatile private var latestAt = 0L

    fun unavailable(o: Overlay): Boolean = missing[o] == true

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
        if (cached != null && System.currentTimeMillis() - latestAt < 120_000L) return@withContext cached
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

    private fun mapUrl(layer: String, format: String, time: Instant?, transparent: Boolean, req: SatRequest): String {
        val b = req.bounds
        val sb = StringBuilder(WMS)
        sb.append("?service=WMS&version=1.1.1&request=GetMap&layers=").append(layer)
        sb.append("&styles=&format=").append(format)
        sb.append("&srs=EPSG%3A3857&bbox=")
        sb.append("%.1f,%.1f,%.1f,%.1f".format(
            java.util.Locale.US, Merc.metresX(b.west), Merc.metresY(b.south), Merc.metresX(b.east), Merc.metresY(b.north)))
        sb.append("&width=${req.width}&height=${req.height}")
        if (transparent) sb.append("&transparent=true")
        if (time != null) sb.append("&time=").append(time.toString().replace(":", "%3A"))
        return sb.toString()
    }

    private fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "sat").apply { mkdirs() }

    /** Removes cached images older than 4 hours, and the oldest ones beyond a fixed count. */
    fun trimCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 4 * 3600_000L
        val files = cacheDir(ctx).listFiles() ?: return
        files.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        // Panning makes a new set each time; keep only the newest few hundred files.
        files.filter { it.exists() }.sortedByDescending { it.lastModified() }.drop(260).forEach { it.delete() }
    }

    /** Greyscale IR image for [time], from disk if already downloaded. Null when the server has no usable image. */
    suspend fun loadGrey(ctx: Context, time: Instant, req: SatRequest): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(cacheDir(ctx), "ir_${time.epochSecond}_${req.key}.jpg")
        try {
            val bytes = if (f.exists() && f.length() > 1000) f.readBytes()
            else Net.getBytes(mapUrl(LAYER, "image%2Fjpeg", time, false, req))
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            if (isBlank(bmp)) { bmp.recycle(); f.delete(); return@withContext null }
            if (!f.exists()) f.writeBytes(bytes)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** An overlay picture (transparent where there is nothing to show) for [time]. */
    suspend fun loadOverlay(ctx: Context, o: Overlay, time: Instant, req: SatRequest): Bitmap? = withContext(Dispatchers.IO) {
        if (o == Overlay.NONE || unavailable(o)) return@withContext null
        val f = File(cacheDir(ctx), "${o.key}_${time.epochSecond}_${req.key}.png")
        try {
            if (f.exists() && f.length() > 100) {
                return@withContext BitmapFactory.decodeFile(f.absolutePath)
            }
            for (layer in candidates(o)) {
                val bytes = try {
                    Net.getBytes(mapUrl(layer.replace(":", "%3A"), "image%2Fpng", time, true, req))
                } catch (e: Exception) {
                    continue
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                resolved[o] = layer
                f.writeBytes(bytes)
                return@withContext bmp
            }
            if (resolved[o] == null) missing[o] = true
            null
        } catch (e: Exception) {
            null
        }
    }

    /** The server's own colour key for an overlay, once its layer is known. */
    suspend fun loadLegend(o: Overlay): Bitmap? = withContext(Dispatchers.IO) {
        val layer = resolved[o] ?: return@withContext null
        try {
            val bytes = Net.getBytes(
                "$WMS?service=WMS&version=1.1.1&request=GetLegendGraphic&format=image%2Fpng&layer=" + layer.replace(":", "%3A"),
                maxBytes = 500_000,
            )
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** True when the picture is one flat colour (the server had no data for that time or place). */
    private fun isBlank(b: Bitmap): Boolean {
        var min = 255
        var max = 0
        val stepX = b.width / 14
        val stepY = b.height / 14
        if (stepX == 0 || stepY == 0) return true
        var y = stepY / 2
        while (y < b.height) {
            var x = stepX / 2
            while (x < b.width) {
                val g = (b.getPixel(x, y) shr 8) and 0xFF
                if (g < min) min = g
                if (g > max) max = g
                x += stepX
            }
            y += stepY
        }
        return max - min < 3
    }

    // ---- colour enhancement

    /** Stops of the enhancement scale, from the first coloured cloud tops to the coldest. */
    val SCALE = intArrayOf(
        0x2E9E4F,  // green
        0xE8D53A,  // yellow
        0xF29A2E,  // orange
        0xE5352B,  // red
        0xC21FB5,  // magenta
        0xF5E9FF,  // near white: coldest tops
    )

    /** Brightness (0..255) where colour begins for a given sensitivity. */
    fun threshold(sensitivity: Float): Int = (185 - 70 * sensitivity.coerceIn(0f, 1f)).toInt()

    /**
     * Lookup from image brightness to an overlay colour. Clear sea and land become transparent so the
     * map shows through; ordinary cloud is drawn white; cold tops take the colour scale when [colour] is on.
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
                val a = (t * 0.88f * 255).toInt()
                val v = (205 + 50 * t).toInt().coerceAtMost(255)
                lut[g] = (a shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                val p = (g - start).toFloat() / (255 - start).coerceAtLeast(1) * (SCALE.size - 1)
                val i = p.toInt().coerceIn(0, SCALE.size - 2)
                lut[g] = (0xF2 shl 24) or mix(SCALE[i], SCALE[i + 1], p - i)
            }
        }
        return lut
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * tt).toInt()
        val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * tt).toInt()
        val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * tt).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    /** Turns a greyscale image into a see-through cloud overlay using [lut]. */
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
}
