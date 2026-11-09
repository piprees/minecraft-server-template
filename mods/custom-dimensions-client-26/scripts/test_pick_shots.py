#!/usr/bin/env python3
"""test_pick_shots.py — unit tests for pick-shots.py's night-vision choice.

Context: template-only rig tooling (stdlib only).
Usage: from mods/custom-dimensions-client-26, `python3 -m unittest discover -s scripts -p 'test_*.py'`.
Gotchas: loads pick-shots.py by path, since its name is not an importable module name.
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('pick_shots', os.path.join(HERE, 'pick-shots.py'))
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)

PIRATE = 'minecraft:gameplay/nether_portal_spawns_piglin'


def dossier(skylight=True, visual=None, effects=None, fixed=None):
    attrs = {PIRATE: True}
    attrs.update(visual or {})
    atmosphere = {'hasSkyLight': skylight, 'registeredType': {'attributes': attrs}}
    if effects or fixed is not None:
        atmosphere['definitionAtmosphere'] = {k: v for k, v in (('effects', effects), ('fixedTime', fixed)) if v is not None}
    cell = {'type': 1, 'sharePercent': 60, 'distinctness': 0.2, 'description': 'd', 'coordinates': [{'camera': [0.5, 70, 0.5]}]}
    return {'dimension': 'adventure:the_x', 'atmosphere': atmosphere, 'dimensionType': {'ceiling': False},
            'places': {'vista': {'summit': [0, 90, 0], 'lookAt': [50, 60, 0]}, 'surface': {'types': [cell]}, 'underground': {'types': [dict(cell, type=2)]}}}


def nv(shots):
    return {s['name'].split('the_x-', 1)[1]: s['nightVision'] for s in shots}


class NightVisionTest(unittest.TestCase):
    def test_lit_overworld_lights_underground_only(self):
        self.assertEqual(nv(ps.pick(dossier())), {'far': False, 'surface-t1': False, 'underground-t2': True})

    def test_dark_dimension_without_fog_lights_everything(self):
        self.assertEqual(set(nv(ps.pick(dossier(skylight=False))).values()), {True})

    def test_the_piglin_attribute_alone_is_not_fog(self):
        self.assertTrue(nv(ps.pick(dossier(skylight=False)))['far'])

    def test_a_dimension_held_at_night_is_dark(self):
        self.assertEqual(set(nv(ps.pick(dossier(fixed=18000))).values()), {True})
        self.assertEqual(nv(ps.pick(dossier(fixed=6000)))['far'], False)

    def test_fixed_fog_end_distance_is_fog_bound(self):
        d = dossier(skylight=False, visual={'minecraft:visual/fog_end_distance': 96.0})
        self.assertEqual(set(nv(ps.pick(d)).values()), {False})

    def test_nether_effects_in_the_definition_are_fog_bound(self):
        self.assertEqual(set(nv(ps.pick(dossier(skylight=False, effects='minecraft:the_nether'))).values()), {False})


if __name__ == '__main__':
    unittest.main()
