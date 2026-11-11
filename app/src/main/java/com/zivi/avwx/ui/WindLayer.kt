package com.zivi.avwx.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zivi.avwx.data.FcGrid
import com.zivi.avwx.data.Sat
import com.zivi.avwx.wx.Merc
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Wind drawn the way wind maps do it: a colour wash for speed, with short fading lines that drift along the flow.
 */

/** Speed colours, one stop every 5 knots from calm to 60 kt. */
private val WIND_STOPS = intArrayOf(
    0x6271B7, 0x39619F, 0x4A94A9, 0x4D8D7B, 0x53A553, 0x359F35, 0xA79D51,
    0x9F7F3A, 0xA16C5C, 0x94485A, 0x813A4E, 0xA1487A, 0xAF5088,
)

internal fun windColour(kt: Float): Int {
    val p = (kt / 5f).coerceIn(0f, (WIND_STOPS.size - 1).toFloat())
    val i = p.toInt().coerceAtMost(WIND_STOPS.size - 2)
    return Sat.mix(WIND_STOPS[i], WIND_STOPS[i + 1], p - i)
}

internal val WIND_LEGEND: List<Color> = WIND_STOPS.map { Color(0xFF000000.toInt() or it) }

/** The speed wash for one level and hour, as a small picture stretched over the grid's area. */
internal fun windWash(g: FcGrid, level: Int, ti: Int): ImageBitmap {
    val w = g.cols * 5
    val h = g.rows * 5
    val px = IntArray(w * h)
    val out = FloatArray(2)
    val yTop = Merc.y(g.north)
    val yBot = Merc.y(g.south)
    for (j in 0 until h) {
        // Rows are evenly spaced on the map (Mercator), so the picture lines up when stretched.
        val lat = Merc.lat(yTop + (yBot - yTop) * (j + 0.5) / h)
        for (i in 0 until w) {
            val lon = g.west + (g.east - g.west) * (i + 0.5) / w
            px[j * w + i] = if (g.sample(level, ti, lat, lon, out)) {
                (0xFF shl 24) or windColour(sqrt(out[0] * out[0] + out[1] * out[1]))
            } else 0
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

internal fun DrawScope.drawWindWash(img: ImageBitmap, g: FcGrid, cam: MapCam, alpha: Float) {
    val s = cam.scale
    val l = px(size.width, cam.cx, s, Merc.x(g.west)).roundToInt()
    val r = px(size.width, cam.cx, s, Merc.x(g.east)).roundToInt()
    val t = px(size.height, cam.cy, s, Merc.y(g.north)).roundToInt()
    val b = px(size.height, cam.cy, s, Merc.y(g.south)).roundToInt()
    if (r <= l || b <= t) return
    drawImage(
        img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(l, t), IntSize(r - l, b - t),
        alpha = alpha, filterQuality = FilterQuality.Low,
    )
}

private const val TRAIL = 7          // positions remembered per particle
private const val MAX_PARTICLES = 2200
private const val MIN_PARTICLES = 500

/** Everything the animation keeps between frames. Plain arrays, so a frame allocates nothing. */
private class Flow {
    var count = 1500
    val wx = DoubleArray(MAX_PARTICLES)
    val wy = DoubleArray(MAX_PARTICLES)
    val age = IntArray(MAX_PARTICLES)
    val life = IntArray(MAX_PARTICLES)
    val hist = FloatArray(MAX_PARTICLES * TRAIL * 2)
    var head = 0
    val lines = Array(TRAIL - 1) { FloatArray(MAX_PARTICLES * 4) }
    val used = IntArray(TRAIL - 1)
    val sample = FloatArray(2)
    val rnd = Random(7)
    var camX = Double.NaN
    var camY = 0.0
    var camS = 0.0
    var lastNanos = 0L
    var slowFrames = 0
    var fastFrames = 0
    var seeded = false
    val paints = Array(TRAIL - 1) { k ->
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = android.graphics.Color.WHITE
            alpha = (235 * (1f - k / (TRAIL - 1f))).toInt().coerceIn(20, 255)
        }
    }
}

/**
 * The moving wind lines. Drawn in a layer of its own, so each animation frame redraws only these lines
 * and not the map underneath. The number of lines drops by itself if the phone can't keep up.
 */
@Composable
internal fun WindParticles(cam: MapCam, grid: FcGrid, level: Int, ti: Int, modifier: Modifier = Modifier) {
    val flow = remember { Flow() }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame.longValue = it }
    }
    Canvas(modifier.graphicsLayer()) {
        val nanos = frame.longValue
        val s = cam.scale
        if (s <= 0.0 || ti >= grid.times.size) return@Canvas
        val w = size.width
        val h = size.height
        val f = flow

        var dt = if (f.lastNanos == 0L) 0.016f else ((nanos - f.lastNanos) / 1_000_000_000.0).toFloat()
        f.lastNanos = nanos
        // Fewer lines when frames run long, a few more again when there is time to spare.
        if (dt > 0.024f) { f.slowFrames++; f.fastFrames = 0 } else if (dt < 0.018f) { f.fastFrames++; f.slowFrames = 0 }
        if (f.slowFrames > 20) { f.count = (f.count * 3 / 4).coerceAtLeast(MIN_PARTICLES); f.slowFrames = 0 }
        if (f.fastFrames > 240) { f.count = (f.count + 100).coerceAtMost(MAX_PARTICLES); f.fastFrames = 0 }
        dt = dt.coerceIn(0.004f, 0.05f)

        val moved = cam.cx != f.camX || cam.cy != f.camY || s != f.camS
        f.camX = cam.cx
        f.camY = cam.cy
        f.camS = s

        val pxPerKt = 2.1.dp.toPx()          // screen distance per second for each knot
        val n = f.count
        f.head = (f.head + 1) % TRAIL
        val head = f.head
        val out = f.sample
        for (k in f.used.indices) f.used[k] = 0

        for (i in 0 until n) {
            var respawn = !f.seeded || f.age[i] >= f.life[i]
            var x = 0f
            var y = 0f
            if (!respawn) {
                val lat = Merc.lat(f.wy[i])
                val lon = Merc.lon(f.wx[i])
                if (grid.sample(level, ti, lat, lon, out)) {
                    f.wx[i] += out[0] * pxPerKt * dt / s
                    f.wy[i] -= out[1] * pxPerKt * dt / s
                    x = px(w, cam.cx, s, f.wx[i])
                    y = px(h, cam.cy, s, f.wy[i])
                    if (x < -20f || y < -20f || x > w + 20f || y > h + 20f) respawn = true
                } else respawn = true
            }
            val base = i * TRAIL * 2
            if (respawn) {
                f.wx[i] = cam.cx + (f.rnd.nextDouble() - 0.5) * w / s
                f.wy[i] = cam.cy + (f.rnd.nextDouble() - 0.5) * h / s
                f.age[i] = 0
                f.life[i] = 50 + f.rnd.nextInt(110)
                x = px(w, cam.cx, s, f.wx[i])
                y = px(h, cam.cy, s, f.wy[i])
                for (k in 0 until TRAIL) { f.hist[base + k * 2] = x; f.hist[base + k * 2 + 1] = y }
                continue
            }
            f.age[i]++
            if (moved) {
                // The map moved: old trail points no longer line up, so the trail starts again from here.
                for (k in 0 until TRAIL) { f.hist[base + k * 2] = x; f.hist[base + k * 2 + 1] = y }
                continue
            }
            f.hist[base + head * 2] = x
            f.hist[base + head * 2 + 1] = y
            // Newest segment first, each older one fainter.
            for (k in 0 until TRAIL - 1) {
                val a = base + ((head - k + TRAIL) % TRAIL) * 2
                val b = base + ((head - k - 1 + TRAIL * 2) % TRAIL) * 2
                val arr = f.lines[k]
                val u = f.used[k]
                arr[u] = f.hist[a]; arr[u + 1] = f.hist[a + 1]; arr[u + 2] = f.hist[b]; arr[u + 3] = f.hist[b + 1]
                f.used[k] = u + 4
            }
        }
        f.seeded = true

        val stroke = 1.6.dp.toPx()
        drawIntoCanvas { c ->
            for (k in f.used.indices) {
                if (f.used[k] == 0) continue
                f.paints[k].strokeWidth = stroke
                c.nativeCanvas.drawLines(f.lines[k], 0, f.used[k], f.paints[k])
            }
        }
    }
}
