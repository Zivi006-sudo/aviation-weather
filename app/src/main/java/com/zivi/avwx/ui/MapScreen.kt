package com.zivi.avwx.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zivi.avwx.data.Sat
import com.zivi.avwx.wx.FlightCat
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

private const val DWELL_SEC = 1.2f           // pause on the newest image before the loop restarts
private val SPEEDS = floatArrayOf(1.5f, 3f, 6f)   // images per second
private val SPEED_LABELS = arrayOf("Slow", "Normal", "Fast")

@Composable
fun MapScreen(vm: AppViewModel, now: Long) {
    val frames = vm.frames
    val images: List<ImageBitmap> = remember(frames) { frames.map { it.bitmap.asImageBitmap() } }
    val coastBmp = vm.coast
    val coast: ImageBitmap? = remember(coastBmp) { coastBmp?.asImageBitmap() }

    var playing by rememberSaveable { mutableStateOf(false) }
    var speedIdx by rememberSaveable { mutableStateOf(1) }
    var pos by remember { mutableFloatStateOf(0f) }

    // Map view: centre in degrees and scale in pixels per degree. Starts over the Maldives.
    var cLon by rememberSaveable { mutableStateOf(73.3f) }
    var cLat by rememberSaveable { mutableStateOf(3.2f) }
    var scale by rememberSaveable { mutableStateOf(0f) }

    // Load images when the screen opens and look for a newer one every 3 minutes.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.checkSatUpdate()
                delay(3 * 60_000L)
            }
        }
    }

    // New set of images: jump to the newest.
    LaunchedEffect(frames) {
        pos = (frames.size - 1).coerceAtLeast(0).toFloat()
    }

    // Animation clock, tied to the display refresh rate.
    LaunchedEffect(playing, frames.size, speedIdx) {
        if (!playing || frames.size < 2) return@LaunchedEffect
        val last = (frames.size - 1).toFloat()
        val speed = SPEEDS[speedIdx]
        val end = last + DWELL_SEC * speed
        var prev = withFrameNanos { it }
        if (pos >= last) pos = 0f
        while (true) {
            val t = withFrameNanos { it }
            val dt = ((t - prev) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            prev = t
            var p = pos + dt * speed
            if (p >= end) p = 0f
            pos = p
        }
    }

    val lastIdx = (frames.size - 1).coerceAtLeast(0)
    val shownPos = pos.coerceIn(0f, lastIdx.toFloat())
    val nearest = shownPos.roundToInt().coerceIn(0, lastIdx)

    val airports = vm.airports
    val decoded = vm.decoded
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05080B))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { if (scale <= 0f) scale = it.height / 13f }   // about 13 degrees of latitude top to bottom
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (scale <= 0f) return@detectTransformGestures
                        val minScale = maxOf(w, h) / (Sat.LON_MAX - Sat.LON_MIN).toFloat()
                        val newScale = (scale * zoom).coerceIn(minScale, minScale * 24f)
                        // Keep the point under the fingers fixed while zooming.
                        val lonAt = cLon + (centroid.x - w / 2f) / scale
                        val latAt = cLat - (centroid.y - h / 2f) / scale
                        var lon = lonAt - (centroid.x - w / 2f) / newScale
                        var lat = latAt + (centroid.y - h / 2f) / newScale
                        lon -= pan.x / newScale
                        lat += pan.y / newScale
                        scale = newScale
                        cLon = lon.coerceIn(Sat.LON_MIN.toFloat(), Sat.LON_MAX.toFloat())
                        cLat = lat.coerceIn(Sat.LAT_MIN.toFloat(), Sat.LAT_MAX.toFloat())
                    }
                }
        ) {
            val s = if (scale > 0f) scale else size.height / 13f
            val left = size.width / 2f + (Sat.LON_MIN.toFloat() - cLon) * s
            val top = size.height / 2f - (Sat.LAT_MAX.toFloat() - cLat) * s
            val span = ((Sat.LON_MAX - Sat.LON_MIN).toFloat() * s)
            val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
            val dstSize = IntSize(span.roundToInt(), span.roundToInt())

            if (images.isNotEmpty()) {
                val i = floor(shownPos).toInt().coerceIn(0, images.size - 1)
                val frac = shownPos - i
                val a = images[i]
                drawImage(
                    a, IntOffset.Zero, IntSize(a.width, a.height), dstOffset, dstSize,
                    filterQuality = FilterQuality.Low,
                )
                if (frac > 0.01f && i + 1 < images.size) {
                    val b = images[i + 1]
                    // Smoothstep: holds each picture briefly, then fades to the next.
                    val f = frac * frac * (3f - 2f * frac)
                    drawImage(
                        b, IntOffset.Zero, IntSize(b.width, b.height), dstOffset, dstSize,
                        alpha = f, filterQuality = FilterQuality.Low,
                    )
                }
            }
            if (coast != null) {
                drawImage(
                    coast, IntOffset.Zero, IntSize(coast.width, coast.height), dstOffset, dstSize,
                    alpha = 0.8f, filterQuality = FilterQuality.Low,
                )
            }
            drawGraticule(s, cLon, cLat, labelPaint)

            // Airports, coloured by flight category.
            val dot = 5.dp.toPx()
            labelPaint.textSize = 12.sp.toPx()
            for (ap in airports) {
                val lat = ap.lat ?: continue
                val lon = ap.lon ?: continue
                val x = size.width / 2f + (lon.toFloat() - cLon) * s
                val y = size.height / 2f - (lat.toFloat() - cLat) * s
                if (x < -40f || y < -40f || x > size.width + 40f || y > size.height + 40f) continue
                val cat = decoded[ap.icao]?.metar?.category ?: FlightCat.UNKNOWN
                drawCircle(Color.Black, radius = dot + 2.dp.toPx(), center = Offset(x, y))
                drawCircle(catColor(cat), radius = dot, center = Offset(x, y))
                drawCircle(Color.White, radius = dot, center = Offset(x, y), style = Stroke(width = 1.dp.toPx()))
                drawIntoCanvas { c ->
                    labelPaint.color = android.graphics.Color.BLACK
                    labelPaint.style = Paint.Style.STROKE
                    labelPaint.strokeWidth = 3.dp.toPx()
                    c.nativeCanvas.drawText(ap.icao, x + dot + 4.dp.toPx(), y + 4.dp.toPx(), labelPaint)
                    labelPaint.color = android.graphics.Color.WHITE
                    labelPaint.style = Paint.Style.FILL
                    c.nativeCanvas.drawText(ap.icao, x + dot + 4.dp.toPx(), y + 4.dp.toPx(), labelPaint)
                }
            }
        }

        // ---- top controls
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(
                    options = listOf("Colour", "IR"),
                    selected = if (vm.enhanced) 0 else 1,
                    onSelect = { vm.updateEnhanced(it == 0) },
                )
                Segmented(
                    options = listOf("1 h", "2 h", "3 h"),
                    selected = vm.loopHours - 1,
                    onSelect = { vm.updateLoopHours(it + 1) },
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).background(Color(0xCC10161D), CircleShape), contentAlignment = Alignment.Center) {
                    if (vm.satLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { vm.loadSat() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload images", tint = Color.White)
                        }
                    }
                }
            }
            if (vm.enhanced) {
                Spacer(Modifier.height(8.dp))
                Legend()
            }
        }

        // ---- timeline
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color(0xE610161D), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (frames.isEmpty()) {
                val err = vm.satError
                Text(
                    err ?: if (vm.satTotal > 0) "Loading satellite images… ${vm.satLoaded} of ${vm.satTotal}" else "Loading satellite images…",
                    fontSize = 14.sp, color = if (err != null) Amber else MaterialTheme.colorScheme.onSurface,
                )
                if (err == null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                val shown = frames[nearest].time
                val newest = frames[lastIdx].time
                val ageMin = ((now - newest.toEpochMilli()) / 60000L).coerceAtLeast(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Fmt.zulu(shown) + "  /  " + Fmt.local(shown) + " LT",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            color = Color.White,
                        )
                        Text(
                            (if (nearest == lastIdx) "Latest image" else "Latest image: " + Fmt.zulu(newest)) +
                                " · " + Fmt.ago(newest.toEpochMilli(), now),
                            fontSize = 12.sp,
                            color = if (ageMin > 45) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        SPEED_LABELS[speedIdx], fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { speedIdx = (speedIdx + 1) % SPEEDS.size }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    PlayPauseButton(playing, enabled = frames.size > 1) { playing = !playing }
                }
                if (frames.size > 1) {
                    Slider(
                        value = shownPos,
                        onValueChange = { playing = false; pos = it },
                        onValueChangeFinished = { pos = pos.roundToInt().toFloat() },
                        valueRange = 0f..lastIdx.toFloat(),
                        steps = (frames.size - 2).coerceAtLeast(0),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Fmt.zulu(frames.first().time), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${frames.size} images · every 15 min", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Fmt.zulu(newest), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val err = vm.satError
                if (err != null) {
                    Text(err, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

private fun DrawScope.drawGraticule(s: Float, cLon: Float, cLat: Float, paint: Paint) {
    val stepDeg = when {
        s > 260f -> 0.5f
        s > 110f -> 1f
        s > 45f -> 2f
        else -> 5f
    }
    val line = Color.White.copy(alpha = 0.13f)
    val lonLeft = cLon - size.width / 2f / s
    val lonRight = cLon + size.width / 2f / s
    val latBottom = cLat - size.height / 2f / s
    val latTop = cLat + size.height / 2f / s
    paint.textSize = 10.sp.toPx()
    paint.style = Paint.Style.FILL
    paint.color = Color.White.copy(alpha = 0.55f).toArgb()

    var lon = floor(lonLeft / stepDeg) * stepDeg
    while (lon <= lonRight) {
        val x = size.width / 2f + (lon - cLon) * s
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        lon += stepDeg
    }
    var lat = floor(latBottom / stepDeg) * stepDeg
    while (lat <= latTop) {
        val y = size.height / 2f - (lat - cLat) * s
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        val label = when {
            lat > 0.01f -> trimDeg(lat) + "°N"
            lat < -0.01f -> trimDeg(-lat) + "°S"
            else -> "EQ"
        }
        drawIntoCanvas { it.nativeCanvas.drawText(label, 4.dp.toPx(), y - 3.dp.toPx(), paint) }
        lat += stepDeg
    }
}

private fun trimDeg(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.background(Color(0xCC10161D), RoundedCornerShape(12.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                color = if (on) Color(0xFF06131B) else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Legend() {
    val colours = remember { Sat.SCALE.map { Color(0xFF000000.toInt() or it) } }
    Column(
        Modifier
            .width(220.dp)
            .background(Color(0xCC10161D), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(colours))
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cloud tops: colder", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
            Text("coldest / deep CB", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            Canvas(Modifier.size(18.dp)) {
                val barW = size.width * 0.3f
                drawRect(Color(0xFF06131B), topLeft = Offset(size.width * 0.12f, 0f), size = Size(barW, size.height))
                drawRect(Color(0xFF06131B), topLeft = Offset(size.width * 0.58f, 0f), size = Size(barW, size.height))
            }
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color(0xFF06131B), modifier = Modifier.size(28.dp))
        }
    }
}
