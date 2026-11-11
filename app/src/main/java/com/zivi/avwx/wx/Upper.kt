package com.zivi.avwx.wx

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Upper-air estimates from model data: icing, turbulence and thunderstorm risk.
 * These are rules of thumb applied to forecast temperature, humidity and wind. Pure Kotlin.
 */
object Upper {
    val LEVELS_HPA = intArrayOf(925, 850, 700, 600, 500, 400)
    val LEVELS_FT = intArrayOf(2500, 5000, 10000, 14000, 18000, 24000)

    fun label(i: Int): String {
        val ft = LEVELS_FT[i]
        return if (ft < 10000) "%,d ft".format(ft) else "FL%03d".format(ft / 100)
    }

    /** Levels of the map forecast layers. The first is the surface (10 m wind); null means no pressure level. */
    val GRID_HPA = arrayOf<Int?>(null, 925, 850, 700, 600, 500, 400)
    val GRID_LABELS = arrayOf("Surface", "2,500 ft", "5,000 ft", "FL100", "FL140", "FL180", "FL240")

    /** Cloud-top colour for a height in feet, counted from the lowest height shown ([fromFt]) up to 50,000 ft. */
    val TOPS_SCALE = intArrayOf(0xF4E04D, 0xF28C28, 0xE0302B, 0xC21FB5, 0xF7F0FF)

        /** 0 none, 1 light, 2 moderate, 3 high. Icing needs sub-zero temperature and moist air. */
    fun icing(tempC: Double?, rh: Double?): Int {
        if (tempC == null || rh == null) return 0
        if (tempC > 0.0 || tempC < -20.0) return 0
        return when {
            rh >= 90 && tempC <= -2.0 && tempC >= -12.0 -> 3
            rh >= 80 -> 2
            rh >= 65 -> 1
            else -> 0
        }
    }

    /** Vector wind change between two levels, in knots per 1,000 ft. */
    fun shear(spd1: Double, dir1: Double, ft1: Int, spd2: Double, dir2: Double, ft2: Int): Double {
        val a1 = Math.toRadians(dir1)
        val a2 = Math.toRadians(dir2)
        val du = spd2 * sin(a2) - spd1 * sin(a1)
        val dv = spd2 * cos(a2) - spd1 * cos(a1)
        val dz = kotlin.math.abs(ft2 - ft1) / 1000.0
        return if (dz <= 0.0) 0.0 else sqrt(du * du + dv * dv) / dz
    }

    /** 0 none, 1 light, 2 moderate, 3 high, from wind shear in kt per 1,000 ft. */
    fun turbulence(shear: Double): Int = when {
        shear >= 9.0 -> 3
        shear >= 6.0 -> 2
        shear >= 3.5 -> 1
        else -> 0
    }

    /** 0 low, 1 medium, 2 high. [code] is the WMO weather code (95 and above are thunderstorms). */
    fun thunder(cape: Double?, precipMm: Double?, code: Double?): Int {
        val c = cape ?: 0.0
        val p = precipMm ?: 0.0
        return when {
            code != null && code >= 95.0 -> 2
            c >= 1500.0 && p >= 1.0 -> 2
            c >= 800.0 && p >= 0.2 -> 1
            c >= 2500.0 -> 1
            else -> 0
        }
    }

    fun riskWord(n: Int): String = when (n) { 1 -> "light"; 2 -> "moderate"; 3 -> "high"; else -> "none" }
    fun thunderWord(n: Int): String = when (n) { 1 -> "Medium"; 2 -> "High"; else -> "Low" }

    /** "FL140–FL240, up to moderate" from per-level risks (lowest level first), or null when there is none. */
    fun band(risks: List<Int>): String? {
        val first = risks.indexOfFirst { it > 0 }
        if (first < 0) return null
        val last = risks.indexOfLast { it > 0 }
        val worst = riskWord(risks.max())
        return if (first == last) label(first) + ", " + worst
        else label(first) + "–" + label(last) + ", up to " + worst
    }

    fun freezingLabel(ft: Double?): String {
        if (ft == null) return "—"
        val rounded = (Math.round(ft / 500.0) * 500).toInt()
        return if (rounded >= 10000) "FL%03d".format(rounded / 100) else "%,d ft".format(rounded)
    }
}
