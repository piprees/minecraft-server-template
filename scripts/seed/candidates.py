#!/usr/bin/env python3
"""candidates.py — per-dimension candidate storage for the seed roller (v4 Phase 5).

One JSON file per roll target at {config_dir}/candidates/{slug}.json:

  {
    "configHash": "a1b2c3d4",          # md5[:8] of the dimension config minus seed/spawn
    "candidates": {
      "<seed>": {
        "measurements": { ... },        # raw locate/terrain metrics (seed-specific, never stale)
        "fingerprint": "17be85b67f59",  # generation fingerprint the seed was MEASURED under
                                        # (dimension_profiles.generation_fingerprint) — drift
                                        # vs the current config means the measurements describe
                                        # a world this config no longer generates (warned at
                                        # status/finalise, never deleted)
        "scores": {                     # keyed by config hash — a config change makes old
          "a1b2c3d4": {"total": 77.6, "namesake": 1.0, ..., "timestamp": "..."}
          }                             # scores stale WITHOUT invalidating measurements
      }
    },
    "winner": "<seed>",                 # current best (or human pick)
    "winnerPinned": false,              # true = human pick, survives re-scoring
    "rejected": {"<seed>": "reason"},   # spawn-filter rejects (never re-roll)
    "abandoned": {"<seed>": "reason"}   # RCON failures (not scored, never re-roll)
  }

Workers never write here (open question 4: per-worker spool files merge in
at finalise time — no locking). Writes are atomic: .tmp + rename.
"""
import hashlib
import json
from pathlib import Path

# Keys that do NOT change a candidate's score: the seed/spawn are the
# candidate itself, identity keys don't affect scoring.
VOLATILE_KEYS = ("seed", "spawn", "name", "dimensionId")


def candidates_dir(config_dir):
    return Path(config_dir) / "candidates"


def config_hash(dim_config):
    """md5[:8] of the dimension's config entry minus seed/spawn — the
    scoring-relevant fingerprint. Changing biomes/structures/difficulty/
    seedRoll changes the hash; existing measurements stay valid but their
    scores go stale and are recomputed by `rescore` without re-rolling."""
    slim = {k: v for k, v in (dim_config or {}).items() if k not in VOLATILE_KEYS}
    return hashlib.md5(
        json.dumps(slim, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:8]


def empty_store():
    return {"configHash": None, "candidates": {}, "winner": None,
            "winnerPinned": False, "rejected": {}, "abandoned": {}}


def load_store(path):
    path = Path(path)
    store = empty_store()
    if not path.exists():
        return store
    try:
        data = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return store
    if isinstance(data, dict):
        for key, default in store.items():
            value = data.get(key)
            if value is None:
                data[key] = default
            elif isinstance(default, dict) and not isinstance(value, dict):
                data[key] = default
            elif key == "winnerPinned":
                data[key] = bool(value)
        return data
    return store


def save_store(path, store):
    """Atomic write: .tmp + rename (same directory, same filesystem)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(store, indent=2, ensure_ascii=False) + "\n")
    tmp.replace(path)


def merge_rows(store, seed, rows, fingerprint=None):
    """Fold one measured candidate's raw metric rows into the store.
    Spawn-filter rejects land in rejected{} with a derived reason.
    `fingerprint` (the generation fingerprint the seed was measured under)
    is stamped on NEW candidates only — an existing candidate keeps the
    stamp from its own measurement run."""
    seed = str(seed)
    if rows.get("rejected") == "1":
        if seed not in store["rejected"]:
            fdist = rows.get("spawn_filter_dist")
            try:
                dist = int(float(fdist))
            except (TypeError, ValueError):
                dist = -1
            store["rejected"][seed] = (
                f"spawn filter: nearest biome at {dist} blocks" if dist >= 0
                else "spawn filter: no matching biome found")
        return
    is_new = seed not in store["candidates"]
    cand = store["candidates"].setdefault(seed, {"measurements": {}, "scores": {}})
    cand.setdefault("measurements", {}).update(rows)
    cand.setdefault("scores", {})
    if fingerprint and is_new:
        cand["fingerprint"] = fingerprint


def record_score(store, seed, chash, total, parts, timestamp):
    cand = store["candidates"].get(str(seed))
    if cand is None:
        return
    cand.setdefault("scores", {})[chash] = {
        "total": total, **parts, "timestamp": timestamp}


def seen_seeds(config_dir):
    """Every seed banked in candidate files (measured, rejected, or
    abandoned) — the roller must never re-roll any of them."""
    seen = set()
    cdir = candidates_dir(config_dir)
    if not cdir.is_dir():
        return seen
    for f in cdir.glob("*.json"):
        store = load_store(f)
        seen.update(store["candidates"])
        seen.update(store["rejected"])
        seen.update(store["abandoned"])
    return seen
