"""Extract only the public 2024 EPA name/size-class reference from a downloaded ZIP."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import zipfile


def extract(path):
    raw = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        rows = csv.DictReader(io.StringIO(archive.read('vehicles.csv').decode('utf-8-sig')))
        fields = ('id', 'make', 'model', 'baseModel', 'VClass', 'year')
        vehicles = [{k: r[k] for k in fields} for r in rows if r['year'] == '2024']
    if not vehicles or any(not row[k] for row in vehicles for k in fields):
        raise ValueError('Incomplete body-class reference')
    vehicles.sort(key=lambda r: int(r['id']))
    return dict(source_url='https://www.fueleconomy.gov/feg/epadata/vehicles.csv.zip',
                license_url='https://edg.epa.gov/EPA_Data_License.html',
                source_zip_sha256=hashlib.sha256(raw).hexdigest(),
                model_year=2024, vehicles=vehicles)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip', type=Path)
    parser.add_argument('--output', type=Path, default=Path(__file__).parent / 'data/epa2024-body-classes.json')
    args = parser.parse_args()
    args.output.write_text(json.dumps(extract(args.zip), ensure_ascii=False, indent=2) + '\n')
