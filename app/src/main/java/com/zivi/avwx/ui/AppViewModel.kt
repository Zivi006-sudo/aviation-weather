package com.zivi.avwx.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zivi.avwx.data.Airport
import com.zivi.avwx.data.Aloft
import com.zivi.avwx.data.ExtraApi
import com.zivi.avwx.data.Sat
import com.zivi.avwx.data.SatFrame
import com.zivi.avwx.data.SatRequest
import com.zivi.avwx.data.Sigmet
import com.zivi.avwx.data.StationWx
import com.zivi.avwx.data.Store
import com.zivi.avwx.data.WxApi
import com.zivi.avwx.data.WxWidget
import com.zivi.avwx.data.WxWorker
import com.zivi.avwx.wx.Metar
import com.zivi.avwx.wx.Minima
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

    init {
        WxWorker.schedule(app)
    }

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
    var recents by mutableStateOf(store.loadRecents())
        private set
    /** Positions the weather server reported, for stations that aren't in the airport list. */
    var positions by mutableStateOf<Map<String, DoubleArray>>(emptyMap())
        private set

    fun positionOf(icao: String): DoubleArray? {
        val a = airports.firstOrNull { it.icao == icao }
        val lat = a?.lat
        val lon = a?.lon
        return if (lat != null && lon != null) doubleArrayOf(lat, lon) else positions[icao]
    }

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

    /** Every station the app needs: the list plus any sector airport not in it. */
    private fun wantedStations(): List<String> {
        val ids = LinkedHashSet<String>()
        airports.forEach { ids += it.icao }
        listOf(sectorDep, sectorDest, sectorAltn).forEach { if (it.length == 4) ids += it }
        return ids.toList()
    }

    fun refresh() {
        val ids = wantedStations()
        if (refreshing || ids.isEmpty()) return
        refreshing = true
        viewModelScope.launch {
            try {
                val res = WxApi.fetch(ids)
                val merged = HashMap(rawWx)
                merged.putAll(res.wx)
                rawWx = merged
                store.saveWx(merged)
                decoded = decode(merged)
                lastRefresh = System.currentTimeMillis()
                wxError = null
                val pos = HashMap(positions)
                for ((id, info) in res.info) {
                    val la = info.lat
                    val lo = info.lon
                    if (la != null && lo != null) pos[id] = doubleArrayOf(la, lo)
                }
                positions = pos
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
                runCatching { WxWidget.updateAll(getApplication()) }
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
        saveAirports(airports + Airport(icao, icao, null, null, ""))
        if (icao in recents) { recents = recents - icao; store.saveRecents(recents) }
        refresh()
        return null
    }

    fun removeAirport(icao: String) {
        saveAirports(airports.filter { it.icao != icao })
        recents = (listOf(icao) + recents.filter { it != icao }).take(8)
        store.saveRecents(recents)
    }

    fun moveAirport(icao: String, delta: Int) {
        val list = airports.toMutableList()
        val i = list.indexOfFirst { it.icao == icao }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        saveAirports(list)
    }

    fun setRunways(icao: String, runways: String) {
        saveAirports(airports.map { if (it.icao == icao) it.copy(runways = runways.trim().uppercase()) else it })
    }

    private fun saveAirports(list: List<Airport>) {
        airports = list
        store.saveAirports(list)
        runCatching { WxWidget.updateAll(getApplication()) }
    }

    // ------------------------------------------------------------ per-airport extras

    var history by mutableStateOf<Map<String, List<Metar>>>(emptyMap())
        private set
    var aloft by mutableStateOf<Map<String, Aloft>>(emptyMap())
        private set
    private val historyAt = HashMap<String, Long>()
    private val loadingExtra = HashSet<String>()

    /** Past 12 hours of METARs for the trend graphs. Re-fetched at most every 10 minutes. */
    fun loadHistory(icao: String) {
        val now = System.currentTimeMillis()
        if (now - (historyAt[icao] ?: 0L) < 10 * 60_000L || !loadingExtra.add("h$icao")) return
        viewModelScope.launch {
            try {
                val list = ExtraApi.history(icao, 12).mapNotNull { (t, raw) -> runCatching { parseMetar(raw, t) }.getOrNull() }
                history = history + (icao to list)
                historyAt[icao] = now
            } catch (e: Exception) {
                // Trend graphs are optional; leave whatever was there.
            } finally {
                loadingExtra.remove("h$icao")
            }
        }
    }

    /** Winds and temperatures aloft over the airport. Re-fetched at most hourly. */
    fun loadAloft(a: Airport) {
        val lat = a.lat ?: return
        val lon = a.lon ?: return
        val have = aloft[a.icao]
        if (have != null && System.currentTimeMillis() - have.fetchedAt < 60 * 60_000L) return
        if (!loadingExtra.add("a${a.icao}")) return
        viewModelScope.launch {
            try {
                aloft = aloft + (a.icao to ExtraApi.aloft(lat, lon))
            } catch (e: Exception) {
                // Optional section.
            } finally {
                loadingExtra.remove("a${a.icao}")
            }
        }
    }

    // ------------------------------------------------------------ SIGMETs

    var sigmets by mutableStateOf<List<Sigmet>>(emptyList())
        private set
    var sigmetsLoaded by mutableStateOf(false)
        private set
    private var sigmetsAt = 0L

    fun loadSigmets() {
        val now = System.currentTimeMillis()
        if (now - sigmetsAt < 10 * 60_000L) return
        sigmetsAt = now
        viewModelScope.launch {
            try {
                sigmets = ExtraApi.sigmets()
                sigmetsLoaded = true
            } catch (e: Exception) {
                sigmetsAt = 0L
            }
        }
    }

    // ------------------------------------------------------------ sector

    private val sector0 = store.loadSector()
    var sectorDep by mutableStateOf(sector0[0])
        private set
    var sectorDest by mutableStateOf(sector0[1])
        private set
    var sectorAltn by mutableStateOf(sector0[2])
        private set
    var sectorEtd by mutableStateOf(sector0[3])
        private set
    var sectorEta by mutableStateOf(sector0[4])
        private set

    fun updateSector(dep: String = sectorDep, dest: String = sectorDest, altn: String = sectorAltn, etd: String = sectorEtd, eta: String = sectorEta) {
        val stationsChanged = dep != sectorDep || dest != sectorDest || altn != sectorAltn
        sectorDep = dep; sectorDest = dest; sectorAltn = altn; sectorEtd = etd; sectorEta = eta
        store.saveSector(listOf(dep, dest, altn, etd, eta))
        if (stationsChanged && listOf(dep, dest, altn).any { it.length == 4 && decoded[it] == null }) refresh()
        if (stationsChanged) runCatching { WxWidget.updateAll(getApplication()) }
    }

    // ------------------------------------------------------------ settings

    var nightMode by mutableStateOf(store.nightMode)
        private set
    var notify by mutableStateOf(store.notify)
        private set
    var destMinima by mutableStateOf(store.destMinima)
        private set
    var altnMinima by mutableStateOf(store.altnMinima)
        private set

    fun updateNightMode(v: Boolean) { nightMode = v; store.nightMode = v }
    fun updateNotify(v: Boolean) { notify = v; store.notify = v }
    fun updateDestMinima(m: Minima) { destMinima = m; store.destMinima = m }
    fun updateAltnMinima(m: Minima) { altnMinima = m; store.altnMinima = m }

    // ------------------------------------------------------------ satellite

    var frames by mutableStateOf<List<SatFrame>>(emptyList())
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
    var showRain by mutableStateOf(store.showRain)
        private set
    var showSigmet by mutableStateOf(store.showSigmet)
        private set
    var rainMissing by mutableStateOf(false)
        private set
    var loopHours by mutableStateOf(store.loopHours.coerceIn(1, 3))
        private set
    var sensitivity by mutableStateOf(store.sensitivity)
        private set

    private var satJob: Job? = null
    var satRequest: SatRequest? = null
        private set

    fun updateEnhanced(v: Boolean) { if (v != enhanced) { enhanced = v; store.enhanced = v; reloadSat() } }
    fun updateShowRain(v: Boolean) { if (v != showRain) { showRain = v; store.showRain = v; reloadSat() } }
    fun updateShowSigmet(v: Boolean) { showSigmet = v; store.showSigmet = v; if (v) loadSigmets() }
    fun updateLoopHours(h: Int) { if (h != loopHours) { loopHours = h; store.loopHours = h; reloadSat() } }
    fun previewSensitivity(v: Float) { sensitivity = v }
    fun commitSensitivity() { store.sensitivity = sensitivity; if (enhanced) reloadSat() }

    fun reloadSat() {
        val req = satRequest ?: return
        loadSat(req)
    }

    /** Reloads only when the server has a newer image than the one on screen. */
    fun checkSatUpdate() {
        if (satLoading || satRequest == null) return
        viewModelScope.launch {
            val latest = Sat.latestTime()
            val shown = frames.lastOrNull()?.time
            if (shown == null || latest.isAfter(shown)) reloadSat()
        }
    }

    fun loadSat(req: SatRequest) {
        satJob?.cancel()
        satRequest = req
        val ctx = getApplication<Application>()
        val hours = loopHours
        val lut = Sat.buildLut(sensitivity, enhanced)
        val wantRain = showRain
        satJob = viewModelScope.launch {
            satLoading = true
            satError = null
            satLoaded = 0
            try {
                val latest = Sat.latestTime()
                val times = Sat.frameTimes(latest, hours)
                satTotal = times.size
                val gate = Semaphore(4)
                val loaded = coroutineScope {
                    times.map { t ->
                        async {
                            gate.withPermit {
                                val grey = Sat.loadGrey(ctx, t, req)
                                val frame = if (grey == null) null else {
                                    val bmp = Sat.colourise(grey, lut)
                                    grey.recycle()
                                    val rain = if (wantRain) Sat.loadRain(ctx, t, req) else null
                                    SatFrame(t, bmp, rain, req.bounds)
                                }
                                satLoaded += 1
                                frame
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()
                rainMissing = wantRain && Sat.rainUnavailable
                if (loaded.isEmpty()) {
                    satError = if (frames.isEmpty()) "No satellite images for this area. Check your connection, or move the map."
                    else "Couldn't load images here. Showing the last ones loaded."
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
