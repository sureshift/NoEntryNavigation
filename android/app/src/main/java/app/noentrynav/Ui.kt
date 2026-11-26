package app.noentrynav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    BackHandler(enabled = s.screen == Screen.RULES) { vm.showRules(false) }
    BackHandler(enabled = s.screen == Screen.MAP && s.pin != null) { vm.dismissPin() }
    BackHandler(enabled = s.screen == Screen.MAP && s.pin == null && s.editing != null) { vm.edit(null) }
    BackHandler(enabled = s.screen == Screen.MAP && s.report != ReportStep.NONE) { vm.cancelReport() }

    Box(Modifier.fillMaxSize()) {
        MapViewHost(s, vm.mapController, { vm.onMapLongPress(it) }, vm::onViewport)

        if (s.navigating) NavTop(s, vm) else SearchTop(s, vm)

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (s.pin == null && s.report != ReportStep.DETAILS) {
                Row(Modifier.fillMaxWidth().padding(end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                        if (!s.navigating) {
                            Box {
                                RoundButton(onClick = { vm.showRules(true) }) { RulesIcon(C.Ink) }
                                if (s.zones.isNotEmpty()) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(14.dp)
                                            .clip(CircleShape).background(Color.White).padding(2.dp)
                                            .clip(CircleShape).background(C.Danger)
                                    )
                                }
                            }
                        }
                        RoundButton(onClick = { vm.recenter() }) { TargetIcon(C.Ink) }
                    }
                }
            }
            when {
                s.report == ReportStep.DETAILS -> ReportSheet(s, vm)
                s.pin != null -> PinSheet(s.pin!!, s.report, vm)
                s.navigating -> NavBottom(s, vm)
                else -> PlanSheet(s, vm)
            }
        }

        if (s.screen == Screen.RULES) RulesScreen(s, vm)
    }
}

// ---------------------------------------------------------------- top bars

@Composable
private fun BoxScope.SearchTop(s: UiState, vm: NavViewModel) {
    var query by remember(s.editing, s.dest, s.origin) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val planning = s.dest != null || s.origin != null || s.editing == Field.FROM
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (!planning) {
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
            if (s.ready && s.results.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 6.dp,
                    modifier = Modifier.clickable { vm.edit(Field.FROM) }
                ) {
                    Text(
                        "Start from another place", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 10.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.padding(top = 18.dp).width(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(C.Primary).padding(3.dp).clip(CircleShape).background(Color.White))
                        Box(Modifier.width(2.dp).height(37.dp).background(C.Line))
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(C.Ink))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        FieldRow(
                            text = if (s.origin == null) "Current location" else s.originName,
                            hint = "Choose start point",
                            editing = s.editing == Field.FROM, query = query, onQuery = { query = it },
                            onTap = { vm.edit(Field.FROM) },
                            onSearch = { focus.clearFocus(); vm.search(query) }
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        FieldRow(
                            text = s.destName, hint = "Where to?",
                            editing = s.editing == Field.TO || (s.editing == null && s.dest == null),
                            query = query, onQuery = { query = it },
                            onTap = { vm.edit(Field.TO) },
                            onSearch = { focus.clearFocus(); vm.search(query) }
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).clickable { vm.swap() },
                            contentAlignment = Alignment.Center
                        ) { SwapIcon(C.Muted) }
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).clickable { query = ""; vm.clearRoute() },
                            contentAlignment = Alignment.Center
                        ) { CloseIcon(C.Muted, 16.dp) }
                    }
                }
            }
        }
        if (s.results.isNotEmpty() || s.editing == Field.FROM) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 10.dp) {
                Column {
                    if (s.editing == Field.FROM) {
                        Row(
                            Modifier.fillMaxWidth().clickable { query = ""; focus.clearFocus(); vm.useCurrentLocation() }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TargetIcon(C.Primary, 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Use my current location", color = C.Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    s.results.forEachIndexed { i, p ->
                        val parts = p.name.split(" · ")
                        if (i > 0 || s.editing == Field.FROM) Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { query = ""; focus.clearFocus(); vm.pickPlace(p) }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PinIcon(C.Muted, 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(parts[0], color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (parts.size > 1) Text(parts[1], color = C.Muted, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    text: String, hint: String, editing: Boolean, query: String,
    onQuery: (String) -> Unit, onTap: () -> Unit, onSearch: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).then(if (editing) Modifier else Modifier.clickable(onClick = onTap)),
        contentAlignment = Alignment.CenterStart
    ) {
        if (editing) {
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { try { fr.requestFocus() } catch (_: Exception) {} }
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = C.Ink),
                cursorBrush = SolidColor(C.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(hint, color = C.Muted, fontSize = 16.sp)
                        inner()
                    }
                }
            )
        } else {
            Text(
                text.ifBlank { hint }, color = if (text.isBlank()) C.Muted else C.Ink, fontSize = 16.sp,
                fontWeight = if (text.isBlank()) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
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
                Row(
                    Modifier.clickable { vm.toggleMute() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VolumeIcon(!s.muted, C.Ink, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.muted) "Voice off" else "Voice on", color = C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
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
            if (s.loadError != null) {
                StatusChip(s.loadError, C.DangerBg, C.Danger)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.retryLoad() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Primary)
                ) { Text("Try again", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
            }
            s.crash?.let {
                StatusChip("The app closed unexpectedly last time ($it). Tap to dismiss.", C.Chip, C.Muted) { vm.dismissCrash() }
                Spacer(Modifier.height(12.dp))
            }
            Segmented(s.vehicle) { vm.setVehicle(it) }
            Spacer(Modifier.height(16.dp))

            val route = s.route
            if (route != null) {
                if (s.routes.size > 1 && s.routes.any { it === route }) {
                    RouteChips(s, vm)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    (if (s.origin != null) s.originName.ifBlank { "Start" } + " to " else "To ") + s.destName.ifBlank { "Dropped pin" },
                    color = C.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtTime(route.timeSec), color = C.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("${"%.1f".format(route.distKm)} km", color = C.Muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(
                    "Arrive ${arrivalAt(route.timeSec)}" + if (route.via.isNotBlank()) " · via ${route.via}" else "",
                    color = C.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                route.warning?.let {
                    Spacer(Modifier.height(10.dp))
                    StatusChip(it, C.DangerBg, C.Danger) { vm.showRules(true) }
                }
                if (route.warning == null && route.applied.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val n = route.applied.size
                    StatusChip(
                        "Avoiding $n active no-entry rule${if (n > 1) "s" else ""} for ${s.vehicle.label}",
                        C.OkBg, C.Ok
                    ) { vm.showRules(true) }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.startNav() },
                        modifier = Modifier.weight(2f).height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Primary)
                    ) {
                        NavigateIcon(Color.White, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Start", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
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
                    val roads = s.zones.map { it.ruleId }.distinct().size
                    if (s.zones.isNotEmpty()) {
                        StatusChip("$roads no-entry rule${if (roads > 1) "s" else ""} active for ${s.vehicle.label} now. Tap for details", C.DangerBg, C.Danger) { vm.showRules(true) }
                    } else {
                        StatusChip("No gazette restrictions active for ${s.vehicle.label} right now", C.OkBg, C.Ok) { vm.showRules(true) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text("Search above, or long-press the map to set a start or destination.", color = C.Muted, fontSize = 15.sp)
            }

            s.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                val neutral = msg.startsWith("Loading") || msg.startsWith("Waiting") || msg.startsWith("Routing from") || msg.startsWith("Location is off") || msg.contains("gazette rule") || msg.contains("long-press", true)
                val good = msg.startsWith("You have arrived") || msg.startsWith("Thanks") || msg.startsWith("Imported")
                StatusChip(
                    msg,
                    if (good) C.OkBg else if (neutral) C.Chip else C.DangerBg,
                    if (good) C.Ok else if (neutral) C.Muted else C.Danger
                )
            }
            if (s.report == ReportStep.START || s.report == ReportStep.END) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Cancel report", color = C.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.cancelReport() }.padding(vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Offline · Map data © OpenStreetMap contributors · Rules as of 26 Nov 2026", color = C.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PinSheet(p: LatLon, step: ReportStep, vm: NavViewModel) {
    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, shadowElevation = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(C.Line)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                when (step) {
                    ReportStep.START -> "No-entry road starts here"
                    ReportStep.END -> "No-entry road ends here"
                    else -> "Dropped pin"
                },
                color = C.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Text("%.5f, %.5f".format(p.lat, p.lon), color = C.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            if (step == ReportStep.START || step == ReportStep.END) {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Danger)
                        .clickable { vm.markReportPoint(p) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlagIcon(Color.White, 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (step == ReportStep.START) "Mark no-entry start" else "Mark no-entry end",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Chip)
                            .clickable { vm.setOrigin(p) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(C.Primary).padding(3.dp).clip(CircleShape).background(Color.White))
                            Spacer(Modifier.width(8.dp))
                            Text("Start here", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Primary)
                            .clickable { vm.setDestination(p) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PinIcon(Color.White, 20.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Go here", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(C.DangerBg)
                        .clickable { vm.beginReport(); vm.markReportPoint(p) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlagIcon(C.Danger, 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Report no-entry road from here", color = C.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cancel", color = C.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .clickable { if (step == ReportStep.NONE) vm.dismissPin() else vm.cancelReport() }.padding(12.dp)
            )
        }
    }
}

@Composable
private fun SelChip(text: String, sel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (sel) Color(0xFFEFF6FF) else Color.White)
            .border(1.5.dp, if (sel) C.Primary else C.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (sel) C.Primary else C.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun ReportSheet(s: UiState, vm: NavViewModel) {
    var veh by remember { mutableStateOf(setOf(s.vehicle.key)) }
    var win by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    val wins = listOf("All day", "07-23", "Peak hours", "Night 23-07")
    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, shadowElevation = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(C.Line)
            )
            Spacer(Modifier.height(14.dp))
            if (s.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = C.Primary, trackColor = C.Chip
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagIcon(C.Danger, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text("Report no-entry road", color = C.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text("From the first red pin to the second. Used for routing on this phone.", color = C.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Text("Vehicles", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VEHICLES.forEach { v ->
                    SelChip(v.label, v.key in veh, Modifier.weight(1f)) {
                        veh = if (v.key in veh) (if (veh.size > 1) veh - v.key else veh) else veh + v.key
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("When", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wins.forEachIndexed { i, w -> SelChip(w, win == i, Modifier.weight(1f)) { win = i } }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Chip).padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                BasicTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = C.Ink), cursorBrush = SolidColor(C.Primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box { if (name.isEmpty()) Text("Road name (optional)", color = C.Muted, fontSize = 15.sp); inner() }
                    }
                )
            }
            s.message?.let { Spacer(Modifier.height(10.dp)); StatusChip(it, C.DangerBg, C.Danger) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(2f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Primary)
                        .clickable(enabled = !s.busy) { vm.saveReport(veh, win, name) },
                    contentAlignment = Alignment.Center
                ) { Text("Save report", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.Chip)
                        .clickable { vm.cancelReport() },
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun RouteChips(s: UiState, vm: NavViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.routes.forEachIndexed { i, r ->
            val sel = r === s.route
            val diff = ((r.timeSec - s.routes[0].timeSec) / 60).roundToInt()
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Color(0xFFEFF6FF) else Color.White)
                    .border(1.5.dp, if (sel) C.Primary else C.Line, RoundedCornerShape(14.dp))
                    .clickable { vm.selectRoute(i) }.padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(fmtTime(r.timeSec), color = if (sel) C.Primary else C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    if (i == 0) "Fastest" else "+$diff min", color = C.Muted, fontSize = 12.sp, maxLines = 1
                )
                Text(
                    if (r.via.isBlank()) "${"%.1f".format(r.distKm)} km" else "via ${r.via}",
                    color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
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
internal fun Segmented(current: Vehicle, onPick: (Vehicle) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Chip).padding(4.dp)) {
        VEHICLES.forEach { v ->
            val sel = v.key == current.key
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) Color.White else Color.Transparent)
                    .clickable { onPick(v) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TruckIcon(VEHICLES.indexOf(v).coerceAtLeast(0), if (sel) C.Primary else C.Muted, 30.dp)
                    Text(
                        v.label, color = if (sel) C.Primary else C.Ink, fontSize = 16.sp, maxLines = 1,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold
                    )
                    Text(v.sub, color = C.Muted, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun StatusChip(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (fg) {
            C.Danger -> WarnIcon(fg, 20.dp)
            C.Ok -> CheckIcon(fg, 20.dp)
            else -> InfoIcon(fg, 20.dp)
        }
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
internal fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = Color.White, shadowElevation = 10.dp) {
        Box(Modifier.size(52.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
    }
}

// ---------------------------------------------------------------- icons (drawn, no icon library)

@Composable
internal fun SearchIcon(color: Color, dp: Dp = 22.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color = color, radius = w * 0.32f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(width = w * 0.11f))
        drawLine(color = color, start = Offset(w * 0.66f, w * 0.66f), end = Offset(w * 0.9f, w * 0.9f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

@Composable
internal fun CloseIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color = color, start = Offset(w * 0.15f, w * 0.15f), end = Offset(w * 0.85f, w * 0.85f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(w * 0.85f, w * 0.15f), end = Offset(w * 0.15f, w * 0.85f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
    }
}

@Composable
internal fun TargetIcon(color: Color, dp: Dp = 24.dp) {
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

@Composable
internal fun BackIcon(color: Color, dp: Dp = 24.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color = color, start = Offset(w * 0.85f, w / 2), end = Offset(w * 0.15f, w / 2), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        val head = Path().apply { moveTo(w * 0.45f, w * 0.2f); lineTo(w * 0.15f, w / 2); lineTo(w * 0.45f, w * 0.8f) }
        drawPath(head, color = color, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun SwapIcon(color: Color, dp: Dp = 22.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val sw = w * 0.1f
        drawLine(color = color, start = Offset(w * 0.32f, w * 0.88f), end = Offset(w * 0.32f, w * 0.14f), strokeWidth = sw, cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * 0.12f, w * 0.34f); lineTo(w * 0.32f, w * 0.12f); lineTo(w * 0.52f, w * 0.34f) },
            color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color = color, start = Offset(w * 0.68f, w * 0.12f), end = Offset(w * 0.68f, w * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * 0.48f, w * 0.66f); lineTo(w * 0.68f, w * 0.88f); lineTo(w * 0.88f, w * 0.66f) },
            color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A page with text lines: opens the gazette rules list. */
@Composable
internal fun RulesIcon(color: Color, dp: Dp = 24.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val sw = w * 0.09f
        drawRoundRect(color = color, topLeft = Offset(w * 0.2f, w * 0.1f), size = androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.8f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = Stroke(width = sw))
        for (y in listOf(0.34f, 0.5f, 0.66f)) {
            drawLine(color = color, start = Offset(w * 0.34f, w * y), end = Offset(w * 0.66f, w * y), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}
