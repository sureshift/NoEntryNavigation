package app.noentrynav

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Downloads city map packs (built by CI, hosted as GitHub release files) and installs them next to the Delhi map. */
object Packs {
    private const val CATALOG_URL = "https://raw.githubusercontent.com/sureshift/NoEntryNavigation/main/feed/catalog.json"

    private fun parse(txt: String): List<PackInfo> {
        val arr = JSONObject(txt).getJSONArray("packs")
        val out = ArrayList<PackInfo>()
        for (i in 0 until arr.length()) try {
            val o = arr.getJSONObject(i)
            val bb = o.getJSONArray("bbox")
            val url = o.getString("url")
            if (!url.startsWith("https://github.com/sureshift/NoEntryNavigation/")) continue   // only our own release files
            out.add(
                PackInfo(
                    o.getString("id"), o.getString("name"), o.optString("state"),
                    doubleArrayOf(bb.getDouble(0), bb.getDouble(1), bb.getDouble(2), bb.getDouble(3)),
                    o.optLong("size"), o.optLong("dbsize", o.optLong("size") * 2), o.optString("sha256"), url
                )
            )
        } catch (_: Exception) {}
        return out
    }

    /** Latest catalog from the internet, or the copy saved last time, or null. */
    suspend fun catalog(ctx: Context): List<PackInfo>? = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, "catalog.json")
        try {
            val c = URL(CATALOG_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "NoEntryNav/1.0")
            if (c.responseCode == 200) {
                val txt = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                val list = parse(txt)
                if (list.isNotEmpty()) { cache.writeText(txt); return@withContext list }
            }
        } catch (_: Exception) {}
        try { if (cache.exists()) parse(cache.readText()).ifEmpty { null } else null } catch (_: Exception) { null }
    }

    /** Downloads, checks and installs a pack. Returns an error text, or null when done. */
    suspend fun download(ctx: Context, engine: Engine, p: PackInfo, onPct: (Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val target = engine.dbFileFor(p.id)
        val gz = File(ctx.cacheDir, "${p.id}.db.gz")
        val tmp = File(target.path + ".tmp")
        try {
            target.parentFile?.mkdirs()
            val need = p.size + p.dbsize + 20_000_000
            if (target.parentFile!!.usableSpace < need) return@withContext "Not enough free storage. ${need / 1_000_000} MB needed."
            val c = URL(p.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "NoEntryNav/1.0")
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return@withContext "Download failed (server said ${c.responseCode})"
            val total = c.contentLengthLong.takeIf { it > 0 } ?: p.size
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { ins ->
                gz.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var got = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else 0
                        if (pct != last) { last = pct; onPct(pct) }
                    }
                }
            }
            val sum = md.digest().joinToString("") { "%02x".format(it) }
            if (p.sha256.isNotEmpty() && !sum.equals(p.sha256, true)) { gz.delete(); return@withContext "The download was damaged. Please try again." }
            GZIPInputStream(gz.inputStream().buffered(64 * 1024)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            gz.delete()
            // sanity check before it replaces anything
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            val n = try { db.rawQuery("SELECT COUNT(*) FROM seg", null).use { it.moveToFirst(); it.getInt(0) } } finally { db.close() }
            if (n < 100) { tmp.delete(); return@withContext "The map file is empty. Please try again later." }
            target.delete()
            if (!tmp.renameTo(target)) { tmp.delete(); return@withContext "Could not save the map." }
            onPct(100)
            null
        } catch (e: java.net.UnknownHostException) {
            gz.delete(); tmp.delete(); "No internet connection"
        } catch (e: java.io.IOException) {
            gz.delete(); tmp.delete()
            if ((e.message ?: "").contains("ENOSPC", true)) "Not enough free storage" else "Download interrupted. Try again."
        } catch (e: Exception) {
            gz.delete(); tmp.delete(); "Could not install the map (${e.javaClass.simpleName})"
        }
    }
}
