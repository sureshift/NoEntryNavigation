package app.noentrynav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun mb(bytes: Long) = "${maxOf(1L, bytes / 1_000_000)} MB"

/** Offline city maps: download a city once, then it works without internet. */
@Composable
fun MapsScreen(s: UiState, vm: NavViewModel) {
    var q by remember { mutableStateOf("") }
    val list = s.catalog.filter { q.isBlank() || it.name.contains(q, true) || it.state.contains(q, true) }
    val suggested = s.catalog.firstOrNull { it.id == s.packSuggest }
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF8FAFC)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (s.needPack) Image(painterResource(R.drawable.routiq_logo), contentDescription = "ROUTIQ", modifier = Modifier.padding(start = 20.dp, top = 18.dp).height(34.dp))
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!s.needPack) Box(Modifier.size(48.dp).clip(CircleShape).clickable { vm.showMaps(false) }, contentAlignment = Alignment.Center) { BackIcon(C.Ink) }
                else Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(start = 6.dp)) {
                    Text(if (s.needPack) "Choose your city" else "Offline maps", color = C.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Download a city once. It then works without internet.", color = C.Muted, fontSize = 13.sp)
                }
            }
            Box(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                    .border(1.dp, C.Line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                BasicTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = C.Ink), cursorBrush = SolidColor(C.Primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search city or state", color = C.Muted, fontSize = 16.sp); inner() } }
                )
            }
            if (suggested != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp), color = Color(0xFFEFF6FF), modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth()
                ) {
                    Text("That place is in ${suggested.name}. Download its map to search and route there.", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp))
                }
            }
            Text("Maps you no longer need are removed automatically after each trip.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
            s.packNote?.let { Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) { StatusChip(it, C.DangerBg, C.Danger) } }
            if (s.catalog.isEmpty()) {
                Column(Modifier.padding(24.dp)) {
                    Text("City list not loaded yet.", color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Connect to the internet once to see the cities.", color = C.Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(C.Primary).clickable { vm.refreshCatalog() }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (s.installed.isNotEmpty() && !s.needPack) Spacer(Modifier.height(0.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list.sortedWith(compareByDescending<PackInfo> { it.id == s.packSuggest }.thenByDescending { it.id in s.installed }.thenBy { it.name }), key = { it.id }) { p ->
                    val have = p.id in s.installed
                    val active = p.id == s.packId && s.ready
                    val busy = s.packBusy == p.id
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, if (p.id == s.packSuggest) C.Primary else C.Line), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, color = C.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(p.state + " · " + mb(p.size) + (if (active) " · in use" else if (have) " · on this phone" else ""), color = C.Muted, fontSize = 13.sp)
                                }
                                when {
                                    busy -> Text("${s.packPct}%", color = C.Primary, fontWeight = FontWeight.Bold)
                                    active -> Text("In use", color = C.Ok, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    have -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Primary).clickable { vm.usePack(p.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                            Text("Open", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    else -> Box(
                                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (s.packBusy == null) C.Primary else Color(0xFF94A3B8))
                                            .clickable(enabled = s.packBusy == null) { vm.downloadPack(p.id) }.padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) { Text("Download", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                            if (busy) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(progress = { s.packPct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = C.Primary, trackColor = C.Line)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)); Text("Outside Delhi NCR the app has no gazette rules yet. No-entry roads come from drivers and admin approval.", color = C.Muted, fontSize = 12.sp); Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
