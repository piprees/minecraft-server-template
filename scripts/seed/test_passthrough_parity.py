#!/usr/bin/env python3
"""test_passthrough_parity.py — zero-tolerance parity test for pass-through
structure set positions, per placement type.

Reads census fixtures from testdata/census/ and diffs each pass-through
set's positions against the Python get_start_chunk recomputation. Collects
which placement types passed and which failed. Types that pass are
candidates for VERIFIED_PASSTHROUGH_TYPES; types that fail are excluded
from battery measurement and banked "not exactly measurable".

A fixture without a passThrough key is skipped (no pass-through data
available yet). A fixture whose passThrough is a list (old format) is
also skipped. Both are normal states for existing committed fixtures.
"""

import json
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from structure_placement import (
    get_start_chunk,
    recompute_passthrough_positions,
    VERIFIED_PASSTHROUGH_TYPES,
)


TESTDATA = Path(__file__).resolve().parent / "testdata" / "census"


def _load_fixtures():
    """All census fixtures that carry the rich passThrough object."""
    if not TESTDATA.is_dir():
        return []
    fixtures = []
    for f in sorted(TESTDATA.glob("*.json")):
        try:
            data = json.loads(f.read_text())
        except (json.JSONDecodeError, OSError):
            continue
        pt = data.get("passThrough")
        # Old format is a list of strings; new format is an object
        if not isinstance(pt, dict):
            continue
        fixtures.append((f.stem, data))
    return fixtures


def _fixture_ids():
    return [name for name, _ in _load_fixtures()]


_FIXTURES = None


def _get_fixtures():
    global _FIXTURES
    if _FIXTURES is None:
        _FIXTURES = _load_fixtures()
    return _FIXTURES


class TestPassthroughParity:
    """Zero-tolerance parity between live getStartChunk and Python maths."""

    def test_fixtures_present_or_skip(self):
        """Fixtures without passThrough data: skip with a reason, never fail."""
        if not TESTDATA.is_dir():
            pytest.skip("no census testdata directory")
        all_files = list(TESTDATA.glob("*.json"))
        if not all_files:
            pytest.skip("no census fixtures found")
        fixtures = _get_fixtures()
        if not fixtures:
            pytest.skip("no fixtures contain a passThrough object "
                        "(old list format or absent key)")

    def test_parity_per_type(self):
        """Each placement type's positions must match exactly."""
        fixtures = _get_fixtures()
        if not fixtures:
            pytest.skip("no fixtures with passThrough object data")

        passed_types = set()
        failed_types = {}

        for fixture_name, data in fixtures:
            seed = data.get("seed")
            if seed is None:
                continue
            pt = data["passThrough"]
            for set_id, entry in pt.items():
                ptype = entry.get("placementType", "")
                spacing = entry.get("spacing", 0)
                separation = entry.get("separation", 0)
                salt = entry.get("salt", 0)
                frequency = entry.get("frequency", 1.0)
                live_positions = set()
                for pos in entry.get("positions", []):
                    if len(pos) >= 2:
                        live_positions.add((pos[0], pos[1]))

                if spacing <= 0:
                    continue

                # A frequency-gated set filters positions through the live
                # frequency reducer, which the Python maths does not mirror
                # — such a set is banked "not exactly measurable" (the
                # attach_battery_groups exclusion), so its parity is not a
                # claim either side makes. Skipped, never counted for or
                # against its placement type.
                if frequency < 1.0:
                    continue

                # Compute the horizon from the positions or use a generous
                # default. The fixture was dumped with a specific horizon;
                # we need to match it.
                if live_positions:
                    max_dist_sq = max(cx * cx + cz * cz
                                     for cx, cz in live_positions)
                    # The horizon must be at least as large as the farthest
                    # position, plus some margin for boundary effects
                    import math
                    horizon = int(math.sqrt(max_dist_sq)) + spacing + 2
                else:
                    horizon = 640  # default

                python_positions = recompute_passthrough_positions(
                    seed, spacing, separation, salt, frequency, horizon)

                # Zero-tolerance: every live position must be in Python's
                # set and vice versa within the horizon
                missing_from_python = live_positions - python_positions
                extra_in_python = python_positions - live_positions
                # Only count extras within the live horizon (positions
                # beyond max live distance are expected to differ due to
                # horizon choice)
                if live_positions:
                    max_live_sq = max(cx * cx + cz * cz
                                     for cx, cz in live_positions)
                    extra_in_python = {
                        (cx, cz) for cx, cz in extra_in_python
                        if cx * cx + cz * cz <= max_live_sq}

                if missing_from_python or extra_in_python:
                    failed_types.setdefault(ptype, []).append({
                        "fixture": fixture_name,
                        "set_id": set_id,
                        "missing": len(missing_from_python),
                        "extra": len(extra_in_python),
                        "total_live": len(live_positions),
                    })
                else:
                    passed_types.add(ptype)

        # Report results
        if passed_types:
            print(f"\nPassed placement types: {sorted(passed_types)}")
        if failed_types:
            for ptype, failures in sorted(failed_types.items()):
                total_miss = sum(f["missing"] for f in failures)
                total_extra = sum(f["extra"] for f in failures)
                print(f"\nFailed: {ptype} ({len(failures)} sets, "
                      f"{total_miss} missing, {total_extra} extra)")
                for f in failures[:3]:
                    print(f"  {f['fixture']}/{f['set_id']}: "
                          f"{f['missing']} missing, {f['extra']} extra "
                          f"of {f['total_live']}")

        # The verified types must all pass
        for vtype in VERIFIED_PASSTHROUGH_TYPES:
            if vtype in failed_types:
                details = failed_types[vtype]
                pytest.fail(
                    f"Verified type {vtype} failed parity in "
                    f"{len(details)} set(s): "
                    + "; ".join(f"{d['set_id']} ({d['missing']} missing, "
                               f"{d['extra']} extra)"
                               for d in details[:5]))

    def test_verified_types_subset_of_known(self):
        """VERIFIED_PASSTHROUGH_TYPES must only contain types that actually
        appear in fixtures and pass the parity test. A type on the list
        that has never been tested is a claim without evidence."""
        fixtures = _get_fixtures()
        if not fixtures:
            pytest.skip("no fixtures with passThrough object data")

        seen_types = set()
        for _name, data in fixtures:
            pt = data.get("passThrough")
            if not isinstance(pt, dict):
                continue
            for entry in pt.values():
                ptype = entry.get("placementType", "")
                if ptype:
                    seen_types.add(ptype)

        if not seen_types:
            pytest.skip("no placement types found in fixtures")

        # Every verified type must appear in at least one fixture.
        # An allowlist entry without a passing parity test is an unproven
        # claim — the precision plan says "the parity test is the only
        # way onto this list".
        untested = VERIFIED_PASSTHROUGH_TYPES - seen_types
        if untested:
            pytest.fail(
                f"VERIFIED_PASSTHROUGH_TYPES contains types not seen in "
                f"any fixture: {sorted(untested)} — remove them or "
                f"commit fixtures that exercise them")


class TestRecomputePassthroughPositions:
    """Unit tests for the recompute function itself."""

    def test_empty_on_zero_spacing(self):
        assert recompute_passthrough_positions(12345, 0, 0, 0, 1.0, 100) == set()

    def test_empty_on_zero_horizon(self):
        assert recompute_passthrough_positions(12345, 32, 8, 100, 1.0, 0) == set()

    def test_deterministic(self):
        a = recompute_passthrough_positions(42, 32, 8, 10387312, 1.0, 100)
        b = recompute_passthrough_positions(42, 32, 8, 10387312, 1.0, 100)
        assert a == b

    def test_positions_within_horizon(self):
        positions = recompute_passthrough_positions(
            42, 32, 8, 10387312, 1.0, 50)
        for cx, cz in positions:
            assert cx * cx + cz * cz <= 50 * 50, \
                f"position ({cx}, {cz}) is outside horizon 50"

    def test_frequency_reduces_positions(self):
        full = recompute_passthrough_positions(42, 32, 8, 100, 1.0, 100)
        half = recompute_passthrough_positions(42, 32, 8, 100, 0.5, 100)
        assert len(half) <= len(full), \
            "frequency < 1.0 should produce fewer or equal positions"

    def test_known_vanilla_village(self):
        """Vanilla village_plains: spacing=34, separation=8, salt=10387312.
        At seed 0, the nearest village is a well-known position."""
        positions = recompute_passthrough_positions(
            0, 34, 8, 10387312, 1.0, 50)
        assert len(positions) > 0, "expected at least one village position"
