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

/**
 * Vehicle illustrations in the flat, filled side-view style used by delivery apps (1.5 : 1).
 * variant 0 = Tata Ace style mini truck (LCV)
 * variant 1 = Canter style box truck (MCV)
 * variant 2 = tractor with container trailer (HCV)
 * `color` is the body colour; window, wheels and details are drawn in neutral tones.
 */
@Composable
fun TruckIcon(variant: Int, color: Color, dp: Dp = 28.dp) {
    Canvas(Modifier.size(dp * 1.5f, dp)) {
        val w = size.width
        val h = size.height
        fun x(v: Float) = w * v
        fun y(v: Float) = h * v
        val glass = Color(0xFFDCEBFA)
        val dark = Color(0xFF263238)
        val shade = color.copy(alpha = 0.55f)
        fun rect(l: Float, t: Float, r: Float, b: Float, c: Color, cr: Float = 0.03f) =
            drawRoundRect(c, Offset(x(l), y(t)), Size(x(r - l), y(b - t)), CornerRadius(h * cr))
        fun poly(c: Color, vararg pts: Pair<Float, Float>) = drawPath(Path().apply {
            moveTo(x(pts[0].first), y(pts[0].second)); for (i in 1 until pts.size) lineTo(x(pts[i].first), y(pts[i].second)); close()
        }, c)
        fun wheel(cx: Float, r: Float = 0.105f) {
            drawCircle(dark, h * r, Offset(x(cx), y(0.80f)))
            drawCircle(Color(0xFFB0BEC5), h * r * 0.45f, Offset(x(cx), y(0.80f)))
        }
        when (variant) {
            0 -> {   // mini truck: short closed cargo box, small cab
                rect(0.05f, 0.30f, 0.60f, 0.72f, color)
                rect(0.05f, 0.62f, 0.60f, 0.72f, shade, 0.01f)
                poly(color, 0.60f to 0.72f, 0.60f to 0.38f, 0.76f to 0.38f, 0.90f to 0.56f, 0.95f to 0.58f, 0.95f to 0.72f)
                poly(glass, 0.64f to 0.45f, 0.75f to 0.45f, 0.85f to 0.57f, 0.64f to 0.57f)
                rect(0.92f, 0.62f, 0.97f, 0.68f, Color(0xFFFFC107), 0.01f)
                rect(0.03f, 0.72f, 0.97f, 0.76f, dark, 0.01f)
                wheel(0.24f); wheel(0.78f)
            }
            1 -> {   // box truck: tall closed body, cab-over cabin
                rect(0.04f, 0.12f, 0.64f, 0.70f, color)
                rect(0.04f, 0.58f, 0.64f, 0.70f, shade, 0.01f)
                poly(color, 0.66f to 0.70f, 0.66f to 0.28f, 0.84f to 0.28f, 0.95f to 0.48f, 0.95f to 0.70f)
                poly(glass, 0.70f to 0.35f, 0.83f to 0.35f, 0.90f to 0.48f, 0.70f to 0.48f)
                rect(0.92f, 0.58f, 0.97f, 0.64f, Color(0xFFFFC107), 0.01f)
                rect(0.03f, 0.70f, 0.97f, 0.75f, dark, 0.01f)
                wheel(0.20f); wheel(0.34f); wheel(0.80f)
            }
            else -> {   // tractor + container trailer
                rect(0.02f, 0.14f, 0.70f, 0.66f, color)
                for (i in 1..5) rect(0.02f + i * 0.1f, 0.18f, 0.02f + i * 0.1f + 0.015f, 0.62f, shade, 0.005f)
                rect(0.02f, 0.66f, 0.97f, 0.72f, dark, 0.01f)
                poly(color, 0.73f to 0.66f, 0.73f to 0.30f, 0.87f to 0.30f, 0.97f to 0.48f, 0.97f to 0.66f)
                poly(glass, 0.76f to 0.36f, 0.86f to 0.36f, 0.92f to 0.47f, 0.76f to 0.47f)
                rect(0.94f, 0.56f, 0.985f, 0.62f, Color(0xFFFFC107), 0.01f)
                wheel(0.10f, 0.09f); wheel(0.22f, 0.09f); wheel(0.34f, 0.09f); wheel(0.80f, 0.09f); wheel(0.91f, 0.09f)
            }
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
