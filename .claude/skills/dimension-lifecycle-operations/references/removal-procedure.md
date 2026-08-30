---
title: Dimension Removal Procedure
description: The full level.dat scrub for fully removing a runtime custom-dimensions dimension without wedging the next boot, step by step, with the ordering requirement.
tags: [level.dat, nbtlib, removal, world-dir, portal_links, fingerprint, boot-wedge]
---

# Dimension Removal Procedure

Proven locally 2026-07-24, on a fixture-cleanup pass converting several test dimensions. This is the only procedure that fully removes a dimension without leaving a boot-time wedge behind. **Every deletion below must complete before the next `docker start` — the mod reads the config directory, `level.dat`, and the state files fresh at boot, and will happily re-create anything it still finds.**

## Why the shortcuts don't work

- **Deleting the config file alone**: the mod reconciles orphans (any managed-namespace world no longer in the config gets unloaded), but the `level.dat` registry entry and the world directory both survive — the dimension comes back the moment anything references it again, and if the world directory is also gone, the next boot regenerates it from scratch.
- **`customdim destroy <name>`**: unloads the world from memory only. It does not touch `level.dat`. Confirmed by reading the command's own behaviour and by the trap in `mods/custom-dimensions/README.md`: "Does **not** scrub its `level.dat` entry."
- **Deleting the world directory without touching `level.dat`**: the dimension's `Data.WorldGenSettings.dimensions` entry is still there. The next boot sees the registry key, recreates the `ServerWorld`, and — because there's no world directory — **regenerates spawn chunks from nothing, on that very boot**. If an Epic Dungeons dungeon happens to generate in the regenerated spawn area, the boot itself hangs (see the wedge in `SKILL.md`). This is not a hypothetical: it was hit twice in one session (2026-07-24) doing exactly this "cleanup".

## The procedure

Run this on the machine that hosts the world (local Mac for `./dev`, the production server over SSH for a real removal — treat a production run as "confirm before proceeding" per `AGENTS.md`, since it touches `data/` directly).

### 1. Stop mc

```bash
docker stop -t 90 mc
```

Do not skip the graceful stop — a mid-write `level.dat` is not something you want to hand-edit.

### 2. Back up `level.dat`

```bash
cp data/world/level.dat data/world/level.dat.bak.$(date +%Y%m%d%H%M%S)
```

This is the one file in this whole procedure you cannot regenerate if the edit goes wrong. Don't proceed without the copy existing on disk.

### 3. Get `nbtlib` into a scratch venv

`level.dat` is gzipped NBT. There's no existing script in this repo for this — it's a manual, occasional operation, not automated tooling.

```bash
python3 -m venv /tmp/nbt-scratch
source /tmp/nbt-scratch/bin/activate
pip install nbtlib
```

### 4. Delete the dimension's key from `Data.WorldGenSettings.dimensions`

```python
import nbtlib

nbt_file = nbtlib.load("data/world/level.dat")
dimensions = nbt_file["Data"]["WorldGenSettings"]["dimensions"]

# The key is the full namespaced id, e.g. "adventure:the_gauntlet"
key_to_remove = "adventure:the_slug_being_removed"
if key_to_remove in dimensions:
    del dimensions[key_to_remove]
    nbt_file.save("data/world/level.dat")
    print(f"Removed {key_to_remove}")
else:
    print(f"{key_to_remove} not found — check the namespace and slug")
```

Verify before saving over the real file if you have any doubt about the key spelling — an `nbtlib.load()`/inspect pass costs nothing and a wrong key silently does nothing (the `if` guard above at least tells you when that happens; don't remove that guard).

### 5. Delete every trace, in one pass, before starting anything

All of the following must be gone. Missing any one of them means the mod finds something to rebuild from at boot.

```bash
# World directory itself
rm -rf "data/world/dimensions/adventure/the_slug_being_removed"

# Platform/consumer config file(s) — check both locations
rm -f "config/custom-dimensions/dimensions/the_slug_being_removed.json"
rm -f "data/config/custom-dimensions/dimensions/the_slug_being_removed.json"
rm -f "overlay/config/custom-dimensions/dimensions/the_slug_being_removed.json"   # consumer repos only

# Fingerprint entry (JSON — edit out the dimension's key, don't delete the whole file
# unless every dimension in it is being removed)
python3 -c "
import json
p = 'data/config/custom-dimensions-fingerprints.json'
d = json.load(open(p))
d.pop('adventure:the_slug_being_removed', None)
json.dump(d, open(p, 'w'), indent=2)
"

# portal_links.json — remove any record whose dimension field matches
python3 -c "
import json
p = 'data/config/portal_links.json'
d = json.load(open(p))
# Structure varies by record type (source-zone-v1, aura-site-v1, etc.) — filter
# defensively on any field that names the dimension being removed.
def references_dim(record, dim):
    return any(v == dim for v in record.values() if isinstance(v, str))
before = len(d) if isinstance(d, list) else None
if isinstance(d, list):
    d = [r for r in d if not references_dim(r, 'adventure:the_slug_being_removed')]
    print(f'{before} -> {len(d)} records')
json.dump(d, open(p, 'w'), indent=2)
"
```

`portal_links.json`'s exact shape depends on the mod version (source-zone/aura-site/anchor records all coexist) — inspect the file's actual structure before running a blind filter on production; a dry-run print of what would be removed costs nothing and this file is "managed automatically; do not edit by hand" under normal operation, so treat a manual edit as an exception, not a pattern to repeat.

### 6. Start mc

```bash
docker start mc
```

Only now. Confirm the dimension is genuinely gone and nothing wedged:

```bash
docker exec -i mc rcon-cli "execute in adventure:the_slug_being_removed run seed"   # expect "Unknown dimension"
docker inspect mc --format 'Health={{.State.Health.Status}} Started={{.State.StartedAt}}'
docker exec mc cat /data/logs/latest.log | grep -iE 'Error upgrading chunk|DungeonZombie|Timed out waiting for world statistics'
```

A moved `StartedAt`, or any of those log lines, means something regenerated when it shouldn't have — re-check that every file in step 5 was actually removed (a typo'd slug in one of the five paths is the most common cause of a partial scrub).

## Ordering summary

1. `docker stop -t 90 mc`
2. Back up `level.dat`
3. `pip install nbtlib` in a scratch venv
4. Delete the dimension's key from `Data.WorldGenSettings.dimensions`
5. Delete: world directory, config file(s), fingerprint entry, `portal_links.json` records
6. `docker start mc`

Steps 4 and 5 have no required order relative to each other, but both must be **fully complete** before step 6. Never start mc between any of steps 2–5.
