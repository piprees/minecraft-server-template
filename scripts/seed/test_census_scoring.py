#!/usr/bin/env python3
"""Tests for census_scoring — exact-position scoring (precision plan §3.4)."""
import gzip
import json
import math
import os
import sys
import tempfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import census_scoring
import noise_placement


# ---------------------------------------------------------------------------
# census_want_score
# ---------------------------------------------------------------------------

class TestCensusWantScore:
    """Want scored 1.0 when enough assigned positions in band; WANT_WRONG_RING_SCORE
    when in pool but none in band; 0.0 when absent from pool."""

    def test_full_credit_when_enough_in_band(self):
        positions = [(10, 0, "ns:village"), (20, 0, "ns:village"), (5, 0, "ns:other")]
        score = census_scoring.census_want_score(
            "ns:village", (0, 500), positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == 1.0

    def test_partial_credit_one_in_band(self):
        positions = [(10, 0, "ns:village")]
        score = census_scoring.census_want_score(
            "ns:village", (0, 500), positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == pytest.approx(1.0 / census_scoring.WANT_BAND_TARGET)

    def test_wrong_ring_when_in_pool_but_none_in_band(self):
        positions = [(100, 0, "ns:village")]
        score = census_scoring.census_want_score(
            "ns:village", (0, 100), positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == census_scoring.WANT_WRONG_RING_SCORE

    def test_wrong_ring_when_pick_never_assigns(self):
        positions = [(10, 0, "ns:other")]
        score = census_scoring.census_want_score(
            "ns:village", (0, 500), positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == census_scoring.WANT_WRONG_RING_SCORE

    def test_zero_when_not_in_pool(self):
        positions = [(10, 0, "ns:other")]
        score = census_scoring.census_want_score(
            "ns:village", (0, 500), positions, spawn_cx=0, spawn_cz=0, in_pool=False)
        assert score == 0.0

    def test_zero_when_not_in_pool_empty_positions(self):
        score = census_scoring.census_want_score(
            "ns:village", (0, 500), [], spawn_cx=0, spawn_cz=0, in_pool=False)
        assert score == 0.0

    def test_distance_computed_from_spawn(self):
        """A position at chunk (10, 0) relative to spawn at chunk (5, 0) is
        5*16=80 blocks away."""
        positions = [(10, 0, "ns:village")]
        in_band = census_scoring.census_want_score(
            "ns:village", (70, 90), positions, spawn_cx=5, spawn_cz=0, in_pool=True)
        assert in_band == pytest.approx(1.0 / census_scoring.WANT_BAND_TARGET)
        outside = census_scoring.census_want_score(
            "ns:village", (0, 50), positions, spawn_cx=5, spawn_cz=0, in_pool=True)
        assert outside == census_scoring.WANT_WRONG_RING_SCORE

    def test_band_target_exactly_met(self):
        target = int(census_scoring.WANT_BAND_TARGET)
        positions = [(i, 0, "ns:village") for i in range(target)]
        score = census_scoring.census_want_score(
            "ns:village", (0, 10000), positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == 1.0

    def test_empty_positions_in_pool(self):
        score = census_scoring.census_want_score(
            "ns:village", (0, 500), [], spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == census_scoring.WANT_WRONG_RING_SCORE


# ---------------------------------------------------------------------------
# census_shun_score
# ---------------------------------------------------------------------------

class TestCensusShunScore:
    """Shun pass/fail from exact distances."""

    def test_fail_when_inside_threshold(self):
        """Chunk (2, 0) = 32 blocks from spawn, threshold 500 — inside."""
        positions = [(2, 0, "ns:dungeon")]
        score = census_scoring.census_shun_score(
            "ns:dungeon", 500, positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == 0.0

    def test_pass_when_outside_threshold(self):
        """Chunk (100, 0) = 1600 blocks from spawn, threshold 100 — outside."""
        positions = [(100, 0, "ns:dungeon")]
        score = census_scoring.census_shun_score(
            "ns:dungeon", 100, positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == 1.0

    def test_pass_when_not_in_pool(self):
        positions = [(1, 0, "ns:dungeon")]
        score = census_scoring.census_shun_score(
            "ns:dungeon", 500, positions, spawn_cx=0, spawn_cz=0, in_pool=False)
        assert score == 1.0

    def test_pass_empty_positions(self):
        score = census_scoring.census_shun_score(
            "ns:dungeon", 500, [], spawn_cx=0, spawn_cz=0, in_pool=True)
        assert score == 1.0

    def test_distance_from_spawn(self):
        """Chunk (3, 4) relative to spawn (0, 0) = sqrt(9+16)*16 = 80 blocks."""
        positions = [(3, 4, "ns:dungeon")]
        inside = census_scoring.census_shun_score(
            "ns:dungeon", 100, positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert inside == 0.0
        outside = census_scoring.census_shun_score(
            "ns:dungeon", 50, positions, spawn_cx=0, spawn_cz=0, in_pool=True)
        assert outside == 1.0


# ---------------------------------------------------------------------------
# compute_by_structure (inBand removed)
# ---------------------------------------------------------------------------

class TestComputeByStructure:
    """byStructure carries count and nearest; no inBand."""

    def test_basic(self):
        positions = [(10, 0), (20, 0), (30, 0)]
        pool = [("ns:a", 1), ("ns:b", 1)]
        ps = noise_placement.pick_seed(12345)
        result = census_scoring.compute_by_structure(
            positions, pool, ps, spawn_cx=0, spawn_cz=0)
        total = sum(e["count"] for e in result.values())
        assert total == 3
        for entry in result.values():
            assert "count" in entry
            assert "nearest" in entry
            assert "inBand" not in entry


# ---------------------------------------------------------------------------
# Sidecar roundtrip
# ---------------------------------------------------------------------------

class TestSidecarRoundtrip:
    """Write then load equals computed positions."""

    def test_roundtrip(self, tmp_path):
        group_data = {
            "settlements": {
                "ids": ["ns:village", "ns:pillager_outpost"],
                "positions": [[10, 20, 0], [-5, 8, 1], [3, 3, 0]],
            }
        }
        noise_placement._write_census_sidecar(
            str(tmp_path), "test_dim", "12345", "fp1", "ph1", group_data)
        loaded = noise_placement.load_census_positions(
            str(tmp_path), "test_dim", "12345")
        assert "settlements" in loaded
        expected = [(10, 20, "ns:village"), (-5, 8, "ns:pillager_outpost"),
                    (3, 3, "ns:village")]
        assert loaded["settlements"] == expected

    def test_missing_file_returns_empty(self, tmp_path):
        result = noise_placement.load_census_positions(
            str(tmp_path), "nonexistent", "99999")
        assert result == {}


# ---------------------------------------------------------------------------
# Cache miss on missing sidecar
# ---------------------------------------------------------------------------

class TestSidecarCacheMiss:
    """Missing sidecar forces a cache miss."""

    def test_sidecar_exists_false_when_absent(self, tmp_path):
        assert not noise_placement.census_sidecar_exists(
            str(tmp_path), "dim", "seed")

    def test_sidecar_exists_true_when_present(self, tmp_path):
        group_data = {"g": {"ids": ["ns:a"], "positions": [[0, 0, 0]]}}
        noise_placement._write_census_sidecar(
            str(tmp_path), "dim", "seed", "fp", "ph", group_data)
        assert noise_placement.census_sidecar_exists(
            str(tmp_path), "dim", "seed")


# ---------------------------------------------------------------------------
# Invariant: sum(byStructure counts) == group count
# ---------------------------------------------------------------------------

class TestByStructureInvariant:
    """The sum of per-structure counts in byStructure equals the group's total."""

    def test_invariant(self):
        positions = [(i, j) for i in range(-5, 6) for j in range(-5, 6)]
        pool = [("ns:a", 3), ("ns:b", 2), ("ns:c", 5)]
        ps = noise_placement.pick_seed(42)
        by_struct = census_scoring.compute_by_structure(
            positions, pool, ps, spawn_cx=0, spawn_cz=0)
        total = sum(e["count"] for e in by_struct.values())
        assert total == len(positions)
