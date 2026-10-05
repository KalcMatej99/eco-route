"""Read-only local catalogue search. No remote requests or user query logging."""
import json
from contextlib import closing
import os
from pathlib import Path
import re
import sqlite3
import unicodedata
from .body_style import enrich

DEFAULT_DB = Path.home() / '.local/share/eco-route/catalogue/eea2024.sqlite3'
COVERAGE = ('EEA final 2024 new-car registrations in the EU, Iceland and Norway. '
            'Petrol and diesel, including non-plug-in hybrids. Not a model-year or exhaustive car catalogue. '
            'Electric and plug-in cars are not supported by this fuel model.')
SOURCE = {
    'name': 'EEA / DG CLIMA · 2024 final registrations',
    'url': 'https://www.eea.europa.eu/en/datahub/datahubitem-view/fa8b1229-3db6-495d-b18e-9c9b3267c02b',
    'license': 'CC BY 4.0',
    'license_url': 'https://creativecommons.org/licenses/by/4.0/',
    'copyright': 'Directorate-General for Climate Action (DG CLIMA)',
    'metadata_url': 'https://sdi.eea.europa.eu/catalogue/srv/api/records/5018ec17-2348-4c92-8761-6f2377bbd1c0/formatters/xml',
}

class CatalogueInputError(ValueError):
    pass

class CatalogueUnavailable(RuntimeError):
    pass

def tokens(value):
    value = ''.join(c for c in unicodedata.normalize('NFKD', value.casefold()) if not unicodedata.combining(c))
    # Split punctuation and letter/digit boundaries: XC90 == XC 90, B5 == B 5.
    value = re.sub(r'([^\W\d_])(\d)|(\d)([^\W\d_])', lambda m: ' '.join(x for x in m.groups() if x), value)
    return re.findall(r'[^\W_]+', value, flags=re.UNICODE)

def search(body, db_path=None):
    query = body.get('query')
    limit = body.get('limit', 30)
    fuel = body.get('fuel_type')
    offset = body.get('offset', 0)
    if not isinstance(query, str) or not 2 <= len(query.strip()) <= 80:
        raise CatalogueInputError('Enter between 2 and 80 characters to search for a car.')
    parts = tokens(query)
    if not parts or len(parts) > 12:
        raise CatalogueInputError('Enter a shorter make, model or engine search.')
    if type(limit) is not int or not 1 <= limit <= 30:
        raise CatalogueInputError('The result limit must be between 1 and 30.')
    if type(offset) is not int or not 0 <= offset <= 100000:
        raise CatalogueInputError('Invalid result offset.')
    if fuel not in (None, 'diesel', 'petrol'):
        raise CatalogueInputError('Choose diesel or petrol.')
    if body.get('model_year') is not None:
        raise CatalogueInputError('This catalogue records registration year, not model year.')
    path = Path(db_path or os.environ.get('ECO_ROUTE_VEHICLE_DB', DEFAULT_DB))
    if not path.is_file():
        raise CatalogueUnavailable('Car search is unavailable. You can still enter your vehicle manually.')
    match = ' AND '.join('"' + part + '"*' for part in parts)
    where = 'search_index MATCH ?' + (' AND v.fuel_type = ?' if fuel else '')
    args = [match] + ([fuel] if fuel else [])
    try:
        with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=2)) as db:
            total = db.execute('SELECT COUNT(*) FROM search_index JOIN vehicles v ON v.rowid=search_index.rowid WHERE ' + where, args).fetchone()[0]
            rows = db.execute('SELECT v.data FROM search_index JOIN vehicles v ON v.rowid=search_index.rowid WHERE ' + where + ' ORDER BY v.supported DESC, v.frequency DESC, v.id LIMIT ? OFFSET ?', args + [limit, offset]).fetchall()
            meta = json.loads(db.execute("SELECT value FROM metadata WHERE key='snapshot'").fetchone()[0])
    except (sqlite3.Error, TypeError, ValueError) as exc:
        raise CatalogueUnavailable('Car search is unavailable. You can still enter your vehicle manually.') from exc
    return {'vehicles': [enrich(json.loads(row[0])) for row in rows], 'total_matches': total,
            'truncated': total > offset + limit, 'next_offset': offset + limit if total > offset + limit else None, 'offset': offset, 'coverage': COVERAGE, 'source': SOURCE,
            'snapshot': meta}
