package app.noentrynav

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

// OSM raster tiles for development. For production, self-host tiles (Protomaps/OpenMapTiles)
// and point this at your own tile server: the public OSM tile servers are not meant for heavy app traffic.
private const val STYLE_JSON = """
{"version":8,
 "sources":{"osm":{"type":"raster","tileSize":256,"maxzoom":19,
   "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
   "attribution":"© OpenStreetMap contributors"}},
 "layers":[{"id":"osm","type":"raster","source":"osm"}]}
"""

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

class MapController {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var last: UiState? = null
    private var shownZones: List<Zone>? = null
    private var shownRoute: Route? = null
    private var fitted: Route? = null
    private var centered = false

    fun attach(map: MapLibreMap, onLongPress: (LatLon) -> Unit) {
        this.map = map
        map.cameraPosition = CameraPosition.Builder().target(LatLng(28.6139, 77.2090)).zoom(10.5).build()
        map.setStyle(Style.Builder().fromJson(STYLE_JSON)) { st ->
            st.addSource(GeoJsonSource("zones", EMPTY_FC))
            st.addLayer(FillLayer("zones-fill", "zones").withProperties(fillColor("#E53935"), fillOpacity(0.25f)))
            st.addLayer(LineLayer("zones-line", "zones").withProperties(lineColor("#B71C1C"), lineWidth(1.5f)))
            st.addSource(GeoJsonSource("route", EMPTY_FC))
            st.addLayer(
                LineLayer("route-casing", "route").withProperties(
                    lineColor("#0D47A1"), lineWidth(9f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            st.addLayer(
                LineLayer("route-line", "route").withProperties(
                    lineColor("#2196F3"), lineWidth(5.5f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            st.addSource(GeoJsonSource("dest", EMPTY_FC))
            st.addLayer(
                CircleLayer("dest-dot", "dest").withProperties(
                    circleColor("#D32F2F"), circleRadius(9f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                )
            )
            st.addSource(GeoJsonSource("me", EMPTY_FC))
            st.addLayer(
                CircleLayer("me-dot", "me").withProperties(
                    circleColor("#1E88E5"), circleRadius(8f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                )
            )
            style = st
            shownZones = null
            shownRoute = null
            last?.let { render(it) }
        }
        map.addOnMapLongClickListener { ll ->
            onLongPress(LatLon(ll.latitude, ll.longitude))
            true
        }
    }

    fun render(s: UiState) {
        last = s
        val st = style ?: return
        val m = map ?: return

        if (s.zones !== shownZones) {
            st.getSourceAs<GeoJsonSource>("zones")?.setGeoJson(zonesJson(s.zones))
            shownZones = s.zones
        }
        if (s.route !== shownRoute) {
            st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeJson(s.route))
            shownRoute = s.route
        }
        st.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(pointJson(s.dest))
        st.getSourceAs<GeoJsonSource>("me")?.setGeoJson(pointJson(s.me))

        val me = s.me
        val route = s.route
        if (s.navigating && me != null) {
            val pos = CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon)).zoom(17.0).tilt(45.0).bearing(s.bearing.toDouble())
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 700)
        } else if (route != null && route !== fitted) {
            fitted = route
            val b = LatLngBounds.Builder()
            route.points.forEach { b.include(LatLng(it.lat, it.lon)) }
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80, 260, 80, 520), 800)
        } else if (route == null) {
            fitted = null
            if (!centered && me != null) {
                centered = true
                m.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(me.lat, me.lon)).zoom(13.5).build()
                    )
                )
            }
        }
    }

    private fun zonesJson(z: List<Zone>): String {
        val feats = JSONArray()
        z.forEach { zone ->
            val ring = JSONArray()
            zone.ring.forEach { ring.put(JSONArray().put(it.lon).put(it.lat)) }
            feats.put(
                JSONObject().put("type", "Feature")
                    .put("properties", JSONObject().put("name", zone.name))
                    .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring)))
            )
        }
        return JSONObject().put("type", "FeatureCollection").put("features", feats).toString()
    }

    private fun routeJson(r: Route?): String {
        if (r == null) return EMPTY_FC
        val coords = JSONArray()
        r.points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)).toString()
    }

    private fun pointJson(p: LatLon?): String {
        if (p == null) return EMPTY_FC
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
            .toString()
    }
}

@Composable
fun MapViewHost(state: UiState, controller: MapController, onLongPress: (LatLon) -> Unit) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = (context as ComponentActivity).lifecycle

    DisposableEffect(lifecycle, mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    AndroidView(
        factory = {
            mapView.also { mv -> mv.getMapAsync { map -> controller.attach(map, onLongPress) } }
        },
        modifier = Modifier.fillMaxSize(),
        update = { controller.render(state) }
    )
}
