package app.noentrynav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Api(private val baseUrl: () -> String) {

    private fun http(method: String, url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 40000
        c.setRequestProperty("User-Agent", "NoEntryNav/0.1 (Android)")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val msg = try {
                val j = JSONObject(text)
                j.optString("detail", j.optString("error", text))
            } catch (e: Exception) {
                text.take(200)
            }
            throw RuntimeException(if (msg.isBlank()) "HTTP $code" else msg)
        }
        return text
    }

    suspend fun zones(vclass: String): List<Zone> = withContext(Dispatchers.IO) {
        val arr = JSONArray(http("GET", "${baseUrl()}/zones/active?vehicle_class=$vclass"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ring = o.getJSONArray("ring")
            Zone(
                o.getLong("id"),
                o.getString("name"),
                (0 until ring.length()).map { k ->
                    val p = ring.getJSONArray(k)
                    LatLon(p.getDouble(1), p.getDouble(0))
                }
            )
        }
    }

    suspend fun route(from: LatLon, to: LatLon, v: Vehicle): Route = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("origin", JSONObject().put("lat", from.lat).put("lon", from.lon))
            .put("destination", JSONObject().put("lat", to.lat).put("lon", to.lon))
            .put("vehicle_class", v.key)
            .put("height", v.height)
            .put("weight", v.weight)
            .put("length", v.length)
            .toString()
        val j = JSONObject(http("POST", "${baseUrl()}/route", body))
        val trip = j.getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val pts = Geo.decodePolyline(leg.getString("shape"), 6)
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i])
        val ms = leg.getJSONArray("maneuvers")
        val maneuvers = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            val text = m.optString("instruction")
            Maneuver(
                text,
                m.optString("verbal_pre_transition_instruction", text),
                m.optInt("begin_shape_index"),
                m.optDouble("length", 0.0)
            )
        }
        val sum = trip.getJSONObject("summary")
        val ap = j.optJSONArray("applied_restrictions") ?: JSONArray()
        val applied = (0 until ap.length()).map { i ->
            val o = ap.getJSONObject(i)
            Pair(o.getLong("id"), o.getString("name"))
        }
        Route(pts, cum, maneuvers, sum.getDouble("length"), sum.getDouble("time"), applied)
    }

    /** Place search via OSM Nominatim, limited to the Delhi NCR box. Fine for testing; self-host Photon for production. */
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&countrycodes=in" +
            "&viewbox=76.80,28.95,77.65,28.35&bounded=1&q=" + URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(http("GET", url))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(o.getString("display_name"), LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
    }
}
