package com.zivi.avwx.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zivi.avwx.data.Airport
import com.zivi.avwx.data.Sat
import com.zivi.avwx.data.SatFrame
import com.zivi.avwx.data.StationWx
import com.zivi.avwx.data.Store
import com.zivi.avwx.data.WxApi
import com.zivi.avwx.wx.Metar
import com.zivi.avwx.wx.Taf
import com.zivi.avwx.wx.parseMetar
import com.zivi.avwx.wx.parseTaf
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

class Decoded(val wx: StationWx, val metar: Metar?, val taf: Taf?)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    // ------------------------------------------------------------ airports + weather

    var airports by mutableStateOf(store.loadAirports())
        private set
    var decoded by mutableStateOf(decode(store.loadWx()))
        private set
    var refreshing by mutableStateOf(false)
        private set
    var wxError by mutableStateOf<String?>(null)
        private set
    var lastRefresh by mutableStateOf(0L)
        private set

    private var rawWx: Map<String, StationWx> = store.loadWx()

    private fun decode(map: Map<String, StationWx>): Map<String, Decoded> {
        val out = HashMap<String, Decoded>()
        for ((k, v) in map) {
            val ref = if (v.fetchedAt > 0) Instant.ofEpochMilli(v.fetchedAt) else Instant.now()
            val m = v.metarRaw?.let { runCatching { parseMetar(it, ref) }.getOrNull() }
            val t = v.tafRaw?.let { runCatching { parseTaf(it, ref) }.getOrNull() }
            out[k] = Decoded(v, m, t)
        }
        return out
    }

    fun refresh() {
        if (refreshing || airports.isEmpty()) return
        refreshing = true
        viewModelScope.launch {
            try {
                val res = WxApi.fetch(airports.map { it.icao })
                val merged = HashMap(rawWx)
                merged.putAll(res.wx)
                rawWx = merged
                store.saveWx(merged)
                decoded = decode(merged)
                lastRefresh = System.currentTimeMillis()
                wxError = null
                // Fill in names and positions the server knows for airports the user added by code.
                var changed = false
                val updated = airports.map { a ->
                    val info = res.info[a.icao] ?: return@map a
                    val name = if (a.name.isBlank() || a.name == a.icao) info.name?.let { cleanName(it) } ?: a.name else a.name
                    val lat = a.lat ?: info.lat
                    val lon = a.lon ?: info.lon
                    if (name != a.name || lat != a.lat || lon != a.lon) {
                        changed = true
                        a.copy(name = name, lat = lat, lon = lon)
                    } else a
                }
                if (changed) {
                    airports = updated
                    store.saveAirports(updated)
                }
            } catch (e: Exception) {
                wxError = "Couldn't update. Showing saved weather."
            } finally {
                refreshing = false
            }
        }
    }

    private fun cleanName(n: String): String = n.substringBefore(",").trim().ifEmpty { n }

    /** Returns an error message, or null when the airport was added. */
    fun addAirport(code: String): String? {
        val icao = code.trim().uppercase()
        if (icao.length != 4 || !icao.all { it.isLetterOrDigit() }) return "Enter a 4-letter ICAO code, like VRMM."
        if (airports.any { it.icao == icao }) return "$icao is already in your list."
        val list = airports + Airport(icao, icao, null, null, "")
        airports = list
        store.saveAirports(list)
        refresh()
        return null
    }

    fun removeAirport(icao: String) {
        val list = airports.filter { it.icao != icao }
        airports = list
        store.saveAirports(list)
    }

    fun setRunways(icao: String, runways: String) {
        val list = airports.map { if (it.icao == icao) it.copy(runways = runways.trim().uppercase()) else it }
        airports = list
        store.saveAirports(list)
    }

    // ------------------------------------------------------------ settings

    var nightMode by mutableStateOf(store.nightMode)
        private set

    fun updateNightMode(v: Boolean) { nightMode = v; store.nightMode = v }

    // ------------------------------------------------------------ satellite

    var frames by mutableStateOf<List<SatFrame>>(emptyList())
        private set
    var coast by mutableStateOf<Bitmap?>(null)
        private set
    var satLoading by mutableStateOf(false)
        private set
    var satLoaded by mutableStateOf(0)
        private set
    var satTotal by mutableStateOf(0)
        private set
    var satError by mutableStateOf<String?>(null)
        private set
    var enhanced by mutableStateOf(store.enhanced)
        private set
    var loopHours by mutableStateOf(store.loopHours.coerceIn(1, 3))
        private set
    var sensitivity by mutableStateOf(store.sensitivity)
        private set

    private var satJob: Job? = null
    private var coastTried = false

    fun updateEnhanced(v: Boolean) { if (v != enhanced) { enhanced = v; store.enhanced = v; loadSat() } }
    fun updateLoopHours(h: Int) { if (h != loopHours) { loopHours = h; store.loopHours = h; loadSat() } }
    fun previewSensitivity(v: Float) { sensitivity = v }
    fun commitSensitivity() { store.sensitivity = sensitivity; if (enhanced) loadSat() }

    /** Reloads only when the server has a newer image than the one on screen. */
    fun checkSatUpdate() {
        if (satLoading) return
        if (frames.isEmpty()) { loadSat(); return }
        viewModelScope.launch {
            val latest = Sat.latestTime()
            val shown = frames.lastOrNull()?.time
            if (shown == null || latest.isAfter(shown)) loadSat()
        }
    }

    fun loadSat() {
        satJob?.cancel()
        val ctx = getApplication<Application>()
        val hours = loopHours
        val colour = enhanced
        val lut = Sat.buildLut(sensitivity)
        satJob = viewModelScope.launch {
            satLoading = true
            satError = null
            satLoaded = 0
            try {
                if (!coastTried) {
                    coastTried = true
                    viewModelScope.launch { coast = Sat.loadCoast(ctx) }
                }
                val latest = Sat.latestTime()
                val times = Sat.frameTimes(latest, hours)
                satTotal = times.size
                val gate = Semaphore(4)
                val loaded = coroutineScope {
                    times.map { t ->
                        async {
                            gate.withPermit {
                                val grey = Sat.loadGrey(ctx, t)
                                val bmp = when {
                                    grey == null -> null
                                    colour -> Sat.colourise(grey, lut).also { grey.recycle() }
                                    else -> grey
                                }
                                satLoaded += 1
                                bmp?.let { SatFrame(t, it) }
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()
                if (loaded.isEmpty()) {
                    if (frames.isEmpty()) satError = "No satellite images available. Check your connection."
                    else satError = "Couldn't update. Showing the last images loaded."
                } else {
                    frames = loaded
                }
                Sat.trimCache(ctx)
            } finally {
                // A newer load may already have replaced this one; only the current load clears the flag.
                if (satJob === coroutineContext[Job]) satLoading = false
            }
        }
    }
}
