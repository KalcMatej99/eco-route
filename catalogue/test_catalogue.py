import copy
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from .import_eea import build, record
from .service import search, CatalogueInputError, CatalogueUnavailable
from .body_style import enrich

class CatalogueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'vehicles.db'
        self.rows = [dict(Mk='VOLVO', Cn='XC90', T='L', Va='LFK2', Ft='diesel', Fm='H', engine_cc=1969,
                          power_kw=173, mass_kg=2170, fc_min=6.8, fc_max=7.4, n=10),
                     dict(Mk='ŠKODA', Cn='OCTAVIA', T='NX', Va='A', Ft='petrol', Fm='M', engine_cc=None,
                          power_kw=None, mass_kg=None, fc_min=None, fc_max=None, n=2)]
        self.body = json.dumps({'results': self.rows}).encode()
        build(self.body, self.path, 12)

    def test_search_alias_accents_and_source_facts(self):
        found = search({'query': 'Volvo XC 90 B5'}, self.path)['vehicles'][0]
        self.assertEqual(found['mass_kg'], 2170)
        self.assertEqual(found['power_kw'], 173)
        self.assertEqual(found['fuel_type'], 'diesel')
        self.assertEqual(found['registration_year'], 2024)
        self.assertNotIn('model_year', found)
        self.assertEqual(found['source']['license'], 'CC BY 4.0')
        self.assertEqual(found['body_style'], 'large_suv')
        self.assertIn('EPA', found['body_style_source']['name'])
        self.assertIsNone(search({'query': 'skoda'}, self.path)['vehicles'][0]['mass_kg'])
        self.assertFalse(search({'query': 'skoda'}, self.path)['vehicles'][0]['supported_for_model'])
        self.assertEqual(search({'query': 'Volvo', 'fuel_type': 'petrol'}, self.path)['total_matches'], 0)
        self.assertEqual(search({'query': "Volvo' OR 1=1 --"}, self.path)['total_matches'], 0)

    def test_incomplete_import_does_not_replace_existing(self):
        before = self.path.read_bytes()
        with self.assertRaises(ValueError):
            build(self.body, self.path, 13)
        self.assertEqual(self.path.read_bytes(), before)

    def test_missing_or_implausible_data_not_invented(self):
        row = dict(self.rows[0], mass_kg=-1, fc_min=0, fc_max=7, power_kw=0)
        item, _ = record(row)
        self.assertIsNone(item['mass_kg'])
        self.assertIsNone(item['power_kw'])
        self.assertIsNone(item['wltp_l_per_100km'])
        self.assertIsNone(item['wltp_max_l_per_100km'])
        self.assertFalse(item['supported_for_model'])
        self.assertFalse(record(dict(self.rows[0], Fm='P'))[0]['supported_for_model'])
        self.assertIsNone(record(dict(self.rows[0], Cn='')))

    def test_input_validation_and_unavailable(self):
        for value in [{'query': 'a'}, {'query': 'Volvo', 'offset': -1}, {'query': 'Volvo','limit': True},
                      {'query': 'Volvo', 'fuel_type': 'electric'}, {'query': 'Volvo','model_year': 2024}]:
            with self.assertRaises(CatalogueInputError):search(value,self.path)
        with self.assertRaises(CatalogueUnavailable):search({'query':'Volvo'}, self.path.parent/'missing.db')

    def test_page_boundary_and_stable_identity(self):
        rows = [dict(self.rows[0], Va=str(i), n=i+1) for i in range(5)]
        build(json.dumps({'results': rows}).encode(), self.path, 15)
        first = search({'query':'Volvo', 'limit':2}, self.path)
        second = search({'query':'Volvo','limit':2,'offset':first['next_offset']}, self.path)
        third = search({'query':'Volvo','limit':2,'offset':second['next_offset']}, self.path)
        ids = [v['id'] for page in [first,second,third] for v in page['vehicles']]
        self.assertEqual(len(set(ids)), 5)
        self.assertIsNone(third['next_offset'])
        self.assertEqual(record(rows[0])[0]['id'],record(dict(rows[0],n=900))[0]['id'])

    def test_body_reference_never_guesses_unknown_or_ambiguous_names(self):
        for make, model in [('VOLVO', 'XC900'), ('Fiat', '500'), ('BMW', '4 Series'),
                            ('Land Rover', 'Range Rover'), ('Unknown', 'XC90')]:
            self.assertIsNone(enrich(dict(make=make, model=model))['body_style'], (make, model))
        self.assertEqual(enrich(dict(make='VOLVO', model='VOLVO XC90'))['body_style'], 'large_suv')
        self.assertEqual(enrich(dict(make='VOLVO', model='XC90 / 2.0 / TD / AUT. AWD', type='L'))['body_style'], 'large_suv')
        self.assertIsNone(enrich(dict(make='VOLVO', model='XC900 / 2.0', type='L'))['body_style'])
        self.assertEqual(enrich(dict(make='Renault', model='CLIO', type='RJA'))['body_style'], 'small_car')
        self.assertIsNone(enrich(dict(make='Renault', model='CLIO', type='OTHER'))['body_style'])

if __name__ == '__main__':unittest.main()
