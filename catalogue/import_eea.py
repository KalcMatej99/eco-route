"""Build the free EEA 2024 final catalogue; atomically replace a verified snapshot.

python3 -m catalogue.import_eea [--raw-json previously-downloaded.json]
The immutable table name is deliberate. New years/statuses require a reviewed import.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import gzip
import json
import math
from pathlib import Path
import sqlite3
import tempfile
import urllib.parse
import urllib.request

from .service import DEFAULT_DB, SOURCE, tokens

TABLE = '[CO2Emission].[latest].[co2cars_2024Fv30]'
QUERY = ("SELECT [Mk],[Cn],[T],[Va],[Ft],[Fm],[Ec (cm3)] AS engine_cc,[Ep (KW)] AS power_kw,"
         "[M (kg)] AS mass_kg,MIN([Fc]) AS fc_min,MAX([Fc]) AS fc_max,COUNT(*) AS n FROM " + TABLE +
         " WHERE [Ft] IN ('diesel','petrol') GROUP BY [Mk],[Cn],[T],[Va],[Ft],[Fm],[Ec (cm3)],[Ep (KW)],[M (kg)] ORDER BY n DESC")
COUNT_QUERY = "SELECT COUNT(*) AS n FROM " + TABLE + " WHERE [Ft] IN ('diesel','petrol')"
VOLVO_SOURCE = 'https://www.volvocars.com/files/cs/v3/assets/blt0feaa88e629251fc/bltae8b2f62e4d70686/66eb042044ba3379a77f646e/xc90-my24-26-10-2023.pdf?branch=prod_alias'

def download(query):
    url = 'https://discodata.eea.europa.eu/sql?' + urllib.parse.urlencode({'query': query, 'p': 1, 'nrOfHits': 100000})
    req = urllib.request.Request(url, headers={'User-Agent': 'EcoRoute-catalogue/1.0 (private pilot; EEA attribution retained)'})
    with urllib.request.urlopen(req, timeout=60) as response:
        body = response.read(32 * 1024 * 1024 + 1)
    if len(body) > 32 * 1024 * 1024:
        raise ValueError('Source exceeded the bounded download size')
    parsed = json.loads(body)
    if not isinstance(parsed.get('results'), list):
        raise ValueError('EEA query failed; no snapshot replaced')
    return body, parsed['results']

def number(value, low, high):
    return value if type(value) in (int, float) and math.isfinite(value) and low <= value <= high else None

def text(value):
    return ' '.join(value.split()) if isinstance(value, str) else ''

def record(raw):
    make, model = text(raw.get('Mk')), text(raw.get('Cn'))
    if not make or not model:
        return None
    fuel = text(raw.get('Ft')).lower()
    mode = text(raw.get('Fm')).upper()
    mass = number(raw.get('mass_kg'), 600, 6000)
    power = number(raw.get('power_kw'), 1, 1500)
    engine = number(raw.get('engine_cc'), 100, 15000)
    supported = fuel in ('diesel', 'petrol') and mode in ('M', 'H') and mass is not None
    name = model if model.casefold().startswith(make.casefold() + ' ') else f'{make} {model}'
    # Preserve the actual grouping key, including unknown values, in stable identity.
    key = [raw.get(k) for k in ('Mk', 'Cn', 'T', 'Va', 'Ft', 'Fm', 'engine_cc', 'power_kw', 'mass_kg')]
    identifier = 'eea2024-' + hashlib.sha256(json.dumps(key, ensure_ascii=False).encode()).hexdigest()[:24]
    fcmin = number(raw.get('fc_min'), 1, 40)
    fcmax = number(raw.get('fc_max'), 1, 40)
    # An implausible endpoint cannot be silently converted to a misleading range.
    if fcmin is None or fcmax is None or fcmin > fcmax:
        fcmin = fcmax = None
    result = dict(id=identifier, make=make, model=model, display_name=name[:80],
                  type=text(raw.get('T')), variant=text(raw.get('Va')), fuel_type=fuel,
                  fuel_mode=mode, registration_year=2024, mass_kg=mass,
                  mass_basis='Mass in running order, including a standard 75 kg driver',
                  power_kw=power, engine_cc=engine, wltp_l_per_100km=fcmin,
                  wltp_max_l_per_100km=fcmax, supported_for_model=supported,
                  unsupported_reason=None if supported else 'Missing or unsupported specifications. Enter your vehicle manually.',
                  source=SOURCE)
    aliases = ''
    if make.upper() == 'VOLVO' and 'XC90' in model.upper() and fuel == 'diesel' and power == 173 and engine == 1969:
        aliases = 'B5 D420T2'
        result['search_alias_source'] = VOLVO_SOURCE
    search_text = ' '.join(tokens(' '.join(str(v or '') for v in [make, model, raw.get('T'), raw.get('Va'), fuel, power, engine, mass, aliases])))
    return result, search_text

def build(raw_bytes, destination, expected_rows):
    rows = json.loads(raw_bytes)['results']
    if not rows or len(rows) >= 100000 or sum(row['n'] for row in rows) != expected_rows:
        raise ValueError('Incomplete or truncated EEA response; no snapshot replaced')
    destination = Path(destination).expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, suffix='.sqlite3', delete=False) as f:
        temporary = Path(f.name)
    try:
        with sqlite3.connect(temporary) as db:
            db.executescript('''
              CREATE TABLE vehicles(id TEXT UNIQUE NOT NULL, fuel_type TEXT NOT NULL,
                supported INTEGER NOT NULL, frequency INTEGER NOT NULL, data TEXT NOT NULL);
              CREATE VIRTUAL TABLE search_index USING fts5(search_text, tokenize='unicode61 remove_diacritics 2');
              CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            ''')
            skipped = 0
            for row in rows:
                normalized = record(row)
                if normalized is None:
                    skipped += 1
                    continue
                entry, words = normalized
                cursor = db.execute('INSERT INTO vehicles VALUES(?,?,?,?,?)', (entry['id'], entry['fuel_type'], int(entry['supported_for_model']), row['n'], json.dumps(entry, ensure_ascii=False)))
                db.execute('INSERT INTO search_index(rowid,search_text) VALUES(?,?)', (cursor.lastrowid, words))
            count = db.execute('SELECT COUNT(*) FROM vehicles').fetchone()[0]
            meta = dict(table=TABLE, registration_year=2024, status='Final', imported_at=datetime.now(timezone.utc).isoformat(),
                        source_sha256=hashlib.sha256(raw_bytes).hexdigest(), source_rows=expected_rows,
                        source_groups=len(rows), entries=count, skipped_unnamed_groups=skipped,
                        transformations='Grouped identical make/model/type/variant/fuel/mode/engine/power/mass; reported consumption min/max; invalid numeric values kept unavailable.',
                        source=SOURCE)
            db.execute('INSERT INTO metadata VALUES(?,?)', ('snapshot', json.dumps(meta)))
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        db.close()
        temporary.chmod(0o600)
        temporary.replace(destination)
        return meta
    finally:
        temporary.unlink(missing_ok=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw-json', type=Path)
    parser.add_argument('--output', type=Path, default=DEFAULT_DB)
    args = parser.parse_args()
    body = (gzip.decompress(args.raw_json.read_bytes()) if args.raw_json.suffix == '.gz' else args.raw_json.read_bytes()) if args.raw_json else download(QUERY)[0]
    expected = download(COUNT_QUERY)[1][0]['n']
    meta = build(body, args.output, expected)
    # Retain the exact input and query for independent reconstruction and provenance.
    archive = args.output.parent / (meta['source_sha256'] + '.json')
    archive.write_bytes(body)
    archive.chmod(0o600)
    (args.output.parent / 'query.sql').write_text(QUERY + '\n')
    print(json.dumps(meta, indent=2))

if __name__ == '__main__':
    main()
