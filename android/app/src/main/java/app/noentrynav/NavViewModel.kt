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
                        notices = engine.notices, allZones = engine.allZones,
                        cams = engine.camsJson.ifEmpty { null }
                    )
                }
                refreshZones()
                _s.update { it.copy(feedUpdated = engine.feedUpdated) }
                // pick up new gazette notifications / diversions in the background (at most every 6 hours)
                if (System.currentTimeMillis() - engine.lastFeedCheck() > 6 * 3600_000L) refreshFeed(manual = false)
            } catch (e: Throwable) {
                _s.update { it.copy(ready = false, message = null, loadError = e.message ?: "Map data could not be loaded") }
            }
        }
    }

    fun retryLoad() = load()

    /** Fetch new gazette notifications and Delhi Traffic Police diversions / temporary no-entry from the online feed. */
    fun refreshFeed(manual: Boolean = true) {
        if (_s.value.feedBusy || !_s.value.ready) return
        if (!manual && _s.value.navigating) return
        _s.update { it.copy(feedBusy = true, feedStatus = if (manual) "Checking for updates…" else null) }
        viewModelScope.launch {
            val (feedChanged, msg) = try { engine.refreshFeed() } catch (e: Throwable) { Pair(false, "Update failed") }
            val sharedChanged = try { engine.syncCommunity() } catch (e: Throwable) { false }
            val changed = feedChanged || sharedChanged
            _s.update {
                it.copy(
                    feedBusy = false, feedUpdated = engine.feedUpdated, notices = engine.notices, allZones = engine.allZones,
                    feedStatus = if (manual || changed) msg else null
                )
            }
            if (changed) {
                refreshZones()
                if (!_s.value.navigating && _s.value.dest != null) requestRoute()
            }
        }
    }

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
                        val sig = engine.zoneStatuses(st.vehicle.key).map { it.first.id to it.second }
                        if (sig != st.zoneSt.map { it.first.id to it.second } || zs.map { it.id } != st.zones.map { it.id }) {
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
        val st = engine.zoneStatuses(_s.value.vehicle.key)
        val g = try { engine.zoneLines(st) } catch (e: Exception) { null }
        _s.update { it.copy(zoneLines = g, zoneSt = st) }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // a newer viewport replaced this one: not an error
            } catch (e: Exception) {
                if (e is IllegalStateException) return@launch   // database still opening
                _s.update { it.copy(message = "Could not draw map: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()) }
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
                bearing = if (loc.hasBearing() && loc.speed > 1f) loc.bearing else it.bearing,
                speedKph = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else it.speedKph
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

    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { _s.update { it.copy(results = emptyList(), busy = false, message = null) }; return }
        searchJob = viewModelScope.launch {
            try {
                val r = engine.search(q.trim(), _s.value.me ?: _s.value.origin ?: LatLon(28.6139, 77.2090))
                _s.update { it.copy(busy = false, results = r, message = if (r.isEmpty()) "Nothing found offline. Try \"Search online\"." else null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _s.update { it.copy(busy = false, message = "Search failed: ${e.message}") }
            }
        }
    }

    fun searchOnline(q: String) {
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _s.update { it.copy(busy = true, message = null) }
            try {
                val r = engine.searchOnline(q)
                _s.update { it.copy(busy = false, results = r, message = if (r.isEmpty()) "OpenStreetMap found nothing for that in Delhi NCR." else null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                _s.update { it.copy(busy = false, message = "No internet connection for online search") }
            } catch (e: Exception) {
                _s.update { it.copy(busy = false, message = "Online search failed: ${e.message}") }
            }
        }
    }

    fun edit(f: Field?) {
        if (_s.value.navigating) return
        _s.update { it.copy(editing = f, results = emptyList(), message = null) }
    }

    /** A search result was tapped: it becomes the start or the destination depending on the field being edited. */
    fun pickPlace(p: Place) {
        val name = p.name
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
        if (_s.value.navigating || _s.value.report == ReportStep.DETAILS) return
        _s.update { it.copy(pin = p) }
    }

    /** A plain tap: only used while placing the start / end of a reported no-entry road. */
    fun onMapTap(p: LatLon) {
        val st = _s.value
        if (st.navigating) return
        if (st.report == ReportStep.START || st.report == ReportStep.END) _s.update { it.copy(pin = p) }
    }

    /** "Use my current location" while placing a point of a report. */
    /** Start/end point typed or pasted as coordinates, a Google Maps link, or a Google Plus Code. */
    fun setReportPointFromText(text: String) {
        val st = _s.value
        val p = CoordParse.parse(text, st.me ?: st.reportA ?: st.origin)
        if (p == null) {
            _s.update { it.copy(message = "GPS: could not read that. Use latitude, longitude (28.6139, 77.2090), a Google Maps link or a Plus Code, inside Delhi NCR.") }
            return
        }
        mapController.flyTo(p)
        markReportPoint(p)
    }

    fun useGpsForReport() {
        val me = _s.value.me
        if (me == null) { _s.update { it.copy(message = "Waiting for GPS fix… or tap the map instead.") }; return }
        markReportPoint(me)
    }

    /** One-tap reporting while driving: first tap marks where the no-entry starts, second tap where it ends. */
    fun quickReport() {
        val st = _s.value
        val me = st.me
        if (me == null) { _s.update { it.copy(message = "Waiting for GPS fix…") }; return }
        when (st.report) {
            ReportStep.NONE -> _s.update { it.copy(report = ReportStep.END, reportA = me, reportB = null, message = null) }
            ReportStep.END -> _s.update { it.copy(reportB = me, report = ReportStep.DETAILS, message = null) }
            else -> {}
        }
    }

    fun dismissPin() { _s.update { it.copy(pin = null) } }

    /** The "Route details" page where anyone can fill in no-entry roads for the current route. */
    fun showForm(show: Boolean) {
        _s.update { it.copy(screen = if (show) Screen.FORM else Screen.MAP, message = null, pin = null, editing = null, report = ReportStep.NONE) }
    }

    /** Fill one end of the no-entry stretch from text, GPS, or the route's own start/end. */
    fun setFormPoint(start: Boolean, p: LatLon) {
        _s.update { if (start) it.copy(reportA = p, message = null) else it.copy(reportB = p, message = null) }
    }

    fun setFormPointFromText(start: Boolean, text: String) {
        val st = _s.value
        val p = CoordParse.parse(text, st.me ?: st.reportA ?: st.origin ?: st.dest)
        if (p == null) {
            _s.update { it.copy(message = "Could not read that. Use latitude, longitude (28.6139, 77.2090), a Google Maps link or a Plus Code, inside Delhi NCR.") }
            return
        }
        setFormPoint(start, p)
    }

    fun clearFormPoint(start: Boolean) { _s.update { if (start) it.copy(reportA = null) else it.copy(reportB = null) } }

    fun communityOn(): Boolean = engine.communityEndpoint().isNotEmpty()
    fun shareReportsOn(): Boolean = engine.shareReportsOn()
    fun setShareReports(on: Boolean) = engine.setShareReports(on)

    fun clearFormPoints() { _s.update { it.copy(reportA = null, reportB = null) } }

    fun showRules(show: Boolean) { _s.update { it.copy(screen = if (show) Screen.RULES else Screen.MAP) } }

    fun selectRoute(i: Int) {
        val st = _s.value
        if (st.navigating) return
        val r = st.routes.getOrNull(i) ?: return
        _s.update { it.copy(route = r, remainingM = r.cum.last()) }
    }

    // ---------- driver reports ----------
    fun beginReport() {
        if (_s.value.navigating) return
        _s.update {
            it.copy(
                screen = Screen.MAP, report = ReportStep.START, reportA = null, reportB = null, pin = null, editing = null,
                message = "Tap the map where the no-entry road STARTS."
            )
        }
    }

    fun cancelReport() {
        _s.update { it.copy(report = ReportStep.NONE, reportA = null, reportB = null, pin = null, message = null) }
    }

    fun markReportPoint(p: LatLon) {
        when (_s.value.report) {
            ReportStep.START -> _s.update {
                it.copy(reportA = p, report = ReportStep.END, pin = null, message = "Now tap where the no-entry road ENDS (same road).")
            }
            ReportStep.END -> _s.update { it.copy(reportB = p, report = ReportStep.DETAILS, pin = null, message = null) }
            else -> {}
        }
    }

    /** windowKey: 0 = all day, 1 = 07:00-23:00, 2 = peak hours, 3 = night 23:00-07:00 */
    fun saveReport(vehicles: Set<String>, windowKey: Int, name: String) {
        val st = _s.value
        val a = st.reportA ?: return
        val b = st.reportB ?: return
        val wins = when (windowKey) {
            1 -> listOf(intArrayOf(420, 1380))
            2 -> listOf(intArrayOf(420, 660), intArrayOf(1020, 1380))
            3 -> listOf(intArrayOf(1380, 420))
            else -> listOf(intArrayOf(0, 1440))
        }
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val err = try { engine.addReport(a, b, vehicles, wins, name) } catch (e: Throwable) { e.message ?: "Could not save" }
            if (err != null) {
                _s.update { it.copy(busy = false, message = err) }
            } else {
                _s.update {
                    it.copy(busy = false, report = ReportStep.NONE, reportA = null, reportB = null, allZones = engine.allZones,
                        message = "Thanks. Your no-entry report is saved on this phone and used for routing.")
                }
                refreshZones()
                if (_s.value.dest != null) requestRoute(auto = _s.value.navigating)
            }
        }
    }

    fun deleteReport(id: String) {
        viewModelScope.launch {
            try { engine.deleteReport(id) } catch (_: Exception) {}
            _s.update { it.copy(allZones = engine.allZones) }
            refreshZones()
            if (_s.value.dest != null) requestRoute()
        }
    }

    fun exportReports(): String = engine.exportReports()

    /** Message for sharing the current route (with the driver-reported no-entry roads) through any app. */
    fun routeShareText(): String? {
        val st = _s.value
        val r = st.route ?: return null
        val from = st.origin ?: st.me ?: return null
        val to = st.dest ?: return null
        val summary = "%.1f km, %s".format(r.distKm, fmtTime(r.timeSec))
        return engine.exportRoute(from, if (st.origin == null) "Start" else st.originName.ifBlank { "Start" }, to, st.destName.ifBlank { "Destination" }, st.vehicle, summary)
    }

    /** A message received from another driver: loads the route (if any) and imports the no-entry reports in it. */
    fun handleShared(text: String) {
        viewModelScope.launch {
            var waited = 0
            while (!_s.value.ready && waited < 120_000) { kotlinx.coroutines.delay(300); waited += 300 }
            if (!_s.value.ready) return@launch
            val sr = engine.parseSharedRoute(text)
            val n = try { engine.importReports(text) } catch (_: Exception) { -1 }
            if (sr == null && n < 0) { _s.update { it.copy(message = "That message has no NoEntry Nav route or reports.") }; return@launch }
            val veh = VEHICLES.firstOrNull { it.key == sr?.veh } ?: _s.value.vehicle
            _s.update {
                it.copy(
                    allZones = engine.allZones, navigating = false,
                    screen = if (sr != null) Screen.FORM else Screen.MAP, vehicle = veh, origin = sr?.from ?: it.origin, originName = sr?.fromName ?: it.originName,
                    dest = sr?.to ?: it.dest, destName = sr?.toName ?: it.destName, route = null, routes = emptyList(), editing = null, pin = null,
                    message = (if (sr != null) "Shared route loaded. " else "") + (if (n > 0) "Added $n no-entry report${if (n > 1) "s" else ""}. " else "") +
                        (if (sr != null) "Tap \"Add no-entry\" to mark roads on this route." else "")
                )
            }
            refreshZones()
            if (sr != null) requestRoute()
        }
    }

    fun importReports(text: String) {
        if (text.contains("noentry_route")) { handleShared(text); return }
        viewModelScope.launch {
            val n = try { engine.importReports(text) } catch (_: Exception) { -1 }
            _s.update {
                it.copy(
                    allZones = engine.allZones,
                    message = when {
                        n < 0 -> "No driver reports found in the copied text."
                        n == 0 -> "Nothing new to import (already added, or the roads were not found)."
                        else -> "Imported $n driver report${if (n > 1) "s" else ""}."
                    }
                )
            }
            if (n > 0) { refreshZones(); if (_s.value.dest != null) requestRoute() }
        }
    }

    fun clearRoute() {
        _s.update { it.copy(dest = null, destName = "", origin = null, originName = "", route = null, routes = emptyList(), message = null, editing = null, pin = null) }
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
                val rs = engine.routes(from, dest, _s.value.vehicle)
                // keep the alternative the driver had picked when the route is recalculated while driving
                val keep = if (auto) rs.indexOfFirst { it.via == _s.value.route?.via && it.label == _s.value.route?.label } else -1
                val r = rs[if (keep >= 0) keep else 0]
                progressIdx = 0
                offCount = 0
                announced.clear()
                _s.update { it.copy(route = r, routes = rs, busy = false, remainingM = r.cum.last(), zones = zs) }
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

    // ---------- Speed limit and camera warnings ----------
    private var limitBusy = false
    private var lastLimitAt = 0L
    private val camAnnounced = HashSet<Int>()

    private fun updateSpeedInfo(p: LatLon, r: Route, bi: Int, along: Double) {
        val now = SystemClock.elapsedRealtime()
        if (!limitBusy && now - lastLimitAt > 2500) {
            limitBusy = true; lastLimitAt = now
            viewModelScope.launch {
                try {
                    val res = engine.limitAt(p, Profile(false, 0.0, 0.0, 1.0))
                    _s.update { it.copy(limit = res?.first ?: 0, limitPosted = res?.second ?: false) }
                } catch (_: Exception) {
                } finally { limitBusy = false }
            }
        }
        // nearest camera lying on the route within the next 600 m
        var bestD = -1.0; var bestLim = 0; var bestId = -1
        val pts = r.points
        for (cm in engine.cams) {
            if (Math.abs(cm.pos.lat - p.lat) > 0.0065 || Math.abs(cm.pos.lon - p.lon) > 0.0075) continue
            var idx = -1; var dd = 35.0
            val end = minOf(pts.size - 2, bi + 60)
            for (i in bi..end) {
                if (r.cum[i] - along > 650.0) break
                val (d, _) = Geo.toSegment(cm.pos, pts[i], pts[i + 1])
                if (d < dd) { dd = d; idx = i }
            }
            if (idx < 0) continue
            val ahead = r.cum[idx] + Geo.dist(pts[idx], cm.pos) - along
            if (ahead < -10.0 || ahead > 600.0) continue
            if (bestD < 0 || ahead < bestD) { bestD = ahead.coerceAtLeast(0.0); bestLim = cm.lim; bestId = cm.id }
        }
        _s.update { it.copy(camDistM = bestD, camLimit = bestLim) }
        if (bestId >= 0 && bestD <= 350.0 && camAnnounced.add(bestId)) speak("Speed camera ahead")
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
        updateSpeedInfo(p, r, bi, along)
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
