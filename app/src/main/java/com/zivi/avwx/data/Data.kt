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
import java.time.Instant

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

    var loopHours: Int
        get() = prefs.getInt("loopHours", 1)
        set(v) = prefs.edit().putInt("loopHours", v).apply()

    /** 0..1, higher = colour starts on warmer (lower) cloud tops. */
    var sensitivity: Float
        get() = prefs.getFloat("sens", 0.5f)
        set(v) = prefs.edit().putFloat("sens", v).apply()
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

// ---------------------------------------------------------------- satellite

class SatFrame(val time: Instant, val bitmap: Bitmap)

object Sat {
    // Area shown on the map (degrees). Maldives sits in the middle.
    const val LON_MIN = 62.0
    const val LON_MAX = 84.0
    const val LAT_MIN = -8.0
    const val LAT_MAX = 14.0
    const val SIZE = 768
    const val STEP_MIN = 15L

    private const val WMS = "https://view.eumetsat.int/geoserver/wms"
    private const val LAYER = "msg_iodc%3Air108"
    private val ISO_RE = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z")

    private fun floorToStep(t: Instant): Instant {
        val step = STEP_MIN * 60
        return Instant.ofEpochSecond(t.epochSecond / step * step)
    }

    /** Time of the newest image the server lists; falls back to an estimate if the listing can't be read. */
    suspend fun latestTime(): Instant = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val estimate = floorToStep(now.minusSeconds(20 * 60))
        try {
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
            best?.let { floorToStep(it) } ?: estimate
        } catch (e: Exception) {
            estimate
        }
    }

    /** Oldest first. */
    fun frameTimes(latest: Instant, hours: Int): List<Instant> {
        val n = (hours * 60 / STEP_MIN).toInt()
        return (n downTo 0).map { latest.minusSeconds(it * STEP_MIN * 60) }
    }

    private fun mapUrl(layer: String, format: String, time: Instant?, transparent: Boolean): String {
        val sb = StringBuilder(WMS)
        sb.append("?service=WMS&version=1.1.1&request=GetMap&layers=").append(layer)
        sb.append("&styles=&format=").append(format)
        sb.append("&srs=EPSG%3A4326&bbox=$LON_MIN,$LAT_MIN,$LON_MAX,$LAT_MAX")
        sb.append("&width=$SIZE&height=$SIZE")
        if (transparent) sb.append("&transparent=true")
        if (time != null) sb.append("&time=").append(time.toString().replace(":", "%3A"))
        return sb.toString()
    }

    private fun cacheDir(ctx: Context): File = File(ctx.cacheDir, "sat").apply { mkdirs() }

    /** Removes cached images older than 6 hours. */
    fun trimCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 6 * 3600_000L
        cacheDir(ctx).listFiles()?.forEach { f ->
            if (f.name.startsWith("ir_") && f.lastModified() < cutoff) f.delete()
        }
    }

    /** Greyscale IR image for [time], from disk if already downloaded. Null when the server has no usable image. */
    suspend fun loadGrey(ctx: Context, time: Instant): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(cacheDir(ctx), "ir_${time.epochSecond}.jpg")
        try {
            val bytes = if (f.exists() && f.length() > 2000) f.readBytes() else {
                val b = Net.getBytes(mapUrl(LAYER, "image%2Fjpeg", time, false))
                b
            }
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@withContext null
            if (isBlank(bmp)) { bmp.recycle(); f.delete(); return@withContext null }
            if (!f.exists()) f.writeBytes(bytes)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** True when the picture is one flat colour (the server had no data for that time). */
    private fun isBlank(b: Bitmap): Boolean {
        var min = 255
        var max = 0
        val stepX = b.width / 12
        val stepY = b.height / 12
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
        return max - min < 6
    }

    /** Coastlines as a transparent overlay. Optional: returns null if the server doesn't offer it. */
    suspend fun loadCoast(ctx: Context): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(cacheDir(ctx), "coast_v1.png")
        try {
            val bytes = if (f.exists() && f.length() > 500) f.readBytes()
            else Net.getBytes(mapUrl("backgrounds%3Ane_10m_coastline", "image%2Fpng", null, true))
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            src.recycle()
            var drawn = 0
            for (i in px.indices) {
                val a = px[i] ushr 24
                if (a > 40) { px[i] = (minOf(a, 200) shl 24) or 0xE8F4FF; drawn++ } else px[i] = 0
            }
            // Nothing drawn, or a solid picture: not a usable overlay.
            if (drawn == 0 || drawn > px.size / 3) return@withContext null
            if (!f.exists()) f.writeBytes(bytes)
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }

    // ---- colour enhancement

    /** Stops of the enhancement scale: position 0..1 above the threshold, and colour. */
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

    fun buildLut(sensitivity: Float): IntArray {
        val start = threshold(sensitivity)
        val lut = IntArray(256)
        for (g in 0..255) {
            if (g < start) {
                // Warm, low cloud and sea: keep as dimmed grey so the colours stand out.
                val v = (g * 0.75f).toInt()
                lut[g] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                val p = (g - start).toFloat() / (255 - start).coerceAtLeast(1) * (SCALE.size - 1)
                val i = p.toInt().coerceIn(0, SCALE.size - 2)
                lut[g] = (0xFF shl 24) or mix(SCALE[i], SCALE[i + 1], p - i)
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

    /** Applies the colour scale to a greyscale image. Brighter (colder, higher) cloud gets the hotter colours. */
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
        Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565)
    }
}
