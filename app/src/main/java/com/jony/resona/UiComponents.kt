package com.jony.resona

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Color del espectro para una posicion 0..1 (0 = graves, calido; 1 = agudos, frio). */
fun spectrumColor(t: Float): Color {
    val stops = listOf(ResonaPalette.Low, ResonaPalette.LowMid, ResonaPalette.Mid, ResonaPalette.High)
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

/** Panel con titulo, subtitulo opcional y un control opcional a la derecha. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    corner: Dp = 18.dp,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (trailing != null) trailing()
            }
            content()
        }
    }
}

/** Boton de accion con esquinas redondeadas: relleno para la accion principal, con borde para las demas. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val pad = PaddingValues(horizontal = 8.dp)
    if (filled) {
        Button(onClick = onClick, modifier = modifier, shape = shape, contentPadding = pad) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            contentPadding = pad,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Selector de una opcion entre varias, en una fila de botones del mismo ancho. */
@Composable
fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            ActionButton(
                text = label,
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                filled = i == selected
            )
        }
    }
}

/** Titulo con descripcion y un interruptor a la derecha. */
@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun StepButton(sign: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Text(sign, style = MaterialTheme.typography.titleMedium) }
}

/** Fila con boton -, slider con pasos, boton + y valor. */
@Composable
fun StepRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    snapStep: Float,   // paso del slider al arrastrar
    fineStep: Float,   // paso de los botones - y +
    format: String,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    onChange: (Float) -> Unit
) {
    fun fine(delta: Float): Float =
        (((value + delta) / fineStep).roundToInt() * fineStep).coerceIn(min, max)

    val steps = ((max - min) / snapStep).roundToInt() - 1

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(
                label,
                modifier = Modifier.width(56.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StepButton("\u2212", enabled) { onChange(fine(-fineStep)) }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { onChange((it / fineStep).roundToInt() * fineStep) },
            valueRange = min..max,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        StepButton("+", enabled) { onChange(fine(fineStep)) }
        Text(
            format.format(value),
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Curva del ecualizador: una linea que une las 10 bandas, coloreada con el espectro. */
@Composable
fun EqCurve(gains: List<Float>, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val stops = listOf(ResonaPalette.Low, ResonaPalette.LowMid, ResonaPalette.Mid, ResonaPalette.High)

    Canvas(modifier = modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        val zeroY = h / 2f
        val pad = 8.dp.toPx()
        val n = gains.size
        if (n < 2) return@Canvas

        val pts = List(n) { i ->
            Offset(w * (i + 0.5f) / n, zeroY - (gains[i] / 15f) * (zeroY - pad))
        }

        drawLine(
            color = grid,
            start = Offset(0f, zeroY),
            end = Offset(w, zeroY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))
        )

        fun curve(p: Path) {
            p.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until n) {
                val a = pts[i - 1]
                val b = pts[i]
                val cx = (a.x + b.x) / 2f
                p.cubicTo(cx, a.y, cx, b.y, b.x, b.y)
            }
        }

        val line = Path().also { curve(it) }
        val area = Path().also {
            curve(it)
            it.lineTo(pts.last().x, zeroY)
            it.lineTo(pts.first().x, zeroY)
            it.close()
        }

        drawPath(area, brush = Brush.horizontalGradient(stops.map { it.copy(alpha = 0.18f) }))
        drawPath(
            line,
            brush = Brush.horizontalGradient(stops),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        pts.forEachIndexed { i, pt ->
            drawCircle(color = spectrumColor(i / (n - 1f)), radius = 3.5.dp.toPx(), center = pt)
        }
    }
}
