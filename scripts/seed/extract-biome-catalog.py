#!/usr/bin/env python3
"""extract-biome-catalog.py — build the biome reference the curation runs on.

Reads every worldgen/biome JSON from the server's mod jars (Terralith,
Incendium, Nullscape, Nature's Spirit, WWOO, Paradise Lost, ...) plus the
vanilla server jar, and writes scripts/seed/biome_catalog.json:

    { "<id>": {"mod": ..., "temperature": ..., "downfall": ...,
               "precipitation": bool, "sky": "#rrggbb", "fog": "#...",
               "water": "#...", "grass": "#...", "foliage": "#...",
               "monsters": [...], "creatures": [...],
               "features": [notable feature ids]} }

Palette proxy: colours + notable placed features (trees, geodes, deltas,
pillars...). Surface blocks live in noise-settings surface rules, not the
biome JSON, so colours + features + mobs are the curation signal.

Usage: extract-biome-catalog.py <data-dir> [out.json]
  <data-dir> = a consumer data/ dir (mods/ + versions/ inside).
"""
import json
import re
import sys
import zipfile
from pathlib import Path

NOTABLE = re.compile(
    r"(tree|fungus|geode|delta|spike|pillar|spire|glow|crystal|coral|"
    r"bamboo|cactus|mushroom|flower|lavender|cherry|sakura|vegetation|"
    r"iceberg|basalt|obsidian|sculk|chorus)", re.I)


def hexcolor(v):
    return f"#{v:06x}" if isinstance(v, int) else None


def summarise(biome_id, mod, data):
    eff = data.get("effects", {})
    spawners = data.get("spawners", {})

    def mobs(cat):
        return sorted({s["type"].replace("minecraft:", "")
                       for s in spawners.get(cat, [])})

    feats = []
    for step in data.get("features", []):
        for f in (step if isinstance(step, list) else [step]):
            if isinstance(f, str) and NOTABLE.search(f):
                feats.append(f.split(":", 1)[-1].split("/")[-1])
    return {
        "mod": mod,
        "temperature": data.get("temperature"),
        "downfall": data.get("downfall"),
        "precipitation": data.get("has_precipitation"),
        "sky": hexcolor(eff.get("sky_color")),
        "fog": hexcolor(eff.get("fog_color")),
        "water": hexcolor(eff.get("water_color")),
        "grass": hexcolor(eff.get("grass_color")),
        "foliage": hexcolor(eff.get("foliage_color")),
        "particle": (eff.get("particle") or {}).get("options", {}).get("type"),
        "monsters": mobs("monster"),
        "creatures": mobs("creature"),
        "features": sorted(set(feats))[:14],
    }


def main():
    data_dir = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else \
        Path(__file__).parent / "biome_catalog.json"

    jars = sorted((data_dir / "mods").glob("*.jar"))
    jars += sorted((data_dir / "versions").rglob("*.jar"))
    for dp in (data_dir / "world" / "datapacks").rglob("*.zip"):
        jars.append(dp)

    catalog = {}
    pat = re.compile(r"^data/([a-z_0-9]+)/worldgen/biome/([a-z_0-9/]+)\.json$")
    for jar in jars:
        mod = jar.stem
        try:
            zf = zipfile.ZipFile(jar)
        except (zipfile.BadZipFile, OSError):
            continue
        for name in zf.namelist():
            m = pat.match(name)
            if not m:
                continue
            biome_id = f"{m.group(1)}:{m.group(2)}"
            # First definition wins except vanilla ids: a mod overriding a
            # vanilla biome (Terralith/WWOO do this) is the live version.
            if biome_id in catalog and m.group(1) == "minecraft":
                continue
            try:
                data = json.loads(zf.read(name))
            except (json.JSONDecodeError, KeyError):
                continue
            catalog[biome_id] = summarise(biome_id, mod, data)

    out.write_text(json.dumps(dict(sorted(catalog.items())), indent=1))
    mods = {}
    for v in catalog.values():
        mods[v["mod"]] = mods.get(v["mod"], 0) + 1
    print(f"{len(catalog)} biomes -> {out}")
    for m, n in sorted(mods.items(), key=lambda kv: -kv[1]):
        print(f"  {n:4} {m}")


if __name__ == "__main__":
    main()
