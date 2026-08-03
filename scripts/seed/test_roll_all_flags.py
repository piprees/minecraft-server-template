#!/usr/bin/env python3
"""Guards on roll-all.sh's spool handling and flag forwarding.

Two regressions this pins, both of which cost real work:

1. `roll()` used to rotate fast-roller.csv out of the way at the start of
   every run. The CSV is the spool the fold reads, so the moment a new roll
   began, the previous run's measurements left the fold's reach — recoverable
   by hand, and only if you knew to look. It is now append-only, and only an
   explicit --reset/--reset-csv clears it.

2. --census-workers was forwarded to `score-dimensions.py finalise` but not to
   `fast_roller.py`, and the census actually runs inside fast_roller's fold
   step. The flag therefore controlled nothing: by the time finalise ran,
   every census was already computed and cached.

These are structural assertions over the script text plus a real `--help`
run, because the roll path itself needs Docker, mod jars and a warmup.
"""
import re
import subprocess
import unittest
from pathlib import Path

ROLL_ALL = Path(__file__).with_name("roll-all.sh")
SCRIPT = ROLL_ALL.read_text()


def function_body(name):
    """The text of a `name() { ... }` block, to the first column-0 brace."""
    match = re.search(rf"^{name}\(\) \{{\n(.*?)^\}}", SCRIPT,
                      re.MULTILINE | re.DOTALL)
    assert match, f"{name}() not found in roll-all.sh"
    return match.group(1)


class SpoolHandlingTests(unittest.TestCase):
    def test_a_normal_roll_does_not_touch_the_csv(self):
        body = function_body("roll")
        self.assertNotIn("mv \"$SEEDTEST/fast-roller.csv\"", body,
                         "roll() must not rotate the spool — it is the only "
                         "copy of measurements not yet folded into the bank")
        self.assertNotIn("rm -f \"$SEEDTEST/fast-roller.csv\"", body)

    def test_reset_csv_archives_the_spool(self):
        self.assertIn("--reset-csv", SCRIPT)
        block = SCRIPT.split("--reset-csv)", 1)[1].split(";;", 1)[0]
        self.assertIn("fast-roller.csv", block)
        self.assertIn("mv ", block, "archive rather than delete")
        self.assertNotIn("rm -rf \"$SEEDTEST\"", block,
                         "--reset-csv must keep the candidate bank")

    def test_reset_all_is_accepted_alongside_reset(self):
        self.assertRegex(SCRIPT, r"--reset \| --reset-all\)")

    def test_reset_still_wipes_everything(self):
        block = SCRIPT.split("--reset | --reset-all)", 1)[1].split(";;", 1)[0]
        self.assertIn('rm -rf "$SEEDTEST"', block)


class FlagForwardingTests(unittest.TestCase):
    def test_census_flags_reach_fast_roller(self):
        """fast_roller's fold is where the census actually runs."""
        body = function_body("roll")
        self.assertIn("fast_roller.py", body)
        self.assertIn("--census-workers", body)
        self.assertIn("--census-top", body)

    def test_census_flags_also_reach_finalise(self):
        body = function_body("finalise")
        self.assertIn("--census-workers", body)
        self.assertIn("--census-top", body)

    def test_fast_roller_accepts_what_roll_all_sends(self):
        fast = ROLL_ALL.with_name("fast_roller.py").read_text()
        for flag in ("--census-workers", "--census-top"):
            self.assertIn(f'"{flag}"', fast,
                          f"roll-all.sh passes {flag} but fast_roller.py "
                          f"does not accept it")

    def test_score_dimensions_accepts_census_top(self):
        score = ROLL_ALL.with_name("score-dimensions.py").read_text()
        self.assertIn('"--census-top"', score)


class FoldOnlyTests(unittest.TestCase):
    """Resuming an interrupted census must not re-roll first."""

    def test_fold_only_skips_the_roll(self):
        self.assertIn("--fold-only", SCRIPT)
        tail = SCRIPT.split("if [[ \"$FOLD_ONLY\" != 1 ]]", 1)
        self.assertEqual(len(tail), 2, "the main flow must gate roll on FOLD_ONLY")
        self.assertIn("finalise", tail[1])

    def test_fold_only_names_the_spool(self):
        """With no roll ahead of it, the CSV is the only measurement source —
        fast_roller is what normally folds it into the bank."""
        body = function_body("finalise")
        self.assertIn("FOLD_ONLY", body)
        self.assertIn("fast-roller.csv", body)


class HelpTests(unittest.TestCase):
    def test_help_runs_and_lists_the_new_flags(self):
        result = subprocess.run(["bash", str(ROLL_ALL), "--help"],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        for flag in ("--reset-csv", "--reset-all", "--census-top", "--fold-only"):
            self.assertIn(flag, result.stdout)

    def test_help_never_destroys_anything(self):
        """--help is answered before the parse loop, so `--reset --help`
        must not wipe the bank on its way to printing a help page."""
        head = SCRIPT.split("while [[ $# -gt 0 ]]", 1)[0]
        self.assertIn("--help", head.split("for arg in", 1)[1])


if __name__ == "__main__":
    unittest.main()
