package org.kalc.ecoroute

import org.junit.Assert.*
import org.junit.Test

class JourneyTradeoffsTest {
    private fun plan(minutes: Double, liters: Double, speed: Double = 100.0) = Plan(minutes, liters, emptyList(), listOf(speed, speed), "{}")
    private fun route(id: String, minutes: Double, liters: Double, plans: Map<Int, Plan> = emptyMap()) = RouteReport(
        id, id, 50.0, minutes, 100.0, 0.0, 0.0, emptyList(), plan(minutes, liters), plans, emptyList(), "{}")
    private fun leg(vararg routes: RouteReport) = LegReport("", emptyList(), routes.toList(), emptyMap(), routes.minOf { it.baseline.minutes },
        emptyList(), null, TimeAllowance(TimeAllowanceMode.PERCENT, emptyList()), "{}")
    private fun journey(vararg legs: LegReport) = Journey("multi_stop", legs.toList(), "{}")

    @Test fun pointsUseActualTimesAndOneBaselineAcrossRoutesAndSpeedPlans() {
        val fast = route("fast", 40.0, 4.0, mapOf(10 to plan(43.0, 3.4, 85.0), 15 to plan(43.0, 3.4, 85.0)))
        val local = route("local", 50.0, 3.0)
        val dominated = route("wasteful", 55.0, 4.5)
        val t = JourneyTradeoffs.build(journey(leg(fast, local, dominated)))
        assertEquals(3, t.choices.size)
        assertEquals(40.0, t.baseline.minutes, 0.0)
        val middle = t.choices[1]
        assertEquals(7.5, t.extraPercent(middle), 1e-8) // not the 10% budget label
        assertEquals(.6, t.litersSaved(middle), 1e-8)
        assertEquals(85.0, middle.legs.single().plan.speedKmh.first(), 0.0)
        assertEquals("local", t.choices.last().legs.single().route.id)
        assertEquals(25.0, t.extraPercent(t.choices.last()), 0.0)
        assertEquals(1.0, t.litersSaved(t.choices.last()), 0.0)
        assertEquals(middle, t.initialChoice)
        val totals = t.totals(middle, 2.0)
        assertEquals(6.8, totals.fuelCostEur!!, 1e-8)
        assertEquals(1.2, totals.fuelCostSavedEur!!, 1e-8)
    }

    @Test fun wholeJourneyCombinesDifferentPlansOnAsymmetricLegs() {
        val outbound = leg(route("out", 40.0, 4.0, mapOf(10 to plan(44.0, 3.0))))
        val home = leg(route("home", 20.0, 2.0, mapOf(10 to plan(22.0, 1.8))))
        val t = JourneyTradeoffs.build(journey(outbound, home))
        val mixed = t.choices.first { it.minutes == 64.0 }
        assertEquals(listOf("10", "baseline"), mixed.legs.map { it.key })
        assertEquals(100 * 4.0 / 60, t.extraPercent(mixed), 1e-8)
        assertEquals(1.0, t.litersSaved(mixed), 1e-8)
        assertEquals(6.0, t.baseline.liters, 0.0)
        assertEquals(4.8, t.choices.last().liters, 1e-8)
        assertEquals(10.0, t.extraPercent(t.choices.last()), 1e-8)
    }

    @Test fun singlePointHasFiniteAxesInputsAndNoInventedSaving() {
        val t = JourneyTradeoffs.build(journey(leg(route("only", 2.0, .1, mapOf(10 to plan(2.0, .1))))))
        assertEquals(1, t.choices.size)
        assertEquals(0.0, t.extraPercent(t.initialChoice), 0.0)
        assertEquals(0.0, t.litersSaved(t.initialChoice), 0.0)
    }

    @Test fun manyStopsAreBoundedAndRetainFastestAndLowestFuelPlans() {
        val legs = (0 until 10).map { i -> leg(route("r$i", 20.0, 3.0,
            (1..80).associateWith { step -> plan(20 + step * (1.0 + i / 10.0), 3.0 - step * .025) })) }
        var checkpoints = 0
        val t = JourneyTradeoffs.build(Journey("multi_stop", legs, "{}")) { checkpoints++ }
        assertTrue(t.choices.size <= JourneyTradeoffs.MAX_POINTS)
        assertEquals(200.0, t.choices.first().minutes, 0.0)
        assertEquals(10.0, t.choices.last().liters, 1e-8)
        assertTrue(t.choices.all { it.legs.size == 10 })
        assertTrue(t.choices.zipWithNext().all { (a,b) -> b.minutes >= a.minutes && b.liters < a.liters })
        assertTrue(checkpoints > 10)
    }

    @Test fun equallyFastRoutesChooseLowestFuelReferenceDeterministically() {
        val t = JourneyTradeoffs.build(journey(leg(route("b", 40.0, 5.0), route("a", 40.0, 4.0))))
        assertEquals("a", t.baseline.legs.single().route.id)
        assertEquals(1, t.choices.size)
    }
    @Test fun balanceFindsDiminishingReturnsAndIsIndependentOfJourneyScale() {
        fun graph(scale: Double) = JourneyTradeoffs.build(journey(leg(route("r", 40.0 * scale, 4.0 * scale,
            mapOf(1 to plan(42.0 * scale, 3.2 * scale), 2 to plan(50.0 * scale, 3.0 * scale))))))
        for (scale in listOf(.1, 1.0, 10.0)) {
            val t = graph(scale)
            assertEquals(2.0 * scale, t.extraMinutes(t.suggestedBalance!!), 1e-8)
            assertEquals(t.suggestedBalance, t.initialChoice)
            assertEquals(3.0 * scale, t.mostFuelSaved.liters, 1e-8)
        }
    }

    @Test fun straightTwoPointAndZeroTimeCurvesDoNotInventABalance() {
        val straight = JourneyTradeoffs.build(journey(leg(route("r", 40.0, 4.0,
            mapOf(1 to plan(45.0, 3.5), 2 to plan(50.0, 3.0))))))
        val two = JourneyTradeoffs.build(journey(leg(route("r", 40.0, 4.0, mapOf(1 to plan(50.0, 3.0))))))
        val vertical = JourneyTradeoffs.build(journey(leg(route("r", 40.0, 4.0, mapOf(1 to plan(40.0, 3.0))))))
        for (t in listOf(straight, two, vertical)) assertNull(t.suggestedBalance)
        assertEquals(vertical.mostFuelSaved, vertical.initialChoice)
    }

    @Test fun smallMinuteRangesRemainVisibleAndArrivalHandlesMidnight() {
        assertEquals(.4, chartMaximum(.36, .1), 1e-8)
        assertTrue(chartMaximum(0.0, .1) > 0)
        assertTrue(chartMaximum(170.0, .1) >= 170.0)
        val now = java.time.ZonedDateTime.parse("2026-10-08T23:50:00+02:00[Europe/Rome]")
        assertTrue(arrivalDescription(now, 20.0).endsWith(" tomorrow"))
        assertFalse(arrivalDescription(now, 5.0).contains("tomorrow"))
        assertTrue(arrivalDescription(now, 1500.0).endsWith("(+2 days)"))
    }

}
