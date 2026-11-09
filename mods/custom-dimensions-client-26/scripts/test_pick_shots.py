#!/usr/bin/env python3
"""test_pick_shots.py — unit tests for pick-shots.py's night-vision choice, alternates and structure labels.

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


class AlternatesTest(unittest.TestCase):
    def shots(self):
        d = dossier()
        d['places']['surface']['types'][0]['coordinates'].append({'camera': [16.5, 71, 0.5], 'yaw': 90.0, 'pitch': 5.0})
        d['places']['vista']['summit'] = [800, 90, 0]
        d['places']['vista']['lookAt'] = [960, 60, 0]
        d['clusters'] = {'types': {'minecraft:waxed_cut_copper': {'distinctness': 0.9, 'clusters': 3, 'patches': 2, 'sizeP50': 3, 'places': [
            {'layer': 'underground', 'at': [10, 0, 0], 'camera': [4.5, 2, 0.5]}]}}}
        return {s['name'].split('the_x-', 1)[1]: s for s in ps.pick(d)}

    def test_every_shot_has_two_or_three_alternates(self):
        for name, s in self.shots().items():
            self.assertTrue(2 <= len(s['alternates']) <= 3, name)

    def test_other_cells_come_before_other_angles(self):
        alts = self.shots()['surface-t1']['alternates']
        self.assertEqual(alts[0]['x'], 16.5)
        self.assertEqual(alts[1], {'yaw': 180, 'pitch': 5})

    def test_the_far_view_turns_inward_first(self):
        alts = self.shots()['far']['alternates']
        self.assertEqual(alts[0]['at'], [640, 60, 0])
        self.assertEqual(len(alts), 3)

    def test_a_crafted_cluster_is_labelled_a_structure(self):
        s = self.shots()['structure-waxed_cut_copper']
        self.assertIn('a structure', s['why'])
        self.assertEqual(s['alternates'][0]['at'], [-2, 0, 0])


if __name__ == '__main__':
    unittest.main()
