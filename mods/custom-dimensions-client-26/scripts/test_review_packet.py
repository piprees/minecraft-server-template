#!/usr/bin/env python3
"""test_review_packet.py — unit tests for review-packet.py's facts table and scorecard.

Context: template-only rig tooling (stdlib only). Run: python3 -m unittest discover -s scripts -p 'test_*.py'
Usage: from mods/custom-dimensions-client-26, `python3 -m unittest discover -s scripts -p 'test_*.py'`.
Gotchas: loads review-packet.py by path, since its name is not an importable module name.
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('review_packet', os.path.join(HERE, 'review-packet.py'))
rp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rp)

MAPPING = {'terralith:birch_taiga': ('minecraft:birch_forest', 'rebuilt by us', 'Birch woods'),
           'terralith:rocky_mountains': ('adventure:alpine_scree', 'rebuilt by us', 'Scree'),
           'terralith:gone': (None, 'cut', 'No counterpart')}
OLD = {'type': 'multi_biome', 'noiseSettings': 'adventure:wide',
       'biomes': ['minecraft:snowy_plains', 'terralith:birch_taiga', {'id': 'terralith:rocky_mountains'}, 'terralith:gone',
                  'minecraft:ice_spikes', 'minecraft:frozen_ocean']}
NEW = {'biomes': [{'id': 'minecraft:snowy_plains'}, {'id': 'minecraft:birch_forest'}, {'id': 'adventure:alpine_scree'},
                  {'id': 'minecraft:ice_spikes'}, {'id': 'minecraft:desert'}]}
DOSSIER = {
    'surface': {'biomesPercent': {'minecraft:desert': 50.0, 'minecraft:snowy_plains': 30.0, 'minecraft:birch_forest': 15.0,
                                  'minecraft:ice_spikes': 5.0},
                'snowIcePercent': 33.0, 'fluidCoverPercent': {'water': 2.0, 'lava': 0.0}, 'voidPercent': 0.0, 'canopyPercent': 10.0},
    'terrain': {'reliefMedian': 21.0, 'surfaceHeight': {'p50': 70}},
    'vertical': {'layerColumnsPercent': {'sky': 0.2, 'surface': 100.0, 'underground': 99.0, 'roof': 0.0}},
    'biomeExpression': {'listedNotFound': ['adventure:alpine_scree'], 'biomes': {
        'minecraft:desert': {'columns': 500, 'state': 'expressed', 'flags': []},
        'minecraft:snowy_plains': {'columns': 300, 'state': 'expressed', 'flags': []},
        'minecraft:birch_forest': {'columns': 150, 'state': 'placed, not expressed', 'flags': ['mostly water']},
        'minecraft:ice_spikes': {'columns': 50, 'state': 'placed, not expressed', 'flags': ['features name trees']}}},
}


class FactsTest(unittest.TestCase):
    def setUp(self):
        self.f = rp.facts('the_test', OLD, NEW, DOSSIER, MAPPING)
        self.rows = {r['old']: r for r in self.f['oldBiomes']}

    def test_every_old_biome_has_a_row_through_the_mapping(self):
        self.assertEqual([r['row'] for r in self.f['oldBiomes']], ['O1', 'O2', 'O3', 'O4', 'O5', 'O6'])
        self.assertEqual(self.rows['terralith:birch_taiga']['v2'], 'minecraft:birch_forest')
        self.assertEqual(self.rows['terralith:birch_taiga']['surfacePercent'], 15.0)

    def test_placed_unexpressed_is_present_with_its_flag_not_absent(self):
        r = self.rows['terralith:birch_taiga']
        self.assertEqual(r['state'], 'placed, not expressed')
        self.assertEqual(r['flagReason'], 'mostly water')
        self.assertEqual(r['columns'], 150)

    def test_bad_paths_carry_their_reason(self):
        self.assertEqual(self.rows['terralith:rocky_mountains']['state'], 'listed, not found')
        self.assertEqual(self.rows['terralith:gone']['state'], 'no v2 id')
        self.assertEqual(self.rows['terralith:gone']['flagReason'], 'cut')
        self.assertEqual(self.rows['minecraft:frozen_ocean']['state'], 'not in v2 definition')

    def test_v2_biomes_no_old_biome_maps_to(self):
        self.assertEqual([(r['row'], r['v2'], r['surfacePercent']) for r in self.f['v2Only']], [('V1', 'minecraft:desert', 50.0)])

    def test_scorecard(self):
        s = self.f['scorecard']
        self.assertEqual(s['a'], 50.0)
        self.assertEqual(s['b'], round(100 / 6, 1))
        self.assertEqual(s['c'], 2)
        self.assertEqual(s['d'], {'surface': {'percent': 100.0, 'present': True}, 'underground': {'percent': 99.0, 'present': True}})

    def test_type_layers_mark_a_missing_layer_absent(self):
        f = rp.facts('the_isles', dict(OLD, type='sky_islands'), NEW, DOSSIER, MAPPING)
        self.assertEqual(f['scorecard']['d'], {'sky': {'percent': 0.2, 'present': False}})
        base = rp.facts('the_nether', {}, NEW, DOSSIER, MAPPING)
        self.assertEqual(sorted(base['scorecard']['d']), ['roof', 'surface'])
        self.assertIsNone(base['scorecard']['a'])
        self.assertIsNone(base['scorecard']['b'])

    def test_facts_section_states_the_rule_and_every_row(self):
        text = '\n'.join(rp.facts_section(self.f))
        self.assertIn(rp.RULE, text)
        for row in ('| O1 |', '| O6 |', '| V1 |', 'M:snow', 'S:a', 'S:d'):
            self.assertIn(row, text)


if __name__ == '__main__':
    unittest.main()
