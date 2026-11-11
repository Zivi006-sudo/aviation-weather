package com.zivi.avwx.ui

import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zivi.avwx.data.Sat
import java.time.Instant
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Drawing the satellite picture from tiles. A tile that has not arrived yet is filled from whatever is already
 * in memory: finer tiles from a closer zoom, a coarser tile from a wider zoom, or the same tile of the newest frame.
 * That is what keeps the picture on screen while zooming and panning.
 */

/**
 * Satellite tiles held in memory, limited by size. Each kind has its own tiles, so switching between
 * colour cloud, plain (IR) cloud, rain and tops shows what is already there instead of rebuilding it.
 */
internal object SatMem {
    const val COLOUR = 0
    const val GREY = 1
    const val RAIN = 2
    const val TOPS = 3

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 3).toInt().coerceIn(32_000, 160_000)
    val mem = object : LruCache<Long, ImageBitmap>(maxKb) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }
    /** Cloud tiles that failed to load, with when, so they are not asked for again straight away. */
    val failed = HashMap<Long, Long>()
    /** Extra-layer tiles that were loaded and had nothing in them, with when. Asked again after a few minutes. */
    val empty = HashMap<Long, Long>()
    var tick by mutableIntStateOf(0)

    fun isEmpty(k: Long): Boolean {
        val at = empty[k] ?: return false
        return System.currentTimeMillis() - at < 4 * 60_000L
    }

    /** [time] is the satellite's own picture time, so two loop frames showing the same picture share one tile. */
    fun key(kind: Int, time: Instant, z: Int, x: Int, y: Int): Long =
        (kind.toLong() shl 44) or (((time.epochSecond / 300) and 0xFFFFF) shl 24) or (z.toLong() shl 16) or (x.toLong() shl 8) or y.toLong()

    fun kindOf(key: Long): Int = (key ushr 44).toInt()

    /** Drops the tiles of some kinds only (after a colour setting changed). */
    fun clearKinds(vararg kinds: Int) {
        for (k in mem.snapshot().keys) if (kindOf(k) in kinds) mem.remove(k)
        empty.keys.removeAll { kindOf(it) in kinds }
        tick++
    }

    fun clear() {
        mem.evictAll()
        failed.clear()
        empty.clear()
        tick++
    }
}

/**
 * The tiles a view needs. The newest picture is loaded sharper than the older loop frames, and only a few
 * zoom levels are used, so zooming in and out mostly reuses tiles already saved.
 */
internal data class SatView(val newest: TileRange, val loop: TileRange)

private fun rangeAt(cam: MapCam, z: Int): TileRange {
    val s = cam.scale
    val w = cam.size.width
    val h = cam.size.height
    val n = 1 shl z
    return TileRange(
        z,
        floor((cam.cx - w / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cx + w / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cy - h / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cy + h / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
    )
}

internal fun satView(cam: MapCam): SatView? {
    val s = cam.scale
    if (s <= 0.0 || cam.size.width == 0 || cam.size.height == 0) return null
    val ideal = floor(ln(s / 384.0) / ln(2.0)).toInt()
    val zNew = when {
        ideal <= 2 -> 2
        ideal <= 4 -> 4
        else -> Sat.MAX_ZOOM
    }
    val zLoop = if (zNew == Sat.MAX_ZOOM) zNew - 1 else zNew
    val rn = rangeAt(cam, zNew)
    return SatView(rn, if (zLoop == zNew) rn else rangeAt(cam, zLoop))
}

/** One picture to draw: part of an image ([sx], [sy], [ss]) placed over part of a tile (fractions [fx], [fy], [fs]). */
internal class SatPiece(val img: ImageBitmap, val sx: Int, val sy: Int, val ss: Int, val tx: Int, val ty: Int, val fx: Float, val fy: Float, val fs: Float)

/** What to draw for each loop frame, worked out again only when the view, the kind or the tiles in memory change. */
internal class SatPlan {
    private var view: SatView? = null
    private var kind = -1
    private var tick = -1
    private var times: List<Instant> = emptyList()
    var frames: Array<ArrayList<SatPiece>> = emptyArray()
        private set
    var zs: IntArray = IntArray(0)
        private set

    fun ensure(v: SatView, t: List<Instant>, k: Int) {
        val now = SatMem.tick
        if (v == view && now == tick && t === times && k == kind) return
        view = v
        tick = now
        times = t
        kind = k
        val overlay = k >= SatMem.RAIN
        val last = t.size - 1
        val newest = t.lastOrNull()
        val complete = BooleanArray(t.size)
        val z = IntArray(t.size)
        val built = Array(t.size) { f ->
            val r = if (f == last) v.newest else v.loop
            z[f] = r.z
            val list = ArrayList<SatPiece>()
            var all = true
            for (y in r.y0..r.y1) {
                for (x in r.x0..r.x1) {
                    var ok = place(list, t[f], r.z, x, y)
                    // Cloud picture: a tile missing from an older frame borrows the newest frame's.
                    if (!ok && !overlay && newest != null && f != last) ok = place(list, newest, r.z, x, y)
                    if (!ok) all = false
                }
            }
            complete[f] = all
            list
        }
        if (overlay) {
            // Extra layers are shown a whole frame at a time: an unfinished frame shows the nearest finished one.
            for (f in built.indices) {
                if (complete[f]) continue
                var pick = -1
                var d = 1
                while (pick < 0 && (f + d < built.size || f - d >= 0)) {
                    if (f + d < built.size && complete[f + d]) pick = f + d
                    else if (f - d >= 0 && complete[f - d]) pick = f - d
                    d++
                }
                if (pick >= 0) { built[f] = built[pick]; z[f] = z[pick] } else built[f] = ArrayList()
            }
        }
        frames = built
        zs = z
    }

    /** A picture, EMPTY when known to have nothing, or null when not loaded. */
    private fun fetch(frame: Instant, z: Int, x: Int, y: Int): Any? {
        val src = Sat.sourceFor(z, x)
        val overlay = kind >= SatMem.RAIN
        if (overlay && src.gibs != null) return EMPTY
        val t = if (overlay) Sat.overlayTime(frame) else Sat.timeFor(src, frame) ?: return null
        val k = SatMem.key(kind, t, z, x, y)
        val img = SatMem.mem.get(k)
        if (img != null) return img
        return if (overlay && k in SatMem.empty) EMPTY else null
    }

    private fun place(out: ArrayList<SatPiece>, frame: Instant, z: Int, x: Int, y: Int): Boolean {
        val own = fetch(frame, z, x, y)
        if (own is ImageBitmap) {
            out += SatPiece(own, 0, 0, own.width, x, y, 0f, 0f, 1f)
            return true
        }
        if (own === EMPTY) return true
        // Finer tiles from a closer zoom, when all of them are there.
        for (d in 1..2) {
            if (z + d > Sat.MAX_ZOOM) break
            val k = 1 shl d
            val found = ArrayList<SatPiece>(k * k)
            var have = 0
            for (j in 0 until k) {
                for (i in 0 until k) {
                    val child = fetch(frame, z + d, x * k + i, y * k + j) ?: continue
                    have++
                    if (child is ImageBitmap) found += SatPiece(child, 0, 0, child.width, x, y, i.toFloat() / k, j.toFloat() / k, 1f / k)
                }
            }
            if (have == k * k) {
                out += found
                return true
            }
        }
        // A coarser tile from a wider zoom.
        for (up in 1..4) {
            if (z - up < 0) break
            val parent = fetch(frame, z - up, x shr up, y shr up) ?: continue
            if (parent is ImageBitmap) {
                val sub = parent.width shr up
                if (sub < 1) break
                out += SatPiece(parent, (x - ((x shr up) shl up)) * sub, (y - ((y shr up) shl up)) * sub, sub, x, y, 0f, 0f, 1f)
            }
            return true
        }
        // Cloud picture only: some of the finer tiles are better than a hole.
        if (kind < SatMem.RAIN && z + 1 <= Sat.MAX_ZOOM) {
            var any = false
            for (j in 0..1) {
                for (i in 0..1) {
                    val child = fetch(frame, z + 1, x * 2 + i, y * 2 + j) as? ImageBitmap ?: continue
                    out += SatPiece(child, 0, 0, child.width, x, y, i / 2f, j / 2f, 0.5f)
                    any = true
                }
            }
            if (any) return true
        }
        return false
    }

    private companion object {
        val EMPTY = Any()
    }
}

private fun DrawScope.drawPieces(list: List<SatPiece>, z: Int, cam: MapCam, alpha: Float) {
    val n = (1 shl z).toDouble()
    val s = cam.scale
    val w = size.width
    val h = size.height
    for (i in list.indices) {
        val p = list[i]
        val l = px(w, cam.cx, s, (p.tx + p.fx) / n).roundToInt()
        val t = px(h, cam.cy, s, (p.ty + p.fy) / n).roundToInt()
        val r = px(w, cam.cx, s, (p.tx + p.fx + p.fs) / n).roundToInt()
        val b = px(h, cam.cy, s, (p.ty + p.fy + p.fs) / n).roundToInt()
        if (r <= l || b <= t) continue
        drawImage(
            p.img, IntOffset(p.sx, p.sy), IntSize(p.ss, p.ss), IntOffset(l, t), IntSize(r - l, b - t),
            alpha = alpha, filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * Draws the loop of [kind] at position [pos] (frame index, with a fraction for the fade between two frames).
 * With [fade] off the nearest frame alone is drawn, which suits layers with hard-edged colours.
 */
internal fun DrawScope.drawSat(
    plan: SatPlan, cam: MapCam, view: SatView?, times: List<Instant>, kind: Int, pos: Float, alpha: Float, fade: Boolean,
) {
    if (view == null || times.isEmpty()) return
    plan.ensure(view, times, kind)
    val frames = plan.frames
    if (frames.isEmpty()) return
    val p = pos.coerceIn(0f, (frames.size - 1).toFloat())
    if (!fade) {
        val i = p.roundToInt()
        drawPieces(frames[i], plan.zs[i], cam, alpha)
        return
    }
    val i = floor(p).toInt().coerceIn(0, frames.size - 1)
    val frac = p - i
    // A steady fade across the whole step, so the clouds appear to drift rather than jump.
    val f = if (i + 1 < frames.size) frac else 0f
    drawPieces(frames[i], plan.zs[i], cam, alpha * (1f - f))
    if (f > 0.01f) drawPieces(frames[i + 1], plan.zs[i + 1], cam, alpha * f)
}
