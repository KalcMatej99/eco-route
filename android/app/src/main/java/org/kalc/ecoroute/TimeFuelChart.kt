package org.kalc.ecoroute

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private fun Double.chartNumber(digits: Int = 1) = String.format(Locale.UK, "%.${digits}f", this)

internal fun chartMaximum(value: Double, minimum: Double): Double {
    val raw = value.coerceAtLeast(minimum)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val unit = when {
        raw / magnitude <= 2 -> magnitude / 5
        raw / magnitude <= 5 -> magnitude / 2
        else -> magnitude
    }
    return ceil(raw / unit) * unit
}

internal fun arrivalDescription(now: ZonedDateTime, minutes: Double): String {
    val arrival = now.plusSeconds((minutes * 60).toLong())
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), arrival.toLocalDate())
    val day = when (days) { 0L -> ""; 1L -> " tomorrow"; else -> " (+$days days)" }
    return "Leaving now: arrive ${arrival.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}$day"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimeFuelChart(tradeoffs: JourneyTradeoffs, selected: JourneyChoice, price: Double?, onSelect: (String) -> Unit) {
    val choices = tradeoffs.choices
    var showEuros by rememberSaveable { mutableStateOf(false) }
    val validPrice = price?.takeIf { it.isFinite() && it > 0 }
    val euros = showEuros && validPrice != null
    val multiplier = if (euros) validPrice else 1.0
    val balance = remember(tradeoffs) { tradeoffs.suggestedBalance }
    val mostSaved = remember(tradeoffs) { tradeoffs.mostFuelSaved }
    val now by produceState(ZonedDateTime.now()) {
        while (true) { value = ZonedDateTime.now(); delay(30_000) }
    }
    val index = choices.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
    val primary = MaterialTheme.colorScheme.primary
    val balanceColor = MaterialTheme.colorScheme.tertiary
    val endpointColor = MaterialTheme.colorScheme.secondary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surface
    val xMax = remember(tradeoffs) { chartMaximum(choices.maxOf(tradeoffs::extraMinutes), .1) }
    val yMax = remember(tradeoffs, multiplier) { chartMaximum(choices.maxOf(tradeoffs::litersSaved) * multiplier, .1) }
    fun saving(choice: JourneyChoice) = tradeoffs.litersSaved(choice).coerceAtLeast(0.0) * multiplier
    fun xLabel(value: Double) = value.chartNumber(if (value % 1.0 == 0.0) 0 else if (xMax < 1) 2 else 1)
    val density = LocalDensity.current
    val axisPaint = remember(secondary, density) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary.toArgb(); textSize = with(density) { 12.sp.toPx() }
    } }
    val leftPadding = with(density) { maxOf(54.dp.toPx(), axisPaint.measureText(yMax.chartNumber(2)) + 10.dp.toPx()) }
    val rightPadding = with(density) { maxOf(18.dp.toPx(), axisPaint.measureText(xLabel(xMax)) / 2 + 3.dp.toPx()) }
    val extra = tradeoffs.extraMinutes(selected)
    val saved = tradeoffs.litersSaved(selected).coerceAtLeast(0.0)
    val description = "Point ${index + 1} of ${choices.size}, ${extra.chartNumber()} minutes later, " +
        if (euros) "${(saved * multiplier).chartNumber(2)} euros saved" else "${saved.chartNumber(2)} litres saved"

    Column(Modifier.fillMaxWidth().testTag("time-fuel-chart"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your time, your fuel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Tap a dot to choose the route and speeds for your whole journey.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !euros, onClick = { showEuros = false }, label = { Text("Litres (L)") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("graph-unit-litres"))
            FilterChip(selected = euros, onClick = { showEuros = true }, enabled = validPrice != null,
                label = { Text("Euros (€)") }, modifier = Modifier.heightIn(min = 48.dp).testTag("graph-unit-euros"))
        }
        if (validPrice == null) Text("Euro savings need a current fuel price.", style = MaterialTheme.typography.bodySmall)
        Text(if (euros) "Estimated fuel cost saved (€)" else "Estimated fuel saved (L)", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(224.dp).testTag("time-fuel-plot")
            .semantics {
                contentDescription = "Time and fuel savings graph"
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..choices.lastIndex.toFloat(), (choices.size - 2).coerceAtLeast(0))
                setProgress { value -> onSelect(choices[value.roundToInt().coerceIn(choices.indices)].id); true }
            }
            .pointerInput(tradeoffs, multiplier, xMax, yMax, leftPadding, rightPadding) {
                detectTapGestures { tap ->
                    val left = leftPadding; val right = size.width - rightPadding
                    val top = 16.dp.toPx(); val bottom = size.height - 32.dp.toPx()
                    if (tap.x >= left - 16.dp.toPx() && tap.x <= right + 16.dp.toPx() && tap.y in 0f..bottom + 16.dp.toPx()) {
                        val nearest = choices.minBy { choice ->
                            val x = left + (tradeoffs.extraMinutes(choice) / xMax).toFloat() * (right - left)
                            val y = bottom - (saving(choice) / yMax).toFloat() * (bottom - top)
                            (tap - Offset(x, y)).getDistanceSquared()
                        }
                        onSelect(nearest.id)
                    }
                }
            }) {
            val left = leftPadding; val right = size.width - rightPadding
            val top = 16.dp.toPx(); val bottom = size.height - 32.dp.toPx()
            val paint = axisPaint
            repeat(3) { step ->
                val fraction = step / 2f
                val x = left + fraction * (right - left); val y = bottom - fraction * (bottom - top)
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
                drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText((yMax * fraction).chartNumber(2), left - 8.dp.toPx(), y + paint.textSize / 3, paint)
                paint.textAlign = Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(xLabel(xMax * fraction), x, bottom + 24.dp.toPx(), paint)
            }
            fun position(choice: JourneyChoice) = Offset(
                left + (tradeoffs.extraMinutes(choice) / xMax).toFloat() * (right - left),
                bottom - (saving(choice) / yMax).toFloat() * (bottom - top),
            )
            // Dots are the only selectable plans; connecting segments are a visual guide.
            val line = Path()
            choices.forEachIndexed { i, choice -> val p = position(choice); if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y) }
            drawPath(line, primary.copy(alpha = .35f), style = Stroke(2.dp.toPx()))
            choices.forEach { choice ->
                val p = position(choice)
                drawCircle(background, 6.dp.toPx(), p)
                val highlight = choice.id == tradeoffs.baseline.id || choice.id == mostSaved.id || choice.id == balance?.id
                val color = if (choice.id == balance?.id) balanceColor else if (highlight) endpointColor else primary
                drawCircle(color, (if (highlight) 6 else 4).dp.toPx(), p)
            }
            val p = position(selected)
            drawCircle(primary.copy(alpha = .18f), 13.dp.toPx(), p)
            drawCircle(background, 8.dp.toPx(), p)
            drawCircle(primary, 7.dp.toPx(), p)
        }
        Text("Extra travel time (minutes)", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelLarge)
        Text("Compared with the fastest journey: ${tradeoffs.baseline.minutes.chartNumber()} min · ${tradeoffs.baseline.liters.chartNumber(2)} L.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val highlights = listOfNotNull(
                Triple("Fastest", tradeoffs.baseline, endpointColor),
                balance?.let { Triple("Suggested balance", it, balanceColor) },
                mostSaved.takeIf { it.id != tradeoffs.baseline.id }?.let { Triple("Most fuel saved", it, endpointColor) },
            )
            highlights.forEach { (label, choice, color) ->
                FilterChip(selected = selected.id == choice.id, onClick = { onSelect(choice.id) },
                    label = { Text(label) }, leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(when (label) {
                        "Fastest" -> "graph-fastest"; "Suggested balance" -> "graph-balance"; else -> "graph-most-saved"
                    }))
            }
        }
        if (balance != null) Text("Suggested balance: near the bend where extra time starts buying smaller fuel savings.", style = MaterialTheme.typography.bodySmall)
        if (choices.size > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { onSelect(choices[index - 1].id) }, enabled = index > 0,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tradeoff-previous")) { Text("Earlier") }
                Text("${index + 1} / ${choices.size}", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { onSelect(choices[index + 1].id) }, enabled = index < choices.lastIndex,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tradeoff-next")) { Text("Less fuel") }
            }
        } else {
            Text("No lower-fuel alternative was found for this journey.", style = MaterialTheme.typography.bodyMedium)
        }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp).testTag("tradeoff-selection").semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (selected.id == tradeoffs.baseline.id) "Fastest journey" else "Arrive ${extra.chartNumber()} minutes later",
                    fontWeight = FontWeight.Bold)
                Text("Estimated saving: ${saved.chartNumber(2)} L${validPrice?.let { " · €${(saved * it).chartNumber(2)}" }.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Text(arrivalDescription(now, selected.minutes), style = MaterialTheme.typography.bodyMedium)
                Text("${selected.minutes.chartNumber()} min · ${selected.liters.chartNumber(2)} L · ${selected.distanceKm.chartNumber()} km", style = MaterialTheme.typography.bodyMedium)
                Text("${tradeoffs.extraPercent(selected).chartNumber()}% extra time", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (validPrice != null) Text("Fuel cost uses the current Slovenia reference price; excludes tolls and parking.", style = MaterialTheme.typography.bodySmall)
        Text("Modeled estimates. Only the dots are selectable plans.", style = MaterialTheme.typography.bodySmall)
    }
}
