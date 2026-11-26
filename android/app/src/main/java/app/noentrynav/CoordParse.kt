package app.noentrynav

import kotlin.math.abs
import kotlin.math.floor

/**
 * Reads a point typed or pasted by the driver: "28.6139, 77.2090", degrees-minutes-seconds,
 * a Google Maps link containing coordinates, or a Google Plus Code (Open Location Code) such as
 * "7JWVXXXX+XX" or the short form "XXXX+XX Najafgarh". Pure Kotlin, no Android classes.
 */
object CoordParse {
    private const val ALPHA = "23456789CFGHJMPQRVWX"
    private val RES = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
    private val DELHI = LatLon(28.6139, 77.2090)

    fun inNcr(p: LatLon) = p.lat in 28.2..29.1 && p.lon in 76.6..77.8

    fun parse(text: String, ref: LatLon? = null): LatLon? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val p = fromUrl(t) ?: fromDms(t) ?: fromDecimal(t) ?: fromPlusCode(t, ref ?: DELHI)
        return p?.takeIf { inNcr(it) }
    }

    private fun fromUrl(t: String): LatLon? {
        if (!t.contains("http", true) && !t.contains("google", true)) return null
        val pats = listOf(
            Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
            Regex("[?&](?:q|ll|query|destination)=(-?\\d+\\.\\d+)(?:,|%2C)\\s*(-?\\d+\\.\\d+)")
        )
        for (r in pats) r.find(t)?.let { return LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        return null
    }

    private fun fromDecimal(t: String): LatLon? {
        val m = Regex("^\\(?\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*°?\\s*([NSns])?\\s*[,;\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*°?\\s*([EWew])?\\s*\\)?$").find(t) ?: return null
        var a = m.groupValues[1].toDouble(); var b = m.groupValues[3].toDouble()
        if (m.groupValues[2].equals("S", true)) a = -abs(a)
        if (m.groupValues[4].equals("W", true)) b = -abs(b)
        if (abs(a) > 90 && abs(b) <= 90) { val x = a; a = b; b = x }   // pasted as lon, lat
        return LatLon(a, b)
    }

    private fun fromDms(t: String): LatLon? {
        if (!t.contains("°")) return null
        val r = Regex("(\\d{1,3})\\s*°\\s*(?:(\\d{1,2})\\s*['′]\\s*)?(?:(\\d{1,2}(?:\\.\\d+)?)\\s*(?:\"|″|'')\\s*)?([NSEW])")
        val parts = r.findAll(t.uppercase()).toList()
        if (parts.size < 2) return null
        fun v(m: MatchResult): Double {
            val d = m.groupValues[1].toDouble() + (m.groupValues[2].ifEmpty { "0" }.toDouble() / 60.0) + (m.groupValues[3].ifEmpty { "0" }.toDouble() / 3600.0)
            return if (m.groupValues[4] == "S" || m.groupValues[4] == "W") -d else d
        }
        val a = parts[0]; val b = parts[1]
        return if (a.groupValues[4] in listOf("N", "S")) LatLon(v(a), v(b)) else LatLon(v(b), v(a))
    }

    private fun fromPlusCode(t: String, ref: LatLon): LatLon? {
        val m = Regex("([23456789CFGHJMPQRVWX]{2,8})\\+([23456789CFGHJMPQRVWX]{0,7})", RegexOption.IGNORE_CASE).find(t.uppercase()) ?: return null
        var head = m.groupValues[1]; val tail = m.groupValues[2]
        if (head.length % 2 != 0) return null
        if (head.length < 8) {
            val need = 8 - head.length
            head = encodePairs(ref, need / 2) + head
            val full = head + tail
            val c = decode(full) ?: return null
            val half = RES[need / 2 - 1] / 2
            var lat = c.lat; var lon = c.lon
            if (lat > ref.lat + half) lat -= RES[need / 2 - 1] else if (lat < ref.lat - half) lat += RES[need / 2 - 1]
            if (lon > ref.lon + half) lon -= RES[need / 2 - 1] else if (lon < ref.lon - half) lon += RES[need / 2 - 1]
            return LatLon(lat, lon)
        }
        return decode(head + tail)
    }

    private fun encodePairs(p: LatLon, pairs: Int): String {
        var lat = p.lat + 90; var lon = p.lon + 180
        val sb = StringBuilder()
        for (i in 0 until pairs) {
            val la = floor(lat / RES[i]).toInt().coerceIn(0, 19); lat -= la * RES[i]
            val lo = floor(lon / RES[i]).toInt().coerceIn(0, 19); lon -= lo * RES[i]
            sb.append(ALPHA[la]).append(ALPHA[lo])
        }
        return sb.toString()
    }

    /** Pair digits only (up to 10 characters): the centre of the code's cell. */
    private fun decode(code: String): LatLon? {
        val s = code.take(10)
        var lat = -90.0; var lon = -180.0
        var i = 0
        while (i + 1 < s.length) {
            val a = ALPHA.indexOf(s[i]); val b = ALPHA.indexOf(s[i + 1])
            if (a < 0 || b < 0) return null
            lat += a * RES[i / 2]; lon += b * RES[i / 2]
            i += 2
        }
        val r = RES[(s.length / 2 - 1).coerceAtLeast(0)]
        return LatLon(lat + r / 2, lon + r / 2)
    }
}
