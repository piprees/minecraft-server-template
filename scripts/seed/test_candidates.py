#!/usr/bin/env python3
"""Candidate-store fingerprint stamping: measured stamps from merge_rows,
assumed stamps from ensure_fingerprints, and the drift condition that
status/finalise evaluate over their union (effective_fingerprint)."""
import unittest

import candidates


def drifted(cand, current_fp):
    """The status/finalise drift condition, verbatim."""
    cand_fp = candidates.effective_fingerprint(cand)
    return bool(current_fp and cand_fp and cand_fp != current_fp)


class MeasuredStampTests(unittest.TestCase):
    """Fresh rolls stamp exactly: the fold that supplies measurements."""

    def test_merge_rows_stamps_new_candidates(self):
        store = candidates.empty_store()
        candidates.merge_rows(store, 111, {"relief": "5"}, fingerprint="fp_a")
        self.assertEqual("fp_a", store["candidates"]["111"]["fingerprint"])
        self.assertEqual("fp_a",
                         candidates.effective_fingerprint(store["candidates"]["111"]))

    def test_merge_rows_never_restamps_measured_candidates(self):
        store = candidates.empty_store()
        candidates.merge_rows(store, 111, {"relief": "5"}, fingerprint="fp_a")
        candidates.merge_rows(store, 111, {"grain": "2"}, fingerprint="fp_b")
        self.assertEqual("fp_a", store["candidates"]["111"]["fingerprint"])

    def test_empty_shell_takes_measured_stamp_when_measurements_arrive(self):
        store = candidates.empty_store()
        # census/survey passes setdefault an empty shell before the fold runs
        store["candidates"]["222"] = {"measurements": {}, "scores": {}}
        candidates.merge_rows(store, 222, {"relief": "5"}, fingerprint="fp_a")
        self.assertEqual("fp_a", store["candidates"]["222"]["fingerprint"])


class AssumedStampTests(unittest.TestCase):
    """Legacy candidates: assumed at the current fold, drift on the NEXT change."""

    def legacy_store(self):
        store = candidates.empty_store()
        store["candidates"]["333"] = {"measurements": {"relief": "5"}, "scores": {}}
        return store

    def test_unstamped_legacy_gains_assumed_stamp(self):
        store = self.legacy_store()
        self.assertEqual(1, candidates.ensure_fingerprints(store, "fp_now"))
        cand = store["candidates"]["333"]
        self.assertNotIn("fingerprint", cand, "assumed must never forge a measured stamp")
        self.assertEqual("fp_now", cand["fingerprintAssumed"])

    def test_assumed_candidate_is_fresh_now_and_drifts_on_next_change(self):
        store = self.legacy_store()
        candidates.ensure_fingerprints(store, "fp_now")
        cand = store["candidates"]["333"]
        self.assertFalse(drifted(cand, "fp_now"))
        self.assertTrue(drifted(cand, "fp_changed"))

    def test_measured_candidate_drifts_on_change(self):
        store = candidates.empty_store()
        candidates.merge_rows(store, 111, {"relief": "5"}, fingerprint="fp_a")
        cand = store["candidates"]["111"]
        self.assertFalse(drifted(cand, "fp_a"))
        self.assertTrue(drifted(cand, "fp_b"))

    def test_ensure_is_idempotent_and_respects_existing_stamps(self):
        store = self.legacy_store()
        candidates.merge_rows(store, 111, {"relief": "5"}, fingerprint="fp_meas")
        self.assertEqual(1, candidates.ensure_fingerprints(store, "fp_now"))
        # second pass, later fingerprint: nothing left to stamp, nothing rewritten
        self.assertEqual(0, candidates.ensure_fingerprints(store, "fp_later"))
        self.assertEqual("fp_now", store["candidates"]["333"]["fingerprintAssumed"])
        self.assertEqual("fp_meas", store["candidates"]["111"]["fingerprint"])
        self.assertNotIn("fingerprintAssumed", store["candidates"]["111"])

    def test_shells_and_missing_fp_are_skipped(self):
        store = candidates.empty_store()
        store["candidates"]["444"] = {"measurements": {}, "scores": {}}
        self.assertEqual(0, candidates.ensure_fingerprints(store, "fp_now"))
        self.assertNotIn("fingerprintAssumed", store["candidates"]["444"])
        legacy = self.legacy_store()
        self.assertEqual(0, candidates.ensure_fingerprints(legacy, None))

    def test_measured_stamp_wins_over_assumed(self):
        cand = {"fingerprint": "fp_meas", "fingerprintAssumed": "fp_ass"}
        self.assertEqual("fp_meas", candidates.effective_fingerprint(cand))
        self.assertIsNone(candidates.effective_fingerprint({}))


if __name__ == "__main__":
    unittest.main()
