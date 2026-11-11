package com.zivi.avwx.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.graphics.RectF
import com.zivi.avwx.data.Airport
import com.zivi.avwx.data.FcGrid
import com.zivi.avwx.data.Overlay
import com.zivi.avwx.data.Sat
import com.zivi.avwx.data.Sigmet
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.Merc
import com.zivi.avwx.wx.Upper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val DWELL_SEC = 2f                   // pause on the newest image before the loop restarts
private val SPEEDS = floatArrayOf(0.8f, 1.5f, 3f)   // images per second
private val SPEED_LABELS = arrayOf("Slow", "Normal", "Fast")
private val SEA = Color(0xFFAAD3DF)
private val PANEL = Color(0xF010161D)

private data class ViewKey(val cx: Double, val cy: Double, val scale: Double, val size: IntSize)

@Composable
fun MapScreen(vm: AppViewModel, now: Long) {
    val haptic = LocalHapticFeedback.current
    val times = vm.satTimes

    var playing by rememberSaveable { mutableStateOf(false) }
    var speedIdx by rememberSaveable { mutableStateOf(1) }
    // Loop position. Read only by the canvas while drawing and by the timeline panel,
    // so the animation doesn't rebuild the rest of the screen on every frame.
    val pos = remember { mutableFloatStateOf(0f) }
    var showSigmetList by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<DoubleArray?>(null) }       // tapped point: latitude, longitude
    var tapped by remember { mutableStateOf<Airport?>(null) }
    val cam = rememberSaveable(saver = MapCam.saver(13.0)) { MapCam(Merc.x(HOME_LON), Merc.y(HOME_LAT), 0.0, 13.0) }

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.checkSatUpdate()
                if (vm.showSigmet) vm.loadSigmets()
                delay(2 * 60_000L)
            }
        }
    }

    // Satellite tiles for the view. Changes only when the view crosses into new tiles, not on every pan step.
    val satR by remember(cam) { derivedStateOf { satView(cam) } }
    LaunchedEffect(cam) {
        snapshotFlow { satR }.collectLatest { r ->
            if (r != null) {
                delay(300)
                vm.satWant(r, loop = true)
            }
        }
    }

    // Forecast layers: tell the view model which area is on screen once the map settles.
    val gridOn = vm.showIcing || vm.showWind
    LaunchedEffect(cam, gridOn) {
        if (!gridOn) return@LaunchedEffect
        snapshotFlow { ViewKey(cam.cx, cam.cy, cam.scale, cam.size) }.collectLatest { v ->
            if (v.scale > 0.0 && v.size.width > 0) {
                delay(500)
                vm.wantGrid(v.cx, v.cy, v.scale, v.size.width, v.size.height)
            }
        }
    }

    // The loop moved on by a frame: keep showing the same picture, or the newest if that was on screen.
    val prevTimes = remember { arrayOf<List<Instant>>(emptyList()) }
    LaunchedEffect(times) {
        val old = prevTimes[0]
        prevTimes[0] = times
        val last = (times.size - 1).coerceAtLeast(0).toFloat()
        val p = pos.floatValue
        pos.floatValue = when {
            playing -> p.coerceAtMost(last)
            old.isEmpty() || p >= old.size - 1 -> last
            else -> {
                val i = times.indexOf(old[p.roundToInt().coerceIn(0, old.size - 1)])
                if (i >= 0) i.toFloat() else last
            }
        }
    }

    // Animation clock, tied to the display refresh rate.
    LaunchedEffect(playing, times.size, speedIdx) {
        if (!playing || times.size < 2) return@LaunchedEffect
        val last = (times.size - 1).toFloat()
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

    val baseMap = remember { BaseMapLink() }

    val apBoxes = remember { ArrayList<RectF>() }
    val allAirports = remember(vm.airports) { MvAirports.merged(vm.airports) }
    val irPlan = remember { SatPlan() }
    val ovPlan = remember { SatPlan() }
    val airports = vm.airports
    val decoded = vm.decoded
    val sigmets = if (vm.showSigmet) vm.sigmets else emptyList()
    val overlayOn = vm.overlay != Overlay.NONE
    val level = vm.gridLevel
    val showIcing = vm.showIcing
    val showWind = vm.showWind
    val arrows = vm.windAsArrows
    val windGrid = if (showWind) vm.windGrid else null
    val iceGrid = if (showIcing) vm.iceGrid else null
    val wantSec = now / 1000 / 600 * 600 + vm.gridHour * 3600L
    val windIdx = remember(windGrid, wantSec) { windGrid?.indexAt(wantSec) ?: 0 }
    val iceIdx = remember(iceGrid, wantSec) { iceGrid?.indexAt(wantSec) ?: 0 }
    val wash = remember(windGrid, level, windIdx, arrows) {
        if (windGrid != null && !arrows && windIdx < windGrid.times.size) windWash(windGrid, level, windIdx) else null
    }
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Box(Modifier.fillMaxSize().background(SEA)) {
        BaseMap(baseMap, Modifier.fillMaxSize())
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer()
                .mapCamera(cam)
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val s = cam.scale
                        val ap = airportAt(allAirports, cam, size.width.toFloat(), size.height.toFloat(), p.x, p.y, 24.dp.toPx())
                        if (ap != null) {
                            tapped = ap
                        } else if (s > 0.0 && (vm.showWind || vm.showIcing)) {
                            val lat = Merc.lat(cam.cy + (p.y - size.height / 2.0) / s)
                            val lon = Merc.lon(cam.cx + (p.x - size.width / 2.0) / s)
                            tip = doubleArrayOf(lat, lon)
                        }
                    }
                }
        ) {
            val s = cam.scale
            if (s > 0.0) {
                val w = size.width
                val h = size.height
                val ox = cam.cx
                val oy = cam.cy

                baseMap.sync(cam, density)

                // ---- wind speed wash, under the cloud
                if (wash != null && windGrid != null) drawWindWash(wash, windGrid, cam, 0.62f)

                // ---- satellite, then the extra layer
                val p = pos.floatValue
                drawSat(irPlan, cam, satR, times, vm.cloudKind, p, if (wash != null) 0.8f else 1f, fade = true)
                val ok = vm.overlayKind
                if (overlayOn && ok >= 0) drawSat(ovPlan, cam, satR, times, ok, p, 0.9f, fade = false)

                // ---- forecast layers
                if (iceGrid != null && level > 0 && iceIdx < iceGrid.times.size) drawIcing(iceGrid, level, iceIdx, cam)
                if (windGrid != null && arrows && windIdx < windGrid.times.size) drawWindArrows(windGrid, level, windIdx, cam, labelPaint)

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

                // ---- airports (all Maldivian ones, yours in colour), then English place names around them
                drawAirports(allAirports, decoded, cam, labelPaint, apBoxes)

                // ---- the point tapped for a forecast read-out
                val tp = tip
                if (tp != null && (showWind || showIcing)) {
                    val x = px(w, ox, s, Merc.x(tp[1]))
                    val y = px(h, oy, s, Merc.y(tp[0]))
                    drawCircle(Color.Black, radius = 7.dp.toPx(), center = Offset(x, y), style = Stroke(width = 4.dp.toPx()))
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, y), style = Stroke(width = 2.dp.toPx()))
                }
            }
        }

        if (windGrid != null && !arrows && windIdx < windGrid.times.size) {
            WindParticles(cam, windGrid, level, windIdx, Modifier.fillMaxSize())
        }

        // ---- top controls
        Column(
            Modifier.fillMaxWidth().onSizeChanged { cam.padTop = it.height; if (!cam.moved) cam.home() }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                Toggle("Wind", vm.showWind) { vm.updateShowWind(!vm.showWind) }
                Toggle("Icing", vm.showIcing) { vm.updateShowIcing(!vm.showIcing) }
                Toggle("Rain", vm.overlay == Overlay.RAIN) {
                    vm.updateOverlay(if (vm.overlay == Overlay.RAIN) Overlay.NONE else Overlay.RAIN)
                }
                Toggle("Tops", vm.overlay == Overlay.TOPS) {
                    vm.updateOverlay(if (vm.overlay == Overlay.TOPS) Overlay.NONE else Overlay.TOPS)
                }
                Toggle(
                    if (vm.showSigmet && vm.sigmetsLoaded) "SIGMET ${vm.sigmets.size}" else "SIGMET",
                    vm.showSigmet,
                ) { vm.updateShowSigmet(!vm.showSigmet) }
                if (vm.showSigmet && vm.sigmets.isNotEmpty()) {
                    Toggle("List", false) { showSigmetList = true }
                }
                Toggle("Maldives", false) { cam.home() }
            }
            val prog = vm.satProgress
            if (prog >= 0f && !vm.satLoading) {
                LinearProgressIndicator(
                    progress = { prog },
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                    color = Color(0xFF4FC3F7),
                    trackColor = Color(0x33FFFFFF),
                )
            }
            if (overlayOn && vm.overlayMissing && !vm.satLoading) {
                Note((if (vm.overlay == Overlay.RAIN) "The rain layer" else "The cloud top layer") + " isn't available from EUMETSAT right now.")
            }
        }

        // ---- bottom panels: forecast controls, then the satellite timeline with its colour keys
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { cam.padBottom = it.height; if (!cam.moved) cam.home() }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (gridOn) {
                ForecastPanel(vm, vm.windGrid, vm.iceGrid, windIdx, iceIdx, tip)
                Spacer(Modifier.height(6.dp))
            }
            Timeline(
                vm, times, pos, playing, speedIdx, now,
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
    val tap = tapped
    if (tap != null) AirportPeek(vm, tap) { tapped = null }
}

/** The time read-out, play controls, slider and colour key. The only part of the screen rebuilt while the loop plays. */
@Composable
private fun Timeline(
    vm: AppViewModel, times: List<Instant>, pos: MutableFloatState, playing: Boolean, speedIdx: Int, now: Long,
    onPlayPause: () -> Unit, onSpeed: () -> Unit, onScrub: (Float) -> Unit,
) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(vm.satNewAt) {
        if (vm.satNewAt > 0L) {
            flash = true
            delay(8000)
            flash = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(PANEL, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (times.isEmpty()) {
            val err = vm.satError
            Text(
                err ?: "Loading satellite images…",
                fontSize = 14.sp, color = if (err != null) Amber else MaterialTheme.colorScheme.onSurface,
            )
            if (err == null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            val lastIdx = times.size - 1
            val shownPos = pos.floatValue.coerceIn(0f, lastIdx.toFloat())
            val nearest = shownPos.roundToInt().coerceIn(0, lastIdx)
            val shown = times[nearest]
            val newest = times[lastIdx]
            val ageMin = ((now - newest.toEpochMilli()) / 60000L).coerceAtLeast(0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Fmt.zulu(shown) + "  /  " + Fmt.local(shown) + " LT",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = Color.White,
                    )
                    Text(
                        if (flash) "New image " + Fmt.zulu(newest)
                        else (if (nearest == lastIdx) "Latest image" else "Latest image: " + Fmt.zulu(newest)) +
                            " · " + Fmt.ago(newest.toEpochMilli(), now),
                        fontSize = 12.sp, maxLines = 1,
                        fontWeight = if (flash) FontWeight.Bold else FontWeight.Normal,
                        color = if (flash) MaterialTheme.colorScheme.primary
                        else if (ageMin > 45) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
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
                PlayPauseButton(playing, enabled = times.size > 1, onClick = onPlayPause)
            }
            if (times.size > 1) {
                Slider(
                    value = shownPos,
                    onValueChange = onScrub,
                    onValueChangeFinished = { pos.floatValue = pos.floatValue.roundToInt().toFloat() },
                    valueRange = 0f..lastIdx.toFloat(),
                    steps = (times.size - 2).coerceAtLeast(0),
                    modifier = Modifier.height(36.dp),
                )
            }
            val err = vm.satError
            if (err != null) {
                Text(err, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 2.dp))
            }
            val ovTo = vm.overlayTo
            if (vm.overlay != Overlay.NONE && ovTo != null && shown.isAfter(ovTo)) {
                Text(
                    (if (vm.overlay == Overlay.RAIN) "Rain" else "Cloud tops") + " to " + Fmt.zulu(ovTo) + " · newer not out yet",
                    fontSize = 12.sp, color = Amber, modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        // ---- one colour key at a time, kept here so it never covers the map
        val legend = vm.overlayLegend
        if (vm.overlay == Overlay.TOPS) {
            val colours = remember { Upper.TOPS_SCALE.map { Color(0xFF000000.toInt() or it) } }
            KeyBar("Cloud tops", colours, "FL${vm.topsFl}", "FL500")
        } else if (vm.overlay == Overlay.RAIN) {
            if (legend != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rain rate", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
                    Image(
                        bitmap = remember(legend) { legend.asImageBitmap() }, contentDescription = "Rain colour key",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.background(Color.White, RoundedCornerShape(4.dp)).padding(2.dp).heightIn(max = 40.dp).widthIn(max = 220.dp),
                    )
                }
            }
        } else if (vm.enhanced) {
            val colours = remember { listOf(Color(0xFFC8C8C8)) + Sat.SCALE.map { Color(0xFF000000.toInt() or it) } }
            KeyBar("Cloud", colours, "low", "coldest tops / deep CB")
        }
        Text(
            "Map © OpenFreeMap, OpenMapTiles, OpenStreetMap · Satellite © EUMETSAT, NASA GIBS · Forecast: Open-Meteo",
            fontSize = 8.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A labelled colour bar with a caption at each end. */
@Composable
private fun KeyBar(title: String, colours: List<Color>, low: String, high: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(colours)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(low, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
                Text(high, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

// ---------------------------------------------------------------- forecast layers

private val ICING_COLOURS = arrayOf(Color(0x5538BDF8), Color(0x771E6FD9), Color(0x99B01FB5))

/** Icing risk as tinted cells, one per forecast grid point. */
private fun DrawScope.drawIcing(g: FcGrid, level: Int, ti: Int, cam: MapCam) {
    val s = cam.scale
    val w = size.width
    val h = size.height
    val temp = g.a[level][ti]
    val rh = g.b[level][ti]
    for (r in 0 until g.rows) {
        val top = px(h, cam.cy, s, Merc.y(g.north - r * g.dLat))
        val bottom = px(h, cam.cy, s, Merc.y(g.north - (r + 1) * g.dLat))
        if (bottom < 0f || top > h) continue
        for (c in 0 until g.cols) {
            val i = r * g.cols + c
            if (temp[i].isNaN() || rh[i].isNaN()) continue
            val risk = Upper.icing(temp[i].toDouble(), rh[i].toDouble())
            if (risk == 0) continue
            val l = px(w, cam.cx, s, Merc.x(g.west + c * g.dLon))
            val rr = px(w, cam.cx, s, Merc.x(g.west + (c + 1) * g.dLon))
            if (rr < 0f || l > w) continue
            drawRect(ICING_COLOURS[risk - 1], topLeft = Offset(l, top), size = Size(rr - l, bottom - top))
        }
    }
}

/** Wind arrows on an even pattern across the screen, pointing the way the air is moving, with the speed in knots. */
private fun DrawScope.drawWindArrows(g: FcGrid, level: Int, ti: Int, cam: MapCam, paint: Paint) {
    val s = cam.scale
    val step = 62.dp.toPx()
    val len = 12.dp.toPx()
    val head = 5.dp.toPx()
    val dark = Color(0xFF0B2233)
    val out = FloatArray(2)
    paint.textSize = 11.sp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            val lat = Merc.lat(cam.cy + (y - size.height / 2.0) / s)
            val lon = Merc.lon(cam.cx + (x - size.width / 2.0) / s)
            if (g.sample(level, ti, lat, lon, out)) {
                val spd = sqrt(out[0] * out[0] + out[1] * out[1])
                val towards = Math.toDegrees(atan2(out[0].toDouble(), out[1].toDouble())).toFloat()
                val c = Offset(x, y)
                rotate(degrees = towards, pivot = c) {
                    val tail = Offset(x, y + len)
                    val tipPt = Offset(x, y - len)
                    for (pass in 0..1) {
                        val col = if (pass == 0) Color.White else dark
                        val sw = if (pass == 0) 4.5.dp.toPx() else 2.2.dp.toPx()
                        drawLine(col, tail, tipPt, strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(col, tipPt, Offset(x - head, y - len + head * 1.4f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(col, tipPt, Offset(x + head, y - len + head * 1.4f), strokeWidth = sw, cap = StrokeCap.Round)
                    }
                }
                drawLabel(paint, spd.roundToInt().toString(), x + len, y + len * 1.4f, centre = true)
            }
            x += step
        }
        y += step
    }
}

/** Level and forecast-time controls for the wind and icing layers, with the read-out for a tapped point. */
@Composable
private fun ForecastPanel(vm: AppViewModel, wind: FcGrid?, ice: FcGrid?, windIdx: Int, iceIdx: Int, tip: DoubleArray?) {
    val level = vm.gridLevel
    Column(
        Modifier
            .fillMaxWidth()
            .background(PANEL, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Upper.GRID_LABELS.forEachIndexed { i, label ->
                val on = i == level
                Text(
                    label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Color(0xFF06131B) else Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color(0xFF1B242F))
                        .clickable { vm.updateGridLevel(i) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        val any = (if (vm.showWind) wind else null) ?: (if (vm.showIcing) ice else null)
        val idx = if (vm.showWind && wind != null) windIdx else iceIdx
        val valid = any?.times?.getOrNull(idx)?.let { Instant.ofEpochSecond(it) }
        val err = vm.gridError
        Text(
            when {
                vm.gridLoading -> "Loading forecast…"
                any == null && err != null -> err
                valid != null -> "Forecast " + Fmt.zulu(valid) + " / " + Fmt.local(valid) + " LT" +
                    (if (vm.gridHour == 0) "  (now)" else "  (+${vm.gridHour} h)")
                else -> "Forecast"
            },
            fontSize = 13.sp, color = if (any == null && err != null) Amber else Color.White,
            modifier = Modifier.padding(top = 6.dp),
        )
        Slider(
            value = vm.gridHour.toFloat(),
            onValueChange = { vm.updateGridHour(it.roundToInt()) },
            valueRange = 0f..48f,
            steps = 47,
        )

        // ---- read-out for the tapped point
        val out = remember { FloatArray(2) }
        val parts = ArrayList<String>()
        if (tip != null) {
            if (vm.showWind && wind != null && windIdx < wind.times.size && wind.sample(level, windIdx, tip[0], tip[1], out)) {
                val spd = sqrt(out[0] * out[0] + out[1] * out[1])
                val from = ((Math.toDegrees(atan2(-out[0].toDouble(), -out[1].toDouble())) + 360.0) % 360.0).roundToInt() % 360
                parts += if (spd < 1f) "Wind calm" else "Wind %03d° / %d kt".format(from, spd.roundToInt())
            }
            if (vm.showIcing && ice != null && level > 0 && iceIdx < ice.times.size && ice.sample(level, iceIdx, tip[0], tip[1], out)) {
                parts += "%+d°C".format(out[0].roundToInt()) + " · icing " + Upper.riskWord(Upper.icing(out[0].toDouble(), out[1].toDouble()))
            }
        }
        val hasParts = parts.isNotEmpty()
        Text(
            when {
                hasParts -> parts.joinToString("   ·   ")
                vm.showIcing && !vm.showWind && level == 0 -> "Icing isn't shown at the surface. Pick a level above."
                else -> "Tap the map for the forecast at that point."
            },
            fontSize = if (hasParts) 15.sp else 11.sp,
            fontWeight = if (hasParts) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (hasParts) FontFamily.Monospace else FontFamily.Default,
            color = if (hasParts) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.showWind && !vm.windAsArrows) {
            Spacer(Modifier.height(6.dp))
            KeyBar("Wind", WIND_LEGEND, "calm", "60 kt")
        }
        if (vm.showIcing) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Icing", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(58.dp))
                val names = listOf("light", "moderate", "high")
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
