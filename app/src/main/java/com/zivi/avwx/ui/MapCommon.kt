package com.zivi.avwx.ui

import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zivi.avwx.data.TileSource
import com.zivi.avwx.data.Tiles
import com.zivi.avwx.wx.Merc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Shared map machinery for the Satellite and Radar tabs: the camera, gestures, and tile loading / drawing.
 * The camera values are only read while drawing, so panning and zooming redraw the canvas
 * without rebuilding the rest of the screen.
 */

internal const val HOME_LON = 73.3
internal const val HOME_LAT = 3.2

/** Map view: centre in Mercator world units (0..1) and scale = width of the whole world in pixels. */
internal class MapCam(cx: Double, cy: Double, scale: Double, private val spanDeg: Double) {
    var cx by mutableDoubleStateOf(cx)
    var cy by mutableDoubleStateOf(cy)
    var scale by mutableDoubleStateOf(scale)
    var size by mutableStateOf(IntSize.Zero)

    /** Height in pixels of the controls lying over the top and bottom of the map. */
    var padTop = 0
    var padBottom = 0

    /** False until the user pans or zooms; until then the view keeps itself framed on the Maldives. */
    var moved = scale > 0.0

    fun onSize(s: IntSize) {
        size = s
        if (!moved) home()
    }

    /** The whole of the Maldives, centred in the part of the map that no control covers. */
    fun home() {
        moved = false
        val h = size.height
        if (h <= 0) return
        val clear = (h - padTop - padBottom).coerceAtLeast(h / 3)
        scale = clear * 360.0 / spanDeg
        cx = Merc.x(HOME_LON)
        cy = Merc.y(HOME_LAT) - (padTop - padBottom) / 2.0 / scale
    }

    companion object {
        fun saver(spanDeg: Double) = listSaver<MapCam, Double>(
            save = { listOf(it.cx, it.cy, it.scale) },
            restore = { MapCam(it[0], it[1], it[2], spanDeg) },
        )
    }
}

/** Pan and pinch-zoom for a [MapCam]. */
internal fun Modifier.mapCamera(cam: MapCam, maxScale: Double = 256.0 * 5800.0): Modifier =
    this.onSizeChanged { cam.onSize(it) }
        .pointerInput(cam) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val w = size.width.toDouble()
                val h = size.height.toDouble()
                val scale = cam.scale
                if (scale > 0.0) {
                    cam.moved = true
                    val minScale = maxOf(w, h) * 1.2
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    // Keep the point under the fingers fixed while zooming.
                    val wx = cam.cx + (centroid.x - w / 2) / scale
                    val wy = cam.cy + (centroid.y - h / 2) / scale
                    val nx = wx - (centroid.x - w / 2) / newScale - pan.x / newScale
                    val ny = wy - (centroid.y - h / 2) / newScale - pan.y / newScale
                    cam.scale = newScale
                    cam.cx = nx.coerceIn(0.0, 1.0)
                    cam.cy = ny.coerceIn(0.1, 0.9)
                }
            }
        }

/** World coordinate (0..1) to a screen pixel along one axis. */
internal fun px(extent: Float, centre: Double, scale: Double, world: Double): Float =
    (extent / 2 + (world - centre) * scale).toFloat()

// ---------------------------------------------------------------- tiles

internal data class TileRange(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int)

internal fun tileRange(cam: MapCam, maxZoom: Int): TileRange? {
    val s = cam.scale
    val w = cam.size.width
    val h = cam.size.height
    if (s <= 0.0 || w == 0 || h == 0) return null
    val z = floor(ln(s / 256.0) / ln(2.0)).toInt().coerceIn(1, maxZoom)
    val n = 1 shl z
    return TileRange(
        z,
        floor((cam.cx - w / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cx + w / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cy - h / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
        floor((cam.cy + h / 2.0 / s) * n).toInt().coerceIn(0, n - 1),
    )
}

/** Tiles kept in memory for the whole app session, shared by both maps. */
internal object TileMem {
    val mem = LruCache<Long, ImageBitmap>(220)
    val inFlight = HashSet<Long>()
    val gates = TileSource.values().associateWith { Semaphore(it.parallel) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var tick by mutableIntStateOf(0)

    fun key(s: TileSource, z: Int, x: Int, y: Int): Long =
        (s.ordinal.toLong() shl 58) or (z.toLong() shl 52) or (x.toLong() shl 26) or y.toLong()
}

/** Downloads the tiles the camera needs. Rebuilds only when the set of tiles changes, not on every pan step. */
@Composable
internal fun TileLoader(source: TileSource, cam: MapCam) {
    val ctx = LocalContext.current.applicationContext
    val range by remember(cam, source) { derivedStateOf { tileRange(cam, source.maxZoom) } }
    val r = range
    LaunchedEffect(source, r) {
        if (r == null || (r.x1 - r.x0 + 1) * (r.y1 - r.y0 + 1) > 80) return@LaunchedEffect
        val gate = TileMem.gates.getValue(source)
        for (ty in r.y0..r.y1) {
            for (tx in r.x0..r.x1) {
                val key = TileMem.key(source, r.z, tx, ty)
                if (TileMem.mem.get(key) == null && TileMem.inFlight.add(key)) {
                    // Runs outside this effect so a download isn't thrown away when the map moves on.
                    TileMem.scope.launch {
                        try {
                            val bmp = gate.withPermit { Tiles.load(ctx, source, r.z, tx, ty) }
                            if (bmp != null) {
                                TileMem.mem.put(key, bmp.asImageBitmap())
                                TileMem.tick++
                            }
                        } finally {
                            TileMem.inFlight.remove(key)
                        }
                    }
                }
            }
        }
    }
}

/** Draws the base map. A tile not yet downloaded is filled from a coarser one already in memory. */
internal fun DrawScope.drawTiles(source: TileSource, cam: MapCam) {
    val r = tileRange(cam, source.maxZoom) ?: return
    if (TileMem.tick < 0) return   // read so the canvas redraws when a tile arrives
    val n = 1 shl r.z
    val s = cam.scale
    val w = size.width
    val h = size.height
    for (ty in r.y0..r.y1) {
        for (tx in r.x0..r.x1) {
            val l = px(w, cam.cx, s, tx.toDouble() / n).roundToInt()
            val t = px(h, cam.cy, s, ty.toDouble() / n).roundToInt()
            val rr = px(w, cam.cx, s, (tx + 1).toDouble() / n).roundToInt()
            val b = px(h, cam.cy, s, (ty + 1).toDouble() / n).roundToInt()
            val dstO = IntOffset(l, t)
            val dstS = IntSize(rr - l, b - t)
            val img = TileMem.mem.get(TileMem.key(source, r.z, tx, ty))
            if (img != null) {
                drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), dstO, dstS, filterQuality = FilterQuality.Low)
            } else {
                var up = 1
                while (up <= 5 && r.z - up >= 0) {
                    val parent = TileMem.mem.get(TileMem.key(source, r.z - up, tx shr up, ty shr up))
                    if (parent != null) {
                        val sub = parent.width shr up
                        if (sub >= 1) {
                            val ox = (tx - ((tx shr up) shl up)) * sub
                            val oy = (ty - ((ty shr up) shl up)) * sub
                            drawImage(parent, IntOffset(ox, oy), IntSize(sub, sub), dstO, dstS, filterQuality = FilterQuality.Low)
                        }
                        break
                    }
                    up++
                }
            }
        }
    }
}

/** White text with a dark outline, readable over map, cloud and satellite imagery alike. */
internal fun DrawScope.drawLabel(paint: Paint, text: String, x: Float, y: Float, centre: Boolean) {
    paint.textAlign = if (centre) Paint.Align.CENTER else Paint.Align.LEFT
    val stroke = 3.dp.toPx()
    drawIntoCanvas { c ->
        paint.color = android.graphics.Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        c.nativeCanvas.drawText(text, x, y, paint)
        paint.color = android.graphics.Color.WHITE
        paint.style = Paint.Style.FILL
        c.nativeCanvas.drawText(text, x, y, paint)
    }
    paint.textAlign = Paint.Align.LEFT
}
