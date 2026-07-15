#!/usr/bin/env python3
"""Generate the adventure:wide / adventure:compressed noise-settings datapack.

Purpose:  Clone Tectonic's (Terratonic) overworld noise settings and its full
          density-function graph into the `adventure` namespace, with every
          config-driven node (tectonic:config_constant, tectonic:config_noise,
          tectonic:config_clamp, tectonic:invert) replaced by literal values
          for a named preset. The output is baked into the custom-dimensions
          mod jar (src/main/resources/data/adventure/worldgen/) so the
          ChunkGeneratorSettings registry entries exist from chunk zero in
          every environment, fully independent of the runtime tectonic.json.

Context:  mods/.ideas/customising-terrain.md — "fully independent
          per-dimension tunes need the copies to inline their own constants".
          Semantics of the custom density-function types were verified against
          the pinned jar's bytecode (tectonic 3.0.26-fabric-21.1):
            - config_constant  -> ConfigState.getValue(key) as a constant
            - config_noise     -> shifted_noise(noise, xz_scale=NoiseState.scale,
                                  y_scale=0) * multiplier + offset
                                  (when experimental alternate scaling is OFF)
            - config_clamp     -> vanilla clamp with min/max folded to numbers
            - tectonic:invert  -> 1 / <constant-folded argument>

Usage:    scripts/gen-terrain-presets.py [--jar path/to/tectonic.jar]
          Without --jar the pinned version from config/modrinth-mods.txt is
          downloaded from the Modrinth CDN into a temp dir.

Gotchas:  - Re-run this whenever the Tectonic pin bumps (weekly mod-updates PR
            is the checkpoint) and commit the regenerated files.
          - Template-only tooling: not shipped in the stack bundle.
          - The wide preset applies the ultrasmooth overlay; compressed does
            not. Changing that means regenerating.
          - Output must contain zero `tectonic:` density-function references;
            the script hard-fails otherwise. `tectonic:` NOISE references
            (worldgen/noise/, static definitions with no config wiring) are
            deliberately kept global.
"""

import argparse
import io
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_ROOT = REPO / "mods/custom-dimensions/src/main/resources/data/adventure/worldgen"
MODS_TXT = REPO / "config/modrinth-mods.txt"

# --- Preset definitions -------------------------------------------------------
# getValue() keys (constants) and getNoiseState() keys (noises), mirroring
# ConfigState bytecode. alternate_erosion_scale = erosion_scale * 4.
# Values: config/tectonic.json rationale for `wide`; customising-terrain.md
# "compressed" sketch (erosion/ridge ~0.45, vertical 1.5, tighter climate).
PRESETS = {
    "wide": {
        "ultrasmooth": True,
        "noise_block": {"min_y": -64, "height": 512, "size_horizontal": 1, "size_vertical": 1},
        "constants": {
            "vertical_scale": 1.0, "elevation_boost": 0.3,
            "min_y": -64, "max_y": 448,
            "lava_tunnels": 1, "ocean_offset": -0.8,
            "alternate_erosion_scale": 0.48,
            "underground_rivers": -1, "flat_terrain_skew": 0.3,
            "rolling_hills": 1, "jungle_pillars": 1,
            "ocean_depth": -0.22, "deep_ocean_depth": -0.45,
            "depth_cutoff_start": 0.1, "depth_cutoff_size": 0.1,
            "cheese_enabled": 1, "cheese_additive": 0.27,
            "noodle_enabled": 1, "noodle_additive": -0.075,
        },
        "noises": {  # key -> (scale, multiplier, offset)
            "continents": (0.1, 1.0, 0.0), "island": (0.11, 1.0, 0.0),
            "erosion": (0.12, 1.0, 0.0), "ridge": (0.18, 1.0, 0.0),
            "temperature": (0.15, 1.0, 0.0), "vegetation": (0.15, 1.0, 0.0),
        },
    },
    "compressed": {
        "ultrasmooth": False,
        "noise_block": {"min_y": -64, "height": 448, "size_horizontal": 1, "size_vertical": 1},
        "constants": {
            "vertical_scale": 1.5, "elevation_boost": 0.5,
            "min_y": -64, "max_y": 384,
            "lava_tunnels": 1, "ocean_offset": -0.8,
            "alternate_erosion_scale": 1.8,
            "underground_rivers": -1, "flat_terrain_skew": 0.0,
            "rolling_hills": 1, "jungle_pillars": 1,
            "ocean_depth": -0.22, "deep_ocean_depth": -0.45,
            "depth_cutoff_start": 0.1, "depth_cutoff_size": 0.1,
            "cheese_enabled": 1, "cheese_additive": 0.27,
            "noodle_enabled": 1, "noodle_additive": -0.075,
        },
        "noises": {
            "continents": (0.13, 1.0, 0.0), "island": (0.11, 1.0, 0.0),
            "erosion": (0.45, 1.0, 0.0), "ridge": (0.45, 1.0, 0.0),
            "temperature": (0.35, 1.0, 0.0), "vegetation": (0.35, 1.0, 0.0),
        },
    },
}

DF_RE = re.compile(r"^data/([a-z_0-9.]+)/worldgen/density_function/(.+)\.json$")


def pinned_jar_url():
    for line in MODS_TXT.read_text().splitlines():
        line = line.strip()
        if line.startswith("tectonic:"):
            version_id = line.split(":", 1)[1].split()[0]
            api = f"https://api.modrinth.com/v2/version/{version_id}"
            with urllib.request.urlopen(api) as r:
                data = json.load(r)
            for f in data["files"]:
                if f.get("primary"):
                    return f["url"], data["version_number"]
    raise SystemExit("tectonic pin not found in config/modrinth-mods.txt")


def load_layers(zf, preset):
    """Return {id: json} for density functions, plus the noise settings json,
    honouring overlay order for this preset (base < mod < ultrasmooth < terratonic)."""
    layers = ["resourcepacks/tectonic/data/",
              "resourcepacks/tectonic/overlay.mod/data/"]
    if preset["ultrasmooth"]:
        layers.append("resourcepacks/tectonic/overlay.ultrasmooth/data/")
    layers.append("resourcepacks/tectonic/overlay.terratonic/data/")

    dfs = {}
    noise_settings = None
    for layer in layers:
        for name in zf.namelist():
            if not name.startswith(layer) or not name.endswith(".json"):
                continue
            rel = "data/" + name[len(layer):]
            m = DF_RE.match(rel)
            if m:
                dfs[f"{m.group(1)}:{m.group(2)}"] = json.loads(zf.read(name))
            elif rel == "data/minecraft/worldgen/noise_settings/overworld.json":
                noise_settings = json.loads(zf.read(name))
    if noise_settings is None:
        raise SystemExit("terratonic overworld noise settings not found in jar")
    return dfs, noise_settings


def fold(node, constants, dfs=None, _depth=0):
    """Constant-fold a density function subtree; return float or None."""
    if _depth > 32:
        return None
    if isinstance(node, (int, float)):
        return float(node)
    if isinstance(node, str):
        if dfs is not None and node in dfs:
            return fold(dfs[node], constants, dfs, _depth + 1)
        return None
    if not isinstance(node, dict):
        return None
    t = node.get("type", "")
    if t == "tectonic:config_constant":
        key = node["key"]
        if key not in constants:
            return 0.0  # mirrors ConfigState.getValue default (see transform)
        return float(constants[key])
    if t in ("minecraft:flat_cache", "minecraft:cache_2d", "minecraft:cache_once",
             "minecraft:interpolated", "minecraft:cache_all_in_cell"):
        return fold(node.get("argument"), constants, dfs, _depth + 1)
    if t in ("minecraft:add", "minecraft:mul", "minecraft:min", "minecraft:max"):
        a = fold(node.get("argument1"), constants, dfs, _depth + 1)
        b = fold(node.get("argument2"), constants, dfs, _depth + 1)
        if a is None or b is None:
            return None
        return {"minecraft:add": a + b, "minecraft:mul": a * b,
                "minecraft:min": min(a, b), "minecraft:max": max(a, b)}[t]
    if t == "minecraft:clamp":
        v = fold(node.get("input"), constants, dfs, _depth + 1)
        if v is None:
            return None
        return max(float(node["min"]), min(float(node["max"]), v))
    return None


def transform(node, preset, df_ids, prefix, dfs=None, parent_key=None):
    """Rewrite refs into the preset namespace and inline config-driven nodes."""
    if isinstance(node, str):
        # Rewrite references to cloned density functions. "type" and "noise"
        # values are never DF references.
        if parent_key not in ("type", "noise") and node in df_ids:
            return f"adventure:{prefix}/{node.replace(':', '/')}"
        return node
    if isinstance(node, list):
        return [transform(x, preset, df_ids, prefix, dfs, parent_key) for x in node]
    if not isinstance(node, dict):
        return node

    t = node.get("type", "")
    # shift/shift_a/shift_b take a NOISE id in their "argument" field — the
    # only place a noise reference hides under a DF-looking key. Ids can
    # collide across the noise and density-function registries
    # (e.g. tectonic:blend_alpha is both), so position decides meaning.
    if t in ("minecraft:shift", "minecraft:shift_a", "minecraft:shift_b"):
        return dict(node)
    if t == "tectonic:config_constant":
        key = node["key"]
        if key not in preset["constants"]:
            # Faithful to ConfigState.getValue (jar bytecode): keys missing
            # from its switch return 0.0 — e.g. "spaghetti_enabled" in 3.0.26,
            # an upstream quirk we reproduce so presets match the live
            # overworld. Warn loudly so a future pin bump gets re-checked.
            print(f"warning: config_constant key not in getValue switch, "
                  f"inlining 0.0 (matches runtime): {key}")
            return 0.0
        return preset["constants"][key]
    if t == "tectonic:config_noise":
        key = node["key"]
        if key not in preset["noises"]:
            raise SystemExit(f"config_noise key with no preset value: {key}")
        scale, mult, offset = preset["noises"][key]
        out = {
            "type": "minecraft:shifted_noise",
            "noise": node["noise"],
            "xz_scale": scale,
            "y_scale": 0.0,
            "shift_x": transform(node.get("shift_x", 0), preset, df_ids, prefix, dfs),
            "shift_y": 0.0,
            "shift_z": transform(node.get("shift_z", 0), preset, df_ids, prefix, dfs),
        }
        if mult != 1.0:
            out = {"type": "minecraft:mul", "argument1": out, "argument2": mult}
        if offset != 0.0:
            out = {"type": "minecraft:add", "argument1": out, "argument2": offset}
        return out
    if t == "tectonic:config_clamp":
        lo = fold(node["min"], preset["constants"], dfs)
        hi = fold(node["max"], preset["constants"], dfs)
        if lo is None or hi is None:
            raise SystemExit("config_clamp min/max did not fold to constants")
        return {
            "type": "minecraft:clamp",
            "input": transform(node["input"], preset, df_ids, prefix, dfs),
            "min": lo, "max": hi,
        }
    if t == "tectonic:invert":
        v = fold(node.get("argument"), preset["constants"], dfs)
        if v is None or v == 0:
            raise SystemExit("tectonic:invert argument did not fold to a nonzero constant")
        return 1.0 / v

    return {k: transform(v, preset, df_ids, prefix, dfs, k) for k, v in node.items()}


def check_output(obj, path):
    s = json.dumps(obj)
    for m in re.finditer(r'"(tectonic:[^"]+)"', s):
        ref = m.group(1)
        # tectonic noise definitions are static (no config wiring) and stay global
        if re.search(r'"noise":\s*"%s"' % re.escape(ref), s):
            continue
        raise SystemExit(f"{path}: unresolved tectonic reference {ref}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", help="path to tectonic jar (skips download)")
    args = ap.parse_args()

    if args.jar:
        jar_bytes = Path(args.jar).read_bytes()
        version = Path(args.jar).name
    else:
        url, version = pinned_jar_url()
        print(f"downloading tectonic {version} ...")
        with urllib.request.urlopen(url) as r:
            jar_bytes = r.read()

    zf = zipfile.ZipFile(io.BytesIO(jar_bytes))
    total_files = 0
    for name, preset in PRESETS.items():
        dfs, noise_settings = load_layers(zf, preset)
        df_ids = set(dfs)

        # Ids can collide across the noise and density-function registries;
        # position (noise/shift* fields vs everything else) disambiguates.
        noise_ids = {f"{m.group(1)}:{m.group(2)}"
                     for n in zf.namelist()
                     if (m := re.match(r"^resourcepacks/tectonic/(?:overlay\.[a-z_0-9]+/)?data/([a-z_0-9.]+)/worldgen/noise/(.+)\.json$", n))}
        clash = df_ids & noise_ids
        if clash:
            print(f"note: {len(clash)} id(s) exist in both registries "
                  f"(disambiguated by field position): {sorted(clash)}")

        out_dir = OUT_ROOT / "density_function" / name
        for df_id, body in sorted(dfs.items()):
            rel = df_id.replace(":", "/")
            dest = out_dir / (rel + ".json")
            dest.parent.mkdir(parents=True, exist_ok=True)
            converted = transform(body, preset, df_ids, name, dfs)
            check_output(converted, dest)
            dest.write_text(json.dumps(converted, indent=1) + "\n")
            total_files += 1

        ns = dict(noise_settings)
        ns["noise"] = preset["noise_block"]
        ns = transform(ns, preset, df_ids, name, dfs)
        check_output(ns, f"noise_settings/{name}.json")
        dest = OUT_ROOT / "noise_settings" / f"{name}.json"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(ns, indent=1) + "\n")
        total_files += 1
        print(f"preset {name}: {len(dfs)} density functions + noise settings")

    print(f"generated {total_files} files from {version} into {OUT_ROOT}")


if __name__ == "__main__":
    main()
