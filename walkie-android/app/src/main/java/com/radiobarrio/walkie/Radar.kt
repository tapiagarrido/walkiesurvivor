package com.radiobarrio.walkie

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

val ColDirecto = Color(0xFF3FB27F)
val ColRelevo = Color(0xFFE0A526)
private val ColAnillo = Color(0xFF2E3A44)
private val ColFondoRadar = Color(0xFF0E1A17)

object Geo {
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun compass(deg: Double): String =
        listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((deg + 22.5) % 360) / 45).toInt()]

    fun fmt(m: Double): String = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)

    private val escalas = listOf(50.0, 100.0, 200.0, 300.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
    fun escala(maxDist: Double): Double = escalas.firstOrNull { it >= maxDist * 1.15 } ?: escalas.last()
}

/** Posición en pantalla (relativa al centro, en fracción del radio) de cada nodo. */
data class RadarPos(val id: Int, val x: Float, val y: Float, val geo: Boolean)

fun radarLayout(st: Mesh.State, rotationDeg: Float): Pair<List<RadarPos>, Double> {
    val geoNodes = if (st.hasLoc) st.nodes.filter { it.hasLoc } else emptyList()
    val maxDist = geoNodes.maxOfOrNull { Geo.distance(st.myLat, st.myLon, it.lat, it.lon) } ?: 0.0
    val scale = Geo.escala(maxDist)
    val out = mutableListOf<RadarPos>()
    for (n in geoNodes) {
        val d = Geo.distance(st.myLat, st.myLon, n.lat, n.lon)
        val a = Math.toRadians(Geo.bearing(st.myLat, st.myLon, n.lat, n.lon) - rotationDeg)
        val r = (d / scale).toFloat().coerceAtMost(1f)
        out += RadarPos(n.id, (r * sin(a)).toFloat(), (-r * cos(a)).toFloat(), true)
    }
    // Sin GPS: se ubican en anillos según los saltos (1 salto = anillo interior)
    val sinGeo = st.nodes.filter { n -> out.none { it.id == n.id } }.groupBy { it.hops.coerceIn(1, 4) }
    for ((hops, grupo) in sinGeo) {
        val r = 0.22f * hops
        grupo.forEachIndexed { i, n ->
            val a = 2 * PI * (i + 0.5) / grupo.size + hops * 0.7
            out += RadarPos(n.id, (r * sin(a)).toFloat(), (-r * cos(a)).toFloat(), false)
        }
    }
    return out to scale
}

@Composable
fun Radar(
    st: Mesh.State,
    rotationDeg: Float,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    // El barrido se lee solo al dibujar: así la animación redibuja el radar sin recomponer la pantalla
    val sweepState = rememberInfiniteTransition(label = "barrido").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "ang",
    )
    val (pos, scale) = remember(st.nodes, st.myLat, st.myLon, rotationDeg) { radarLayout(st, rotationDeg) }
    val anyGeo = pos.any { it.geo }

    Canvas(
        modifier.pointerInput(pos) {
            detectTapGestures(
                onTap = { tap ->
                    val radius = min(size.width, size.height) / 2f * 0.92f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val hit = pos.minByOrNull { p ->
                        (Offset(c.x + p.x * radius, c.y + p.y * radius) - tap).getDistance()
                    }?.takeIf { p ->
                        (Offset(c.x + p.x * radius, c.y + p.y * radius) - tap).getDistance() < 48.dp2px(density)
                    }
                    onSelect(hit?.id)
                },
                onLongPress = { onLongPress() },
            )
        }
    ) {
        val sweep = sweepState.value
        val radius = min(size.width, size.height) / 2f * 0.92f
        val c = center
        fun at(p: RadarPos) = Offset(c.x + p.x * radius, c.y + p.y * radius)

        drawCircle(ColFondoRadar, radius)
        for (k in 1..4) drawCircle(ColAnillo, radius * k / 4f, style = Stroke(2f))
        drawLine(ColAnillo, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.5f)
        drawLine(ColAnillo, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.5f)

        // barrido
        val a = Math.toRadians(sweep.toDouble())
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, ColDirecto.copy(alpha = 0.55f)), c,
                Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat())),
            c, Offset(c.x + radius * sin(a).toFloat(), c.y - radius * cos(a).toFloat()), 4f,
        )

        val etiqueta = TextStyle(color = Color(0xFF7D8B97), fontSize = 11.sp)
        if (anyGeo) {
            for (k in 1..4) {
                drawText(measurer, Geo.fmt(scale * k / 4), Offset(c.x + 4f, c.y - radius * k / 4f + 2f), etiqueta)
            }
        } else {
            for (k in 1..3) drawText(measurer, "$k salto${if (k > 1) "s" else ""}",
                Offset(c.x + 4f, c.y - radius * 0.22f * k + 2f), etiqueta)
        }
        // Norte
        val na = Math.toRadians(-rotationDeg.toDouble())
        val np = Offset(c.x + (radius + 2f) * sin(na).toFloat(), c.y - (radius + 2f) * cos(na).toFloat())
        val nl = measurer.measure("N", TextStyle(color = Color(0xFFD6333A), fontSize = 14.sp, fontWeight = FontWeight.Bold))
        drawText(nl, topLeft = Offset(np.x - nl.size.width / 2f, np.y - nl.size.height / 2f))

        // Enlaces (quién está conectado con quién): se ve el "puente"
        val byId = pos.associateBy { it.id }
        val dibujados = mutableSetOf<Pair<Int, Int>>()
        for (n in st.nodes) {
            val pa = byId[n.id] ?: continue
            for (nb in n.neighbors) {
                val key = minOf(n.id, nb) to maxOf(n.id, nb)
                if (!dibujados.add(key)) continue
                val pb = if (nb == st.myId) c else byId[nb]?.let { at(it) } ?: continue
                drawLine(Color(0xFF55707F), at(pa), pb, 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            }
            if (n.direct && (n.id to st.myId) !in dibujados && (st.myId to n.id) !in dibujados) {
                drawLine(Color(0xFF55707F), at(pa), c, 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            }
        }

        // Yo
        drawCircle(Color.White, 11f, c)
        drawCircle(Color(0xFF3B82F6), 8f, c)

        // Nodos
        val nombre = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val ocupados = mutableListOf<androidx.compose.ui.geometry.Rect>()
        for (n in st.nodes) {
            val p = byId[n.id] ?: continue
            val o = at(p)
            val sos = n.sos || st.sos.any { it.id == n.id && it.active }
            val col = if (sos) Color(0xFFD6333A) else if (n.direct) ColDirecto else ColRelevo
            if (sos) drawCircle(Color(0xFFD6333A).copy(alpha = 0.35f), 30f + 10f * sin(Math.toRadians(sweep * 4.0)).toFloat(), o)
            if (n.id == selected) drawCircle(Color.White, 22f, o, style = Stroke(3f))
            if (p.geo) drawCircle(col, 14f, o) else {
                drawCircle(col, 14f, o, style = Stroke(5f))
            }
            // Etiqueta: debajo, o arriba / a los lados si choca con otra; siempre dentro del radar
            val layout = measurer.measure((if (sos) "🚨 " else if (n.quiet) "💤 " else "") + n.name.take(14), nombre)
            val w = layout.size.width.toFloat(); val h = layout.size.height.toFloat()
            val candidatos = listOf(
                Offset(o.x - w / 2, o.y + 18f), Offset(o.x - w / 2, o.y - 18f - h),
                Offset(o.x + 20f, o.y - h / 2), Offset(o.x - 20f - w, o.y - h / 2),
            ).map { Offset(it.x.coerceIn(0f, size.width - w), it.y.coerceIn(0f, size.height - h)) }
            val pos = candidatos.firstOrNull { c0 ->
                val r = androidx.compose.ui.geometry.Rect(c0, androidx.compose.ui.geometry.Size(w, h))
                ocupados.none { it.overlaps(r) }
            } ?: candidatos.first()
            ocupados += androidx.compose.ui.geometry.Rect(pos, androidx.compose.ui.geometry.Size(w, h))
            drawRoundRect(Color(0xAA0E1A17), pos - Offset(4f, 1f), androidx.compose.ui.geometry.Size(w + 8f, h + 2f),
                androidx.compose.ui.geometry.CornerRadius(6f))
            drawText(layout, topLeft = pos)
        }
    }
}

private fun Int.dp2px(density: Float) = this * density
