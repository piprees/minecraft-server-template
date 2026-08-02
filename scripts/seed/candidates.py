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
        "fingerprintAssumed": "…",      # for candidates measured before stamping existed:
                                        # the fingerprint current when the bank was first
                                        # folded under stamping-aware code. An assumption,
                                        # recorded as its own key — never forged into
                                        # `fingerprint` — so drift fires on the NEXT
                                        # generation-affecting change (ensure_fingerprints)
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
import sys
from pathlib import Path

# Keys that do NOT change a candidate's score: the seed/spawn are the
# candidate itself, identity keys don't affect scoring.
VOLATILE_KEYS = ("seed", "spawn", "name", "dimensionId")


_BANK_ROOT = None
_WARNED_NO_ROOT = False


def set_bank_root(seedtest_dir):
    """Point the candidate bank at <seedtest>/candidates.

    Every entry point already knows its --seedtest, so the location is
    passed in rather than derived from the config path: deriving it meant
    walking ABOVE the config directory, which lands outside the project
    entirely for any layout that is not the exact consumer/platform shape.
    """
    global _BANK_ROOT
    _BANK_ROOT = Path(seedtest_dir).resolve() if seedtest_dir else None


def candidates_dir(config_dir):
    """Where the candidate bank lives: <seedtest>/candidates.

    NOT under the config directory. In a consumer that resolved to
    <root>/data/config/custom-dimensions/candidates — inside `data/`, the
    disposable runtime tree you wipe to reset a world. A bank is hours of
    measurement and is not game data; losing it to a routine `rm -rf data/`
    is a trap.

    Everything else in .seedtest is derived research state of exactly this
    kind, so the bank belongs beside it. Callers that never set a root (the
    unit tests, which build a bare config directory) keep the old location,
    so this stays a relocation rather than a second storage format.

    That fallback is LOUD, because silently it is worse than useless: a
    consumer whose bundle carries a new candidates.py beside a stale
    fast_roller.py can bank candidates into the stack bundle path — inside
    .stack/<version>/, invisible to the viewer, and thrown away by the next
    `./dev update` — while the roll reports "accepted" and the viewer
    reports "no candidates".
    """
    global _WARNED_NO_ROOT
    legacy = Path(config_dir) / "candidates"
    if _BANK_ROOT is None:
        if not _WARNED_NO_ROOT:
            _WARNED_NO_ROOT = True
            print(f"WARNING: candidate bank root not set — using {legacy}. "
                  "Entry points must call candidates.set_bank_root(<seedtest>) "
                  "before touching the bank.", file=sys.stderr, flush=True)
        return legacy
    new = _BANK_ROOT / "candidates"
    # Migrate on first touch rather than stranding an existing bank.
    if legacy.is_dir() and not new.exists():
        stores = sorted(legacy.glob("*.json"))
        if stores:
            new.mkdir(parents=True, exist_ok=True)
            for f in stores:
                target = new / f.name
                if not target.exists():
                    f.replace(target)
            try:
                legacy.rmdir()
            except OSError:
                pass   # other files in there; leave them be
    return new


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
    cand = store["candidates"].setdefault(seed, {"measurements": {}, "scores": {}})
    # Stamp on the call that supplies the measurements: the record may already
    # exist as an empty shell written by the census/survey passes. Never
    # back-stamp a record that already has measurements — its config is
    # unknown, and guessing defeats the drift check (see T22).
    first_measurements = not cand.get("measurements")
    cand.setdefault("measurements", {}).update(rows)
    cand.setdefault("scores", {})
    if fingerprint and first_measurements:
        cand["fingerprint"] = fingerprint


def effective_fingerprint(cand):
    """The generation fingerprint a candidate's measurements answer for:
    the measured stamp when there is one, else the assumed one."""
    return cand.get("fingerprint") or cand.get("fingerprintAssumed")


def ensure_fingerprints(store, current_fp):
    """Give every measured-but-unstamped candidate an ASSUMED stamp — the
    fingerprint current when this fold ran.

    A pre-existing candidate's true measurement config is unknowable, so
    the assumption is recorded under its own key rather than forged into
    `fingerprint` (measured stamps are only ever written by merge_rows,
    with the measurements). The assumed stamp makes the drift check LIVE
    for the whole bank: it can never claim historical drift it cannot
    know about, but any generation-affecting change AFTER this fold
    drifts every candidate, stamped or legacy. Empty shells (no
    measurements) are skipped — they take a measured stamp when their
    measurements arrive. Returns how many candidates were stamped."""
    if not current_fp:
        return 0
    stamped = 0
    for cand in store["candidates"].values():
        if not cand.get("measurements"):
            continue
        if cand.get("fingerprint") or cand.get("fingerprintAssumed"):
            continue
        cand["fingerprintAssumed"] = current_fp
        stamped += 1
    return stamped


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
