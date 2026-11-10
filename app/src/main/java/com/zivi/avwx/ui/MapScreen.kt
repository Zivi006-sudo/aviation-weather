package com.zivi.avwx.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import com.zivi.avwx.data.TileSource
import com.zivi.avwx.data.SatFrame
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zivi.avwx.data.Overlay
import com.zivi.avwx.data.Sat
import com.zivi.avwx.data.WxGrid
import com.zivi.avwx.wx.Upper
import com.zivi.avwx.wx.snapBox
import com.zivi.avwx.data.SatRequest
import com.zivi.avwx.data.Sigmet
import com.zivi.avwx.data.Tiles
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.GeoBounds
import com.zivi.avwx.wx.Merc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

private const val DWELL_SEC = 1.2f                 // pause on the newest image before the loop restarts
private val SPEEDS = floatArrayOf(1.5f, 3f, 6f)     // images per second
private val SPEED_LABELS = arrayOf("Slow", "Normal", "Fast")
private val SEA = Color(0xFFAAD3DF)

private data class ViewKey(val cx: Double, val cy: Double, val scale: Double, val size: IntSize)

@Composable
fun MapScreen(vm: AppViewModel, now: Long) {
    val haptic = LocalHapticFeedback.current
    val frames = vm.frames
    val images: List<ImageBitmap> = remember(frames) { frames.map { it.bitmap.asImageBitmap() } }
    val rains: List<ImageBitmap?> = remember(frames) { frames.map { it.overlay?.asImageBitmap() } }

    var playing by rememberSaveable { mutableStateOf(false) }
    var speedIdx by rememberSaveable { mutableStateOf(1) }
    // Loop position. Read only by the canvas while drawing and by the timeline panel,
    // so the animation doesn't rebuild the rest of the screen on every frame.
    val pos = remember { mutableFloatStateOf(0f) }
    var showSigmetList by remember { mutableStateOf(false) }
    val cam = rememberSaveable(saver = MapCam.saver(13.0)) { MapCam(Merc.x(HOME_LON), Merc.y(HOME_LAT), 0.0, 13.0) }

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.checkSatUpdate()
                if (vm.showSigmet) vm.loadSigmets()
                delay(3 * 60_000L)
            }
        }
    }

    // When the map stops moving, make sure satellite images cover the new view.
    LaunchedEffect(cam) {
        snapshotFlow { ViewKey(cam.cx, cam.cy, cam.scale, cam.size) }.collectLatest { v ->
            if (v.scale > 0.0 && v.size.width > 0) {
                delay(400)
                vm.requestSatForView(v.cx, v.cy, v.scale, v.size.width, v.size.height)
            }
        }
    }

    // New set of images: stay where the loop is if it is playing, otherwise show the newest.
    LaunchedEffect(frames) {
        val last = (frames.size - 1).coerceAtLeast(0).toFloat()
        pos.floatValue = if (playing) pos.floatValue.coerceAtMost(last) else last
    }

    // Animation clock, tied to the display refresh rate.
    LaunchedEffect(playing, frames.size, speedIdx) {
        if (!playing || frames.size < 2) return@LaunchedEffect
        val last = (frames.size - 1).toFloat()
        val speed = SPEEDS[speedIdx]
        val end = last + DWELL_SEC * speed
        var prev = withFrameNanos { it }
        if (pos.floatValue >= last) pos.floatValue = 0f
        while (true) {
            val t = withFrameNanos { it }
            val dt = ((t - prev) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            prev = t
            var p = pos.floatValue + dt * speed
            if (p >= end) p = 0f
            pos.floatValue = p
        }
    }

    TileLoader(TileSource.MAP, cam)

    val airports = vm.airports
    val decoded = vm.decoded
    val sigmets = if (vm.showSigmet) vm.sigmets else emptyList()
    val grid = vm.grid
    val gridOn = vm.showIcing || vm.showWind
    val gridIdx = remember(grid, vm.gridHour, now / 600_000L) {
        grid?.indexAt(now / 1000 + vm.gridHour * 3600L) ?: 0
    }
    val showIcing = vm.showIcing
    val showWind = vm.showWind
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Box(Modifier.fillMaxSize().background(SEA)) {
        Canvas(Modifier.fillMaxSize().clipToBounds().mapCamera(cam)) {
            val s = cam.scale
            if (s > 0.0) {
                val w = size.width
                val h = size.height
                val ox = cam.cx
                val oy = cam.cy

                drawTiles(TileSource.MAP, cam)

                // ---- satellite, then the extra layer
                if (images.isNotEmpty() && frames.isNotEmpty()) {
                    val bnd = frames[0].bounds
                    val l = px(w, ox, s, Merc.x(bnd.west)).roundToInt()
                    val t = px(h, oy, s, Merc.y(bnd.north)).roundToInt()
                    val r = px(w, ox, s, Merc.x(bnd.east)).roundToInt()
                    val b = px(h, oy, s, Merc.y(bnd.south)).roundToInt()
                    val dstO = IntOffset(l, t)
                    val dstS = IntSize((r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
                    val shownPos = pos.floatValue.coerceIn(0f, (images.size - 1).toFloat())
                    val i = floor(shownPos).toInt().coerceIn(0, images.size - 1)
                    val frac = shownPos - i
                    // Smoothstep: holds each picture briefly, then fades to the next.
                    val f = if (i + 1 < images.size) frac * frac * (3f - 2f * frac) else 0f
                    val a = images[i]
                    drawImage(a, IntOffset.Zero, IntSize(a.width, a.height), dstO, dstS, alpha = 1f - f, filterQuality = FilterQuality.Low)
                    if (f > 0.01f) {
                        val nx = images[i + 1]
                        drawImage(nx, IntOffset.Zero, IntSize(nx.width, nx.height), dstO, dstS, alpha = f, filterQuality = FilterQuality.Low)
                    }
                    val rain = rains.getOrNull(shownPos.roundToInt())
                    if (rain != null) {
                        drawImage(rain, IntOffset.Zero, IntSize(rain.width, rain.height), dstO, dstS, alpha = 0.85f, filterQuality = FilterQuality.Low)
                    }
                }

                // ---- forecast layers: icing cells, then wind arrows
                if (grid != null && gridOn && gridIdx < grid.times.size) {
                    if (showIcing) drawIcing(grid, gridIdx, w, h, ox, oy, s)
                    if (showWind) drawWind(grid, gridIdx, w, h, ox, oy, s, labelPaint)
                }

                // ---- SIGMET areas
                labelPaint.textSize = 11.sp.toPx()
                for (sg in sigmets) {
                    val path = Path()
                    var mx = 0f
                    var my = 0f
                    for (k in sg.coords.indices) {
                        val c = sg.coords[k]
                        val x = px(w, ox, s, Merc.x(c[1]))
                        val y = px(h, oy, s, Merc.y(c[0]))
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        mx += x
                        my += y
                    }
                    path.close()
                    val col = sigmetColour(sg.hazard)
                    drawPath(path, col.copy(alpha = 0.16f))
                    drawPath(path, col, style = Stroke(width = 2.dp.toPx()))
                    mx /= sg.coords.size
                    my /= sg.coords.size
                    if (mx > -50f && my > -50f && mx < w + 50f && my < h + 50f) {
                        drawLabel(labelPaint, "SIGMET " + sg.hazard, mx, my, centre = true)
                    }
                }

                drawGraticule(s, ox, oy, labelPaint)

                // ---- airports, coloured by flight category
                val dot = 5.dp.toPx()
                labelPaint.textSize = 12.sp.toPx()
                for (ap in airports) {
                    val lat = ap.lat
                    val lon = ap.lon
                    if (lat != null && lon != null) {
                        val x = px(w, ox, s, Merc.x(lon))
                        val y = px(h, oy, s, Merc.y(lat))
                        if (x > -40f && y > -40f && x < w + 40f && y < h + 40f) {
                            val cat = decoded[ap.icao]?.metar?.category ?: FlightCat.UNKNOWN
                            drawCircle(Color.Black, radius = dot + 2.dp.toPx(), center = Offset(x, y))
                            drawCircle(catColor(cat), radius = dot, center = Offset(x, y))
                            drawCircle(Color.White, radius = dot, center = Offset(x, y), style = Stroke(width = 1.dp.toPx()))
                            drawLabel(labelPaint, ap.icao, x + dot + 4.dp.toPx(), y + 4.dp.toPx(), centre = false)
                        }
                    }
                }
            }
        }

        // ---- top controls
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(0xE610161D))
                        .clickable(enabled = !vm.satLoading) { vm.reloadSat() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (vm.satLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload images", tint = Color.White)
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Toggle("Rain", vm.overlay == Overlay.RAIN) {
                    vm.updateOverlay(if (vm.overlay == Overlay.RAIN) Overlay.NONE else Overlay.RAIN)
                }
                Toggle("Tops", vm.overlay == Overlay.TOPS) {
                    vm.updateOverlay(if (vm.overlay == Overlay.TOPS) Overlay.NONE else Overlay.TOPS)
                }
                Toggle("Icing", vm.showIcing) { vm.updateShowIcing(!vm.showIcing) }
                Toggle("Wind", vm.showWind) { vm.updateShowWind(!vm.showWind) }
                Toggle(
                    if (vm.showSigmet && vm.sigmetsLoaded) "SIGMET ${vm.sigmets.size}" else "SIGMET",
                    vm.showSigmet,
                ) { vm.updateShowSigmet(!vm.showSigmet) }
                if (vm.showSigmet && vm.sigmets.isNotEmpty()) {
                    Toggle("List", false) { showSigmetList = true }
                }
                Toggle("Maldives", false) { cam.home() }
            }
            if (vm.enhanced) Legend()
            val legend = vm.overlayLegend
            if (vm.overlay != Overlay.NONE && legend != null) {
                Column(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(4.dp)) {
                    Text(
                        if (vm.overlay == Overlay.RAIN) "Rain rate" else "Cloud top height",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    )
                    Image(
                        bitmap = remember(legend) { legend.asImageBitmap() }, contentDescription = "Colour key",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = 130.dp).widthIn(max = 130.dp),
                    )
                }
            }
            if (vm.overlay != Overlay.NONE && vm.overlayMissing) {
                Note((if (vm.overlay == Overlay.RAIN) "The rain layer" else "The cloud top layer") + " isn't available from EUMETSAT right now.")
            }
        }

        // ---- timeline
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            if (gridOn) {
                ForecastPanel(vm, grid, gridIdx)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Map © OpenStreetMap contributors, CARTO · Satellite © EUMETSAT",
                fontSize = 9.sp, color = Color(0xFF1B2530),
                modifier = Modifier.align(Alignment.End).padding(bottom = 4.dp, end = 4.dp),
            )
            Timeline(
                vm, frames, pos, playing, speedIdx, now,
                onPlayPause = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    playing = !playing
                },
                onSpeed = { speedIdx = (speedIdx + 1) % SPEEDS.size },
                onScrub = { playing = false; pos.floatValue = it },
            )
        }
    }

    if (showSigmetList) {
        SigmetDialog(vm.sigmets) { showSigmetList = false }
    }
}

/** The time read-out, play controls and slider. The only part of the screen rebuilt while the loop plays. */
@Composable
private fun Timeline(
    vm: AppViewModel, frames: List<SatFrame>, pos: MutableFloatState, playing: Boolean, speedIdx: Int, now: Long,
    onPlayPause: () -> Unit, onSpeed: () -> Unit, onScrub: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF010161D), RoundedCornerShape(20.dp))
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
            val lastIdx = frames.size - 1
            val shownPos = pos.floatValue.coerceIn(0f, lastIdx.toFloat())
            val nearest = shownPos.roundToInt().coerceIn(0, lastIdx)
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
                            " · " + Fmt.ago(newest.toEpochMilli(), now) +
                            (if (vm.satLoading && vm.satTotal > frames.size) " · loading ${frames.size}/${vm.satTotal}" else ""),
                        fontSize = 12.sp,
                        color = if (ageMin > 45) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    SPEED_LABELS[speedIdx], fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSpeed)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(4.dp))
                PlayPauseButton(playing, enabled = frames.size > 1, onClick = onPlayPause)
            }
            if (frames.size > 1) {
                Slider(
                    value = shownPos,
                    onValueChange = onScrub,
                    onValueChangeFinished = { pos.floatValue = pos.floatValue.roundToInt().toFloat() },
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

private val ICING_COLOURS = arrayOf(Color(0x5538BDF8), Color(0x771E6FD9), Color(0x99B01FB5))

/** Icing risk as tinted cells, one per forecast grid point. */
private fun DrawScope.drawIcing(g: WxGrid, ti: Int, w: Float, h: Float, ox: Double, oy: Double, s: Double) {
    for (i in g.lats.indices) {
        val t = g.temp[i][ti]
        val rh = g.rh[i][ti]
        if (t.isNaN() || rh.isNaN()) continue
        val risk = Upper.icing(t.toDouble(), rh.toDouble())
        if (risk == 0) continue
        val l = px(w, ox, s, Merc.x(g.lons[i] - g.dLon / 2))
        val r = px(w, ox, s, Merc.x(g.lons[i] + g.dLon / 2))
        val top = px(h, oy, s, Merc.y(g.lats[i] + g.dLat / 2))
        val bottom = px(h, oy, s, Merc.y(g.lats[i] - g.dLat / 2))
        if (r < 0f || l > w || bottom < 0f || top > h) continue
        drawRect(ICING_COLOURS[risk - 1], topLeft = Offset(l, top), size = Size(r - l, bottom - top))
    }
}

/** Wind arrows pointing the way the air is moving, with the speed in knots beside each. */
private fun DrawScope.drawWind(g: WxGrid, ti: Int, w: Float, h: Float, ox: Double, oy: Double, s: Double, paint: Paint) {
    val len = 13.dp.toPx()
    val head = 5.dp.toPx()
    val dark = Color(0xFF0B2233)
    paint.textSize = 11.sp.toPx()
    for (i in g.lats.indices) {
        val spd = g.speed[i][ti]
        val dir = g.dir[i][ti]
        if (spd.isNaN() || dir.isNaN()) continue
        val x = px(w, ox, s, Merc.x(g.lons[i]))
        val y = px(h, oy, s, Merc.y(g.lats[i]))
        if (x < -30f || y < -30f || x > w + 30f || y > h + 30f) continue
        val c = Offset(x, y)
        rotate(degrees = dir + 180f, pivot = c) {
            val tail = Offset(x, y + len)
            val tip = Offset(x, y - len)
            for (pass in 0..1) {
                val col = if (pass == 0) Color.White else dark
                val sw = if (pass == 0) 4.5.dp.toPx() else 2.2.dp.toPx()
                drawLine(col, tail, tip, strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(col, tip, Offset(x - head, y - len + head * 1.4f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(col, tip, Offset(x + head, y - len + head * 1.4f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        drawLabel(paint, spd.roundToInt().toString(), x + len * 0.9f, y + len * 1.3f, centre = true)
    }
}

/** Level and forecast-time controls for the icing and wind layers. */
@Composable
private fun ForecastPanel(vm: AppViewModel, grid: WxGrid?, idx: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF010161D), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Upper.label(vm.gridLevel), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06131B),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { vm.updateGridLevel((vm.gridLevel + 1) % Upper.LEVELS_FT.size) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(10.dp))
            val valid = grid?.times?.getOrNull(idx)
            val err = vm.gridError
            Text(
                when {
                    vm.gridLoading -> "Loading forecast…"
                    grid == null && err != null -> err
                    valid != null -> "Forecast " + Fmt.zulu(valid) + " / " + Fmt.local(valid) + " LT" +
                        (if (vm.gridHour == 0) "  (now)" else "  (+${vm.gridHour} h)")
                    else -> "Forecast"
                },
                fontSize = 13.sp, color = if (grid == null && err != null) Amber else Color.White, modifier = Modifier.weight(1f),
            )
        }
        Slider(
            value = vm.gridHour.toFloat(),
            onValueChange = { vm.updateGridHour(it.roundToInt()) },
            valueRange = 0f..24f,
            steps = 23,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tap the level to change it.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (vm.showIcing) {
                Text("Icing:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val names = listOf("light", "mod", "high")
                for (k in 0..2) {
                    Box(Modifier.size(10.dp).background(ICING_COLOURS[k].copy(alpha = 1f), RoundedCornerShape(2.dp)))
                    Text(names[k], fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

private fun sigmetColour(hazard: String): Color = when (hazard.uppercase()) {
    "TS", "TSGR", "CONVECTIVE" -> Color(0xFFE5352B)
    "TURB" -> Color(0xFFE08A00)
    "ICE" -> Color(0xFF1E6FD9)
    "VA" -> Color(0xFF6B4E2E)
    "TC" -> Color(0xFFB01FB5)
    else -> Color(0xFF8A3FD1)
}

private fun DrawScope.drawGraticule(s: Double, cx: Double, cy: Double, paint: Paint) {
    val degPx = s / 360.0
    val step = when {
        degPx > 260 -> 0.5
        degPx > 110 -> 1.0
        degPx > 45 -> 2.0
        degPx > 18 -> 5.0
        else -> 10.0
    }
    val line = Color(0xFF12324A).copy(alpha = 0.22f)
    val lonLeft = Merc.lon(cx - size.width / 2.0 / s)
    val lonRight = Merc.lon(cx + size.width / 2.0 / s)
    val latTop = Merc.lat((cy - size.height / 2.0 / s).coerceIn(0.02, 0.98))
    val latBottom = Merc.lat((cy + size.height / 2.0 / s).coerceIn(0.02, 0.98))
    paint.textSize = 10.sp.toPx()
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.LEFT
    paint.color = Color(0xFF12324A).copy(alpha = 0.8f).toArgb()
    val labelX = 4.dp.toPx()
    val labelDy = 3.dp.toPx()

    var lon = floor(lonLeft / step) * step
    while (lon <= lonRight) {
        val x = px(size.width, cx, s, Merc.x(lon))
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        lon += step
    }
    var lat = floor(latBottom / step) * step
    while (lat <= latTop) {
        val y = px(size.height, cy, s, Merc.y(lat))
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        val label = when {
            lat > 0.01 -> trimDeg(lat) + "°N"
            lat < -0.01 -> trimDeg(-lat) + "°S"
            else -> "EQ"
        }
        drawIntoCanvas { it.nativeCanvas.drawText(label, labelX, y - labelDy, paint) }
        lat += step
    }
}

private fun trimDeg(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.background(Color(0xE610161D), RoundedCornerShape(12.dp)).padding(3.dp)) {
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
internal fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        color = if (on) Color(0xFF06131B) else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) MaterialTheme.colorScheme.primary else Color(0xE610161D))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
internal fun Note(text: String) {
    Text(
        text, fontSize = 12.sp, color = Amber,
        modifier = Modifier.background(Color(0xE610161D), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Legend() {
    val colours = remember { Sat.SCALE.map { Color(0xFF000000.toInt() or it) } }
    Column(
        Modifier
            .width(220.dp)
            .background(Color(0xE610161D), RoundedCornerShape(12.dp))
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

@Composable
private fun SigmetDialog(list: List<Sigmet>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SIGMETs in the region") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (sg in list) {
                    Column {
                        val until = if (sg.to > 0) " · until " + Fmt.zuluDay(Instant.ofEpochSecond(sg.to)) else ""
                        Text(
                            (sg.firName.ifBlank { sg.fir }) + " · " + sg.hazard + until,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        )
                        Text(sg.raw.trim(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
