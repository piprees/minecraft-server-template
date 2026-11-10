#!/usr/bin/env python3
"""Tests for deploy.sh's step 14: base-world activation, the custom-dimensions
health check, the world spawn and the game rules, plus the deploy's lock and
whitelist guards.

These drive the SHIPPED text: each test slices the block out of scripts/deploy.sh
by anchor strings and runs it under stubbed docker/rcon/sleep. A copy of the
logic here would pass while the real script rots, which is the failure this
guards against — the block cannot be exercised any other way, because dev-up.sh
has no activation path and the smoke test boots the local profile.

The activation invariant is serialisation: one dimension's chunk must be on
disk before the next is started (K6).
"""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

DEPLOY_SH = Path(__file__).resolve().parent.parent / "deploy.sh"

ACTIVATION_BLOCK = (
    'DIM_NAMESPACE="$(python3 -c "',
    'echo "  $ACTIVATED_COUNT base world(s) activated"',
)
LIST_BLOCK = (
    'DIMENSION_LIST_ATTEMPTS="${DIMENSION_LIST_ATTEMPTS:-6}"',
    'echo "  $DIM_SUMMARY"',
)
SPAWN_BLOCK = (
    'SPAWN_FROM_DEFINITION="$(python3 -c "',
    'echo "  Spawn: ${SPAWN_X} ${SPAWN_Y} ${SPAWN_Z} (SPAWN_X/Y/Z)"\nfi',
)

REGIONS = {
    "the_nether": "data/world/DIM-1/region",
    "the_end": "data/world/DIM1/region",
    "the_aviary": "data/world/dimensions/adventure/the_aviary/region",
}
LIST_REPLY = "82 dimensions: 4 base, 78 lazy, 0 open.overworld: base, 0 players, 441 chunks loaded"

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
  local id="${1#execute in }"
  id="${id%% *}"
  case "$1" in
    *the_nether*) echo "$SERVER_DIR/data/world/DIM-1/region" ;;
    *the_end*)    echo "$SERVER_DIR/data/world/DIM1/region" ;;
    *)            echo "$SERVER_DIR/data/world/dimensions/${id%%:*}/the_aviary/region" ;;
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
      if [[ "$SCENARIO" == no_aviary && "$cmd" == *the_aviary* ]]; then
        echo "Unknown or invalid dimension"
        return 0
      fi
      if [[ "$SCENARIO" == dead_on_add ]]; then
        return 124
      fi
      region_for "${cmd}" > "$SERVER_DIR/live_region"
      echo "Added 1 chunk"
      return 0
      ;;
    "customdim list")
      LISTS=$(( $(cat "$SERVER_DIR/lists" 2> /dev/null || echo 0) + 1 ))
      echo "$LISTS" > "$SERVER_DIR/lists"
      case "$SCENARIO" in
        list_dead) return 124 ;;
        list_never) echo "The level manager has not started." ;;
        list_late) if [[ $LISTS -lt 3 ]]; then echo "The level manager has not started."; else echo "$LIST_REPLY"; fi ;;
        *) echo "$LIST_REPLY" ;;
      esac
      return 0
      ;;
    *) return 0 ;;
  esac
}
"""

# Every slice must still contain these, or the extraction has silently
# shrunk past the logic under test and the suite would pass on nothing.
BLOCK_MUST_CONTAIN = {
    ACTIVATION_BLOCK[0]: ("settings.json", "dim_has_region", "wait_for_region",
                          "ACTIVATION_STALL_LIMIT", "forceload add", "the_aviary"),
    LIST_BLOCK[0]: ("customdim list", "exit 1"),
    SPAWN_BLOCK[0]: ("overworld.json", "setworldspawn", "SPAWN_X"),
    "restore_whitelist() {": ("rcon_verified", "WHITELIST_CLEARED"),
}


def extract(start, end):
    """Slice a block out of the real deploy.sh by anchor strings."""
    text = DEPLOY_SH.read_text()
    if text.count(start) != 1:
        raise AssertionError(f"start anchor is not unique: {start!r}")
    i = text.index(start)
    j = text.index(end, i) + len(end)
    block = text[i:j]
    for needle in BLOCK_MUST_CONTAIN.get(start, ()):
        if needle not in block:
            raise AssertionError(
                f"extraction lost {needle!r} — the anchors no longer bound the "
                f"logic under test, so these tests are asserting on nothing"
            )
    return block


class ActivationHarness(unittest.TestCase):
    block_anchors = ACTIVATION_BLOCK
    prelude = ""

    def run_block(self, scenario, generated=(), files=None, **env):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "data" / "world").mkdir(parents=True)
        for slug in generated:
            region = root / REGIONS[slug]
            region.mkdir(parents=True)
            (region / "r.0.0.mca").write_bytes(b"\0" * 12288)
        for rel, text in (files or {}).items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text)

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
                "LIST_REPLY": LIST_REPLY,
                **{k: str(v) for k, v in env.items()},
            },
        )
        calls = (root / "calls.log").read_text().splitlines()
        return proc.returncode, proc.stdout + proc.stderr, calls, root


class BaseWorldActivationTests(ActivationHarness):
    def test_a_fresh_world_activates_all_three(self):
        rc, out, calls, root = self.run_block("fresh")
        self.assertEqual(rc, 0, out)
        self.assertIn("3 base world(s) activated", out)
        self.assertIn("exec execute in adventure:the_aviary run forceload add 0 0", calls)
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
        self.assertIn("0 base world(s) activated", out)
        self.assertEqual([c for c in calls if "forceload" in c], [])

    def test_a_partially_generated_world_activates_only_what_is_missing(self):
        rc, out, calls, _ = self.run_block("fresh", generated=("the_end",))
        self.assertEqual(rc, 0, out)
        self.assertIn("2 base world(s) activated", out)
        self.assertNotIn("the_end", " ".join(c for c in calls if "forceload" in c))

    def test_an_unanswering_server_stops_the_deploy(self):
        rc, out, calls, _ = self.run_block("wedged", ACTIVATION_STALL_LIMIT=3)
        self.assertEqual(rc, 1)
        self.assertIn("never wrote a region file (K6)", out)
        self.assertEqual(len([c for c in calls if "save-all flush" in c]), 3)

    def test_a_dimension_the_pack_lacks_is_skipped_not_fatal(self):
        rc, out, _calls, _ = self.run_block("no_aviary")
        self.assertEqual(rc, 0, out)
        self.assertIn("not present on this server, skipping", out)
        self.assertIn("2 base world(s) activated", out)

    def test_the_aviary_s_namespace_comes_from_settings_json(self):
        settings = "data/config/custom-dimensions/dimensions/settings.json"
        rc, out, calls, root = self.run_block("fresh", files={settings: '{"schemaVersion": 2, "namespace": "realm"}'})
        self.assertEqual(rc, 0, out)
        self.assertIn("exec execute in realm:the_aviary run forceload add 0 0", calls)
        self.assertTrue((root / "data/world/dimensions/realm/the_aviary/region/r.0.0.mca").exists())

    def test_no_lazy_dimension_is_opened(self):
        _rc, _out, calls, _ = self.run_block("fresh")
        self.assertEqual([c for c in calls if "customdim" in c], [])

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


class DimensionListTests(ActivationHarness):
    block_anchors = LIST_BLOCK

    def test_a_running_mod_reports_its_dimensions(self):
        rc, out, calls, _ = self.run_block("list_ok")
        self.assertEqual(rc, 0, out)
        self.assertIn("  82 dimensions: 4 base, 78 lazy, 0 open", out)
        self.assertEqual([c for c in calls if c.startswith("exec")], ["exec customdim list"])

    def test_a_level_manager_still_starting_is_waited_for(self):
        rc, out, calls, _ = self.run_block("list_late")
        self.assertEqual(rc, 0, out)
        self.assertEqual(len([c for c in calls if c == "exec customdim list"]), 3)
        self.assertIn("no level index yet (attempt 2/6)", out)

    def test_a_server_without_the_mod_s_levels_fails_the_deploy(self):
        rc, out, calls, _ = self.run_block("list_never", DIMENSION_LIST_ATTEMPTS=4)
        self.assertEqual(rc, 1)
        self.assertIn("customdim list never reported the dimensions", out)
        self.assertEqual(len([c for c in calls if c == "exec customdim list"]), 4)

    def test_an_unanswering_server_fails_after_the_bounded_tries(self):
        rc, out, calls, _ = self.run_block("list_dead", DIMENSION_LIST_ATTEMPTS=2)
        self.assertEqual(rc, 1)
        self.assertEqual(len([c for c in calls if c == "exec customdim list"]), 2)

    def test_the_parsed_reply_is_the_mod_s_header_template(self):
        messages = json.loads((DEPLOY_SH.parent.parent / "config/messages.json").read_text())
        header = messages["customdim.list.header"]
        rendered = header.format(count=82, base=4, lazy=78, open=0)
        self.assertEqual(rendered, LIST_REPLY.split("overworld:")[0])


OVERWORLD = "data/config/custom-dimensions/dimensions/overworld.json"


class SpawnTests(ActivationHarness):
    block_anchors = SPAWN_BLOCK

    def spawn_calls(self, calls):
        return [c for c in calls if "setworldspawn" in c]

    def test_the_overworld_definition_s_spawn_is_set(self):
        rc, out, calls, _ = self.run_block("fresh", files={
            OVERWORLD: '// a comment line\n{"schemaVersion": 2, "spawn": [-64, 179, 640]}'})
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.spawn_calls(calls), ["rcon setworldspawn -64 179 640"])

    def test_the_definition_wins_over_the_environment(self):
        _rc, _out, calls, _ = self.run_block("fresh", files={OVERWORLD: '{"spawn": [1, 70, 2]}'},
                                             SPAWN_X=9, SPAWN_Y=9, SPAWN_Z=9)
        self.assertEqual(self.spawn_calls(calls), ["rcon setworldspawn 1 70 2"])

    def test_the_unchosen_sentinel_falls_back_to_the_environment(self):
        _rc, out, calls, _ = self.run_block("fresh", files={OVERWORLD: '{"spawn": [0, 64, 0]}'},
                                            SPAWN_X=5, SPAWN_Y=80, SPAWN_Z=-5)
        self.assertEqual(self.spawn_calls(calls), ["rcon setworldspawn 5 80 -5"])
        self.assertIn("(SPAWN_X/Y/Z)", out)

    def test_no_spawn_anywhere_sets_nothing(self):
        for files in ({}, {OVERWORLD: '{"schemaVersion": 2}'}, {OVERWORLD: "not json"},
                      {OVERWORLD: '{"spawn": [1, "x", 2]}'}):
            with self.subTest(files=files):
                rc, out, calls, _ = self.run_block("fresh", files=files)
                self.assertEqual(rc, 0, out)
                self.assertEqual(self.spawn_calls(calls), [])


class GameRuleTests(unittest.TestCase):
    """restore_game_rules runs lib.sh's one list through the version's ids."""

    def run_rules(self, version):
        text = DEPLOY_SH.read_text()
        body = text[text.index("restore_game_rules() {"):]
        body = body[: body.index("\n}\n") + 3]
        script = (f'source "{DEPLOY_SH.parent / "lib.sh"}"\n'
                  'rcon() { echo "$*"; }\n' + body + "restore_game_rules\n")
        proc = subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                              env={"PATH": "/usr/bin:/bin", "MC_VERSION_OVERRIDE": version})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc.stdout.splitlines()

    def test_every_rule_is_set_with_the_26_3_id(self):
        self.assertEqual(self.run_rules("26.3"), [
            "gamerule spawn_mobs true", "gamerule random_tick_speed 3", "gamerule advance_time true",
            "gamerule advance_weather true", "gamerule fire_spread_radius_around_player 128",
            "gamerule spawn_phantoms false", "gamerule players_sleeping_percentage 50",
            "gamerule respawn_radius 0", "gamerule forgive_dead_players true", "gamerule universal_anger false",
            "gamerule mob_griefing true", "gamerule players_nether_portal_default_delay 0",
            "gamerule log_admin_commands true"])

    def test_the_list_has_one_definition(self):
        scripts = DEPLOY_SH.parent
        for name in ("deploy.sh", "dev-up.sh", "admin-parity-check.sh"):
            text = (scripts / name).read_text()
            with self.subTest(script=name):
                self.assertIn("mc_game_rules", text)
                self.assertNotIn("mc_game_rule doInsomnia", text)
                self.assertNotIn("mc_game_rule logAdminCommands true)\"", text.replace(
                    "set -- $(mc_game_rule logAdminCommands true)", ""))

    def test_quiet_boot_turns_off_only_listed_rules(self):
        text = DEPLOY_SH.read_text()
        quiet = re.findall(r'rcon "gamerule \$\(mc_game_rule ([A-Za-z]+) ([a-z0-9]+)\)"', text)
        listed = {line.split()[0] for line in subprocess.run(
            ["bash", "-c", f'source "{DEPLOY_SH.parent / "lib.sh"}"; mc_game_rules'],
            capture_output=True, text=True).stdout.splitlines()}
        self.assertTrue(quiet)
        for rule, _value in quiet:
            self.assertIn(rule, listed, f"quiet boot sets {rule}, which restore_game_rules never restores")


class OverlayStagingTests(unittest.TestCase):
    def test_deploy_and_dev_up_stage_the_dimension_overlay_the_same_way(self):
        scripts = DEPLOY_SH.parent
        for name, overlay in (("deploy.sh", "$SERVER_DIR/overlay/config/custom-dimensions"),
                              ("dev-up.sh", "$CONSUMER_DIR/overlay/config/custom-dimensions")):
            text = (scripts / name).read_text()
            with self.subTest(script=name):
                self.assertIn('stage-dimensions.py" --platform', text)
                self.assertIn(overlay, text)
                self.assertNotIn("custom-dimensions/overlay/${f#./}", text)

    def test_the_stager_ships_in_the_bundle(self):
        self.assertIn("scripts/stage-dimensions.py",
                      (DEPLOY_SH.parent / "build-stack-bundle.sh").read_text())


class RestoreWhitelistTests(unittest.TestCase):
    """The real function, driven with a flaky rcon_verified.

    bash's errexit is per CALL SITE: a bare `restore_whitelist` lets an
    internal failure kill the whole script mid-loop, while `restore_whitelist
    || warn` makes the body immune AND collapses the return value to the last
    statement. Both shapes are exercised here.
    """

    BLOCK = ("restore_whitelist() {", "\n}\n")

    def run_restore(self, fail_on, call, players="alice,bob,carol"):
        """fail_on: player names whose `whitelist add` fails; 'ON' = enforcement."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        script = root / "run.sh"
        script.write_text(
            "set -euo pipefail\n"
            "warn() { echo \"WARN: $*\" >&2; }\n"
            f'FAIL_ON="{fail_on}"\n'
            "rcon_verified() {\n"
            '  local label="$1"; shift\n'
            '  local target="$*"\n'
            '  for f in $FAIL_ON; do\n'
            '    if [[ "$target" == *"$f"* ]]; then return 1; fi\n'
            "  done\n"
            '  echo "did: $target" >> "$OUT"\n'
            "  return 0\n"
            "}\n"
            f'WHITELIST="{players}"\n'
            "WHITELIST_CLEARED=1\n"
            + extract(*self.BLOCK)
            + "\n"
            + call
            + '\necho "SURVIVED cleared=$WHITELIST_CLEARED"\n'
        )
        out = root / "did.log"
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={"PATH": "/usr/bin:/bin", "OUT": str(out)},
        )
        did = out.read_text().splitlines() if out.exists() else []
        return proc, did

    def test_one_flaky_player_does_not_skip_the_players_after_it(self):
        proc, did = self.run_restore("bob", "restore_whitelist")
        self.assertIn("SURVIVED", proc.stdout, proc.stderr)
        self.assertTrue(any("carol" in d for d in did), f"carol was skipped: {did}")
        self.assertIn("WARN:", proc.stderr, "the skipped player must be reported")

    def test_one_flaky_player_does_not_fail_the_deploy(self):
        proc, _ = self.run_restore("bob", "restore_whitelist")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("cleared=0", proc.stdout, "the flag must still be cleared")

    def test_a_failed_enforcement_call_does_fail(self):
        """`whitelist on` is the load-bearing half — that one must be fatal."""
        proc, _ = self.run_restore("whitelist on", "restore_whitelist")
        self.assertEqual(proc.returncode, 1)
        self.assertNotIn("SURVIVED", proc.stdout)

    def test_the_guarded_call_can_still_report_failure(self):
        """In the trap the call is guarded, so it must not always return 0."""
        proc, _ = self.run_restore(
            "whitelist on", 'restore_whitelist || echo "WARNING FIRED"'
        )
        self.assertEqual(proc.returncode, 0)
        self.assertIn("WARNING FIRED", proc.stdout, "the diagnostic is dead code")
        self.assertIn("cleared=1", proc.stdout, "a failed restore must not clear it")


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

    @unittest.skipIf(os.geteuid() == 0, "root writes through a read-only mode")
    def test_an_unwritable_lock_file_warns_rather_than_failing_silently(self):
        """A root-owned lock file must not disable exclusion without saying so."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        bindir = root / "bin"
        bindir.mkdir()
        fake = bindir / "flock"
        fake.write_text("#!/bin/sh\nexit 0\n")
        fake.chmod(0o755)
        lock = root / ".deploy.lock"
        lock.write_text("someone-else pid=1\n")
        lock.chmod(0o444)
        self.addCleanup(lock.chmod, 0o644)

        script = root / "run.sh"
        script.write_text(
            f'source "{self.LIB_SH}"\n'
            f'SERVER_DIR="{root}"\n'
            'acquire_deploy_lock "deploy.sh"\n'
            'echo "PROCEEDED"\n'
        )
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={"PATH": f"{bindir}:/usr/bin:/bin"},
        )
        self.assertEqual(proc.returncode, 0, "must fail open, not block every deploy")
        self.assertIn("PROCEEDED", proc.stdout)
        self.assertIn("UNGUARDED", proc.stderr, "the failure must be visible in the log")

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

    def test_the_step_12_lockdown_is_undone_however_the_deploy_exits(self):
        """Whitelist, discord-sync and quiet-boot rules all outlive an exit."""
        text = DEPLOY_SH.read_text()
        traps = re.findall(r"^trap '([^']*)' EXIT", text, re.M)
        self.assertTrue(traps, "no EXIT trap found")
        self.assertIn("restore_after_abort", traps[-1])

        body = text[text.index("restore_after_abort() {") :]
        body = body[: body.index("\n}\n")]
        self.assertIn("restore_whitelist", body)
        self.assertIn("docker start discord-sync", body)
        self.assertIn("restore_game_rules", body)

    def test_the_game_rules_have_one_definition(self):
        """The trap and the happy path must not drift apart."""
        text = DEPLOY_SH.read_text()
        self.assertEqual(text.count("restore_game_rules() {"), 1)
        self.assertEqual(text.count("done < <(mc_game_rules)"), 1)

    def test_no_1_21_1_dimension_setup_remains(self):
        text = DEPLOY_SH.read_text()
        for gone in ("customdim load", ".dimension-setup", "paradise_lost", "DIMENSION_FAILURE_LIMIT",
                     "custom-dimensions/overlay/dimensions"):
            self.assertNotIn(gone, text)

    def test_every_early_exit_sits_under_the_trap(self):
        """An exit before the trap is registered would restore nothing."""
        text = DEPLOY_SH.read_text()
        trap_at = text.index("trap 'restore_after_abort")
        for match in re.finditer(r"^\s*exit 1$", text, re.M):
            if match.start() < trap_at:
                line = text.count("\n", 0, match.start()) + 1
                self.fail(f"exit 1 at line {line} is above the EXIT trap")


if __name__ == "__main__":
    unittest.main()
