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
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

// Fully offline: plain background, roads are drawn from the on-device database.
private const val STYLE_JSON = """
{"version":8,"sources":{},
 "layers":[{"id":"bg","type":"background","paint":{"background-color":"#F1F3F5"}}]}
"""

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

class MapController {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var last: UiState? = null
    private var shownZones: List<Pair<Zone, Int>>? = null
    private var shownRoute: Route? = null
    private var shownRoutes: List<Route>? = null
    private var shownRoads: Roads? = null
    private var shownCams: String? = null
    private var shownLines: String? = ""
    private var fitted: Route? = null
    /** Space (px) covered by the search card and the bottom sheet; the route is fitted into what is left. */
    @Volatile var padTop = 220
    @Volatile var padBottom = 600

    fun refit(route: Route?) {
        val m = map ?: return
        if (route == null || route.points.size < 2) return
        val b = LatLngBounds.Builder()
        route.points.forEach { b.include(LatLng(it.lat, it.lon)) }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 70, padTop + 40, 70, padBottom + 40), 700)
    }
    private var centered = false
    private var lastRecenter = 0
    private var navZoom = 17.0

    fun flyTo(p: LatLon, zoom: Double = 16.5) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 600)
    }

    /** + / - buttons. While navigating this adjusts the follow-camera zoom, otherwise it zooms the map. */
    fun zoomBy(d: Double) {
        val m = map ?: return
        if (last?.navigating == true) {
            navZoom = (navZoom + d).coerceIn(12.0, 19.5)
            last?.me?.let { me ->
                m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(me.lat, me.lon)).zoom(navZoom).tilt(45.0).bearing(last!!.bearing.toDouble()).build()), 250)
            }
        } else {
            m.animateCamera(CameraUpdateFactory.zoomBy(d), 250)
        }
    }

    fun attach(map: MapLibreMap, onLongPress: (LatLon) -> Unit, onTap: (LatLon) -> Unit, onViewport: (Double, Double, Double, Double, Double) -> Unit) {
        this.map = map
        map.cameraPosition = CameraPosition.Builder().target(LatLng(28.6139, 77.2090)).zoom(10.5).build()
        map.setStyle(Style.Builder().fromJson(STYLE_JSON)) { st ->
            // offline roads: casings first, then fills, major roads on top
            st.addSource(GeoJsonSource("roads-local", EMPTY_FC))
            st.addSource(GeoJsonSource("roads-main", EMPTY_FC))
            st.addSource(GeoJsonSource("roads-major", EMPTY_FC))
            val round = arrayOf(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
            st.addLayer(LineLayer("rc-local", "roads-local").withProperties(lineColor("#E1E5EA"), lineWidth(4f), *round))
            st.addLayer(LineLayer("rc-main", "roads-main").withProperties(lineColor("#D3D8DE"), lineWidth(6.5f), *round))
            st.addLayer(LineLayer("rc-major", "roads-major").withProperties(lineColor("#E8B54A"), lineWidth(8f), *round))
            st.addLayer(LineLayer("rf-local", "roads-local").withProperties(lineColor("#FFFFFF"), lineWidth(2.4f), *round))
            st.addLayer(LineLayer("rf-main", "roads-main").withProperties(lineColor("#FFFFFF"), lineWidth(4.5f), *round))
            st.addLayer(LineLayer("rf-major", "roads-major").withProperties(lineColor("#FFD66B"), lineWidth(6f), *round))
            st.addSource(GeoJsonSource("zones", EMPTY_FC))
            st.addLayer(FillLayer("zones-fill", "zones").withProperties(fillColor(org.maplibre.android.style.expressions.Expression.toColor(org.maplibre.android.style.expressions.Expression.get("c"))), fillOpacity(0.2f)))
            st.addLayer(LineLayer("zones-line", "zones").withProperties(lineColor(org.maplibre.android.style.expressions.Expression.toColor(org.maplibre.android.style.expressions.Expression.get("c"))), lineWidth(2f)))
            st.addSource(GeoJsonSource("zone-roads", EMPTY_FC))
            st.addLayer(
                LineLayer("zone-roads-line", "zone-roads").withProperties(
                    lineColor(org.maplibre.android.style.expressions.Expression.toColor(org.maplibre.android.style.expressions.Expression.get("c"))),
                    lineWidth(5f), lineOpacity(0.85f), *round
                )
            )
            st.addSource(GeoJsonSource("alts", EMPTY_FC))
            st.addLayer(
                LineLayer("alts-casing", "alts").withProperties(lineColor("#FFFFFF"), lineWidth(9f), *round)
            )
            st.addLayer(LineLayer("alts-line", "alts").withProperties(lineColor("#94A3B8"), lineWidth(5.5f), *round))
            st.addSource(GeoJsonSource("route", EMPTY_FC))
            st.addLayer(
                LineLayer("route-casing", "route").withProperties(
                    lineColor("#FFFFFF"), lineWidth(11f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            st.addLayer(
                LineLayer("route-line", "route").withProperties(
                    lineColor("#2563EB"), lineWidth(6.5f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            st.addSource(GeoJsonSource("origin", EMPTY_FC))
            st.addLayer(
                CircleLayer("origin-dot", "origin").withProperties(
                    circleColor("#FFFFFF"), circleRadius(8f),
                    circleStrokeColor("#2563EB"), circleStrokeWidth(4f)
                )
            )
            st.addSource(GeoJsonSource("cams", EMPTY_FC))
            for ((kind, color) in listOf("speed" to "#7C3AED", "redlight" to "#EA580C")) {
                val layer = CircleLayer("cams-$kind", "cams").withProperties(
                    circleColor(color), circleRadius(6f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
                )
                layer.setFilter(org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("k"),
                    org.maplibre.android.style.expressions.Expression.literal(kind)))
                layer.setMinZoom(12.5f)
                st.addLayer(layer)
            }
            st.addSource(GeoJsonSource("rep", EMPTY_FC))
            st.addLayer(
                CircleLayer("rep-dot", "rep").withProperties(
                    circleColor("#DC2626"), circleRadius(9f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                )
            )
            st.addSource(GeoJsonSource("pin", EMPTY_FC))
            st.addLayer(
                CircleLayer("pin-dot", "pin").withProperties(
                    circleColor("#F59E0B"), circleRadius(8f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                )
            )
            st.addSource(GeoJsonSource("dest", EMPTY_FC))
            st.addLayer(
                CircleLayer("dest-dot", "dest").withProperties(
                    circleColor("#111827"), circleRadius(9f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                )
            )
            st.addSource(GeoJsonSource("me", EMPTY_FC))
            st.addLayer(
                CircleLayer("me-dot", "me").withProperties(
                    circleColor("#2563EB"), circleRadius(8f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
                )
            )
            style = st
            shownZones = null
            shownRoute = null
            shownRoutes = null
            shownRoads = null
            shownLines = ""
            last?.let { render(it) }
            reportViewport(map, onViewport)
        }
        map.addOnCameraIdleListener { reportViewport(map, onViewport) }
        map.addOnMapClickListener { ll ->
            onTap(LatLon(ll.latitude, ll.longitude))
            false
        }
        map.addOnMapLongClickListener { ll ->
            onLongPress(LatLon(ll.latitude, ll.longitude))
            true
        }
    }

    private fun reportViewport(map: MapLibreMap, cb: (Double, Double, Double, Double, Double) -> Unit) {
        if (style == null) return
        val b = map.projection.visibleRegion.latLngBounds
        cb(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast, map.cameraPosition.zoom)
    }

    fun render(s: UiState) {
        last = s
        val st = style ?: return
        val m = map ?: return

        if (s.zoneSt !== shownZones) {
            st.getSourceAs<GeoJsonSource>("zones")?.setGeoJson(zonesJson(s.zoneSt))
            shownZones = s.zoneSt
        }
        if (s.roads !== shownRoads && s.roads != null) {
            st.getSourceAs<GeoJsonSource>("roads-major")?.setGeoJson(s.roads.major)
            st.getSourceAs<GeoJsonSource>("roads-main")?.setGeoJson(s.roads.main)
            st.getSourceAs<GeoJsonSource>("roads-local")?.setGeoJson(s.roads.local)
            shownRoads = s.roads
        }
        if (s.routes !== shownRoutes || s.route !== shownRoute) {
            val others = s.routes.filter { it !== s.route }
            st.getSourceAs<GeoJsonSource>("alts")?.setGeoJson(altsJson(if (s.route == null) emptyList() else others))
            shownRoutes = s.routes
        }
        if (s.route !== shownRoute) {
            st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeJson(s.route))
            shownRoute = s.route
        }
        if (s.zoneLines != shownLines) {
            st.getSourceAs<GeoJsonSource>("zone-roads")?.setGeoJson(s.zoneLines ?: EMPTY_FC)
            shownLines = s.zoneLines
        }
        if (s.cams != shownCams) {
            st.getSourceAs<GeoJsonSource>("cams")?.setGeoJson(s.cams ?: EMPTY_FC)
            shownCams = s.cams
        }
        st.getSourceAs<GeoJsonSource>("rep")?.setGeoJson(multiPointJson(listOfNotNull(s.reportA, s.reportB)))
        st.getSourceAs<GeoJsonSource>("origin")?.setGeoJson(pointJson(s.origin))
        st.getSourceAs<GeoJsonSource>("pin")?.setGeoJson(pointJson(s.pin))
        st.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(pointJson(s.dest))
        st.getSourceAs<GeoJsonSource>("me")?.setGeoJson(pointJson(s.me))

        val me = s.me
        val route = s.route
        if (s.recenter != lastRecenter) {
            lastRecenter = s.recenter
            if (me != null && !s.navigating) {
                m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(me.lat, me.lon)).zoom(15.5).bearing(0.0).tilt(0.0).build()
                    ), 600
                )
                return
            }
        }
        if (s.navigating && me != null) {
            val pos = CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon)).zoom(navZoom).tilt(45.0).bearing(s.bearing.toDouble())
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 700)
        } else if (route != null && route !== fitted) {
            fitted = route
            val b = LatLngBounds.Builder()
            route.points.forEach { b.include(LatLng(it.lat, it.lon)) }
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 70, padTop + 40, 70, padBottom + 40), 800)
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

    private fun zonesJson(z: List<Pair<Zone, Int>>): String {
        val feats = JSONArray()
        z.filter { it.first.ring.size >= 3 }.forEach { (zone, cat) ->
            val ring = JSONArray()
            zone.ring.forEach { ring.put(JSONArray().put(it.lon).put(it.lat)) }
            zone.ring.first().let { ring.put(JSONArray().put(it.lon).put(it.lat)) }
            feats.put(
                JSONObject().put("type", "Feature")
                    .put("properties", JSONObject().put("name", zone.name).put("c", Zone.statusColor(cat)))
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

    private fun altsJson(rs: List<Route>): String {
        if (rs.isEmpty()) return EMPTY_FC
        val lines = JSONArray()
        rs.forEach { r ->
            val c = JSONArray()
            r.points.forEach { c.put(JSONArray().put(it.lon).put(it.lat)) }
            lines.put(c)
        }
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", lines)).toString()
    }

    private fun multiPointJson(ps: List<LatLon>): String {
        if (ps.isEmpty()) return EMPTY_FC
        val c = JSONArray()
        ps.forEach { c.put(JSONArray().put(it.lon).put(it.lat)) }
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "MultiPoint").put("coordinates", c)).toString()
    }

    private fun pointJson(p: LatLon?): String {
        if (p == null) return EMPTY_FC
        return JSONObject().put("type", "Feature").put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
            .toString()
    }
}

@Composable
fun MapViewHost(
    state: UiState,
    controller: MapController,
    onLongPress: (LatLon) -> Unit,
    onTap: (LatLon) -> Unit,
    onViewport: (Double, Double, Double, Double, Double) -> Unit
) {
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
            mapView.also { mv -> mv.getMapAsync { map -> controller.attach(map, onLongPress, onTap, onViewport) } }
        },
        modifier = Modifier.fillMaxSize(),
        update = { controller.render(state) }
    )
}
