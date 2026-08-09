#!/usr/bin/env python3
"""warmup_biomes.py — Dump biome params for ALL dimension families.

Boots a short-lived MC server, dumps biome params for each family's BASE
WORLD via RCON, and merges them into biome_params.json. Runs once during
warmup; cached for all future rolls.

Each family dumps its base world directly. Base worlds exist from the first
tick (TROUBLESHOOTING.md#t18), so no dimension is created or destroyed.

A family whose biomes reach the world through TerraBlender also yields a
`_tbRegions` table; one whose biomes come from the dimension's own
multi-noise parameter list does not, and its absence is not a fault. The
summary reports which tables were produced — a table missing its region half
otherwise stays invisible until a roll has scored against it (T29).

Uses docker exec rcon-cli for ALL RCON commands — the Python RCON socket
enters a bad state after the boot warmup's RCON cycle.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from seed_worker import boot, docker, prepare_boot_dir  # noqa: E402

#: (dimension id to dump, create type or None, family tag). A create type is
#: retained only as a fallback for a family with no base world on this stack.
FAMILIES = [
    ("minecraft:overworld", None, "overworld"),
    ("minecraft:the_nether", None, "nether"),
    ("minecraft:the_end", None, "end"),
    ("paradise_lost:paradise_lost", None, "paradise_lost"),
]


def rcon(container, cmd):
    """Run one RCON command via docker exec rcon-cli (fresh connection)."""
    r = subprocess.run(
        ["docker", "exec", container, "rcon-cli", cmd],
        capture_output=True, text=True, timeout=30)
    return r.stdout.strip()


def dump_family(container, dim_id, workdir):
    out = rcon(container, f"customdim dump-biome-params {dim_id}")
    if "Dumped" not in out:
        print(f"  dump-biome-params {dim_id} failed: {out[:120]}", flush=True)
        return []
    params_path = Path(workdir) / "config" / "custom-dimensions" / "biome_params.json"
    if not params_path.exists():
        print(f"  biome_params.json not written for {dim_id}", flush=True)
        return []
    entries = json.loads(params_path.read_text())
    print(f"  {dim_id}: {len(entries)} biome entries", flush=True)
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", required=True)
    ap.add_argument("--mvconfig", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--memory", default=os.environ.get("ROLL_MEMORY", "10G"))
    args = ap.parse_args()

    container = "seedrollall-warmup-biomes"
    prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
    print("  Booting MC server for biome param dump...", flush=True)
    rcon_obj = boot("warmup", container, args.workdir, args.memory)
    if rcon_obj is None:
        print("  ERROR: server boot failed", flush=True)
        docker("rm", "-f", container, check=False)
        return 1
    rcon_obj.close()

    ns = "adventure"
    all_entries = []

    for dim_id, create_type, family_tag in FAMILIES:
        if create_type is not None:
            out = rcon(container, f"customdim create {dim_id} {create_type} 1 - - -")
            if "Queued" not in out and "Created" not in out:
                print(f"  failed to create {dim_id}: {out[:120]}", flush=True)
                continue
            for _ in range(12):
                if "Seed" in rcon(container, f"execute in {ns}:{dim_id} run seed"):
                    break
                time.sleep(2)
            else:
                print(f"  {dim_id} never became queryable", flush=True)
                continue
            dump_dim = f"{ns}:{dim_id}"
        else:
            dump_dim = dim_id

        entries = dump_family(container, dump_dim, args.workdir)
        for e in entries:
            if "biome" in e:
                e["family"] = family_tag
        all_entries.extend(entries)

        if create_type is not None:
            rcon(container, f"customdim destroy {dim_id}")
            time.sleep(1)

    docker("rm", "-f", container, check=False)

    if not all_entries:
        print("  ERROR: no biome entries dumped", flush=True)
        return 1

    Path(args.output).write_text(json.dumps(all_entries, indent=2) + "\n")

    families = {}
    for e in all_entries:
        if "biome" in e:
            families.setdefault(e.get("family", "?"), set()).add(e["biome"])

    print(f"  Merged: {len(all_entries)} entries "
          f"({', '.join(f'{k}: {len(v)}' for k, v in sorted(families.items()))})",
          flush=True)

    # A table's region half is invisible in the entry count, so report it.
    regions = {}
    for e in all_entries:
        if "_tbRegions" in e:
            s = e["_tbRegions"]
            regions[s.get("type", "overworld")] = len(s.get("regions") or [])
    if regions:
        print("  TB region tables: "
              + ", ".join(f"{k}: {v} regions" for k, v in sorted(regions.items())),
              flush=True)
    else:
        print("  WARNING: no _tbRegions tables dumped — TerraBlender-placed "
              "biomes will be scored against the flat union only (T29)",
              flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
