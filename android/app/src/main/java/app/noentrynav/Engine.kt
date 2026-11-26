package app.noentrynav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    private lateinit var db: SQLiteDatabase
    private lateinit var graph: Graph
    private var zones: List<Zone> = emptyList()

    fun now(): Clock {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dow = c.get(Calendar.DAY_OF_WEEK)               // Sunday = 1
        return Clock(
            if (dow == Calendar.SUNDAY) 7 else dow - 1,
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        )
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val dbFile = ctx.getDatabasePath("delhi.db")
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val prefs = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE)
        if (!dbFile.exists() || prefs.getLong("dbstamp", 0L) != stamp) {
            dbFile.parentFile?.mkdirs()
            ctx.assets.open("delhi.db").use { i -> dbFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            prefs.edit().putLong("dbstamp", stamp).apply()
        }
        db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

        val nn = db.rawQuery("SELECT COUNT(*) FROM node", null).use { it.moveToFirst(); it.getInt(0) }
        val lat = DoubleArray(nn)
        val lon = DoubleArray(nn)
        db.rawQuery("SELECT lat, lon FROM node ORDER BY id", null).use { c ->
            var i = 0
            while (c.moveToNext()) { lat[i] = c.getDouble(0); lon[i] = c.getDouble(1); i++ }
        }
        val ns = db.rawQuery("SELECT COUNT(*) FROM seg", null).use { it.moveToFirst(); it.getInt(0) }
        val a = IntArray(ns); val b = IntArray(ns); val len = FloatArray(ns); val spd = IntArray(ns)
        val ow = ByteArray(ns); val fl = ByteArray(ns); val mh = FloatArray(ns); val mw = FloatArray(ns)
        db.rawQuery("SELECT a, b, len, speed, oneway, flags, maxh, maxw FROM seg ORDER BY id", null).use { c ->
            var i = 0
            while (c.moveToNext()) {
                a[i] = c.getInt(0); b[i] = c.getInt(1); len[i] = c.getFloat(2)
                spd[i] = c.getInt(3).coerceIn(5, 80)
                ow[i] = c.getInt(4).toByte(); fl[i] = c.getInt(5).toByte()
                mh[i] = c.getFloat(6); mw[i] = c.getFloat(7)
                i++
            }
        }
        graph = Graph(lat, lon, a, b, len, spd, ow, fl, mh, mw)
        zones = parseZones(ctx.assets.open("zones.json").bufferedReader().use { it.readText() })
    }

    private fun parseZones(json: String): List<Zone> {
        fun mins(s: String) = s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val poly = o.getJSONArray("polygon")
            val vs = o.getJSONArray("vehicles")
            val ds = o.optJSONArray("days")
            Zone(
                id = o.getLong("id"),
                name = o.getString("name"),
                ring = (0 until poly.length()).map { k -> poly.getJSONArray(k).let { LatLon(it.getDouble(1), it.getDouble(0)) } },
                vehicles = (0 until vs.length()).map { vs.getString(it) }.toSet(),
                days = if (ds == null) (1..7).toSet() else (0 until ds.length()).map { ds.getInt(it) }.toSet(),
                startMin = mins(o.optString("start", "00:00")),
                endMin = mins(o.optString("end", "23:59")),
                validFrom = o.optInt("valid_from", 0),
                validTo = o.optInt("valid_to", 99999999),
                verified = o.optString("status", "verified") == "verified"
            )
        }
    }

    fun zonesFor(vclass: String): List<Zone> {
        val c = now()
        return zones.filter { it.isActive(vclass, c.dayIso, c.minute, c.date) }
    }

    // ---- SegStore ----
    override fun geom(seg: Int): List<LatLon> =
        db.rawQuery("SELECT geom FROM seg WHERE id=$seg", null).use {
            if (it.moveToFirst()) Geo.decodePolyline(it.getString(0), 6) else emptyList()
        }

    override fun name(seg: Int): String =
        db.rawQuery("SELECT name FROM seg WHERE id=$seg", null).use {
            if (it.moveToFirst()) (it.getString(0) ?: "") else ""
        }

    // ---- snapping, routing ----
    private fun snap(p: LatLon, prof: Profile): Snap? {
        for (d in doubleArrayOf(0.0015, 0.006, 0.02)) {
            val sql = "SELECT s.id, s.geom FROM seg s WHERE s.id IN " +
                "(SELECT seg FROM seg_cell WHERE ${cellCond(p.lat - d, p.lat + d, p.lon - d, p.lon + d)})"
            var best: Snap? = null
            db.rawQuery(sql, null).use { c ->
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
        val prof = Profile(v.key != "car", v.height, v.weight)
        val so = snap(from, prof) ?: throw RuntimeException("Your location is outside the Delhi map data.")
        val sd = snap(to, prof) ?: throw RuntimeException("That destination is outside the Delhi map data.")
        val active = zonesFor(v.key)
        val blocked = Planner.blockedSegs(graph, active, so.proj, sd.proj)
        val pieces = Planner.plan(graph, this@Engine, prof, blocked, so, sd)
            ?: throw RuntimeException("No route found. A no-entry rule or vehicle limit may be blocking every way.")
        Planner.buildRoute(pieces, prof, active.map { Pair(it.id, it.name) })
    }

    // ---- search ----
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val s = q.trim().lowercase(Locale.ROOT).replace("\\", "").replace("%", "").replace("_", "")
        if (s.isEmpty()) return@withContext emptyList()
        val out = ArrayList<Place>()
        db.rawQuery(
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
            db.rawQuery(sql, null).use { c ->
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
