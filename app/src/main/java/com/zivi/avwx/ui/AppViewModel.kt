package com.zivi.avwx.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zivi.avwx.data.Airport
import com.zivi.avwx.data.Aloft
import android.graphics.Bitmap
import com.zivi.avwx.data.ExtraApi
import com.zivi.avwx.data.Overlay
import com.zivi.avwx.data.FcGrid
import com.zivi.avwx.data.GridKind
import com.zivi.avwx.wx.Upper
import androidx.compose.ui.graphics.asImageBitmap
import com.zivi.avwx.data.WxMerge
import com.zivi.avwx.data.MvMet
import com.zivi.avwx.data.Aircraft
import com.zivi.avwx.data.Tiles
import com.zivi.avwx.wx.pickReports
import com.zivi.avwx.wx.Merc
import com.zivi.avwx.wx.snapBox
import kotlinx.coroutines.delay
import com.zivi.avwx.data.Sat
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
        viewModelScope.launch { runCatching { Tiles.trim(app) } }
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

    /** Merges fresh reports into what is held, keeping the newer report for each station, and saves the result. */
    private fun applyWx(fresh: Map<String, StationWx>) {
        val merged = HashMap(rawWx)
        for ((k, v) in fresh) merged[k] = WxMerge.pick(merged[k], v)
        rawWx = merged
        store.saveWx(merged)
        decoded = decode(merged)
        lastRefresh = System.currentTimeMillis()
        runCatching { WxWidget.updateAll(getApplication()) }
    }

    fun refresh() {
        val ids = wantedStations()
        if (refreshing || ids.isEmpty()) return
        refreshing = true
        viewModelScope.launch {
            var failed = false
            try {
                val res = WxApi.fetch(ids)
                applyWx(res.wx)
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
            } catch (e: Exception) {
                failed = true
                wxError = "Couldn't update. Showing saved weather."
            } finally {
                refreshing = false
            }
            // Second source: the Maldives Met page, which carries airports the international feed misses.
            try {
                delay(1200)
                val lines = MvMet.reports(getApplication())
                if (lines.isNotEmpty()) {
                    val picked = pickReports(lines, Instant.now())
                    val at = System.currentTimeMillis()
                    val fresh = HashMap<String, StationWx>()
                    for (id in ids) {
                        val r = picked[id] ?: continue
                        fresh[id] = StationWx(id, r.first, r.second, at)
                    }
                    if (fresh.isNotEmpty()) {
                        applyWx(fresh)
                        if (failed) wxError = null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The first source's reports stay as they are.
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

    /** Upper-air forecast over a station. Re-fetched at most hourly. */
    fun loadAloft(icao: String, lat: Double, lon: Double) {
        val have = aloft[icao]
        if (have != null && System.currentTimeMillis() - have.fetchedAt < 60 * 60_000L) return
        if (!loadingExtra.add("a$icao")) return
        viewModelScope.launch {
            try {
                aloft = aloft + (icao to ExtraApi.aloft(lat, lon))
            } catch (e: Exception) {
                // Optional section.
            } finally {
                loadingExtra.remove("a$icao")
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

    // ------------------------------------------------------------ satellite (tiles)

    /** Times of the loop frames, oldest first. */
    var satTimes by mutableStateOf<List<Instant>>(emptyList())
        private set
    var satLoading by mutableStateOf(false)
        private set
    var satError by mutableStateOf<String?>(null)
        private set
    /** When a newer picture last joined the loop (0 = not yet). */
    var satNewAt by mutableStateOf(0L)
        private set
    var enhanced by mutableStateOf(store.enhanced)
        private set
    var overlay by mutableStateOf(Overlay.of(store.overlay))
        private set
    var overlayMissing by mutableStateOf(false)
        private set
    var overlayLegend by mutableStateOf<Bitmap?>(null)
        private set
    var showSigmet by mutableStateOf(store.showSigmet)
        private set
    var loopHours by mutableStateOf(store.loopHours.coerceIn(1, 3))
        private set
    var sensitivity by mutableStateOf(store.sensitivity)
        private set
    var topsFl by mutableStateOf(store.topsFl.coerceIn(150, 400))
        private set
    var showLightRain by mutableStateOf(store.showLightRain)
        private set
    var cacheMb by mutableStateOf(-1)
        private set

    private class SatItem(val frame: Instant, val z: Int, val x: Int, val y: Int, val key: Long)

    private val pending = ArrayDeque<SatItem>()
    private val inFlight = HashSet<Long>()
    private var workers = 0
    private var prepJob: Job? = null
    private var satWanted: TileRange? = null
    private var satLoop = true
    private var styleGen = 0
    private var topsTable: ByteArray? = null

    /** How many loop frames the current setting gives. */
    val loopFrames: Int get() = loopHours * 4 + 1

    /** Colours or layers changed: tiles in memory are redrawn from the copies saved on the phone. */
    private fun restyle() {
        styleGen++
        SatMem.clear()
        reloadSat()
    }

    fun updateEnhanced(v: Boolean) { if (v != enhanced) { enhanced = v; store.enhanced = v; restyle() } }
    fun updateOverlay(o: Overlay) {
        if (o == overlay) return
        overlay = o
        store.overlay = o.key
        overlayLegend = null
        overlayMissing = false
        restyle()
    }
    fun updateShowSigmet(v: Boolean) { showSigmet = v; store.showSigmet = v; if (v) loadSigmets() }
    fun updateLoopHours(h: Int) { if (h != loopHours) { loopHours = h; store.loopHours = h; reloadSat() } }
    fun previewSensitivity(v: Float) { sensitivity = v }
    fun commitSensitivity() { store.sensitivity = sensitivity; restyle() }
    fun previewTopsFl(v: Int) { topsFl = v }
    fun commitTopsFl() { store.topsFl = topsFl; if (overlay == Overlay.TOPS) restyle() }
    fun updateShowLightRain(v: Boolean) { showLightRain = v; store.showLightRain = v; if (overlay == Overlay.RAIN) restyle() }

    fun refreshCacheSize() {
        viewModelScope.launch { cacheMb = Sat.cacheMb(getApplication()) }
    }

    fun clearCache() {
        viewModelScope.launch {
            Sat.clearCache(getApplication())
            windGrid = null
            iceGrid = null
            cacheMb = Sat.cacheMb(getApplication())
            restyle()
            ensureGrid()
        }
    }

    /** The refresh button: failed tiles are tried again. */
    fun reloadSat() {
        val r = satWanted ?: return
        SatMem.failed.clear()
        SatMem.empty.clear()
        SatMem.tick++
        satWant(r, satLoop, force = true)
    }

    /** Looks for a newer picture. Everything already held is kept, so only the new frame is downloaded. */
    fun checkSatUpdate() {
        val r = satWanted ?: return
        satWant(r, satLoop, force = true)
    }

    private fun overlayReady(): Boolean =
        overlay != Overlay.NONE && Sat.isResolved(overlay) && (overlay != Overlay.TOPS || topsTable != null)

    /** The work key of a tile of a frame if anything for it still has to be loaded, else null. */
    private fun needsWork(frame: Instant, z: Int, x: Int, y: Int): Long? {
        val src = Sat.sourceFor(z, x)
        val t = Sat.timeFor(src, frame) ?: return null
        val key = SatMem.key(0, t, z, x, y)
        if (key in inFlight) return null
        val failedAt = SatMem.failed[key]
        val irNeed = SatMem.mem.get(key) == null && (failedAt == null || System.currentTimeMillis() - failedAt > 60_000L)
        val oKey = SatMem.key(1, t, z, x, y)
        val ovNeed = src.gibs == null && overlayReady() && SatMem.mem.get(oKey) == null && oKey !in SatMem.empty
        return if (irNeed || ovNeed) key else null
    }

    /** Lists what is still missing for the view: newest frame first, from the middle of the screen outwards. */
    private fun rebuildQueue() {
        val r = satWanted ?: return
        pending.clear()
        val midX = (r.x0 + r.x1) / 2.0
        val midY = (r.y0 + r.y1) / 2.0
        val tiles = ArrayList<IntArray>()
        for (y in r.y0..r.y1) for (x in r.x0..r.x1) tiles += intArrayOf(x, y)
        tiles.sortBy { (it[0] - midX) * (it[0] - midX) + (it[1] - midY) * (it[1] - midY) }
        val seen = HashSet<Long>()
        for (t in satTimes.reversed()) {
            for (tile in tiles) {
                val key = needsWork(t, r.z, tile[0], tile[1]) ?: continue
                if (seen.add(key)) pending.addLast(SatItem(t, r.z, tile[0], tile[1], key))
            }
        }
    }

    /** Starts downloads for the queue, six at a time. A download that has started always finishes. */
    private fun pump() {
        while (workers < 6 && pending.isNotEmpty()) {
            workers++
            satLoading = true
            viewModelScope.launch {
                try {
                    while (true) {
                        val item = pending.removeFirstOrNull() ?: break
                        if (!inFlight.add(item.key)) continue
                        try {
                            loadTile(item)
                        } finally {
                            inFlight.remove(item.key)
                        }
                    }
                } finally {
                    workers--
                    if (workers == 0) {
                        // Anything dropped along the way (a colour change mid-download) is picked up again.
                        rebuildQueue()
                        if (pending.isNotEmpty()) pump() else {
                            satLoading = false
                            if (SatMem.mem.size() == 0 && satTimes.isNotEmpty()) {
                                satError = "No satellite images for this area. Check your connection, or move the map."
                            }
                            viewModelScope.launch { runCatching { Sat.trimCache(getApplication()) } }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadTile(item: SatItem) {
        val ctx = getApplication<Application>()
        val gen = styleGen
        val kind = overlay
        // With rain or tops on, the cloud underneath is plain grey so only one set of colours is on the map.
        val colour = enhanced && kind == Overlay.NONE
        val lut = Sat.buildLut(sensitivity, colour)
        val src = Sat.sourceFor(item.z, item.x)
        val t = Sat.timeFor(src, item.frame) ?: return
        val key = SatMem.key(0, t, item.z, item.x, item.y)
        val oKey = SatMem.key(1, t, item.z, item.x, item.y)
        val irNeed = SatMem.mem.get(key) == null
        val ovNeed = src.gibs == null && overlayReady() && SatMem.mem.get(oKey) == null && oKey !in SatMem.empty
        if (!irNeed && !ovNeed) return

        if (src.gibs != null) {
            val raw = Sat.loadGibsTile(ctx, src, t, item.z, item.x, item.y)
            if (raw != null) {
                val bmp = Sat.styleGibs(raw, lut, colour)
                raw.recycle()
                if (gen == styleGen) { SatMem.mem.put(key, bmp.asImageBitmap()); SatMem.tick++; satError = null }
            } else if (gen == styleGen) SatMem.failed[key] = System.currentTimeMillis()
            return
        }

        val grey = if (irNeed || (ovNeed && kind == Overlay.TOPS)) Sat.loadIrTile(ctx, src, t, item.z, item.x, item.y) else null
        if (irNeed) {
            if (grey != null) {
                val bmp = Sat.colourise(grey, lut)
                if (gen == styleGen) { SatMem.mem.put(key, bmp.asImageBitmap()); SatMem.tick++; satError = null }
            } else if (gen == styleGen) SatMem.failed[key] = System.currentTimeMillis()
        }
        if (ovNeed) {
            val raw = Sat.loadOverlayTile(ctx, kind, src, t, item.z, item.x, item.y)
            val table = topsTable
            val out: Bitmap? = when {
                raw == null -> null
                kind == Overlay.TOPS -> {
                    if (grey != null && table != null) {
                        val start = Sat.threshold(sensitivity)
                        Sat.recolourTops(raw, table, topsFl, grey, start + (255 - start) / 2).also { raw.recycle() }
                    } else { raw.recycle(); null }
                }
                kind == Overlay.RAIN && !showLightRain -> Sat.hideLightRain(raw).also { raw.recycle() }
                else -> raw
            }
            if (gen == styleGen) {
                if (out != null) SatMem.mem.put(oKey, out.asImageBitmap()) else SatMem.empty.add(oKey)
                SatMem.tick++
            }
        }
        grey?.recycle()
    }

    /**
     * Tells the loader which satellite tiles the map needs. Tiles already held are kept; only what is missing
     * is queued. Called again with [force] to look for a newer picture.
     */
    internal fun satWant(r: TileRange, loop: Boolean, force: Boolean = false) {
        if (!force && r == satWanted && loop == satLoop) return
        satWanted = r
        satLoop = loop
        prepJob?.cancel()
        prepJob = viewModelScope.launch {
            if (satTimes.isEmpty()) satLoading = true
            val latest = Sat.latestTime()
            val times = if (loop) Sat.frameTimes(latest, loopHours) else listOf(latest)
            if (times != satTimes) {
                val old = satTimes.lastOrNull()
                if (old != null && latest.isAfter(old)) satNewAt = System.currentTimeMillis()
                satTimes = times
            }
            rebuildQueue()
            pump()

            // The overlay's layer name and colour key are looked up once.
            val kind = overlay
            if (kind != Overlay.NONE &&
                (!Sat.isResolved(kind) || (kind == Overlay.RAIN && overlayLegend == null) || (kind == Overlay.TOPS && topsTable == null))
            ) {
                val legend = Sat.resolve(kind)
                if (kind == Overlay.RAIN && legend != null) overlayLegend = legend
                if (kind == Overlay.TOPS && Sat.isResolved(kind)) topsTable = Sat.buildTopsTable(legend)
                overlayMissing = !Sat.isResolved(kind)
                rebuildQueue()
                pump()
            }

            // NASA's satellites: find their newest picture, then queue their tiles.
            for (src in Sat.sourcesIn(r.z, r.x0, r.x1)) {
                if (src.gibs == null) continue
                launch {
                    val before = Sat.latestOf(src)
                    val found = Sat.probeLatest(src)
                    if (found != null && found != before) {
                        SatMem.tick++
                        rebuildQueue()
                        pump()
                    }
                }
            }
            if (workers == 0 && pending.isEmpty()) satLoading = false
        }
    }

    // ------------------------------------------------------------ forecast map layers (wind, icing)

    var showIcing by mutableStateOf(store.showIcing)
        private set
    var showWind by mutableStateOf(store.showWind)
        private set
    var windAsArrows by mutableStateOf(store.windAsArrows)
        private set
    var gridLevel by mutableStateOf(store.gridLevel.coerceIn(0, Upper.GRID_LABELS.size - 1))
        private set
    var gridHour by mutableStateOf(0)       // hours ahead of now
        private set
    var windGrid by mutableStateOf<FcGrid?>(null)
        private set
    var iceGrid by mutableStateOf<FcGrid?>(null)
        private set
    var gridLoading by mutableStateOf(false)
        private set
    var gridError by mutableStateOf<String?>(null)
        private set
    private var gridJob: Job? = null
    private var gridBox: DoubleArray? = null     // west, south, east, north
    private var gridKey = ""

    fun updateShowIcing(v: Boolean) { showIcing = v; store.showIcing = v; ensureGrid() }
    fun updateShowWind(v: Boolean) { showWind = v; store.showWind = v; ensureGrid() }
    fun updateWindAsArrows(v: Boolean) { windAsArrows = v; store.windAsArrows = v }
    fun updateGridLevel(i: Int) { gridLevel = i; store.gridLevel = i }
    fun updateGridHour(h: Int) { gridHour = h }

    /** Tells the forecast layers which part of the map is on screen (centre and scale in Mercator world units). */
    fun wantGrid(cx: Double, cy: Double, scale: Double, wPx: Int, hPx: Int) {
        if (scale <= 0.0 || wPx <= 0 || hPx <= 0) return
        // A grid-snapped area somewhat larger than the screen, so small moves reuse the same download.
        val b = snapBox(cx, cy, wPx / scale, hPx / scale, 1.35)
        val west = Merc.lon(b[0].coerceIn(0.0, 1.0))
        val east = Merc.lon(b[2].coerceIn(0.0, 1.0))
        val north = Merc.lat(b[1].coerceIn(0.03, 0.97))
        val south = Merc.lat(b[3].coerceIn(0.03, 0.97))
        if (east - west < 0.5 || north - south < 0.5) return
        val key = "%.2f_%.2f_%.2f_%.2f".format(java.util.Locale.US, west, south, east, north).replace('-', 'm').replace('.', 'p')
        if (key != gridKey) {
            gridKey = key
            gridBox = doubleArrayOf(west, south, east, north)
        }
        ensureGrid()
    }

    /** Downloads the forecast grids that are switched on and not yet held for the area on screen. */
    private fun ensureGrid() {
        val box = gridBox ?: return
        val key = gridKey
        val now = System.currentTimeMillis()
        fun stale(g: FcGrid?) = g == null || g.key != key || now - g.fetchedAt > 60 * 60_000L
        val needWind = showWind && stale(windGrid)
        val needIce = showIcing && stale(iceGrid)
        if (!needWind && !needIce) return
        gridJob?.cancel()
        val ctx = getApplication<Application>()
        gridJob = viewModelScope.launch {
            gridLoading = true
            try {
                delay(350)      // lets the map settle before spending a download
                if (needWind) windGrid = ExtraApi.grid(ctx, GridKind.WIND, box[0], box[1], box[2], box[3], key)
                if (needIce) iceGrid = ExtraApi.grid(ctx, GridKind.ICING, box[0], box[1], box[2], box[3], key)
                gridError = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                gridError = "Couldn't load the forecast layer."
            } finally {
                if (gridJob === coroutineContext[Job]) gridLoading = false
            }
        }
    }

    // ------------------------------------------------------------ live traffic

    var aircraft by mutableStateOf<List<Aircraft>>(emptyList())
        private set
    var radarAt by mutableStateOf(0L)
        private set
    var radarError by mutableStateOf<String?>(null)
        private set
    /** Recent positions of each aircraft ([lat, lon]), oldest first. Read while drawing. */
    val trails = HashMap<String, ArrayList<DoubleArray>>()
    private var radarBusy = false

    fun refreshRadar(lat: Double, lon: Double, radiusNm: Int) {
        if (radarBusy) return
        radarBusy = true
        viewModelScope.launch {
            try {
                val list = ExtraApi.aircraft(lat, lon, radiusNm)
                for (a in list) {
                    val t = trails.getOrPut(a.hex) { ArrayList() }
                    val last = t.lastOrNull()
                    if (last == null || last[0] != a.lat || last[1] != a.lon) {
                        t += doubleArrayOf(a.lat, a.lon)
                        if (t.size > 80) t.removeAt(0)
                    }
                }
                if (trails.size > 500) {
                    val live = list.map { it.hex }.toSet()
                    trails.keys.retainAll(live)
                }
                aircraft = list
                radarAt = System.currentTimeMillis()
                radarError = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                radarError = "Couldn't update traffic. Check your connection."
            } finally {
                radarBusy = false
            }
        }
    }
}
