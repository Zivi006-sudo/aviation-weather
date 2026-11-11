package com.zivi.avwx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zivi.avwx.wx.Merc
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.floor

/**
 * Downloads the satellite pictures (cloud, rain, tops) and the wind and icing forecasts for the Maldives
 * ahead of time, so they are already on the phone when the map opens. Runs from the app while it is open
 * or minimized, and from the 15-minute background job. Stops after 6 hours without the app being opened.
 */
object Prefetch {
    /** Satellite area: lon 66..81, lat -4..11. */
    private val SAT = doubleArrayOf(66.0, -4.0, 81.0, 11.0)
    /** Forecast area used whenever the map is around the Maldives: west, south, east, north. */
    val GRID = doubleArrayOf(62.0, -10.0, 85.0, 14.0)
    const val GRID_KEY = "mv"

    private val lock = Mutex()
    /** Rain and tops tiles that came back empty (not yet out); not asked again while the app runs. */
    private val empty = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun allowed(ctx: Context, store: Store): Boolean {
        if (!store.prefetchOn) return false
        val opened = store.lastOpened
        if (opened > 0 && System.currentTimeMillis() - opened > 6 * 3600_000L) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (store.prefetchWifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        return true
    }

    suspend fun run(ctx: Context, store: Store) {
        if (!allowed(ctx, store)) return
        if (!lock.tryLock()) return     // already running
        try {
            runCatching { satellite(ctx, store) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            runCatching { grids(ctx, store) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            runCatching { Sat.trimCache(ctx) }
        } finally {
            lock.unlock()
        }
    }

    private class T(val z: Int, val x: Int, val y: Int)

    private fun tiles(): List<T> {
        val out = ArrayList<T>()
        for (z in listOf(Sat.MAX_ZOOM, Sat.MAX_ZOOM - 1)) {
            val n = 1 shl z
            val x0 = floor(Merc.x(SAT[0]) * n).toInt()
            val x1 = floor(Merc.x(SAT[2]) * n).toInt()
            val y0 = floor(Merc.y(SAT[3]) * n).toInt()
            val y1 = floor(Merc.y(SAT[1]) * n).toInt()
            for (y in y0..y1) for (x in x0..x1) out += T(z, x, y)
        }
        return out
    }

    private suspend fun satellite(ctx: Context, store: Store) = coroutineScope {
        val gate = Semaphore(4)
        val latest = Sat.latestTime()
        val frames = Sat.frameTimes(latest, store.loopHours.coerceIn(1, 3))
        val list = tiles()

        // Cloud: newest picture first.
        val jobs = ArrayList<kotlinx.coroutines.Deferred<*>>()
        for (f in frames.reversed()) for (t in list) {
            val src = Sat.sourceFor(t.z, t.x)
            if (src.gibs != null || Sat.hasIrTile(ctx, src, f, t.z, t.x, t.y)) continue
            jobs.add(async {
                gate.withPermit {
                    Sat.loadIrTile(ctx, src, f, t.z, t.x, t.y, preferCache = f == latest)?.recycle()
                }
            })
        }
        jobs.awaitAll()

        // Rain and cloud tops, each up to its own newest picture.
        for (o in listOf(Overlay.RAIN, Overlay.TOPS)) {
            if (!Sat.isResolved(o)) Sat.resolve(o)?.recycle()
            if (!Sat.isResolved(o)) continue
            val top = Sat.checkOverlayLatest(o) ?: Sat.overlayLatest(o) ?: continue
            val times = frames.map { if (it.isAfter(top)) top else it }.distinct()
            val oj = ArrayList<kotlinx.coroutines.Deferred<*>>()
            for (f in times.reversed()) for (t in list) {
                val src = Sat.sourceFor(t.z, t.x)
                if (src.gibs != null) continue
                val name = "${o.key}${src.id}_${f.epochSecond}_${t.z}_${t.x}_${t.y}"
                val e = empty[name]
                if (e != null && System.currentTimeMillis() - e < 10 * 60_000L) continue
                val needOv = !Sat.hasOverlayTile(ctx, o, src, f, t.z, t.x, t.y)
                // Tops are trimmed with the infrared picture of the same time.
                val needIr = o == Overlay.TOPS && !Sat.hasIrTile(ctx, src, f, t.z, t.x, t.y)
                if (!needOv && !needIr) continue
                oj.add(async {
                    gate.withPermit {
                        if (needOv) {
                            val b = Sat.loadOverlayTile(ctx, o, src, f, t.z, t.x, t.y)
                            if (b == null || !Sat.hasOverlayTile(ctx, o, src, f, t.z, t.x, t.y)) empty[name] = System.currentTimeMillis()
                            b?.recycle()
                        }
                        if (needIr) Sat.loadIrTile(ctx, src, f, t.z, t.x, t.y)?.recycle()
                    }
                })
            }
            oj.awaitAll()
        }
    }

    /** Wind and icing every 3 hours: Open-Meteo's free allowance is counted per point. */
    private suspend fun grids(ctx: Context, store: Store) {
        val now = System.currentTimeMillis()
        if (now - store.lastGridPrefetch < 3 * 3600_000L) return
        val b = GRID
        ExtraApi.grid(ctx, GridKind.WIND, b[0], b[1], b[2], b[3], GRID_KEY, maxAgeMin = 170)
        ExtraApi.grid(ctx, GridKind.ICING, b[0], b[1], b[2], b[3], GRID_KEY, maxAgeMin = 170)
        store.lastGridPrefetch = now
    }
}
