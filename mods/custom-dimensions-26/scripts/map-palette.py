#!/usr/bin/env python3
"""Writes the web map's block palette from a Minecraft client jar.

Purpose: each block's colour seen from above is the alpha-weighted mean of
the texture on its model's highest upward face (the particle texture when the
model has no elements), with the texture's mean opacity, and whether that
face is tinted (a fluid, drawn without elements, counts as tinted); the grass, foliage and dry-foliage colormaps are kept as
33 x 33 samples (every eighth pixel, rows of downfall, a sample past the
used triangle repeating its row's diagonal). Colours only: no texture leaves the jar.
Usage: map-palette.py <client.jar> <out.json>
  (Loom keeps the jar at ~/.gradle/caches/fabric-loom/<version>/minecraft-client.jar)
Gotchas: tint kinds (grass, foliage, water, fixed) come from TINTS and FIXED
below, because the client decides them in code, not data: a face with a
tintindex whose block is not listed (cherry leaves) is drawn untinted.
"""
import io
import json
import sys
import zipfile

from PIL import Image

TINTS = {
    "grass": ["grass_block", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern", "sugar_cane", "bush"],
    "foliage": ["oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine"],
    "dry_foliage": ["leaf_litter"],
    "water": ["water", "bubble_column", "water_cauldron"],
}
FIXED = {"spruce_leaves": 0x619961, "birch_leaves": 0x80A755, "lily_pad": 0x208030, "redstone_wire": 0x960000,
         "attached_pumpkin_stem": 0xE0C71C, "attached_melon_stem": 0xE0C71C, "pumpkin_stem": 0x71C71C, "melon_stem": 0x71C71C}
STEP = 8


def load(jar, path):
    try:
        return json.loads(jar.read(path))
    except KeyError:
        return None


def model_path(ref):
    ns, _, name = ref.partition(":") if ":" in ref else ("minecraft", "", ref)
    return "assets/%s/models/%s.json" % (ns, name)


def resolve(jar, ref):
    """Textures (child wins) and the first elements up the parent chain."""
    textures, elements, seen = {}, None, 0
    while ref and seen < 16:
        seen += 1
        m = load(jar, model_path(ref))
        if m is None:
            break
        for k, v in (m.get("textures") or {}).items():
            textures.setdefault(k, v)
        if elements is None and m.get("elements"):
            elements = m["elements"]
        ref = m.get("parent")
    return textures, elements


def texture_ref(textures, ref, depth=0):
    while depth < 16:
        if isinstance(ref, dict):
            ref = ref.get("sprite")
        if not ref or not ref.startswith("#"):
            return ref
        ref = textures.get(ref[1:])
        depth += 1
    return None


def first_model(state):
    if "variants" in state:
        variants = state["variants"]
        key = "" if "" in variants else next((k for k in variants if "snowy=true" not in k), next(iter(variants)))
        v = variants[key]
    else:
        parts = state.get("multipart") or []
        v = next((p["apply"] for p in parts if "when" not in p), parts[0]["apply"] if parts else None)
    if isinstance(v, list):
        v = v[0]
    return v.get("model") if v else None


def top_face(textures, elements):
    if elements:
        best = None
        for e in elements:
            up = (e.get("faces") or {}).get("up")
            if up and (best is None or e["to"][1] > best[0]):
                best = (e["to"][1], up)
        if best:
            return texture_ref(textures, best[1].get("texture")), "tintindex" in best[1]
        for e in elements:
            for f in (e.get("faces") or {}).values():
                return texture_ref(textures, f.get("texture")), "tintindex" in f
    return texture_ref(textures, textures.get("particle")), True


def average(jar, ref):
    ns, _, name = ref.partition(":") if ":" in ref else ("minecraft", "", ref)
    try:
        img = Image.open(io.BytesIO(jar.read("assets/%s/textures/%s.png" % (ns, name)))).convert("RGBA")
    except KeyError:
        return None
    w = img.width
    img = img.crop((0, 0, w, min(w, img.height)))
    r = g = b = a = 0
    data = img.tobytes()
    for k in range(0, len(data), 4):
        pr, pg, pb, pa = data[k], data[k + 1], data[k + 2], data[k + 3]
        r += pr * pa
        g += pg * pa
        b += pb * pa
        a += pa
    n = img.width * img.height
    if a == 0:
        return 0, 0
    rgb = (round(r / a) << 16) | (round(g / a) << 8) | round(b / a)
    return rgb, round(a / n)


def colormap(jar, name):
    try:
        img = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/colormap/%s.png" % name))).convert("RGB")
    except KeyError:
        return None
    out = []
    for j in range(0, 257, STEP):
        for i in range(0, 257, STEP):
            x, y = min(i, 255), min(j, 255)
            r, g, b = img.getpixel((min(x, y), y))
            out.append("%02x%02x%02x" % (r, g, b))
    return " ".join(out)


def main():
    jar = zipfile.ZipFile(sys.argv[1])
    tint_of = {b: k for k, blocks in TINTS.items() for b in blocks}
    blocks, tints = {}, {}
    for path in sorted(jar.namelist()):
        if not path.startswith("assets/minecraft/blockstates/") or not path.endswith(".json"):
            continue
        name = path.rsplit("/", 1)[1][:-5]
        ref = first_model(load(jar, path) or {})
        if not ref:
            continue
        textures, elements = resolve(jar, ref)
        tex, tinted = top_face(textures, elements)
        avg = average(jar, tex) if tex else None
        if avg is None:
            continue
        rgb, alpha = avg
        blocks["minecraft:" + name] = "%06x%02x" % (rgb, alpha)
        if name in FIXED:
            tints["minecraft:" + name] = "#%06x" % FIXED[name]
        elif tinted and name in tint_of:
            tints["minecraft:" + name] = tint_of[name]
    out = {
        "source": "alpha-weighted mean of each block's highest upward face texture (particle without elements), "
                  "rrggbbaa with aa the texture's mean opacity; tinted faces multiply by the tint",
        "colormapStep": STEP,
        "colormaps": {n: colormap(jar, n) for n in ("grass", "foliage", "dry_foliage")},
        "tints": tints,
        "blocks": blocks,
    }
    with open(sys.argv[2], "w") as f:
        json.dump(out, f, indent=0, sort_keys=False)
        f.write("\n")
    print("%d blocks, %d tinted" % (len(blocks), len(tints)))


if __name__ == "__main__":
    main()
