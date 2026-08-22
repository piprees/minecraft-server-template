#!/usr/bin/env python3
"""Tests for deploy.sh's dimension activation and its circuit breakers.

These drive the SHIPPED text: each test slices the block out of scripts/deploy.sh
by anchor strings and runs it under stubbed docker/rcon/sleep. A copy of the
logic here would pass while the real script rots, which is the failure this
guards against — the block cannot be exercised any other way, because dev-up.sh
has no activation path and the smoke test boots the local profile.

The invariant under test is serialisation: one dimension's chunk must be on disk
before the next is started (K6).
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

DEPLOY_SH = Path(__file__).resolve().parent.parent / "deploy.sh"

ACTIVATION_BLOCK = (
    'WORLD_DIR="$SERVER_DIR/data/world"',
    'echo "  $ACTIVATED_COUNT reserved dimension(s) activated"',
)
SETUP_LOOP_BLOCK = (
    "  NEW_COUNT=0",
    "    exit 1\n  fi",
)

REGIONS = {
    "the_nether": "data/world/DIM-1/region",
    "the_end": "data/world/DIM1/region",
    "paradise_lost": "data/world/dimensions/paradise_lost/paradise_lost/region",
}

# Stubs stand in for the container. SCENARIO decides what the fake server does;
# every RCON call is recorded to calls.log so ordering can be asserted.
STUBS = r"""
set -euo pipefail
: "${SCENARIO:?}"
: "${SERVER_DIR:?}"
CALLS="$SERVER_DIR/calls.log"
: > "$CALLS"
FLUSHES=0

region_for() {
  case "$1" in
    *the_nether*) echo "$SERVER_DIR/data/world/DIM-1/region" ;;
    *the_end*)    echo "$SERVER_DIR/data/world/DIM1/region" ;;
    *)            echo "$SERVER_DIR/data/world/dimensions/paradise_lost/paradise_lost/region" ;;
  esac
}

populate() {
  mkdir -p "$1"
  dd if=/dev/zero of="$1/r.0.0.mca" bs=1024 count=12 2> /dev/null
}

rcon() { echo "rcon $*" >> "$CALLS"; }
sleep() { :; }

# Shadows the binary: deploy.sh calls `timeout 30 docker exec mc rcon-cli "<cmd>"`.
timeout() {
  shift
  local cmd="${*: -1}"
  echo "exec $cmd" >> "$CALLS"
  case "$cmd" in
    "save-all flush")
      if [[ "$SCENARIO" == wedged ]]; then
        return 124
      fi
      FLUSHES=$((FLUSHES + 1))
      # Whichever dimension is live finishes generating on its 3rd flush.
      if [[ $FLUSHES -ge 3 && -s "$SERVER_DIR/live_region" ]]; then
        populate "$(cat "$SERVER_DIR/live_region")"
        FLUSHES=0
      fi
      return 0
      ;;
    *"forceload add"*)
      if [[ "$SCENARIO" == no_paradise && "$cmd" == *paradise_lost* ]]; then
        echo "Unknown or invalid dimension"
        return 0
      fi
      if [[ "$SCENARIO" == dead_on_add ]]; then
        return 124
      fi
      region_for "$cmd" > "$SERVER_DIR/live_region"
      echo "Added 1 chunk"
      return 0
      ;;
    *"run seed"*)
      case "$SCENARIO" in
        loop_wedged) echo "" ;;
        loop_flaky)  case "$cmd" in *dim_0[357]*) echo "" ;; *) echo "Seed: [1]" ;; esac ;;
        loop_late)   case "$cmd" in *dim_1[012]*) echo "" ;; *) echo "Seed: [1]" ;; esac ;;
        *)           echo "Seed: [1]" ;;
      esac
      return 0
      ;;
    *) return 0 ;;
  esac
}
"""

SETUP_LOOP_PRELUDE = r"""
DIM_NAMESPACE=adventure
SETUP_MARKERS_DIR="$SERVER_DIR/markers"
mkdir -p "$SETUP_MARKERS_DIR"
DIM_DATA="adventure
$(for i in $(seq 0 19); do printf 'dim_%02d|1\n' "$i"; done)"
"""


def extract(start, end):
    """Slice a block out of the real deploy.sh by anchor strings."""
    text = DEPLOY_SH.read_text()
    i = text.index(start)
    j = text.index(end, i) + len(end)
    return text[i:j]


class ActivationHarness(unittest.TestCase):
    block_anchors = ACTIVATION_BLOCK
    prelude = ""

    def run_block(self, scenario, generated=(), **env):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "data" / "world").mkdir(parents=True)
        for slug in generated:
            region = root / REGIONS[slug]
            region.mkdir(parents=True)
            (region / "r.0.0.mca").write_bytes(b"\0" * 12288)

        script = root / "run.sh"
        script.write_text(STUBS + self.prelude + "\n" + extract(*self.block_anchors))
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "SCENARIO": scenario,
                "SERVER_DIR": str(root),
                **{k: str(v) for k, v in env.items()},
            },
        )
        calls = (root / "calls.log").read_text().splitlines()
        return proc.returncode, proc.stdout + proc.stderr, calls, root


class ReservedActivationTests(ActivationHarness):
    def test_a_fresh_world_activates_all_three(self):
        rc, out, _calls, root = self.run_block("fresh")
        self.assertEqual(rc, 0, out)
        self.assertIn("3 reserved dimension(s) activated", out)
        for slug in REGIONS:
            self.assertTrue((root / REGIONS[slug] / "r.0.0.mca").exists(), slug)

    def test_dimensions_are_started_one_at_a_time(self):
        """The K6 invariant: no forceload is issued while another is generating."""
        _, _out, calls, _ = self.run_block("fresh")
        adds = [i for i, c in enumerate(calls) if "forceload add" in c]
        removes = [i for i, c in enumerate(calls) if "forceload remove" in c]
        self.assertEqual(len(adds), 3, calls)
        self.assertEqual(len(removes), 3, calls)
        # Each add is followed by its own remove before the next add begins.
        for add, remove, next_add in zip(adds, removes, adds[1:] + [len(calls)]):
            self.assertLess(add, remove)
            self.assertLess(remove, next_add)

    def test_an_already_generated_world_issues_no_forceload(self):
        rc, out, calls, _ = self.run_block("fresh", generated=tuple(REGIONS))
        self.assertEqual(rc, 0, out)
        self.assertIn("0 reserved dimension(s) activated", out)
        self.assertEqual([c for c in calls if "forceload" in c], [])

    def test_a_partially_generated_world_activates_only_what_is_missing(self):
        rc, out, calls, _ = self.run_block("fresh", generated=("the_end",))
        self.assertEqual(rc, 0, out)
        self.assertIn("2 reserved dimension(s) activated", out)
        self.assertNotIn("the_end", " ".join(c for c in calls if "forceload" in c))

    def test_an_unanswering_server_stops_the_deploy(self):
        rc, out, calls, _ = self.run_block("wedged", ACTIVATION_STALL_LIMIT=3)
        self.assertEqual(rc, 1)
        self.assertIn("never wrote a region file (K6)", out)
        self.assertEqual(len([c for c in calls if "save-all flush" in c]), 3)

    def test_a_dimension_the_pack_lacks_is_skipped_not_fatal(self):
        rc, out, _calls, _ = self.run_block("no_paradise")
        self.assertEqual(rc, 0, out)
        self.assertIn("not present on this server, skipping", out)
        self.assertIn("2 reserved dimension(s) activated", out)

    def test_a_timeout_on_the_forceload_itself_stops_the_deploy(self):
        """A timed-out add is a wedge, not an absent dimension."""
        rc, out, _, _ = self.run_block("dead_on_add")
        self.assertEqual(rc, 1)
        self.assertIn("never wrote a region file (K6)", out)

    def test_the_wait_is_bounded_when_the_server_answers_but_never_generates(self):
        rc, out, _calls, _ = self.run_block("fresh", ACTIVATION_ATTEMPTS=4)
        # 'fresh' generates on the 3rd flush, so 4 attempts still succeeds;
        # drop below that and the bound must fire rather than hang.
        self.assertEqual(rc, 0, out)
        rc, out, calls, _ = self.run_block("fresh", ACTIVATION_ATTEMPTS=2)
        self.assertEqual(rc, 1)
        self.assertEqual(len([c for c in calls if "save-all flush" in c]), 2)


class CustomDimensionLoopTests(ActivationHarness):
    block_anchors = SETUP_LOOP_BLOCK
    prelude = SETUP_LOOP_PRELUDE

    def markers(self, root):
        return sorted(p.name for p in (root / "markers").iterdir())

    def test_every_dimension_is_configured_when_the_server_answers(self):
        rc, out, _, root = self.run_block("loop_ok")
        self.assertEqual(rc, 0, out)
        self.assertIn("20 dimension(s) newly configured", out)
        self.assertEqual(len(self.markers(root)), 20)

    def test_the_breaker_trips_after_consecutive_failures(self):
        rc, out, _, root = self.run_block("loop_wedged", DIMENSION_FAILURE_LIMIT=3)
        self.assertEqual(rc, 1)
        self.assertIn("dimensions in a row failed to load (K6)", out)
        self.assertEqual(self.markers(root), [])
        # It must stop at the limit, not grind through all 20.
        self.assertEqual(out.count("not ready yet"), 3)

    def test_scattered_failures_do_not_trip_the_breaker(self):
        rc, out, _, root = self.run_block("loop_flaky", DIMENSION_FAILURE_LIMIT=3)
        self.assertEqual(rc, 0, out)
        self.assertIn("17 dimension(s) newly configured", out)
        self.assertEqual(len(self.markers(root)), 17)

    def test_a_late_wedge_keeps_the_markers_already_earned(self):
        rc, out, _, root = self.run_block("loop_late", DIMENSION_FAILURE_LIMIT=3)
        self.assertEqual(rc, 1)
        self.assertEqual(len(self.markers(root)), 10)

    def test_an_already_configured_dimension_is_skipped(self):
        _rc, _out, _calls, root = self.run_block("loop_ok")
        # Re-running against the markers the first pass left must be a no-op.
        script = root / "run.sh"
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "SCENARIO": "loop_ok",
                "SERVER_DIR": str(root),
            },
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("0 dimension(s) newly configured, 20 already set up", proc.stdout)


class DeployLockTests(unittest.TestCase):
    """lib.sh's flock guard, driven through a stubbed flock so it runs anywhere.

    flock is Linux-only and these tests run on macOS too, so PATH gets a fake
    one whose exit status the test chooses.
    """

    LIB_SH = DEPLOY_SH.parent / "lib.sh"

    def run_lock(self, flock_rc, holder="deploy.sh", existing=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        bindir = root / "bin"
        bindir.mkdir()
        fake = bindir / "flock"
        fake.write_text(f"#!/bin/sh\nexit {flock_rc}\n")
        fake.chmod(0o755)
        if existing is not None:
            (root / ".deploy.lock").write_text(existing)

        script = root / "run.sh"
        script.write_text(
            f'source "{self.LIB_SH}"\n'
            f'SERVER_DIR="{root}"\n'
            f'acquire_deploy_lock "{holder}"\n'
            'echo "PROCEEDED"\n'
            'echo "stderr-marker" >&2\n'
        )
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={"PATH": f"{bindir}:/usr/bin:/bin"},
        )
        return proc, root

    def test_an_uncontended_lock_is_taken_and_records_its_holder(self):
        proc, root = self.run_lock(flock_rc=0)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("PROCEEDED", proc.stdout)
        recorded = (root / ".deploy.lock").read_text()
        self.assertIn("deploy.sh", recorded)
        self.assertIn("pid=", recorded)

    def test_a_contended_lock_refuses_and_names_the_holder(self):
        proc, _ = self.run_lock(
            flock_rc=1, holder="infra-deploy.sh", existing="deploy.sh pid=42\n"
        )
        self.assertEqual(proc.returncode, 1)
        self.assertNotIn("PROCEEDED", proc.stdout)
        self.assertIn("Refusing to run infra-deploy.sh", proc.stderr)
        self.assertIn("deploy.sh pid=42", proc.stderr)

    def test_taking_the_lock_does_not_swallow_the_caller_s_stderr(self):
        """`exec 200>> f 2>/dev/null` would redirect the whole script's stderr."""
        proc, _ = self.run_lock(flock_rc=0)
        self.assertIn("stderr-marker", proc.stderr)

    def test_an_unwritable_location_degrades_to_a_no_op(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        bindir = root / "bin"
        bindir.mkdir()
        fake = bindir / "flock"
        fake.write_text("#!/bin/sh\nexit 0\n")
        fake.chmod(0o755)
        readonly = root / "ro"
        readonly.mkdir()
        readonly.chmod(0o555)
        self.addCleanup(readonly.chmod, 0o755)

        script = root / "run.sh"
        script.write_text(
            f'source "{self.LIB_SH}"\n'
            f'SERVER_DIR="{readonly}"\n'
            'acquire_deploy_lock "deploy.sh"\n'
            'echo "PROCEEDED"\n'
        )
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={"PATH": f"{bindir}:/usr/bin:/bin"},
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("PROCEEDED", proc.stdout)

    def test_no_redirection_is_attached_to_the_exec(self):
        """Static guard: `exec <fd>> file` must carry no redirection of its own."""
        for line in self.LIB_SH.read_text().splitlines():
            stripped = line.strip()
            if stripped.startswith("exec ") and ">" in stripped:
                self.assertNotIn(
                    "2>", stripped, f"exec carries a stderr redirect: {stripped}"
                )

    def test_every_server_side_mutator_takes_the_lock(self):
        scripts = DEPLOY_SH.parent
        for name in ("deploy.sh", "infra-deploy.sh"):
            self.assertIn(
                "acquire_deploy_lock",
                (scripts / name).read_text(),
                f"{name} does not take the deploy lock",
            )
        # harden.sh is uploaded alone and cannot source lib.sh, so it checks inline.
        self.assertIn("flock -n", (scripts / "harden.sh").read_text())


class ShippedTextTests(unittest.TestCase):
    """Guards on deploy.sh itself, cheap enough to keep honest."""

    def test_every_rcon_helper_in_the_deploy_chain_is_bounded(self):
        scripts = DEPLOY_SH.parent
        for name in ("deploy.sh", "setup-permissions.sh", "lib.sh", "idle-tasks.sh"):
            text = (scripts / name).read_text()
            for match in re.finditer(r"^\s*(docker exec.*rcon-cli)", text, re.M):
                line_start = text.rfind("\n", 0, match.start()) + 1
                line = text[line_start : text.index("\n", match.start())]
                self.assertIn(
                    "timeout", line, f"{name}: unbounded rcon call: {line.strip()}"
                )

    def test_the_whitelist_is_restored_however_the_deploy_exits(self):
        text = DEPLOY_SH.read_text()
        self.assertIn("restore_whitelist_on_abort", text)
        traps = re.findall(r"^trap '([^']*)' EXIT", text, re.M)
        self.assertTrue(traps, "no EXIT trap found")
        self.assertIn("restore_whitelist_on_abort", traps[-1])


if __name__ == "__main__":
    unittest.main()
