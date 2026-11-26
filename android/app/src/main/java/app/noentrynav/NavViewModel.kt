package app.noentrynav

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class NavViewModel(app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {

    private val _s = MutableStateFlow(UiState(message = "Loading offline Delhi map… (first start takes a minute)"))
    val state: StateFlow<UiState> = _s
    val mapController = MapController()

    private val engine = Engine(app)
    private var roadsJob: kotlinx.coroutines.Job? = null

    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var ttsReady = false

    private var progressIdx = 0
    private var offCount = 0
    private var lastReroute = 0L
    private var lastZoneCheck = 0L
    private val announced = HashSet<Int>()
    private var locStarted = false

    init {
        viewModelScope.launch {
            try {
                engine.load()
                _s.update { it.copy(ready = true, message = null, zones = engine.zonesFor(it.vehicle.key)) }
            } catch (e: Exception) {
                _s.update { it.copy(message = "Map data could not be loaded: ${e.message}") }
            }
        }
    }

    /** Called by the map when the camera stops moving: draw the roads that are in view. */
    fun onViewport(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoom: Double) {
        if (!_s.value.ready || zoom < 9.0) return
        roadsJob?.cancel()
        roadsJob = viewModelScope.launch {
            try {
                val padLat = (maxLat - minLat) * 0.2
                val padLon = (maxLon - minLon) * 0.2
                val r = engine.roads(minLat - padLat, minLon - padLon, maxLat + padLat, maxLon + padLon, zoom)
                _s.update { it.copy(roads = r) }
            } catch (e: Exception) { /* keep previous roads */ }
        }
    }

    // ---------- TTS ----------
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("en", "IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    // ---------- Location ----------
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onLocation(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locStarted) return
        val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            var any = false
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 3f, listener, Looper.getMainLooper())
                    any = true
                }
            }
            locStarted = any
            if (!any) _s.update { it.copy(message = "Turn on device location (GPS) to navigate.") }
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onLocation(last)
        } catch (e: SecurityException) {
            _s.update { it.copy(message = "Location permission is needed.") }
        }
    }

    private fun onLocation(loc: Location) {
        val p = LatLon(loc.latitude, loc.longitude)
        _s.update {
            it.copy(
                me = p,
                bearing = if (loc.hasBearing() && loc.speed > 1f) loc.bearing else it.bearing
            )
        }
        if (_s.value.navigating) updateProgress(p)
    }

    // ---------- UI actions ----------
    fun setVehicle(v: Vehicle) {
        _s.update { it.copy(vehicle = v, route = null) }
        refreshZones()
        if (_s.value.dest != null) requestRoute()
    }

    fun search(q: String) {
        if (q.isBlank()) return
        viewModelScope.launch {
            _s.update { it.copy(busy = true, message = null) }
            try {
                val r = engine.search(q.trim())
                _s.update {
                    it.copy(busy = false, results = r, message = if (r.isEmpty()) "No places found in Delhi NCR." else null)
                }
            } catch (e: Exception) {
                _s.update { it.copy(busy = false, message = "Search failed: ${e.message}") }
            }
        }
    }

    fun pickPlace(p: Place) {
        _s.update { it.copy(results = emptyList()) }
        setDestination(p.pos, p.name)
    }

    fun setDestination(p: LatLon, name: String = "Dropped pin") {
        if (_s.value.navigating) return
        _s.update { it.copy(dest = p, destName = name, route = null, message = null, results = emptyList()) }
        requestRoute()
    }

    fun clearRoute() {
        _s.update { it.copy(dest = null, destName = "", route = null, message = null) }
    }

    fun refreshZones() {
        if (!_s.value.ready) return
        _s.update { it.copy(zones = engine.zonesFor(it.vehicle.key)) }
    }

    fun requestRoute(auto: Boolean = false) {
        val st = _s.value
        val dest = st.dest ?: return
        if (!st.ready) return
        val me = st.me
        if (me == null) {
            _s.update { it.copy(message = "Waiting for GPS fix…") }
            return
        }
        viewModelScope.launch {
            _s.update { it.copy(busy = true, message = null) }
            try {
                val r = engine.route(me, dest, _s.value.vehicle)
                progressIdx = 0
                offCount = 0
                announced.clear()
                _s.update { it.copy(route = r, busy = false, remainingM = r.cum.last()) }
                if (auto) {
                    announced.add(0)
                    _s.value.me?.let { updateProgress(it) }
                }
            } catch (e: Exception) {
                _s.update { it.copy(busy = false, message = e.message ?: "Routing failed") }
            }
        }
    }

    fun startNav() {
        val r = _s.value.route ?: return
        progressIdx = 0
        offCount = 0
        announced.clear()
        announced.add(0)
        lastZoneCheck = SystemClock.elapsedRealtime()
        speak(r.maneuvers.firstOrNull()?.verbal ?: "Starting navigation")
        _s.update { it.copy(navigating = true, message = null) }
        _s.value.me?.let { updateProgress(it) }
    }

    fun stopNav() {
        tts?.stop()
        _s.update { it.copy(navigating = false) }
    }

    // ---------- Navigation core ----------
    private fun updateProgress(p: LatLon) {
        val r = _s.value.route ?: return
        val pts = r.points
        if (pts.size < 2) return

        var best = Double.MAX_VALUE
        var bi = progressIdx
        var bt = 0.0
        fun scan(from: Int, to: Int) {
            for (i in from..to) {
                val (d, t) = Geo.toSegment(p, pts[i], pts[i + 1])
                if (d < best) { best = d; bi = i; bt = t }
            }
        }
        scan(maxOf(0, progressIdx - 2), minOf(pts.size - 2, progressIdx + 80))
        if (best > 50.0) scan(0, pts.size - 2)

        if (best > 50.0) {
            offCount++
            if (offCount >= 3) rerouteNow()
            return
        }
        offCount = 0
        progressIdx = bi

        val along = r.cum[bi] + bt * (r.cum[bi + 1] - r.cum[bi])
        val remaining = r.cum.last() - along
        if (remaining < 25.0) { finish(); return }
        if (r.maneuvers.isEmpty()) {
            _s.update { it.copy(remainingM = remaining) }
            return
        }

        var mi = r.maneuvers.indexOfFirst { it.beginIdx > bi }
        if (mi < 0) mi = r.maneuvers.lastIndex
        val m = r.maneuvers[mi]
        val dM = (r.cum[m.beginIdx.coerceIn(0, pts.size - 1)] - along).coerceAtLeast(0.0)
        if (dM <= 250.0 && announced.add(mi * 2)) speak(m.verbal)
        if (dM <= 35.0 && announced.add(mi * 2 + 1)) speak(m.text)
        _s.update { it.copy(nextText = m.text, nextDistM = dM, remainingM = remaining) }
        maybeCheckZones()
    }

    private fun finish() {
        speak("You have arrived at your destination")
        _s.update { it.copy(navigating = false, route = null, dest = null, destName = "", message = "You have arrived.") }
    }

    private fun rerouteNow() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReroute < 10_000) return
        lastReroute = now
        offCount = 0
        speak("Recalculating")
        requestRoute(auto = true)
    }

    /** Every 2 min: re-fetch active zones; if a zone that started after routing blocks the rest of the route, reroute. */
    private fun maybeCheckZones() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastZoneCheck < 60_000) return
        lastZoneCheck = now
        viewModelScope.launch {
            val zs = engine.zonesFor(_s.value.vehicle.key)
            _s.update { it.copy(zones = zs) }
            val r = _s.value.route ?: return@launch
            val appliedIds = r.applied.map { it.first }.toSet()
            val hit = zs.firstOrNull { z ->
                z.id !in appliedIds && Geo.routeHitsPolygon(r.points, progressIdx, z.ring)
            }
            if (hit != null) {
                speak("No entry zone ahead. Recalculating.")
                lastReroute = SystemClock.elapsedRealtime()
                requestRoute(auto = true)
            }
        }
    }

    override fun onCleared() {
        try {
            val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(listener)
        } catch (e: Exception) {}
        tts?.shutdown()
        super.onCleared()
    }
}
