#!/usr/bin/env python3
"""gen-void-preset.py — generate the adventure:void noise settings.

Derives data/adventure/worldgen/noise_settings/void.json from the shipped
wide.json: the CLIMATE half of the noise router (temperature, vegetation,
continents, erosion, depth, ridges) is kept verbatim — that is what makes
the multi-noise biome source produce a real, seed-varying layout — while
every terrain-shaping output is constant: final_density -1 means no block
ever places, so the world is pure void with a live biome field.

Re-run after regenerating wide.json (Tectonic pin bumps):
    python3 scripts/gen-void-preset.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NS_DIR = ROOT / "mods/custom-dimensions/src/main/resources/data/adventure/worldgen/noise_settings"

CLIMATE_KEYS = ("temperature", "vegetation", "continents", "erosion", "depth", "ridges")
ZERO_KEYS = ("barrier", "fluid_level_floodedness", "fluid_level_spread", "lava",
             "vein_toggle", "vein_ridged", "vein_gap")


def main():
    wide = json.loads((NS_DIR / "wide.json").read_text())
    router = {k: wide["noise_router"][k] for k in CLIMATE_KEYS}
    for k in ZERO_KEYS:
        router[k] = 0
    router["initial_density_without_jaggedness"] = -1
    router["final_density"] = -1

    void = {
        "aquifers_enabled": False,
        "default_block": {"Name": "minecraft:air"},
        "default_fluid": {"Name": "minecraft:air"},
        "disable_mob_generation": False,
        "legacy_random_source": False,
        "noise": wide["noise"],
        "noise_router": router,
        "ore_veins_enabled": False,
        "sea_level": -64,
        "spawn_target": wide.get("spawn_target", []),
        "surface_rule": {"type": "minecraft:sequence", "sequence": []},
    }
    out = NS_DIR / "void.json"
    out.write_text(json.dumps(void, indent=1) + "\n")
    print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
