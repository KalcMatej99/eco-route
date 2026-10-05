"""Conservative name-level body estimates; never substitutes engine or mass facts."""
from functools import lru_cache
import json
from pathlib import Path
import re
import unicodedata

REFERENCE = Path(__file__).parent / 'data' / 'epa2024-body-classes.json'
EPA_SOURCE = {
    'name': 'EPA 2024 size-class reference · body estimate',
    'url': 'https://www.fueleconomy.gov/feg/download.shtml',
    'license': 'US EPA public-domain data',
    'license_url': 'https://edg.epa.gov/EPA_Data_License.html',
}


def normalize(value):
    value = unicodedata.normalize('NFKD', value).encode('ascii', 'ignore').decode().lower()
    return re.sub(r'[^a-z0-9]+', ' ', value).strip()


def category(value):
    if value in ('Minicompact Cars', 'Subcompact Cars'):
        return 'small_car'
    if value in ('Compact Cars', 'Midsize Cars', 'Large Cars', 'Small Station Wagons', 'Midsize Station Wagons'):
        return 'saloon'
    if value.startswith('Small Sport Utility Vehicle'):
        return 'suv'
    if value.startswith(('Standard Sport Utility Vehicle', 'Minivan')):
        return 'large_suv'
    # Pickups, two-seaters and special-purpose vehicles have no suitable preset.
    return None


@lru_cache(maxsize=1)
def reference_index():
    index = {}
    for row in json.loads(REFERENCE.read_text())['vehicles']:
        for model in {row['model'], row['baseModel']}:
            key = (normalize(row['make']), normalize(model))
            index.setdefault(key, set()).add(category(row['VClass']))
    return index


def enrich(vehicle):
    """Return a copy with an optional estimate and separate provenance.

    Require exact normalized names, including an optional repeated make prefix.
    A shared base-model name is accepted only when all reference variants resolve
    to one app category. Missing or conflicting categories remain unanswered.
    """
    result = dict(vehicle, body_style=None, body_style_source=None)
    make, model = normalize(vehicle['make']), normalize(vehicle['model'])
    model = model.removeprefix(make + ' ')
    # EEA also stores explicit engine/transmission suffixes separated by '/'.
    # Restrict this alias to the XC90 type L, not arbitrary prefix matching.
    if make == 'volvo' and vehicle.get('type') == 'L' and '/' in vehicle['model']:
        family = normalize(vehicle['model'].split('/', 1)[0]).removeprefix('volvo ')
        if family == 'xc90':
            model = family
    if make == 'volkswagen':
        model = model.removeprefix('vw ')
    if make == 'renault' and model == 'clio' and vehicle.get('type') == 'RJA':
        result.update(body_style='small_car', body_style_source={
            'name': 'Renault Clio V · body estimate',
            'url': 'https://www.press.renault.co.uk/releases/2612',
            'license': 'Manufacturer model classification',
        })
        return result
    candidates = reference_index().get((make, model), set())
    if len(candidates) == 1 and None not in candidates:
        result.update(body_style=next(iter(candidates)), body_style_source=EPA_SOURCE)
    return result
