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

    def test_dark_dimension_pairs_every_shot(self):
        shots = ps.pick(dossier(skylight=False))
        self.assertEqual({(s.get('pair'), s['nightVision']) for s in shots}, {(True, False)})

    def test_a_lit_dimension_takes_single_frames(self):
        self.assertFalse(any(s.get('pair') for s in ps.pick(dossier())))
        self.assertFalse(any(s.get('pair') for s in ps.pick(dossier(fixed=6000))))

    def test_the_piglin_attribute_alone_is_not_fog(self):
        self.assertFalse(any(s.get('pair') for s in ps.pick(dossier(skylight=True))))

    def test_a_dimension_held_at_night_is_paired(self):
        self.assertTrue(all(s.get('pair') for s in ps.pick(dossier(fixed=18000))))

    def test_fixed_fog_end_distance_is_paired(self):
        d = dossier(skylight=True, visual={'minecraft:visual/fog_end_distance': 96.0})
        self.assertEqual({(s.get('pair'), s['nightVision']) for s in ps.pick(d)}, {(True, False)})

    def test_nether_effects_in_the_definition_are_paired(self):
        self.assertTrue(all(s.get('pair') for s in ps.pick(dossier(effects='minecraft:the_nether'))))


class VoidFeatureTest(unittest.TestCase):
    def test_no_hole_marks_nothing(self):
        self.assertFalse(any(s.get('voidFeature') for s in ps.pick(dossier())))

    def test_a_hole_marks_the_far_view_and_downward_shots(self):
        d = dossier()
        d['landforms'] = {'voidHoles': {'count': 1, 'countOver1Ha': 1}}
        d['places']['vista']['lookAt'] = [50, 85, 0]
        d['places']['surface']['types'][0]['coordinates'] = [{'camera': [0.5, 70, 0.5], 'pitch': 45}]
        marked = {s['name'].split('the_x-', 1)[1] for s in ps.pick(d) if s.get('voidFeature')}
        self.assertEqual(marked, {'far', 'surface-t1'})

    def test_looks_down_reads_the_at_block(self):
        self.assertAlmostEqual(ps.looks_down({'x': 0.5, 'y': '100', 'z': 0.5, 'at': [0, 0, 101]}), 45.0, delta=1.5)


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


class BorderGeometryTest(unittest.TestCase):
    """The far view's border rule, against FrameCensus's ray grid and FrameGate.borderDistance."""
    R = 512.0

    def test_direction_is_minecrafts(self):
        for (pitch, yaw), want in {(0, 0): (0, 0, 1), (0, 90): (-1, 0, 0), (0, 180): (0, 0, -1), (90, 0): (0, -1, 0)}.items():
            for a, b in zip(ps.direction(pitch, yaw), want):
                self.assertAlmostEqual(a, b, places=9)
        self.assertAlmostEqual(ps.yaw_to(0, 0, -10, 0), 90)
        self.assertAlmostEqual(ps.yaw_to(0, 0, 0, 10), 0)

    def test_border_distance_is_the_gates(self):
        self.assertEqual(ps.border_distance(0, 0, 1, 0, 100), 100)
        self.assertAlmostEqual(ps.border_distance(50, 0, -1, 0, 100), 150)
        self.assertAlmostEqual(ps.border_distance(0, 0, 0.6, 0.8, 100), 125)
        self.assertEqual(ps.border_distance(150, 0, 1, 0, 100), 0, 'a camera outside the border')
        self.assertEqual(ps.border_distance(0, 0, 0, 0, 100), float('inf'))

    def test_a_summit_on_the_border_looking_along_it_fails(self):
        self.assertGreater(ps.border_share(-3775.5, 297, -4095.5, ps.yaw_to(-3775.5, -4095.5, -3615.5, -4095.5), 40, 4096, self.R), ps.BORDER_MAX)

    def test_straight_down_from_the_centre_clears_a_border_past_the_frames_corner_rays(self):
        # the frame's corner rays lean 55 degrees from vertical, so a missed ray travels 0.82 of the render distance across
        self.assertEqual(ps.border_share(0, 300, 0, 0, 90, 440, self.R), 0)
        self.assertGreater(ps.border_share(0, 300, 0, 0, 90, 380, self.R), ps.BORDER_MAX)

    def test_a_ring_camera_facing_the_centre_passes_a_small_sky_world(self):
        for half in (512, 683):
            x, z = ps.on_ring(90, half)
            self.assertAlmostEqual(z, half - ps.RING_INSET)
            share = ps.border_share(x, 300, z, ps.yaw_to(x, z, 0, 0), ps.RING_PITCH, half, self.R)
            self.assertLessEqual(share, ps.BORDER_MAX, half)
        x, z = ps.on_ring(45, 512)
        self.assertIsNone(ps.least_pitch(x, 300, z, ps.yaw_to(x, z, 0, 0), 512, self.R, ps.RING_PITCH),
                          'a corner of the border sees both edges beside it')

    def sky(self, summit, look):
        d = dossier()
        d['places']['vista'] = {'summit': summit, 'lookAt': look}
        d['dimensionType'] = {'ceiling': False, 'minY': -64, 'height': 384}
        d['structures'] = {'census': {'note': 'sites from the structure placement over the 512-block border whose biome'}}
        return d

    def far(self, shots):
        return next(s for s in shots if s['name'].endswith('-far'))

    def test_a_far_view_the_border_would_reject_moves_to_the_ring(self):
        f = self.far(ps.pick(self.sky([-300, 200, -500], [-140, 60, -500])))
        self.assertNotIn('at', f)
        self.assertEqual(f['y'], '240')
        self.assertGreater(f['z'], 400, 'the ring point opposite the summit, which lies to the north')
        self.assertLessEqual(ps.border_share(f['x'], 240 + ps.EYE, f['z'], f['yaw'], f['pitch'], 512, self.R), ps.BORDER_MAX)
        for a in f['alternates']:
            if 'at' not in a:
                self.assertLessEqual(ps.border_share(a['x'], float(a['y']) + ps.EYE, a['z'], a['yaw'], a['pitch'], 512, self.R), ps.BORDER_MAX)
        self.assertEqual(len(f['alternates']), ps.ALTERNATES)

    def test_a_far_view_that_passes_keeps_the_summit(self):
        f = self.far(ps.pick(self.sky([0, 200, 0], [0, 60, 160])))
        self.assertEqual(f['at'], [0, 60, 160], 'from the centre facing 160 blocks off, the border lies past every ray')

    def test_a_border_too_small_for_the_render_distance_keeps_the_summit_and_says_why(self):
        f = self.far(ps.pick(self.sky([-100, 200, -200], [60, 60, -200]), border=200))
        self.assertIn('at', f)
        self.assertIn('lower --render-distance', f['why'])
        f = self.far(ps.pick(self.sky([-100, 200, -200], [60, 60, -200]), border=200, render_distance=8))
        self.assertNotIn('at', f, 'at a render distance of 8 the ring passes')

    def test_no_border_known_leaves_the_far_view_alone(self):
        self.assertEqual(self.far(ps.pick(dossier()))['at'], [50, 60, 0])

    def test_the_top_of_the_world_caps_the_camera(self):
        f = self.far(ps.pick(self.sky([-300, 300, -500], [-140, 60, -500])))
        self.assertEqual(f['y'], str(320 - 2))


if __name__ == '__main__':
    unittest.main()
