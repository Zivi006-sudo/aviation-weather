package com.zivi.avwx.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zivi.avwx.R
import com.zivi.avwx.ui.MainActivity
import com.zivi.avwx.wx.FlightCat
import com.zivi.avwx.wx.parseMetar
import com.zivi.avwx.wx.parseTaf
import com.zivi.avwx.wx.pickReports
import com.zivi.avwx.wx.shortCloud
import com.zivi.avwx.wx.shortVis
import com.zivi.avwx.wx.shortWind
import com.zivi.avwx.wx.wxEvents
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- notifications

object Notifier {
    private const val CHANNEL = "wx_alerts"

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, lines: List<String>) {
        if (lines.isEmpty() || !allowed(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weather changes", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (lines.size == 1) lines[0] else "${lines.size} weather changes")
            .setContentText(if (lines.size == 1) "Tap to open" else lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() / 1000 % 100000).toInt(), n)
        } catch (e: SecurityException) {
            // Permission was taken away between the check and the post.
        }
    }
}

// ---------------------------------------------------------------- background refresh

class WxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = Store(ctx)
        val airports = store.loadAirports()
        if (airports.isEmpty()) return Result.success()
        val old = store.loadWx()
        val res = try {
            WxApi.fetch(airports.map { it.icao })
        } catch (e: Exception) {
            return Result.success()   // offline: try again at the next run
        }
        val merged = HashMap(old)
        for ((k, v) in res.wx) merged[k] = WxMerge.pick(merged[k], v)

        if (store.notify) {
            val now = Instant.now()
            // Stations the international feed misses are read from the Maldives Met page.
            try {
                val picked = pickReports(MvMet.reports(ctx), now)
                val at = System.currentTimeMillis()
                for (a in airports) {
                    val r = picked[a.icao] ?: continue
                    merged[a.icao] = WxMerge.pick(merged[a.icao], StationWx(a.icao, r.first, r.second, at))
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
            }
            val events = ArrayList<String>()
            for (a in airports) {
                val o = old[a.icao] ?: continue
                val n = merged[a.icao] ?: continue
                val oldM = o.metarRaw?.let { runCatching { parseMetar(it, now) }.getOrNull() }
                val newM = n.metarRaw?.let { runCatching { parseMetar(it, now) }.getOrNull() }
                val oldT = o.tafRaw?.let { runCatching { parseTaf(it, now) }.getOrNull() }
                val newT = n.tafRaw?.let { runCatching { parseTaf(it, now) }.getOrNull() }
                events += wxEvents(a.icao, oldM, newM, oldT, newT)
            }
            Notifier.post(ctx, events)
        }
        store.saveWx(merged)
        WxWidget.updateAll(ctx)
        return Result.success()
    }

    companion object {
        /** Refreshes weather about every 15 minutes while the phone has a connection. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("wx_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

// ---------------------------------------------------------------- home screen widget

class WxWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HHmm'Z'").withZone(ZoneOffset.UTC)

        private fun catColour(c: FlightCat): Int = when (c) {
            FlightCat.VFR -> 0xFF34C759.toInt()
            FlightCat.MVFR -> 0xFF4A90FF.toInt()
            FlightCat.IFR -> 0xFFFF4D4D.toInt()
            FlightCat.LIFR -> 0xFFE052E0.toInt()
            FlightCat.UNKNOWN -> 0xFF6B7785.toInt()
        }

        /** Shows the sector destination if one is set, otherwise the first airport in the list. */
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WxWidget::class.java))
            if (ids.isEmpty()) return
            val store = Store(ctx)
            val airports = store.loadAirports()
            val dest = store.loadSector().getOrNull(1).orEmpty()
            val icao = if (dest.length == 4 && airports.any { it.icao == dest }) dest else airports.firstOrNull()?.icao
            val views = RemoteViews(ctx.packageName, R.layout.widget_wx)
            val wx = icao?.let { store.loadWx()[it] }
            val metar = wx?.metarRaw?.let { runCatching { parseMetar(it, Instant.ofEpochMilli(wx.fetchedAt)) }.getOrNull() }
            views.setTextViewText(R.id.w_icao, icao ?: "—")
            if (metar != null) {
                val cat = metar.category
                views.setTextViewText(R.id.w_cat, if (cat == FlightCat.UNKNOWN) "N/A" else cat.name)
                views.setInt(R.id.w_cat, "setBackgroundColor", catColour(cat))
                views.setTextViewText(R.id.w_time, metar.obsTime?.let { TIME.format(it) } ?: "")
                val c = metar.cond
                val wxText = if (c.wx.isNotEmpty()) "  " + c.wx.joinToString(" ") else ""
                views.setTextViewText(R.id.w_line, shortWind(c.wind) + "  " + shortVis(c) + "  " + shortCloud(c) + wxText)
            } else {
                views.setTextViewText(R.id.w_cat, "N/A")
                views.setInt(R.id.w_cat, "setBackgroundColor", catColour(FlightCat.UNKNOWN))
                views.setTextViewText(R.id.w_time, "")
                views.setTextViewText(R.id.w_line, if (icao == null) "Add an airport in the app" else "No METAR")
            }
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                R.id.w_root,
                PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            for (id in ids) mgr.updateAppWidget(id, views)
        }
    }
}
