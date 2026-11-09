#!/usr/bin/env python3
"""test_check_targets.py — unit tests for check-targets.py.

Context: template-only rig tooling (stdlib only).
Usage: from mods/custom-dimensions-client-26, `python3 -m unittest discover -s scripts -p 'test_*.py'`.
Gotchas: loads check-targets.py by path, since its name is not an importable module name.
"""
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('check_targets', os.path.join(HERE, 'check-targets.py'))
ct = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ct)

DOSSIER = {
    'surface': {'snowIcePercent': 31.5, 'voidPercent': 0.0,
                'topBlocksPercent': {'minecraft:grass_block': 54.7, 'minecraft:short_grass': 12.8, 'minecraft:water': 17.6},
                'biomesPercent': {'minecraft:plains': 35.3}},
    'terrain': {'surfaceHeight': {'p50': 39.0, 'p95': 58.0}, 'reliefMedian': 15.0},
    'vegetation': {'treesPerHectare': 9.7, 'species': {'minecraft:oak_log': {'count': 21, 'perHectare': 3.0}}},
    'clusters': {'types': {'minecraft:farmland': {'clusters': 3, 'sizeP50': 38.0}}},
    'structures': {},
    'plan': {'hectares': 6.9},
    'notable': {'surface': {'minecraft:glow_lichen': 69}},
    'landforms': {'zones': [{'index': 0, 'depthBelowRing': 24.0}]},
    'kind': 'survey',
}


def t(key, op, value):
    return {'key': key, 'op': op, 'value': value, 'from': 'test'}


class CheckTargetsTest(unittest.TestCase):
    def test_pass_and_fail_with_measured_value(self):
        r = ct.check(DOSSIER, [t('surface.snowIcePercent', '>=', 70), t('vegetation.treesPerHectare', '<=', 10)])
        self.assertEqual([x['pass'] for x in r], [False, True])
        self.assertEqual(r[0]['measured'], 31.5)
        self.assertIn('FAIL surface.snowIcePercent >= 70: 31.5', ct.fmt(r[0]))

    def test_sum_of_paths(self):
        key = 'surface.topBlocksPercent.minecraft:grass_block + surface.topBlocksPercent.minecraft:short_grass'
        r = ct.check(DOSSIER, [t(key, '>=', 67)])
        self.assertAlmostEqual(r[0]['measured'], 67.5)
        self.assertTrue(r[0]['pass'])

    def test_between_is_inclusive(self):
        r = ct.check(DOSSIER, [t('terrain.reliefMedian', 'between', [8, 15]), t('terrain.reliefMedian', 'between', [16, 30])])
        self.assertEqual([x['pass'] for x in r], [True, False])
        self.assertIn('16-30', ct.fmt(r[1]))

    def test_missing_entry_in_numeric_map_is_zero(self):
        r = ct.check(DOSSIER, [t('surface.topBlocksPercent.minecraft:pumpkin', '<=', 5)])
        self.assertEqual(r[0]['measured'], 0.0)
        self.assertTrue(r[0]['pass'])
        self.assertIn('not listed', r[0]['note'])

    def test_missing_entry_in_map_of_records_is_zero(self):
        r = ct.check(DOSSIER, [t('clusters.types.minecraft:magma_block.clusters', '>=', 5),
                               t('vegetation.species.minecraft:spruce_log.perHectare', '<=', 1)])
        self.assertEqual([x['measured'] for x in r], [0.0, 0.0])
        self.assertEqual([x['pass'] for x in r], [False, True])

    def test_empty_map_entry_is_zero(self):
        self.assertEqual(ct.resolve(DOSSIER, 'structures.minecraft:village')[0], 0.0)
        with self.assertRaises(ct.TargetError):
            ct.resolve(DOSSIER, 'surface.topBlocksPercent.water')

    def test_list_index_and_per(self):
        r = ct.check(DOSSIER, [t('landforms.zones.0.depthBelowRing', '>=', 20),
                               dict(t('notable.surface.minecraft:glow_lichen', '>=', 5), per='plan.hectares')])
        self.assertEqual([x['measured'] for x in r], [24.0, 10.0])
        self.assertIn('per plan.hectares', r[1]['note'])
        with self.assertRaises(ct.TargetError):
            ct.check(DOSSIER, [t('landforms.zones.1.depthBelowRing', '>=', 20)])
        with self.assertRaises(ct.TargetError):
            ct.check(DOSSIER, [dict(t('terrain.reliefMedian', '>=', 1), per='structures.minecraft:x')])

    def test_unknown_key_is_an_error(self):
        for key in ('surface.snowOrIce', 'trees.perHa', 'clusters.types.minecraft:farmland.bogus',
                    'terrain.surfaceHeight.p99x.y'):
            with self.assertRaises(ct.TargetError, msg=key):
                ct.check(DOSSIER, [t(key, '>=', 1)])

    def test_non_number_and_bad_op_are_errors(self):
        with self.assertRaises(ct.TargetError):
            ct.check(DOSSIER, [t('kind', '>=', 1)])
        with self.assertRaises(ct.TargetError):
            ct.check(DOSSIER, [t('terrain.reliefMedian', '=>', 1)])
        with self.assertRaises(ct.TargetError):
            ct.check(DOSSIER, [t('terrain.reliefMedian', 'between', 5)])

    def test_main_exit_codes_and_json(self):
        with tempfile.TemporaryDirectory() as d:
            dp, tp, jp = (os.path.join(d, n) for n in ('d.json', 't.json', 'o.json'))
            json.dump(DOSSIER, open(dp, 'w'))
            json.dump({'slug': 'x', 'targets': [t('terrain.surfaceHeight.p95', '>=', 50)]}, open(tp, 'w'))
            self.assertEqual(ct.main(['c', dp, tp, '--json', jp]), 0)
            self.assertEqual(json.load(open(jp))['pass'], 1)
            json.dump({'slug': 'x', 'targets': [t('terrain.surfaceHeight.p95', '>=', 200)]}, open(tp, 'w'))
            self.assertEqual(ct.main(['c', dp, tp]), 1)
            json.dump({'slug': 'x', 'targets': [t('terrain.nope', '>=', 200)]}, open(tp, 'w'))
            self.assertEqual(ct.main(['c', dp, tp]), 2)


if __name__ == '__main__':
    unittest.main()
