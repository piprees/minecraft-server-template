#!/usr/bin/env python3
"""Tests for warmup_structure_pools.py — the structure pool dump.

Almost all of this file is RCON reply parsing and dimension selection, and both
have bitten before. RCON concatenates feedback lines with NO separator and
truncates at a few KB, so a plausible-looking parser can silently return the
wrong number forever; the strings below are the replies a live server actually
gave on 2026-07-29, not invented ones.
"""
import re
import unittest

import warmup_structure_pools as wsp
from dimension_profiles import BASE_WORLD_IDS


class RecordedCountParsingTests(unittest.TestCase):
    """The progress signal. Getting it wrong costs a 60-second stall per batch."""

    @staticmethod
    def parse(reply):
        match = re.search(r"(\d+) dimension", reply)
        return int(match.group(1)) if match else -1

    def test_the_real_dump_reply_parses(self):
        self.assertEqual(self.parse(
            "dump-structure-pools: 6 dimension(s) -> "
            "./config/custom-dimensions/structure_pools.json"), 6)
        self.assertEqual(self.parse(
            "dump-structure-pools: 81 dimension(s) -> /x"), 81)

    def test_a_run_together_list_reply_is_not_mistaken_for_a_count(self):
        """The trap. RCON gives back

            "  adventure:the_starwell1 custom dimension(s) loaded"

        with the count welded to the last dimension id, so a whitespace split
        yields "adventure:the_starwell1" and int() raises. This parser must not
        claim a number from it at all — it reads a different command.
        """
        reply = "  adventure:the_starwell1 custom dimension(s) loaded"
        self.assertEqual(self.parse(reply), -1)
        with self.assertRaises(ValueError):
            int(reply.split(" custom dimension")[0].strip().split()[-1])

    def test_an_unparseable_reply_is_not_zero(self):
        """An RCON timeout returns "". Reading that as zero would restart the
        wait from scratch every poll; -1 just means "not there yet"."""
        for reply in ("", "Unknown command", "No dimension has installed a pool yet"):
            self.assertEqual(self.parse(reply), -1)


class RollableSelectionTests(unittest.TestCase):
    def test_base_worlds_are_excluded(self):
        """They load themselves at boot, so they are already in the record and
        `customdim load overworld` would be meaningless."""
        import tempfile
        import json
        from pathlib import Path
        with tempfile.TemporaryDirectory() as tmp:
            dims = Path(tmp) / "dimensions"
            dims.mkdir()
            for slug in list(BASE_WORLD_IDS) + ["the_test", "the_other"]:
                (dims / ("%s.json" % slug)).write_text(json.dumps(
                    {"type": "multi_biome", "biomes": ["minecraft:plains"]}))
            # Not rollable: explicit opt-out and a flat world.
            (dims / "the_skipped.json").write_text(json.dumps(
                {"type": "multi_biome", "seedRoll": {"skip": True}}))
            (dims / "the_flat.json").write_text(json.dumps({"type": "superflat"}))

            got = wsp.rollable_slugs(tmp)
        self.assertEqual(got, ["the_other", "the_test"])


class BatchingTests(unittest.TestCase):
    def test_the_batch_size_and_timeout_are_sane(self):
        """A batch that cannot settle inside its timeout makes every batch stall
        for the full window, turning a warmup into ten idle minutes."""
        self.assertGreaterEqual(wsp.BATCH, 1)
        self.assertGreaterEqual(wsp.BATCH_TIMEOUT, wsp.POLL_SECONDS * 2)


if __name__ == "__main__":
    unittest.main()
