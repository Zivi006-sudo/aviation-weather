package com.zivi.avwx.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.res.painterResource
import com.zivi.avwx.R
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme(night = vm.nightMode) {
                AppRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.markOpened()
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Refresh weather while the app is on screen: at once, then every 5 minutes.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(5 * 60_000L)
            }
        }
    }
    // Keeps "x min ago" labels current.
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(20_000L)
            }
        }
    }

    BackHandler(enabled = detail != null || tab != 0) {
        if (detail != null) detail = null else tab = 0
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0; detail = null },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Airports") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(painterResource(R.drawable.ic_tab_sat), contentDescription = null) },
                    label = { Text("Satellite") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(painterResource(R.drawable.ic_tab_radar), contentDescription = null) },
                    label = { Text("Radar") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Send, contentDescription = null) },
                    label = { Text("Sector") },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> {
                    val icao = detail
                    if (icao != null && vm.airports.any { it.icao == icao }) {
                        AirportDetailScreen(vm, icao, now, onBack = { detail = null })
                    } else {
                        AirportsScreen(vm, now, onOpen = { detail = it })
                    }
                }
                1 -> MapScreen(vm, now)
                2 -> RadarScreen(vm, now)
                3 -> SectorScreen(vm, now)
                else -> SettingsScreen(vm)
            }
        }
    }
}
