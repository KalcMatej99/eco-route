package org.kalc.ecoroute

import kotlin.math.*

/** A device-local fix; monotonic time prevents clock changes from reviving old guidance. */
data class DrivingFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedMillis: Long,
    val speedKmh: Double? = null,
    val bearingDegrees: Double? = null,
)

data class DrivingState(
    val active: Boolean = false,
    val message: String = "Waiting for GPS…",
    val fix: DrivingFix? = null,
    val advisoryKmh: Double? = null,
    val mappedLimitKmh: Double? = null,
    val mappedLimitUnlimited: Boolean = false,
    val roadName: String = "",
    val basis: String = "",
    val progressMeters: Double? = null,
    val remainingMeters: Double? = null,
    val arrived: Boolean = false,
)

/** Projection on the retained road polyline, scaled to the model's section distances. */
internal class DrivingGuide(private val route: RouteReport, private val plan: Plan) {
    private val distances = MutableList(route.coordinates.size) { 0.0 }
    private var previousMeters: Double? = null
    private var previousTime: Long? = null
    private var arrivalCount = 0

    init {
        require(!route.ignoreSpeedLimits) { "Theoretical plans cannot provide live driving guidance." }
        require(route.coordinates.size >= 2 && route.segments.isNotEmpty())
        require(plan.speedKmh.size == route.segments.size + 1 && plan.speedKmh.all { it.isFinite() && it >= 0 })
        require(route.coordinates.all { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
        route.coordinates.indices.drop(1).forEach { i ->
            distances[i] = distances[i - 1] + RoadLoadModel.distance(route.coordinates[i - 1], route.coordinates[i])
        }
        require(distances.last() > 0 && route.distanceKm > 0)
        require(route.segments.withIndex().all { (i, s) ->
            s.startM.isFinite() && s.endM.isFinite() && s.endM > s.startM &&
                abs(s.startM - if (i == 0) 0.0 else route.segments[i - 1].endM) < 1.0
        })
        require(abs(route.segments.last().endM - route.distanceKm * 1000) < 1.0)
    }

    fun update(fix: DrivingFix, now: Long): DrivingState {
        fun unavailable(message: String) = DrivingState(active = true, message = message, fix = fix)
        if (!usableDrivingFix(fix, now)) {
            arrivalCount = 0
            return DrivingState(active = true, message = if (now - fix.elapsedMillis > MAX_AGE_MS) "GPS signal lost · waiting for a fresh position" else "GPS accuracy too low · waiting for a precise position")
        }
        if (previousTime?.let { fix.elapsedMillis <= it } == true) {
            arrivalCount = 0
            return unavailable("Waiting for a newer GPS position")
        }
        val point = GeoPoint(fix.latitude, fix.longitude)
        val scale = route.distanceKm * 1000 / distances.last()
        data class Match(val meters: Double, val distance: Double, val headingDifference: Double)
        val matches = route.coordinates.zipWithNext().mapIndexed { i, (a, b) ->
            val cosLat = cos(Math.toRadians(point.lat))
            val ax = Math.toRadians(a.lon - point.lon) * cosLat * EARTH_RADIUS
            val ay = Math.toRadians(a.lat - point.lat) * EARTH_RADIUS
            val bx = Math.toRadians(b.lon - point.lon) * cosLat * EARTH_RADIUS
            val by = Math.toRadians(b.lat - point.lat) * EARTH_RADIUS
            val dx = bx - ax; val dy = by - ay
            val fraction = (-(ax * dx + ay * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            val bearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            val headingDifference = fix.bearingDegrees?.let { abs((bearing - it + 540) % 360 - 180) } ?: 0.0
            Match((distances[i] + fraction * (distances[i + 1] - distances[i])) * scale,
                hypot(ax + dx * fraction, ay + dy * fraction), headingDifference)
        }.filter { it.distance <= MAX_ROUTE_DISTANCE_M }
        if (matches.isEmpty()) {
            arrivalCount = 0
            return unavailable("Off route · return to your selected route or stop to replan")
        }
        val headingMatches = matches.filter { (fix.speedKmh ?: 0.0) < 10 || fix.bearingDegrees == null || it.headingDifference < 75 }
        val prior = previousMeters
        val elapsedSeconds = previousTime?.let { ((fix.elapsedMillis - it) / 1000.0).coerceAtLeast(0.0) }
        val continuous = headingMatches.filter {
            prior == null || elapsedSeconds == null || elapsedSeconds > 15 ||
                it.meters in (prior - 45)..(prior + max(100.0, elapsedSeconds * 55 + fix.accuracyMeters))
        }
        val best = continuous.minByOrNull { it.distance } ?: return unavailable("Route position uncertain · waiting for GPS")
        if (continuous.any { abs(it.meters - best.meters) > 100 && it.distance <= best.distance + max(8.0, fix.accuracyMeters.toDouble()) }) {
            arrivalCount = 0
            return unavailable("Route position ambiguous · waiting for GPS")
        }
        previousMeters = best.meters
        previousTime = fix.elapsedMillis
        val meters = best.meters.coerceIn(0.0, route.distanceKm * 1000)
        val segmentIndex = route.segments.indexOfFirst { meters < it.endM }.let { if (it < 0) route.segments.lastIndex else it }
        val segment = route.segments[segmentIndex]
        val fraction = ((meters - segment.startM) / (segment.endM - segment.startM)).coerceIn(0.0, 1.0)
        val v0 = plan.speedKmh[segmentIndex]; val v1 = plan.speedKmh[segmentIndex + 1]
        val modeled = sqrt(max(0.0, v0 * v0 + (v1 * v1 - v0 * v0) * fraction))
        // At a section boundary, never show a speed above either adjacent mapped limit.
        val limits = route.segments.filter { abs(it.startM - meters) < 1 || abs(it.endM - meters) < 1 } + segment
        // Missing limits add no constraint; retain any known adjacent cap.
        val limit = limits.mapNotNull { it.limitKmh?.takeIf { value -> value.isFinite() && value > 0 } }.minOrNull()
        val unlimited = limit == null && segment.limitUnlimited
        val remaining = route.distanceKm * 1000 - meters
        arrivalCount = if (remaining <= 25 && (fix.speedKmh ?: Double.POSITIVE_INFINITY) < 10 &&
            RoadLoadModel.distance(point, route.coordinates.last()) <= 30) arrivalCount + 1 else 0
        val arrived = arrivalCount >= 2
        return DrivingState(
            active = true, fix = fix, message = when {
                arrived -> "Section complete"
                unlimited -> "Mapped limit unlimited · follow road signs"
                limit == null -> "Mapped limit unknown · follow road signs"
                else -> "Live route position"
            },
            advisoryKmh = if (arrived) null else min(modeled, limit ?: Double.POSITIVE_INFINITY),
            mappedLimitKmh = limit, mappedLimitUnlimited = unlimited, roadName = segment.name.ifBlank { "Unnamed road" },
            basis = if (segment.roadClass == "motorway") "Modeled speed plan" else "Static road estimate",
            progressMeters = meters, remainingMeters = remaining, arrived = arrived,
        )
    }

    companion object {
        const val MAX_AGE_MS = 5_000L
        const val MAX_ACCURACY_M = 35f
        const val MAX_ROUTE_DISTANCE_M = 30.0
        private const val EARTH_RADIUS = 6371000.0
    }
}

internal fun usableDrivingFix(fix: DrivingFix, now: Long): Boolean =
    fix.latitude.isFinite() && fix.latitude in -90.0..90.0 && fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
        fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..DrivingGuide.MAX_ACCURACY_M &&
        now - fix.elapsedMillis in 0..DrivingGuide.MAX_AGE_MS
