# Recent places (0.11.0)

Open destination search to see up to five recently selected places immediately,
without entering a query. Editing an already selected endpoint also offers
recents; the current selection stays intact until another place is chosen. The same list is available for starting points and
intermediate stops. As you type, up to three matching recents stay above up to
five new online results. Lists are scrollable; recent entries have a history icon
and an explicit **Recent places** label. Online matches have **Search results**.
Matching ignores case and accents and accepts partial words, including town and
postcode prefixes. Results are not automatically selected.

Choose a place to move it to the top. Equivalent provider results are deduplicated.
The list keeps at most 20 selected places, with full coordinates and display details
so selecting a recent works after an app restart without another address search.
New route calculation still needs online road data. Network loading or failure
does not hide matching recents, and selecting one cancels pending address search.

Remove a recent using its labeled close button, or use **Clear recent places**.
These actions affect history only; they do not change the selected trip, saved
cars or trip load. History stays in app-private preferences, with Android backup
disabled. Unconfirmed queries, search-result lists and live GPS fixes are not added
to it. Typed queries still go to Photon for normal online address search. History
is not synchronized or uploaded as a list. Prior app versions did not retain
confirmed places across sessions; old cache responses are not used to invent a
history of selections.

The design uses simple text suggestions next to the input, clear labels for
recent versus new results, and a stable recent-first ordering. Nielsen Norman
Group's [search-suggestion guidance](https://www.nngroup.com/articles/site-search-suggestions/)
recommends suggestions that are easy to scan, particularly on mobile; its
[guidance on enriched suggestions](https://www.nngroup.com/articles/enriched-site-search-suggestions/)
recommends labeling suggestion types and preserving simple text suggestions.
Android's [Compose search guidance](https://developer.android.com/develop/ui/compose/components/search-bar)
shows scrollable, filtered suggestions with explicit selection. The existing
address panel is retained rather than replacing the journey interface.

Validation covers ordering, limits, normalization, malformed persisted entries,
deduplication and coordinate round trips, plus Android selection after session
restart, pending/failed network searches, clearing/removal, stop reuse, normal
empty results and portrait/landscape layouts. Signed-upgrade checks preserve
saved cars, trip load and recent history.
