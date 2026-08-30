#!/usr/bin/env python3
"""Report spawn filters that name a palette rather than a place, or name a biome the dimension does not list.

Purpose:  `seedRoll.spawnFilter` answers one question — which biomes read as
          this dimension, so that a rolled spawn lands in one and the score
          reflects how much of the world is the thing it is named after.
          `Criteria.SpawnReadsAsNamesake` awards full marks when spawn sits in
          a filter biome and otherwise ramps the filter's combined share to a
          cap; `RollPipeline.spawnFromGrid` prefers a filter biome within twice
          the distance of the nearest ground. Both readings are of a PLACE.

          A filter listing most of the dimension's palette answers a different
          question. It cannot aim a spawn, because almost anywhere satisfies
          it, and it inflates the share until the criterion is free.

Context:  A place is 128-256 blocks across and covers a structure, the ground
          it stands on and its surroundings together, so it spans a small
          number of biomes rather than one. And a dimension has a budget of
          PLACES, not a share per biome: a pocket holds one at most, a 4096 up
          to sixteen. Both figures are stated in docs/design/biome-placement.md
          and BUDGET is fitted to them exactly.

          The shape this catches is mechanical rather than stylistic. Seventeen
          of the eighteen filters naming six or more biomes are the head of
          that dimension's own `biomes` array — the first entries typed, not a
          set anybody chose.

Third arm, ADVISORY: whether the named biomes hold any ground. That is a
          measurement, so it never gates. Where a consumer has a persisted
          `customdim facts` record for a dimension's shipped seed
          (`.seed-rolling/facts__<ns>_<slug>__<seed>.json`) the filter's summed
          share is read from it and reported. Absent a record the dimension is
          named as unmeasured, never assumed fine — see K7 before treating a
          small share as a defect.

Usage:    scripts/check-spawn-filters.py       # exits 1 on either arm
          CONSUMER_DIR=~/Projects/elfydd scripts/check-spawn-filters.py

Gotchas:  - The reserved four and the list-discarding types (`amplified`,
            `large_biomes`) carry an EMPTY `biomes` array by design, so the
            membership arm has nothing to check and skips them. That is not a
            pass, it is an abstention.
          - An overlay entry under `overrides` deep-merges over the platform
            default, so its border comes from the platform file when it states
            none. Reading the overlay alone gives every such dimension the
            pocket budget and over-reports.
          - BIOMES_PER_PLACE is the one judgement here. The budget formula is
            derived from the design doc's two stated anchors; three biomes per
            place is a reading of "the ground it stands on and its
            surroundings", not a measurement.
"""
import json
import glob
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import optional_consumer_dir  # noqa: E402

PLATFORM = "config/custom-dimensions/dimensions/*.json"
OVERLAY = "overlay/config/custom-dimensions/dimensions/*.json"

# One place, in biomes: the one it sits in plus its surroundings.
BIOMES_PER_PLACE = 3

# Only a ZERO share is reported. biome-placement.md refuses a per-biome minimum
# and K7 says narrow is not empty, so any other floor would be invented.
RESERVED = {"overworld": "minecraft:overworld", "the_nether": "minecraft:the_nether",
            "the_end": "minecraft:the_end", "paradise_lost": "paradise_lost:paradise_lost"}


def facts_shares(consumer, name, platform_cfg, overlay_cfg):
    """The persisted facts record for this dimension's shipped seed, if one exists."""
    if not consumer:
        return None
    seed = (overlay_cfg or {}).get("seed", platform_cfg.get("seed"))
    if seed is None:
        return None
    dim = RESERVED.get(name, "adventure:" + name).replace(":", "_")
    path = consumer / ".seed-rolling" / f"facts__{dim}__{seed}.json"
    if not path.is_file():
        return None
    try:
        doc = json.loads(path.read_text())
    except json.JSONDecodeError:
        return None
    return (doc.get("biomes") or {}).get("shares"), doc.get("measuredAt") or ""


def budget(border):
    """Places a dimension of this border can hold.

    Fitted to docs/design/biome-placement.md's two anchors: 1024 holds one
    place, 4096 holds sixteen. The pocket clause falls out as the floor.
    """
    if not border or border <= 0:
        return None
    return max(1, round((border / 1024.0) ** 2))


def entries(pattern):
    """(name, config) per dimension file, thumbnails excluded."""
    for f in sorted(glob.glob(pattern)):
        name = os.path.basename(f)[:-5]
        if name.endswith("_thumb"):
            continue
        doc = json.load(open(f))
        yield name, doc.get("overrides", doc)


def biome_ids(cfg):
    return [b if isinstance(b, str) else b.get("id") for b in (cfg.get("biomes") or [])]


def spawn_filter(cfg):
    return ((cfg.get("seedRoll") or {}).get("spawnFilter")) or []


def main():
    platform = dict(entries(PLATFORM))
    sources = [("platform", platform)]
    consumer = optional_consumer_dir()
    if consumer:
        sources.append(("overlay", dict(entries(str(consumer / OVERLAY)))))

    overlay = dict(sources[1][1]) if len(sources) > 1 else {}
    unlisted = palettes = scanned = 0
    measured = unmeasured = inert = 0
    held_shares = []
    stamps = []
    for where, configs in sources:
        for name, cfg in sorted(configs.items()):
            sf = spawn_filter(cfg)
            if not sf:
                continue
            scanned += 1

            listed = biome_ids(cfg) or biome_ids(platform.get(name, {}))
            missing = [b for b in sf if b not in listed] if listed else []
            if missing:
                unlisted += len(missing)
                for b in missing:
                    print(f"  {name:26s} [{where:8s}] {b} is in spawnFilter but not in biomes")
                print("      a filter biome the dimension never lists cannot be curated into"
                      " the world, so it scores only where the base source happens to place it")

            if where == "platform":
                got = facts_shares(consumer, name, cfg, overlay.get(name))
                if got is None:
                    unmeasured += 1
                else:
                    shares, stamp = got
                    stamps.append(stamp)
                    measured += 1
                    held = sum(shares.get(b, 0.0) for b in sf)
                    held_shares.append(held)
                    if held == 0.0:
                        inert += 1
                        print(f"  {name:26s} [measured] every one of its {len(sf)} filter biomes"
                              f" holds no cell — the share branch can never score")

            border = cfg.get("borders", {}).get("player") \
                or platform.get(name, {}).get("borders", {}).get("player")
            bud = budget(border)
            if bud is None:
                continue
            allowed = BIOMES_PER_PLACE * bud
            if len(sf) > allowed:
                palettes += 1
                print(f"  {name:26s} [{where:8s}] spawnFilter names {len(sf)} biomes;"
                      f" a {border} border holds {bud} place(s), so at most {allowed}")
                print("      name the biome that IS the place and the ground around it, not the"
                      " palette — a filter satisfied almost anywhere cannot aim a spawn")

    print(f"{scanned} spawn filter(s), {unlisted} naming an unlisted biome,"
          f" {palettes} naming a palette")
    # Advisory only. A share is a measurement and this is a static gate, so the
    # count is printed whether it is zero or not — a silent arm is an unrun one.
    med = sorted(held_shares)[len(held_shares) // 2] if held_shares else 0.0
    print(f"share check (advisory): {measured} measured against a facts record,"
          f" {unmeasured} with no record, {inert} holding nothing at all,"
          f" median share {med * 100:.1f}%")
    # A record is whatever the last `customdim facts` on that seed wrote, from
    # whatever jar was installed then. Print the span so a population mixed
    # across two jars is visible rather than silently averaged.
    if stamps:
        lo, hi = min(stamps), max(stamps)
        print(f"  records written {lo[:19]}Z .. {hi[:19]}Z"
              + ("  <- SPANS MORE THAN AN HOUR: may mix two jars" if lo[:13] != hi[:13] else ""))
    if unlisted or palettes:
        return 1
    print("✓ Every spawn filter names a place, from biomes its dimension lists")
    return 0


if __name__ == "__main__":
    sys.exit(main())
