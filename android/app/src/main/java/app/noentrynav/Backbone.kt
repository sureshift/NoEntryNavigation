package app.noentrynav

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream

/**
 * All-India highway skeleton (motorway, trunk, primary; built by CI, see tools/build_backbone.py).
 * It is only used to decide which map tiles a trip needs, so the line it gives is a rough corridor, not the final route.
 */
class Backbone private constructor(
    private val lat: DoubleArray, private val lon: DoubleArray,
    private val a: IntArray, private val b: IntArray, private val len: FloatArray, private val cls: ByteArray
) {
    private val adjStart = IntArray(lat.size + 1)
    private val adj: IntArray

    init {
        val deg = IntArray(lat.size)
        for (s in a.indices) { deg[a[s]]++; deg[b[s]]++ }
        var sum = 0
        for (i in lat.indices) { adjStart[i] = sum; sum += deg[i] }
        adjStart[lat.size] = sum
        val fill = adjStart.copyOf(lat.size)
        adj = IntArray(sum)
        for (s in a.indices) { adj[fill[a[s]]++] = s; adj[fill[b[s]]++] = s }
    }

    // only the big connected networks count when snapping a point (small isolated bits of primary road inside towns lead nowhere)
    private val mainNet = BooleanArray(lat.size).also { ok ->
        val par = IntArray(lat.size) { it }
        fun find(x: Int): Int { var r = x; while (par[r] != r) { par[r] = par[par[r]]; r = par[r] }; return r }
        for (s in a.indices) { val x = find(a[s]); val y = find(b[s]); if (x != y) par[x] = y }
        val size = IntArray(lat.size)
        for (i in lat.indices) size[find(i)]++
        for (i in lat.indices) ok[i] = size[find(i)] >= 300
    }

    private fun speed(seg: Int) = if (cls[seg].toInt() == 0) 70.0 else 45.0     // km/h

    /** Up to [k] nearest nodes within [maxM] metres, nearest first. */
    private fun nearest(p: LatLon, k: Int, maxM: Double): List<Pair<Int, Double>> {
        val best = ArrayList<Pair<Int, Double>>()
        val cosl = Math.cos(Math.toRadians(p.lat))
        val maxDeg = maxM / 111_000.0
        for (i in lat.indices) {
            if (!mainNet[i]) continue
            val dy = lat[i] - p.lat
            if (dy > maxDeg || dy < -maxDeg) continue
            val dx = (lon[i] - p.lon) * cosl
            if (dx > maxDeg || dx < -maxDeg) continue
            val d = Math.hypot(dy, dx) * 111_000.0
            if (d > maxM) continue
            if (best.size < k) { best.add(i to d); best.sortBy { it.second } }
            else if (d < best.last().second) { best[best.size - 1] = i to d; best.sortBy { it.second } }
        }
        return best
    }

    /** Rough driving line from [from] to [to] along the highways: start, shape points, end. Null when no highway link was found. */
    fun route(from: LatLon, to: LatLon): List<LatLon>? {
        val starts = nearest(from, 6, 120_000.0)
        val ends = nearest(to, 6, 120_000.0)
        if (starts.isEmpty() || ends.isEmpty()) return null
        val n = lat.size
        val cost = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val tail = HashMap<Int, Double>()
        for ((i, d) in ends) tail[i] = d / (25.0 / 3.6)          // off-highway leg at about 25 km/h
        val pq = PriorityQueue<DoubleArray>(1024) { x, y -> x[0].compareTo(y[0]) }
        for ((i, d) in starts) { val c = d / (25.0 / 3.6); if (c < cost[i]) { cost[i] = c; pq.add(doubleArrayOf(c, i.toDouble())) } }
        var bestTotal = Double.MAX_VALUE
        var bestNode = -1
        while (pq.isNotEmpty()) {
            val top = pq.poll()
            val u = top[1].toInt()
            if (top[0] > cost[u]) continue
            if (top[0] >= bestTotal) break
            tail[u]?.let { t -> if (top[0] + t < bestTotal) { bestTotal = top[0] + t; bestNode = u } }
            for (e in adjStart[u] until adjStart[u + 1]) {
                val s = adj[e]
                val v = if (a[s] == u) b[s] else a[s]
                val c = top[0] + len[s] / (speed(s) / 3.6)
                if (c < cost[v]) { cost[v] = c; prev[v] = u; pq.add(doubleArrayOf(c, v.toDouble())) }
            }
        }
        if (bestNode < 0) return null
        val path = ArrayList<LatLon>()
        var u = bestNode
        while (u >= 0) { path.add(LatLon(lat[u], lon[u])); u = prev[u] }
        path.reverse()
        return listOf(from) + path + listOf(to)
    }

    companion object {
        private const val INDEX_URL = "https://raw.githubusercontent.com/sureshift/NoEntryNavigation/main/feed/backbone.json"
        @Volatile private var cached: Backbone? = null

        private fun parse(gz: File): Backbone {
            val raw = GZIPInputStream(gz.inputStream().buffered(64 * 1024)).use { it.readBytes() }
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt() != 0x314B4242) throw IllegalStateException("bad backbone file")     // 'BBK1'
            val nn = bb.getInt(); val ns = bb.getInt()
            val lat = DoubleArray(nn) { bb.getInt() / 1e5 }
            val lon = DoubleArray(nn) { bb.getInt() / 1e5 }
            val a = IntArray(ns) { bb.getInt() }
            val b = IntArray(ns) { bb.getInt() }
            val len = FloatArray(ns) { (bb.getShort().toInt() and 0xFFFF) * 10f }
            val cls = ByteArray(ns) { bb.get() }
            return Backbone(lat, lon, a, b, len, cls)
        }

        private fun http(url: String, timeout: Int = 20000): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "ROUTIQ/1.0")
            c.instanceFollowRedirects = true
            return c
        }

        /** The highway skeleton: from the phone when present, otherwise downloaded once (a few MB). Null when offline and never downloaded. */
        suspend fun get(ctx: Context, onPct: (Int) -> Unit): Backbone? = withContext(Dispatchers.IO) {
            cached?.let { return@withContext it }
            val file = File(ctx.filesDir, "backbone.bin.gz")
            val meta = File(ctx.filesDir, "backbone.json")
            var info: JSONObject? = try {
                val c = http(INDEX_URL, 15000)
                if (c.responseCode == 200) JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }).also { meta.writeText(it.toString()) } else null
            } catch (_: Exception) { null }
            if (info == null) info = try { if (meta.exists()) JSONObject(meta.readText()) else null } catch (_: Exception) { null }
            val prefs = ctx.getSharedPreferences("noentry", Context.MODE_PRIVATE)
            val want = info?.optString("sha256").orEmpty()
            val have = prefs.getString("backbone_sha", "") ?: ""
            if (info != null && want.isNotEmpty() && (!file.exists() || have != want)) {
                val url = info.optString("url")
                if (url.startsWith("https://github.com/sureshift/NoEntryNavigation/")) try {
                    val tmp = File(file.path + ".tmp")
                    val c = http(url, 30000)
                    if (c.responseCode == 200) {
                        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.optLong("size")
                        val md = MessageDigest.getInstance("SHA-256")
                        c.inputStream.use { ins -> tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var got = 0L; var last = -1
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); got += n
                                val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else 0
                                if (pct != last) { last = pct; onPct(pct) }
                            }
                        } }
                        val sum = md.digest().joinToString("") { "%02x".format(it) }
                        if (sum.equals(want, true)) { file.delete(); tmp.renameTo(file); prefs.edit().putString("backbone_sha", want).apply() } else tmp.delete()
                    }
                } catch (_: Exception) { File(file.path + ".tmp").delete() }
            }
            if (!file.exists()) return@withContext null
            try { parse(file).also { cached = it } } catch (_: Exception) { file.delete(); null }
        }
    }
}
