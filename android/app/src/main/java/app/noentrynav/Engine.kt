package app.noentrynav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class Clock(val dayIso: Int, val minute: Int, val date: Int)

private const val CELL = 0.005   // must match tools/build_data.py

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
    private val lineCache = HashMap<Long, String>()

    /** Everything the rules screen needs. */
    var notices: List<Notice> = emptyList(); private set
    val allZones: List<Zone> get() = zones
    var ruleWarnings: List<String> = emptyList(); private set

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
            val na = root.optJSONArray("notices") ?: root.optJSONArray("notifications") ?: JSONArray()
            for (i in 0 until na.length()) try {
                val o = na.getJSONObject(i)
                ns.add(Notice(
                    o.getString("id"), o.optString("kind", "advisory"), o.getString("title"),
                    o.optString("authority"), o.optString("ref"), o.optString("date"),
                    o.optString("gazette"), o.optString("summary"), o.optString("url")
                ))
            } catch (e: Exception) { warns.add("A notification entry could not be read") }

            var nextId = 1L
            val ra = root.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until ra.length()) {
                val o = ra.optJSONObject(i) ?: continue
                val rid = o.optString("id", "rule$i")
                try {
                    val vs = o.getJSONArray("vehicles")
                    val veh = (0 until vs.length()).map { vs.getString(it) }.toSet()
                    val ds = o.optJSONArray("days")
                    val days = if (ds == null) (1..7).toSet() else (0 until ds.length()).map { ds.getInt(it) }.toSet()
                    val wa = o.getJSONArray("windows")
                    val wins = (0 until wa.length()).map { k -> wa.getJSONArray(k).let { intArrayOf(mins(it.getString(0)), mins(it.getString(1))) } }
                    val eff = o.optInt("effective", 0)
                    val to = o.optInt("until", 99999999)
                    val notif = o.optString("notification")
                    fun box(a: JSONArray) = doubleArrayOf(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3))
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
        zones = zs
        notices = ns
        ruleWarnings = warns
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

    suspend fun route(from: LatLon, to: LatLon, v: Vehicle): Route = withContext(Dispatchers.Default) {
        val prof = Profile(true, v.height, v.weight, v.speedFactor)
        val so = snap(from, prof) ?: throw RuntimeException("The start point is outside the Delhi map data.")
        val sd = snap(to, prof) ?: throw RuntimeException("That destination is outside the Delhi map data.")
        val active = zonesFor(v.key)
        val blocked = Planner.blockedSegs(graph, active, so.proj, sd.proj)
        var pieces = Planner.plan(graph, this@Engine, prof, blocked, so, sd)
        var warn: String? = null
        if (pieces == null && blocked != null && !blocked.isEmpty) {
            pieces = Planner.plan(graph, this@Engine, prof, null, so, sd)
            if (pieces != null) warn = "No route avoids every no-entry rule right now. This is the shortest route and it passes restricted roads."
        }
        if (pieces == null) throw RuntimeException("No route found. A vehicle height/weight limit or one-way roads may be blocking every way.")
        val r = Planner.buildRoute(pieces, prof, active.map { Pair(it.id, it.name) })
        r.warning = warn
        r
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
