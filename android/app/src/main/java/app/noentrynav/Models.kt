package app.noentrynav

data class LatLon(val lat: Double, val lon: Double)
data class Maneuver(val text: String, val verbal: String, val beginIdx: Int, val lengthKm: Double)
data class Zone(val id: Long, val name: String, val ring: List<LatLon>)
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
    val serverUrl: String = "",
    val showSettings: Boolean = false
)
