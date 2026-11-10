package com.zivi.avwx.wx

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A box in degrees. */
data class GeoBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(o: GeoBounds): Boolean = o.west >= west && o.east <= east && o.south >= south && o.north <= north
    val widthDeg: Double get() = east - west
}

/** Web Mercator. World x and y run 0..1, with y = 0 at the top (north). */
object Merc {
    private const val R = 20037508.342789244

    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val l = lat.coerceIn(-85.0, 85.0) * PI / 180.0
        return (1.0 - ln(tan(l) + 1.0 / cos(l)) / PI) / 2.0
    }
    fun lon(x: Double): Double = x * 360.0 - 180.0
    fun lat(y: Double): Double = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

    /** EPSG:3857 metres. */
    fun metresX(lon: Double): Double = lon * R / 180.0
    fun metresY(lat: Double): Double = (0.5 - y(lat)) * 2.0 * R
}

/**
 * An enlarged box around a view, snapped to a fixed set of sizes and positions so that nearby views
 * ask for exactly the same area (and so reuse images already downloaded).
 * All values are Mercator world units; returns west, north, east, south.
 */
fun snapBox(cx: Double, cy: Double, viewW: Double, viewH: Double, grow: Double): DoubleArray {
    val k = kotlin.math.ceil(ln(viewW * grow) / ln(1.5))
    val w = Math.pow(1.5, k)
    val h = w * viewH / viewW
    val gx = w / 6.0
    val gy = h / 6.0
    val sx = Math.round(cx / gx) * gx
    val sy = Math.round(cy / gy) * gy
    return doubleArrayOf(sx - w / 2, sy - h / 2, sx + w / 2, sy + h / 2)
}

/** Great-circle distance in nautical miles. */
fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * atan2(sqrt(a), sqrt(1 - a)) * 3440.065
}

// ---------------------------------------------------------------- sun

data class SunTimes(
    val civilDawn: Instant?,
    val sunrise: Instant?,
    val sunset: Instant?,
    val civilDusk: Instant?,
)

object Sun {
    private const val OFFICIAL = 90.833
    private const val CIVIL = 96.0

    /** Sun times for the local day (by longitude) that contains [at]. */
    fun times(lat: Double, lon: Double, at: Instant): SunTimes {
        val offsetSec = Math.round(lon / 15.0) * 3600L
        val localDate = at.plusSeconds(offsetSec).atZone(ZoneOffset.UTC).toLocalDate()
        val dayStart = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(offsetSec)
        fun ev(zenith: Double, rising: Boolean): Instant? {
            val h = utcHours(localDate, lat, lon, zenith, rising) ?: return null
            var t = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds((h * 3600).toLong())
            while (t.isBefore(dayStart)) t = t.plusSeconds(86400)
            while (!t.isBefore(dayStart.plusSeconds(86400))) t = t.minusSeconds(86400)
            return t
        }
        return SunTimes(ev(CIVIL, true), ev(OFFICIAL, true), ev(OFFICIAL, false), ev(CIVIL, false))
    }

    /** True between civil dawn and civil dusk. */
    fun isDay(lat: Double, lon: Double, at: Instant): Boolean {
        val s = times(lat, lon, at)
        val a = s.civilDawn ?: return true
        val b = s.civilDusk ?: return true
        return !at.isBefore(a) && at.isBefore(b)
    }

    private fun utcHours(date: LocalDate, lat: Double, lon: Double, zenith: Double, rising: Boolean): Double? {
        val n = date.dayOfYear.toDouble()
        val lngHour = lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = mod(l, 360.0)
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = mod(ra, 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1 || cosH < -1) return null
        var h = Math.toDegrees(acos(cosH))
        if (rising) h = 360.0 - h
        h /= 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        return mod(localT - lngHour, 24.0)
    }

    private fun mod(v: Double, m: Double): Double = ((v % m) + m) % m
}
