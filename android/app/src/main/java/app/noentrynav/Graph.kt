package app.noentrynav

/** In-memory road graph. Segments are undirected rows; directed edge id = seg*2 + dir (0: a->b, 1: b->a). */
class Graph(
    val nodeLat: DoubleArray,
    val nodeLon: DoubleArray,
    val segA: IntArray,
    val segB: IntArray,
    val segLen: FloatArray,
    val segSpeed: IntArray,
    val segOneway: ByteArray,   // 0 both ways, 1 a->b only, -1 b->a only
    val segFlags: ByteArray,    // bit0: HGVs banned
    val segMaxH: FloatArray,    // 0 = no limit
    val segMaxW: FloatArray
) {
    val nNodes: Int get() = nodeLat.size
    val nSegs: Int get() = segA.size
    val adjStart = IntArray(nodeLat.size + 1)
    val adj: IntArray

    fun dirValid(seg: Int, dir: Int): Boolean =
        if (dir == 0) segOneway[seg].toInt() != -1 else segOneway[seg].toInt() != 1

    fun edgeFrom(e: Int): Int = if ((e and 1) == 0) segA[e shr 1] else segB[e shr 1]
    fun edgeTo(e: Int): Int = if ((e and 1) == 0) segB[e shr 1] else segA[e shr 1]

    init {
        val deg = IntArray(nNodes)
        for (s in 0 until nSegs) {
            if (dirValid(s, 0)) deg[segA[s]]++
            if (dirValid(s, 1)) deg[segB[s]]++
        }
        var sum = 0
        for (i in 0 until nNodes) { adjStart[i] = sum; sum += deg[i] }
        adjStart[nNodes] = sum
        val fill = adjStart.copyOf(nNodes)
        adj = IntArray(sum)
        for (s in 0 until nSegs) {
            if (dirValid(s, 0)) adj[fill[segA[s]]++] = s * 2
            if (dirValid(s, 1)) adj[fill[segB[s]]++] = s * 2 + 1
        }
    }
}
