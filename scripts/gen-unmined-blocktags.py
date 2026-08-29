#!/usr/bin/env python3
"""Generate uNmINeD block tags for modded blocks the map renderer draws pink.

Purpose:  uNmINeD colours a block it has no style for in pink. Its default
          stylesheet already carries cross-namespace patterns (**leaves,
          **sapling, *:*_slab and ~160 more), so only the blocks matching
          none of them need tagging here. Tagged blocks inherit the vanilla
          style for that tag.

Context:  Reads config/custom-dimensions/extractors/blocks.json (the mod-jar
          block catalogue from extract-blocks.py) and writes a blocktags file
          shipped to /opt/unmined/config/adventure/ by the unmined-render
          image. Tags are additive and last-loaded wins, so a redundant rule
          is harmless; a missing one leaves the block pink.

Usage:    ./scripts/gen-unmined-blocktags.py [--check]
          --check exits 1 if the committed file is stale.

Gotchas:  Rules are ordered — first match wins, so put specific before
          generic. Tag names must exist in uNmINeD's default stylesheet
          (config/default/default.blocktags.minecraft.js); an unknown tag is
          accepted silently and colours nothing.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOGUE = ROOT / "config/custom-dimensions/extractors/blocks.json"
OUT = ROOT / "docker/unmined-render/unmined-config/custom.blocktags.txt"

# Worldgen mods: their blocks appear in terrain, so each needs a real tag.
# Everything else is furniture/building sets — the catch-all below is right
# for those, and #artificial beats pink.
WORLDGEN_NAMESPACES = {
    "regions_unexplored", "natures_spirit", "paradise_lost", "terralith",
    "incendium", "nullscape", "biomesoplenty", "byg", "blue_skies",
    "deeperdarker", "yungsapi", "betternether", "betterend",
}

# Ordered: (token, match mode, tag). First match wins, specific before generic.
#   "end" - name equals token or ends in _<token>
#   "sub" - token appears anywhere in the name
# Tags must exist in uNmINeD's default stylesheet; --check validates them.
RULES: list[tuple[str, str, str]] = [
    # --- ore and rock -----------------------------------------------------
    ("ore", "end", "#ore"),
    ("nylium", "sub", "#ground"),
    ("basalt", "sub", "#darkrock"),
    ("blackstone", "sub", "#darkrock"),
    ("obsidian", "sub", "#darkrock"),
    ("ashen", "sub", "#darkrock"),
    ("chert", "sub", "#rock"),
    ("chalk", "sub", "#rock"),
    ("kaolin", "sub", "#rock"),
    ("travertine", "sub", "#rock"),
    ("argillite", "sub", "#rock"),
    ("floestone", "sub", "#rock"),
    ("heliolith", "sub", "#rock"),
    ("levita", "sub", "#rock"),
    ("burnished_stone", "sub", "#rock"),
    ("surtrum", "sub", "#rock"),
    ("nitra", "sub", "#rock"),
    ("permafrost", "sub", "#rock"),
    ("calcite", "sub", "#rock"),
    ("prismaglass", "sub", "#crystal"),
    ("prismarite", "sub", "#crystal"),
    ("earlight", "sub", "#crystal"),
    ("redstone_bud", "sub", "#crystal"),
    ("redstone_bulb", "sub", "#crystal"),
    ("icicle", "sub", "#ice"),
    ("cluster", "sub", "#crystal"),
    ("cloud", "sub", "#snow"),
    # --- soil -------------------------------------------------------------
    ("sandstone", "sub", "#sand"),
    ("sandy_soil", "sub", "#sand"),
    ("mud", "end", "#mud"),
    ("silt", "sub", "#dirt"),
    ("peat", "sub", "#dirt"),
    ("dirt", "sub", "#dirt"),
    ("frozen_path", "sub", "#path"),
    ("placement", "end", "#ground"),
    ("ash", "end", "#gravel"),
    # --- plants -----------------------------------------------------------
    ("leaf_litter", "sub", "#leaves"),
    ("leaf_pile", "sub", "#leaves"),
    ("frond", "sub", "#leaves"),
    ("branch", "sub", "#branch"),
    ("bioshroom", "sub", "#mushroom"),
    ("mushroom", "sub", "#mushroom"),
    ("lily_pad", "sub", "#lilypad"),
    ("azolla", "sub", "#lilypad"),
    ("duckweed", "sub", "#lilypad"),
    ("helvola", "sub", "#lilypad"),
    ("moss", "sub", "#mossy"),
    ("lichen", "sub", "#lichen"),
    ("fern", "sub", "#grass"),
    ("grass", "sub", "#grass"),
    ("shrub", "sub", "#bush"),
    ("bush", "sub", "#bush"),
    ("cactus", "sub", "#flora"),
    ("succulent", "sub", "#flora"),
    ("cattail", "sub", "#flora"),
    ("reeds", "sub", "#flora"),
    ("bulrush", "sub", "#flora"),
    ("alluaudia", "sub", "#flora"),
    ("brimsprout", "sub", "#flora"),
    ("tassel", "sub", "#flora"),
    ("sprig", "sub", "#flora"),
    ("liverwort", "sub", "#flora"),
    ("flax", "sub", "#flora"),
    ("clover", "sub", "#flora"),
    ("hyssop", "sub", "#flora"),
    ("shamrock", "sub", "#flora"),
    ("beard", "sub", "#flora"),
    ("dropleaf", "sub", "#flora"),
    ("elephant_ear", "sub", "#flora"),
    ("root", "sub", "#roots"),
    ("petals", "sub", "#flower"),
    ("blossom", "sub", "#flower"),
    ("wisteria", "sub", "#flower"),
    ("bloom", "sub", "#flower"),
    ("melon", "sub", "#fruit"),
    ("coconut", "sub", "#fruit"),
    # named flora no generic pattern catches
    ("anemone", "sub", "#flower"), ("begonia", "sub", "#flower"),
    ("bluebell", "sub", "#flower"), ("carnation", "sub", "#flower"),
    ("iris", "end", "#flower"), ("bleeding_heart", "sub", "#flower"),
    ("marigold", "sub", "#flower"), ("foxglove", "sub", "#flower"),
    ("gardenia", "sub", "#flower"), ("hibiscus", "sub", "#flower"),
    ("lavender", "sub", "#flower"), ("tiger_lily", "sub", "#flower"),
    ("dandelion", "sub", "#flower"), ("rose", "sub", "#flower"),
    ("daisy", "sub", "#flower"), ("day_lily", "sub", "#flower"),
    ("corpse_flower", "sub", "#flower"), ("dorcel", "sub", "#flower"),
    ("dusktrap", "sub", "#flower"), ("fireweed", "sub", "#flower"),
    ("mallow", "sub", "#flower"), ("meadow_sage", "sub", "#flower"),
    ("poppy", "sub", "#flower"), ("tsubaki", "sub", "#flower"),
    ("waratah", "sub", "#flower"), ("trillium", "sub", "#flower"),
    ("amadrys", "sub", "#flower"), ("ataraxia", "sub", "#flower"),
    ("drigean", "sub", "#flower"), ("luminar", "sub", "#flower"),
    ("honey_nettle", "sub", "#flower"), ("ancient_flower", "sub", "#flower"),
    ("cloudsbluff", "sub", "#flower"), ("glister", "sub", "#flower"),
    ("nettle", "sub", "#flora"), ("vent", "end", "#darkrock"),
    # --- wood and built ---------------------------------------------------
    ("mosaic", "sub", "#planks"),
    ("thatch", "sub", "#artificial"),
    ("stripped", "sub", "#log"),
    ("log", "end", "#log"),
    ("roof", "sub", "#masonry"),
    ("column", "sub", "#masonry"),
    ("bridge", "sub", "#masonry"),
    ("wool", "end", "#wool"),
    ("webbing", "sub", "#artificial"),
    # --- stragglers: named blocks no pattern above reaches ----------------
    ("cauldron", "sub", "#artificial"),
    ("paper_panel", "sub", "#artificial"),
    ("bookshelf", "sub", "#artificial"),
    ("campfire", "sub", "#artificial"),
    ("incubator", "sub", "#artificial"),
    ("cheesecake", "sub", "#artificial"),
    ("tree_tap", "sub", "#artificial"),
    ("door_extension", "sub", "#artificial"),
    ("bowl", "end", "#artificial"),
    ("bars", "end", "#artificial"),
    ("chain", "end", "#artificial"),
    ("nest", "end", "#artificial"),
    ("portal", "end", "#artificial"),
    ("shell", "end", "#rock"),
    ("pointed_redstone", "sub", "#crystal"),
    ("log_magma", "sub", "#log"),
    ("log_transition", "sub", "#log"),
    ("lotus", "sub", "#lilypad"),
    ("bundle", "sub", "#flora"),
    ("protea", "sub", "#flower"),
    ("snapdragon", "sub", "#flower"),
    ("hyacinth", "sub", "#flower"),
    ("aster", "end", "#flower"),
    # --- families the vendor stylesheet is assumed to cover; tagged anyway,
    # because a redundant tag is free and a missed one renders pink ---------
    ("leaves", "end", "#leaves"),
    ("sapling", "end", "#sapling"),
    ("hanging_sign", "sub", "#sign"),
    ("wall_sign", "sub", "#sign"),
    ("sign", "end", "#sign"),
    ("planks", "end", "#planks"),
    ("slab", "end", "#slab"),
    ("stairs", "end", "#stairs"),
    ("trapdoor", "end", "#trapdoor"),
    ("door", "end", "#door"),
    ("pressure_plate", "end", "#pressureplate"),
    ("button", "end", "#button"),
    ("fence_gate", "end", "#fencegate"),
    ("fence", "end", "#fence"),
    ("wood", "end", "#wood"),
    ("lantern", "end", "#light"),
    # --- remaining named flora --------------------------------------------
    ("snowbelle", "sub", "#flower"),
    ("lupine", "sub", "#flower"),
    ("heather", "sub", "#flower"),
    ("wildflower", "sub", "#flower"),
    ("coneflower", "sub", "#flower"),
    ("bearberries", "sub", "#bush"),
    ("sprouts", "end", "#sprout"),
    ("flowers", "end", "#flower"),
    ("vines", "sub", "#vine"),
    ("polypore", "sub", "#mushroom"),
    ("sporecap", "sub", "#mushroom"),
    ("pink_sand", "sub", "#sand"),
    ("barley", "sub", "#crops"),
    ("turnip", "sub", "#crops"),
    ("farmland", "end", "#soil"),
    ("torch", "end", "#torch"),
    ("crystalite", "sub", "#crystal"),
    ("bulbs", "end", "#flower"),
    ("cheese", "sub", "#artificial"),
    ("pizza", "sub", "#artificial"),
    ("paper_block", "sub", "#artificial"),
    ("amber_tile", "sub", "#artificial"),
    ("olvite", "sub", "#artificial"),
    ("cherine", "sub", "#artificial"),
]

# Anything in a non-worldgen namespace that no rule matched. Furniture and
# building blocks read acceptably as #artificial and never as pink.
CATCH_ALL = "#artificial"

# Last resort for a worldgen namespace. Terrain-plausible and, above all,
# not pink — uNmINeD colours anything it has no style for bright pink.
WORLDGEN_FALLBACK = "#rock"


def tag_for(name: str) -> str | None:
    for token, mode, tags in RULES:
        if mode == "end":
            if name == token or name.endswith("_" + token):
                return tags
        elif token in name:
            return tags
    return None


def build() -> str:
    blocks = json.loads(CATALOGUE.read_text())["blocks"]
    lines: dict[str, list[str]] = {}
    for block in blocks:
        if block.startswith("minecraft:"):
            continue
        namespace, name = block.split(":", 1)
        tags = tag_for(name) or (
            WORLDGEN_FALLBACK if namespace in WORLDGEN_NAMESPACES else CATCH_ALL)
        if tags:
            lines.setdefault(tags, []).append(block)
    out = [
        "// Generated by scripts/gen-unmined-blocktags.py — do not edit by hand.",
        "// Modded blocks uNmINeD's default stylesheet has no pattern for.",
        "// Regenerate after any mod change: ./scripts/gen-unmined-blocktags.py",
        "",
    ]
    for tags in sorted(lines):
        out.append(f"// {tags}")
        for block in sorted(lines[tags]):
            out.append(f"{block} = {tags}")
        out.append("")
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="fail if the committed file is stale")
    args = ap.parse_args()
    content = build()
    rules = content.count(" = ")
    if args.check:
        if not OUT.exists() or OUT.read_text() != content:
            print(f"STALE: {OUT.relative_to(ROOT)} — run ./scripts/gen-unmined-blocktags.py", file=sys.stderr)
            return 1
        print(f"OK: {rules} rules")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(content)
    print(f"wrote {OUT.relative_to(ROOT)}: {rules} rules")
    return 0


if __name__ == "__main__":
    sys.exit(main())
