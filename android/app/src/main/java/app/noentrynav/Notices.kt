package app.noentrynav

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFFF8FAFC)
private val DAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun daysText(d: Set<Int>): String = when {
    d.size == 7 -> "Every day"
    else -> d.sorted().joinToString(", ") { DAY[it - 1] }
}

@Composable
fun RulesScreen(s: UiState, vm: NavViewModel) {
    var tab by remember { mutableStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).clickable { vm.showRules(false) }, contentAlignment = Alignment.Center) {
                    BackIcon(C.Ink)
                }
                Column(Modifier.padding(start = 6.dp)) {
                    Text("No-entry rules", color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Delhi gazette notifications · as of 26 Nov 2026", color = C.Muted, fontSize = 13.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.feedUpdated.isBlank()) "Online updates: not fetched yet" else "Online updates as of ${s.feedUpdated}",
                        color = C.Muted, fontSize = 13.sp
                    )
                    s.feedStatus?.let { Text(it, color = C.Muted, fontSize = 12.sp) }
                }
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFFEFF6FF))
                        .clickable(enabled = !s.feedBusy) { vm.refreshFeed(true) }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RefreshIcon(C.Primary, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (s.feedBusy) "Checking…" else "Check for updates", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(horizontal = 16.dp)) { Segmented(s.vehicle) { vm.setVehicle(it) } }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Rules", tab == 0) { tab = 0 }
                Pill("Gazette notifications", tab == 1) { tab = 1 }
            }
            Spacer(Modifier.height(10.dp))
            if (tab == 0) RuleList(s, vm) else NoticeList(s)
        }
    }
}

@Composable
private fun Pill(text: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (sel) C.Ink else Color.White)
            .border(1.dp, if (sel) C.Ink else C.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Text(text, color = if (sel) Color.White else C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun ActionChip(text: String, fg: Color, bg: Color, modifier: Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
            .border(1.dp, C.Line, RoundedCornerShape(18.dp)).padding(16.dp)
    ) { content() }
}

@Composable
private fun RuleList(s: UiState, vm: NavViewModel) {
    val ctx = LocalContext.current
    val activeIds = s.zones.map { it.id }.toSet()
    val groups = s.allZones.filter { s.vehicle.key in it.vehicles }.groupBy { it.ruleId }.values
        .sortedByDescending { g -> g.any { it.id in activeIds } }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Report road", C.Danger, C.DangerBg, Modifier.weight(1.2f), { FlagIcon(C.Danger, 18.dp) }) { vm.beginReport() }
                ActionChip("Share", C.Primary, Color(0xFFEFF6FF), Modifier.weight(0.9f), { ShareIcon(C.Primary, 17.dp) }) {
                    try {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.exportReports())
                        ctx.startActivity(Intent.createChooser(send, "Share driver reports").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
                ActionChip("Import", C.Primary, Color(0xFFEFF6FF), Modifier.weight(0.9f), { ImportIcon(C.Primary, 17.dp) }) {
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                        vm.importReports(t)
                    } catch (_: Exception) { vm.importReports("") }
                }
            }
        }
        s.message?.let { m -> item { StatusChip(m, if (m.startsWith("Imported") || m.startsWith("Thanks")) C.OkBg else C.Chip, if (m.startsWith("Imported") || m.startsWith("Thanks")) C.Ok else C.Muted) } }
        item {
            val n = groups.count { g -> g.any { it.id in activeIds } }
            if (n > 0) StatusChip("$n rule${if (n > 1) "s" else ""} active for ${s.vehicle.label} right now. Routes avoid them.", C.DangerBg, C.Danger)
            else StatusChip("Nothing is restricted for ${s.vehicle.label} right now.", C.OkBg, C.Ok)
        }
        if (groups.isEmpty()) item { Text("No rules loaded.", color = C.Muted, fontSize = 15.sp) }
        items(groups.toList()) { g ->
            val z = g.first()
            val active = g.any { it.id in activeIds }
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 2.dp, end = 10.dp)) {
                        val ic = if (active) C.Danger else C.Muted
                        when {
                            z.community -> PersonIcon(ic, 20.dp)
                            z.isRoad -> RoadIcon(ic, 20.dp)
                            else -> AreaIcon(ic, 20.dp)
                        }
                    }
                    Text(
                        if (g.size > 1 && !z.community) "Notified market areas (${g.size})" else z.name,
                        color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    if (active) Badge("Active now", C.DangerBg, C.Danger) else Badge("Not now", C.Chip, C.Muted)
                }
                if (z.community) {
                    Spacer(Modifier.height(6.dp))
                    Badge("Driver report", Color(0xFFFEF3C7), Color(0xFF92400E))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClockIcon(C.Muted, 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("${z.windowText()} · ${daysText(z.days)}" + (if (z.validTo < 99999999) " · until ${z.validTo % 100}/${(z.validTo / 100) % 100}/${z.validTo / 10000}" else ""), color = C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (g.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(g.joinToString(", ") { it.name.removeSuffix(" market") }, color = C.Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VEHICLES.filter { it.key in z.vehicles }.forEach { Badge(it.label, C.Chip, C.Ink) }
                }
                Spacer(Modifier.height(8.dp))
                val note = when {
                    z.community -> "Applied to ${z.segs?.size ?: 0} road segments between the two reported points."
                    z.isRoad && !z.enforceable -> "Road not found on the map. This rule is NOT applied to routing."
                    z.isRoad -> "Applied to ${z.segs?.size ?: 0} road segments on the map. Extent is approximate."
                    else -> "Area boundary is approximate."
                }
                Text(note, color = if (z.isRoad && !z.enforceable) C.Danger else C.Muted, fontSize = 12.sp)
                if (z.community) {
                    Text("Reported by a driver. Not an official notification.", color = C.Muted, fontSize = 12.sp)
                    if (!z.reportId.startsWith("C-")) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.clickable { vm.deleteReport(z.reportId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrashIcon(C.Danger, 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Delete report", color = C.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    val n = s.notices.firstOrNull { it.id == z.notification }
                    if (n != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Source: " + n.title + if (n.ref.isNotBlank()) " (${n.ref})" else "", color = C.Muted, fontSize = 12.sp)
                        if (n.url.isNotBlank()) {
                            Row(
                                Modifier.clickable {
                                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    catch (_: Exception) {}
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                InfoIcon(C.Primary, 16.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Open notification", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else if (z.notification.isNotBlank()) Text(z.notification, color = C.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun NoticeList(s: UiState) {
    val ctx = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(s.notices) { n ->
            Card {
                val (label, bg, fg) = when (n.kind) {
                    "timing" -> Triple("Timing restriction", C.DangerBg, C.Danger)
                    "entry" -> Triple("Entry permission", Color(0xFFFEF3C7), Color(0xFF92400E))
                    "temporary" -> Triple("Diversion / temporary", Color(0xFFFEF3C7), Color(0xFF92400E))
                    else -> Triple("Advisory", C.Chip, C.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (n.kind) {
                        "timing" -> ClockIcon(fg, 18.dp)
                        "entry" -> RulesIcon(fg, 18.dp)
                        "temporary" -> WarnIcon(fg, 18.dp)
                        else -> InfoIcon(fg, 18.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Badge(label, bg, fg)
                }
                Spacer(Modifier.height(8.dp))
                Text(n.title, color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(listOf(n.authority, n.ref).filter { it.isNotBlank() }.joinToString(" · "), color = C.Muted, fontSize = 13.sp)
                val g = listOf(n.date, n.gazette).filter { it.isNotBlank() }.joinToString(" · ")
                if (g.isNotBlank()) Text(g, color = C.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(n.summary, color = C.Ink, fontSize = 14.sp)
                if (n.url.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Open official source", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: Exception) {}
                        }
                    )
                }
            }
        }
        item {
            Text(
                "Rules are read from the published notifications and may change. Always obey on-road signs and police. " +
                    "Road extents are matched to OpenStreetMap names and are approximate.",
                color = C.Muted, fontSize = 12.sp
            )
        }
    }
}
