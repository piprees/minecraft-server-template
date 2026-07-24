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

Self-containment (optional-mods hardening, 2026-07-24): the presets must
survive Tectonic AND Terralith being removed via overlay/mods-remove.txt.
Registry entries baked into a mod jar are always loaded, so a dangling
reference is a boot break, not a cosmetic gap. Three mechanisms:
  1. DENSITY FUNCTIONS are cloned into the adventure namespace (the
     original mechanism, now extended to the terralith-jar closure —
     DF ids carry no seed, so renaming is generation-neutral).
  2. NOISES keep their ORIGINAL ids — vanilla seeds each noise by
     MD5-hashing the id string, so a rename shifts terrain on every
     existing world. Byte-identical same-id copies are emitted into
     data/tectonic/ and data/terralith/ instead; when the real mod is
     present its pack outranks ours (mods each get their own datapack,
     tectonic's built-in pack sits above all of them) and the duplicate
     is harmless either way because the bytes match.
  3. minecraft:-namespace refs are audited against the frozen vanilla
     1.21.1 id sets below: vanilla ids stay runtime-resolved (vanilla
     always provides them), mod-INVENTED minecraft: DFs are cloned like
     any other, and mod-invented minecraft: noises are same-id-copied
     (safe: no vanilla surface references them). Vanilla ids are NEVER
     shipped as copies — our copy would leak into the real overworld
     whenever the owning mod is removed.

Usage:    scripts/gen-terrain-presets.py [--tectonic-jar X] [--terralith-jar Y]
          (--jar is a legacy alias for --tectonic-jar.) Without the args the
          pinned versions from config/modrinth-mods.txt are downloaded from
          the Modrinth CDN.

Gotchas:  - Re-run this whenever the Tectonic OR Terralith pin bumps (weekly
            mod-updates PR is the checkpoint) and commit the regenerated
            files. Regenerate VANILLA_DFS/VANILLA_NOISES on MC version bumps
            (extract data/minecraft/worldgen/{density_function,noise} ids
            from the vanilla server jar).
          - Template-only tooling: not shipped in the stack bundle.
          - The wide preset applies the ultrasmooth overlay; compressed does
            not. Changing that means regenerating.
          - Output must contain zero `tectonic:`/`terralith:`
            density-function references; every retained external reference
            must be a NOISE reference with an emitted same-id copy. The
            final audit hard-fails otherwise.
          - Generator-owned output dirs are wiped before emission:
            data/adventure/worldgen/density_function/{wide,compressed},
            data/adventure/worldgen/noise_settings, data/tectonic,
            data/terralith, data/minecraft/worldgen/noise. Everything else
            under data/ is hand-authored — never clean it here.
"""

import argparse
import io
import json
import re
import shutil
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES_DATA = REPO / "mods/custom-dimensions/src/main/resources/data"
OUT_ROOT = RES_DATA / "adventure/worldgen"
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

# Vanilla 1.21.1 worldgen ids, extracted from server-1.21.1.jar
# (data/minecraft/worldgen/{density_function,noise}). Regenerate on MC bumps.
VANILLA_DFS = frozenset({
    "end/base_3d_noise", "end/sloped_cheese", "nether/base_3d_noise",
    "overworld/base_3d_noise", "overworld/caves/entrances",
    "overworld/caves/noodle", "overworld/caves/pillars",
    "overworld/caves/spaghetti_2d",
    "overworld/caves/spaghetti_2d_thickness_modulator",
    "overworld/caves/spaghetti_roughness_function", "overworld/continents",
    "overworld/depth", "overworld/erosion", "overworld/factor",
    "overworld/jaggedness", "overworld/offset", "overworld/ridges",
    "overworld/ridges_folded", "overworld/sloped_cheese",
    "overworld_amplified/depth", "overworld_amplified/factor",
    "overworld_amplified/jaggedness", "overworld_amplified/offset",
    "overworld_amplified/sloped_cheese", "overworld_large_biomes/continents",
    "overworld_large_biomes/depth", "overworld_large_biomes/erosion",
    "overworld_large_biomes/factor", "overworld_large_biomes/jaggedness",
    "overworld_large_biomes/offset", "overworld_large_biomes/sloped_cheese",
    "shift_x", "shift_z", "y", "zero",
})
VANILLA_NOISES = frozenset({
    "aquifer_barrier", "aquifer_fluid_level_floodedness",
    "aquifer_fluid_level_spread", "aquifer_lava", "badlands_pillar",
    "badlands_pillar_roof", "badlands_surface", "calcite", "cave_cheese",
    "cave_entrance", "cave_layer", "clay_bands_offset", "continentalness",
    "continentalness_large", "erosion", "erosion_large", "gravel",
    "gravel_layer", "ice", "iceberg_pillar", "iceberg_pillar_roof",
    "iceberg_surface", "jagged", "nether_state_selector", "nether_wart",
    "netherrack", "noodle", "noodle_ridge_a", "noodle_ridge_b",
    "noodle_thickness", "offset", "ore_gap", "ore_vein_a", "ore_vein_b",
    "ore_veininess", "packed_ice", "patch", "pillar", "pillar_rareness",
    "pillar_thickness", "powder_snow", "ridge", "soul_sand_layer",
    "spaghetti_2d", "spaghetti_2d_elevation", "spaghetti_2d_modulator",
    "spaghetti_2d_thickness", "spaghetti_3d_1", "spaghetti_3d_2",
    "spaghetti_3d_rarity", "spaghetti_3d_thickness", "spaghetti_roughness",
    "spaghetti_roughness_modulator", "surface", "surface_secondary",
    "surface_swamp", "temperature", "temperature_large", "vegetation",
    "vegetation_large",
})

# JSON keys whose string values are never worldgen references.
INERT_KEYS = ("type", "biome_is", "random_name", "Name")
SHIFT_TYPES = ("minecraft:shift", "minecraft:shift_a", "minecraft:shift_b")

ID_RE = re.compile(r"^[a-z_0-9.]+:[a-z_0-9/.]+$|^[a-z_0-9/.]+$")


def qualify(ref):
    """Bare ids (terralith writes e.g. "overworld/temperature") mean minecraft:."""
    return ref if ":" in ref else f"minecraft:{ref}"


def scan_refs(node, noise_refs, df_refs, key=None, node_type=None):
    """Positionally classify external references in a worldgen JSON tree.

    "noise" values and shift-node "argument" values are NOISE ids; every
    other id-shaped string outside INERT_KEYS is a density-function ref.
    Ids can collide across the two registries (tectonic:blend_alpha is
    both) — position is the only correct disambiguator.
    """
    if isinstance(node, dict):
        t = node.get("type", "")
        for k, v in node.items():
            scan_refs(v, noise_refs, df_refs, k, t)
    elif isinstance(node, list):
        for v in node:
            scan_refs(v, noise_refs, df_refs, key, node_type)
    elif isinstance(node, str):
        if key in INERT_KEYS or not ID_RE.match(node):
            return
        ref = qualify(node)
        ns, path = ref.split(":", 1)
        if key == "noise" or (key == "argument" and node_type in SHIFT_TYPES):
            noise_refs.add(ref)
        elif ns in ("tectonic", "terralith"):
            df_refs.add(ref)
        elif ns == "minecraft" and ("/" in path or path in VANILLA_DFS):
            # slash-less minecraft: strings that aren't vanilla DFs are
            # block/etc ids that slipped past INERT_KEYS — not references
            df_refs.add(ref)


def pinned_jar_url(slug):
    for line in MODS_TXT.read_text().splitlines():
        line = line.strip()
        if line.startswith(f"{slug}:"):
            version_id = line.split(":", 1)[1].split()[0]
            api = f"https://api.modrinth.com/v2/version/{version_id}"
            with urllib.request.urlopen(api) as r:
                data = json.load(r)
            for f in data["files"]:
                if f.get("primary"):
                    return f["url"], data["version_number"]
    raise SystemExit(f"{slug} pin not found in config/modrinth-mods.txt")


def fetch_jar(arg_path, slug):
    if arg_path:
        return zipfile.ZipFile(io.BytesIO(Path(arg_path).read_bytes())), Path(arg_path).name
    url, version = pinned_jar_url(slug)
    print(f"downloading {slug} {version} ...")
    with urllib.request.urlopen(url) as r:
        return zipfile.ZipFile(io.BytesIO(r.read())), f"{slug} {version}"


def load_layers(zf, preset):
    """Return ({id: json} density functions, noise settings json, {rel: bytes}
    raw file map), honouring overlay order for this preset
    (base < mod < ultrasmooth < terratonic — later layers win)."""
    layers = ["resourcepacks/tectonic/data/",
              "resourcepacks/tectonic/overlay.mod/data/"]
    if preset["ultrasmooth"]:
        layers.append("resourcepacks/tectonic/overlay.ultrasmooth/data/")
    layers.append("resourcepacks/tectonic/overlay.terratonic/data/")

    dfs = {}
    files = {}
    noise_settings = None
    for layer in layers:
        for name in zf.namelist():
            if not name.startswith(layer) or not name.endswith(".json"):
                continue
            rel = "data/" + name[len(layer):]
            files[rel] = zf.read(name)
            m = DF_RE.match(rel)
            if m:
                dfs[f"{m.group(1)}:{m.group(2)}"] = json.loads(zf.read(name))
            elif rel == "data/minecraft/worldgen/noise_settings/overworld.json":
                noise_settings = json.loads(zf.read(name))
    if noise_settings is None:
        raise SystemExit("terratonic overworld noise settings not found in jar")
    # Dead data in the terratonic settings: preliminary_surface_level is not
    # a 1.21.1 noise_router field (codecs ignore it) and the DF it references
    # does not exist in ANY jar — tectonic ships the dangling ref and boots,
    # which is the proof it's ignored. Strip it so the closure stays honest.
    noise_settings.get("noise_router", {}).pop("preliminary_surface_level", None)
    return dfs, noise_settings, files


def expand_closure(dfs, noise_settings, lz):
    """Pull referenced-but-missing density functions into `dfs` so the clone
    pass makes the presets self-contained.

    Resolution mirrors runtime pack priority: a terralith: id already in
    `dfs` came from tectonic's terratonic overlay (its built-in pack
    outranks every mod pack, so that copy is what generation uses today);
    otherwise the terralith jar provides it. minecraft: ids that vanilla
    ships stay runtime-resolved — vanilla always provides them, and
    cloning them would freeze mod overrides into the presets. Mod-INVENTED
    minecraft: ids must be cloned or they dangle when the mod is removed.
    """
    def df_body_from_terralith(ref):
        ns, path = ref.split(":", 1)
        rel = f"data/{ns}/worldgen/density_function/{path}.json"
        try:
            return json.loads(lz.read(rel))
        except KeyError:
            return None

    added = 0
    while True:
        noise_refs, df_refs = set(), set()
        scan_refs(noise_settings, noise_refs, df_refs)
        for body in dfs.values():
            scan_refs(body, noise_refs, df_refs)
        missing = []
        for ref in sorted(df_refs):
            if ref in dfs:
                continue
            ns, path = ref.split(":", 1)
            if ns == "minecraft" and path in VANILLA_DFS:
                continue  # vanilla provides it in every configuration
            body = df_body_from_terralith(ref)
            if body is None:
                raise SystemExit(f"density function {ref} not found in any jar")
            dfs[ref] = body
            missing.append(ref)
        if not missing:
            return added
        added += len(missing)


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
        # values are never DF references. Terralith writes some minecraft:
        # refs BARE ("overworld/temperature") — qualify before lookup.
        if parent_key not in ("type", "noise") and ID_RE.match(node):
            ref = qualify(node)
            if ref in df_ids:
                return f"adventure:{prefix}/{ref.replace(':', '/')}"
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
    """Per-file guard: every surviving external ref must be a noise reference
    (same-id copies are emitted for those); a tectonic:/terralith: DF ref or
    a non-vanilla minecraft: DF ref means the clone pass missed something."""
    noise_refs, df_refs = set(), set()
    scan_refs(obj, noise_refs, df_refs)
    bad = sorted(
        r for r in df_refs
        if r.split(":", 1)[0] in ("tectonic", "terralith")
        or (r.startswith("minecraft:") and r.split(":", 1)[1] not in VANILLA_DFS)
    )
    if bad:
        raise SystemExit(f"{path}: unresolved density-function refs {bad}")


def source_noise_bytes(ref, tect_files, lz):
    """Raw bytes of a noise definition from its owning jar, or None."""
    ns, path = ref.split(":", 1)
    rel = f"data/{ns}/worldgen/noise/{path}.json"
    if ns == "tectonic":
        return tect_files.get(rel)
    try:
        return lz.read(rel)  # terralith: and terralith-invented minecraft: ids
    except KeyError:
        return None


def emit_noise_copies(noise_refs, tect_files, lz):
    """Same-id, byte-identical noise copies for every external reference.

    Ids are NEVER renamed: vanilla seeds a noise by MD5-hashing its id
    string, so a renamed copy generates different terrain. Byte-identical
    same-id duplicates are order-safe — whichever pack wins provides the
    same content. Vanilla-shipped minecraft: ids are skipped entirely: our
    copy would override VANILLA (not just the mod) whenever the owning mod
    is absent, silently changing the real overworld.
    """
    copied, skipped = [], []
    for ref in sorted(noise_refs):
        ns, path = ref.split(":", 1)
        if ns == "adventure":
            continue
        if ns == "minecraft" and path in VANILLA_NOISES:
            skipped.append(ref)
            continue
        raw = source_noise_bytes(ref, tect_files, lz)
        if raw is None:
            raise SystemExit(f"noise {ref} not found in any jar")
        dest = RES_DATA / ns / "worldgen/noise" / (path + ".json")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(raw)
        copied.append(ref)
    print(f"noise copies: {len(copied)} emitted "
          f"({sum(1 for r in copied if r.startswith('tectonic:'))} tectonic, "
          f"{sum(1 for r in copied if r.startswith('terralith:'))} terralith, "
          f"{sum(1 for r in copied if r.startswith('minecraft:'))} mod-invented minecraft), "
          f"{len(skipped)} vanilla ids left runtime-resolved")
    return copied


def final_audit(tect_files, lz):
    """Re-scan everything actually on disk and hard-fail on any gap."""
    noise_refs, df_refs = set(), set()
    emitted_dfs = set()
    for base, ns_prefix in ((OUT_ROOT / "density_function", "adventure:"),
                            (OUT_ROOT / "noise_settings", None)):
        for p in base.rglob("*.json"):
            scan_refs(json.loads(p.read_text()), noise_refs, df_refs)
            if ns_prefix:
                emitted_dfs.add(ns_prefix + str(p.relative_to(base))[:-len(".json")])
    problems = []
    for r in sorted(df_refs):
        ns, path = r.split(":", 1)
        if ns == "adventure":
            if r not in emitted_dfs:
                problems.append(f"dangling adventure DF ref {r}")
        elif ns == "minecraft":
            if path not in VANILLA_DFS:
                problems.append(f"non-vanilla minecraft DF ref {r}")
        else:
            problems.append(f"external DF ref {r}")
    for r in sorted(noise_refs):
        ns, path = r.split(":", 1)
        if ns == "minecraft" and path in VANILLA_NOISES:
            continue
        dest = RES_DATA / ns / "worldgen/noise" / (path + ".json")
        if not dest.is_file():
            problems.append(f"noise ref {r} has no emitted copy")
        elif dest.read_bytes() != source_noise_bytes(r, tect_files, lz):
            problems.append(f"noise copy {r} is not byte-identical to its source")
    if problems:
        raise SystemExit("final audit FAILED:\n  " + "\n  ".join(problems))
    print(f"final audit OK: {len(df_refs)} DF refs, {len(noise_refs)} noise refs, "
          f"all closed or vanilla-resolved")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tectonic-jar", "--jar", dest="tectonic_jar",
                    help="path to tectonic jar (skips download)")
    ap.add_argument("--terralith-jar", help="path to terralith jar (skips download)")
    args = ap.parse_args()

    zf, t_version = fetch_jar(args.tectonic_jar, "tectonic")
    lz, l_version = fetch_jar(args.terralith_jar, "terralith")

    # Generator-owned output only — everything else under data/ is
    # hand-authored (tags, structures, template pools, and the hand-written
    # noise_settings/void.json). See docstring.
    for owned in (OUT_ROOT / "density_function",
                  RES_DATA / "tectonic", RES_DATA / "terralith",
                  RES_DATA / "minecraft/worldgen/noise"):
        shutil.rmtree(owned, ignore_errors=True)
    for name in PRESETS:
        (OUT_ROOT / "noise_settings" / f"{name}.json").unlink(missing_ok=True)

    total_files = 0
    all_noise_refs = set()
    tect_files = {}
    for name, preset in PRESETS.items():
        dfs, noise_settings, tect_files = load_layers(zf, preset)
        pulled = expand_closure(dfs, noise_settings, lz)
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
            scan_refs(converted, all_noise_refs, set())
            dest.write_text(json.dumps(converted, indent=1) + "\n")
            total_files += 1

        ns = dict(noise_settings)
        ns["noise"] = preset["noise_block"]
        ns = transform(ns, preset, df_ids, name, dfs)
        check_output(ns, f"noise_settings/{name}.json")
        scan_refs(ns, all_noise_refs, set())
        dest = OUT_ROOT / "noise_settings" / f"{name}.json"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(ns, indent=1) + "\n")
        total_files += 1
        print(f"preset {name}: {len(dfs)} density functions "
              f"({pulled} pulled via closure) + noise settings")

    copied = emit_noise_copies(all_noise_refs, tect_files, lz)
    final_audit(tect_files, lz)
    print(f"generated {total_files} preset files + {len(copied)} noise copies "
          f"from {t_version} / {l_version}")


if __name__ == "__main__":
    main()
