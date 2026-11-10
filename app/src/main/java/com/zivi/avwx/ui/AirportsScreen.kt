package com.zivi.avwx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zivi.avwx.data.Airport
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.shortCloud
import com.zivi.avwx.wx.shortVis
import com.zivi.avwx.wx.shortWind

@Composable
fun ScreenHeader(title: String, subtitle: String?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

@Composable
fun AirportsScreen(vm: AppViewModel, now: Long, onOpen: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) {
        LaunchedEffect(true) {
            vm.refresh()
            if (!vm.refreshing) pull.endRefresh()
        }
    }
    LaunchedEffect(vm.refreshing) {
        if (!vm.refreshing) pull.endRefresh()
    }

    val subtitle = when {
        vm.wxError != null -> vm.wxError
        vm.lastRefresh > 0 -> "Updated " + Fmt.ago(vm.lastRefresh, now)
        vm.refreshing -> "Updating…"
        else -> "Saved weather"
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Airports", subtitle) {
            IconButton(onClick = { editing = !editing }) {
                Icon(
                    Icons.Filled.Edit, contentDescription = "Reorder airports",
                    tint = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add airport")
            }
            IconButton(onClick = { vm.refresh() }, enabled = !vm.refreshing) {
                if (vm.refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        if (vm.airports.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No airports yet. Tap + to add one by its ICAO code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.airports, key = { it.icao }) { a ->
                        if (editing) {
                            ReorderRow(
                                a,
                                onUp = { vm.moveAirport(a.icao, -1) },
                                onDown = { vm.moveAirport(a.icao, 1) },
                            )
                        } else {
                            AirportCard(a, vm.decoded[a.icao], now, onClick = { onOpen(a.icao) })
                        }
                    }
                    item {
                        Text(
                            if (editing) "Use the arrows to reorder. The first airport is the one shown on the home screen widget."
                            else "Flight categories use the US definitions (ceiling and visibility). For situational awareness only.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
                val grow = if (pull.isRefreshing) 1f else pull.progress.coerceIn(0f, 1f)
                PullToRefreshContainer(
                    state = pull,
                    modifier = Modifier.align(Alignment.TopCenter).graphicsLayer(scaleX = grow, scaleY = grow),
                )
            }
        }
    }

    if (adding) {
        AddAirportDialog(
            recents = vm.recents,
            onDismiss = { adding = false },
            onAdd = { code ->
                val err = vm.addAirport(code)
                if (err == null) adding = false
                err
            },
        )
    }
}

@Composable
private fun AirportCard(a: Airport, d: Decoded?, now: Long, onClick: () -> Unit) {
    val metar = d?.metar
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardColor)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CatPill(metar?.category ?: FlightCat.UNKNOWN)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.icao, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (a.name.isNotBlank() && a.name != a.icao) {
                    Text(
                        a.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val obs = metar?.obsTime
            if (obs != null) {
                val ageMin = (now - obs.toEpochMilli()) / 60000L
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.zulu(obs), fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        Fmt.ago(obs.toEpochMilli(), now), fontSize = 12.sp,
                        color = if (ageMin > 90) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (metar == null) {
            Text(
                if (d == null) "Waiting for weather…" else "No METAR published for this airport.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d != null && d.taf != null) {
                Text("TAF available", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val c = metar.cond
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("WIND", shortWind(c.wind))
                Stat("VIS", shortVis(c))
                Stat("CLOUD", shortCloud(c))
                Stat("T / QNH", (metar.tempC?.toString() ?: "—") + "° / " + (metar.qnhHpa?.toString() ?: "—"))
            }
            val flags = ArrayList<String>()
            if (c.wx.isNotEmpty()) flags += c.wx.joinToString(" ")
            if (c.hasCb && !c.hasTs) flags += "CB/TCU reported"
            if (flags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    flags.joinToString("  ·  "), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (c.hasTs || c.hasCb) Amber else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AddAirportDialog(recents: List<String>, onDismiss: () -> Unit, onAdd: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add airport") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase().take(4); error = null },
                    label = { Text("ICAO code") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recently removed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        for (r in recents.take(4)) {
                            Text(
                                r, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { error = onAdd(r) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                val msg = error
                if (msg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { error = onAdd(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReorderRow(a: Airport, onUp: () -> Unit, onDown: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CardColor, RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.icao, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (a.name.isNotBlank() && a.name != a.icao) {
                Text(a.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = Color.White) }
        IconButton(onClick = onDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = Color.White) }
    }
}
