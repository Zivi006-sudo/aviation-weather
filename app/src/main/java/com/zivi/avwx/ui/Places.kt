package com.zivi.avwx.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zivi.avwx.data.Airport
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.Merc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

/*
 * English place names and every Maldivian airport, drawn by the app over the map.
 * Names come from Natural Earth (public domain), shipped inside the app so they work offline.
 */

// ---------------------------------------------------------------- Maldivian airports

/** All the airports in the Maldives, from the published list. */
internal object MvAirports {
    val all = listOf(
        Airport("VRMM", "Velana Intl (Malé)", 4.1917, 73.5292, "18/36"),
        Airport("VRMG", "Gan Intl", -0.6933, 73.1556, "10/28"),
        Airport("VRMH", "Hanimaadhoo Intl", 6.7442, 73.1703, "03/21"),
        Airport("VRDA", "Maafaru Intl", 5.8222, 73.4747, "18/36"),
        Airport("VRMV", "Villa Intl Maamigili", 3.4708, 72.8361, ""),
        Airport("VRMD", "Dharavandhoo", 5.1558, 73.1328, ""),
        Airport("VRMR", "Fuvahmulah", -0.3092, 73.4339, ""),
        Airport("VRQM", "Maavarulu", 0.3367, 73.5114, ""),
        Airport("VREI", "Ifuru", 5.7081, 73.0250, ""),
        Airport("VRMT", "Kaadedhdhoo", 0.4881, 72.9969, "15/33"),
        Airport("VRMK", "Kadhdhoo", 1.8592, 73.5219, "03/21"),
        Airport("VRMO", "Kooddoo", 0.7342, 73.4328, ""),
        Airport("VRMU", "Dhaalu (Kudahuvadhoo)", 2.6672, 72.8928, ""),
        Airport("VRNT", "Thimarafushi", 2.2103, 73.1514, ""),
        Airport("VRBK", "Kulhudhuffushi", 6.6311, 73.0667, ""),
        Airport("VRGD", "Madivaru", 5.4575, 73.3703, ""),
        Airport("VRCF", "Funadhoo", 6.1611, 73.2872, ""),
        Airport("VRAH", "Hoarafushi", 6.9806, 72.8958, ""),
        Airport("VRQF", "Faresmaathodaa", 0.1929, 73.1958, ""),
        Airport("VRKM", "Muli", 2.9114, 73.5820, ""),
    )

    /** The user's airports first (they keep their colours and always get a label), then the rest. */
    fun merged(mine: List<Airport>): List<Pair<Airport, Boolean>> {
        val out = ArrayList<Pair<Airport, Boolean>>()
        val seen = HashSet<String>()
        for (a in mine) if (a.lat != null && a.lon != null && seen.add(a.icao)) out += a to true
        for (a in all) if (seen.add(a.icao)) out += a to false
        return out
    }
}

/** Draws airport markers. Returns the screen boxes they and their labels take up, for the place names to avoid. */
internal fun DrawScope.drawAirports(
    list: List<Pair<Airport, Boolean>>, decoded: Map<String, Decoded>, cam: MapCam, paint: Paint, out: ArrayList<RectF>,
) {
    out.clear()
    val s = cam.scale
    val w = size.width
    val h = size.height
    val big = 5.dp.toPx()
    val small = 3.5.dp.toPx()
    val gap = 4.dp.toPx()
    val placed = ArrayList<RectF>()
    // Pass 1: your airports, always labelled. Pass 2: the others, labelled only where there is room.
    for (pass in 0..1) {
        for ((a, mine) in list) {
            if (mine != (pass == 0)) continue
            val lat = a.lat ?: continue
            val lon = a.lon ?: continue
            val x = px(w, cam.cx, s, Merc.x(lon))
            val y = px(h, cam.cy, s, Merc.y(lat))
            if (x < -40f || y < -40f || x > w + 40f || y > h + 40f) continue
            val metar = decoded[a.icao]?.metar
            if (mine || metar != null) {
                val cat = metar?.category ?: FlightCat.UNKNOWN
                drawCircle(Color.Black, radius = big + 2.dp.toPx(), center = Offset(x, y))
                drawCircle(catColor(cat), radius = big, center = Offset(x, y))
                drawCircle(Color.White, radius = big, center = Offset(x, y), style = Stroke(width = 1.dp.toPx()))
            } else {
                drawCircle(Color.Black, radius = small + 1.5.dp.toPx(), center = Offset(x, y))
                drawCircle(Color.White, radius = small, center = Offset(x, y))
            }
            val r = if (mine || metar != null) big else small
            out += RectF(x - r - 2, y - r - 2, x + r + 2, y + r + 2)
            paint.textSize = (if (mine) 12 else 10).sp.toPx()
            val tw = paint.measureText(a.icao)
            val lx = x + r + gap
            val box = RectF(lx - 2, y - paint.textSize * 0.75f, lx + tw + 2, y + paint.textSize * 0.4f)
            if (!mine && placed.any { RectF.intersects(it, box) }) continue
            placed += box
            out += box
            drawLabel(paint, a.icao, lx, y + paint.textSize * 0.33f, centre = false)
        }
    }
}

/** The airport marker nearest to a tap, within reach. */
internal fun airportAt(list: List<Pair<Airport, Boolean>>, cam: MapCam, w: Float, h: Float, tx: Float, ty: Float, reach: Float): Airport? {
    val s = cam.scale
    if (s <= 0.0) return null
    var best: Airport? = null
    var bestD = reach * reach
    for ((a, _) in list) {
        val lat = a.lat ?: continue
        val lon = a.lon ?: continue
        val dx = px(w, cam.cx, s, Merc.x(lon)) - tx
        val dy = px(h, cam.cy, s, Merc.y(lat)) - ty
        val d = dx * dx + dy * dy
        if (d < bestD) { bestD = d; best = a }
    }
    return best
}

/** Name, weather and an add button for an airport tapped on a map. */
@Composable
internal fun AirportPeek(vm: AppViewModel, a: Airport, onDismiss: () -> Unit) {
    LaunchedEffect(a.icao) { vm.peek(a.icao) }
    val d = vm.decoded[a.icao]
    val metar = d?.metar
    val inList = vm.airports.any { it.icao == a.icao }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.icao, fontWeight = FontWeight.Bold)
                    Text(a.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CatPill(metar?.category ?: FlightCat.UNKNOWN)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    metar != null -> {
                        Text(metar.raw, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
                        val obs = metar.obsTime
                        if (obs != null) {
                            Text(Fmt.zulu(obs) + " · " + Fmt.ago(obs.toEpochMilli(), System.currentTimeMillis()), fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    vm.peeking == a.icao -> Text("Looking for a report…", fontSize = 14.sp)
                    else -> Text("No METAR published for this airport.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val taf = d?.taf
                if (taf != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(taf.raw, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = {
            if (!inList) {
                TextButton(onClick = { vm.addKnownAirport(a); onDismiss() }) { Text("Add to my airports") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------------------------------------------------------- place names

internal class Place(val kind: Int, val minZoom: Float, val wx: Double, val wy: Double, val pop: Long, val name: String)

internal object Places {
    @Volatile var list: List<Place> = emptyList()
        private set

    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        if (list.isNotEmpty()) return@withContext
        val out = ArrayList<Place>(7500)
        try {
            ctx.assets.open("places.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 6) continue
                    val lat = p[2].toDoubleOrNull() ?: continue
                    val lon = p[3].toDoubleOrNull() ?: continue
                    out += Place(p[0].toInt(), p[1].toFloat(), Merc.x(lon), Merc.y(lat), p[4].toLongOrNull() ?: 0L, p[5])
                }
            }
        } catch (e: Exception) {
            // No names then; the map still works.
        }
        list = out
    }
}

@Composable
internal fun rememberPlaces(): List<Place> {
    val ctx = LocalContext.current.applicationContext
    var places by remember { mutableStateOf(Places.list) }
    LaunchedEffect(Unit) {
        Places.load(ctx)
        places = Places.list
    }
    return places
}

/**
 * Lays out and draws place names. The layout is worked out again only when the map moves,
 * so the satellite loop and wind animation don't pay for it on every frame.
 */
internal class PlaceLabels(private val dark: Boolean) {
    private class Lbl(val text: String, val x: Float, val y: Float, val kind: Int, val big: Boolean, val dotX: Float, val dotY: Float)

    private var kx = Double.NaN
    private var ky = 0.0
    private var ks = 0.0
    private var kw = 0f
    private var kh = 0f
    private var kList: List<Place>? = null
    private val placed = ArrayList<Lbl>()
    private val boxes = ArrayList<RectF>()

    private val country = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val bold = Paint().apply { isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val plain = Paint().apply { isAntiAlias = true; typeface = Typeface.DEFAULT }
    private val halo = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    private val textCol = if (dark) android.graphics.Color.WHITE else 0xFF1E2B38.toInt()
    private val countryCol = if (dark) 0xFFF2F2F2.toInt() else 0xFF4A5A6A.toInt()
    private val haloCol = if (dark) 0xCC000000.toInt() else 0xE6FFFFFF.toInt()

    fun draw(scope: DrawScope, places: List<Place>, cam: MapCam, avoid: List<RectF>) = with(scope) {
        val s = cam.scale
        if (s <= 0.0 || places.isEmpty()) return@with
        val w = size.width
        val h = size.height
        country.textSize = 13.sp.toPx()
        bold.textSize = 12.sp.toPx()
        plain.textSize = 11.sp.toPx()
        halo.strokeWidth = 3.dp.toPx()
        if (cam.cx != kx || cam.cy != ky || s != ks || w != kw || h != kh || places !== kList) {
            kx = cam.cx; ky = cam.cy; ks = s; kw = w; kh = h; kList = places
            layout(places, cam, w, h, avoid)
        }
        val dot = 2.5.dp.toPx()
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for (l in placed) {
                val p = when { l.kind == 0 -> country; l.big -> bold; else -> plain }
                if (l.kind != 0) {
                    halo.color = haloCol
                    halo.style = Paint.Style.FILL
                    nc.drawCircle(l.dotX, l.dotY, dot + 1.5f, halo)
                    halo.style = Paint.Style.STROKE
                    p.color = textCol
                    nc.drawCircle(l.dotX, l.dotY, dot, p)
                }
                p.color = if (l.kind == 0) countryCol else textCol
                halo.color = haloCol
                halo.textSize = p.textSize
                halo.typeface = p.typeface
                halo.letterSpacing = p.letterSpacing
                halo.textAlign = p.textAlign
                nc.drawText(l.text, l.x, l.y, halo)
                nc.drawText(l.text, l.x, l.y, p)
            }
        }
    }

    private fun DrawScope.layout(places: List<Place>, cam: MapCam, w: Float, h: Float, avoid: List<RectF>) {
        placed.clear()
        boxes.clear()
        boxes.addAll(avoid)
        val s = cam.scale
        val z = (ln(s / 256.0) / ln(2.0)).toFloat()
        val left = cam.cx - w / 2.0 / s
        val right = cam.cx + w / 2.0 / s
        val top = cam.cy - h / 2.0 / s
        val bottom = cam.cy + h / 2.0 / s
        val pad = 3.dp.toPx()
        val dotGap = 5.dp.toPx()
        var cities = 0
        for (p in places) {
            if (p.kind == 0) {
                if (p.minZoom > z + 0.6f || z > 8.5f) continue
            } else {
                if (p.minZoom > z + 0.4f || cities >= 45) continue
            }
            if (p.wx < left || p.wx > right || p.wy < top || p.wy > bottom) continue
            val x = px(w, cam.cx, s, p.wx)
            val y = px(h, cam.cy, s, p.wy)
            val big = p.kind == 1 || p.pop >= 1_000_000
            val paint = when { p.kind == 0 -> country; big -> bold; else -> plain }
            val tw = paint.measureText(p.name)
            val th = paint.textSize
            val box: RectF
            val tx: Float
            val ty: Float
            if (p.kind == 0) {
                tx = x - tw / 2
                ty = y + th * 0.35f
                box = RectF(tx - pad, y - th * 0.65f - pad, tx + tw + pad, y + th * 0.45f + pad)
            } else {
                tx = x + dotGap
                ty = y + th * 0.35f
                box = RectF(x - dotGap, y - th * 0.65f - pad, tx + tw + pad, y + th * 0.45f + pad)
            }
            if (box.right < 0 || box.left > w || box.bottom < 0 || box.top > h) continue
            if (boxes.any { RectF.intersects(it, box) }) continue
            boxes += box
            placed += Lbl(p.name, tx, ty, p.kind, big, x, y)
            if (p.kind != 0) cities++
        }
    }
}
