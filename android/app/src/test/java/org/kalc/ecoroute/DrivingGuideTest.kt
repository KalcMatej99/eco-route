package org.kalc.ecoroute

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class DrivingGuideTest {
    private fun route(limit: Double? = 90.0, points: List<GeoPoint> = listOf(GeoPoint(46.0, 14.0), GeoPoint(46.0, 14.02))): RouteReport {
        val baseline = Plan(2.0, .1, emptyList(), listOf(80.0, 100.0), "{}")
        return RouteReport("r", "Route", 2.0, 2.0, 100.0, 0.0, 0.0, points, baseline, mapOf(10 to baseline),
            listOf(Segment(0.0, 2000.0, "A1", "motorway", limit, 0.0, 0.0, "{}")), "{}")
    }
    private fun fix(lon: Double = 14.005, lat: Double = 46.0, accuracy: Float = 5f, time: Long = 1000, speed: Double? = 80.0, bearing: Double? = 90.0) =
        DrivingFix(lat, lon, accuracy, time, speed, bearing)

    @Test fun theoreticalPlansCannotBecomeLiveDrivingGuidance() {
        val theoretical = route().copy(ignoreSpeedLimits = true)
        assertThrows(IllegalArgumentException::class.java) { DrivingGuide(theoretical,theoretical.baseline) }
    }

    @Test fun projectsBetweenVerticesAndInterpolatesInDistance() {
        val r = route(); val state = DrivingGuide(r, r.baseline).update(fix(), 1000)
        assertEquals(500.0, state.progressMeters!!, .1)
        assertEquals(sqrt(80.0 * 80 + (100.0 * 100 - 80.0 * 80) * .25), state.advisoryKmh!!, .01)
        assertEquals(1500.0, state.remainingMeters!!, .1)
    }
    @Test fun speedIsCappedAndMissingLimitsKeepModeledAdvice() {
        val r = route(70.0)
        assertEquals(70.0, DrivingGuide(r, r.baseline).update(fix(), 1000).advisoryKmh!!, .01)
        for (limit in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val unknown = route(limit)
            val state = DrivingGuide(unknown, unknown.baseline).update(fix(), 1000)
            assertEquals(sqrt(7300.0), state.advisoryKmh!!, .01)
            assertNull(state.mappedLimitKmh)
            assertFalse(state.mappedLimitUnlimited)
            assertTrue(state.message.contains("unknown"))
        }
    }
    @Test fun staleInaccurateAndOffRouteFixesSuppressAdvice() {
        val r = route(); val guide = DrivingGuide(r, r.baseline)
        assertNull(guide.update(fix(), 6001).advisoryKmh)
        assertNull(guide.update(fix(accuracy = 70f), 1000).advisoryKmh)
        assertNull(guide.update(fix(lat = 46.001), 1000).advisoryKmh)
        assertNull(guide.update(fix(time = 1001), 1000).advisoryKmh)
    }
    @Test fun oppositeHeadingDoesNotAttachToRoute() {
        val r = route()
        assertNull(DrivingGuide(r, r.baseline).update(fix(bearing = 270.0), 1000).advisoryKmh)
    }
    @Test fun arrivalNeedsTwoFreshSlowFixesAtTheDestination() {
        val r = route(); val guide = DrivingGuide(r, r.baseline)
        assertFalse(guide.update(fix(lon = 14.02, speed = 50.0), 1000).arrived)
        assertFalse(guide.update(fix(lon = 14.02, speed = 0.0, time = 2000), 2000).arrived)
        assertTrue(guide.update(fix(lon = 14.02, speed = 0.0, time = 3000), 3000).arrived)
    }
    @Test fun overlappingRouteIsAmbiguousWithoutPriorProgress() {
        val r = route(points = listOf(GeoPoint(46.0,14.0),GeoPoint(46.0,14.02),GeoPoint(46.0,14.0)))
        val state = DrivingGuide(r, r.baseline).update(fix(bearing = null), 1000)
        assertNull(state.advisoryKmh)
        assertTrue(state.message.contains("ambiguous"))
    }
    @Test fun headingDisambiguatesOppositeDirectionsAtARepeatedRoad() {
        val r = route(points = listOf(GeoPoint(46.0,14.0),GeoPoint(46.0,14.02),GeoPoint(46.0,14.0)))
        assertNotNull(DrivingGuide(r, r.baseline).update(fix(), 1000).advisoryKmh)
    }
    @Test fun impossibleJumpCannotSelectAnotherRoutePosition() {
        val r = route(); val guide = DrivingGuide(r, r.baseline)
        assertNotNull(guide.update(fix(), 1000).advisoryKmh)
        assertNull(guide.update(fix(lon = 14.015, time = 2000), 2000).advisoryKmh)
    }
    @Test fun repeatedFixCannotCountTwiceForArrival() {
        val r = route(); val guide = DrivingGuide(r,r.baseline)
        val endpoint = fix(lon = 14.02,speed = 0.0)
        assertFalse(guide.update(endpoint,1000).arrived)
        assertFalse(guide.update(endpoint,1000).arrived)
    }
    @Test fun malformedSpeedPlanIsRejected() {
        val r = route()
        assertThrows(IllegalArgumentException::class.java) { DrivingGuide(r, r.baseline.copy(speedKmh = listOf(90.0))) }
    }
    @Test fun lowerLimitAtBoundaryCapsGuidanceImmediately() {
        val r = route().let { it.copy(segments = listOf(
            Segment(0.0,1000.0,"A1","motorway",130.0,0.0,0.0,"{}"),
            Segment(1000.0,2000.0,"Exit","trunk",50.0,0.0,0.0,"{}")),
            baseline = it.baseline.copy(speedKmh = listOf(90.0,90.0,50.0))) }
        assertEquals(50.0, DrivingGuide(r,r.baseline).update(fix(lon = 14.01),1000).advisoryKmh!!,.01)
    }
    @Test fun missingBoundaryLimitDoesNotEraseKnownAdjacentCap() {
        for (limits in listOf(listOf(null, 50.0), listOf(50.0, null), listOf(null, null))) {
            val r = route().let { it.copy(segments = listOf(
                Segment(0.0,1000.0,"A1","motorway",limits[0],0.0,0.0,"{}"),
                Segment(1000.0,2000.0,"Exit","trunk",limits[1],0.0,0.0,"{}")),
                baseline = it.baseline.copy(speedKmh = listOf(90.0,90.0,90.0))) }
            val state = DrivingGuide(r,r.baseline).update(fix(lon = 14.01),1000)
            assertEquals(if (limits.all { it == null }) 90.0 else 50.0,state.advisoryKmh!!,.01)
        }
    }
    @Test fun explicitUnlimitedKeepsModelSpeedAndDistinctLabel() {
        val r = route(null).let { it.copy(segments = it.segments.map { s -> s.copy(limitUnlimited = true) }) }
        val state = DrivingGuide(r,r.baseline).update(fix(),1000)
        assertEquals(sqrt(7300.0),state.advisoryKmh!!,.01)
        assertTrue(state.mappedLimitUnlimited)
        assertNull(state.mappedLimitKmh)
    }

}
