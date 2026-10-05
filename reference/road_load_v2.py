"""Transparent, uncalibrated road-load model and discrete speed-profile optimizer.

No live traffic, departure time, regenerative energy credit, or engine efficiency map.
Only known-limit motorway cruise sections are optimized. Other roads retain the
router's static speed envelope, with acceleration/deceleration transitions.
"""
import bisect
import math


MODEL_MAX_SPEED_KMH = 130
MODEL_VERSION = 'road-load-v2'


def _structure_name_hint(edge):
    """An explicit viaduct name is a fallback hint, not a verified bridge tag."""
    names = [name.casefold().strip() for name in edge.get('names',[])]
    return any(name.startswith(('viadukt ', 'viaduct ', 'viadotto ', 'viaduc '))
               and name not in {'viaduct road', 'viaduct street', 'viaduct avenue'}
               for name in names)


def _speed_envelope(edge, planning):
    """Return a traffic-free planning ceiling and auditable provenance.

    Valhalla ``speed`` is its routing/travel-time speed, whereas ``speed_limit``
    is the mapped posted limit.  The opt-in policy deliberately uses the latter
    for ordinary, known-limit motorway carriageways.  Ramps, other road classes,
    and unknown limits keep the cautious legacy/router envelope.
    """
    router_speed = edge['speed']
    limit = edge.get('speed_limit')
    known = isinstance(limit, (int, float)) and 0 < limit < 200
    posted_motorway_policy = planning.get('speed_policy') == 'mapped_motorway_limit'
    ordinary_motorway = (edge['road_class'] == 'motorway' and edge['use'] == 'road'
                         and not edge.get('roundabout'))
    if posted_motorway_policy and ordinary_motorway and known:
        cap_kmh = min(limit, MODEL_MAX_SPEED_KMH)
        basis = 'mapped_limit_motorway'
        detail = (f'Traffic-free known-limit motorway envelope: mapped limit {limit:g} km/h, '
                  f'bounded by the {MODEL_MAX_SPEED_KMH} km/h model maximum. Valhalla static '
                  f'routing speed {router_speed:g} km/h is ETA context, not the cruise ceiling.')
    else:
        cap_kmh = min(router_speed, limit if known else router_speed, MODEL_MAX_SPEED_KMH)
        basis = 'router_static_speed_and_mapped_limit' if known else 'router_static_speed_unknown_limit'
        detail = (f'Cautious envelope uses Valhalla static routing speed {router_speed:g} km/h'
                  + (f' and mapped limit {limit:g} km/h.' if known else '; mapped limit unavailable.'))
    return cap_kmh / 3.6, known, {
        'provider_speed_kmh': router_speed,
        'cruise_cap_kmh': cap_kmh,
        'speed_basis': ('mapped_motorway_limit'
                        if basis == 'mapped_limit_motorway' else 'provider_estimate'),
        'router_static_speed_kmh': router_speed,
        'speed_envelope_kmh': cap_kmh,
        'speed_envelope_basis': basis,
        'speed_envelope_detail': detail,
        'speed_choice_detail': ('A modeled profile may be below this envelope to reduce fuel or '
                                'to satisfy acceleration/deceleration bounds.'),
    }


def decode_polyline(encoded, precision=6):
    coords, index, lat, lon = [], 0, 0, 0
    while index < len(encoded):
        deltas = []
        for _ in range(2):
            value = shift = 0
            while True:
                byte = ord(encoded[index]) - 63
                index += 1
                value |= (byte & 31) << shift
                shift += 5
                if byte < 32:
                    break
            deltas.append(~(value >> 1) if value & 1 else value >> 1)
        lat += deltas[0]
        lon += deltas[1]
        coords.append([lat / 10**precision, lon / 10**precision])
    return coords


def distance(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    q = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 6371000 * 2 * math.atan2(math.sqrt(q), math.sqrt(max(0, 1-q)))


def interp(xs, ys, x):
    i = max(0, min(len(xs)-2, bisect.bisect_right(xs, x)-1))
    f = (x-xs[i]) / max(1e-9, xs[i+1]-xs[i])
    return ys[i] + min(1, max(0, f)) * (ys[i+1]-ys[i])


def prepare(candidate, config):
    trace = candidate['trace']
    coords = decode_polyline(trace['shape'])
    cumulative = [0.0]
    for a, b in zip(coords, coords[1:]):
        cumulative.append(cumulative[-1] + distance(a, b))
    total = cumulative[-1]
    heights = trace.get('elevation', [])
    if len(heights) < 2 or any(isinstance(h,bool) or not isinstance(h,(int,float))
                              or not math.isfinite(h) or abs(h) >= 32000 for h in heights):
        raise ValueError(f"{candidate['id']}: missing elevation; cannot silently assume flat terrain")
    interval = trace['elevation_interval']
    if total <= 0 or not isinstance(interval,(int,float)) or not math.isfinite(interval) or interval <= 0:
        raise ValueError(f"{candidate['id']}: invalid elevation spacing or route length")
    # Valhalla resamples the matched geometry at the requested interval plus endpoint.
    hx = [min(i*interval, total) for i in range(len(heights))]
    hx[-1] = total
    if any(b <= a for a, b in zip(hx, hx[1:])):
        hx = [i * total/(len(heights)-1) for i in range(len(heights))]
    heights = list(heights)
    corrected = inferred_structure_m = 0.0
    structures = []
    for edge in trace['edges']:
        inferred = (config['planning'].get('terrain_policy') != 'legacy_tag_only'
                    and _structure_name_hint(edge) and not edge.get('bridge') and not edge.get('tunnel'))
        if edge.get('tunnel') or edge.get('bridge') or inferred:
            a, b = cumulative[edge['begin_shape_index']], cumulative[edge['end_shape_index']]
            if inferred:
                inferred_structure_m += b-a
            if structures and a <= structures[-1][1]+1:
                structures[-1][1] = b
            else:
                structures.append([a, b])
    for a, b in structures:
        ha, hb = interp(hx, heights, a), interp(hx, heights, b)
        for i in range(bisect.bisect_left(hx, a), bisect.bisect_right(hx, b)):
            heights[i] = ha+(hb-ha)*(hx[i]-a)/max(1, b-a)
        corrected += b-a
    # Smooth noisy terrain samples; this is approximate road elevation, not a survey.
    smoothed = [sum(heights[max(0,i-2):min(len(heights),i+3)]) /
                len(heights[max(0,i-2):min(len(heights),i+3)]) for i in range(len(heights))]
    smoothed[0], smoothed[-1] = heights[0], heights[-1]
    segments = []
    known_m = 0.0
    for edge in trace['edges']:
        start = cumulative[edge['begin_shape_index']]
        end = cumulative[edge['end_shape_index']]
        if end-start < 0.01:
            continue
        limit = edge.get('speed_limit')
        cap, known, envelope_metadata = _speed_envelope(edge, config['planning'])
        if cap <= 0:
            raise ValueError('Non-positive static speed')
        optimize = (edge['road_class'] == 'motorway' and edge['use'] == 'road'
                    and known and cap * 3.6 >= 80 and not edge.get('roundabout'))
        known_m += end-start if known else 0
        count = math.ceil((end-start)/config['planning']['segment_max_m'])
        for i in range(count):
            a = start+(end-start)*i/count
            b = start+(end-start)*(i+1)/count
            segments.append({'start_m':a, 'end_m':b, 'distance_m':b-a,
                             'height_start_m':interp(hx,smoothed,a),
                             'height_end_m':interp(hx,smoothed,b),
                             'cap_ms':cap, 'optimize':optimize,
                             'name':' / '.join(edge.get('names',[])) or 'Local access road',
                             'road_class':edge['road_class'], 'limit_kmh':limit if known else None,
                             'tunnel':edge.get('tunnel',False), 'way_id':edge['way_id'],
                             'structure_name_hint':(config['planning'].get('terrain_policy') != 'legacy_tag_only'
                                                    and _structure_name_hint(edge)),
                             **envelope_metadata})
    n = len(segments)
    caps = [0.0]+[min(segments[i-1]['cap_ms'],segments[i]['cap_ms']) for i in range(1,n)]+[0.0]
    acceleration = config['planning']['acceleration_m_s2']
    deceleration = config['planning']['deceleration_m_s2']
    for i,s in enumerate(segments):
        caps[i+1] = min(caps[i+1], math.sqrt(caps[i]**2+2*acceleration*s['distance_m']))
    for i in range(n-1,-1,-1):
        caps[i] = min(caps[i], math.sqrt(caps[i+1]**2+2*deceleration*segments[i]['distance_m']))
    states = []
    for i,cap in enumerate(caps):
        choices = [cap]
        if 0 < i < n and segments[i-1]['optimize'] and segments[i]['optimize']:
            choices += [v/3.6 for v in range(config['planning']['minimum_motorway_cruise_kmh'],
                                             int(cap*3.6),config['planning']['speed_step_kmh'])]
        # Preserve the exact cap in the graph. Rounding it changes the fastest
        # attainable time and can make the zero-extra-time deadline infeasible.
        states.append(sorted(set(choices)))
    delay = sum(e.get('end_node',{}).get('transition_time',0) for e in trace['edges'])
    return {'id':candidate['id'], 'label':candidate['label'], 'segments':segments,
            'states':states, 'caps':caps, 'junction_delay_s':delay,
            'coordinates':coords, 'distance_m':total,
            'provider_minutes':candidate['trip']['summary']['time']/60,
            'speed_envelope_policy':(
                'mapped_limit_for_known_motorway_roads'
                if config['planning'].get('speed_policy') == 'mapped_motorway_limit'
                else 'legacy_router_static_speed'),
            'model_max_speed_kmh':MODEL_MAX_SPEED_KMH,
            'known_speed_limit_pct':100*known_m/total,
            'structure_interpolation_km':corrected/1000,
            'structure_name_fallback_km':inferred_structure_m/1000,
            'ascent_m':sum(max(0,s['height_end_m']-s['height_start_m']) for s in segments),
            'descent_m':sum(max(0,s['height_start_m']-s['height_end_m']) for s in segments)}


def segment_cost(segment, v0, v1, vehicle):
    """Integrate positive wheel work along a constant-acceleration segment.

    v^2 is linear in distance, so net force (including drag) is linear too.
    Clip force *before* integrating: braking cannot cancel earlier engine work.
    Auxiliary power is an equivalent mechanical load; efficiency is incremental
    fuel-to-wheel efficiency, with a separate engine-running overhead. This is
    an engine-on approximation, not a BSFC map or a hybrid battery model.
    """
    length = segment['distance_m']
    if length <= 0 or min(v0, v1) < 0 or v0+v1 <= 0:
        raise ValueError('A positive-distance segment needs a moving endpoint')
    seconds = 2*length/(v0+v1)
    mass = vehicle['mass_kg']
    constant_force = (mass*9.80665*vehicle['rolling_resistance']
                      + mass*9.80665*(segment['height_end_m']-segment['height_start_m'])/length
                      + 0.5*mass*(v1*v1-v0*v0)/length)
    drag_factor = 0.5*vehicle['air_density_kg_m3']*vehicle['drag_area_m2']
    f0, f1 = constant_force+drag_factor*v0*v0, constant_force+drag_factor*v1*v1
    if min(f0, f1) >= 0:
        work = (f0+f1)*length/2
    elif max(f0, f1) <= 0:
        work = 0.0
    else:
        # Area of the positive triangle at a traction/braking sign crossing.
        work = length*max(f0, f1)**2/(2*abs(f1-f0))
    fuel = work/_fuel_conversion(vehicle) + _running_fuel(seconds, vehicle)
    return seconds, fuel


def _fuel_conversion(vehicle):
    # Do not eagerly require the legacy diesel key for petrol-only inputs.
    energy = (vehicle['fuel_energy_mj_l'] if 'fuel_energy_mj_l' in vehicle
              else vehicle['diesel_energy_mj_l'])
    return vehicle['tank_to_wheel_efficiency']*energy*1e6


def _running_fuel(seconds, vehicle):
    return (vehicle['auxiliary_kw']*1000*seconds/_fuel_conversion(vehicle)
            + vehicle['engine_overhead_l_h']*seconds/3600)


def evaluate(route, speeds, vehicle):
    seconds, liters = 0., 0.
    for s,a,b in zip(route['segments'],speeds,speeds[1:]):
        t,f = segment_cost(s,a,b,vehicle)
        seconds += t
        liters += f
    seconds += route['junction_delay_s']
    liters += _running_fuel(route['junction_delay_s'], vehicle)
    return {'seconds':seconds,'liters':liters,'speeds_ms':speeds}


def graph(route, config):
    transitions = []
    for i,seg in enumerate(route['segments']):
        layer=[]
        for b in route['states'][i+1]:
            options=[]
            for j,a in enumerate(route['states'][i]):
                acc = (b*b-a*a)/(2*seg['distance_m'])
                if -config['planning']['deceleration_m_s2']-1e-6 <= acc <= config['planning']['acceleration_m_s2']+1e-6:
                    t,f = segment_cost(seg,a,b,config['vehicle'])
                    options.append((j,t,f))
            layer.append(options)
        transitions.append(layer)
    return transitions


def solve(route, transitions, penalty, vehicle):
    costs = [0.]
    parents=[]
    for layer in transitions:
        next_costs, previous=[],[]
        for options in layer:
            candidates=[(costs[j]+f+penalty*t,j) for j,t,f in options]
            best=min(candidates,default=(math.inf,-1))
            next_costs.append(best[0]);previous.append(best[1])
        costs=next_costs;parents.append(previous)
    index=min(range(len(costs)),key=costs.__getitem__)
    if not math.isfinite(costs[index]):
        raise ValueError('No feasible speed profile')
    path=[index]
    for previous in reversed(parents):
        index=previous[index];path.append(index)
    path.reverse()
    speeds=[states[j] for states,j in zip(route['states'],path)]
    return evaluate(route,speeds,vehicle)


def _pareto_window(route, transitions, speeds, start, end, budget_s):
    """Exact nondominated time/fuel labels inside a bounded, fixed-end window.

    Weighted sums miss unsupported points of a discrete Pareto frontier. Keep
    those points here, without rounding time or relaxing the deadline. An explicit
    work limit aborts the window unchanged instead of silently pruning good labels.
    """
    first = min(range(len(route['states'][start])),
                key=lambda j:abs(route['states'][start][j]-speeds[start]))
    last = min(range(len(route['states'][end])),
               key=lambda j:abs(route['states'][end][j]-speeds[end]))
    labels = [[] for _ in route['states'][start]]
    labels[first] = [(0.0, 0.0, (first,))]
    generated = 0
    for i in range(start, end):
        next_labels = [[] for _ in route['states'][i+1]]
        for b, options in enumerate(transitions[i]):
            if i+1 == end and b != last:
                continue
            candidates = []
            for j, seconds, fuel in options:
                for elapsed, liters, path in labels[j]:
                    generated += 1
                    if generated > 20000:
                        return None
                    if elapsed+seconds <= budget_s:
                        candidates.append((elapsed+seconds, liters+fuel, path+(b,)))
            best_fuel = math.inf
            for label in sorted(candidates):
                if label[1] < best_fuel:
                    next_labels[b].append(label)
                    best_fuel = label[1]
        labels = next_labels
    best = min(labels[last], key=lambda label:label[1], default=None)
    if best is None:
        return None
    return [route['states'][i][j] for i,j in zip(range(start,end+1),best[2])]


def _refine(route, transitions, plan, budget_s, vehicle):
    """Improve a feasible profile with exact, overlapping eight-segment windows.

    This is local improvement for long routes, not a global optimality guarantee.
    Every accepted profile is independently evaluated against the actual budget.
    """
    speeds = list(plan['speeds_ms'])
    count = len(route['segments'])
    for offset in (0, 4):
        for start in range(offset, count, 8):
            end = min(start+8, count)
            if end-start < 2 or not any(len(s)>1 for s in route['states'][start+1:end]):
                continue
            block_seconds = sum(segment_cost(route['segments'][i], speeds[i], speeds[i+1], vehicle)[0]
                                for i in range(start,end))
            replacement = _pareto_window(route, transitions, speeds, start, end,
                                         budget_s-plan['seconds']+block_seconds)
            if replacement is None:
                continue
            candidate = speeds[:start]+replacement+speeds[end+1:]
            result = evaluate(route,candidate,vehicle)
            if result['seconds'] <= budget_s and result['liters'] < plan['liters']:
                speeds, plan = candidate, result
    return plan


def optimize(route, transitions, budget_s, config):
    baseline = evaluate(route,route['caps'],config['vehicle'])
    if baseline['seconds'] > budget_s+1e-5:
        return None
    vehicle = config['vehicle']
    slow = solve(route,transitions,0,vehicle)
    if slow['seconds'] <= budget_s:
        slow['optimization'] = {'method':'minimum_fuel_dynamic_programming',
                                'fuel_lower_bound_liters':slow['liters'], 'fuel_gap_liters':0.0}
        return slow
    best=baseline
    lower_bound = slow['liters']
    def consider(result, penalty):
        nonlocal best, lower_bound
        # Lagrangian dual bound for this finite speed graph, including the
        # same fixed junction delays and auxiliary fuel as the feasible plan.
        lower_bound = max(lower_bound, result['liters']+penalty*(result['seconds']-budget_s))
        if result['seconds'] <= budget_s and result['liters'] < best['liters']:
            best = result
    low,high=0.,0.0001
    for _ in range(40):
        result=solve(route,transitions,high,vehicle)
        consider(result,high)
        if result['seconds'] <= budget_s:
            break
        low,high=high,high*2
    for _ in range(26):
        mid=(low+high)/2
        result=solve(route,transitions,mid,vehicle)
        consider(result,mid)
        if result['seconds'] <= budget_s:
            high=mid
        else:
            low=mid
    if best['liters']-lower_bound > 1e-8:
        best=_refine(route,transitions,best,budget_s,vehicle)
    # Rounding speeds in the graph can cause sub-microliter bound discrepancies.
    lower_bound = min(best['liters'], max(0.0, lower_bound))
    best['optimization'] = {'method':'lagrangian_search_with_local_pareto_refinement',
                            'fuel_lower_bound_liters':lower_bound,
                            'fuel_gap_liters':max(0.0,best['liters']-lower_bound)}
    return best
