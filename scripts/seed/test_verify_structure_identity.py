#!/usr/bin/env python3
"""Tests for verify-structure-identity.py.

Builds synthetic mini-bank fixtures in a temp directory and exercises the
verifier's three checks: schemaVersion, sidecar existence, and byStructure
population (with the empty-pool exception via structure_pools.json).
"""

import gzip
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import candidates  # noqa: E402
import noise_placement  # noqa: E402

# Imported as a module — the hyphen in the filename means we use importlib.
import importlib
_verify_mod = importlib.import_module("verify-structure-identity")
verify = _verify_mod.verify


def _write_sidecar(seedtest, dim_name, seed, groups_data=None):
    """Write a minimal valid sidecar file."""
    out_dir = Path(seedtest) / "census-positions" / dim_name
    out_dir.mkdir(parents=True, exist_ok=True)
    dest = out_dir / "{}.json.gz".format(seed)
    doc = {
        "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
        "fp": "test_fp",
        "poolHash": "test_ph",
        "groups": groups_data or {},
    }
    raw = json.dumps(doc, separators=(",", ":")).encode()
    with gzip.open(str(dest), "wb") as gz:
        gz.write(raw)


def _make_store(cands_dict):
    """Build a store dict from {seed: noiseCensus_dict}."""
    store = candidates.empty_store()
    store["configHash"] = "testcfg1"
    for seed, census in cands_dict.items():
        store["candidates"][str(seed)] = {
            "measurements": {"errors": "0"},
            "scores": {},
            "noiseCensus": census,
        }
    return store


class TestVerifyStructureIdentity(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="test_verify_")
        self.seedtest = Path(self.tmpdir) / ".seedtest"
        self.seedtest.mkdir()
        # Point candidates module at our test seedtest.
        candidates.set_bank_root(str(self.seedtest))

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)
        candidates.set_bank_root(None)

    def _write_backup(self, dim_name, store):
        backup = self.seedtest / "candidates.bak.test"
        backup.mkdir(parents=True, exist_ok=True)
        path = backup / "{}.json".format(dim_name)
        path.write_text(json.dumps(store, indent=2))
        return str(backup)

    def _write_live(self, dim_name, store):
        live_dir = self.seedtest / "candidates"
        live_dir.mkdir(parents=True, exist_ok=True)
        path = live_dir / "{}.json".format(dim_name)
        path.write_text(json.dumps(store, indent=2))

    def _write_pools(self, pools):
        (self.seedtest / "structure_pools.json").write_text(
            json.dumps(pools, indent=2))

    def test_all_checks_pass(self):
        """A fully migrated candidate passes all three checks."""
        backup_census = {
            "schemaVersion": 1,
            "groups": {
                "settlements": {"count": 42, "hist": [4] * 10},
            },
        }
        live_census = {
            "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
            "fp": "test_fp",
            "poolHash": "test_ph",
            "groups": {
                "settlements": {
                    "count": 42,
                    "hist": [4] * 10,
                    "byStructure": {"minecraft:village_plains": {"count": 20, "nearest": 100.0}},
                },
            },
        }
        backup_store = _make_store({"111": backup_census})
        live_store = _make_store({"111": live_census})
        backup_dir = self._write_backup("test_dim", backup_store)
        self._write_live("test_dim", live_store)
        _write_sidecar(str(self.seedtest), "test_dim", "111")

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 0, "unexpected failures: {}".format(report))
        self.assertEqual(passed, 1)

    def test_missing_schema_version(self):
        """A candidate without schemaVersion 2 fails."""
        backup_census = {
            "schemaVersion": 1,
            "groups": {"settlements": {"count": 10, "hist": [1] * 10}},
        }
        live_census = {
            "schemaVersion": 1,  # not migrated
            "groups": {
                "settlements": {
                    "count": 10,
                    "byStructure": {"x": {"count": 5, "nearest": 50.0}},
                },
            },
        }
        backup_store = _make_store({"222": backup_census})
        live_store = _make_store({"222": live_census})
        backup_dir = self._write_backup("dim_a", backup_store)
        self._write_live("dim_a", live_store)
        _write_sidecar(str(self.seedtest), "dim_a", "222")

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 1)
        self.assertIn("schemaVersion", report[0])

    def test_missing_sidecar(self):
        """A candidate without a sidecar file fails."""
        backup_census = {
            "groups": {"settlements": {"count": 5, "hist": [1] * 5}},
        }
        live_census = {
            "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
            "groups": {
                "settlements": {
                    "count": 5,
                    "byStructure": {"x": {"count": 3, "nearest": 80.0}},
                },
            },
        }
        backup_store = _make_store({"333": backup_census})
        live_store = _make_store({"333": live_census})
        backup_dir = self._write_backup("dim_b", backup_store)
        self._write_live("dim_b", live_store)
        # No sidecar written.

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 1)
        self.assertIn("sidecar", report[0])

    def test_empty_by_structure_with_empty_pool_passes(self):
        """byStructure={} is valid when the pool confirms the group is empty."""
        backup_census = {
            "groups": {"exotic": {"count": 8, "hist": [1] * 8}},
        }
        live_census = {
            "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
            "groups": {
                "exotic": {"count": 8, "byStructure": {}},
            },
        }
        self._write_pools({"dim_c": {"exotic": {}}})
        backup_store = _make_store({"444": backup_census})
        live_store = _make_store({"444": live_census})
        backup_dir = self._write_backup("dim_c", backup_store)
        self._write_live("dim_c", live_store)
        _write_sidecar(str(self.seedtest), "dim_c", "444")

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 0, "unexpected failures: {}".format(report))
        self.assertEqual(passed, 1)

    def test_empty_by_structure_with_nonempty_pool_fails(self):
        """byStructure={} is invalid when the pool has structures."""
        backup_census = {
            "groups": {"settlements": {"count": 12, "hist": [1] * 10}},
        }
        live_census = {
            "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
            "groups": {
                "settlements": {"count": 12, "byStructure": {}},
            },
        }
        self._write_pools({"dim_d": {"settlements": {"minecraft:village": 1}}})
        backup_store = _make_store({"555": backup_census})
        live_store = _make_store({"555": live_census})
        backup_dir = self._write_backup("dim_d", backup_store)
        self._write_live("dim_d", live_store)
        _write_sidecar(str(self.seedtest), "dim_d", "555")

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 1)
        self.assertIn("byStructure is empty but pool has", report[0])

    def test_zero_count_backup_is_skipped(self):
        """Candidates whose backup had count=0 everywhere are skipped."""
        backup_census = {
            "groups": {"settlements": {"count": 0, "hist": [0] * 10}},
        }
        backup_store = _make_store({"666": backup_census})
        backup_dir = self._write_backup("dim_e", backup_store)
        # No live store needed — it should never be checked.

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(skipped, 1)
        self.assertEqual(failed, 0)
        self.assertEqual(passed, 0)

    def test_missing_candidate_in_live_fails(self):
        """A candidate present in the backup but absent from the live bank fails."""
        backup_census = {
            "groups": {"dungeons": {"count": 3, "hist": [1, 1, 1]}},
        }
        backup_store = _make_store({"777": backup_census})
        live_store = candidates.empty_store()
        backup_dir = self._write_backup("dim_f", backup_store)
        self._write_live("dim_f", live_store)

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 1)
        self.assertIn("missing from the live bank", report[0])

    def test_missing_by_structure_key_fails(self):
        """A group in the live census that has no byStructure key at all fails."""
        backup_census = {
            "groups": {"loot": {"count": 5, "hist": [1] * 5}},
        }
        live_census = {
            "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
            "groups": {
                "loot": {"count": 5, "hist": [1] * 5},
                # byStructure intentionally missing
            },
        }
        backup_store = _make_store({"888": backup_census})
        live_store = _make_store({"888": live_census})
        backup_dir = self._write_backup("dim_g", backup_store)
        self._write_live("dim_g", live_store)
        _write_sidecar(str(self.seedtest), "dim_g", "888")

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 1)
        self.assertIn("byStructure missing", report[0])

    def test_multiple_candidates_mixed_results(self):
        """Multiple candidates: one passing, one failing, one skipped."""
        v2 = noise_placement.NOISE_CENSUS_SCHEMA_VERSION

        # Candidate A: fully migrated (pass)
        census_a_backup = {"groups": {"g": {"count": 3, "hist": [3]}}}
        census_a_live = {
            "schemaVersion": v2,
            "groups": {"g": {"count": 3, "byStructure": {"s": {"count": 3, "nearest": 10.0}}}},
        }

        # Candidate B: missing sidecar (fail)
        census_b_backup = {"groups": {"g": {"count": 2, "hist": [2]}}}
        census_b_live = {
            "schemaVersion": v2,
            "groups": {"g": {"count": 2, "byStructure": {"s": {"count": 2, "nearest": 20.0}}}},
        }

        # Candidate C: zero count (skip)
        census_c_backup = {"groups": {"g": {"count": 0, "hist": [0]}}}

        backup_store = _make_store({
            "100": census_a_backup,
            "200": census_b_backup,
            "300": census_c_backup,
        })
        live_store = _make_store({
            "100": census_a_live,
            "200": census_b_live,
            "300": {"groups": {}},
        })
        backup_dir = self._write_backup("dim_h", backup_store)
        self._write_live("dim_h", live_store)
        _write_sidecar(str(self.seedtest), "dim_h", "100")
        # Deliberately no sidecar for "200".

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(passed, 1)
        self.assertEqual(failed, 1)
        self.assertEqual(skipped, 1)

    def test_backup_dir_does_not_exist(self):
        """A missing backup directory reports an error."""
        passed, failed, skipped, report = verify(
            str(self.seedtest), "/nonexistent/path")
        self.assertEqual(failed, 0)
        self.assertIn("does not exist", report[0])

    def test_empty_backup_dir_returns_zero_counts(self):
        """An existing but empty backup directory yields 0/0/0 (no candidates)."""
        backup = self.seedtest / "candidates.bak.empty"
        backup.mkdir(parents=True, exist_ok=True)
        passed, failed, skipped, report = verify(
            str(self.seedtest), str(backup))
        self.assertEqual(passed, 0)
        self.assertEqual(failed, 0)
        self.assertEqual(skipped, 0)
        self.assertEqual(report, [])

    def test_missing_group_in_live_fails(self):
        """A group present in the backup but missing from the live census fails."""
        backup_census = {
            "groups": {"maritime": {"count": 7, "hist": [1] * 7}},
        }
        live_census = {
            "schemaVersion": noise_placement.NOISE_CENSUS_SCHEMA_VERSION,
            "groups": {},
        }
        backup_store = _make_store({"999": backup_census})
        live_store = _make_store({"999": live_census})
        backup_dir = self._write_backup("dim_i", backup_store)
        self._write_live("dim_i", live_store)
        _write_sidecar(str(self.seedtest), "dim_i", "999")

        passed, failed, skipped, report = verify(str(self.seedtest), backup_dir)
        self.assertEqual(failed, 1)
        self.assertIn("missing from live census", report[0])


if __name__ == "__main__":
    unittest.main()
