# Fuel model audit — 3 October 2026

## Scope and finish condition

Audit the existing traffic-free petrol/diesel model against primary research;
correct demonstrable energy-accounting and optimization errors; verify the
formulas independently and check real saved route inputs. No user-feedback,
account, vehicle-calibration UI, or new Android release is part of this change.

The server identifies the revised implementation as `road-load-v2`. Existing
prototype reports remain historical v1 evidence. New calculations use v2.

## Equations implemented

For a section with length d in metres, elevation difference dh in metres,
endpoint speeds v0 and v1 in m/s, and constant longitudinal acceleration:

```text
t = 2 d / (v0 + v1)                                    [s]
a = (v1² - v0²) / (2 d)                                [m/s²]
v(x)² = v0² + (v1² - v0²) x / d

F(x) = m g Crr + 0.5 rho CdA v(x)² + m g dh/d + m a     [N]
E_positive = integral from 0 to d of max(0, F(x)) dx     [J]

L = E_positive / (eta Q)
    + P_aux t / (eta Q)
    + overhead_l_h t / 3600                            [L]
```

Q is the selected fuel's lower-heating energy per litre in J/L; the existing
nominal assumptions are 35.8 MJ/L for diesel and 32.0 MJ/L for petrol. eta is
the constant incremental conversion efficiency used by this approximation.
P_aux is an equivalent mechanical accessory demand, not a raw electrical load
with an independently modeled alternator. An additional engine-running fuel
rate accounts approximately for overhead. The configured coefficients are
engineering assumptions, not measurements of a particular car's engine map.

F(x) is linear in distance. Its positive integral is a trapezoid when both
endpoint forces are positive, zero when both are negative, and a triangle when
the sign changes. This is an exact integral **within the section assumptions**.
Constant-speed drag energy is proportional to v²; drag power is proportional
to v³. Constant-acceleration aerodynamic work averages endpoint **squared**
speeds in distance; it must not square their arithmetic average.

The original road-load terms and distance-domain aerodynamic integral were
correct. However, clipping the whole section's net work after integration
allowed braking to cancel powered motion inside that section. V2 clips net
force before integration. Splitting an identical section no longer changes
the fuel result when force crosses zero. Fixed junction delays now include
accessory demand as well as the engine-running overhead. The petrol energy
lookup no longer unnecessarily requires the legacy diesel-only field.

Route distance comes from the mapped horizontal geometry. Grade uses dh/d, a
small-slope approximation to a fully three-dimensional road model. The model
does not separately represent rotational inertia, drivetrain spin loss, wind,
temperature, warm-up, gear changes, or engine torque/power limits. Signal
delays are fixed router transition times; they are not second-by-second urban
stop/start driving cycles. Elevation is smoothed and bridge/tunnel terrain is
interpolated, so it is not surveyed road elevation.

The audit found a concrete DEM artifact at Črni Kal: the saved route named the
viaduct but its bridge flag was false, leaving a modeled slope above 23%.
V2 also recognizes explicit viaduct names as a structure hint and interpolates
between endpoints. The inferred length is separately reported as
`structure_name_fallback_km`; this is an approximation, not newly surveyed
bridge data. In the saved inputs it reduces the maximum named-viaduct slope
below 8% and removes the resulting >200 kW wheel-demand artifact. This does
not prove all grades are accurate or enforce power limits for every car.

## Fuel conversion and hybrids

This is an engine-on affine approximation to fuel demand. It retains overhead
and accessory fuel while braking/downhill. It must not be interpreted as an
engine's actual fuel-cutoff, start/stop or mild-hybrid control strategy. Accurate
modeling of those states requires engine/gear and battery parameters. A generic
hybrid percentage discount or negative fuel credit would not solve this.

Catalogue mass, engine rating and homologation fuel consumption are useful
facts, but a combined L/100 km value does not identify a speed/load efficiency
map. The app currently displays catalogue fuel/power as references rather than
using them as a measured engine model. Fitting one whole-cycle figure directly
to motorway consumption would require an explicit and defensible drive-cycle
model, and independent validation.

## Optimizer corrections

The objective is minimum estimated litres with total modeled time <= the chosen
deadline, within a finite set of candidate routes and speed states. Only
known-limit motorway cruise sections have adjustable speeds. Acceleration and
deceleration limits connect speed states; other sections retain their envelope.

Dynamic programming minimizes `fuel + lambda * time`. A fixed upper lambda and
weighted-sum search alone are insufficient for all discrete hard-deadline
problems: an unsupported Pareto point can be feasible and best under the
deadline while never winning a weighted sum. V2 adaptively brackets lambda
and adds exact nondominated time/fuel searches inside overlapping eight-section
windows. Each accepted replacement is evaluated against the real deadline.
Each window has a 20,000 generated-label work limit; exceeding it leaves the
existing feasible path untouched. Long routes retain a local-search limitation.

Speed caps now remain exact graph states; previously rounding them could make
the zero-extra-time deadline differ from the fastest available graph path.

For every scalar search result, a Lagrangian lower bound is computed:

```text
LB(lambda) = min_path [fuel(path) + lambda * (time(path) - deadline)]
fuel_gap_liters = returned_feasible_fuel - max_lambda LB(lambda)
```

This bounds remaining optimizer error on that route's finite speed graph.
It is **not** a bound on real-world fuel-prediction error, missing route
alternatives, or road-data uncertainty. The assumption-sensitivity range is
also explicitly marked as scenarios, not a statistical confidence interval.

## Research and decisions

- Geyer and Malen (2020), [Parsimonious powertrain modeling, part 1](https://link.springer.com/article/10.1007/s11367-020-01774-0),
  DOI 10.1007/s11367-020-01774-0. Supports net tractive-force accounting and
  engine-map modeling. This informed the sign-crossing correction and the
  separation between sound wheel mechanics and approximate fuel conversion.
- Baker et al. (2021), [FASTSim Validation Report](https://docs.nlr.gov/docs/fy22osti/81097.pdf),
  NREL/TP-5400-81097. FASTSim uses representative power-dependent efficiency
  maps, and supports more detailed vehicle-specific inputs. Its published
  validation belongs to FASTSim and its tested vehicles, not to Eco Route.
- Yan et al. (2021), [Eco-Coasting Strategies Using Road Grade Preview](https://arxiv.org/html/2111.07377v3).
  Separately models fuel cutoff, engine start/stop, engine drag and restart
  energy. Supports keeping engine-running fuel explicit instead of claiming
  that downhill zero wheel demand always means zero fuel.
- Rakha et al. (2011), [VT-CPFM research and calibration resources](https://rakha.cee.vt.edu/Software.html),
  DOI 10.1016/j.trd.2011.05.008. The research group describes calibration from
  public city/highway fuel ratings. This offers a path without collecting user
  feedback, provided matching ratings and test cycles are available.
- [FASTSim source and documentation](https://github.com/NatLabRockies/fastsim)
  provide a free, reusable powertrain simulator. A future upgrade should
  benchmark/import suitable public engine curves and test cycles instead of
  inventing Volvo-specific coefficients or introducing an unsupported hybrid
  credit. No FASTSim dependency or guessed engine curve was added in this audit.
- [Valhalla elevation API](https://valhalla.github.io/valhalla/api/elevation/)
  describes DEM-based heights. Its [map-matching API](https://valhalla.github.io/valhalla/api/map-matching/)
  distinguishes structure flags from the manufactured weighted-grade factor;
  that factor is not a replacement for actual road slope.

## Verification

Independent midpoint integration verifies traction/braking sign crossings.
Subdivision invariance checks 2, 7 and 100 subdivisions. Additional tests cover
stationary auxiliary fuel and petrol-only energy inputs. A constructed discrete
Pareto counterexample catches the old search error; 36 small-graph/deadline
comparisons use exhaustive enumeration as the optimization oracle.

Saved outbound/return road inputs are separately checked with 0%, 5%, 10%, and
15% allowances, speed and acceleration bounds, a comparison against the old
search using the **same revised** fuel equation, and runtime measurements.
These checks establish mathematical/software consistency. They do not establish
a measured Volvo fuel-prediction accuracy or a guaranteed network-global route.

The next improvement supported by these sources is an offline engine-map and
standard-drive-cycle benchmark. It does not require a user-feedback feature.

Final verification: 59 backend tests passed. Thirteen saved route/allowance
profiles passed the deadline, speed and acceleration checks; the revised search
used no more fuel than the old search evaluated with the same v2 physics and
terrain. The largest observed search improvement was about 0.045 L and the
largest remaining finite-graph fuel bound was about 0.0274 L. Solving a profile
took at most 0.49 s in this bounded run; these are host measurements, not a
general latency guarantee. Maximum checked wheel-power demand was 116 kW.

Android 0.9.0 runs the same road-load-v2 equations and bounded optimization on the phone. Public road/elevation providers supply inputs; no private backend connection is required. Python/Android parity vectors cover synthetic input profiles. This source snapshot excludes personal trip captures and operational deployment records. The historical backend timings above are not Android latency guarantees.
