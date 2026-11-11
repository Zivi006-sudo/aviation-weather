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

/** Coloured satellite tiles held in memory, limited by size. Kind 0 is the cloud picture, 1 the extra layer. */
internal object SatMem {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt().coerceIn(24_000, 110_000)
    val mem = object : LruCache<Long, ImageBitmap>(maxKb) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }
    /** Cloud tiles that failed to load, with when, so they are not asked for again straight away. */
    val failed = HashMap<Long, Long>()
    /** Extra-layer tiles that were loaded and had nothing in them. */
    val empty = HashSet<Long>()
    var tick by mutableIntStateOf(0)

    /** [time] is the satellite's own picture time, so two loop frames showing the same picture share one tile. */
    fun key(kind: Int, time: Instant, z: Int, x: Int, y: Int): Long =
        (kind.toLong() shl 44) or (((time.epochSecond / 300) and 0xFFFFF) shl 24) or (z.toLong() shl 16) or (x.toLong() shl 8) or y.toLong()

    fun clear() {
        mem.evictAll()
        failed.clear()
        empty.clear()
        tick++
    }
}

/** Satellite tiles the camera needs. With a long loop a coarser zoom is used so every frame fits in memory. */
internal fun satRange(cam: MapCam, frames: Int): TileRange? {
    val s = cam.scale
    val w = cam.size.width
    val h = cam.size.height
    if (s <= 0.0 || w == 0 || h == 0) return null
    val z = (floor(ln(s / 384.0) / ln(2.0)).toInt() - (if (frames > 7) 1 else 0)).coerceIn(2, Sat.MAX_ZOOM)
    val n = 1 shl z
    return TileRange(
        z,
        floor((cam.cx - w / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cx + w / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cy - h / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cy + h / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
    )
}

/** One picture to draw: part of an image ([sx], [sy], [ss]) placed over part of a tile (fractions [fx], [fy], [fs]). */
internal class SatPiece(val img: ImageBitmap, val sx: Int, val sy: Int, val ss: Int, val tx: Int, val ty: Int, val fx: Float, val fy: Float, val fs: Float)

/** What to draw for each loop frame, worked out again only when the view or the tiles in memory change. */
internal class SatPlan(private val kind: Int) {
    private var range: TileRange? = null
    private var tick = -1
    private var times: List<Instant> = emptyList()
    var frames: Array<ArrayList<SatPiece>> = emptyArray()
        private set
    var z = 0
        private set

    fun ensure(r: TileRange, t: List<Instant>) {
        val now = SatMem.tick
        if (r == range && now == tick && t === times) return
        range = r
        tick = now
        times = t
        z = r.z
        val newest = t.lastOrNull()
        val complete = BooleanArray(t.size)
        val built = Array(t.size) { f ->
            val list = ArrayList<SatPiece>()
            var all = true
            for (y in r.y0..r.y1) {
                for (x in r.x0..r.x1) {
                    var ok = place(list, t[f], r.z, x, y)
                    // Cloud picture: a tile missing from an older frame borrows the newest frame's.
                    if (!ok && kind == 0 && newest != null && t[f] != newest) ok = place(list, newest, r.z, x, y)
                    if (!ok) all = false
                }
            }
            complete[f] = all
            list
        }
        if (kind == 1) {
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
                built[f] = if (pick >= 0) built[pick] else ArrayList()
            }
        }
        frames = built
    }

    /** 0 = a picture is there (in [out]); 1 = known to have nothing; 2 = not loaded. */
    private fun fetch(frame: Instant, z: Int, x: Int, y: Int): Any? {
        val src = Sat.sourceFor(z, x)
        if (kind == 1 && src.gibs != null) return EMPTY
        val t = Sat.timeFor(src, frame) ?: return null
        val k = SatMem.key(kind, t, z, x, y)
        val img = SatMem.mem.get(k)
        if (img != null) return img
        return if (kind == 1 && k in SatMem.empty) EMPTY else null
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
        if (kind == 0 && z + 1 <= Sat.MAX_ZOOM) {
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
 * Draws the loop at position [pos] (frame index, with a fraction for the fade between two frames).
 * With [fade] off the nearest frame alone is drawn, which suits layers with hard-edged colours.
 */
internal fun DrawScope.drawSat(plan: SatPlan, cam: MapCam, range: TileRange?, times: List<Instant>, pos: Float, alpha: Float, fade: Boolean) {
    if (range == null || times.isEmpty()) return
    plan.ensure(range, times)
    val frames = plan.frames
    if (frames.isEmpty()) return
    val p = pos.coerceIn(0f, (frames.size - 1).toFloat())
    if (!fade) {
        drawPieces(frames[p.roundToInt()], plan.z, cam, alpha)
        return
    }
    val i = floor(p).toInt().coerceIn(0, frames.size - 1)
    val frac = p - i
    // Smoothstep: holds each picture briefly, then fades to the next.
    val f = if (i + 1 < frames.size) frac * frac * (3f - 2f * frac) else 0f
    drawPieces(frames[i], plan.z, cam, alpha * (1f - f))
    if (f > 0.01f) drawPieces(frames[i + 1], plan.z, cam, alpha * f)
}
