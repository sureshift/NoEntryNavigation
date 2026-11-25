package app.noentrynav

import kotlin.math.*

object Geo {
    private const val R = 6371000.0

    fun dist(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Distance in metres from p to segment a-b, and fraction t (0..1) of the closest point along a-b. */
    fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat))
        val mPerDeg = Math.toRadians(1.0) * R
        val ax = (a.lon - p.lon) * mPerDeg * k
        val ay = (a.lat - p.lat) * mPerDeg
        val bx = (b.lon - p.lon) * mPerDeg * k
        val by = (b.lat - p.lat) * mPerDeg
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val cx = ax + tc * dx
        val cy = ay + tc * dy
        return Pair(sqrt(cx * cx + cy * cy), tc)
    }

    fun inPolygon(p: LatLon, ring: List<LatLon>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** True if the route from vertex index `from` onwards passes through the polygon (sampled every ~40 m). */
    fun routeHitsPolygon(pts: List<LatLon>, from: Int, ring: List<LatLon>): Boolean {
        for (i in from until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val n = maxOf(1, (dist(a, b) / 40.0).toInt())
            for (k in 0..n) {
                val t = k.toDouble() / n
                val q = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                if (inPolygon(q, ring)) return true
            }
        }
        return false
    }

    /** Google-style encoded polyline. Valhalla uses precision 6. */
    fun decodePolyline(s: String, precision: Int = 6): List<LatLon> {
        val f = 10.0.pow(precision)
        val out = ArrayList<LatLon>()
        var i = 0
        var lat = 0
        var lon = 0
        while (i < s.length) {
            var shift = 0
            var result = 0
            var b = 0
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            out.add(LatLon(lat / f, lon / f))
        }
        return out
    }
}
