package app.noentrynav

import java.util.BitSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

interface SegStore {
    fun geom(seg: Int): List<LatLon>
    fun name(seg: Int): String
}

/** A GPS point snapped onto a road segment. `idx` = geometry vertex before the projection point. */
class Snap(val seg: Int, val distM: Double, val alongM: Double, val pts: List<LatLon>, val idx: Int, val proj: LatLon)

class Piece(val name: String, val pts: List<LatLon>, val speedKph: Double)

object Planner {

    fun polyLen(pts: List<LatLon>): Double {
        var l = 0.0
        for (i in 0 until pts.size - 1) l += Geo.dist(pts[i], pts[i + 1])
        return l
    }

    fun makeSnap(seg: Int, pts: List<LatLon>, p: LatLon): Snap {
        var best = Double.MAX_VALUE
        var bi = 0
        var bt = 0.0
        for (i in 0 until pts.size - 1) {
            val (d, t) = Geo.toSegment(p, pts[i], pts[i + 1])
            if (d < best) { best = d; bi = i; bt = t }
        }
        var along = 0.0
        for (i in 0 until bi) along += Geo.dist(pts[i], pts[i + 1])
        along += bt * Geo.dist(pts[bi], pts[bi + 1])
        val a = pts[bi]
        val b = pts[bi + 1]
        return Snap(seg, best, along, pts, bi, LatLon(a.lat + (b.lat - a.lat) * bt, a.lon + (b.lon - a.lon) * bt))
    }

    /** Segments that fall inside any active no-entry zone, except those near the trip's start/end. */
    fun blockedSegs(g: Graph, zones: List<Zone>, o: LatLon, d: LatLon, graceM: Double = 350.0): BitSet? {
        if (zones.isEmpty()) return null
        val bs = BitSet(g.nSegs)
        for (z in zones) {
            if (z.ring.size < 3) continue
            val minLat = z.ring.minOf { it.lat }; val maxLat = z.ring.maxOf { it.lat }
            val minLon = z.ring.minOf { it.lon }; val maxLon = z.ring.maxOf { it.lon }
            for (s in 0 until g.nSegs) {
                val a = g.segA[s]; val b = g.segB[s]
                val la = g.nodeLat[a]; val oa = g.nodeLon[a]
                val lb = g.nodeLat[b]; val ob = g.nodeLon[b]
                val lm = (la + lb) / 2; val om = (oa + ob) / 2
                if (hit(la, oa, minLat, maxLat, minLon, maxLon, z) ||
                    hit(lb, ob, minLat, maxLat, minLon, maxLon, z) ||
                    hit(lm, om, minLat, maxLat, minLon, maxLon, z)
                ) bs.set(s)
            }
        }
        var s = bs.nextSetBit(0)
        while (s >= 0) {
            val lm = (g.nodeLat[g.segA[s]] + g.nodeLat[g.segB[s]]) / 2
            val om = (g.nodeLon[g.segA[s]] + g.nodeLon[g.segB[s]]) / 2
            if (Geo.flat(lm, om, o.lat, o.lon) < graceM || Geo.flat(lm, om, d.lat, d.lon) < graceM) bs.clear(s)
            s = bs.nextSetBit(s + 1)
        }
        return bs
    }

    private fun hit(lat: Double, lon: Double, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, z: Zone): Boolean {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
        return Geo.inPolygon(LatLon(lat, lon), z.ring)
    }

    private fun fwd(so: Snap): List<LatLon> = listOf(so.proj) + so.pts.subList(so.idx + 1, so.pts.size)
    private fun back(so: Snap): List<LatLon> = listOf(so.proj) + so.pts.subList(0, so.idx + 1).reversed()
    private fun toDestFromA(sd: Snap): List<LatLon> = sd.pts.subList(0, sd.idx + 1) + sd.proj
    private fun toDestFromB(sd: Snap): List<LatLon> = sd.pts.subList(sd.idx + 1, sd.pts.size).reversed() + sd.proj

    fun plan(g: Graph, st: SegStore, p: Profile, blocked: BitSet?, so: Snap, sd: Snap): List<Piece>? {
        val kphO = max(5, g.segSpeed[so.seg]).toDouble()
        val nameO = st.name(so.seg)

        if (so.seg == sd.seg) {
            if (sd.alongM >= so.alongM && g.dirValid(so.seg, 0)) {
                val pts = if (so.idx == sd.idx) listOf(so.proj, sd.proj)
                else listOf(so.proj) + so.pts.subList(so.idx + 1, sd.idx + 1) + sd.proj
                return listOf(Piece(nameO, pts, kphO))
            }
            if (sd.alongM < so.alongM && g.dirValid(so.seg, 1)) {
                val pts = if (so.idx == sd.idx) listOf(so.proj, sd.proj)
                else listOf(so.proj) + so.pts.subList(sd.idx + 1, so.idx + 1).reversed() + sd.proj
                return listOf(Piece(nameO, pts, kphO))
            }
        }

        val vO = Router.mps(g, so.seg, p)
        val lo = polyLen(so.pts)
        val seeds = ArrayList<Router.Seed>()
        if (g.dirValid(so.seg, 0)) seeds.add(Router.Seed(g.segB[so.seg], (lo - so.alongM) / vO, 0))
        if (g.dirValid(so.seg, 1)) seeds.add(Router.Seed(g.segA[so.seg], so.alongM / vO, 1))

        val vD = Router.mps(g, sd.seg, p)
        val ld = polyLen(sd.pts)
        val goals = ArrayList<Router.Goal>()
        if (g.dirValid(sd.seg, 0)) goals.add(Router.Goal(g.segA[sd.seg], sd.alongM / vD, 0))
        if (g.dirValid(sd.seg, 1)) goals.add(Router.Goal(g.segB[sd.seg], (ld - sd.alongM) / vD, 1))

        val res = Router.route(g, p, blocked, seeds, goals, sd.proj) ?: return null

        val pieces = ArrayList<Piece>()
        val first = if (res.seed.viaDir == 0) fwd(so) else back(so)
        if (first.size >= 2) pieces.add(Piece(nameO, first, kphO))
        for (e in res.edges) {
            val seg = e shr 1
            var pts = st.geom(seg)
            if ((e and 1) == 1) pts = pts.reversed()
            pieces.add(Piece(st.name(seg), pts, max(5, g.segSpeed[seg]).toDouble()))
        }
        val last = if (res.goal.enterDir == 0) toDestFromA(sd) else toDestFromB(sd)
        if (last.size >= 2) pieces.add(Piece(st.name(sd.seg), last, max(5, g.segSpeed[sd.seg]).toDouble()))
        return pieces
    }

    private fun compass(b: Double): String {
        val names = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return names[(((b + 22.5) % 360) / 45).toInt()]
    }

    private fun fwdIdx(pts: List<LatLon>, j: Int): Int {
        for (k in j + 1 until pts.size) if (Geo.dist(pts[j], pts[k]) >= 12.0) return k
        return pts.size - 1
    }

    private fun backIdx(pts: List<LatLon>, j: Int): Int {
        for (k in j - 1 downTo 0) if (Geo.dist(pts[j], pts[k]) >= 12.0) return k
        return 0
    }

    fun buildRoute(pieces: List<Piece>, p: Profile, applied: List<Pair<Long, String>>): Route {
        val pts = ArrayList<LatLon>()
        val start = IntArray(pieces.size)
        for ((i, pc) in pieces.withIndex()) {
            start[i] = if (pts.isEmpty()) 0 else pts.size - 1
            for (q in pc.pts) if (pts.isEmpty() || Geo.dist(pts.last(), q) > 0.3) pts.add(q)
        }
        if (pts.size < 2) pts.add(pts.first())
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i])

        var time = 0.0
        for (pc in pieces) time += polyLen(pc.pts) / (max(5.0, pc.speedKph) * p.speedFactor / 3.6)

        class M(val text: String, val idx: Int, val turn: Int = 0, val arrive: Boolean = false)
        val ms = ArrayList<M>()
        val hb = Geo.bearing(pts[0], pts[fwdIdx(pts, 0)])
        val firstName = pieces.firstOrNull()?.name ?: ""
        ms.add(M("Head ${compass(hb)}" + if (firstName.isNotBlank()) " on $firstName" else "", 0))

        for (i in 1 until pieces.size) {
            val joint = start[i]
            if (joint <= 0 || joint >= pts.size - 1) continue
            val d = Geo.angleDiff(
                Geo.bearing(pts[backIdx(pts, joint)], pts[joint]),
                Geo.bearing(pts[joint], pts[fwdIdx(pts, joint)])
            )
            val ad = abs(d)
            val nm = pieces[i].name
            val prevNm = pieces[i - 1].name
            val text: String = if (ad >= 30) {
                if (ad < 60 && nm == prevNm) continue
                val dir = if (d > 0) "right" else "left"
                val lead = when {
                    ad < 60 -> "Bear $dir"
                    ad < 135 -> "Turn $dir"
                    else -> "Turn sharp $dir"
                }
                lead + if (nm.isNotBlank()) " onto $nm" else ""
            } else if (nm.isNotBlank() && prevNm.isNotBlank() && nm != prevNm) {
                "Continue onto $nm"
            } else continue
            ms.add(M(text, joint, d.roundToInt()))
        }
        ms.add(M("You have arrived at your destination", pts.size - 1, 0, true))

        val maneuvers = ms.mapIndexed { k, m ->
            val len = if (k + 1 < ms.size) (cum[ms[k + 1].idx] - cum[m.idx]) / 1000.0 else 0.0
            val verbal = if (k == 0 || k == ms.size - 1) m.text
            else "In 250 metres, " + m.text.replaceFirstChar { it.lowercase() }
            Maneuver(m.text, verbal, m.idx, len, m.turn, m.arrive)
        }
        return Route(pts, cum, maneuvers, cum.last() / 1000.0, time, applied)
    }
}
