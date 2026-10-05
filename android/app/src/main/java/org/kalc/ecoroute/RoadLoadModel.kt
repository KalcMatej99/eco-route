package org.kalc.ecoroute

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** road-load-v2, ported without changing the audited fuel equation or search bounds. */
internal object RoadLoadModel {
    const val VERSION = "road-load-v2"
    data class Vehicle(val mass: Double, val drag: Double, val rolling: Double,
        val efficiency: Double, val auxiliary: Double, val overhead: Double, val energy: Double,
        val density: Double = 1.225) {
        companion object {
            fun fromJson(v: JSONObject) = Vehicle(v.getDouble("mass_kg"), v.getDouble("drag_area_m2"),
                v.getDouble("rolling_resistance"), v.getDouble("tank_to_wheel_efficiency"),
                v.getDouble("auxiliary_kw"), v.getDouble("engine_overhead_l_h"),
                if (v.getString("fuel_type") == "diesel") 35.8 else 32.0)
        }
    }
    data class Segment(val length: Double, val h0: Double, val h1: Double, val cap: Double,
        val adjustable: Boolean, val json: JSONObject)
    data class Route(val id: String, val label: String, val segments: List<Segment>,
        val states: List<DoubleArray>, val caps: DoubleArray, val delay: Double,
        val coordinates: List<GeoPoint>, val metadata: JSONObject)
    data class Cost(val seconds: Double, val liters: Double)
    data class Plan(val seconds: Double, val liters: Double, val speeds: DoubleArray,
        var optimization: JSONObject? = null)
    data class Transition(val from: Int, val seconds: Double, val liters: Double)

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val q = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(b.lon - a.lon) / 2).pow(2)
        return 6371000 * 2 * atan2(sqrt(q.coerceIn(0.0, 1.0)), sqrt((1 - q).coerceIn(0.0, 1.0)))
    }

    fun decodePolyline(encoded: String): List<GeoPoint> {
        var i = 0; var lat = 0L; var lon = 0L
        fun delta(): Long {
            var value = 0L; var shift = 0
            while (true) {
                require(i < encoded.length && shift <= 60) { "Invalid road geometry." }
                val byte = encoded[i++].code - 63
                require(byte in 0..63) { "Invalid road geometry." }
                value = value or ((byte and 31).toLong() shl shift); shift += 5
                if (byte < 32) break
            }
            return if (value and 1L != 0L) (value shr 1).inv() else value shr 1
        }
        return buildList { while (i < encoded.length) { lat += delta(); lon += delta(); add(GeoPoint(lat / 1e6, lon / 1e6)) } }
    }

    private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        var low = 0; var high = xs.size
        while (low < high) { val mid = (low + high) / 2; if (xs[mid] <= x) low = mid + 1 else high = mid }
        val i = (low - 1).coerceIn(0, xs.size - 2)
        val fraction = ((x - xs[i]) / max(1e-9, xs[i + 1] - xs[i])).coerceIn(0.0, 1.0)
        return ys[i] + fraction * (ys[i + 1] - ys[i])
    }

    private fun structureHint(edge: JSONObject): Boolean {
        val names = edge.optJSONArray("names") ?: return false
        return (0 until names.length()).any {
            val n = names.getString(it).lowercase(java.util.Locale.ROOT).trim()
            listOf("viadukt ", "viaduct ", "viadotto ", "viaduc ").any(n::startsWith) &&
                n !in setOf("viaduct road", "viaduct street", "viaduct avenue")
        }
    }

    fun prepare(id: String, label: String, trip: JSONObject, trace: JSONObject, checkpoint: () -> Unit = {}): Route {
        val coords = decodePolyline(trace.getString("shape"))
        require(coords.size >= 2) { "Road geometry is missing." }
        val cumulative = DoubleArray(coords.size)
        for (i in 1 until coords.size) cumulative[i] = cumulative[i - 1] + distance(coords[i - 1], coords[i])
        val total = cumulative.last()
        val samples = trace.optJSONArray("elevation") ?: throw IllegalArgumentException("Elevation is missing.")
        require(samples.length() >= 2) { "Elevation is missing; a flat road cannot be assumed." }
        val heights = DoubleArray(samples.length()) { samples.getDouble(it) }
        require(heights.all { it.isFinite() && abs(it) < 32000 }) { "Road elevation is invalid." }
        val interval = trace.getDouble("elevation_interval")
        require(total > 0 && interval.isFinite() && interval > 0) { "Road elevation spacing is invalid." }
        val hx = DoubleArray(heights.size) { min(it * interval, total) }; hx[hx.lastIndex] = total
        if ((1 until hx.size).any { hx[it] <= hx[it - 1] }) for (i in hx.indices) hx[i] = i * total / (hx.size - 1)
        val edges = trace.getJSONArray("edges")
        val structures = mutableListOf<DoubleArray>(); var corrected = 0.0; var inferredMeters = 0.0
        for (i in 0 until edges.length()) {
            checkpoint()
            val e = edges.getJSONObject(i)
            val inferred = structureHint(e) && !e.optBoolean("bridge") && !e.optBoolean("tunnel")
            if (e.optBoolean("bridge") || e.optBoolean("tunnel") || inferred) {
                val a = cumulative[e.getInt("begin_shape_index")]; val b = cumulative[e.getInt("end_shape_index")]
                if (inferred) inferredMeters += b - a
                if (structures.isNotEmpty() && a <= structures.last()[1] + 1) structures.last()[1] = b
                else structures.add(doubleArrayOf(a, b))
            }
        }
        for ((a, b) in structures) {
            val ha = interpolate(hx, heights, a); val hb = interpolate(hx, heights, b)
            for (i in hx.indices) if (hx[i] in a..b) heights[i] = ha + (hb - ha) * (hx[i] - a) / max(1.0, b - a)
            corrected += b - a
        }
        val smooth = DoubleArray(heights.size) { i -> (max(0, i - 2)..min(heights.lastIndex, i + 2)).map { heights[it] }.average() }
        smooth[0] = heights[0]; smooth[smooth.lastIndex] = heights.last()
        val segments = mutableListOf<Segment>(); var knownMeters = 0.0; var delay = 0.0
        for (i in 0 until edges.length()) {
            checkpoint()
            val e = edges.getJSONObject(i)
            delay += e.optJSONObject("end_node")?.optDouble("transition_time", 0.0) ?: 0.0
            val start = cumulative[e.getInt("begin_shape_index")]; val end = cumulative[e.getInt("end_shape_index")]
            if (end - start < 0.01) continue
            val limit = e.optDouble("speed_limit", Double.NaN); val known = limit.isFinite() && limit > 0 && limit < 200
            val motorway = e.getString("road_class") == "motorway" && e.getString("use") == "road" && !e.optBoolean("roundabout")
            val routerSpeed = e.getDouble("speed")
            val capKmh = if (motorway && known) min(limit, 130.0) else min(min(routerSpeed, if (known) limit else routerSpeed), 130.0)
            require(capKmh.isFinite() && capKmh > 0) { "Road speed data is invalid." }
            val basis = if (motorway && known) "mapped_limit_motorway" else if (known) "router_static_speed_and_mapped_limit" else "router_static_speed_unknown_limit"
            val adjustable = motorway && known && capKmh >= 80
            if (known) knownMeters += end - start
            val count = ceil((end - start) / 250).toInt()
            val names = e.optJSONArray("names") ?: JSONArray()
            val name = (0 until names.length()).joinToString(" / ") { names.getString(it) }.ifBlank { "Local access road" }
            for (j in 0 until count) {
                val a = start + (end - start) * j / count; val b = start + (end - start) * (j + 1) / count
                val h0 = interpolate(hx, smooth, a); val h1 = interpolate(hx, smooth, b)
                val json = JSONObject().put("start_m", a).put("end_m", b).put("distance_m", b - a)
                    .put("height_start_m", h0).put("height_end_m", h1).put("optimize", adjustable)
                    .put("name", name).put("road_class", e.getString("road_class"))
                    .put("limit_kmh", if (known) limit else JSONObject.NULL).put("tunnel", e.optBoolean("tunnel"))
                    .put("structure_name_hint", structureHint(e)).put("provider_speed_kmh", routerSpeed)
                    .put("cruise_cap_kmh", capKmh).put("router_static_speed_kmh", routerSpeed)
                    .put("speed_envelope_kmh", capKmh).put("speed_envelope_basis", basis)
                    .put("speed_basis", if (motorway && known) "mapped_motorway_limit" else "provider_estimate")
                    .put("speed_envelope_detail", if (motorway && known) "Mapped motorway limit, bounded by the 130 km/h model maximum. Static routing speed is ETA context." else "Static routing speed bounded by the mapped limit where available.")
                    .put("speed_choice_detail", "Modeled speed may be below the envelope for fuel savings or acceleration/deceleration bounds.")
                segments.add(Segment(b - a, h0, h1, capKmh / 3.6, adjustable, json))
            }
        }
        require(segments.size >= 2 && segments.size <= 30000) { "No usable road sections were found." }
        val caps = DoubleArray(segments.size + 1)
        for (i in 1 until segments.size) caps[i] = min(segments[i - 1].cap, segments[i].cap)
        for (i in segments.indices) caps[i + 1] = min(caps[i + 1], sqrt(caps[i].pow(2) + 2 * .7 * segments[i].length))
        for (i in segments.indices.reversed()) caps[i] = min(caps[i], sqrt(caps[i + 1].pow(2) + 2 * segments[i].length))
        val states = caps.mapIndexed { i, cap ->
            val choices = mutableListOf(cap)
            if (i > 0 && i < segments.size && segments[i - 1].adjustable && segments[i].adjustable) {
                var speed = 80
                while (speed < (cap * 3.6).toInt()) { choices.add(speed / 3.6); speed += 5 }
            }
            choices.distinct().sorted().toDoubleArray()
        }
        val metadata = JSONObject().put("id", id).put("label", label).put("distance_km", total / 1000)
            .put("provider_minutes", trip.getJSONObject("summary").getDouble("time") / 60)
            .put("known_speed_limit_pct", 100 * knownMeters / total).put("structure_interpolation_km", corrected / 1000)
            .put("structure_name_fallback_km", inferredMeters / 1000)
            .put("ascent_m", segments.sumOf { max(0.0, it.h1 - it.h0) })
            .put("descent_m", segments.sumOf { max(0.0, it.h0 - it.h1) })
        return Route(id, label, segments, states, caps, delay, coords, metadata)
    }

    private fun runningFuel(seconds: Double, v: Vehicle) = v.auxiliary * 1000 * seconds / (v.efficiency * v.energy * 1e6) + v.overhead * seconds / 3600
    fun segmentCost(s: Segment, v0: Double, v1: Double, v: Vehicle): Cost {
        require(s.length > 0 && min(v0, v1) >= 0 && v0 + v1 > 0)
        val seconds = 2 * s.length / (v0 + v1)
        val constant = v.mass * 9.80665 * v.rolling + v.mass * 9.80665 * (s.h1 - s.h0) / s.length + .5 * v.mass * (v1 * v1 - v0 * v0) / s.length
        val drag = .5 * v.density * v.drag
        val f0 = constant + drag * v0 * v0; val f1 = constant + drag * v1 * v1
        val work = if (min(f0, f1) >= 0) (f0 + f1) * s.length / 2 else if (max(f0, f1) <= 0) 0.0 else s.length * max(f0, f1).pow(2) / (2 * abs(f1 - f0))
        return Cost(seconds, work / (v.efficiency * v.energy * 1e6) + runningFuel(seconds, v))
    }
    fun evaluate(r: Route, speeds: DoubleArray, v: Vehicle): Plan {
        var seconds = r.delay; var liters = runningFuel(r.delay, v)
        for (i in r.segments.indices) { val c = segmentCost(r.segments[i], speeds[i], speeds[i + 1], v); seconds += c.seconds; liters += c.liters }
        return Plan(seconds, liters, speeds)
    }
    fun graph(r: Route, v: Vehicle, checkpoint: () -> Unit = {}): List<List<List<Transition>>> = r.segments.mapIndexed { i, s ->
        checkpoint()
        r.states[i + 1].map { b -> r.states[i].toList().mapIndexedNotNull { j, a ->
            val acceleration = (b * b - a * a) / (2 * s.length)
            if (acceleration >= -1.0 - 1e-6 && acceleration <= .7 + 1e-6) {
                val c = segmentCost(s, a, b, v); Transition(j, c.seconds, c.liters)
            } else null
        } }
    }
    fun solve(r: Route, graph: List<List<List<Transition>>>, penalty: Double, v: Vehicle, checkpoint: () -> Unit = {}): Plan {
        var costs = doubleArrayOf(0.0); val parents = mutableListOf<IntArray>()
        for (layer in graph) {
            checkpoint()
            val next = DoubleArray(layer.size) { Double.POSITIVE_INFINITY }; val previous = IntArray(layer.size) { -1 }
            for ((b, options) in layer.withIndex()) for (t in options) {
                val cost = costs[t.from] + t.liters + penalty * t.seconds
                if (cost < next[b] || cost == next[b] && t.from < previous[b]) { next[b] = cost; previous[b] = t.from }
            }
            costs = next; parents.add(previous)
        }
        var index = costs.indices.minBy { costs[it] }; require(costs[index].isFinite()) { "No feasible speed profile." }
        val path = IntArray(r.states.size); path[path.lastIndex] = index
        for (i in parents.indices.reversed()) { index = parents[i][index]; path[i] = index }
        return evaluate(r, DoubleArray(path.size) { r.states[it][path[it]] }, v)
    }
    private data class Label(val seconds: Double, val liters: Double, val path: List<Int>)
    private fun window(r: Route, graph: List<List<List<Transition>>>, speeds: DoubleArray, start: Int, end: Int, budget: Double, checkpoint: () -> Unit): DoubleArray? {
        val first = r.states[start].indices.minBy { abs(r.states[start][it] - speeds[start]) }
        val last = r.states[end].indices.minBy { abs(r.states[end][it] - speeds[end]) }
        var labels = List(r.states[start].size) { mutableListOf<Label>() }; labels[first].add(Label(0.0, 0.0, listOf(first)))
        var generated = 0
        for (i in start until end) {
            checkpoint()
            val next = List(r.states[i + 1].size) { mutableListOf<Label>() }
            for ((b, options) in graph[i].withIndex()) {
                if (i + 1 == end && b != last) continue
                val candidates = mutableListOf<Label>()
                for (t in options) for (label in labels[t.from]) {
                    if (++generated > 20000) return null
                    if (generated % 1024 == 0) checkpoint()
                    if (label.seconds + t.seconds <= budget) candidates.add(Label(label.seconds + t.seconds, label.liters + t.liters, label.path + b))
                }
                var bestFuel = Double.POSITIVE_INFINITY
                for (l in candidates.sortedWith(compareBy<Label> { it.seconds }.thenBy { it.liters })) if (l.liters < bestFuel) { next[b].add(l); bestFuel = l.liters }
            }
            labels = next
        }
        val best = labels[last].minByOrNull { it.liters } ?: return null
        return DoubleArray(best.path.size) { r.states[start + it][best.path[it]] }
    }
    fun optimize(r: Route, graph: List<List<List<Transition>>>, budget: Double, v: Vehicle, checkpoint: () -> Unit = {}): Plan? {
        val baseline = evaluate(r, r.caps, v)
        if (baseline.seconds > budget + 1e-5) return null
        val slow = solve(r, graph, 0.0, v, checkpoint)
        if (slow.seconds <= budget) {
            slow.optimization = JSONObject().put("method", "minimum_fuel_dynamic_programming").put("fuel_lower_bound_liters", slow.liters).put("fuel_gap_liters", 0.0)
            return slow
        }
        var best = baseline; var lowerBound = slow.liters
        fun consider(p: Plan, penalty: Double) {
            lowerBound = max(lowerBound, p.liters + penalty * (p.seconds - budget))
            if (p.seconds <= budget && p.liters < best.liters) best = p
        }
        var low = 0.0; var high = .0001
        for (i in 0 until 40) {
            val p = solve(r, graph, high, v, checkpoint); consider(p, high)
            if (p.seconds <= budget) break
            low = high; high *= 2
        }
        repeat(26) {
            val mid = (low + high) / 2; val p = solve(r, graph, mid, v, checkpoint); consider(p, mid)
            if (p.seconds <= budget) high = mid else low = mid
        }
        if (best.liters - lowerBound > 1e-8) {
            for (offset in listOf(0, 4)) {
                for (start in offset until r.segments.size step 8) {
                    val end = min(start + 8, r.segments.size)
                    if (end - start < 2 || (start + 1 until end).none { r.states[it].size > 1 }) continue
                    val blockSeconds = (start until end).sumOf { segmentCost(r.segments[it], best.speeds[it], best.speeds[it + 1], v).seconds }
                    val replacement = window(r, graph, best.speeds, start, end, budget - best.seconds + blockSeconds, checkpoint) ?: continue
                    val speeds = best.speeds.copyOf(); replacement.copyInto(speeds, start)
                    val p = evaluate(r, speeds, v)
                    if (p.seconds <= budget && p.liters < best.liters) best = p
                }
            }
        }
        lowerBound = min(best.liters, max(0.0, lowerBound))
        best.optimization = JSONObject().put("method", "lagrangian_search_with_local_pareto_refinement")
            .put("fuel_lower_bound_liters", lowerBound).put("fuel_gap_liters", max(0.0, best.liters - lowerBound))
        return best
    }
    fun pack(r: Route, p: Plan, v: Vehicle): JSONObject {
        val low = v.copy(drag = v.drag * .8 / .9, rolling = v.rolling * .009 / .011, efficiency = min(.49, v.efficiency + .04))
        val high = v.copy(drag = v.drag * 1.05 / .9, rolling = v.rolling * .013 / .011, efficiency = max(.1, v.efficiency - .04))
        return JSONObject().put("minutes", p.seconds / 60).put("liters", p.liters)
            .put("optimization", p.optimization ?: JSONObject.NULL)
            .put("sensitivity_kind", "assumption_scenarios_not_statistical_confidence_interval")
            .put("sensitivity_liters", JSONArray(listOf(evaluate(r, p.speeds, low).liters, evaluate(r, p.speeds, high).liters)))
            .put("speed_kmh", JSONArray(p.speeds.map { round(it * 360) / 100 }))
    }
}
