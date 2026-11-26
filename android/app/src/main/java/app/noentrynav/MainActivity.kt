package app.noentrynav

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.android.MapLibre
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: NavViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) vm.startLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { MaterialTheme { MainScreen(vm) } }
        permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
}

fun fmtDist(m: Double): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

fun fmtTime(sec: Double): String {
    val min = (sec / 60).roundToInt().coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

@Composable
fun MainScreen(vm: NavViewModel) {
    val s by vm.state.collectAsState()
    val view = LocalView.current
    SideEffect { view.keepScreenOn = s.navigating }
    var query by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        MapViewHost(s, vm.mapController, { vm.setDestination(it) }, vm::onViewport)

        // ---- top ----
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp)) {
            if (s.navigating) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.nextText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("in ${fmtDist(s.nextDistM)}", color = Color(0xFFBBDEFB), fontSize = 16.sp)
                    }
                }
            } else {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f).padding(6.dp),
                            placeholder = { Text(if (s.ready) "Search a place or street in Delhi" else "Loading map…") },
                            enabled = s.ready,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search(query) })
                        )
                        Text("Offline", fontSize = 12.sp, color = Color(0xFF2E7D32), modifier = Modifier.padding(end = 10.dp))
                    }
                }
                if (s.results.isNotEmpty()) {
                    Card(Modifier.padding(top = 6.dp)) {
                        Column {
                            s.results.forEach { p ->
                                Text(
                                    p.name,
                                    maxLines = 2,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { query = ""; vm.pickPlace(p) }
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- bottom ----
        Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val route = s.route
                if (s.navigating && route != null) {
                    val total = route.cum.last().coerceAtLeast(1.0)
                    val eta = route.timeSec * (s.remainingM / total)
                    Text("${fmtDist(s.remainingM)} left · ${fmtTime(eta)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Button(
                        onClick = { vm.stopNav() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Stop navigation") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VEHICLES.forEach { v ->
                            val mod = Modifier.weight(1f)
                            if (v.key == s.vehicle.key) {
                                Button(onClick = {}, modifier = mod, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(v.label, maxLines = 1) }
                            } else {
                                OutlinedButton(onClick = { vm.setVehicle(v) }, modifier = mod, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(v.label, maxLines = 1) }
                            }
                        }
                    }
                    if (route != null) {
                        Text("${"%.1f".format(route.distKm)} km · ${fmtTime(route.timeSec)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (route.applied.isNotEmpty()) {
                            Text(
                                "Avoiding no-entry: " + route.applied.joinToString { it.second },
                                color = Color(0xFFB71C1C), fontSize = 13.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.startNav() }, modifier = Modifier.weight(1f)) { Text("Start") }
                            OutlinedButton(onClick = { vm.clearRoute() }, modifier = Modifier.weight(1f)) { Text("Clear") }
                        }
                    } else if (s.dest != null) {
                        Button(onClick = { vm.requestRoute() }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (s.busy) "Finding route…" else "Get route")
                        }
                    } else {
                        Text("Search above, or long-press the map to drop a destination.", fontSize = 14.sp)
                    }
                }
                s.message?.let { Text(it, color = Color(0xFFB71C1C), fontSize = 13.sp) }
                Text(
                    "Red = no-entry for ${s.vehicle.label} right now · Map data © OpenStreetMap contributors",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        }

    }
}
