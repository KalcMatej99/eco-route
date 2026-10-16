# Eco Route for Android

Eco Route is an early-beta Android trip planner for comparing routes, estimated fuel consumption, fuel cost and travel-time trade-offs. Fuel and speed optimization run on your phone.

## Download

[Download the signed Android 0.14.0 beta](https://matejkalc.com/downloads/eco-route/latest.apk) · [Project and screenshots](https://matejkalc.com/projects/eco-route)

Android 8.0 or newer. Package `org.kalc.ecoroute`, version code 19. Updates preserve saved cars, trip load, recent places, and preferences. Install the signed release APK; build outputs are unsigned intermediates.

## Features

- Destination-first map, worldwide address search, optional current-location start.
- Multiple saved cars; offline public EEA/EPA car catalogue and editable physical assumptions.
- Select a whole-journey graph point: extra travel minutes versus estimated litres or euros saved.
- Fastest / Most fuel saved shortcuts and a Suggested balance when a useful interior choice exists.
- Foreground driving guidance from the selected speed profile; stale or uncertain GPS clears the advisory.
- Device-local recent places with individual removal or clearing.
- Optional theoretical calculations ignore mapped speed limits; driving guidance is disabled in that mode.
- Up to eight intermediate stops, return to start, section-specific speed plans and CSV export.
- Estimated litres, L/100 km and fuel cost using a dated current GOV.SI reference quote.

## Data and limits

Photon supplies addresses, Valhalla supplies roads/elevations and OpenStreetMap supplies map tiles. These public services are best effort; new journeys require internet. Vehicle details and fuel calculations stay on the phone; address queries and route coordinates are sent to the relevant public providers. No account, advertising, analytics or background location tracking is included. See [privacy](PRIVACY.md).

The road-load-v2 model is uncalibrated. It supports petrol/diesel, including non-plug-in hybrids, without simulating hybrid battery control, engine maps or fuel cutoff. EV/PHEV planning is outside its scope. Traffic, weather, tolls, parking and stop time are excluded. Fuel prices are a Slovenian non-motorway reference, including for journeys abroad. Speed plans are planning estimates; follow actual signs and road conditions. The finite route/speed search does not guarantee a global optimum or measured fuel savings.

## Build

Use JDK 21, Android SDK platform 36, build-tools 35.0.0 and the Gradle wrapper. Set `JAVA_HOME` and `ANDROID_HOME` to your local installations, then:

```sh
cd android
./gradlew test lintRelease assembleRelease
```

The minified APK is unsigned. Distribution uses the permanent app signing identity; no signing materials are included here.

`reference/road_load_v2.py` is the pure Python numerical oracle. `python3 scripts/generate_model_vectors.py` regenerates synthetic parity vectors; `python3 -m catalogue.export_android` rebuilds the bundled public FTS4 catalogue. Public source inputs and attribution are in `catalogue/`. No personal trip captures or runtime caches are included.

## License

Original code: [MIT](LICENSE). Third-party licenses and public dataset attribution: [NOTICES.txt](NOTICES.txt) and `THIRD_PARTY_LICENSES/`.

## Behavior details

See [time–fuel graph](docs/TIME_FUEL_GRAPH.md), [driving mode](docs/DRIVING_MODE.md), [recent places](docs/RECENT_PLACES.md), [theoretical mode](docs/THEORETICAL_MODE.md), and [fuel-model limits](docs/FUEL_MODEL.md). Production Android sources and build inputs match the signed release; two recent-place test fixtures use non-personal public example labels.
