package com.zivi.avwx.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zivi.avwx.wx.Merc
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import kotlin.math.ln

/*
 * The street map under the weather: OpenFreeMap (OpenStreetMap data), drawn by MapLibre.
 * It has no gestures of its own; it simply follows the app's map camera, so everything drawn on top stays aligned.
 */

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/** Keeps the street map's camera in step with the app's. */
internal class BaseMapLink {
    var map: MapLibreMap? = null
    private var lastX = Double.NaN
    private var lastY = 0.0
    private var lastS = 0.0

    /** Called while drawing: moves the street map to match [cam] when it has changed. */
    fun sync(cam: MapCam, density: Float) {
        val m = map ?: return
        val s = cam.scale
        if (s <= 0.0) return
        if (cam.cx == lastX && cam.cy == lastY && s == lastS) return
        lastX = cam.cx
        lastY = cam.cy
        lastS = s
        // MapLibre's world is 512 density-independent pixels wide at zoom 0.
        val zoom = ln(s / (512.0 * density)) / ln(2.0)
        val pos = CameraPosition.Builder()
            .target(LatLng(Merc.lat(cam.cy).coerceIn(-85.0, 85.0), Merc.lon(cam.cx)))
            .zoom(zoom.coerceIn(0.0, 20.0))
            .build()
        try {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        } catch (e: Exception) {
            // Map not ready yet; the next frame tries again.
            lastX = Double.NaN
        }
    }

    fun reset() {
        map = null
        lastX = Double.NaN
    }
}

/** Every label that shows a place name is switched to English, falling back to the local name. */
private fun englishLabels(style: Style) {
    val english = Expression.coalesce(
        Expression.get("name:en"),
        Expression.get("name_en"),
        Expression.get("name:latin"),
        Expression.get("name"),
    )
    for (layer in style.layers) {
        if (layer !is SymbolLayer) continue
        val tf = try { layer.textField } catch (e: Exception) { null } ?: continue
        val current = (tf.expression?.toString() ?: tf.value?.toString() ?: "")
        if (current.contains("name")) {
            try {
                layer.setProperties(PropertyFactory.textField(english))
            } catch (e: Exception) {
                // Leave that layer as it is.
            }
        }
    }
}

private fun makeMapView(ctx: Context, link: BaseMapLink): MapView {
    MapLibre.getInstance(ctx)
    val options = MapLibreMapOptions.createFromAttributes(ctx)
        .textureMode(true)
        .attributionEnabled(false)
        .logoEnabled(false)
        .compassEnabled(false)
    val view = MapView(ctx, options)
    view.onCreate(null)
    view.getMapAsync { map ->
        map.uiSettings.setAllGesturesEnabled(false)
        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style -> englishLabels(style) }
        link.map = map
    }
    return view
}

/** The street map, filling its space. Follows the app's lifecycle so it stops drawing when hidden. */
@Composable
internal fun BaseMap(link: BaseMapLink, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { makeMapView(ctx, link) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            link.reset()
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
