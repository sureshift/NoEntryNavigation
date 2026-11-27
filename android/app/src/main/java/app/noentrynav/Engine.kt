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
    private var posted = ByteArray(0)
    class Cam(val id: Int, val pos: LatLon, val lim: Int, val kind: String)
    var cams: List<Cam> = emptyList(); private set
    var camsJson: String = ""; private set
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

    class NeedPack(val id: String) : Exception("Download a map to start")

    val packId: String get() = prefs().getString("pack", "delhi-ncr") ?: "delhi-ncr"
    fun dbFileFor(id: String): File = ctx.getDatabasePath(if (id == "delhi-ncr") "delhi.db" else "pack_$id.db")
    private fun hasBundledDelhi(): Boolean = try { ctx.assets.open("delhi.db").close(); true } catch (_: Exception) { false }

    /** Packs that are on the phone (the bundled Delhi map counts while it is inside the app). */
    fun installedPacks(): Set<String> {
        val out = HashSet<String>()
        val dir = dbFileFor("x").parentFile
        dir?.listFiles()?.forEach { f ->
            if (f.name.startsWith("pack_") && f.name.endsWith(".db")) out.add(f.name.removePrefix("pack_").removeSuffix(".db"))
        }
        if (dbFileFor("delhi-ncr").exists() || hasBundledDelhi()) out.add("delhi-ncr")
        return out
    }

    fun setPack(id: String) {
        try { db?.close() } catch (_: Exception) {}
        db = null
        reportZoneCache.clear()
        prefs().edit().putString("pack", id).apply()
    }

    fun autoCleanOn(): Boolean = prefs().getBoolean("autoclean", true)
    fun setAutoClean(on: Boolean) { prefs().edit().putBoolean("autoclean", on).apply() }

    /** Deletes every downloaded map except the ones in [keep]. Returns how many were removed. */
    fun pruneMaps(keep: Set<String>): Int {
        var n = 0
        for (id in installedPacks()) {
            if (id in keep) continue
            val f = dbFileFor(id)
            if (f.exists()) { File(f.path + "-journal").delete(); if (f.delete()) n++ }
        }
        return n
    }

    fun deletePack(id: String): Boolean {
        if (id == packId) return false
        val f = dbFileFor(id)
        File(f.path + "-journal").delete()
        return !f.exists() || f.delete()
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val id = packId
        val dbFile = dbFileFor(id)
        val prefs = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE)
        if (id == "delhi-ncr" && hasBundledDelhi()) {
            val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
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
        } else {
            if (!dbFile.exists()) throw NeedPack(id)
            try {
                loadGraph(dbFile)
            } catch (e: Exception) {
                try { db?.close() } catch (_: Exception) {}
                db = null
                dbFile.delete()
                throw NeedPack(id)            // damaged: download it again
            }
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
        loadSpeedData(db, ns)
    }

    /** Posted speed limits per segment and the speed / red-light cameras (both from OpenStreetMap, offline). */
    private fun loadSpeedData(db: SQLiteDatabase, ns: Int) {
        try {
            val pz = ByteArray(ns)
            db.rawQuery("SELECT posted FROM seg ORDER BY id", null).use { c ->
                var i = 0
                while (c.moveToNext() && i < ns) { pz[i] = c.getInt(0).coerceIn(0, 127).toByte(); i++ }
            }
            posted = pz
            val list = ArrayList<Cam>()
            db.rawQuery("SELECT id, lat, lon, lim, kind FROM cam", null).use { c ->
                while (c.moveToNext()) list.add(Cam(c.getInt(0), LatLon(c.getDouble(1), c.getDouble(2)), c.getInt(3), c.getString(4) ?: "speed"))
            }
            cams = list
            val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
            list.forEachIndexed { i, cm ->
                if (i > 0) sb.append(',')
                sb.append("{\"type\":\"Feature\",\"properties\":{\"k\":\"${cm.kind}\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${cm.pos.lon},${cm.pos.lat}]}}")
            }
            camsJson = sb.append("]}").toString()
        } catch (e: Exception) {
            posted = ByteArray(0); cams = emptyList(); camsJson = ""
        }
    }

    /** Speed limit of the road at [p]: (km/h, true when posted in OpenStreetMap, false when estimated from road class). */
    suspend fun limitAt(p: LatLon, prof: Profile): Pair<Int, Boolean>? = withContext(Dispatchers.IO) {
        val sn = snap(p, prof) ?: return@withContext null
        if (sn.distM > 40.0) return@withContext null
        val pz = if (sn.seg < posted.size) posted[sn.seg].toInt() else 0
        if (pz > 0) Pair(pz, true) else Pair(graph.segSpeed[sn.seg].coerceAtLeast(10), false)
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

    /** Every rule that matters for this vehicle today, with its timing category (see Zone.status). */
    fun zoneStatuses(vclass: String): List<Pair<Zone, Int>> {
        val c = now()
        return zones.mapNotNull { z -> val st = z.status(vclass, c.dayIso, c.minute, c.date); if (st >= 0) Pair(z, st) else null }
    }

    /** GeoJSON of the map segments covered by road rules, each feature coloured by its timing category. */
    suspend fun zoneLines(st: List<Pair<Zone, Int>>): String? = withContext(Dispatchers.IO) {
        val feats = ArrayList<String>()
        for ((z, cat) in st) {
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
            if (cached.isNotEmpty())
                feats.add("""{"type":"Feature","properties":{"c":"${Zone.statusColor(cat)}"},"geometry":{"type":"MultiLineString","coordinates":[$cached]}}""")
        }
        if (feats.isEmpty()) null else """{"type":"FeatureCollection","features":[${feats.joinToString(",")}]}"""
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
        try {   // approved reports downloaded from the Sure Shift community sheet (ids start with C-S)
            val arr = JSONArray(prefs().getString("server_reports", "[]"))
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
            nextReportZoneId++, "U-${r.id}", r.name.ifBlank { "Driver-reported no-entry" }, if (r.id.startsWith("C-S")) "Verified by admin" else "Driver report",
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
        var r = Report("U" + System.currentTimeMillis().toString(36), name.trim(), a, b, vehicles, windows, System.currentTimeMillis())
        var z = try { reportZone(r) } catch (_: Exception) { null }
            ?: return@withContext "Start and end must be on the same road, within a few km of each other. Try placing them closer to the road."
        if (r.name.isBlank()) {                       // a memorable name, so the driver can find, edit or delete it later
            r = r.copy(name = autoName(r, z))
            z = try { reportZone(r) } catch (_: Exception) { null } ?: z
        }
        reports.add(r)
        reportZoneCache[r.id] = z
        saveReports()
        rebuildZones()
        queueOutbox(r)
        null
    }

    /** "Najafgarh Road near Dwarka Mor" - the road of the stretch plus the closest chowk / locality / landmark. */
    private fun autoName(r: Report, z: Zone): String {
        var road = ""
        try {
            val ids = (z.segs ?: IntArray(0)).take(60).joinToString(",")
            if (ids.isNotEmpty()) {
                val count = HashMap<String, Int>()
                d().rawQuery("SELECT name FROM seg WHERE id IN ($ids) AND name IS NOT NULL AND name <> ''", null).use { c ->
                    while (c.moveToNext()) count.merge(c.getString(0), 1, Int::plus)
                }
                road = count.maxByOrNull { it.value }?.key ?: ""
            }
        } catch (_: Exception) {}
        var near = ""
        try {
            val dl = 0.006
            var best = Double.MAX_VALUE
            d().rawQuery(
                "SELECT name, kind, lat, lon FROM place WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND kind <> 'street' LIMIT 300",
                arrayOf((r.a.lat - dl).toString(), (r.a.lat + dl).toString(), (r.a.lon - dl).toString(), (r.a.lon + dl).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val k = c.getString(1) ?: ""
                    val w = when (k) { "chowk" -> 0.4; "suburb", "neighbourhood", "locality", "quarter", "village", "town", "colony" -> 0.7; else -> 1.0 }
                    val dd = Geo.dist(r.a, LatLon(c.getDouble(2), c.getDouble(3))) * w
                    if (dd < best) { best = dd; near = c.getString(0) ?: "" }
                }
            }
        } catch (_: Exception) {}
        val date = java.text.SimpleDateFormat("d MMM", Locale.ENGLISH).format(java.util.Date())
        val nm = when {
            road.isNotBlank() && near.isNotBlank() && !near.equals(road, true) -> "$road near $near"
            road.isNotBlank() -> "$road no-entry"
            near.isNotBlank() -> "No-entry near $near"
            else -> "No-entry $date"
        }
        return nm.take(60)
    }

    suspend fun renameReport(id: String, name: String) = withContext(Dispatchers.IO) {
        if (id.startsWith("C-")) return@withContext
        val i = reports.indexOfFirst { it.id == id }
        if (i < 0 || name.isBlank()) return@withContext
        reports[i] = reports[i].copy(name = name.trim().take(60))
        reportZoneCache.remove(id)
        saveReports()
        rebuildZones()
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
        return "ROUTIQ driver reports. Copy this whole message, open ROUTIQ > Rules > Import.\n" +
            JSONObject().put("noentry_reports", arr).toString()
    }

    class SharedRoute(val from: LatLon, val fromName: String, val to: LatLon, val toName: String, val veh: String)

    /** Text a driver can send on WhatsApp etc.: the route, plus every driver-reported no-entry road he knows of. */
    fun exportRoute(from: LatLon, fromName: String, to: LatLon, toName: String, veh: Vehicle, summary: String): String {
        val arr = JSONArray()
        reports.filter { !it.id.startsWith("C-") }.forEach { arr.put(reportToJson(it)) }
        val rt = JSONObject().put("from", JSONArray().put(from.lat).put(from.lon)).put("fn", fromName)
            .put("to", JSONArray().put(to.lat).put(to.lon)).put("tn", toName).put("veh", veh.key)
        return "ROUTIQ route: $fromName to $toName ($summary, ${veh.label}).\n" +
            "Open this message in ROUTIQ (Share > ROUTIQ, or copy it and use Rules > Import). " +
            "Then add any no-entry roads you know on this route and share it back.\n" +
            JSONObject().put("noentry_route", rt).put("noentry_reports", arr).toString()
    }

    fun parseSharedRoute(text: String): SharedRoute? {
        val i = text.indexOf('{'); val j = text.lastIndexOf('}')
        if (i < 0 || j <= i) return null
        return try {
            val rt = JSONObject(text.substring(i, j + 1)).optJSONObject("noentry_route") ?: return null
            val f = rt.getJSONArray("from"); val t = rt.getJSONArray("to")
            val a = LatLon(f.getDouble(0), f.getDouble(1)); val b = LatLon(t.getDouble(0), t.getDouble(1))
            if (!CoordParse.inNcr(a) || !CoordParse.inNcr(b)) return null
            SharedRoute(a, rt.optString("fn", "Start"), b, rt.optString("tn", "Destination"), rt.optString("veh", "lcv"))
        } catch (_: Exception) { null }
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

    // ---- community sheet (Google Sheets + Apps Script, see tools/community_server) ----
    private val cfgKey = "NoEntryNav-2026-sureshift".toByteArray()
    private fun unscramble(s: String): String {
        if (s.isEmpty()) return ""
        val raw = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        return String(ByteArray(raw.size) { (raw[it].toInt() xor cfgKey[it % cfgKey.size].toInt()).toByte() })
    }
    private fun cfg(k: String): String = try {
        unscramble(JSONObject(ctx.assets.open("community_config.json").bufferedReader().use { it.readText() }).optString(k))
    } catch (_: Exception) { "" }

    fun communityEndpoint(): String = if (cfg("s").isEmpty()) "" else cfg("e")

    private fun signed(action: String, d: String): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        val dev = deviceId()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(cfg("s").toByteArray(), "HmacSHA256"))
        val sig = mac.doFinal("$action|$t|$dev|$d".toByteArray()).joinToString("") { "%02x".format(it) }
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        return "?action=$action&t=$t&dev=${enc(dev)}&d=${enc(d)}&sig=$sig"
    }

    fun shareReportsOn(): Boolean = prefs().getBoolean("share_reports", true)
    fun setShareReports(on: Boolean) { prefs().edit().putBoolean("share_reports", on).apply() }

    private fun deviceId(): String {
        var id = prefs().getString("device_id", null)
        if (id == null) { id = java.util.UUID.randomUUID().toString().take(12); prefs().edit().putString("device_id", id).apply() }
        return id
    }

    private fun queueOutbox(r: Report) {
        if (communityEndpoint().isEmpty() || !shareReportsOn()) return
        try {
            val arr = JSONArray(prefs().getString("outbox", "[]"))
            arr.put(reportToJson(r))
            prefs().edit().putString("outbox", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "NoEntryNav/1.0")
        if (conn.responseCode != 200) return null
        val b = conn.inputStream.use { it.readBytes() }
        return if (b.size > 800_000) null else String(b, Charsets.UTF_8)
    }

    /** Sends waiting reports to the sheet and downloads the approved ones. Returns true if the approved list changed. */
    suspend fun syncCommunity(): Boolean = withContext(Dispatchers.IO) {
        val ep = communityEndpoint()
        if (ep.isEmpty()) return@withContext false
        try {
            // 1. outbox
            val out = JSONArray(prefs().getString("outbox", "[]"))
            if (out.length() > 0) {
                val keep = JSONArray()
                for (i in 0 until out.length()) {
                    val o = out.getJSONObject(i)
                    val ok = try {
                        val t = httpGet(ep + signed("submit", o.toString()))
                        t != null && JSONObject(t).optBoolean("ok", false)
                    } catch (_: Exception) { false }
                    if (!ok) keep.put(o)
                }
                prefs().edit().putString("outbox", keep.toString()).apply()
            }
            // 2. approved reports
            val txt = httpGet(ep + signed("list", "")) ?: return@withContext false
            val arr = JSONObject(txt).optJSONArray("noentry_reports") ?: return@withContext false
            val fresh = ArrayList<Report>()
            val store = JSONArray()
            for (i in 0 until arr.length()) try {
                val o = arr.getJSONObject(i)
                o.put("id", "C-S" + o.optString("id"))
                val r = reportFromJson(o)
                fresh.add(r); store.put(reportToJson(r))
            } catch (_: Exception) {}
            val oldIds = reports.filter { it.id.startsWith("C-S") }.map { it.id }.toSet()
            val newIds = fresh.map { it.id }.toSet()
            if (oldIds == newIds) return@withContext false
            reports.removeAll { it.id.startsWith("C-S") }
            oldIds.forEach { reportZoneCache.remove(it) }
            reports.addAll(fresh)
            prefs().edit().putString("server_reports", store.toString()).apply()
            rebuildZones()
            true
        } catch (_: Exception) { false }
    }

    // ---- search (places, roads, streets, businesses, with addresses) ----
    suspend fun search(q: String, near: LatLon): List<Place> = withContext(Dispatchers.IO) {
        val clean = q.trim().lowercase(Locale.ROOT).replace("\\", " ").replace("%", " ").replace("_", " ").replace("'", "")
        val toks = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(5)
        if (toks.isEmpty()) return@withContext emptyList()
        // every typed word must start a word in the name or the address (so "ram" no longer matches "program")
        val where = toks.joinToString(" AND ") { "(lname LIKE ? OR lname LIKE ? OR addr LIKE ? OR addr LIKE ?)" }
        val args = ArrayList<String>()
        toks.forEach { args.add("$it%"); args.add("% $it%"); args.add("$it%"); args.add("% $it%") }
        val first = toks[0]
        val whole = clean
        // all words in the name itself ranks above words only found in the address
        val inName = toks.joinToString(" AND ") { "(lname LIKE '$it%' OR lname LIKE '% $it%')" }
        val sql = "SELECT name, kind, lat, lon, addr, phone, MIN(ord) AS o FROM (" +
            "SELECT name, kind, lat, lon, addr, phone, gk, " +
            "(CASE WHEN lname = ? THEN 0 WHEN lname LIKE ? THEN 1 WHEN ($inName) THEN 2 ELSE 3 END) * 1000 + " +
            "(CASE WHEN kind IN ('suburb','neighbourhood','village','town','city','locality','quarter','hamlet','city_block') THEN 0 " +
            "WHEN kind = 'street' OR kind IN ('chowk','school','college','university','hospital','apartment','apartments','condominium','clinic','residential','place_of_worship','station','attraction','kindergarten','mall','government','marketplace') THEN 1 ELSE 2 END) * 100 + " +
            "MIN(99.0, ((lat - ${near.lat}) * (lat - ${near.lat}) + (lon - ${near.lon}) * (lon - ${near.lon})) * 100) AS ord " +
            "FROM place WHERE $where) GROUP BY gk ORDER BY o LIMIT 30"
        val out = ArrayList<Place>()
        d().rawQuery(sql, arrayOf(whole, "$first%") + args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(Place(c.getString(0), LatLon(c.getDouble(2), c.getDouble(3)), c.getString(1) ?: "", c.getString(4) ?: "", c.getString(5) ?: ""))
            }
        }
        out
    }

    /** Optional online lookup in OpenStreetMap's own search service (Nominatim), limited to Delhi NCR. Only runs when the driver asks. */
    suspend fun searchOnline(q: String, near: LatLon = LatLon(28.6139, 77.2090)): List<Place> = withContext(Dispatchers.IO) {
        try { val r = photon(q, near); if (r.isNotEmpty()) return@withContext r } catch (_: Exception) { }
        nominatim(q)
    }

    /** Photon (open source, OpenStreetMap data): forgiving of spelling mistakes and partial names. */
    private fun photon(q: String, near: LatLon): List<Place> {
        val enc = java.net.URLEncoder.encode(q.trim(), "UTF-8")
        val url = "https://photon.komoot.io/api/?limit=15&lat=${near.lat}&lon=${near.lon}&bbox=68.0,6.5,97.5,35.7&q=$enc"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "NoEntryNav/1.0 (sureshift.in)")
        if (conn.responseCode != 200) throw IllegalStateException("Photon answered ${conn.responseCode}")
        val feats = JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }).getJSONArray("features")
        val out = ArrayList<Place>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i); val pr = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val name = pr.optString("name").ifBlank { pr.optString("street") }
            if (name.isBlank()) continue
            val addr = listOf("street", "district", "city").map { pr.optString(it) }.filter { it.isNotBlank() && it != name }.joinToString(", ")
            out.add(Place(name, LatLon(c.getDouble(1), c.getDouble(0)), pr.optString("osm_value"), addr, ""))
        }
        return out
    }

    private fun nominatim(q: String): List<Place> {
        val enc = java.net.URLEncoder.encode(q.trim(), "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=15&countrycodes=in&bounded=1" +
            "&viewbox=68.0,35.7,97.5,6.5&addressdetails=0&q=$enc"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "NoEntryNav/1.0 (sureshift.in)")
        if (conn.responseCode != 200) throw IllegalStateException("OpenStreetMap search answered ${conn.responseCode}")
        val arr = org.json.JSONArray(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(",") }
            val addr = full.substringAfter(",", "").trim().split(",").take(3).joinToString(",").trim()
            out.add(Place(name, LatLon(o.getDouble("lat"), o.getDouble("lon")), o.optString("type"), addr, ""))
        }
        return out
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
            val nameCls = when { zoom < 12.5 -> -1; zoom < 14.0 -> 0; zoom < 15.0 -> 1; zoom < 16.0 -> 2; else -> 3 }
            val sql = "SELECT s.cls, s.geom, s.name FROM seg s WHERE s.cls <= $maxCls AND s.id IN " +
                "(SELECT seg FROM seg_cell WHERE ${cellCond(minLat, maxLat, minLon, maxLon)}) LIMIT 20000"
            val major = StringBuilder(); val main = StringBuilder(); val local = StringBuilder()
            val names = StringBuilder(); var nameCount = 0
            d().rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val cls = c.getInt(0)
                    val sb = when (cls) { 0 -> major; 1 -> main; else -> local }
                    if (sb.isNotEmpty()) sb.append(',')
                    val pts = StringBuilder("[")
                    Geo.decodePolyline(c.getString(1), 6).forEachIndexed { i, p ->
                        if (i > 0) pts.append(',')
                        pts.append('[').append(p.lon).append(',').append(p.lat).append(']')
                    }
                    pts.append(']')
                    sb.append(pts)
                    val nm = if (cls <= nameCls) c.getString(2) else null
                    if (!nm.isNullOrBlank() && nameCount < 1500) {
                        if (names.isNotEmpty()) names.append(',')
                        names.append("""{"type":"Feature","properties":{"name":${JSONObject.quote(nm)}},"geometry":{"type":"LineString","coordinates":""").append(pts).append("}}")
                        nameCount++
                    }
                }
            }
            fun fc(sb: StringBuilder) =
                """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[$sb]}}"""

            // place labels: localities, chowks, societies, landmarks, then shops as you zoom in
            val maxTier = when { zoom < 11.5 -> -1; zoom < 13.5 -> 0; zoom < 15.0 -> 1; zoom < 16.5 -> 2; else -> 3 }
            val pl = StringBuilder()
            if (maxTier >= 0) {
                val tierSql = "CASE WHEN kind IN ('suburb','neighbourhood','village','town','city','locality','quarter','hamlet','city_block','colony') THEN 0 " +
                    "WHEN kind = 'chowk' THEN 1 " +
                    "WHEN kind IN ('apartments','apartment','condominium','residential','hospital','clinic','school','college','university','place_of_worship','station','halt','attraction','park','government','townhall','police','marketplace','stadium','museum','monument','aerodrome','terminal','mall','library','courthouse') THEN 2 ELSE 3 END"
                val q = "SELECT name, lat, lon, $tierSql AS t FROM place WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
                    "AND $tierSql <= $maxTier ORDER BY t LIMIT 700"
                d().rawQuery(q, arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())).use { c ->
                    while (c.moveToNext()) {
                        val nm = c.getString(0) ?: continue
                        if (pl.isNotEmpty()) pl.append(',')
                        pl.append("""{"type":"Feature","properties":{"name":${JSONObject.quote(nm)},"k":${c.getInt(3)}},"geometry":{"type":"Point","coordinates":[${c.getDouble(2)},${c.getDouble(1)}]}}""")
                    }
                }
            }
            Roads(
                fc(major), fc(main), fc(local),
                """{"type":"FeatureCollection","features":[$names]}""",
                """{"type":"FeatureCollection","features":[$pl]}"""
            )
        }
}
