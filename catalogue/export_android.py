"""Reproducible public-only FTS4 catalogue for Android (no online import required)."""
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
from .import_eea import record
from .body_style import enrich

ROOT = Path(__file__).resolve().parents[1]

def export(destination):
    source = ROOT / 'catalogue/data/eea2024-technical-groups.json.gz'
    raw = gzip.decompress(source.read_bytes())
    rows = json.loads(raw)['results']
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'catalogue.db'
        with sqlite3.connect(path) as db:
            db.executescript('''
                PRAGMA page_size=4096;
                CREATE TABLE vehicles(id TEXT UNIQUE NOT NULL, fuel_type TEXT NOT NULL,
                    supported INTEGER NOT NULL, frequency INTEGER NOT NULL, data TEXT NOT NULL);
                CREATE VIRTUAL TABLE search_index USING fts4(search_text, tokenize=unicode61);
                CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            ''')
            count = 0
            for row in rows:
                item = record(row)
                if item is None:
                    continue
                vehicle, words = item
                cursor = db.execute('INSERT INTO vehicles VALUES(?,?,?,?,?)',
                    (vehicle['id'], vehicle['fuel_type'], int(vehicle['supported_for_model']), row['n'],
                     json.dumps(enrich(vehicle), ensure_ascii=False, separators=(',', ':'))))
                db.execute('INSERT INTO search_index(rowid,search_text) VALUES(?,?)', (cursor.lastrowid, words))
                count += 1
            db.execute('INSERT INTO metadata VALUES(?,?)', ('snapshot', json.dumps({
                'entries':count, 'source_sha256':hashlib.sha256(raw).hexdigest(),
                'registration_year':2024, 'status':'Final', 'format':'Android FTS4 v1'})))
            assert count == 39717
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        destination.write_bytes(gzip.compress(path.read_bytes(), mtime=0))
    return {'entries':count, 'asset_sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),
            'size_bytes':destination.stat().st_size}

if __name__ == '__main__':
    print(json.dumps(export(ROOT / 'android/app/src/main/assets/catalogue/eea2024-v1.sqlite.gzip'), indent=2))
