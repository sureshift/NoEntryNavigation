package app.noentrynav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Design tokens: clean, light, one blue accent. */
object C {
    val Primary = Color(0xFF2563EB)
    val PrimaryDark = Color(0xFF1D4ED8)
    val Ink = Color(0xFF0F172A)
    val Muted = Color(0xFF64748B)
    val Line = Color(0xFFE2E8F0)
    val Chip = Color(0xFFF1F5F9)
    val Danger = Color(0xFFDC2626)
    val DangerBg = Color(0xFFFEE2E2)
    val Ok = Color(0xFF15803D)
    val OkBg = Color(0xFFDCFCE7)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.Primary, onPrimary = Color.White,
            surface = Color.White, background = Color.White, error = C.Danger
        ),
        content = content
    )
}

fun fmtDist(m: Double): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

fun fmtTime(sec: Double): String {
    val min = (sec / 60).roundToInt().coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

fun arrivalAt(sec: Double): String {
    val f = SimpleDateFormat("h:mm a", Locale.US)
    f.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    return f.format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}

// ---------------------------------------------------------------- screen

@Composable
fun MainScreen(vm: NavViewModel) {
    val s by vm.state.collectAsState()
    val view = LocalView.current
    SideEffect { view.keepScreenOn = s.navigating }

    Box(Modifier.fillMaxSize()) {
        MapViewHost(s, vm.mapController, { vm.setDestination(it) }, vm::onViewport)

        if (s.navigating) NavTop(s, vm) else SearchTop(s, vm)

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                RoundButton(onClick = { vm.recenter() }) { TargetIcon(C.Ink) }
            }
            if (s.navigating) NavBottom(s, vm) else PlanSheet(s, vm)
        }
    }
}

// ---------------------------------------------------------------- top bars

@Composable
private fun BoxScope.SearchTop(s: UiState, vm: NavViewModel) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 10.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchIcon(C.Muted)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    enabled = s.ready,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = C.Ink),
                    cursorBrush = SolidColor(C.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search(query) }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(if (s.ready) "Where to?" else "Loading map…", color = C.Muted, fontSize = 17.sp)
                            }
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { query = "" },
                        contentAlignment = Alignment.Center
                    ) { CloseIcon(C.Muted, 16.dp) }
                }
            }
        }
        if (s.results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 10.dp) {
                Column {
                    s.results.forEachIndexed { i, p ->
                        val parts = p.name.split(" · ")
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { query = ""; focus.clearFocus(); vm.pickPlace(p) }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            Text(parts[0], color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (parts.size > 1) Text(parts[1], color = C.Muted, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.NavTop(s: UiState, vm: NavViewModel) {
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = C.PrimaryDark, shadowElevation = 12.dp) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                TurnArrow(s.nextTurn, s.nextArrive, Color.White, 60.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(fmtDist(s.nextDistM), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(s.nextText, color = Color(0xFFDBEAFE), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 8.dp) {
                Text(
                    if (s.muted) "Voice off" else "Voice on",
                    color = C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { vm.toggleMute() }.padding(horizontal = 18.dp, vertical = 13.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- bottom sheets

@Composable
private fun PlanSheet(s: UiState, vm: NavViewModel) {
    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, shadowElevation = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(C.Line)
            )
            Spacer(Modifier.height(14.dp))
            if (!s.ready || s.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                    color = C.Primary, trackColor = C.Chip
                )
                Spacer(Modifier.height(12.dp))
            }
            Segmented(s.vehicle) { vm.setVehicle(it) }
            Spacer(Modifier.height(16.dp))

            val route = s.route
            if (route != null) {
                Text(
                    s.destName.ifBlank { "Dropped pin" }, color = C.Muted, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtTime(route.timeSec), color = C.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("${"%.1f".format(route.distKm)} km", color = C.Muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text("Arrive ${arrivalAt(route.timeSec)}", color = C.Muted, fontSize = 14.sp)
                if (route.applied.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    StatusChip(
                        "Routed around: " + route.applied.joinToString { it.second.removePrefix("SAMPLE: ") },
                        C.DangerBg, C.Danger
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.startNav() },
                        modifier = Modifier.weight(2f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Primary)
                    ) { Text("Start", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Chip)
                            .clickable { vm.clearRoute() },
                        contentAlignment = Alignment.Center
                    ) { Text("Clear", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            } else if (s.dest != null) {
                Text(s.destName.ifBlank { "Dropped pin" }, color = C.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.requestRoute() },
                        enabled = s.ready && !s.busy,
                        modifier = Modifier.weight(2f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Primary)
                    ) { Text(if (s.busy) "Finding route…" else "Get route", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Chip)
                            .clickable { vm.clearRoute() },
                        contentAlignment = Alignment.Center
                    ) { Text("Clear", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            } else {
                if (s.ready) {
                    if (s.zones.isNotEmpty()) {
                        StatusChip("${s.zones.size} no-entry zone${if (s.zones.size > 1) "s" else ""} active for ${s.vehicle.label} now", C.DangerBg, C.Danger)
                    } else {
                        StatusChip("No no-entry zones active for ${s.vehicle.label} right now", C.OkBg, C.Ok)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text("Search above, or long-press the map to set a destination.", color = C.Muted, fontSize = 15.sp)
            }

            s.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                val neutral = msg.startsWith("Loading") || msg.startsWith("Waiting")
                val good = msg.startsWith("You have arrived")
                StatusChip(
                    msg,
                    if (good) C.OkBg else if (neutral) C.Chip else C.DangerBg,
                    if (good) C.Ok else if (neutral) C.Muted else C.Danger
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Offline · Map data © OpenStreetMap contributors", color = C.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NavBottom(s: UiState, vm: NavViewModel) {
    val route = s.route ?: return
    val total = route.cum.last().coerceAtLeast(1.0)
    val eta = route.timeSec * (s.remainingM / total)
    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, shadowElevation = 20.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Stat(arrivalAt(eta), "Arrival")
            Stat(fmtTime(eta), "Time left")
            Stat(fmtDist(s.remainingM), "Distance")
            Box(
                Modifier.clip(RoundedCornerShape(14.dp)).background(C.DangerBg).clickable { vm.stopNav() }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) { Text("End", color = C.Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------------------------------------------------------------- small components

@Composable
private fun Segmented(current: Vehicle, onPick: (Vehicle) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Chip).padding(4.dp)) {
        VEHICLES.forEach { v ->
            val sel = v.key == current.key
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) Color.White else Color.Transparent)
                    .clickable { onPick(v) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    v.label, color = if (sel) C.Primary else C.Muted, fontSize = 14.sp, maxLines = 1,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, bg: Color, fg: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = C.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = C.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = Color.White, shadowElevation = 10.dp) {
        Box(Modifier.size(52.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
    }
}

// ---------------------------------------------------------------- icons (drawn, no icon library)

@Composable
private fun SearchIcon(color: Color, dp: Dp = 22.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color = color, radius = w * 0.32f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(width = w * 0.11f))
        drawLine(color = color, start = Offset(w * 0.66f, w * 0.66f), end = Offset(w * 0.9f, w * 0.9f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

@Composable
private fun CloseIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color = color, start = Offset(w * 0.15f, w * 0.15f), end = Offset(w * 0.85f, w * 0.85f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(w * 0.85f, w * 0.15f), end = Offset(w * 0.15f, w * 0.85f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
    }
}

@Composable
private fun TargetIcon(color: Color, dp: Dp = 24.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val c = Offset(w / 2, w / 2)
        drawCircle(color = color, radius = w * 0.26f, center = c, style = Stroke(width = w * 0.1f))
        drawCircle(color = color, radius = w * 0.09f, center = c)
        drawLine(color = color, start = Offset(w / 2, w * 0.02f), end = Offset(w / 2, w * 0.16f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(w / 2, w * 0.84f), end = Offset(w / 2, w * 0.98f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(w * 0.02f, w / 2), end = Offset(w * 0.16f, w / 2), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(w * 0.84f, w / 2), end = Offset(w * 0.98f, w / 2), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

/** Arrow that points the way to turn (0 = straight ahead, positive = right, ±180 = U-turn). */
@Composable
private fun TurnArrow(turn: Int, arrive: Boolean, color: Color, dp: Dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        if (arrive) {
            drawCircle(color = color, radius = w * 0.3f, center = Offset(w / 2, w / 2), style = Stroke(width = w * 0.11f))
            drawCircle(color = color, radius = w * 0.1f, center = Offset(w / 2, w / 2))
        } else {
            rotate(turn.toFloat(), Offset(w / 2, w / 2)) {
                drawLine(color = color, start = Offset(w / 2, w * 0.9f), end = Offset(w / 2, w * 0.28f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.22f, w * 0.46f)
                    lineTo(w / 2, w * 0.12f)
                    lineTo(w * 0.78f, w * 0.46f)
                }
                drawPath(head, color = color, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
