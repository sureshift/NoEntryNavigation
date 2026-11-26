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

class NavViewModel(private val app: Application) : AndroidViewModel(app), TextToSpeech.OnInitListener {

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
        _s.update { it.copy(crash = CrashLog.take(app)) }
        load()
        startTicker()
    }

    private fun load() {
        _s.update { it.copy(loadError = null, message = "Loading offline Delhi map… (first start takes a minute)") }
        viewModelScope.launch {
            try {
                engine.load()
                val warn = engine.ruleWarnings
                _s.update {
                    it.copy(
                        ready = true, loadError = null,
                        message = if (warn.isEmpty()) null else "${warn.size} gazette rule${if (warn.size > 1) "s are" else " is"} not applied - see Rules",
                        notices = engine.notices, allZones = engine.allZones
                    )
                }
                refreshZones()
            } catch (e: Throwable) {
                _s.update { it.copy(ready = false, message = null, loadError = e.message ?: "Map data could not be loaded") }
            }
        }
    }

    fun retryLoad() = load()

    fun dismissCrash() { _s.update { it.copy(crash = null) } }

    /** Every minute: a restriction window may have started or ended. */
    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                try {
                    if (_s.value.ready) {
                        val st = _s.value
                        val zs = engine.zonesFor(st.vehicle.key)
                        if (zs.map { it.id } != st.zones.map { it.id }) {
                            _s.update { it.copy(zones = zs) }
                            updateZoneLines(zs)
                            if (!st.navigating && st.route != null) requestRoute()
                        }
                    }
                } catch (e: Exception) { /* keep ticking */ }
            }
        }
    }

    private suspend fun updateZoneLinesNow(zs: List<Zone>) {
        val g = try { engine.zoneLines(zs) } catch (e: Exception) { null }
        _s.update { it.copy(zoneLines = g) }
    }

    private fun updateZoneLines(zs: List<Zone>) {
        viewModelScope.launch { updateZoneLinesNow(zs) }
    }

    /** Called by the map when the camera stops moving: draw the roads that are in view. */
    fun onViewport(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoom: Double) {
        if (!_s.value.ready || zoom < 10.0) return
        roadsJob?.cancel()
        roadsJob = viewModelScope.launch {
            try {
                val padLat = (maxLat - minLat) * 0.2
                val padLon = (maxLon - minLon) * 0.2
                val r = engine.roads(minLat - padLat, minLon - padLon, maxLat + padLat, maxLon + padLon, zoom)
                _s.update { it.copy(roads = r) }
            } catch (e: Exception) {
                _s.update { it.copy(message = "Could not draw map: ${e.message}") }
            }
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
        if (ttsReady && !_s.value.muted && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
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
            if (!any) _s.update { it.copy(message = "Location is off. You can still set a start point and preview routes.") }
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

    fun edit(f: Field?) {
        if (_s.value.navigating) return
        _s.update { it.copy(editing = f, results = emptyList(), message = null) }
    }

    /** A search result was tapped: it becomes the start or the destination depending on the field being edited. */
    fun pickPlace(p: Place) {
        val name = p.name.substringBefore(" · ")
        if (_s.value.editing == Field.FROM) setOrigin(p.pos, name) else setDestination(p.pos, name)
    }

    fun setOrigin(p: LatLon, name: String = "Dropped pin") {
        if (_s.value.navigating) return
        _s.update {
            it.copy(
                origin = p, originName = name, route = null, message = null, results = emptyList(), pin = null,
                editing = if (it.dest == null) Field.TO else null
            )
        }
        if (_s.value.dest != null) requestRoute()
    }

    fun useCurrentLocation() {
        if (_s.value.navigating) return
        _s.update { it.copy(origin = null, originName = "", route = null, results = emptyList(), editing = null) }
        if (_s.value.dest != null) requestRoute()
    }

    fun setDestination(p: LatLon, name: String = "Dropped pin") {
        if (_s.value.navigating) return
        _s.update { it.copy(dest = p, destName = name, route = null, message = null, results = emptyList(), pin = null, editing = null) }
        requestRoute()
    }

    fun swap() {
        val st = _s.value
        if (st.navigating) return
        val d = st.dest ?: return
        val from = st.origin ?: st.me
        if (from == null) { _s.update { it.copy(message = "Turn on GPS, or set a start point, to swap.") }; return }
        val fromName = if (st.origin == null) "My location" else st.originName
        _s.update {
            it.copy(origin = d, originName = it.destName, dest = from, destName = fromName, route = null, message = null)
        }
        requestRoute()
    }

    fun onMapLongPress(p: LatLon) {
        if (_s.value.navigating) return
        _s.update { it.copy(pin = p) }
    }

    fun dismissPin() { _s.update { it.copy(pin = null) } }

    fun showRules(show: Boolean) { _s.update { it.copy(screen = if (show) Screen.RULES else Screen.MAP) } }

    fun clearRoute() {
        _s.update { it.copy(dest = null, destName = "", origin = null, originName = "", route = null, message = null, editing = null, pin = null) }
    }

    fun refreshZones() {
        if (!_s.value.ready) return
        viewModelScope.launch {
            val zs = engine.zonesFor(_s.value.vehicle.key)
            _s.update { it.copy(zones = zs) }
            updateZoneLinesNow(zs)
        }
    }

    fun requestRoute(auto: Boolean = false, thenStart: Boolean = false) {
        val st = _s.value
        val dest = st.dest ?: return
        if (!st.ready) return
        val from = st.origin ?: st.me
        if (from == null) {
            _s.update { it.copy(message = "Waiting for GPS fix… or set a start point.") }
            return
        }
        viewModelScope.launch {
            _s.update { it.copy(busy = true, message = null) }
            try {
                val zs = engine.zonesFor(_s.value.vehicle.key)
                val r = engine.route(from, dest, _s.value.vehicle)
                progressIdx = 0
                offCount = 0
                announced.clear()
                _s.update { it.copy(route = r, busy = false, remainingM = r.cum.last(), zones = zs) }
                updateZoneLinesNow(zs)
                if (auto) {
                    announced.add(0)
                    _s.value.me?.let { updateProgress(it) }
                }
                if (thenStart) startNav()
            } catch (e: Throwable) {
                _s.update { it.copy(busy = false, message = e.message ?: "Routing failed") }
            }
        }
    }

    fun startNav() {
        val st = _s.value
        val r = st.route ?: return
        val me = st.me
        val o = st.origin
        if (o != null) {
            if (me == null) {
                _s.update { it.copy(message = "Turn on GPS to start navigation. You can still preview this route.") }
                return
            }
            if (Geo.dist(me, o) > 400.0) {
                // the manual start is not where the truck is: navigate from the GPS position instead
                _s.update { it.copy(origin = null, originName = "", route = null, message = "Routing from your current location") }
                requestRoute(thenStart = true)
                return
            }
        }
        progressIdx = 0
        offCount = 0
        announced.clear()
        announced.add(0)
        lastZoneCheck = SystemClock.elapsedRealtime()
        speak(r.maneuvers.firstOrNull()?.verbal ?: "Starting navigation")
        _s.update { it.copy(navigating = true, message = null) }
        _s.value.me?.let { updateProgress(it) }
    }

    fun recenter() {
        _s.update { it.copy(recenter = it.recenter + 1) }
    }

    fun toggleMute() {
        tts?.stop()
        _s.update { it.copy(muted = !it.muted) }
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
        _s.update {
            it.copy(nextText = m.text, nextDistM = dM, nextTurn = m.turn, nextArrive = m.arrive, remainingM = remaining)
        }
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

    /** Every minute while driving: if the set of active rules changed since the route was made, reroute. */
    private fun maybeCheckZones() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastZoneCheck < 60_000) return
        lastZoneCheck = now
        viewModelScope.launch {
            try {
                val zs = engine.zonesFor(_s.value.vehicle.key)
                val r = _s.value.route ?: return@launch
                val before = r.applied.map { it.first }.toSet()
                val after = zs.map { it.id }.toSet()
                _s.update { it.copy(zones = zs) }
                if (before != after) {
                    updateZoneLinesNow(zs)
                    speak("Restrictions have changed. Recalculating.")
                    lastReroute = SystemClock.elapsedRealtime()
                    requestRoute(auto = true)
                }
            } catch (e: Exception) { /* try again next minute */ }
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
