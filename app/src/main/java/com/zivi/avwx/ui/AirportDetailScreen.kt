package com.zivi.avwx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zivi.avwx.data.Airport
import com.zivi.avwx.wx.Conditions
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.Metar
import com.zivi.avwx.wx.RunwayWind
import com.zivi.avwx.wx.Taf
import com.zivi.avwx.wx.TafGroup
import com.zivi.avwx.wx.TafKind
import com.zivi.avwx.wx.Wind
import com.zivi.avwx.wx.describeClouds
import com.zivi.avwx.wx.describeVis
import com.zivi.avwx.wx.describeWind
import com.zivi.avwx.wx.describeWx
import com.zivi.avwx.wx.parseRunways
import com.zivi.avwx.wx.runwayWind
import com.zivi.avwx.wx.shortCloud
import com.zivi.avwx.wx.shortVis
import com.zivi.avwx.wx.shortWind
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AirportDetailScreen(vm: AppViewModel, airport: Airport, now: Long, onBack: () -> Unit) {
    val d = vm.decoded[airport.icao]
    val metar = d?.metar
    val taf = d?.taf
    var editRunways by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(airport.icao, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                if (airport.name.isNotBlank() && airport.name != airport.icao) {
                    Text(airport.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CatPill(metar?.category ?: FlightCat.UNKNOWN)
            IconButton(onClick = { confirmRemove = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove airport")
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            if (d != null && metar == null && taf == null) {
                Section("Limited reporting") {
                    Text(
                        "No METAR or TAF is published for ${airport.icao}. Many smaller airports don't report. " +
                            "Use the satellite map and a nearby reporting airport for the picture.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (metar != null) MetarSection(metar, now)
            if (metar != null || airport.runways.isNotBlank()) {
                RunwaySection(airport, metar?.cond?.wind, onEdit = { editRunways = true })
            }
            if (taf != null) TafSection(taf, now)
            else if (metar != null) {
                Section("TAF") {
                    Text("No TAF published for this airport.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (editRunways) {
        RunwayDialog(
            current = airport.runways,
            onDismiss = { editRunways = false },
            onSave = { vm.setRunways(airport.icao, it); editRunways = false },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${airport.icao}?") },
            text = { Text("It will be taken off your airport list. You can add it back any time.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onBack(); vm.removeAirport(airport.icao) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(18.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun RawText(raw: String) {
    Text(
        raw, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1016), RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(
            value, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = if (highlight) Amber else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetarSection(m: Metar, now: Long) {
    val obs = m.obsTime
    Section(
        title = if (m.speci) "SPECI" else "METAR",
        trailing = {
            if (obs != null) {
                val ageMin = (now - obs.toEpochMilli()) / 60000L
                Text(
                    Fmt.zulu(obs) + " · " + Fmt.local(obs) + " LT · " + Fmt.ago(obs.toEpochMilli(), now),
                    fontSize = 12.sp, color = if (ageMin > 90) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        RawText(m.raw)
        Spacer(Modifier.height(8.dp))
        val c = m.cond
        InfoRow("Wind", describeWind(c.wind))
        InfoRow("Visibility", describeVis(c))
        if (c.wx.isNotEmpty()) InfoRow("Weather", c.wx.joinToString(", ") { describeWx(it) }, highlight = c.hasTs)
        InfoRow("Cloud", describeClouds(c), highlight = c.hasCb)
        val ceil = c.ceilingFt
        InfoRow("Ceiling", if (ceil != null) "$ceil ft" else "None")
        if (m.tempC != null) {
            InfoRow("Temp / dew", "${m.tempC}°C / " + (m.dewC?.let { "$it°C" } ?: "—"))
        }
        if (m.qnhHpa != null) InfoRow("QNH", "${m.qnhHpa} hPa")
        if (m.trend != null) InfoRow("Trend", m.trend)
    }
}

// ---------------------------------------------------------------- runway wind

@Composable
private fun RunwaySection(airport: Airport, wind: Wind?, onEdit: () -> Unit) {
    val runways = remember(airport.runways) { parseRunways(airport.runways) }
    Section(
        title = "Runway wind",
        trailing = { TextButton(onClick = onEdit) { Text(if (runways.isEmpty()) "Set runway" else "RWY ${airport.runways}") } },
    ) {
        if (runways.isEmpty()) {
            Text(
                "Enter the runway (for example 18/36) to see headwind and crosswind.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }
        if (wind == null) {
            Text("No wind reported.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunwayDiagram(runways.first().second, runways.first().first, runways.getOrNull(1)?.first, wind)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wind.dir == null) {
                    Text(
                        if (wind.speedKt == 0) "Wind calm." else "Wind variable at ${wind.speedKt} kt. Any of it could be crosswind or tailwind.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    for (rw in runways) {
                        val steady = runwayWind(rw.first, rw.second, wind)
                        val gust = if (wind.gustKt != null) runwayWind(rw.first, rw.second, wind, useGust = true) else null
                        if (steady != null) RunwayEndText(steady, gust)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Runway heading is taken from the runway number (±5°). METAR wind is in degrees true.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunwayEndText(steady: RunwayWind, gust: RunwayWind?) {
    Column {
        Text("RWY ${steady.runway}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        val headLabel = if (steady.headwindKt >= 0) "Headwind" else "Tailwind"
        val headGust = if (gust != null) " (G" + abs(gust.headwindKt) + ")" else ""
        Text(
            headLabel + " " + abs(steady.headwindKt) + headGust + " kt", fontSize = 14.sp,
            color = if (steady.headwindKt < 0) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val side = if (steady.crosswindKt == 0) "" else if (steady.fromRight) " from right" else " from left"
        val xGust = if (gust != null) " (G" + gust.crosswindKt + ")" else ""
        Text(
            "Crosswind " + steady.crosswindKt + xGust + " kt" + side, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunwayDiagram(headingDeg: Int, identA: String, identB: String?, wind: Wind) {
    val line = MaterialTheme.colorScheme.outline
    val rwyColor = Color(0xFF3A4656)
    val arrow = MaterialTheme.colorScheme.primary
    val box = 132.dp
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            drawCircle(line, radius = r - 1f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            // Runway strip: drawn pointing north, then rotated to its heading.
            rotate(degrees = headingDeg.toFloat(), pivot = c) {
                drawLine(rwyColor, Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y + r * 0.62f), strokeWidth = r * 0.2f)
                drawLine(Color.White.copy(alpha = 0.7f), Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y + r * 0.5f), strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            // Wind arrow: from the wind direction towards the centre.
            val dir = wind.dir
            if (dir != null && wind.speedKt > 0) {
                rotate(degrees = dir.toFloat(), pivot = c) {
                    val top = Offset(c.x, c.y - r * 0.95f)
                    val tip = Offset(c.x, c.y - r * 0.28f)
                    drawLine(arrow, top, tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(arrow, tip, Offset(tip.x - r * 0.11f, tip.y - r * 0.17f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(arrow, tip, Offset(tip.x + r * 0.11f, tip.y - r * 0.17f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        // Runway numbers sit at the end you land towards: ident A is at the far (reciprocal) end of the strip.
        val rad = Math.toRadians(headingDeg.toDouble())
        val dist = 50f
        val dx = (sin(rad) * dist).toFloat()
        val dy = (-cos(rad) * dist).toFloat()
        Text(
            identA, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.offset(x = (-dx).dp, y = (-dy).dp),
        )
        if (identB != null) {
            Text(
                identB, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.offset(x = dx.dp, y = dy.dp),
            )
        }
    }
}

@Composable
private fun RunwayDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runway") },
        text = {
            Column {
                Text("Enter the runway numbers, like 18/36.", fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase().take(9) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- TAF

@Composable
private fun TafSection(taf: Taf, now: Long) {
    val nowInst = Instant.ofEpochMilli(now)
    val issued = taf.issued
    Section(
        title = if (taf.amended) "TAF (amended)" else "TAF",
        trailing = {
            if (issued != null) {
                Text(
                    "Issued " + Fmt.zuluDay(issued) + " · " + Fmt.ago(issued.toEpochMilli(), now),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        RawText(taf.raw)
        val to = taf.validTo
        if (to != null && to.isBefore(nowInst)) {
            Spacer(Modifier.height(8.dp))
            Text("This TAF has expired.", fontSize = 13.sp, color = Amber, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in taf.groups) TafGroupRow(g, nowInst)
        }
    }
}

@Composable
private fun TafGroupRow(g: TafGroup, now: Instant) {
    val from = g.from
    val to = g.to
    val active = from != null && to != null && !now.isBefore(from) && now.isBefore(to)
    val past = to != null && !to.isAfter(now)
    val shape = RoundedCornerShape(12.dp)
    val alpha = if (past) 0.45f else 1f
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1016), shape)
            .then(if (active) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CatPill(g.category)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    g.label + if (active) "  ·  now" else "",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                Text(timeWindow(g), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        Spacer(Modifier.height(8.dp))
        val e = g.effective
        Text(
            summary(e), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        val changed = changedParts(g)
        if (changed.isNotEmpty()) {
            Text(
                changed, fontSize = 12.sp,
                color = (if (g.effective.hasTs || g.change.hasCb) Amber else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
            )
        }
    }
}

private fun timeWindow(g: TafGroup): String {
    val f = g.from
    val t = g.to
    return when {
        f != null && t != null && g.kind == TafKind.FM -> "from " + Fmt.zuluDayHour(f) + "  (" + Fmt.local(f) + " LT)"
        f != null && t != null ->
            Fmt.zuluDayHour(f) + " – " + Fmt.zuluDayHour(t) + "  (" + Fmt.local(f) + "–" + Fmt.local(t) + " LT)"
        f != null -> "from " + Fmt.zuluDayHour(f)
        else -> ""
    }
}

private fun summary(c: Conditions): String {
    val parts = ArrayList<String>()
    parts += shortWind(c.wind)
    parts += shortVis(c)
    if (c.wx.isNotEmpty()) parts += c.wx.joinToString(" ")
    parts += shortCloud(c)
    return parts.joinToString("  ·  ")
}

/** For change groups: says in words what the group changes. */
private fun changedParts(g: TafGroup): String {
    if (g.kind == TafKind.BASE || g.kind == TafKind.FM) {
        return if (g.effective.wx.isNotEmpty()) g.effective.wx.joinToString(", ") { describeWx(it) } else ""
    }
    val c = g.change
    val parts = ArrayList<String>()
    if (c.wind != null) parts += "wind " + describeWind(c.wind)
    if (c.visM != null && !c.cavok) parts += "visibility " + describeVis(c)
    if (c.cavok) parts += "CAVOK"
    if (c.wx.isNotEmpty()) parts += c.wx.joinToString(", ") { describeWx(it).lowercase() }
    if (c.nsw) parts += "weather clearing"
    if (c.clouds.isNotEmpty()) parts += describeClouds(c).lowercase()
    return parts.joinToString("; ").replaceFirstChar { it.uppercase() }
}
