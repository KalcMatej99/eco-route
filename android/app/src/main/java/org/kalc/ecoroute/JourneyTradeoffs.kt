package org.kalc.ecoroute

import kotlin.math.abs
import kotlin.math.roundToInt

/** A point always retains the actual route and speed plan for every journey section. */
data class LegChoice(val route: RouteReport, val plan: Plan, val key: String)

data class JourneyChoice(val legs: List<LegChoice>) {
    val id: String = legs.joinToString("|") { "${it.route.id}:${it.key}" }
    val minutes: Double = legs.sumOf { it.plan.minutes }
    val liters: Double = legs.sumOf { it.plan.liters }
    val distanceKm: Double = legs.sumOf { it.route.distanceKm }
}

data class JourneyTradeoffs(val baseline: JourneyChoice, val choices: List<JourneyChoice>) {
    fun extraMinutes(choice: JourneyChoice) = (choice.minutes - baseline.minutes).coerceAtLeast(0.0)
    fun extraPercent(choice: JourneyChoice) = 100 * extraMinutes(choice) / baseline.minutes
    fun litersSaved(choice: JourneyChoice) = baseline.liters - choice.liters
    val mostFuelSaved: JourneyChoice get() = choices.minWith(compareBy<JourneyChoice> { it.liters }.thenBy { it.minutes })

    // Furthest above the line joining the two extremes, after normalizing both axes.
    // A 0.10 margin avoids claiming a meaningful knee on near-linear/two-point curves.
    // This is a suggestion among sampled plans, not a valuation of the user's time.
    val suggestedBalance: JourneyChoice? get() {
        val end = mostFuelSaved
        val timeRange = extraMinutes(end)
        val fuelRange = litersSaved(end)
        if (timeRange <= 1e-7 || fuelRange <= 1e-7) return null
        fun score(choice: JourneyChoice) = litersSaved(choice) / fuelRange - extraMinutes(choice) / timeRange
        return choices.filter { it.id != baseline.id && it.id != end.id }
            .maxByOrNull(::score)?.takeIf { score(it) >= .10 }
    }
    val initialChoice: JourneyChoice get() = suggestedBalance ?: choices.filter { extraPercent(it) <= 10.0 + 1e-6 }
        .minWithOrNull(compareBy<JourneyChoice> { it.liters }.thenBy { it.minutes }) ?: baseline

    fun totals(choice: JourneyChoice, price: Double?): TripTotals = TripTotals(
        liters = choice.liters, minutes = choice.minutes, distanceKm = choice.distanceKm,
        consumptionLPer100Km = if (choice.distanceKm > 0) choice.liters / choice.distanceKm * 100 else 0.0,
        eligible = true, fuelCostEur = price?.times(choice.liters),
        addedMinutes = (choice.minutes - baseline.minutes).coerceAtLeast(0.0),
        baselineLiters = baseline.liters, litersSaved = litersSaved(choice),
        fuelCostSavedEur = price?.times(litersSaved(choice)),
    )

    companion object {
        // Bound multi-stop combinations while retaining real plans at both ends of the curve.
        internal const val MAX_POINTS = 60
        private const val EPSILON = 1e-7

        fun build(journey: Journey, checkpoint: () -> Unit = {}): JourneyTradeoffs {
            require(journey.legs.isNotEmpty()) { "No journey sections were returned." }
            val baselineLegs = journey.legs.map { leg ->
                require(leg.routes.isNotEmpty()) { "No route was returned for a journey section." }
                leg.routes.map { LegChoice(it, it.baseline, "baseline") }
                    .minWith(compareBy<LegChoice> { it.plan.minutes }.thenBy { it.plan.liters }.thenBy { it.route.id })
            }
            val baseline = JourneyChoice(baselineLegs)
            require(baseline.minutes.isFinite() && baseline.minutes > 0 && baseline.liters.isFinite())
            var frontier = listOf(JourneyChoice(emptyList()))
            journey.legs.forEachIndexed { index, leg ->
                checkpoint()
                val candidates = leg.routes.flatMap { route ->
                    listOf(LegChoice(route, route.baseline, "baseline")) +
                        route.plans.toSortedMap().map { (key, plan) -> LegChoice(route, plan, key.toString()) }
                }.filter { it.plan.minutes.isFinite() && it.plan.minutes > 0 && it.plan.liters.isFinite() && it.plan.liters >= 0 }
                require(candidates.isNotEmpty()) { "No usable speed plans were returned." }
                val legFrontier = prune(candidates.map { JourneyChoice(listOf(it)) })
                val combined = frontier.flatMap { previous ->
                    checkpoint()
                    legFrontier.map { next -> JourneyChoice(previous.legs + next.legs) }
                }
                frontier = sample(prune(combined))
                // Keep the common fastest baseline even if another equal-time plan uses less fuel.
                val partialBaseline = JourneyChoice(baselineLegs.take(index + 1))
                if (frontier.none { it.id == partialBaseline.id }) frontier = frontier + partialBaseline
            }
            val choices = sample(prune(frontier)).filter {
                it.minutes >= baseline.minutes - EPSILON && it.liters <= baseline.liters + EPSILON
            }.filterNot { abs(it.minutes - baseline.minutes) < EPSILON && abs(it.liters - baseline.liters) < EPSILON }
            return JourneyTradeoffs(baseline, (listOf(baseline) + choices)
                .sortedWith(compareBy<JourneyChoice> { it.minutes }.thenByDescending { it.liters }))
        }

        private fun prune(points: List<JourneyChoice>): List<JourneyChoice> {
            var bestFuel = Double.POSITIVE_INFINITY
            return points.sortedWith(compareBy<JourneyChoice> { it.minutes }.thenBy { it.liters }.thenBy { it.id })
                .filter { point -> (point.liters < bestFuel - EPSILON).also { if (it) bestFuel = point.liters } }
        }

        private fun sample(points: List<JourneyChoice>): List<JourneyChoice> = if (points.size <= MAX_POINTS - 1) points
            else (0 until MAX_POINTS - 1).map { points[(it.toDouble() * points.lastIndex / (MAX_POINTS - 2)).roundToInt()] }
    }
}
