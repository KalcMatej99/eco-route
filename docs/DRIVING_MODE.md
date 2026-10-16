# Foreground driving mode (0.10.1)

After calculating a journey, expand the results and tap **Start driving**. Review
and start while parked. Android precise foreground location is required. The
screen shows a large advisory speed, GPS speed, mapped speed limit, current road
and remaining distance in the selected section. The map follows the GPS marker;
**Explore map** allows panning, and **Follow me** resumes following. **Stop** or
Android Back returns to the planner. The display stays awake while driving mode
is visible. Portrait and landscape layouts retain the map and scrolling controls.

The advisory is the selected route's existing modeled speed profile, interpolated
in distance with the same constant-acceleration equation as speed inspection and
capped at every available mapped limit, including adjacent limits at boundaries.
If a limit is missing, it adds no extra cap: the selected plan still supplies the
advisory, bounded by the provider road-speed estimate and the 130 km/h model maximum.
The mapped limit remains **Unknown**; it is not represented as a legal unlimited
road. An explicit Valhalla `speed_limit: "unlimited"` is shown as **Unlimited**.
Valhalla [distinguishes posted limits from routing speed estimates](https://valhalla.github.io/valhalla/concepts/speeds/);
missing posted limits still have a road-speed estimate. The large value rounds down. It updates as the car
moves along the road; it does not re-optimize a journey in response to traffic or
speed deviations. Local-road speeds remain static estimates. Always follow actual
road signs and conditions; map limits may omit temporary restrictions.

Matching projects a fix onto the retained route geometry and maps its cumulative
length to the model's section distances. Fixes older than five seconds or with
accuracy worse than 35 m cannot supply advice. A position more than 30 m from the
route clears advice. Direction, recent progress and competing matches suppress
advice for wrong-way, implausible-jump and ambiguous positions. No routing service
receives these live fixes. GPS speed is the device's reported measurement, with
unavailable or imprecise measurements left blank. Missing mapped limits do not
clear a valid modeled advisory. This is approximate route matching, not lane-level navigation.

Two fresh, slow fixes at the section endpoint produce **Section complete**. For
multi-stop/return journeys, **Continue to next section** advances to that section's
selected route and speed plan. Stops require explicit continuation to avoid
matching an overlapping return leg prematurely. There are no turn instructions,
automatic rerouting, voice commands or background location service.

LocationManager (independent of Google Play services) supplies foreground fixes.
Subscriptions stop on Android pause, Stop, Back or calculation invalidation. On
resume the app waits for fresh GPS and resets matching history. Live fixes and
progress are transient memory only; no trace log, analytics or tracking upload is
added. Ordinary map-tile requests continue to go to OpenStreetMap. Existing saved
cars and trip load keep their storage and upgrade behavior.

Validation combines pure matching/limit tests, Android UI and lifecycle tests,
mock GPS through the real LocationManager API, portrait/landscape screenshots,
and signed upgrade and private-store artifact checks. Emulator/mock GPS evidence
does not establish physical phone reception or real-car acceptance. Start a road
test while parked, using a passenger to observe interactions during motion.
