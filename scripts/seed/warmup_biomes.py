#!/usr/bin/env python3
"""warmup_biomes.py — Dump biome params for ALL dimension families.

Boots a short-lived MC server, creates one dimension per family (nether,
end, paradise_lost), dumps biome params via RCON, and merges them into
biome_params.json. Runs once during warmup; cached for all future rolls.

Usage (called by roll-all.sh warmup):
    python3 warmup_biomes.py --workdir <dir> --output <biome_params.json> --memory 10G
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from seed_worker import (  # noqa: E402
    boot, docker, log, prepare_boot_dir, RconTimeout, RconClosed,
)

FAMILIES = [
    ("minecraft:overworld", None, "overworld"),
    ("_warmup_nether", "nether", "nether"),
    ("_warmup_end", "end", "end"),
    ("_warmup_paradise", '"paradise_lost:paradise_lost"', "paradise_lost"),
]


def dump_family(rcon, dim_id, workdir, container=None):
    """Run dump-biome-params for one dimension, return the JSON entries.
    Uses docker exec rcon-cli to avoid RCON socket state issues."""
    if container:
        r = docker("exec", container, "rcon-cli",
                   f"customdim dump-biome-params {dim_id}", check=False)
        out = r.stdout or ""
    else:
        try:
            out = rcon.cmd(f"customdim dump-biome-params {dim_id}")
        except (RconTimeout, RconClosed) as exc:
            print(f"  RCON error dumping {dim_id}: {exc}", flush=True)
            return []
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

    wid = "warmup"
    container = "seedrollall-warmup-biomes"

    prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
    print("  Booting MC server for biome param dump...", flush=True)
    rcon = boot(wid, container, args.workdir, args.memory)
    if rcon is None:
        print("  ERROR: server boot failed", flush=True)
        docker("rm", "-f", container, check=False)
        return 1

    ns = "adventure"
    all_entries = []

    for dim_id, create_type, family_tag in FAMILIES:
        if create_type is not None:
            out = rcon.cmd(f"customdim create {dim_id} {create_type} 1 - - -")
            if "Queued" not in (out or "") and "Created" not in (out or ""):
                print(f"  failed to create {dim_id}: {out[:120]}", flush=True)
                continue
            for _ in range(12):
                if "Seed" in rcon.cmd(f"execute in {ns}:{dim_id} run seed"):
                    break
                time.sleep(2)
            else:
                print(f"  {dim_id} never became queryable", flush=True)
                continue
            dump_dim = f"{ns}:{dim_id}"
        else:
            dump_dim = dim_id

        entries = dump_family(rcon, dump_dim, args.workdir, container=container)
        for e in entries:
            e["family"] = family_tag
        all_entries.extend(entries)

        if create_type is not None:
            rcon.cmd(f"customdim destroy {dim_id}")
            time.sleep(1)

    docker("rm", "-f", container, check=False)

    if not all_entries:
        print("  ERROR: no biome entries dumped", flush=True)
        return 1

    Path(args.output).write_text(json.dumps(all_entries, indent=2) + "\n")

    families = {}
    for e in all_entries:
        b = e["biome"]
        if any(x in b for x in ("nether", "crimson", "warped", "soul_sand", "basalt")):
            families.setdefault("nether", set()).add(b)
        elif any(x in b for x in ("the_end", "end_", "small_end")):
            families.setdefault("end", set()).add(b)
        elif "paradise" in b or "aurel" in b:
            families.setdefault("paradise_lost", set()).add(b)
        else:
            families.setdefault("overworld", set()).add(b)

    print(f"  Merged: {len(all_entries)} entries "
          f"({', '.join(f'{k}: {len(v)}' for k, v in sorted(families.items()))})",
          flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
