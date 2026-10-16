# Time–fuel graph (0.14.0)

After a calculation, expand the journey sheet and tap a dot. X is actual
extra travel time in minutes against the fastest modeled whole journey.
Y defaults to estimated litres saved against that same journey, even when points
use different roads. Litres / Euros switches only the display unit; euros uses
the current fuel-type reference price and is disabled without a valid price.
Both units retain exactly the same plans, map and CSV. Small time ranges scale
to fractional minutes instead of using a fixed minimum percentage span.
The fastest reference is selected deterministically by time, fuel, then route ID
from each section's baselines, and is always available as the zero-saving dot.
Fastest and Most fuel saved shortcuts select the two extremes. Suggested balance
appears only when an interior point is at least 0.10 above the line joining those
extremes on normalized time/savings axes. The point with the greatest margin is
used (earlier wins ties). This is a transparent heuristic among sampled plans,
not a measured preference or a global optimum. Straight, two-point and zero-time
curves do not invent a balance. Initially select this suggestion when available;
otherwise minimize fuel among displayed points within 10% extra time.

The selected-point card leads with added minutes and estimated litres/euros
saved, followed by an arrival time if leaving now (device timezone, refreshed
while visible, with next-day labeling), total time/fuel/distance and percentage
extra time. Costs use the current Slovenian reference price, excluding tolls and
parking. Unit choice is display state, not a new saved vehicle preference.

Every dot owns a route and speed plan for every section. Selection updates map
geometry, speed inspection, totals, cost and whole-journey CSV together. Switching
the inspected section retains the whole-journey choice. Normal-mode driving uses
the chosen profile; theoretical mode retains its existing driving restriction.
Changing trip inputs or recalculating clears the graph with the old result.
Selections are transient; saved vehicles, load, recents and theoretical mode persist.

The local planner samples speed optimizations with section budgets of 0, 5, 10,
15, 20, 30, 45 and 60 percent beyond each section's fastest baseline. It also
includes every candidate route's baseline, including routes beyond that range.
The graph plots the actual resulting time, never the budget label. Existing
allowance metadata and recommendation keys remain for report compatibility.
Road/provider requests, the finite speed grid, physics and road ceilings are unchanged.

Section choices are combined across the itinerary. Plans that take at least as
long and use at least as much fuel as another plan are removed, retaining the
fastest reference. The combination frontier is sampled to at most 59 real points
after each section, plus the fastest reference; the graph has at most 60 dots.
Sampling retains both extremes but can omit intermediate combinations. This is
a bounded comparison over assessed routes and sampled profiles, not proof of
a global optimum. Lines between dots are visual guides, never selectable or
interpolated speed plans. No saving is invented when only one useful point exists.

Earlier / Less fuel buttons have 48 dp minimum height and expose every point,
including closely spaced dots. The plot exposes range and selection semantics
for screen readers; selection changes announce their summary. This follows
[Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility/semantics).

Verification covers common baselines, actual minutes/percentages, route/speed retention,
asymmetric multi-section combinations, duplicate/dominated plans, bounded large
itineraries, touch and accessible selection, map/CSV/driving consistency, loading,
errors, one-point journeys, unit switching and expired-price fallback, balance
selection and its absence, midnight arrivals, and portrait/landscape layouts.
