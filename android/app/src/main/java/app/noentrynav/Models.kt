package app.noentrynav

data class LatLon(val lat: Double, val lon: Double)
data class Maneuver(
    val text: String,
    val verbal: String,
    val beginIdx: Int,
    val lengthKm: Double,
    val turn: Int = 0,          // signed degrees, positive = right
    val arrive: Boolean = false
)

/** One enforceable no-entry rule: either a set of road segments (road rule) or a polygon (area rule). */
class Zone(
    val id: Long,
    val ruleId: String,
    val name: String,
    val notification: String,
    val ring: List<LatLon> = emptyList(),
    val segs: IntArray? = null,                 // non-null = road rule (matched map segments)
    val vehicles: Set<String> = emptySet(),
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),   // ISO: 1 = Monday .. 7 = Sunday
    val windows: List<IntArray> = listOf(intArrayOf(0, 1440)),   // [startMin, endMin) ; end < start crosses midnight
    val validFrom: Int = 0,                     // yyyymmdd
    val validTo: Int = 99999999,
    val community: Boolean = false,             // driver-reported (not from the gazette)
    val reportId: String = ""
) {
    val isRoad: Boolean get() = segs != null
    /** For a road rule: how many map segments matched. A road rule with 0 matches cannot be enforced. */
    val enforceable: Boolean get() = if (segs != null) segs.isNotEmpty() else ring.size >= 3

    fun windowText(): String = windows.joinToString(", ") {
        if (it[0] == 0 && it[1] >= 1440) "24 hours" else "${hm(it[0])} - ${hm(it[1])}"
    }

    fun isActive(vclass: String, dayIso: Int, minute: Int, date: Int): Boolean {
        if (!enforceable || vclass !in vehicles) return false
        if (date < validFrom || date > validTo) return false
        val prev = if (dayIso == 1) 7 else dayIso - 1
        for (w in windows) {
            val s = w[0]; val e = w[1]
            if (s <= e) {
                if (dayIso in days && minute >= s && minute < e) return true
            } else {
                if ((dayIso in days && minute >= s) || (prev in days && minute < e)) return true
            }
        }
        return false
    }

    companion object {
        fun hm(m: Int): String = if (m >= 1440) "24:00" else "%02d:%02d".format(m / 60, m % 60)
    }
}

data class Notice(
    val id: String,
    val kind: String,        // timing | entry | advisory
    val title: String,
    val authority: String,
    val ref: String,
    val date: String,
    val gazette: String,
    val summary: String,
    val url: String
)

data class Place(val name: String, val pos: LatLon)
data class Vehicle(
    val key: String,
    val label: String,
    val sub: String,
    val height: Double,
    val weight: Double,
    val length: Double,
    val speedFactor: Double
)

class Route(
    val points: List<LatLon>,
    val cum: DoubleArray,                 // cumulative metres along points
    val maneuvers: List<Maneuver>,
    val distKm: Double,
    val timeSec: Double,
    val applied: List<Pair<Long, String>> // no-entry rules that were active (and avoided) for this route
) {
    /** Set when no legal route existed and the shortest route is shown instead. */
    var warning: String? = null
    var label: String = "Fastest"
    var via: String = ""
}

/** A driver-reported no-entry stretch: from point [a] to point [b] along one road. */
class Report(
    val id: String,
    val name: String,
    val a: LatLon,
    val b: LatLon,
    val vehicles: Set<String>,
    val windows: List<IntArray>,
    val ts: Long
)

val VEHICLES = listOf(
    Vehicle("lcv", "LCV", "up to 7.5 t", 2.6, 6.0, 6.0, 0.9),
    Vehicle("mcv", "MCV", "7.5 - 16 t", 3.2, 12.0, 8.0, 0.8),
    Vehicle("hcv", "HCV", "16 t and above", 3.8, 25.0, 11.0, 0.7)
)

enum class Field { FROM, TO }
enum class Screen { MAP, RULES }
enum class ReportStep { NONE, START, END, DETAILS }

data class UiState(
    val me: LatLon? = null,
    val bearing: Float = 0f,
    val origin: LatLon? = null,          // manual start point; null = use GPS
    val originName: String = "",
    val dest: LatLon? = null,
    val destName: String = "",
    val editing: Field? = null,
    val pin: LatLon? = null,             // long-pressed map point waiting for "start / destination" choice
    val route: Route? = null,
    val routes: List<Route> = emptyList(),   // fastest + alternatives; `route` is the selected one
    val report: ReportStep = ReportStep.NONE,
    val reportA: LatLon? = null,
    val reportB: LatLon? = null,
    val zones: List<Zone> = emptyList(), // rules active right now for the selected vehicle
    val allZones: List<Zone> = emptyList(),
    val notices: List<Notice> = emptyList(),
    val zoneLines: String? = null,       // GeoJSON of matched road-rule segments that are active now
    val screen: Screen = Screen.MAP,
    val vehicle: Vehicle = VEHICLES[0],
    val busy: Boolean = false,
    val message: String? = null,
    val loadError: String? = null,
    val crash: String? = null,
    val navigating: Boolean = false,
    val nextText: String = "",
    val nextDistM: Double = 0.0,
    val nextTurn: Int = 0,
    val nextArrive: Boolean = false,
    val muted: Boolean = false,
    val recenter: Int = 0,
    val remainingM: Double = 0.0,
    val results: List<Place> = emptyList(),
    val ready: Boolean = false,
    val roads: Roads? = null
)

/** GeoJSON MultiLineString features for the offline road layers (major, main, local). */
class Roads(val major: String, val main: String, val local: String)
