#!/usr/bin/env python3
"""warmup_biomes.py — Dump biome params for ALL dimension families.

Boots a short-lived MC server, creates one dimension per family (nether,
end, paradise_lost), dumps biome params via RCON, and merges them into
biome_params.json. Runs once during warmup; cached for all future rolls.

Uses docker exec rcon-cli for ALL RCON commands — the Python RCON socket
enters a bad state after the boot warmup's create/destroy cycle.
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

FAMILIES = [
    ("minecraft:overworld", None, "overworld"),
    ("_warmup_nether", "nether", "nether"),
    ("_warmup_end", "end", "end"),
    ("_warmup_paradise", '"paradise_lost:paradise_lost"', "paradise_lost"),
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
        families.setdefault(e.get("family", "?"), set()).add(e["biome"])

    print(f"  Merged: {len(all_entries)} entries "
          f"({', '.join(f'{k}: {len(v)}' for k, v in sorted(families.items()))})",
          flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
