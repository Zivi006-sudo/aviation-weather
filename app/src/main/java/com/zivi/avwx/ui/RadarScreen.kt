package com.zivi.avwx.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zivi.avwx.data.Aircraft
import com.zivi.avwx.data.TileSource
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.Merc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val RADAR_SPAN_DEG = 9.5      // whole Maldives top to bottom
private const val POLL_MS = 10_000L

/** Aircraft outline pointing north, in a box about 22 units tall. */
private val PLANE: Path = Path().apply {
    moveTo(0f, -11f)
    lineTo(1.8f, -4f); lineTo(10f, 2.5f); lineTo(10f, 4.8f); lineTo(1.8f, 2.4f)
    lineTo(1.4f, 8f); lineTo(4.2f, 10f); lineTo(4.2f, 11.4f); lineTo(0f, 10.2f)
    lineTo(-4.2f, 11.4f); lineTo(-4.2f, 10f); lineTo(-1.4f, 8f); lineTo(-1.8f, 2.4f)
    lineTo(-10f, 4.8f); lineTo(-10f, 2.5f); lineTo(-1.8f, -4f)
    close()
}

private fun altColour(a: Aircraft): Color {
    val alt = a.altFt
    return when {
        a.onGround || alt == null -> Color(0xFFB8C2CC)
        alt < 5000 -> Color(0xFF4ADE80)
        alt < 12000 -> Color(0xFFFACC15)
        alt < 20000 -> Color(0xFFFB923C)
        alt < 30000 -> Color(0xFFF472B6)
        else -> Color(0xFFC084FC)
    }
}

@Composable
fun RadarScreen(vm: AppViewModel, now: Long) {
    val cam = rememberSaveable(saver = MapCam.saver(RADAR_SPAN_DEG)) {
        MapCam(Merc.x(HOME_LON), Merc.y(HOME_LAT), 0.0, RADAR_SPAN_DEG)
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showCloud by rememberSaveable { mutableStateOf(false) }
    var showLabels by rememberSaveable { mutableStateOf(true) }

    // Traffic is fetched only while this tab is on screen.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val s = cam.scale
                if (s > 0.0 && cam.size.width > 0) {
                    val lat = Merc.lat(cam.cy)
                    val lon = Merc.lon(cam.cx)
                    val halfLatDeg = (Merc.lat(cam.cy - cam.size.height / 2.0 / s) - Merc.lat(cam.cy + cam.size.height / 2.0 / s)) / 2
                    val halfLonDeg = cam.size.width / s * 360.0 / 2
                    val a = halfLatDeg * 60
                    val b = halfLonDeg * 60 * cos(Math.toRadians(lat))
                    vm.refreshRadar(lat, lon, sqrt(a * a + b * b).roundToInt().coerceIn(25, 250))
                    delay(POLL_MS)
                } else {
                    delay(200)
                }
            }
        }
    }

    // Cloud overlay: the newest satellite picture, loaded as tiles for this view.
    val cloudR by remember(cam) { derivedStateOf { satRange(cam, 1) } }
    LaunchedEffect(cam, showCloud) {
        if (!showCloud) return@LaunchedEffect
        snapshotFlow { cloudR }.collectLatest { r ->
            if (r != null) {
                delay(150)
                vm.satWant(r, loop = false)
            }
        }
    }
    val cloudPlan = remember { SatPlan(0) }
    val cloudTimes = vm.satTimes

    TileLoader(TileSource.SAT, cam)

    val aircraft = vm.aircraft
    val airports = vm.airports
    val decoded = vm.decoded
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A2233))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .mapCamera(cam, maxScale = 256.0 * 11000.0)
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val s = cam.scale
                        if (s <= 0.0) return@detectTapGestures
                        val reach = 30.dp.toPx()
                        var best: String? = null
                        var bestD = reach * reach
                        for (a in vm.aircraft) {
                            val dx = px(size.width.toFloat(), cam.cx, s, Merc.x(a.lon)) - p.x
                            val dy = px(size.height.toFloat(), cam.cy, s, Merc.y(a.lat)) - p.y
                            val d = dx * dx + dy * dy
                            if (d < bestD) { bestD = d; best = a.hex }
                        }
                        selected = best
                    }
                }
        ) {
            val s = cam.scale
            if (s > 0.0) {
                val w = size.width
                val h = size.height
                val ox = cam.cx
                val oy = cam.cy

                drawTiles(TileSource.SAT, cam)

                if (showCloud && cloudTimes.isNotEmpty()) {
                    drawSat(cloudPlan, cam, cloudR, cloudTimes, (cloudTimes.size - 1).toFloat(), 0.8f, fade = false)
                }

                // ---- airports
                val dot = 4.dp.toPx()
                labelPaint.textSize = 11.sp.toPx()
                for (ap in airports) {
                    val lat = ap.lat
                    val lon = ap.lon
                    if (lat != null && lon != null) {
                        val x = px(w, ox, s, Merc.x(lon))
                        val y = px(h, oy, s, Merc.y(lat))
                        if (x > -40f && y > -40f && x < w + 40f && y < h + 40f) {
                            val cat = decoded[ap.icao]?.metar?.category ?: FlightCat.UNKNOWN
                            drawCircle(Color.Black, radius = dot + 1.5.dp.toPx(), center = Offset(x, y))
                            drawCircle(catColor(cat), radius = dot, center = Offset(x, y))
                            drawLabel(labelPaint, ap.icao, x + dot + 4.dp.toPx(), y - dot - 2.dp.toPx(), centre = false)
                        }
                    }
                }

                // ---- trail of the selected aircraft
                val sel = selected
                if (sel != null) {
                    val trail = vm.trails[sel]
                    if (trail != null && trail.size >= 2) {
                        val path = Path()
                        for (i in trail.indices) {
                            val x = px(w, ox, s, Merc.x(trail[i][1]))
                            val y = px(h, oy, s, Merc.y(trail[i][0]))
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                        drawPath(path, Color(0xFF5CC8FF), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }

                // ---- aircraft
                val k = 1.05.dp.toPx()
                val outline = Stroke(width = 1.3f)
                for (a in aircraft) {
                    val x = px(w, ox, s, Merc.x(a.lon))
                    val y = px(h, oy, s, Merc.y(a.lat))
                    if (x < -30f || y < -30f || x > w + 30f || y > h + 30f) continue
                    val isSel = a.hex == sel
                    if (isSel) drawCircle(Color.White, radius = 17.dp.toPx(), center = Offset(x, y), style = Stroke(width = 2.dp.toPx()))
                    withTransform({
                        translate(x, y)
                        rotate(degrees = (a.track ?: 0.0).toFloat(), pivot = Offset.Zero)
                        scale(k, k, pivot = Offset.Zero)
                    }) {
                        drawPath(PLANE, altColour(a))
                        drawPath(PLANE, Color.Black, style = outline)
                    }
                    if (showLabels || isSel) {
                        drawLabel(labelPaint, a.label, x + 13.dp.toPx(), y + 4.dp.toPx(), centre = false)
                    }
                }
            }
        }

        // ---- top controls
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("Labels", showLabels) { showLabels = !showLabels }
                Toggle("Cloud", showCloud) { showCloud = !showCloud }
                Spacer(Modifier.weight(1f))
                Toggle("Maldives", false) { cam.home() }
            }
            val err = vm.radarError
            Text(
                when {
                    vm.radarAt == 0L && err == null -> "Looking for aircraft…"
                    vm.radarAt == 0L -> err ?: ""
                    else -> "${aircraft.size} aircraft · updated " + Fmt.ago(vm.radarAt, now) + (if (err != null) " · update failed" else "")
                },
                fontSize = 12.sp, color = if (err != null) Amber else Color.White,
                modifier = Modifier.background(Color(0xE610161D), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (vm.radarAt != 0L && aircraft.isEmpty() && err == null) {
                Note("No aircraft received here. Coverage depends on volunteer receivers, so traffic can be missing.")
            }
        }

        // ---- selected aircraft and credits
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            val a = selected?.let { id -> aircraft.firstOrNull { it.hex == id } }
            if (selected != null) {
                AircraftCard(a, onClose = { selected = null })
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Traffic: airplanes.live / adsb.lol · Imagery: Sentinel-2 cloudless – s2maps.eu by EOX IT Services GmbH " +
                    "(contains modified Copernicus Sentinel data 2024)",
                fontSize = 9.sp, lineHeight = 11.sp, color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun AircraftCard(a: Aircraft?, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF010161D), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    a?.label ?: "Out of range", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = Color.White,
                )
                if (a != null) {
                    val parts = listOf(a.type, a.reg).filter { it.isNotEmpty() && it != a.label }
                    if (parts.isNotEmpty()) {
                        Text(parts.joinToString("  ·  "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("This aircraft is no longer being received.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Close", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(10.dp),
            )
        }
        if (a != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val alt = a.altFt
                Fact("ALTITUDE", when {
                    a.onGround -> "Ground"
                    alt == null -> "—"
                    alt >= 10000 -> "FL%03d".format(alt / 100)
                    else -> "%,d ft".format(alt)
                })
                Fact("SPEED", a.speedKt?.let { "${it.roundToInt()} kt" } ?: "—")
                Fact("TRACK", a.track?.let { "%03d°".format(it.roundToInt() % 360) } ?: "—")
                val vs = a.vsFpm
                Fact("V/S", when {
                    vs == null -> "—"
                    vs > 100 -> "+$vs"
                    vs < -100 -> vs.toString()
                    else -> "Level"
                })
                Fact("SQUAWK", a.squawk.ifEmpty { "—" })
            }
            if (a.seenSec > 30) {
                Spacer(Modifier.height(6.dp))
                Text("Position is ${a.seenSec.roundToInt()} s old.", fontSize = 12.sp, color = Amber)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(0.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
