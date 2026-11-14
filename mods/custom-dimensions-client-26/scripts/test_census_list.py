#!/usr/bin/env python3
"""test_census_list.py — unit tests for census-list.py's record formats.

Context: template-only rig tooling (stdlib only).
Usage: from mods/custom-dimensions-client-26, `python3 -m unittest discover -s scripts -p 'test_*.py'`.
Gotchas: loads census-list.py by path, since its name is not an importable module name.
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('census_list', os.path.join(HERE, 'census-list.py'))
cl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cl)


class EntriesTest(unittest.TestCase):
    def test_terrain_pick_camera_keeps_yaw_pitch_and_far_radius(self):
        recs = [{'slug': 'the_x', 'shots': [{'kind': 'far', 'shows': 'old', 'file': 'f.png',
                                            'camera': {'x': 1.5, 'y': 80.0, 'z': 2.5, 'yaw': 10, 'pitch': 20}}]}]
        e = cl.entries(recs, None, 8)[0]
        self.assertEqual((e['name'], e['dimension'], e['y'], e['yaw'], e['radius']), ('the_x-far1-census', 'adventure:the_x', '80.0', 10, 24))

    def test_gallery_looking_at_becomes_at(self):
        recs = [{'biome': 'oasis', 'shots': [{'kind': 'eye', 'what': 'old', 'png': 'p', 'dimension': 'adventure:selftest_biome_oasis',
                                             'camera': [1, '70', 2], 'lookingAt': [5, 60, 5]}]}]
        e = cl.entries(recs, None, 8)[0]
        self.assertEqual(e['at'], [5, 60, 5])
        self.assertNotIn('yaw', e)

    def test_gallery_shot_takes_its_record_dimension_else_the_showcase(self):
        recs = [{'biome': 'dunes', 'dimension': 'adventure:selftest_biome_dunes', 'shots': [{'kind': 'wide', 'shows': 'a', 'camera': [0, 69, 0], 'yaw': 5, 'pitch': 15}]},
                {'biome': 'bare', 'shots': [{'kind': 'wide', 'shows': 'b', 'camera': [0, 69, 0]}]}]
        out = cl.entries(recs, None, 8)
        self.assertEqual([e['dimension'] for e in out], ['adventure:selftest_biome_dunes', 'adventure:selftest_biome_bare'])
        self.assertEqual((out[0]['yaw'], out[0]['pitch'], out[0]['caption']), (5, 15, 'a'))

    def test_first_gallery_batch_dict_format(self):
        recs = {'root': '/r', 'biomes': [{'biome': 'adventure:chalk', 'showcase': 'adventure:selftest_biome_chalk',
                                          'shots': [{'kind': 'detail', 'shows': 'c', 'png': 'p', 'camera': {'x': 1, 'y': 'surface+2', 'z': 3, 'yaw': 4, 'pitch': 5}}]}]}
        e = cl.entries(recs, None, 8)[0]
        self.assertEqual((e['name'], e['dimension'], e['y'], e['radius']), ('chalk-detail1-census', 'adventure:selftest_biome_chalk', 'surface+2', 8))
        self.assertEqual((e['yaw'], e['pitch']), (4, 5))
        shot_level = {'root': '/r', 'biomes': [{'biome': 'adventure:chalk', 'showcase': 'adventure:selftest_biome_chalk', 'shots': [
            {'kind': 'wide', 'shows': 'w', 'camera': {'x': 1, 'y': 'surface+30', 'z': 3}, 'yaw': 90, 'pitch': 25},
            {'kind': 'eye', 'shows': 'e', 'camera': {'x': 1, 'y': 'ground+1.6', 'z': 3}, 'lookingAt': [9, 70, 9]}]}]}
        wide, eye = cl.entries(shot_level, None, 8)
        self.assertEqual((wide['yaw'], wide['pitch'], wide['radius']), (90, 25, 24))
        self.assertEqual(eye['at'], [9, 70, 9])

    def test_only_filters_by_slug(self):
        recs = [{'slug': 'a', 'shots': [{'kind': 'ground', 'camera': {'x': 0, 'y': 1, 'z': 0, 'yaw': 0, 'pitch': 0}}]},
                {'slug': 'b', 'shots': [{'kind': 'ground', 'camera': {'x': 0, 'y': 1, 'z': 0, 'yaw': 0, 'pitch': 0}}]}]
        self.assertEqual([e['name'] for e in cl.entries(recs, {'b'}, 8)], ['b-ground1-census'])


if __name__ == '__main__':
    unittest.main()
