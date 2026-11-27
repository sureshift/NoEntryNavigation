package app.noentrynav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PageBg = Color(0xFFF8FAFC)

/** Separate page: a driver or office person fills in the no-entry details of a route (opened from Share or "Add no-entry"). */
@Composable
fun RouteFormScreen(s: UiState, vm: NavViewModel) {
    var veh by remember { mutableStateOf(setOf(s.vehicle.key)) }
    var win by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    val wins = listOf("All day", "07-23", "Peak hours", "Night 23-07")
    val mine = s.allZones.filter { it.community && !it.reportId.startsWith("C-") }
    Surface(Modifier.fillMaxSize(), color = PageBg) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable { vm.showForm(false) }, contentAlignment = Alignment.Center) { BackIcon(C.Ink) }
                Column(Modifier.padding(start = 6.dp)) {
                    Text("Route no-entry details", color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Add roads where trucks are not allowed", color = C.Muted, fontSize = 13.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 16.dp)) {
                // route card
                Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, C.Line), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Route", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (s.originName.isBlank()) "Current location" else s.originName) + "  to  " + s.destName.ifBlank { "Destination" },
                            color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        val r = s.route
                        Text(
                            s.vehicle.label + (if (r != null) " · %.1f km · %s".format(r.distKm, fmtTime(r.timeSec)) else " · finding route…"),
                            color = C.Muted, fontSize = 14.sp
                        )
                        if (s.dest != null) { Spacer(Modifier.height(10.dp)); RouteActions(vm) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Add a no-entry road", color = C.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Fill the two ends of the road stretch. They must be on the same road.", color = C.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PointField("1. Where the no-entry starts", s.reportA, s, vm, true)
                Spacer(Modifier.height(12.dp))
                PointField("2. Where it ends", s.reportB, s, vm, false)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(C.Chip).clickable { vm.beginReport() }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { FlagIcon(C.Danger, 16.dp); Spacer(Modifier.width(6.dp)); Text("Or pick both points on the map", color = C.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(14.dp))
                Text("3. Which vehicles", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VEHICLES.forEach { v ->
                        SelChip(v.label, v.key in veh, Modifier.weight(1f)) {
                            veh = if (v.key in veh) (if (veh.size > 1) veh - v.key else veh) else veh + v.key
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("4. When", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    wins.forEachIndexed { i, w -> SelChip(w, win == i, Modifier.weight(1f)) { win = i } }
                }
                Spacer(Modifier.height(12.dp))
                Text("5. Road name (optional)", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FieldBox(name, "e.g. Ring Road near Naraina", { name = it.take(60) }, Modifier.fillMaxWidth())
                if (vm.communityOn()) {
                    var share by remember { mutableStateOf(vm.shareReportsOn()) }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(14.dp))
                            .clickable { share = !share; vm.setShareReports(share) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Send to admin", color = C.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Turned on by default. Roads the admin approves reach every driver.", color = C.Muted, fontSize = 12.sp)
                        }
                        androidx.compose.material3.Switch(checked = share, onCheckedChange = { share = it; vm.setShareReports(it) })
                    }
                }
                s.message?.let { Spacer(Modifier.height(10.dp)); StatusChip(it, C.DangerBg, C.Danger) }
                Spacer(Modifier.height(14.dp))
                val ready = s.reportA != null && s.reportB != null && !s.busy
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (ready) C.Primary else Color(0xFF94A3B8))
                        .clickable(enabled = ready) { vm.saveReport(veh, win, name); name = "" },
                    contentAlignment = Alignment.Center
                ) { Text(if (s.busy) "Saving…" else "Save no-entry road", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(22.dp))
                Text("Added by drivers (${mine.size})", color = C.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (mine.isEmpty()) Text("None yet. Roads you add appear here and travel with Share route.", color = C.Muted, fontSize = 14.sp)
                mine.forEach { z ->
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, C.Line), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(z.name, color = C.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(z.vehicles.joinToString(" ") { it.uppercase() } + " · " + z.windowText(), color = C.Muted, fontSize = 13.sp)
                            }
                            Box(Modifier.size(40.dp).clip(CircleShape).clickable { vm.deleteReport(z.reportId) }, contentAlignment = Alignment.Center) { CloseIcon(C.Danger) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldBox(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, C.Line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = C.Ink), cursorBrush = SolidColor(C.Primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = C.Muted, fontSize = 15.sp); inner() } }
        )
    }
}

@Composable
private fun PointField(label: String, point: LatLon?, s: UiState, vm: NavViewModel, start: Boolean) {
    var txt by remember { mutableStateOf("") }
    Text(label, color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    if (point != null) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.OkBg).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Set: %.5f, %.5f".format(point.lat, point.lon), color = C.Ok, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldBox(txt, "Lat, long / Maps link / Plus Code", { txt = it }, Modifier.weight(1f))
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(C.Primary).clickable { vm.setFormPointFromText(start, txt); txt = "" }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text("Set", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val o = s.origin ?: s.me
        if (o != null) SelChip("Route start", false) { vm.setFormPoint(start, o) }
        s.dest?.let { d -> SelChip("Route end", false) { vm.setFormPoint(start, d) } }
        s.me?.let { m -> SelChip("My GPS", false) { vm.setFormPoint(start, m) } }
        if (point != null) SelChip("Change", false) { vm.clearFormPoint(start) }
    }
}
