package app.noentrynav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows the route-driven map download: what is being prepared, how far it is, and Retry / Cancel when it stopped. */
@Composable
fun TripBanner(t: TripState, vm: NavViewModel) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (t.failed) C.DangerBg else androidx.compose.ui.graphics.Color.White,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(t.text, color = if (t.failed) C.Danger else C.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (!t.failed) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Chip)) {
                    Box(Modifier.fillMaxWidth(t.pct.coerceIn(2, 100) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Primary))
                }
                Spacer(Modifier.height(6.dp))
                Text("Only the roads on your way are downloaded, and they are removed after the trip.", color = C.Muted, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Retry", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.Primary).clickable { vm.retryTrip() }.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                    Text(
                        "Cancel", color = C.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.Chip).clickable { vm.clearRoute() }.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
