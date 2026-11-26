package app.noentrynav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.BitSet
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class Clock(val dayIso: Int, val minute: Int, val date: Int)

private const val CELL = 0.005   // must match tools/build_data.py
private const val FEED_URL = "https://raw.githubusercontent.com/sureshift/NoEntryNavigation/main/feed/updates.json"

/** SQL condition selecting grid cells that cover a lat/lon box (row-major cell ids: row*100000+col). */
private fun cellCond(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): String {
    val r0 = Math.floor(minLat / CELL).toLong()
    val r1 = Math.floor(maxLat / CELL).toLong()
    val c0 = Math.floor(minLon / CELL).toLong()
    val c1 = Math.floor(maxLon / CELL).toLong()
    val sb = StringBuilder()
    for (r in r0..r1) {
        if (sb.isNotEmpty()) sb.append(" OR ")
        sb.append("cell BETWEEN ").append(r * 100000 + c0).append(" AND ").append(r * 100000 + c1)
    }
    return sb.toString()
}

/** Fully offline engine: road graph, places and no-entry rules all come from files inside the app. */
class Engine(private val ctx: Context) : SegStore {
    private var db: SQLiteDatabase? = null
    private lateinit var graph: Graph
    private var zones: List<Zone> = emptyList()
    private var base: List<Zone> = emptyList()
    private val reports = ArrayList<Report>()
    private val reportZoneCache = HashMap<String, Zone?>()
    private var nextReportZoneId = 100000L
    private val lineCache = HashMap<Long, String>()

    /** Everything the rules screen needs. */
    var notices: List<Notice> = emptyList(); private set
    val allZones: List<Zone> get() = zones
    var ruleWarnings: List<String> = emptyList(); private set
    var feedUpdated: String = ""; private set

    private fun feedFile() = File(ctx.filesDir, "feed.json")

    private fun readFeed(): JSONObject? = try {
        val f = feedFile()
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (_: Exception) { null }

    /**
     * Download the online update feed (new gazette notifications, temporary no-entry rules, traffic advisories).
     * Returns a short status line. The app works fully without it; this only adds newer information.
     */
    suspend fun refreshFeed(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(FEED_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "NoEntryNav/1.0")
            if (conn.responseCode != 200) return@withContext Pair(false, "Update server answered ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 600_000) return@withContext Pair(false, "Update file is too large, ignored")
            val txt = String(bytes, Charsets.UTF_8)
            val j = JSONObject(txt)                                  // must be valid JSON
            if (j.optInt("version", 0) != 1) return@withContext Pair(false, "Update file has a newer format. Please update the app.")
            val old = try { feedFile().takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
            ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE).edit().putLong("feed_ts", System.currentTimeMillis()).apply()
            if (old == txt) return@withContext Pair(false, "Already up to date")
            val tmp = File(ctx.filesDir, "feed.json.tmp")
            tmp.writeText(txt)
            if (!tmp.renameTo(feedFile())) { feedFile().writeText(txt); tmp.delete() }
            loadRules()
            Pair(true, "Updated")
        } catch (e: java.net.UnknownHostException) {
            Pair(false, "No internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            Pair(false, "Update server did not respond")
        } catch (e: Exception) {
            Pair(false, "Could not read updates (${e.javaClass.simpleName})")
        }
    }

    fun lastFeedCheck(): Long = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE).getLong("feed_ts", 0L)

    private fun d(): SQLiteDatabase = db ?: throw IllegalStateException("Map database is not open")

    fun now(): Clock {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dow = c.get(Calendar.DAY_OF_WEEK)               // Sunday = 1
        return Clock(
            if (dow == Calendar.SUNDAY) 7 else dow - 1,
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Copy the bundled database into app storage (atomically, so a failed copy never leaves a half file). */
    private fun installDb(dbFile: File, stamp: Long) {
        val prefs = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE)
        val tmp = File(dbFile.path + ".tmp")
        tmp.delete()
        dbFile.parentFile?.mkdirs()
        try {
            ctx.assets.open("delhi.db").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } catch (e: FileNotFoundException) {
            tmp.delete()
            throw RuntimeException("The map database is missing from this app build. Please install the latest APK.")
        } catch (e: IOException) {
            tmp.delete()
            val full = (e.message ?: "").contains("ENOSPC", true) || (e.message ?: "").contains("space", true)
            throw RuntimeException(if (full) "Not enough free storage to unpack the Delhi map (about 150 MB needed)." else "Could not unpack the map: ${e.message}")
        }
        dbFile.delete()
        File(dbFile.path + "-journal").delete()
        if (!tmp.renameTo(dbFile)) { tmp.delete(); throw RuntimeException("Could not install the map database.") }
        prefs.edit().putLong("dbstamp", stamp).apply()
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val dbFile = ctx.getDatabasePath("delhi.db")
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val prefs = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE)
        if (!dbFile.exists() || prefs.getLong("dbstamp", 0L) != stamp) installDb(dbFile, stamp)
        try {
            loadGraph(dbFile)
        } catch (e: Exception) {
            // a damaged copy: reinstall once from the APK and try again
            try { db?.close() } catch (_: Exception) {}
            db = null
            installDb(dbFile, stamp)
            loadGraph(dbFile)
        }
        loadRules()
    }

    private fun loadGraph(dbFile: File) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        this.db = db
        val nn = db.rawQuery("SELECT COUNT(*) FROM node", null).use { it.moveToFirst(); it.getInt(0) }
        val ns = db.rawQuery("SELECT COUNT(*) FROM seg", null).use { it.moveToFirst(); it.getInt(0) }
        if (nn < 100 || ns < 100) throw IllegalStateException("Map database is empty or incomplete")
        val lat = DoubleArray(nn)
        val lon = DoubleArray(nn)
        db.rawQuery("SELECT lat, lon FROM node ORDER BY id", null).use { c ->
            var i = 0
            while (c.moveToNext() && i < nn) { lat[i] = c.getDouble(0); lon[i] = c.getDouble(1); i++ }
        }
        val a = IntArray(ns); val b = IntArray(ns); val len = FloatArray(ns); val spd = IntArray(ns)
        val ow = ByteArray(ns); val fl = ByteArray(ns); val mh = FloatArray(ns); val mw = FloatArray(ns)
        db.rawQuery("SELECT a, b, len, speed, oneway, flags, maxh, maxw FROM seg ORDER BY id", null).use { c ->
            var i = 0
            while (c.moveToNext() && i < ns) {
                a[i] = c.getInt(0); b[i] = c.getInt(1); len[i] = c.getFloat(2)
                spd[i] = c.getInt(3).coerceIn(5, 80)
                ow[i] = c.getInt(4).toByte(); fl[i] = c.getInt(5).toByte()
                mh[i] = c.getFloat(6); mw[i] = c.getFloat(7)
                i++
            }
        }
        graph = Graph(lat, lon, a, b, len, spd, ow, fl, mh, mw)
    }

    // ---- gazette rules ----

    private fun mins(s: String): Int {
        if (s == "24:00") return 1440
        return s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
    }

    /** Map segments whose name contains one of [names] and whose midpoint lies inside [bbox] (minLon,minLat,maxLon,maxLat). */
    private fun matchSegs(names: List<String>, bbox: DoubleArray): IntArray {
        val like = names.joinToString(" OR ") { "name LIKE ?" }
        val args = names.map { "%${it.replace("%", "").replace("_", "")}%" }.toTypedArray()
        val sql = "SELECT id FROM seg WHERE ($like) AND id IN " +
            "(SELECT seg FROM seg_cell WHERE ${cellCond(bbox[1], bbox[3], bbox[0], bbox[2])})"
        val out = ArrayList<Int>()
        d().rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val s = c.getInt(0)
                if (s < 0 || s >= graph.nSegs) continue
                val la = (graph.nodeLat[graph.segA[s]] + graph.nodeLat[graph.segB[s]]) / 2
                val lo = (graph.nodeLon[graph.segA[s]] + graph.nodeLon[graph.segB[s]]) / 2
                if (la in bbox[1]..bbox[3] && lo in bbox[0]..bbox[2]) out.add(s)
            }
        }
        return out.toIntArray()
    }

    private fun loadRules() {
        val warns = ArrayList<String>()
        val zs = ArrayList<Zone>()
        val ns = ArrayList<Notice>()
        try {
            val root = JSONObject(ctx.assets.open("rules.json").bufferedReader().use { it.readText() })
            val feed = readFeed()
            feedUpdated = feed?.optString("updated") ?: ""
            val noticeSrc = ArrayList<JSONObject>()
            for (arrName in listOf("notices", "notifications")) {
                val a = root.optJSONArray(arrName) ?: continue
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { noticeSrc.add(it) }
            }
            if (feed != null) for (arrName in listOf("notifications", "advisories")) {
                val a = feed.optJSONArray(arrName) ?: continue
                for (i in 0 until minOf(a.length(), 300)) a.optJSONObject(i)?.let { noticeSrc.add(it) }
            }
            val byId = LinkedHashMap<String, Notice>()
            for (o in noticeSrc) try {
                val n = Notice(
                    o.getString("id").take(60), o.optString("kind", "advisory"), o.getString("title").take(200),
                    o.optString("authority").take(100), o.optString("ref").take(100), o.optString("date").take(20),
                    o.optString("gazette").take(120), o.optString("summary").take(600), o.optString("url").take(400)
                )
                byId[n.id] = n          // later entries (the online feed) replace built-in ones with the same id
            } catch (e: Exception) { warns.add("A notification entry could not be read") }
            ns.addAll(byId.values.sortedByDescending { it.date })

            var nextId = 1L
            val today = now().date
            val rules = ArrayList<Pair<JSONObject, Boolean>>()
            val feedRules = ArrayList<JSONObject>()
            feed?.optJSONArray("rules")?.let { a -> for (i in 0 until minOf(a.length(), 200)) a.optJSONObject(i)?.let { feedRules.add(it) } }
            val feedIds = feedRules.map { it.optString("id") }.toSet()
            root.optJSONArray("rules")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("id") !in feedIds) rules.add(Pair(it, false)) }
            }
            feedRules.forEach { rules.add(Pair(it, true)) }
            for ((i, pr) in rules.withIndex()) {
                val o = pr.first
                val fromFeed = pr.second
                val rid = o.optString("id", "rule$i")
                try {
                    if (o.optInt("until", 99999999) < today) continue            // temporary rule has expired
                    val vs = o.getJSONArray("vehicles")
                    val veh = (0 until vs.length()).map { vs.getString(it) }.toSet()
                    val ds = o.optJSONArray("days")
                    val days = if (ds == null) (1..7).toSet() else (0 until ds.length()).map { ds.getInt(it) }.toSet()
                    val wa = o.getJSONArray("windows")
                    val wins = (0 until wa.length()).map { k -> wa.getJSONArray(k).let { intArrayOf(mins(it.getString(0)), mins(it.getString(1))) } }
                    val eff = o.optInt("effective", 0)
                    val to = o.optInt("until", 99999999)
                    val notif = o.optString("notification")
                    fun box(a: JSONArray): DoubleArray {
                        val b = doubleArrayOf(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3))
                        // online rules are sanity-checked so a typo cannot block the whole city
                        val maxSpan = if (o.optString("kind") == "area") 0.06 else 0.3
                        require(b[0] < b[2] && b[1] < b[3] && b[0] > 76.5 && b[2] < 77.8 && b[1] > 28.0 && b[3] < 29.2) { "bad bbox" }
                        require(!fromFeed || (b[2] - b[0] <= maxSpan && b[3] - b[1] <= maxSpan)) { "bbox too large" }
                        return b
                    }
                    if (o.optString("kind") == "area") {
                        val areas = o.getJSONArray("areas")
                        for (k in 0 until areas.length()) {
                            val ao = areas.getJSONObject(k)
                            val b = box(ao.getJSONArray("bbox"))
                            val ring = listOf(LatLon(b[1], b[0]), LatLon(b[1], b[2]), LatLon(b[3], b[2]), LatLon(b[3], b[0]))
                            zs.add(Zone(nextId++, rid, ao.getString("name"), notif, ring, null, veh, days, wins, eff, to))
                        }
                    } else {
                        val na2 = o.getJSONArray("names")
                        val names = (0 until na2.length()).map { na2.getString(it) }
                        val segs = matchSegs(names, box(o.getJSONArray("bbox")))
                        if (segs.isEmpty()) warns.add("${o.optString("name", rid)}: road not found on the map, so it is not applied to routing")
                        zs.add(Zone(nextId++, rid, o.getString("name"), notif, emptyList(), segs, veh, days, wins, eff, to))
                    }
                } catch (e: Exception) {
                    warns.add("Rule $rid could not be loaded (${e.message})")
                }
            }
        } catch (e: Exception) {
            warns.add("Gazette rules file could not be read (${e.message})")
        }
        base = zs
        notices = ns
        ruleWarnings = warns
        loadReports()
        rebuildZones()
    }

    fun zonesFor(vclass: String): List<Zone> {
        val c = now()
        return zones.filter { it.isActive(vclass, c.dayIso, c.minute, c.date) }
    }

    /** GeoJSON of the map segments covered by the active road rules (drawn in red). */
    suspend fun zoneLines(active: List<Zone>): String? = withContext(Dispatchers.IO) {
        val parts = ArrayList<String>()
        for (z in active) {
            val segs = z.segs ?: continue
            val cached = lineCache.getOrPut(z.id) {
                val sb = StringBuilder()
                for (s in segs.take(2500)) {
                    val pts = try { geom(s) } catch (e: Exception) { emptyList() }
                    if (pts.size < 2) continue
                    if (sb.isNotEmpty()) sb.append(',')
                    sb.append('[')
                    pts.forEachIndexed { i, p ->
                        if (i > 0) sb.append(',')
                        sb.append('[').append(p.lon).append(',').append(p.lat).append(']')
                    }
                    sb.append(']')
                }
                sb.toString()
            }
            if (cached.isNotEmpty()) parts.add(cached)
        }
        if (parts.isEmpty()) null
        else """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[${parts.joinToString(",")}]}}"""
    }

    // ---- SegStore ----
    override fun geom(seg: Int): List<LatLon> =
        d().rawQuery("SELECT geom FROM seg WHERE id=$seg", null).use {
            if (it.moveToFirst()) Geo.decodePolyline(it.getString(0), 6) else emptyList()
        }

    override fun name(seg: Int): String =
        d().rawQuery("SELECT name FROM seg WHERE id=$seg", null).use {
            if (it.moveToFirst()) (it.getString(0) ?: "") else ""
        }

    // ---- snapping, routing ----
    private fun snap(p: LatLon, prof: Profile): Snap? {
        for (d in doubleArrayOf(0.0015, 0.006, 0.02)) {
            val sql = "SELECT s.id, s.geom FROM seg s WHERE s.id IN " +
                "(SELECT seg FROM seg_cell WHERE ${cellCond(p.lat - d, p.lat + d, p.lon - d, p.lon + d)})"
            var best: Snap? = null
            d().rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val seg = c.getInt(0)
                    if (!Router.segAllowed(graph, seg, prof)) continue
                    val pts = Geo.decodePolyline(c.getString(1), 6)
                    if (pts.size < 2) continue
                    val s = Planner.makeSnap(seg, pts, p)
                    if (best == null || s.distM < best!!.distM) best = s
                }
            }
            val b = best
            if (b != null && (b.distM <= d * 100000.0 || d >= 0.02)) return b
        }
        return null
    }

    private fun segSet(pieces: List<Piece>): Set<Int> = pieces.filter { it.seg >= 0 }.map { it.seg }.toSet()

    private fun segLenSum(set: Collection<Int>): Double = set.sumOf { graph.segLen[it].toDouble() }

    /** The road that carries most of the route (optionally only the part not shared with [exclude]). */
    private fun via(pieces: List<Piece>, exclude: Set<Int>?): String {
        val m = HashMap<String, Double>()
        for (pc in pieces) {
            if (pc.name.isBlank()) continue
            if (exclude != null && pc.seg in exclude) continue
            m[pc.name] = (m[pc.name] ?: 0.0) + Planner.polyLen(pc.pts)
        }
        return m.maxByOrNull { it.value }?.key ?: ""
    }

    /** Fastest legal route plus up to two alternatives that use different roads. */
    suspend fun routes(from: LatLon, to: LatLon, v: Vehicle): List<Route> = withContext(Dispatchers.Default) {
        val prof = Profile(true, v.height, v.weight, v.speedFactor)
        val so = snap(from, prof) ?: throw RuntimeException("The start point is outside the Delhi map data.")
        val sd = snap(to, prof) ?: throw RuntimeException("That destination is outside the Delhi map data.")
        val active = zonesFor(v.key)
        val applied = active.map { Pair(it.id, it.name) }
        val blocked = Planner.blockedSegs(graph, active, so.proj, sd.proj)
        var pieces = Planner.plan(graph, this@Engine, prof, blocked, so, sd)
        var warn: String? = null
        if (pieces == null && blocked != null && !blocked.isEmpty) {
            pieces = Planner.plan(graph, this@Engine, prof, null, so, sd)
            if (pieces != null) warn = "No route avoids every no-entry rule right now. This is the shortest route and it passes restricted roads."
        }
        if (pieces == null) throw RuntimeException("No route found. A vehicle height/weight limit or one-way roads may be blocking every way.")
        val primary = Planner.buildRoute(pieces, prof, applied)
        primary.warning = warn
        primary.label = "Fastest"
        primary.via = via(pieces, null)
        val out = ArrayList<Route>()
        out.add(primary)

        val pSegs = segSet(pieces)
        val total = segLenSum(pSegs)
        if (warn == null && total > 1500.0) {
            val accepted = ArrayList<Set<Int>>().apply { add(pSegs) }
            val alts = ArrayList<Route>()
            for ((fa, fb) in listOf(0.12 to 0.40, 0.30 to 0.60, 0.50 to 0.80, 0.70 to 0.92, 0.20 to 0.75)) {
                if (alts.size >= 2) break
                try {
                    val bl = (blocked?.clone() as? BitSet) ?: BitSet(graph.nSegs)
                    var cum = 0.0
                    for (pc in pieces) {
                        if (pc.seg < 0) continue
                        val f0 = cum / total
                        cum += graph.segLen[pc.seg]
                        val f1 = cum / total
                        if (f1 > fa && f0 < fb) bl.set(pc.seg)
                    }
                    val p2 = Planner.plan(graph, this@Engine, prof, bl, so, sd) ?: continue
                    val s2 = segSet(p2)
                    val len2 = segLenSum(s2)
                    if (len2 <= 0.0) continue
                    if (accepted.any { segLenSum(s2.intersect(it)) / len2 > 0.8 }) continue
                    val r2 = Planner.buildRoute(p2, prof, applied)
                    if (r2.timeSec > primary.timeSec * 1.8 + 600) continue
                    r2.via = via(p2, pSegs)
                    accepted.add(s2)
                    alts.add(r2)
                } catch (e: Exception) { /* skip this alternative */ }
            }
            alts.sortBy { it.timeSec }
            alts.forEachIndexed { i, r -> r.label = "Alternative ${i + 1}"; out.add(r) }
        }
        out
    }

    // ---- driver reports (crowd-sourced no-entry stretches, stored on the phone and shared as text) ----

    private fun prefs() = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE)

    private fun reportToJson(r: Report): JSONObject = JSONObject()
        .put("id", r.id).put("name", r.name).put("ts", r.ts)
        .put("a", JSONArray().put(r.a.lat).put(r.a.lon)).put("b", JSONArray().put(r.b.lat).put(r.b.lon))
        .put("vehicles", JSONArray(r.vehicles.toList()))
        .put("windows", JSONArray(r.windows.map { JSONArray().put(it[0]).put(it[1]) }))

    private fun reportFromJson(o: JSONObject): Report {
        val va = o.getJSONArray("a"); val vb = o.getJSONArray("b")
        val a = LatLon(va.getDouble(0), va.getDouble(1)); val b = LatLon(vb.getDouble(0), vb.getDouble(1))
        require(a.lat in 28.0..29.2 && a.lon in 76.5..77.8 && b.lat in 28.0..29.2 && b.lon in 76.5..77.8) { "outside Delhi" }
        val vs = o.getJSONArray("vehicles")
        val veh = (0 until vs.length()).map { vs.getString(it) }.filter { it in setOf("lcv", "mcv", "hcv") }.toSet()
        require(veh.isNotEmpty()) { "no vehicles" }
        val wa = o.getJSONArray("windows")
        val wins = (0 until wa.length()).map { k -> wa.getJSONArray(k).let { intArrayOf(it.getInt(0).coerceIn(0, 1440), it.getInt(1).coerceIn(0, 1440)) } }
        require(wins.isNotEmpty()) { "no hours" }
        return Report(o.getString("id").take(40), o.optString("name").take(80), a, b, veh, wins, o.optLong("ts"))
    }

    private fun loadReports() {
        reports.clear()
        try {   // reports shipped inside the app (curated in the repo as community.json, ids start with C-)
            val txt = ctx.assets.open("community.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("noentry_reports") ?: JSONArray()
            for (i in 0 until arr.length()) try { reports.add(reportFromJson(arr.getJSONObject(i))) } catch (_: Exception) {}
        } catch (_: Exception) {}
        try {
            val arr = JSONArray(prefs().getString("reports", "[]"))
            for (i in 0 until arr.length()) try { reports.add(reportFromJson(arr.getJSONObject(i))) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun saveReports() {
        val arr = JSONArray()
        reports.filter { !it.id.startsWith("C-") }.forEach { arr.put(reportToJson(it)) }
        prefs().edit().putString("reports", arr.toString()).apply()
    }

    private fun reportZone(r: Report): Zone? {
        val prof = Profile(false, 0.0, 0.0, 1.0)
        val so = snap(r.a, prof) ?: return null
        val sd = snap(r.b, prof) ?: return null
        val straight = Geo.dist(r.a, r.b)
        var pieces = Planner.plan(graph, this, prof, null, so, sd)
        if (pieces == null) pieces = Planner.plan(graph, this, prof, null, sd, so)   // one-way roads: try the other direction
        if (pieces == null) return null
        val len = pieces.sumOf { Planner.polyLen(it.pts) }
        if (len > 8000.0 || len > maxOf(1500.0, straight * 3.0)) return null          // the two points are not on one road
        val segs = pieces.filter { it.seg >= 0 }.map { it.seg }.distinct().toIntArray()
        return Zone(
            nextReportZoneId++, "U-${r.id}", r.name.ifBlank { "Driver-reported no-entry" }, "Driver report",
            emptyList(), segs, r.vehicles, (1..7).toSet(), r.windows, 0, 99999999, true, r.id
        )
    }

    private fun rebuildZones() {
        val rz = ArrayList<Zone>()
        for (r in reports) {
            val z = reportZoneCache.getOrPut(r.id) { try { reportZone(r) } catch (_: Exception) { null } }
            if (z != null) rz.add(z)
        }
        zones = base + rz
    }

    suspend fun addReport(
        a: LatLon, b: LatLon, vehicles: Set<String>, windows: List<IntArray>, name: String
    ): String? = withContext(Dispatchers.IO) {
        val r = Report("U" + System.currentTimeMillis().toString(36), name.trim(), a, b, vehicles, windows, System.currentTimeMillis())
        val z = try { reportZone(r) } catch (_: Exception) { null }
            ?: return@withContext "Start and end must be on the same road, within a few km of each other. Try placing them closer to the road."
        reports.add(r)
        reportZoneCache[r.id] = z
        saveReports()
        rebuildZones()
        null
    }

    suspend fun deleteReport(id: String) = withContext(Dispatchers.IO) {
        if (id.startsWith("C-")) return@withContext
        reports.removeAll { it.id == id }
        reportZoneCache.remove(id)
        saveReports()
        rebuildZones()
    }

    /** Text a driver can share (WhatsApp etc.). Only reports made or imported on this phone. */
    fun exportReports(): String {
        val arr = JSONArray()
        reports.filter { !it.id.startsWith("C-") }.forEach { arr.put(reportToJson(it)) }
        return "NoEntry Nav driver reports. Copy this whole message, open NoEntry Nav > Rules > Import.\n" +
            JSONObject().put("noentry_reports", arr).toString()
    }

    /** Returns how many new reports were added, or -1 if the text holds no reports. */
    suspend fun importReports(text: String): Int = withContext(Dispatchers.IO) {
        val i = text.indexOf('{')
        val j = text.lastIndexOf('}')
        if (i < 0 || j <= i) return@withContext -1
        val arr = try { JSONObject(text.substring(i, j + 1)).optJSONArray("noentry_reports") } catch (_: Exception) { null }
            ?: return@withContext -1
        var added = 0
        for (k in 0 until arr.length()) {
            try {
                val r = reportFromJson(arr.getJSONObject(k))
                if (r.id.startsWith("C-") || reports.any { it.id == r.id }) continue
                val z = reportZone(r) ?: continue
                reports.add(r); reportZoneCache[r.id] = z; added++
            } catch (_: Exception) {}
        }
        if (added > 0) { saveReports(); rebuildZones() }
        added
    }

    // ---- search ----
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val s = q.trim().lowercase(Locale.ROOT).replace("\\", "").replace("%", "").replace("_", "")
        if (s.isEmpty()) return@withContext emptyList()
        val out = ArrayList<Place>()
        d().rawQuery(
            "SELECT name, kind, lat, lon FROM place WHERE lname LIKE ? OR lname LIKE ? " +
                "ORDER BY (lname LIKE ?) DESC, length(lname) LIMIT 8",
            arrayOf("$s%", "% $s%", "$s%")
        ).use { c ->
            while (c.moveToNext()) out.add(Place("${c.getString(0)} · ${c.getString(1)}", LatLon(c.getDouble(2), c.getDouble(3))))
        }
        out
    }

    // ---- offline road drawing ----
    suspend fun roads(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoom: Double): Roads =
        withContext(Dispatchers.IO) {
            val maxCls = when {
                zoom < 11.0 -> 0
                zoom < 13.0 -> 1
                zoom < 14.5 -> 2
                else -> 3
            }
            val sql = "SELECT s.cls, s.geom FROM seg s WHERE s.cls <= $maxCls AND s.id IN " +
                "(SELECT seg FROM seg_cell WHERE ${cellCond(minLat, maxLat, minLon, maxLon)}) LIMIT 20000"
            val major = StringBuilder(); val main = StringBuilder(); val local = StringBuilder()
            d().rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val sb = when (c.getInt(0)) { 0 -> major; 1 -> main; else -> local }
                    if (sb.isNotEmpty()) sb.append(',')
                    sb.append('[')
                    Geo.decodePolyline(c.getString(1), 6).forEachIndexed { i, p ->
                        if (i > 0) sb.append(',')
                        sb.append('[').append(p.lon).append(',').append(p.lat).append(']')
                    }
                    sb.append(']')
                }
            }
            fun fc(sb: StringBuilder) =
                """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[$sb]}}"""
            Roads(fc(major), fc(main), fc(local))
        }
}
