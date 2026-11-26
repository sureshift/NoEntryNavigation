package app.noentrynav

data class LatLon(val lat: Double, val lon: Double)
data class Maneuver(val text: String, val verbal: String, val beginIdx: Int, val lengthKm: Double)
data class Zone(
    val id: Long,
    val name: String,
    val ring: List<LatLon>,
    val vehicles: Set<String> = emptySet(),
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),   // ISO: 1 = Monday .. 7 = Sunday
    val startMin: Int = 0,
    val endMin: Int = 1439,                            // end < start means the window crosses midnight
    val validFrom: Int = 0,                            // yyyymmdd
    val validTo: Int = 99999999,
    val verified: Boolean = true
) {
    fun isActive(vclass: String, dayIso: Int, minute: Int, date: Int): Boolean {
        if (!verified || vclass !in vehicles) return false
        if (date < validFrom || date > validTo) return false
        if (startMin <= endMin) return dayIso in days && minute in startMin..endMin
        val prev = if (dayIso == 1) 7 else dayIso - 1
        return (dayIso in days && minute >= startMin) || (prev in days && minute <= endMin)
    }
}
data class Place(val name: String, val pos: LatLon)
data class Vehicle(val key: String, val label: String, val height: Double, val weight: Double, val length: Double)

class Route(
    val points: List<LatLon>,
    val cum: DoubleArray,                 // cumulative metres along points
    val maneuvers: List<Maneuver>,
    val distKm: Double,
    val timeSec: Double,
    val applied: List<Pair<Long, String>> // no-entry zones the server avoided
)

val VEHICLES = listOf(
    Vehicle("truck", "Truck", 3.8, 12.0, 9.0),
    Vehicle("lcv", "LCV / Tempo", 2.5, 3.5, 5.0),
    Vehicle("car", "Car", 1.6, 1.5, 4.5)
)

data class UiState(
    val me: LatLon? = null,
    val bearing: Float = 0f,
    val dest: LatLon? = null,
    val destName: String = "",
    val route: Route? = null,
    val zones: List<Zone> = emptyList(),
    val vehicle: Vehicle = VEHICLES[0],
    val busy: Boolean = false,
    val message: String? = null,
    val navigating: Boolean = false,
    val nextText: String = "",
    val nextDistM: Double = 0.0,
    val remainingM: Double = 0.0,
    val results: List<Place> = emptyList(),
    val ready: Boolean = false,
    val roads: Roads? = null
)

/** GeoJSON MultiLineString features for the offline road layers (major, main, local). */
class Roads(val major: String, val main: String, val local: String)
