package app.noentrynav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Line icons drawn on a Canvas (no icon library), one stroke weight so they look like a set.

private fun DrawScope.st(w: Float) = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Truck outline. variant 0 = LCV (short, low box), 1 = MCV (longer, taller), 2 = HCV (long, tall, extra axle). */
@Composable
fun TruckIcon(variant: Int, color: Color, dp: Dp = 28.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val s = st(w)
        val boxLeft = when (variant) { 0 -> 0.24f; 1 -> 0.12f; else -> 0.02f }
        val boxTop = when (variant) { 0 -> 0.40f; 1 -> 0.32f; else -> 0.22f }
        drawRoundRect(color, Offset(w * boxLeft, w * boxTop), Size(w * (0.62f - boxLeft), w * (0.64f - boxTop)),
            CornerRadius(w * 0.04f), style = s)
        val cab = Path().apply {
            moveTo(w * 0.62f, w * 0.38f); lineTo(w * 0.80f, w * 0.38f); lineTo(w * 0.95f, w * 0.54f)
            lineTo(w * 0.95f, w * 0.64f); lineTo(w * 0.62f, w * 0.64f)
        }
        drawPath(cab, color, style = s)
        val wheels = when (variant) { 0 -> listOf(0.40f, 0.80f); 1 -> listOf(0.28f, 0.80f); else -> listOf(0.14f, 0.32f, 0.80f) }
        wheels.forEach { x ->
            drawCircle(Color.White, w * 0.085f, Offset(w * x, w * 0.74f))
            drawCircle(color, w * 0.085f, Offset(w * x, w * 0.74f), style = s)
        }
    }
}

@Composable
fun WarnIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val p = Path().apply { moveTo(w * 0.5f, w * 0.12f); lineTo(w * 0.92f, w * 0.85f); lineTo(w * 0.08f, w * 0.85f); close() }
        drawPath(p, color, style = st(w))
        drawLine(color, Offset(w * 0.5f, w * 0.38f), Offset(w * 0.5f, w * 0.6f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawCircle(color, w * 0.05f, Offset(w * 0.5f, w * 0.73f))
    }
}

@Composable
fun CheckIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color, w * 0.4f, Offset(w / 2, w / 2), style = st(w))
        val p = Path().apply { moveTo(w * 0.32f, w * 0.52f); lineTo(w * 0.45f, w * 0.65f); lineTo(w * 0.69f, w * 0.38f) }
        drawPath(p, color, style = st(w))
    }
}

@Composable
fun InfoIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color, w * 0.4f, Offset(w / 2, w / 2), style = st(w))
        drawLine(color, Offset(w * 0.5f, w * 0.46f), Offset(w * 0.5f, w * 0.7f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawCircle(color, w * 0.05f, Offset(w * 0.5f, w * 0.31f))
    }
}

@Composable
fun FlagIcon(color: Color, dp: Dp = 20.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color, Offset(w * 0.24f, w * 0.9f), Offset(w * 0.24f, w * 0.1f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        val p = Path().apply {
            moveTo(w * 0.24f, w * 0.14f); lineTo(w * 0.84f, w * 0.14f); lineTo(w * 0.7f, w * 0.34f); lineTo(w * 0.84f, w * 0.54f); lineTo(w * 0.24f, w * 0.54f)
        }
        drawPath(p, color, style = st(w))
    }
}

@Composable
fun PinIcon(color: Color, dp: Dp = 20.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color, w * 0.24f, Offset(w * 0.5f, w * 0.38f), style = st(w))
        drawLine(color, Offset(w * 0.31f, w * 0.53f), Offset(w * 0.5f, w * 0.9f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.69f, w * 0.53f), Offset(w * 0.5f, w * 0.9f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawCircle(color, w * 0.07f, Offset(w * 0.5f, w * 0.38f))
    }
}

@Composable
fun ShareIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val a = Offset(w * 0.25f, w * 0.5f); val b = Offset(w * 0.74f, w * 0.24f); val c = Offset(w * 0.74f, w * 0.76f)
        drawLine(color, a, b, strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        drawLine(color, a, c, strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        listOf(a, b, c).forEach { drawCircle(Color.White, w * 0.13f, it); drawCircle(color, w * 0.13f, it, style = st(w)) }
    }
}

@Composable
fun ImportIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color, Offset(w * 0.5f, w * 0.12f), Offset(w * 0.5f, w * 0.6f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * 0.3f, w * 0.42f); lineTo(w * 0.5f, w * 0.62f); lineTo(w * 0.7f, w * 0.42f) }, color, style = st(w))
        drawPath(Path().apply { moveTo(w * 0.18f, w * 0.64f); lineTo(w * 0.18f, w * 0.86f); lineTo(w * 0.82f, w * 0.86f); lineTo(w * 0.82f, w * 0.64f) }, color, style = st(w))
    }
}

@Composable
fun VolumeIcon(on: Boolean, color: Color, dp: Dp = 20.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val sp = Path().apply {
            moveTo(w * 0.12f, w * 0.4f); lineTo(w * 0.28f, w * 0.4f); lineTo(w * 0.46f, w * 0.24f)
            lineTo(w * 0.46f, w * 0.76f); lineTo(w * 0.28f, w * 0.6f); lineTo(w * 0.12f, w * 0.6f); close()
        }
        drawPath(sp, color, style = st(w))
        if (on) {
            drawArc(color, -45f, 90f, false, Offset(w * 0.42f, w * 0.32f), Size(w * 0.3f, w * 0.36f), style = st(w))
            drawArc(color, -45f, 90f, false, Offset(w * 0.36f, w * 0.18f), Size(w * 0.5f, w * 0.64f), style = st(w))
        } else {
            drawLine(color, Offset(w * 0.64f, w * 0.38f), Offset(w * 0.88f, w * 0.62f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.88f, w * 0.38f), Offset(w * 0.64f, w * 0.62f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun ClockIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color, w * 0.4f, Offset(w / 2, w / 2), style = st(w))
        drawPath(Path().apply { moveTo(w * 0.5f, w * 0.28f); lineTo(w * 0.5f, w * 0.5f); lineTo(w * 0.66f, w * 0.6f) }, color, style = st(w))
    }
}

@Composable
fun RoadIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color, Offset(w * 0.28f, w * 0.92f), Offset(w * 0.4f, w * 0.08f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, w * 0.92f), Offset(w * 0.6f, w * 0.08f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        listOf(0.14f to 0.3f, 0.42f to 0.58f, 0.7f to 0.86f).forEach { (a, b) ->
            drawLine(color, Offset(w * 0.5f, w * a), Offset(w * 0.5f, w * b), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun AreaIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawRoundRect(
            color, Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f), CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.14f, w * 0.1f)))
        )
        drawCircle(color, w * 0.08f, Offset(w / 2, w / 2))
    }
}

@Composable
fun TrashIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color, Offset(w * 0.15f, w * 0.26f), Offset(w * 0.85f, w * 0.26f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.4f, w * 0.26f), Offset(w * 0.4f, w * 0.14f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.6f, w * 0.26f), Offset(w * 0.6f, w * 0.14f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawPath(Path().apply { moveTo(w * 0.24f, w * 0.26f); lineTo(w * 0.28f, w * 0.88f); lineTo(w * 0.72f, w * 0.88f); lineTo(w * 0.76f, w * 0.26f) }, color, style = st(w))
    }
}

/** Filled arrow for "Start". */
@Composable
fun NavigateIcon(color: Color, dp: Dp = 20.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        val p = Path().apply { moveTo(w * 0.5f, w * 0.08f); lineTo(w * 0.86f, w * 0.9f); lineTo(w * 0.5f, w * 0.7f); lineTo(w * 0.14f, w * 0.9f); close() }
        drawPath(p, color, style = Fill)
    }
}

@Composable
fun PersonIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawCircle(color, w * 0.18f, Offset(w * 0.5f, w * 0.3f), style = st(w))
        drawPath(Path().apply { moveTo(w * 0.16f, w * 0.88f); cubicTo(w * 0.16f, w * 0.55f, w * 0.84f, w * 0.55f, w * 0.84f, w * 0.88f) }, color, style = st(w))
    }
}

@Composable
fun PlusIcon(color: Color, dp: Dp = 22.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color, Offset(w * 0.2f, w / 2), Offset(w * 0.8f, w / 2), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        drawLine(color, Offset(w / 2, w * 0.2f), Offset(w / 2, w * 0.8f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

@Composable
fun MinusIcon(color: Color, dp: Dp = 22.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawLine(color, Offset(w * 0.2f, w / 2), Offset(w * 0.8f, w / 2), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

@Composable
fun RefreshIcon(color: Color, dp: Dp = 18.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawArc(color, 40f, 280f, false, Offset(w * 0.16f, w * 0.16f), Size(w * 0.68f, w * 0.68f), style = st(w))
        drawPath(Path().apply { moveTo(w * 0.62f, w * 0.1f); lineTo(w * 0.8f, w * 0.26f); lineTo(w * 0.56f, w * 0.34f) }, color, style = st(w))
    }
}

@Composable
fun BuildingIcon(color: Color, dp: Dp = 20.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.minDimension
        drawRoundRect(color, Offset(w * 0.2f, w * 0.12f), Size(w * 0.6f, w * 0.76f), CornerRadius(w * 0.05f), style = st(w))
        for (r in listOf(0.28f, 0.46f)) for (c in listOf(0.34f, 0.58f)) drawRect(color, Offset(w * c, w * r), Size(w * 0.08f, w * 0.08f))
        drawRect(color, Offset(w * 0.44f, w * 0.68f), Size(w * 0.12f, w * 0.2f))
    }
}

fun kindLabel(kind: String): String = when (kind) {
    "street" -> "Road / street"
    "" -> "Place"
    else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private val LOCALITY = setOf("suburb", "neighbourhood", "village", "town", "city", "locality", "quarter", "hamlet", "city_block")
private val PREMISES = setOf("building", "warehouse", "industrial", "commercial", "retail", "works", "office", "yes", "mall", "supermarket",
    "factory", "residential", "storage_tank", "marketplace", "department_store", "convenience", "hardware", "car_repair", "fuel")

@Composable
fun PlaceKindIcon(kind: String, color: Color, dp: Dp = 22.dp) {
    when {
        kind == "street" -> RoadIcon(color, dp)
        kind in LOCALITY -> AreaIcon(color, dp)
        kind in PREMISES -> BuildingIcon(color, dp)
        else -> PinIcon(color, dp)
    }
}
