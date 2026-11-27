package app.noentrynav

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Route-driven maps. All of India is cut into 0.5-degree tiles (built by CI, see tools/build_tiles.py):
 * L1 = main roads only (small, used between cities), L2 = every road and place (used at the start, the destination and in Delhi NCR).
 * For a trip the app downloads just the tiles along the route, in driving order, and joins them into one map file.
 */
object TripMaps {
    private const val INDEX_URL = "https://raw.githubusercontent.com/sureshift/NoEntryNavigation/main/feed/tiles.json"
    // Delhi NCR: tiles here are always full detail because the gazette rules need every road
    private val NCR = doubleArrayOf(28.1, 76.6, 29.1, 77.9)       // minLat, minLon, maxLat, maxLon

    class Tile(val y: Int, val x: Int, val s1: Long, val h1: String, val s2: Long, val h2: String, val db2: Long, val db1: Long)
    class Index(val base: String, val t: Map<String, Tile>)
    data class Req(val id: String, val l2: Boolean)

    fun tileId(p: LatLon): String = "${Math.floor(p.lat * 2).toInt()}_${Math.floor(p.lon * 2).toInt()}"

    fun tilesDir(ctx: Context): File = File(ctx.filesDir, "tiles").also { it.mkdirs() }

    private fun parseIndex(txt: String): Index {
        val j = JSONObject(txt)
        val t = j.getJSONObject("t")
        val m = HashMap<String, Tile>()
        for (k in t.keys()) {
            val a = t.getJSONArray(k)
            m[k] = Tile(a.getInt(0), a.getInt(1), a.getLong(2), a.getString(3), a.getLong(4), a.getString(5), a.getLong(6), a.getLong(7))
        }
        val base = j.getString("base")
        if (!base.startsWith("https://github.com/sureshift/NoEntryNavigation/")) throw IllegalStateException("bad tile index")
        return Index(base, m)
    }

    suspend fun index(ctx: Context): Index? = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, "tiles.json")
        try {
            val c = URL(INDEX_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "ROUTIQ/1.0")
            if (c.responseCode == 200) {
                val txt = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                val idx = parseIndex(txt)
                if (idx.t.isNotEmpty()) { cache.writeText(txt); return@withContext idx }
            }
        } catch (_: Exception) {}
        try { if (cache.exists()) parseIndex(cache.readText()).takeIf { it.t.isNotEmpty() } else null } catch (_: Exception) { null }
    }

    private fun inNcr(y: Int, x: Int): Boolean {
        val minLat = y / 2.0; val minLon = x / 2.0
        return minLat + 0.5 > NCR[0] && minLat < NCR[2] && minLon + 0.5 > NCR[1] && minLon < NCR[3]
    }

    /** Tiles around a point: its own tile, plus the neighbours when the point is close to a tile edge. */
    private fun around(p: LatLon, edge: Double): List<Pair<Int, Int>> {
        val y = Math.floor(p.lat * 2).toInt(); val x = Math.floor(p.lon * 2).toInt()
        val fy = p.lat * 2 - y; val fx = p.lon * 2 - x          // 0..1 inside the tile
        val e = edge * 2
        val dys = ArrayList<Int>().apply { add(0); if (fy < e) add(-1); if (fy > 1 - e) add(1) }
        val dxs = ArrayList<Int>().apply { add(0); if (fx < e) add(-1); if (fx > 1 - e) add(1) }
        val out = ArrayList<Pair<Int, Int>>()
        for (dy in dys) for (dx in dxs) out.add((y + dy) to (x + dx))
        return out
    }

    /** The tiles a trip needs, in the order the driver will meet them. Start and destination areas come as full detail. */
    fun corridor(poly: List<LatLon>, idx: Index, origin: LatLon, dest: LatLon): List<Req> {
        val order = LinkedHashMap<String, Boolean>()          // id -> full detail
        fun add(yx: Pair<Int, Int>, full: Boolean) {
            val id = "${yx.first}_${yx.second}"
            if (id !in idx.t) return
            val f = full || inNcr(yx.first, yx.second)
            order[id] = (order[id] ?: false) || f
        }
        around(origin, 0.05).forEach { add(it, true) }
        for (i in 1 until poly.size) {
            val a = poly[i - 1]; val b = poly[i]
            val d = Geo.dist(a, b)
            if (d <= 0.0) continue
            val step = 2000.0 / d
            var t = 0.0
            while (t <= 1.0) {
                val p = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                around(p, 0.06).forEach { add(it, false) }
                t += step
            }
        }
        around(poly.last(), 0.06).forEach { add(it, false) }
        around(dest, 0.05).forEach { add(it, true) }
        return order.map { Req(it.key, it.value) }
    }

    fun estimateBytes(reqs: List<Req>, idx: Index): Long =
        reqs.sumOf { r -> idx.t[r.id]?.let { if (r.l2) it.s2 + it.db2 else it.s1 + it.db1 } ?: 0L }

    /** Downloads one tile (checked against its hash) and unpacks it. Retries on a weak connection, so it keeps going when the signal returns. */
    suspend fun fetch(ctx: Context, idx: Index, r: Req, onBytes: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val t = idx.t[r.id] ?: throw IllegalStateException("No map for this area")
        val lvl = if (r.l2) "L2" else "L1"
        val out = File(tilesDir(ctx), "${lvl}_${r.id}.db")
        if (out.exists() && out.length() > 1000) return@withContext out
        val band = 6 + 3 * Math.floorDiv(t.y - 12, 6)
        val url = "${idx.base}/tiles-lat$band/${lvl}_${r.id}.db.gz"
        val want = if (r.l2) t.h2 else t.h1
        var lastErr: Exception? = null
        for (attempt in 0 until 6) {
            val gz = File(out.path + ".gz")
            val tmp = File(out.path + ".tmp")
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "ROUTIQ/1.0")
                c.instanceFollowRedirects = true
                if (c.responseCode != 200) throw java.io.IOException("server said ${c.responseCode}")
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { ins -> gz.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) { val n = ins.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); onBytes(n.toLong()) }
                } }
                val sum = md.digest().joinToString("") { "%02x".format(it) }
                if (want.isNotEmpty() && !sum.equals(want, true)) throw java.io.IOException("damaged download")
                GZIPInputStream(gz.inputStream().buffered(64 * 1024)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                gz.delete()
                out.delete()
                if (!tmp.renameTo(out)) throw java.io.IOException("cannot save")
                return@withContext out
            } catch (e: java.io.IOException) {
                lastErr = e
                gz.delete(); tmp.delete()
                if ((e.message ?: "").contains("ENOSPC", true)) throw e
                delay(1500L * (attempt + 1))
            }
        }
        throw lastErr ?: java.io.IOException("download failed")
    }

    // ---- joining tiles into one map file (same layout as a single tile, so the engine needs no changes) ----
    private const val SCHEMA = """
CREATE TABLE node(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
CREATE TABLE seg(id INTEGER PRIMARY KEY, a INTEGER, b INTEGER, len INTEGER, speed INTEGER, oneway INTEGER, cls INTEGER, flags INTEGER, maxh REAL, maxw REAL, name TEXT, geom TEXT, posted INTEGER DEFAULT 0);
CREATE TABLE cam(id INTEGER PRIMARY KEY, lat REAL, lon REAL, lim INTEGER, kind TEXT);
CREATE TABLE seg_cell(cell INTEGER NOT NULL, seg INTEGER NOT NULL, PRIMARY KEY(cell, seg)) WITHOUT ROWID;
CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, lname TEXT, kind TEXT, lat REAL, lon REAL, addr TEXT, phone TEXT, gk INTEGER);
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
CREATE INDEX node_ll ON node(lat, lon);
CREATE INDEX place_ll ON place(lat, lon);
"""

    fun mergeStart(dest: File): SQLiteDatabase {
        dest.delete(); File(dest.path + "-journal").delete()
        dest.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dest, null)
        db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA synchronous=OFF")
        for (st in SCHEMA.split(";")) if (st.isNotBlank()) db.execSQL(st)
        db.execSQL("INSERT INTO meta VALUES ('built', 'route')")
        return db
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    /** Adds one tile. Ids are shifted so they stay 0..n-1; border intersections that two tiles share are joined by coordinates. */
    fun mergeAdd(db: SQLiteDatabase, tile: File) {
        db.execSQL("ATTACH DATABASE ? AS t", arrayOf<Any>(tile.path))
        try {
            db.beginTransaction()
            try {
                val nb = scalar(db, "SELECT COALESCE(MAX(id)+1,0) FROM node")
                val sb = scalar(db, "SELECT COALESCE(MAX(id)+1,0) FROM seg")
                val pb = scalar(db, "SELECT COALESCE(MAX(gk)+1,0) FROM place")
                db.execSQL("INSERT INTO node SELECT id+?, lat, lon FROM t.node", arrayOf<Any>(nb))
                db.execSQL("INSERT INTO seg SELECT id+?, a+?, b+?, len, speed, oneway, cls, flags, maxh, maxw, name, geom, posted FROM t.seg", arrayOf<Any>(sb, nb, nb))
                db.execSQL("INSERT OR IGNORE INTO seg_cell SELECT cell, seg+? FROM t.seg_cell", arrayOf<Any>(sb))
                if (nb > 0) {
                    db.execSQL("DROP TABLE IF EXISTS dmap")
                    db.execSQL("CREATE TEMP TABLE dmap(newid INTEGER PRIMARY KEY, oldid INTEGER)")
                    db.execSQL("INSERT INTO dmap SELECT n.id, MIN(o.id) FROM main.node n JOIN main.node o ON o.lat=n.lat AND o.lon=n.lon WHERE n.id>=? AND o.id<? GROUP BY n.id", arrayOf<Any>(nb, nb))
                    db.execSQL("UPDATE seg SET a=(SELECT oldid FROM dmap WHERE newid=seg.a) WHERE id>=? AND a IN (SELECT newid FROM dmap)", arrayOf<Any>(sb))
                    db.execSQL("UPDATE seg SET b=(SELECT oldid FROM dmap WHERE newid=seg.b) WHERE id>=? AND b IN (SELECT newid FROM dmap)", arrayOf<Any>(sb))
                    db.execSQL("DROP TABLE dmap")
                }
                db.execSQL(
                    "INSERT INTO place(name, lname, kind, lat, lon, addr, phone, gk) SELECT name, lname, kind, lat, lon, addr, phone, gk+? FROM t.place p " +
                        "WHERE NOT EXISTS (SELECT 1 FROM main.place q WHERE q.lat=p.lat AND q.lon=p.lon AND q.lname=p.lname)", arrayOf<Any>(pb)
                )
                db.execSQL("INSERT INTO cam(lat, lon, lim, kind) SELECT lat, lon, lim, kind FROM t.cam c WHERE NOT EXISTS (SELECT 1 FROM main.cam q WHERE q.lat=c.lat AND q.lon=c.lon)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            try { db.execSQL("DETACH DATABASE t") } catch (_: Exception) {}
        }
    }
}
