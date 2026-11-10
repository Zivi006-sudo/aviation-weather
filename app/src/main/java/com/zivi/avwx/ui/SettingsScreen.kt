package com.zivi.avwx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Display") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Night mode", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Turns the whole screen red to protect night vision on the flight deck.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.nightMode, onCheckedChange = { vm.updateNightMode(it) })
                }
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

            Section("Data sources") {
                Text(
                    "METAR and TAF: Aviation Weather Center (aviationweather.gov).\n" +
                        "Satellite: EUMETSAT Meteosat infrared 10.8 µm, Indian Ocean coverage, a new image every 15 minutes.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "The colour scale is applied by this app to image brightness. It shows relative cloud-top " +
                        "cold, not a calibrated temperature or rainfall rate. Images arrive 15–30 minutes after they are taken.",
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
