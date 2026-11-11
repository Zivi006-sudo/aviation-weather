package com.zivi.avwx.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zivi.avwx.data.Notifier
import com.zivi.avwx.wx.Minima

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.updateNotify(granted)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Display") {
                SwitchRow(
                    "Night mode",
                    "Turns the whole screen red to protect night vision on the flight deck.",
                    vm.nightMode,
                ) { vm.updateNightMode(it) }
            }

            Section("Alerts") {
                SwitchRow(
                    "Weather change notifications",
                    "Checks your airports about every 15 minutes in the background and tells you about an amended TAF, " +
                        "a SPECI, a drop in flight category or a new thunderstorm.",
                    vm.notify,
                ) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(ctx)) {
                        askPermission.launch("android.permission.POST_NOTIFICATIONS")
                    } else {
                        vm.updateNotify(on)
                    }
                }
            }

            Section("Your minima") {
                Text(
                    "Used on the Sector tab to flag a forecast below these values. Enter the figures you work to.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                MinimaRow("Destination", vm.destMinima) { vm.updateDestMinima(it) }
                Spacer(Modifier.height(8.dp))
                MinimaRow("Alternate", vm.altnMinima) { vm.updateAltnMinima(it) }
            }

            Section("Satellite colours") {
                Text("Colour sensitivity", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Higher colours more of the cloud, including lower, warmer tops. Lower colours only the coldest, deepest cells.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = vm.sensitivity,
                    onValueChange = { vm.previewSensitivity(it) },
                    onValueChangeFinished = { vm.commitSensitivity() },
                    valueRange = 0f..1f,
                )
            }

            Section("Map layers") {
                Text("Show cloud tops above FL${vm.topsFl}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "The Tops layer colours only cloud higher than this, so tall cells stand out from the general cloud sheet.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = vm.topsFl.toFloat(),
                    onValueChange = { vm.previewTopsFl((it / 10f).toInt() * 10) },
                    onValueChangeFinished = { vm.commitTopsFl() },
                    valueRange = 150f..400f,
                    steps = 24,
                )
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    "Wind as arrows",
                    "Shows wind as arrows with speeds instead of moving lines.",
                    vm.windAsArrows,
                ) { vm.updateWindAsArrows(it) }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    "Show light rain",
                    "The Rain layer normally leaves out its faintest band, which covers most cloud. Turn on to see all of it.",
                    vm.showLightRain,
                ) { vm.updateShowLightRain(it) }
            }

            Section("Storage") {
                LaunchedEffect(Unit) { vm.refreshCacheSize() }
                Text(
                    if (vm.cacheMb >= 0) "Saved satellite and forecast data: ${vm.cacheMb} MB" else "Saved satellite and forecast data",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Satellite pictures are kept for 8 hours so places you have looked at reappear at once. They are removed automatically after that.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clear now", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.clearCache() }.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            Section("Data sources") {
                Text(
                    "METAR and TAF: Aviation Weather Center (aviationweather.gov) and the Maldives Meteorological Service " +
                        "aviation page; the newer report for each airport is shown.\n" +
                        "SIGMET: Aviation Weather Center.\n" +
                        "Satellite: EUMETSAT Meteosat (Indian Ocean, Africa and Europe) every 15 minutes; NASA GIBS (GOES-East, GOES-West, Himawari) elsewhere, every 10 minutes.\n" +
                        "Rain rate and cloud top height: EUMETSAT, in the Meteosat areas only.\n" +
                        "Wind, freezing level, icing, turbulence and thunderstorm risk: Open-Meteo model forecast.\n" +
                        "Live traffic: airplanes.live and adsb.lol community receivers. Aircraft outside their coverage are not shown.\n" +
                        "Radar imagery: Sentinel-2 cloudless by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2024).\n" +
                        "NOTAMs: link to the airport's page on Windy.\n" +
                        "Map: © OpenStreetMap contributors.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The cloud colour scale is applied by this app to image brightness. It shows relative cloud-top " +
                        "cold, not a calibrated temperature. Images arrive 15–30 minutes after they are taken. " +
                        "Icing, turbulence and thunderstorm risk are estimates this app works out from model data, not official forecasts.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Important") {
                Text(
                    "For situational awareness only. This app does not replace your company's official weather " +
                        "sources, the briefing package or the aircraft's weather radar.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = Amber,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MinimaRow(label: String, value: Minima, onChange: (Minima) -> Unit) {
    var ceil by remember { mutableStateOf(value.ceilingFt.toString()) }
    var vis by remember { mutableStateOf(value.visM.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.9f))
        OutlinedTextField(
            value = ceil,
            onValueChange = { v ->
                ceil = v.filter { it.isDigit() }.take(5)
                ceil.toIntOrNull()?.let { onChange(Minima(it, vis.toIntOrNull() ?: value.visM)) }
            },
            label = { Text("Ceiling ft", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = vis,
            onValueChange = { v ->
                vis = v.filter { it.isDigit() }.take(5)
                vis.toIntOrNull()?.let { onChange(Minima(ceil.toIntOrNull() ?: value.ceilingFt, it)) }
            },
            label = { Text("Vis m", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
