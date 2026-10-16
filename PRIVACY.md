# Eco Route privacy

Updated 16 October 2026. Eco Route is provided by Matej Kalc as free, open-source software. No account, advertising, analytics or background tracking is included.

## On your phone

Saved cars, vehicle assumptions, trip load, the theoretical-calculation preference, and up to 20 confirmed recent places (including their coordinates) remain in app-private storage. Remove individual recent places or clear the list in address search. Unconfirmed queries and live GPS fixes are not added to this history. Fuel/speed optimization runs locally; vehicle parameters are not sent to a route backend. The public reference car catalogue is bundled and searchable offline. Android backup and device transfer are disabled. Removing cars changes local profiles; clearing app data or uninstalling removes app-private data. Previously exported CSV files must be deleted separately.

## Online providers

Address searches go to Photon at photon.komoot.io. Selected coordinates, road options and route geometry go to the Valhalla service at valhalla1.openstreetmap.de for roads and elevations. Map tiles come from OpenStreetMap; viewed areas can be inferred from tile requests. The current Slovenian reference fuel price is fetched from GOV.SI. Each provider receives ordinary connection metadata, including IP address, and handles requests under its own terms. Internet is required for new data; these are best-effort services.

Coordinates for an optional current-location start are registered locally, then sent to Valhalla when you calculate. Use my location requests foreground approximate or precise permission when tapped. Start driving requires precise foreground location to match your position to the selected route and show GPS speed and a modeled advisory speed. Driving location updates stop when the app is hidden or driving mode ends. Live driving fixes are processed on the phone and are not stored as a journey history. No background location permission is requested.

## Retention and exports

Road/address responses are cached privately on the device, bounded to 32 MiB. Entries older than seven days are deleted when the cache is next used. Price pages are cached for up to an hour and effective dates are checked before using a quote. Unconfirmed address queries, calculated journeys, graph selections, and live GPS fixes are transient UI state. Confirmed recent places remain locally until removed or cleared; clearing app data or uninstalling also removes them. CSV exports are created only when requested, with Android's document picker; a cloud destination you choose may upload the file under its own terms.

## Downloads and questions

The portfolio website and GitHub download hosting are separate web services with their own connection processing; the portfolio uses Vercel Analytics. Downloading an APK does not send your in-app car profiles or journeys to the portfolio. Contact [matejkalc99@protonmail.com](mailto:matejkalc99@protonmail.com) for privacy questions. Include only information needed for your question.
