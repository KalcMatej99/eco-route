# Free vehicle catalogue

Eco Route uses a local SQLite snapshot for vehicle search, rather than calling a
third-party service for each keystroke. No subscription, API key or paid data is
required. From Android 0.9.0, `python3 -m catalogue.export_android` generates a compressed
FTS4 asset from the committed public inputs. The app installs/searches it locally
and works offline. No vehicle-search backend is required by the Android app.

## Source and scope

Source: European Environment Agency (EEA), **Monitoring of CO2 emissions from
passenger cars, 2024 – Final**, DG CLIMA, table
`[CO2Emission].[latest].[co2cars_2024Fv30]`.

- [Dataset](https://www.eea.europa.eu/en/datahub/datahubitem-view/fa8b1229-3db6-495d-b18e-9c9b3267c02b)
- [Official metadata and reuse licence](https://sdi.eea.europa.eu/catalogue/srv/api/records/5018ec17-2348-4c92-8761-6f2377bbd1c0/formatters/xml)
- [Table definition](https://sdi.eea.europa.eu/catalogue/api/records/5018ec17-2348-4c92-8761-6f2377bbd1c0/attachments/Table-definition-cars-2024-Final.xlsx)
- [SQL REST endpoint](https://discodata.eea.europa.eu/)

Copyright: Directorate-General for Climate Action (DG CLIMA). The official
metadata specifies **CC BY 4.0**. This adapted data remains attributed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), separately from the
application's MIT licence. Eco Route is not endorsed by EEA or DG CLIMA.
The original metadata is retained in `data/eea2024-metadata.xml`.

The source covers new registrations in EU countries, Iceland and Norway in
**2024**. This is a registration year, not a production/model year. Only records
with petrol or diesel fuel are imported, including non-plug-in hybrids. Electric
and plug-in vehicles are outside the current liquid-fuel model. The source may
contain omissions, inconsistent names, unusual approvals and erroneous values;
this is not a definitive fitment or trim catalogue. Manual entry remains available.

## Snapshot and transformations

The bounded aggregate query is in `import_eea.py`. Snapshot downloaded
2026-10-02 UTC: 8,083,429 source rows, 39,955 groups, 39,717 searchable entries
(after removing 238 unnamed groups). The count of source rows is independently
checked against the source before publication, preventing a silently truncated
import. Source rows are not necessarily a one-to-one count of registered cars.

Raw JSON SHA-256:
`38f9e7b1eb84035dd69e8b6ddc96357b88c565a79e617b3d3c759eab82e1a01d`.
The exact public input is retained, compressed deterministically, in
`data/eea2024-technical-groups.json.gz` (the derived FTS4 snapshot is packaged in Android 0.9.0).

Grouping preserves make, commercial name, type, variant, fuel, fuel mode,
engine capacity, power and running-order mass. Versions sharing those inputs
are combined; the minimum and maximum reported consumption are retained.
This does not claim an exact trim or VIN match. Invalid/missing numbers remain
unavailable; there is no average-mass substitution. Entries without a usable
mass cannot auto-fill a calculable profile. Search normalizes accents/punctuation
and letter-digit boundaries, and pagination exposes all matching records.

The only additional marketing search alias is **B5 / D420T2**, restricted to
Volvo XC90 diesel, 173 kW and 1969 cc, supported by the
[Volvo MY24 manufacturer specification](https://www.volvocars.com/files/cs/v3/assets/blt0feaa88e629251fc/bltae8b2f62e4d70686/66eb042044ba3379a77f646e/xc90-my24-26-10-2023.pdf?branch=prod_alias).
It does not overwrite the source name or assert the owner's exact trim.

Mass in running order includes the standardized **75 kg driver**; passengers,
baggage and any driver-weight difference need adjustment, not another full driver
added automatically. See the [European Commission mass definitions](https://circabc.europa.eu/sd/a/89cb6ac3-8170-4ef9-9258-f29e35b1adc7/masses_definitions_EU_TA_legislation.pdf).
The reported laboratory consumption range is a reference, not the route prediction
or a calibration of speed-dependent consumption. Power and displacement identify
the engine; they are not directly fed into the current route model.

Aerodynamic drag area, drivetrain efficiency, rolling resistance, auxiliary load
and idle fuel use are **not supplied by EEA**. A separate body reference can
automatically select a generic estimated preset; missing/ambiguous matches ask
for a body-style choice. All physical assumptions remain labelled and editable.
Hybrid battery state and energy recovery are not simulated. Saving a selected
entry stores a local draft's approved inputs; merely searching/selecting does
not replace the saved vehicle.

## Body-class enrichment (v0.5)

`data/epa2024-body-classes.json` retains 1,277 public 2024 EPA/DOE FuelEconomy.gov
records, restricted to ID, make, model, base model, size class and model year.
Downloaded 2026-10-03; the complete ZIP hash and source URL are in the JSON.
The [EPA licence](https://edg.epa.gov/EPA_Data_License.html) places EPA-produced
data in the US public domain and provides no warranty. The official
[dataset inventory](https://catalog.data.gov/dataset/fuel-economy-label-and-cafe-data-inventory)
links that licence and FuelEconomy.gov. No paid API or account is needed.

Reproduce the extraction from the official downloaded ZIP with
`python3 -m catalogue.import_body_styles vehicles.csv.zip`. Only exact normalized
make/commercial-model or base-model names match; repeated make prefixes are
removed; XC90 type L also accepts explicit slash-separated engine/transmission
suffixes. Conflicting categories and unsupported classes remain null. A separate
manufacturer classification covers Renault Clio V only when EEA type is RJA
([Renault source](https://www.press.renault.co.uk/releases/2612)).

EPA size classes describe US vehicles and use interior volume/GVWR definitions;
they are a **name-level body estimate**, not verification of a European trim or
its drag/efficiency. No US engine, fuel, weight or consumption values are copied.
The EEA facts remain intact (including European XC90 B5 diesel). Mapping logic
is explicit in `body_style.py`; 5,420 of the 39,717 current entries receive a
body estimate, and the rest retain the manual fallback. The API adds nullable
`body_style` (`small_car`, `saloon`, `suv`, `large_suv`) and separate
`body_style_source` provenance. Older clients may ignore these additive fields.

## Build and serve

From the repository root:

```sh
python3 -m catalogue.import_eea --raw-json catalogue/data/eea2024-technical-groups.json.gz
python3 -m unittest catalogue.test_catalogue -v
```

The importer still verifies the source row count online. The output defaults to
`~/.local/share/eco-route/catalogue/eea2024.sqlite3`. Set
`ECO_ROUTE_VEHICLE_DB` for a different database path. Publication uses an atomic
replace only after validation and SQLite integrity checks. Search opens the
snapshot read-only and never logs car queries. Saved user vehicles stay on the
Android device; this database contains public reference data only.

API body: `query` (2–80 characters), optional `fuel_type` (`diesel`/`petrol`),
`limit` (1–30), `offset` (0–100000). Response includes `vehicles`, `total_matches`,
`next_offset`, `coverage`, `source` and snapshot provenance. Each entry includes
source facts and `supported_for_model`. HTTP 422 means invalid input; HTTP 503
means unavailable catalogue (manual entry remains available).

## Android offline export (0.9.0)

Run `python3 -m catalogue.export_android`. It performs no network calls and derives
all 39,717 records and body estimates from the reviewed gzip/EPA inputs. It uses
Android-compatible FTS4 rather than the website's FTS5. Search prefixes, accent
normalization, support gating, ordering and pagination retain the same contract.
The exported database contains public reference facts only. Saved cars and search
queries are not embedded. Asset path:
`android/app/src/main/assets/catalogue/eea2024-v1.sqlite.gzip`.
