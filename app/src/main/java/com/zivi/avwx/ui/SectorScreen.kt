package com.zivi.avwx.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zivi.avwx.wx.Conditions
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.Minima
import com.zivi.avwx.wx.Sun
import com.zivi.avwx.wx.Upper
import com.zivi.avwx.wx.TafGroup
import com.zivi.avwx.wx.TafKind
import com.zivi.avwx.wx.belowMinima
import com.zivi.avwx.wx.parseUtcTime
import com.zivi.avwx.wx.shareText
import com.zivi.avwx.wx.shortCloud
import com.zivi.avwx.wx.shortVis
import com.zivi.avwx.wx.shortWind
import com.zivi.avwx.wx.tafWindow
import java.time.Instant

fun shareOut(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share weather").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun condLine(c: Conditions): String {
    val parts = ArrayList<String>()
    parts += shortWind(c.wind)
    parts += shortVis(c)
    if (c.wx.isNotEmpty()) parts += c.wx.joinToString(" ")
    parts += shortCloud(c)
    return parts.joinToString("  ·  ")
}

@Composable
fun SectorScreen(vm: AppViewModel, now: Long) {
    val ctx = LocalContext.current
    val nowInst = Instant.ofEpochMilli(now)
    val etd = parseUtcTime(vm.sectorEtd, nowInst)
    val eta = parseUtcTime(vm.sectorEta, nowInst)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Sector", "Weather at your departure and arrival times") {
            IconButton(onClick = {
                val sb = StringBuilder()
                for (id in listOf(vm.sectorDep, vm.sectorDest, vm.sectorAltn)) {
                    if (id.length != 4) continue
                    val d = vm.decoded[id]
                    sb.append(shareText(id, vm.airports.firstOrNull { it.icao == id }?.name, d?.metar, d?.taf)).append("\n\n")
                }
                if (sb.isNotEmpty()) shareOut(ctx, sb.toString().trim())
            }) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Flight") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodeField("From", vm.sectorDep, Modifier.weight(1f)) { vm.updateSector(dep = it) }
                    CodeField("To", vm.sectorDest, Modifier.weight(1f)) { vm.updateSector(dest = it) }
                    CodeField("Alternate", vm.sectorAltn, Modifier.weight(1f)) { vm.updateSector(altn = it) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("ETD (UTC)", vm.sectorEtd, Modifier.weight(1f)) { vm.updateSector(etd = it) }
                    TimeField("ETA (UTC)", vm.sectorEta, Modifier.weight(1f)) { vm.updateSector(eta = it) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Times are 4 digits, like 0730. The forecast is checked from 1 hour before to 1 hour after each time. " +
                        "Leave a time empty to use now.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (vm.sectorDep.length != 4 && vm.sectorDest.length != 4) {
                Text(
                    "Enter the ICAO codes for your sector to see the weather at each end.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
            if (vm.sectorDep.length == 4) StationCard(vm, "Departure", vm.sectorDep, etd ?: nowInst, null, nowInst)
            if (vm.sectorDest.length == 4) StationCard(vm, "Destination", vm.sectorDest, eta ?: nowInst, vm.destMinima, nowInst)
            if (vm.sectorAltn.length == 4) StationCard(vm, "Alternate", vm.sectorAltn, eta ?: nowInst, vm.altnMinima, nowInst)
            if (listOf(vm.sectorDep, vm.sectorDest, vm.sectorAltn).any { it.startsWith("VR") }) NotamSection()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CodeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.uppercase().filter { it.isLetterOrDigit() }.take(4)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    )
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun StationCard(vm: AppViewModel, role: String, icao: String, at: Instant, minima: Minima?, now: Instant) {
    val d = vm.decoded[icao]
    val metar = d?.metar
    val taf = d?.taf
    val window = taf?.let { tafWindow(it, at.minusSeconds(3600), at.plusSeconds(3600)) }
    val soon = kotlin.math.abs(at.epochSecond - now.epochSecond) <= 3600
    val where = vm.positionOf(icao)
    LaunchedEffect(icao, where?.get(0), where?.get(1)) {
        if (where != null) vm.loadAloft(icao, where[0], where[1])
    }
    val al = vm.aloft[icao]

    Section("$role · $icao", trailing = {
        Text(
            Fmt.zulu(at) + " · " + Fmt.local(at) + " LT",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }) {
        if (d == null) {
            Text("Waiting for weather…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // ---- verdict line
            val cats = ArrayList<FlightCat>()
            if (window != null && window.covered) cats += window.worst
            if (metar != null && (soon || window == null || !window.covered)) cats += metar.category
            val worst = com.zivi.avwx.wx.worstOf(cats)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CatPill(worst)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        window != null && window.covered -> "Worst forecast around this time"
                        metar != null -> "Latest METAR only"
                        else -> "No weather reported"
                    },
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                )
            }

            // ---- minima
            if (minima != null) {
                val conds = ArrayList<Conditions>()
                if (window != null && window.covered) window.all.forEach { conds += it.effective }
                if (metar != null && (soon || conds.isEmpty())) conds += metar.cond
                if (conds.isNotEmpty()) {
                    val below = conds.any { belowMinima(it, minima) }
                    Spacer(Modifier.height(8.dp))
                    Flag(
                        if (below) "Below your minima (${minima.ceilingFt} ft / ${minima.visM} m) at some point"
                        else "At or above your minima (${minima.ceilingFt} ft / ${minima.visM} m)",
                        if (below) Color(0xFFFF4D4D) else Color(0xFF34C759),
                    )
                }
            }
            if (window != null && (window.thunder || window.cb)) {
                Spacer(Modifier.height(6.dp))
                Flag(if (window.thunder) "Thunderstorms forecast in this period" else "CB / TCU forecast in this period", Amber)
            } else if (metar != null && soon && (metar.cond.hasTs || metar.cond.hasCb)) {
                Spacer(Modifier.height(6.dp))
                Flag(if (metar.cond.hasTs) "Thunderstorm reported now" else "CB / TCU reported now", Amber)
            }

            // ---- METAR
            if (metar != null) {
                Spacer(Modifier.height(12.dp))
                val obs = metar.obsTime
                SmallHead("METAR" + (obs?.let { " " + Fmt.zulu(it) + " · " + Fmt.ago(it.toEpochMilli(), now.toEpochMilli()) } ?: ""), metar.category)
                Text(condLine(metar.cond), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            // ---- TAF around the time
            Spacer(Modifier.height(12.dp))
            when {
                taf == null -> Text("No TAF published for $icao.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                window == null || !window.covered ->
                    Text("The current TAF doesn't cover this time.", fontSize = 13.sp, color = Amber)
                else -> {
                    val p = window.prevailing
                    if (p != null) {
                        SmallHead("TAF · main forecast", p.category)
                        Text(condLine(p.effective), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    for (g in window.changes) {
                        Spacer(Modifier.height(8.dp))
                        SmallHead(changeLabel(g), g.category)
                        Text(condLine(g.effective), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (window.changes.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("No change groups in this period.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- upper air at this time
            if (al != null) {
                val i = al.indexAt(at.epochSecond)
                val near = kotlin.math.abs(al.times[i].epochSecond - at.epochSecond) <= 5400
                if (near) {
                    Spacer(Modifier.height(12.dp))
                    SmallHead("Upper air (model forecast)", FlightCat.UNKNOWN)
                    val ts = al.thunder(i)
                    Text(
                        "Freezing level " + Upper.freezingLabel(al.freezingFt.getOrNull(i)) +
                            "  ·  Thunderstorm risk " + Upper.thunderWord(ts).lowercase(),
                        fontSize = 14.sp, color = if (ts > 0) Amber else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Icing: " + (al.icingBand(i) ?: "none at FL240 and below"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Turbulence: " + (al.turbulenceBand(i) ?: "none indicated"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // ---- daylight
            val pos = vm.positionOf(icao)
            if (pos != null) {
                val sun = Sun.times(pos[0], pos[1], at)
                val rise = sun.sunrise
                val set = sun.sunset
                if (rise != null && set != null) {
                    Spacer(Modifier.height(12.dp))
                    val day = Sun.isDay(pos[0], pos[1], at)
                    Text(
                        (if (day) "Daylight at this time" else "Night at this time") +
                            "  ·  sunrise " + Fmt.local(rise) + "  ·  sunset " + Fmt.local(set) + " LT",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun changeLabel(g: TafGroup): String {
    val f = g.from
    val t = g.to
    val time = when {
        g.kind == TafKind.FM && f != null -> " from " + Fmt.zulu(f)
        f != null && t != null -> " " + Fmt.zulu(f) + "–" + Fmt.zulu(t)
        else -> ""
    }
    return g.label + time
}

@Composable
private fun SmallHead(text: String, cat: FlightCat) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Spacer(Modifier.width(8.dp).height(8.dp).background(catColor(cat), RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Flag(text: String, colour: Color) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colour,
        modifier = Modifier
            .fillMaxWidth()
            .background(colour.copy(alpha = 0.13f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
