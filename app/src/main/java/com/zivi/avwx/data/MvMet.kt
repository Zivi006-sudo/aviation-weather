package com.zivi.avwx.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Reads METAR and TAF text from the Maldives Meteorological Service aviation page.
 * The page fills itself in with scripts, so it is opened in an invisible browser view and its text is searched.
 */
object MvMet {
    private const val PAGE = "https://v2.meteorology.gov.mv/aviation"

    // Finds report texts for Maldivian stations (VR..) anywhere in the page, shown or held in page data.
    private const val FIND = """
(function(){
  try {
    var b = document.body; if (!b) return '';
    var t = (b.innerText || '') + ' \n ' + (b.textContent || '');
    t = t.replace(/\\[nrt]/g, ' ').replace(/\s+/g, ' ');
    var re = /\b(?:(?:METAR|SPECI|TAF)(?: COR| AMD)? )?VR[A-Z]{2} \d{6}Z [^=<>{}"]{5,600}(?:=|(?="))/g;
    var m = t.match(re) || [];
    var seen = {}, out = [];
    for (var i = 0; i < m.length && out.length < 300; i++) { if (!seen[m[i]]) { seen[m[i]] = 1; out.push(m[i]); } }
    return out.join('\n');
  } catch (e) { return ''; }
})()
"""

    @Volatile private var last: List<String> = emptyList()
    @Volatile private var lastAt = 0L
    @Volatile private var busy = false

    /** Report texts from the page. Reuses the previous read when it is newer than [maxAgeMs]. Empty when the page can't be read. */
    suspend fun reports(ctx: Context, maxAgeMs: Long = 4 * 60_000L): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastAt < maxAgeMs || busy) return last
        busy = true
        try {
            val found = withTimeoutOrNull(32_000L) { read(ctx.applicationContext) } ?: emptyList()
            if (found.isNotEmpty()) {
                last = found
                lastAt = System.currentTimeMillis()
            }
            return found
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return emptyList()
        } finally {
            busy = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun read(ctx: Context): List<String> = withContext(Dispatchers.Main) {
        val web = try { WebView(ctx) } catch (t: Throwable) { return@withContext emptyList() }
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.blockNetworkImage = true
            web.webViewClient = WebViewClient()
            web.layout(0, 0, 1080, 1920)
            web.loadUrl(PAGE)
            var found: List<String> = emptyList()
            var steady = 0
            var tries = 0
            // Look once a second until the list of reports stops growing.
            while (tries < 26 && steady < 2) {
                delay(1000)
                tries++
                val lines = evaluate(web).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    if (lines.size == found.size) steady++ else steady = 0
                    found = lines
                }
            }
            found
        } finally {
            try { web.stopLoading(); web.destroy() } catch (t: Throwable) { }
        }
    }

    private suspend fun evaluate(web: WebView): String = suspendCancellableCoroutine { cont ->
        try {
            web.evaluateJavascript(FIND) { v ->
                val text = try { JSONTokener(v ?: "null").nextValue() as? String ?: "" } catch (e: Exception) { "" }
                if (cont.isActive) cont.resume(text)
            }
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume("")
        }
    }
}
