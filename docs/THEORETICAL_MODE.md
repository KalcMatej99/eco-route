# Theoretical calculations (0.12.0)

Open Trip options and enable **Ignore speed limits**. The setting is off by
default and persists on the device independently of the selected car. Changing
it cancels pending work, stops driving mode and clears the current result;
calculate again using the selected endpoints, stops, return option and allowance.

The local fuel/speed model removes mapped speed limits and provider routing
speeds as calculation ceilings. All sections use the existing finite 130 km/h
model maximum and become eligible for the existing discrete speed search.
Acceleration, braking, gradients, vehicle inputs, time allowances, fuel equation
and per-section stop boundaries retain their existing behavior. This mode is a
bounded model experiment, not an unrestricted global optimum: the speed-state
grid still uses the existing 80 km/h upward cruise choices where transition caps
permit them. The fastest reference and savings are recalculated in this mode.

The public router still supplies road geometry, candidate paths, junction delays
and descriptive travel-time estimates. This feature does not remove limits from
the external routing service or generate every conceivable path. In particular,
[Valhalla's routing speeds can be derived from OSM speed limits](https://valhalla.github.io/valhalla/concepts/speeds/),
so merely dropping the explicit speed_limit field would leave an indirect speed
ceiling. Neither field is used as a ceiling in the theoretical calculation.

Actual mapped limits and provider speeds remain in the report for inspection.
Route and section JSON retain ignore_speed_limits, the result sheet identifies
Theoretical mode even when collapsed, and the map inspector identifies a
Theoretical speed plan. CSVs append calculation_mode to every row and use a
separate theoretical filename. Defaults and older unmarked reports remain in
normal mode; a response that disagrees with the requested setting is rejected.

Live driving is available for normal plans. The Start driving button is disabled
for theoretical results and the view model/guide also reject them. Disable
Ignore speed limits and calculate again to obtain a normal driving plan.

Only the boolean ignore_speed_limits_v1 is added to app-private preferences.
Saved cars, load and recent places are preserved. No new permission, background
tracking, account or network request field is introduced.

Validation covers invariance under changed mapped limits/provider speeds,
normal-mode ceilings and existing reference parity, finite speed bounds, round
trip metadata and allowances, persistence, cancellation, mismatched/error
responses, CSV labeling, driving gating and portrait/landscape UI.
