"""Scratch copies of definitions/ with the K10 fields on the dimensions each gap names (definitions/ is untouched)."""
import json
import shutil
import sys
from pathlib import Path

src = Path('/home/user/minecraft-server-template/mods/custom-dimensions-26/definitions')
out = Path(sys.argv[1])
if out.exists():
    shutil.rmtree(out)
shutil.copytree(src, out)


def edit(slug, fn):
    p = out / f'{slug}.json'
    d = json.loads(p.read_text())
    fn(d)
    p.write_text(json.dumps(d, indent=2) + '\n')


ROOFED_FLAT = {'base': 'roofed', 'params': {'floorHeight': 34, 'floorRelief': 6, 'flats': 0.6, 'ceilingGap': 48}}


def buried(d):
    d['biomeLayout']['roles'] = {'minecraft:basalt_deltas': 'ocean'}
    d['atmosphere']['overBiomes'] = True


def molten(d):
    d['terrain'] = ROOFED_FLAT
    d['liquids'] = {'seaLevel': 33, 'defaultFluid': 'minecraft:lava'}
    d['biomeLayout']['roles'] = {'minecraft:basalt_deltas': 'ocean', 'adventure:magma_land': 'shore'}


def gilded(d):
    d['terrain'] = {'base': 'roofed', 'params': {'floorHeight': 64, 'floorRelief': 8, 'pit': 0.2, 'pitDepth': 40, 'ceilingGap': 48},
                    'layout': {'type': 'radial', 'radius': 600, 'strength': 1}}
    d['liquids'] = {'seaLevel': 40, 'defaultFluid': 'minecraft:lava'}
    d['biomeLayout']['roles'] = {'minecraft:basalt_deltas': 'ocean'}


def forged(d):
    d['terrain'] = {'base': 'roofed', 'params': {'floorHeight': 50, 'floorRelief': 16, 'chasms': 0.04, 'chasmDepth': 30}}
    d['liquids'] = {'seaLevel': 40, 'defaultFluid': 'minecraft:lava'}
    d['biomeLayout']['roles'] = {'minecraft:basalt_deltas': 'ocean'}
    d['biomeLayout']['belowFloor'] = {'adventure:magma_land': 12}
    d['biomeLayout']['weights'].pop('adventure:magma_land', None)


def unbind(d, biome):
    d['biomes'] = [biome if isinstance(b, dict) and b.get('id') == biome else b for b in d['biomes']]


def boneyard(d):
    unbind(d, 'minecraft:deep_dark')
    d['biomeLayout']['belowFloor'] = {'minecraft:deep_dark': 8}
    d['atmosphere']['overBiomes'] = True


def ember(d):
    unbind(d, 'minecraft:sulfur_caves')
    d['biomeLayout']['belowFloor'] = {'minecraft:sulfur_caves': 8}
    d['atmosphere']['overBiomes'] = True


def sanctum(d):
    d['atmosphere']['overBiomes'] = True


for slug, fn in [('the_buried_age', buried),
                 ('the_boneyard', boneyard), ('the_ember_fields', ember), ('the_obsidian_sanctum', sanctum)]:
    edit(slug, fn)
print('wrote', out)
