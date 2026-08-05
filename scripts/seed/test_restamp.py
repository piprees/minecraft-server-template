#!/usr/bin/env python3
"""test_restamp.py — tests for restamp-structure-identity.py.

Synthetic fixtures: builds a minimal config directory and candidate store
in a temp directory, then verifies the restamp logic against the five
critical cases:
  1. Eligible candidate is restamped
  2. Stale poolHash is NOT restamped
  3. Missing sidecar is NOT restamped
  4. --dry-run writes nothing
  5. Idempotent second run
"""

import gzip
import json
import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import candidates  # noqa: E402
import noise_placement  # noqa: E402

# Import the restamp function directly from the script module.
import importlib
_restamp_mod = importlib.import_module("restamp-structure-identity")
restamp = _restamp_mod.restamp


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

# A minimal dimension config that produces a generation fingerprint and a
# noise fingerprint. The exact values do not matter — only that they are
# stable and computable from the config.
_DIM_CONFIG = {
    "type": "overworld",
    "biomes": ["minecraft:plains", "minecraft:forest"],
    "seedRoll": {"mood": "standard"},
    "borders": {"player": 512},
}

# A second dimension with different biomes (different fingerprint).
_DIM_CONFIG_2 = {
    "type": "overworld",
    "biomes": ["minecraft:desert", "minecraft:badlands"],
    "seedRoll": {"mood": "dramatic"},
    "borders": {"player": 256},
}


def _write_dim_file(config_dir, slug, dim_config):
    dims_dir = Path(config_dir) / "dimensions"
    dims_dir.mkdir(parents=True, exist_ok=True)
    (dims_dir / "{}.json".format(slug)).write_text(
        json.dumps(dim_config, indent=2))


def _write_type_defaults(config_dir):
    """Write a minimal structure-type-defaults.json so noise fingerprints resolve."""
    td = {
        "curves": {"flat": [1.0] * 10, "inner": [0.0, 0.5, 1.0, 1.5, 1.5, 1.0, 0.5, 0.3, 0.1, 0.0]},
        "groupDefaults": {
            "settlements": {"profile": "natural", "exclusion": 8, "radial": "flat"},
            "dungeons": {"profile": "natural", "exclusion": 6, "radial": "flat"},
        },
        "types": {
            "overworld": {
                "groups": ["settlements", "dungeons"],
            }
        },
        "difficultyShifts": {
            "peaceful": {"maxMobMultiplier": 0.0, "profiles": {}},
            "hostile": {"minMobMultiplier": 2.0, "radial": {}},
        },
    }
    (Path(config_dir) / "structure-type-defaults.json").write_text(
        json.dumps(td, indent=2))


def _write_structure_groups(config_dir):
    """Write a minimal structure-groups.json."""
    sg = {"sets": {
        "minecraft:villages": {"group": "settlements", "rarity": "common",
                               "theme": "overworld", "spacing": 34},
        "minecraft:mineshafts": {"group": "dungeons", "rarity": "common",
                                 "theme": "overworld", "spacing": 4},
    }}
    (Path(config_dir) / "structure-groups.json").write_text(
        json.dumps(sg, indent=2))


def _build_census(noise_fp, pool_hash_val):
    """A noiseCensus dict matching the current schema."""
    return {
        "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
        "fp": noise_fp,
        "poolHash": pool_hash_val,
        "radiusChunks": 32,
        "groups": {
            "settlements": {"count": 5, "hist": [1, 0, 1, 1, 0, 1, 0, 0, 1, 0]},
            "dungeons": {"count": 3, "hist": [0, 1, 0, 1, 0, 0, 0, 1, 0, 0]},
        },
    }


def _write_sidecar(seedtest, dim_name, seed, noise_fp, pool_hash_val):
    """Write a minimal census sidecar gzip file."""
    out_dir = Path(seedtest) / "census-positions" / dim_name
    out_dir.mkdir(parents=True, exist_ok=True)
    dest = out_dir / "{}.json.gz".format(seed)
    doc = {
        "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
        "fp": noise_fp,
        "poolHash": pool_hash_val,
        "groups": {},
    }
    with gzip.open(str(dest), "wb") as gz:
        gz.write(json.dumps(doc).encode())


def _make_store(config_dir, slug, cands_dict):
    """Write a candidate store with the given candidates dict."""
    from dimension_profiles import load_config
    store = candidates.empty_store()
    store["candidates"] = cands_dict
    cdir = candidates.candidates_dir(Path(config_dir))
    cdir.mkdir(parents=True, exist_ok=True)
    candidates.save_store(cdir / "{}.json".format(slug), store)


@pytest.fixture
def env(tmp_path):
    """Build a self-contained config + seedtest + candidate bank.

    The bank root is process-global (candidates._BANK_ROOT); restore the
    previous value on teardown or every store test that runs after this
    module reads the wrong bank.
    """
    config_dir = str(tmp_path / "config" / "custom-dimensions")
    seedtest = str(tmp_path / ".seedtest")
    Path(seedtest).mkdir(parents=True, exist_ok=True)

    # Bank root must be set FIRST.
    previous_root = candidates.bank_root()
    candidates.set_bank_root(seedtest)

    _write_dim_file(config_dir, "test_dim", _DIM_CONFIG)
    _write_type_defaults(config_dir)
    _write_structure_groups(config_dir)

    # Compute the fingerprints from the written config.
    from dimension_profiles import (load_config, generation_fingerprint,
                                    noise_fingerprint)
    cfg = load_config(config_dir)
    sources = {d["name"]: d for d in cfg.get("dimensions", [])}
    sources.update({w["name"]: w for w in cfg.get("worlds", [])})
    src = sources["test_dim"]
    gen_fp = generation_fingerprint(src)
    nfp = noise_fingerprint(src)

    # Pool hash with empty pools (matching what load_structure_pools returns
    # when no structure_pools.json exists).
    ph = noise_placement.pool_hash({})

    yield {
        "config_dir": config_dir,
        "seedtest": seedtest,
        "gen_fp": gen_fp,
        "noise_fp": nfp,
        "pool_hash": ph,
        "tmp_path": tmp_path,
    }
    candidates.set_bank_root(previous_root)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

def test_eligible_candidate_restamped(env):
    """A candidate with current noiseCensus fp/poolHash/sidecar is restamped."""
    old_fp = "old_fingerprint"
    seed = "12345"
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": old_fp,
            "scores": {},
            "noiseCensus": _build_census(env["noise_fp"], env["pool_hash"]),
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    restamped, skipped = restamp(env["seedtest"], env["config_dir"])
    assert restamped == 1
    assert skipped == 0

    # Verify the store was written with the new fingerprint.
    cdir = candidates.candidates_dir(Path(env["config_dir"]))
    store = candidates.load_store(cdir / "test_dim.json")
    assert store["candidates"][seed]["fingerprint"] == env["gen_fp"]


def test_stale_pool_hash_not_restamped(env):
    """A candidate whose poolHash differs from current is skipped."""
    seed = "67890"
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": "old_fingerprint",
            "scores": {},
            "noiseCensus": _build_census(env["noise_fp"], "wrong_pool_hash"),
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    restamped, skipped = restamp(env["seedtest"], env["config_dir"])
    assert restamped == 0
    assert skipped == 1

    cdir = candidates.candidates_dir(Path(env["config_dir"]))
    store = candidates.load_store(cdir / "test_dim.json")
    assert store["candidates"][seed]["fingerprint"] == "old_fingerprint"


def test_missing_sidecar_not_restamped(env):
    """A candidate without a sidecar file is skipped."""
    seed = "11111"
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": "old_fingerprint",
            "scores": {},
            "noiseCensus": _build_census(env["noise_fp"], env["pool_hash"]),
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    # Deliberately NOT writing a sidecar.

    restamped, skipped = restamp(env["seedtest"], env["config_dir"])
    assert restamped == 0
    assert skipped == 1

    cdir = candidates.candidates_dir(Path(env["config_dir"]))
    store = candidates.load_store(cdir / "test_dim.json")
    assert store["candidates"][seed]["fingerprint"] == "old_fingerprint"


def test_dry_run_writes_nothing(env):
    """--dry-run counts but does not modify the store."""
    old_fp = "old_fingerprint"
    seed = "22222"
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": old_fp,
            "scores": {},
            "noiseCensus": _build_census(env["noise_fp"], env["pool_hash"]),
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    restamped, skipped = restamp(env["seedtest"], env["config_dir"],
                                 dry_run=True)
    assert restamped == 1
    assert skipped == 0

    # Store must still have the OLD fingerprint.
    cdir = candidates.candidates_dir(Path(env["config_dir"]))
    store = candidates.load_store(cdir / "test_dim.json")
    assert store["candidates"][seed]["fingerprint"] == old_fp


def test_idempotent_second_run(env):
    """A second run after restamping finds nothing to do."""
    seed = "33333"
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": "old_fingerprint",
            "scores": {},
            "noiseCensus": _build_census(env["noise_fp"], env["pool_hash"]),
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    # First run restamps.
    r1, s1 = restamp(env["seedtest"], env["config_dir"])
    assert r1 == 1
    assert s1 == 0

    # Second run: fingerprint already matches, nothing to do.
    r2, s2 = restamp(env["seedtest"], env["config_dir"])
    assert r2 == 0
    assert s2 == 0


def test_stale_schema_version_not_restamped(env):
    """A candidate with schemaVersion != 2 is skipped."""
    seed = "44444"
    census = _build_census(env["noise_fp"], env["pool_hash"])
    census["schemaVersion"] = 1  # stale schema
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": "old_fingerprint",
            "scores": {},
            "noiseCensus": census,
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    restamped, skipped = restamp(env["seedtest"], env["config_dir"])
    assert restamped == 0
    assert skipped == 1


def test_assumed_fingerprint_candidate_restamped(env):
    """A candidate with fingerprintAssumed (no measured stamp) is restamped
    when its census is current — the restamp writes a MEASURED stamp."""
    seed = "55555"
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprintAssumed": "old_assumed_fp",
            "scores": {},
            "noiseCensus": _build_census(env["noise_fp"], env["pool_hash"]),
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    restamped, skipped = restamp(env["seedtest"], env["config_dir"])
    assert restamped == 1

    cdir = candidates.candidates_dir(Path(env["config_dir"]))
    store = candidates.load_store(cdir / "test_dim.json")
    cand = store["candidates"][seed]
    # The measured stamp replaces the assumed one.
    assert cand["fingerprint"] == env["gen_fp"]
    # fingerprintAssumed remains (it is historical; restamp does not remove it).
    assert cand.get("fingerprintAssumed") == "old_assumed_fp"


def test_stale_noise_fp_not_restamped(env):
    """A candidate whose noiseCensus.fp differs from current is skipped."""
    seed = "66666"
    census = _build_census("wrong_noise_fp", env["pool_hash"])
    cands = {
        seed: {
            "measurements": {"spawn_biome": "minecraft:plains", "errors": "0"},
            "fingerprint": "old_fingerprint",
            "scores": {},
            "noiseCensus": census,
        }
    }
    _make_store(env["config_dir"], "test_dim", cands)
    _write_sidecar(env["seedtest"], "test_dim", seed,
                   env["noise_fp"], env["pool_hash"])

    restamped, skipped = restamp(env["seedtest"], env["config_dir"])
    assert restamped == 0
    assert skipped == 1
