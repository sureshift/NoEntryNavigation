package app.noentrynav

import java.util.BitSet

class Profile(val heavy: Boolean, val heightM: Double, val weightT: Double, val speedFactor: Double = if (heavy) 0.8 else 1.0)

object Router {
    class Seed(val node: Int, val cost: Double, val viaDir: Int)    // viaDir: 0 = origin moves a->b to reach node
    class Goal(val node: Int, val tail: Double, val enterDir: Int)  // enterDir: 0 = destination reached moving a->b
    class Result(val edges: IntArray, val seed: Seed, val goal: Goal, val cost: Double)

    fun segAllowed(g: Graph, seg: Int, p: Profile): Boolean {
        if (p.heavy && (g.segFlags[seg].toInt() and 1) != 0) return false
        val mh = g.segMaxH[seg]
        if (mh > 0f && p.heightM > mh) return false
        val mw = g.segMaxW[seg]
        if (mw > 0f && p.weightT > mw) return false
        return true
    }

    fun mps(g: Graph, seg: Int, p: Profile): Double = maxOf(5, g.segSpeed[seg]) / 3.6 * p.speedFactor

    private class Heap(cap: Int = 1024) {
        var keys = DoubleArray(cap)
        var vals = IntArray(cap)
        var size = 0
        var topKey = 0.0
        fun push(k: Double, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val par = (i - 1) / 2
                if (keys[par] <= k) break
                keys[i] = keys[par]; vals[i] = vals[par]; i = par
            }
            keys[i] = k; vals[i] = v
        }
        fun pop(): Int {
            val rv = vals[0]
            topKey = keys[0]
            size--
            if (size > 0) {
                val k = keys[size]; val v = vals[size]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && keys[c + 1] < keys[c]) c++
                    if (keys[c] >= k) break
                    keys[i] = keys[c]; vals[i] = vals[c]; i = c
                }
                keys[i] = k; vals[i] = v
            }
            return rv
        }
    }

    /** A* with multiple start and goal nodes. Cost is travel time in seconds. */
    fun route(
        g: Graph, p: Profile, blocked: BitSet?, seeds: List<Seed>, goals: List<Goal>, dest: LatLon
    ): Result? {
        if (seeds.isEmpty() || goals.isEmpty()) return null
        val n = g.nNodes
        val gs = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val seedAt = HashMap<Int, Seed>()
        val goalAt = HashMap<Int, Goal>()
        for (gl in goals) { val o = goalAt[gl.node]; if (o == null || gl.tail < o.tail) goalAt[gl.node] = gl }
        val vmax = 80.0 / 3.6
        fun h(v: Int): Double = Geo.flat(g.nodeLat[v], g.nodeLon[v], dest.lat, dest.lon) / vmax

        val heap = Heap()
        for (s in seeds) {
            val o = seedAt[s.node]
            if (o == null || s.cost < o.cost) seedAt[s.node] = s
            if (s.cost < gs[s.node]) { gs[s.node] = s.cost; heap.push(s.cost + h(s.node), s.node) }
        }
        var best = Double.MAX_VALUE
        var bestGoal: Goal? = null
        while (heap.size > 0) {
            val u = heap.pop()
            val f = heap.topKey
            if (f >= best) break
            val gu = gs[u]
            if (f - h(u) > gu + 1e-6) continue   // stale heap entry
            val goal = goalAt[u]
            if (goal != null && gu + goal.tail < best) { best = gu + goal.tail; bestGoal = goal }
            for (k in g.adjStart[u] until g.adjStart[u + 1]) {
                val e = g.adj[k]
                val seg = e shr 1
                if (blocked != null && blocked.get(seg)) continue
                if (!segAllowed(g, seg, p)) continue
                val v = g.edgeTo(e)
                val ng = gu + g.segLen[seg] / mps(g, seg, p)
                if (ng < gs[v]) { gs[v] = ng; prev[v] = e; heap.push(ng + h(v), v) }
            }
        }
        val goal = bestGoal ?: return null
        val list = ArrayList<Int>()
        var cur = goal.node
        while (prev[cur] != -1) { val e = prev[cur]; list.add(e); cur = g.edgeFrom(e) }
        list.reverse()
        val seed = seedAt[cur] ?: return null
        return Result(list.toIntArray(), seed, goal, best)
    }
}
