#!/usr/bin/env bash
# Self-test for the portal matrix: the planner's arithmetic and the driver's
# helpers, each proved red as well as green.
#
# Purpose: portal-matrix-plan.py decides where every frame stands, which axis
#          it may stand on, and what each assertion should expect; a mistake
#          there is a run that measures the wrong thing and passes. This runs
#          both halves against fixtures — no Minecraft, no docker, no RCON, no
#          client — and every case names the failure it is guarding against.
# Context: template-only, run by hand. The python half imports the planner as a
#          module and drives it against synthetic dimension configs AND the
#          shipped ones; the bash half sources portal-matrix-lib.sh with a stub
#          rcon and fixture e2e-state artefacts.
# Usage:   ./portal-matrix-selftest.sh [--mutate NAME]
#
#          --mutate breaks one piece of the planner ON PURPOSE and re-runs the
#          python half, to show which case catches it. Names:
#            orientation   "horizontal" starts allowing every axis but Y
#            twin-offset   the neighbour frame moves next to the first one
#            igniter       every igniter expectation becomes "still in hand"
#            centre        the centre column rounds instead of truncating
#          A mutation that no case catches is itself a failure.
# Gotchas: the counters this reports are the SELF-TEST's own. Each bash case
#          runs a real assertion, so lib.sh's counters fill with the assertions
#          under test and are replaced at the end — a case that expects a FAIL
#          is a self-test PASS.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="portal-matrix-selftest"
SELFTEST_ROOT="${TMPDIR:-/tmp}/portal-matrix-selftest-$$"
mkdir -p "$SELFTEST_ROOT/consumer/data/config"

MUTATE=""
[ "${1:-}" = "--mutate" ] && MUTATE="${2:-}"

PLAYER="selftest"
CONSUMER_DIR="$SELFTEST_ROOT/consumer"
RUN_ROOT="$SELFTEST_ROOT/runs"
export PLAYER CONSUMER_DIR RUN_ROOT

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
# shellcheck source=portal-matrix-lib.sh
. "$HERE/portal-matrix-lib.sh"

POLL_STEP=1
SELF_OK=0
SELF_BAD=0

# lib.sh owns the EXIT trap, and it is what makes a partial run a failure.
# Replacing it would lose that, so this only hands the status on.
selftest_exit() {
  local rc=$?
  ( exit "$rc" )
  on_exit
}
trap selftest_exit EXIT

self_pass() { SELF_OK=$((SELF_OK + 1)); printf '  \033[32mSELF-PASS\033[0m %s\n' "$1"; }
self_fail() { SELF_BAD=$((SELF_BAD + 1)); printf '  \033[31mSELF-FAIL\033[0m %s\n' "$1"; }

# Runs one assertion and judges lib.sh's own verdict for it: the recorded
# ok/fail, and a substring the recorded "actual" must carry. The substring is
# what stops a helper passing this by failing for an unrelated reason.
expect() { # description want(ok|fail) actual-substring assertion...
  local want_verdict="$2" want_actual="$3" line got_verdict got_actual
  local description="$1"
  shift 3
  "$@"
  line="$(tail -1 "$RUN_DIR/assertions.tsv")"
  got_verdict="$(printf '%s' "$line" | cut -f2)"
  got_actual="$(printf '%s' "$line" | cut -f5)"
  if [ "$got_verdict" != "$want_verdict" ]; then
    self_fail "$description — wanted a $want_verdict, lib.sh recorded $got_verdict"
    return
  fi
  case "$got_actual" in
    *"$want_actual"*) self_pass "$description" ;;
    *) self_fail "$description — actual was '$got_actual', wanted '$want_actual'" ;;
  esac
}

# Judges a helper that returns a status rather than recording an assertion.
expect_rc() { # description want-rc command...
  local description="$1" want_rc="$2" rc
  shift 2
  "$@"
  rc=$?
  if [ "$rc" = "$want_rc" ]; then
    self_pass "$description"
  else
    self_fail "$description — wanted rc=$want_rc, got rc=$rc"
  fi
}

banner "portal matrix self-test — the planner's arithmetic and the driver's helpers"

# ===========================================================================
say "The planner, against fixtures and against the shipped configs"
# ===========================================================================
FIX="$RUN_DIR/fixture-dimensions"
mkdir -p "$FIX"

write_dim() { # slug json
  printf '%s\n' "$2" > "$FIX/$1.json"
}

write_dim standard '{"borders": {"player": 4096},
 "portal": {"frameBlock": "minecraft:copper_block", "igniterItem": "minecraft:diamond",
            "scale": 2.0}}'
write_dim doorish '{"borders": {"player": 4096},
 "portal": {"frameBlock": "minecraft:gold_block", "igniterItem": "minecraft:flint_and_steel",
            "scale": 2.0, "shape": "door"}}'
write_dim flatonly '{"borders": {"player": 4096},
 "portal": {"frameBlock": "minecraft:iron_block", "igniterItem": "minecraft:gold_ingot",
            "scale": 2.0, "orientation": "horizontal"}}'
write_dim endexit '{"borders": {"player": 1024},
 "portal": {"frameBlock": "minecraft:dark_prismarine", "igniterItem": "minecraft:ender_eye",
            "scale": 8.0, "shape": "end_exit"}}'
write_dim anchored '{"borders": {"player": 256},
 "portal": {"frameBlock": "minecraft:tuff", "igniterItem": "minecraft:amethyst_shard",
            "scale": 16.0, "anchor": [10, 70, 10]}}'
write_dim wideaura '{"borders": {"player": 4096},
 "portal": {"frameBlock": "minecraft:basalt", "igniterItem": "minecraft:blaze_rod",
            "scale": 2.0, "aura": {"subsume": "everything", "radius": 32}}}'
write_dim colourframe '{"borders": {"player": 4096},
 "portal": {"frameBlock": {"colorGroup": "red"}, "igniterItem": "minecraft:torch",
            "scale": 2.0}}'
write_dim tagframe '{"borders": {"player": 4096},
 "portal": {"frameBlock": "#c:copper_blocks", "igniterItem": "minecraft:torch",
            "scale": 2.0}}'
write_dim vanillaish '{"borders": {"player": 4096},
 "portal": {"frameBlock": "minecraft:obsidian", "igniterItem": "minecraft:flint_and_steel",
            "scale": 8.0, "vanillaManaged": true}}'
write_dim noportal '{"borders": {"player": 4096}}'
write_dim strangeigniter '{"borders": {"player": 4096},
 "portal": {"frameBlock": "minecraft:mud_bricks", "igniterItem": "minecraft:banana",
            "scale": 2.0}}'
write_dim tooborderly '{"borders": {"player": 100},
 "portal": {"frameBlock": "minecraft:calcite", "igniterItem": "minecraft:diamond",
            "scale": 16.0}}'
note "12 fixture dimensions in $FIX"

PY_OUT="$RUN_DIR/planner-selftest.txt"
MUTATE="$MUTATE" FIX="$FIX" HERE="$HERE" python3 - > "$PY_OUT" 2>&1 <<'PYTHON'
"""Cases for portal-matrix-plan.py. Each prints PASS/FAIL plus what it saw."""
import importlib.util
import json
import os
import subprocess
import sys

HERE = os.environ["HERE"]
FIX = os.environ["FIX"]
MUTATE = os.environ.get("MUTATE", "")

spec = importlib.util.spec_from_file_location(
    "portal_matrix_plan", os.path.join(HERE, "portal-matrix-plan.py"))
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)

OK = [0]
BAD = [0]


def check(description, condition, saw):
    if condition:
        OK[0] += 1
        print("  PASS %s" % description)
    else:
        BAD[0] += 1
        print("  FAIL %s\n       saw: %s" % (description, saw))


# --- the deliberate breakages -------------------------------------------
if MUTATE == "orientation":
    plan.allows_axis = lambda orientation, axis: (
        axis != "Y" if orientation == "horizontal" else True)
elif MUTATE == "twin-offset":
    _real = plan.build_bay

    def _narrow(*args, **kwargs):
        bay, problem = _real(*args, **kwargs)
        if bay is None:
            return bay, problem
        # Slide the neighbour in until its ignition sweep can reach the first
        # frame's opening — the failure "very close" invites.
        shift = bay["twin"]["offset"] - 1
        for group in ("interior", "ring"):
            for cell in bay["twin"][group]:
                cell[0] -= shift
        bay["twin"]["offset"] = 1
        bay["twin"]["use"][0] -= shift
        bay["twin"]["centreColumn"] = plan.centre_column(bay["twin"]["interior"])
        return bay, problem
    plan.build_bay = _narrow
elif MUTATE == "igniter":
    plan.resolve_igniter = lambda item, mode: (mode, item, "present", True, None)
elif MUTATE == "centre":
    plan.centre_column = lambda cells: (
        None if not cells else
        [int(round(sum(c[0] for c in cells) / len(cells))),
         int(round(sum(c[2] for c in cells) / len(cells)))])
elif MUTATE:
    print("unknown mutation: %s" % MUTATE)
    sys.exit(2)

# --- orientation, straight off PortalDefinition.allowsAxis ---------------
check('"horizontal" allows the Y axis',
      plan.allows_axis("horizontal", "Y"), plan.allows_axis("horizontal", "Y"))
check('"horizontal" REFUSES a vertical frame — it is a Y-plane constraint, '
      'not a ban on lying flat',
      not plan.allows_axis("horizontal", "X"), plan.allows_axis("horizontal", "X"))
check('"vertical" refuses Y and allows X',
      not plan.allows_axis("vertical", "Y") and plan.allows_axis("vertical", "X"),
      (plan.allows_axis("vertical", "Y"), plan.allows_axis("vertical", "X")))
check('"vertical_z" locks one axis',
      plan.allows_axis("vertical_z", "Z") and not plan.allows_axis("vertical_z", "X"),
      plan.allows_axis("vertical_z", "X"))
check("an unknown orientation behaves as any, never as none",
      plan.allows_axis("sideways", "X") and plan.allows_axis("sideways", "Y"),
      "sideways")
check("a door shape implies vertical without an orientation field",
      plan.effective_orientation({"shape": "door"}) == "vertical",
      plan.effective_orientation({"shape": "door"}))
check("an explicit orientation beats the shape's implication",
      plan.effective_orientation({"shape": "door", "orientation": "any"}) == "any",
      plan.effective_orientation({"shape": "door", "orientation": "any"}))

# --- the igniter contract, the one place it is decided -------------------
cases = [
    ("auto", "minecraft:flint_and_steel", "damaged", "present", True),
    ("auto", "minecraft:diamond", "untouched", "present", True),
    ("consumed", "minecraft:diamond", "consumed", "absent", True),
    ("consumed", "minecraft:flint_and_steel", "consumed", "absent", True),
    ("untouched", "minecraft:flint_and_steel", "untouched", "present", True),
    ("damaged", "minecraft:diamond", "damaged", None, False),
]
for mode, item, want_expectation, want_verdict, want_assertable in cases:
    expectation, predicate, verdict, assertable, why = plan.resolve_igniter(item, mode)
    check("igniter %s under --igniter-expectation %s is '%s' (%s)"
          % (item.split(":")[1], mode, want_expectation, want_verdict or "not assertable"),
          expectation == want_expectation and verdict == want_verdict
          and assertable == want_assertable,
          (expectation, predicate, verdict, assertable, why))
check("a damaged expectation carries damage=1 in its predicate",
      plan.resolve_igniter("minecraft:flint_and_steel", "auto")[1]
      == "minecraft:flint_and_steel[minecraft:damage=1]",
      plan.resolve_igniter("minecraft:flint_and_steel", "auto")[1])
check("an untouched damageable igniter is asserted at damage=0, not merely present",
      plan.resolve_igniter("minecraft:flint_and_steel", "untouched")[1]
      == "minecraft:flint_and_steel[minecraft:damage=0]",
      plan.resolve_igniter("minecraft:flint_and_steel", "untouched")[1])
try:
    plan.resolve_igniter("minecraft:banana", "auto")
    check("an unknown igniter is a hard error, never an assumed-safe default",
          False, "no exception")
except KeyError:
    check("an unknown igniter is a hard error, never an assumed-safe default", True, "KeyError")

# --- the two columns every link turns on ---------------------------------
interior = [[228, 130, 297], [228, 131, 297], [228, 132, 297],
            [229, 130, 297], [229, 131, 297], [229, 132, 297]]
check("centreColumn truncates the way PortalBreakLink does (228, not 229)",
      plan.centre_column(interior) == [228, 297], plan.centre_column(interior))
# x0 odd puts the average on a .5, where Python's round() goes to the EVEN
# neighbour and Java's `/` goes down. Java is the one the mod runs.
odd = [[229, 130, 297], [230, 130, 297]]
check("an average landing on .5 truncates DOWN, never to the even neighbour",
      plan.centre_column(odd) == [229, 297], plan.centre_column(odd))
# The whole room stands at negative coordinates, and this is where Python's //
# (which floors) and Java's / (which truncates toward zero) part company.
negative = [[-6798, 40, -6000], [-6797, 40, -6000],
            [-6798, 41, -6000], [-6797, 41, -6000],
            [-6798, 42, -6000], [-6797, 42, -6000]]
check("a negative column truncates TOWARD ZERO, the way Java does, not down",
      plan.centre_column(negative) == [-6797, -6000], plan.centre_column(negative))
check("java_int_div matches Java on both signs",
      (plan.java_int_div(-40785, 6), plan.java_int_div(40785, 6),
       plan.java_int_div(-7, 2)) == (-6797, 6797, -3),
      (plan.java_int_div(-40785, 6), plan.java_int_div(-7, 2)))
check("destColumn divides by scale and rounds",
      plan.dest_column([228, 297], 4.0) == [57, 74],
      plan.dest_column([228, 297], 4.0))
check("destColumn on a negative column still rounds toward the nearest",
      plan.dest_column([-6798, -6000], 2.0) == [-3399, -3000],
      plan.dest_column([-6798, -6000], 2.0))

# --- frame geometry -------------------------------------------------------
inter, ring = plan.vertical_frame(10, 40, -20, 2, 3)
check("a vertical 2x3 opening is six cells", len(inter) == 6, len(inter))
check("its ring is the full border — 14 cells, corners included — and touches "
      "no interior cell",
      len(ring) == 14 and not (set(map(tuple, ring)) & set(map(tuple, inter))),
      (len(ring), set(map(tuple, ring)) & set(map(tuple, inter))))
check("every interior cell has only ring or interior beside it in the plane",
      all(tuple(c) in set(map(tuple, ring)) or tuple(c) in set(map(tuple, inter))
          for cell in inter
          for c in ([cell[0] + 1, cell[1], cell[2]], [cell[0] - 1, cell[1], cell[2]],
                    [cell[0], cell[1] + 1, cell[2]], [cell[0], cell[1] - 1, cell[2]])),
      "a gap in the ring")
hinter, hring = plan.horizontal_frame(10, 40, -20, 3, 3)
check("a horizontal 3x3 opening lies in one Y layer",
      len(hinter) == 9 and {c[1] for c in hinter} == {40}, hinter[:2])
check("its ring lies in the same layer and encloses it",
      len(hring) == 16 and {c[1] for c in hring} == {40}, len(hring))

# --- the whole plan over the fixtures -------------------------------------
def build(argv):
    out = subprocess.run(
        [sys.executable, os.path.join(HERE, "portal-matrix-plan.py"),
         "--dimensions", FIX, "--compare-dimensions", FIX] + argv,
        capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit("planner failed: %s" % out.stderr)
    return json.loads(out.stdout)


# The planner is a module here for the mutations to bite, and a subprocess for
# the end-to-end shape; run the in-process path for anything a mutation touches.
def build_inprocess(argv):
    import io
    held, sys.stdout = sys.stdout, io.StringIO()
    try:
        plan.main(["--dimensions", FIX, "--compare-dimensions", FIX] + argv)
        return json.loads(sys.stdout.getvalue())
    finally:
        sys.stdout = held


p = build_inprocess([])
bays = {b["id"]: b for b in p["bays"]}
skipped = {s["slug"]: s["reason"] for s in p["skipped"]}

check("every fixture dimension is either a bay or a skip with a reason",
      len(set(b["slug"] for b in p["bays"]) | set(skipped)) == 12,
      sorted(set(b["slug"] for b in p["bays"]) | set(skipped)))
check("a dimension with no portal block is skipped, not silently dropped",
      "no portal" in skipped.get("noportal", ""), skipped.get("noportal"))
check("a vanillaManaged dimension is skipped: vanilla owns its ignition",
      "vanillaManaged" in skipped.get("vanillaish", ""), skipped.get("vanillaish"))
check("a tag-only frame with no framePlaceBlock is skipped, because nothing "
      "can build it",
      "tag" in skipped.get("tagframe", ""), skipped.get("tagframe"))
check("an igniter the durability table has never seen stops the bay",
      "durability table" in skipped.get("strangeigniter", ""),
      skipped.get("strangeigniter"))
check("a colour-group frame resolves to a concrete placeable block",
      bays["colourframe_x"]["frameBlock"] == "minecraft:red_wool",
      bays.get("colourframe_x", {}).get("frameBlock"))
check("an orientation:horizontal dimension gets a Y bay and no X bay",
      "flatonly_y" in bays and "flatonly_x" not in bays, sorted(bays))
check("an end_exit dimension gets a Y bay: PortalShape forces the plane",
      "endexit_y" in bays and "endexit_x" not in bays, sorted(bays))
check("a door dimension gets an X bay and never a Y one",
      "doorish_x" in bays and "doorish_y" not in bays, sorted(bays))
check("a door bay's opening is 1x2, the only geometry PortalShape.isDoor accepts",
      len(bays["doorish_x"]["primary"]["interior"]) == 2,
      len(bays["doorish_x"]["primary"]["interior"]))
check("--horizontal forced adds no second matrix",
      not any(b["horizontal"] and b["orientation"] == "any" for b in p["bays"]),
      [b["id"] for b in p["bays"] if b["horizontal"]])
p_all = build_inprocess(["--horizontal", "all"])
check("--horizontal all repeats every dimension that allows the Y axis",
      len(p_all["bays"]) > len(p["bays"])
      and "standard_y" in [b["id"] for b in p_all["bays"]],
      [b["id"] for b in p_all["bays"]])
check("--horizontal all still refuses a door dimension a Y bay",
      "doorish_y" not in [b["id"] for b in p_all["bays"]],
      [b["id"] for b in p_all["bays"]])
p_none = build_inprocess(["--horizontal", "none"])
check("--horizontal none skips the Y-only dimensions WITH a reason",
      all(b["id"] != "flatonly_y" for b in p_none["bays"])
      and any(s["slug"] == "flatonly" and "horizontal none" in s["reason"]
              for s in p_none["skipped"]),
      [s for s in p_none["skipped"] if s["slug"] == "flatonly"])

# --- expectations derived from the mod's own code -------------------------
check("an anchor dimension does not break symmetrically",
      bays["anchored_x"]["breaksSymmetrically"] is False
      and bays["anchored_x"]["relightExpectation"] == "linked-to-old",
      (bays["anchored_x"]["breaksSymmetrically"],
       bays["anchored_x"]["relightExpectation"]))
check("a plain dimension breaks at both ends, so a re-light builds a new arrival",
      bays["standard_x"]["breaksSymmetrically"] is True
      and bays["standard_x"]["relightExpectation"] == "new-arrival",
      bays["standard_x"]["relightExpectation"])
check("an anchor dimension's scaled column is not border-checked, because the "
      "anchor path never divides by scale",
      bays["anchored_x"]["borderApplies"] is False and bays["anchored_x"]["borderOk"],
      (bays["anchored_x"]["borderApplies"], bays["anchored_x"]["borderNote"]))
check("a non-anchor dimension whose arrival lands outside its own player "
      "border is flagged",
      bays["tooborderly_x"]["borderOk"] is False,
      bays["tooborderly_x"]["borderNote"])
check("an aura that places lava or lights fires is flagged hazardous",
      plan.aura_is_hazardous({"aura": {"fluids": ["minecraft:lava"]}})
      and plan.aura_is_hazardous({"aura": {"fireChance": 0.05}})
      and not plan.aura_is_hazardous({"aura": {"subsume": "everything"}}),
      "hazard detection")

# --- the room layout ------------------------------------------------------
ordered = p["bays"]
for i, b in enumerate(ordered):
    x1, y1, z1, x2, y2, z2 = b["room"]["box"]
    r = b["auraRadius"]
    for who in ("primary", "twin"):
        cx = b[who]["centreColumn"][0]
        check("%s's %s aura (radius %d) stays inside its own bay"
              % (b["id"], who, r),
              x1 <= cx - r and cx + r <= x2, (cx, r, x1, x2))
    if i + 1 < len(ordered):
        nx1 = ordered[i + 1]["room"]["box"][0]
        check("%s's room ends before %s's begins" % (b["id"], ordered[i + 1]["id"]),
              x2 + p["options"]["bayGap"] < nx1, (x2, nx1))
    # IgnitionScan sweeps a 7x7x7 box round the click; the other frame's
    # opening must be outside it, or one ignition finds the wrong opening.
    gap_a = min(c[0] for c in b["twin"]["interior"]) - b["primary"]["use"][0]
    gap_b = b["twin"]["use"][0] - max(c[0] for c in b["primary"]["interior"])
    check("%s's two frames are outside each other's 7x7x7 ignition sweep" % b["id"],
          gap_a > 3 and gap_b > 3, (gap_a, gap_b))
    check("%s's neighbour is inside findRegisteredPortalNear's radius, so the "
          "proximity step has something to prove" % b["id"],
          b["twin"]["linkable"] and b["twin"]["destDelta"] <= 5,
          (b["twin"]["destDelta"], b["twin"]["linkNote"]))
check("a wide aura buys a wider bay, rather than overlapping its neighbour",
      (bays["wideaura_x"]["room"]["box"][3] - bays["wideaura_x"]["room"]["box"][0])
      > (bays["standard_x"]["room"]["box"][3] - bays["standard_x"]["room"]["box"][0]),
      (bays["wideaura_x"]["room"]["box"], bays["standard_x"]["room"]["box"]))
check("no frame is built in the sky: every opening sits on the carved floor",
      all(min(c[1] for c in b["primary"]["interior"]) == b["room"]["floorY"]
          for b in ordered),
      [(b["id"], min(c[1] for c in b["primary"]["interior"]), b["room"]["floorY"])
       for b in ordered][:3])
check("the ignition click is never made from inside the opening",
      all(b["primary"]["use"][:3] not in b["primary"]["interior"] for b in ordered),
      [b["id"] for b in ordered if b["primary"]["use"][:3] in b["primary"]["interior"]])
check("the block the break step removes is a ring block, not an opening cell",
      all(b["primary"]["breakCell"] in b["primary"]["ring"]
          and b["primary"]["breakCell"] not in b["primary"]["interior"]
          for b in ordered),
      [b["id"] for b in ordered if b["primary"]["breakCell"] not in b["primary"]["ring"]])
check("the break block belongs to one frame only",
      all(b["primary"]["breakCell"] not in b["twin"]["ring"] for b in ordered),
      [b["id"] for b in ordered if b["primary"]["breakCell"] in b["twin"]["ring"]])

# --- filters record what they removed -------------------------------------
p_only = build_inprocess(["--only", "standard"])
check("--only keeps one bay and records every other as skipped",
      len(p_only["bays"]) == 1
      and any("--only" in s["reason"] for s in p_only["skipped"]),
      (len(p_only["bays"]), [s["reason"] for s in p_only["skipped"]][:2]))
p_limit = build_inprocess(["--limit", "2"])
check("--limit records the bays it dropped rather than shrinking the denominator",
      p_limit["counts"]["bays"] == 2
      and any("--limit" in s["reason"] for s in p_limit["skipped"]),
      p_limit["counts"])

# --- drift ----------------------------------------------------------------
check("no drift is reported when both config directories are the same",
      p["source"]["portalDrift"] == [], p["source"]["portalDrift"])
shipped = build(["--compare-dimensions",
                 os.path.join(os.path.dirname(os.path.dirname(HERE)),
                              "config", "custom-dimensions", "dimensions")])
check("drift against a different config directory is reported, not hidden",
      isinstance(shipped["source"]["portalDrift"], list)
      and len(shipped["source"]["portalDrift"]) >= 1,
      shipped["source"]["portalDrift"][:5])

print("\npython cases: %d passed, %d failed" % (OK[0], BAD[0]))
sys.exit(1 if BAD[0] else 0)
PYTHON
PY_RC=$?
cat "$PY_OUT"
if [ "$PY_RC" -eq 0 ]; then
  self_pass "the planner's cases all passed (see $PY_OUT)"
else
  self_fail "the planner has failing cases — see $PY_OUT"
fi
if [ -n "$MUTATE" ]; then
  printf '\n\033[36mMUTATION\033[0m %s — the run above is EXPECTED to be red.\n' "$MUTATE"
  if [ "$PY_RC" -ne 0 ]; then
    printf 'A case caught it. That is the self-test working.\n'
  else
    printf '\033[31mNOTHING CAUGHT IT.\033[0m The mutation is a gap in the self-test.\n'
  fi
fi

# ===========================================================================
say "The driver's helpers — a fixture server, a stub RCON, no client"
# ===========================================================================
FXS="$RUN_DIR/state.json"
cat > "$FXS" <<'JSON'
{"stackVersion": "0.0.0-local", "kind": "e2e-state",
 "generatedAt": "2026-09-04T10:00:00.000000Z",
 "server": {"tick": 100, "tps": 20, "modVersion": "0.0.0-local"},
 "players": [
  {"name": "selftest", "online": true, "uuid": "0a1b", "dimension": "adventure:the_crucible",
   "pos": [57, 74, 74], "blockPos": [57, 74, 74], "yaw": 180, "pitch": 0,
   "onGround": true, "mainHandItem": "minecraft:diamond", "portalCooldown": 0}],
 "dimensions": [
  {"id": "adventure:the_crucible", "managed": true, "loadedAtTick": {"absent": "no tick"}},
  {"id": "minecraft:overworld", "managed": false, "loadedAtTick": {"absent": "no tick"}}],
 "zoneScope": "every world loaded right now",
 "zones": [
  {"kind": "source", "world": "minecraft:overworld", "targetWorld": "adventure:the_crucible",
   "axis": "X", "resident": true, "frameStands": true,
   "interior": [[228, 130, 297], [229, 130, 297]]},
  {"kind": "arrival", "world": "adventure:the_crucible", "targetWorld": "minecraft:overworld",
   "axis": "X", "resident": true, "frameStands": true,
   "interior": [[57, 74, 74], [58, 74, 74]]},
  {"kind": "arrival", "world": "minecraft:overworld", "targetWorld": "adventure:the_crucible",
   "axis": "X", "resident": true, "frameStands": true, "interior": [[300, 70, 300]]}]}
JSON
# shellcheck disable=SC2034  # read by lib.sh's state helpers
STATE_FILE="$FXS"

say "A zone is found by a cell of its own interior, and by the world it stands in"
expect "an arrival is found by a cell of its own interior" ok "1" \
  assert_json "the crucible arrival" "$FXS" \
  "[$(arrival_zone_select adventure:the_crucible 57 74 74)] | length" 1
# The return arrival standing in the overworld carries the same targetWorld as
# the source zone beside it, so counting by targetWorld counts two things.
expect "counting arrivals by targetWorld alone finds the return arrival too" ok "1" \
  assert_json "arrivals naming the crucible" "$FXS" \
  '[.zones[] | select(.kind=="arrival" and .targetWorld=="adventure:the_crucible")] | length' 1
expect "keyed on the world it STANDS in, the crucible holds exactly one arrival" ok "1" \
  assert_json "arrivals in the crucible" "$FXS" \
  '[.zones[] | select(.kind=="arrival" and .world=="adventure:the_crucible")] | length' 1
expect "a source zone's own cell is not an arrival" fail "0" \
  assert_json "the source cell" "$FXS" \
  "[$(arrival_zone_select adventure:the_crucible 228 130 297)] | length" 1

say "An unloaded destination is not an unlink"
expect_rc "a loaded world reads 1" 0 test "$(world_loaded adventure:the_crucible)" = "1"
expect_rc "a world nobody has opened reads 0, and the caller must say so" 0 \
  test "$(world_loaded adventure:the_boneyard)" = "0"
expect_rc "the crucible holds one arrival zone" 0 \
  test "$(arrival_zone_count adventure:the_crucible)" = "1"

say "The reuse box — the whole proximity assertion turns on these numbers"
expect_rc "the same cell is inside it" 0 within_reuse_box 57 74 74 57 74 74
expect_rc "5 blocks out horizontally is still the same arrival" 0 \
  within_reuse_box 62 74 74 57 74 74
expect_rc "6 blocks out is a different one" 1 within_reuse_box 63 74 74 57 74 74
expect_rc "16 blocks up is still the same arrival" 0 within_reuse_box 57 90 74 57 74 74
expect_rc "17 up is not" 1 within_reuse_box 57 91 74 57 74 74
expect_rc "a sentence where a coordinate should be is refused, never compared as 0" 2 \
  within_reuse_box "No entity was found" 74 74 57 74 74
expect_rc "the return landed on the source column" 0 near_column 229 298 228 297 4
expect_rc "and a return five blocks off did not" 1 near_column 234 297 228 297 4
expect_rc "a missing coordinate is refused rather than passing as zero" 2 \
  near_column "" 297 228 297 4

say "assert_items — passed, failed, and an answer that is neither"
# The stub answers on the predicate, so each branch of the real helper is driven
# without a server. An unrecognised answer is the case that matters: a
# component predicate this game does not parse must never read as "absent".
# shellcheck disable=SC2329  # stub shadows the real rcon for this fixture
rcon() {
  case "$1" in
    *"minecraft:diamond]"*|*"minecraft:banana"*)
      printf 'Unknown item tag or item %s' "$1" ;;
    *"minecraft:diamond"*) printf 'Test passed' ;;
    *"minecraft:flint_and_steel[minecraft:damage=1]"*) printf 'Test passed' ;;
    *"minecraft:flint_and_steel"*) printf 'Test failed' ;;
    *) printf 'Test failed' ;;
  esac
}
expect "an igniter still in hand passes an untouched expectation" ok "Test passed" \
  assert_items "igniter present" minecraft:diamond present
expect "an igniter that is gone passes a consumed expectation" ok "Test failed" \
  assert_items "igniter consumed" minecraft:flint_and_steel absent
expect "an igniter still in hand FAILS a consumed expectation" fail "Test passed" \
  assert_items "igniter consumed" minecraft:diamond absent
expect "a damaged igniter passes its damage=1 predicate" ok "Test passed" \
  assert_items "igniter damaged" "minecraft:flint_and_steel[minecraft:damage=1]" present
expect "a predicate the game cannot parse is its own failure, never a verdict" fail \
  "did not answer with a verdict" \
  assert_items "igniter untouched" minecraft:banana present

say "place_cells — every cell verified, and both counts printed"
CELLS="$RUN_DIR/cells.txt"
printf '10 40 -20\n11 40 -20\n12 40 -20\n' > "$CELLS"
# shellcheck disable=SC2329  # stub shadows the real rcon for this fixture
rcon() {
  case "$1" in
    *"if block 11 40 -20"*) printf 'Test failed' ;;
    *"if block"*) printf 'Test passed' ;;
    *) printf '' ;;
  esac
}
expect "one wrong cell out of three fails, and names the first" fail \
  "1 wrong out of 3" place_cells "the ring" "$CELLS" minecraft:stone
# shellcheck disable=SC2329  # stub shadows the real rcon for this fixture
rcon() { case "$1" in *"if block"*) printf 'Test passed' ;; *) printf '' ;; esac; }
expect "three of three verified passes, with the denominator in the message" ok \
  "3 placed and verified" place_cells "the ring" "$CELLS" minecraft:stone
: > "$RUN_DIR/empty.txt"
expect "an empty cell list is a failure, not a clean sweep of nothing" fail \
  "cell list was empty" place_cells "the ring" "$RUN_DIR/empty.txt" minecraft:stone
expect "a cell list that was never written is a failure" fail "no such file" \
  place_cells "the ring" "$RUN_DIR/never-written.txt" minecraft:stone

say "wait_for_dimension — the crossing is read from the server, and a cap ends it"
FAKE_DIM="minecraft:overworld"
# shellcheck disable=SC2329  # stub shadows the real state_refresh for this fixture
state_refresh() { return 0; }
# shellcheck disable=SC2329  # stub shadows the real player_dimension for this fixture
player_dimension() { printf '%s' "$FAKE_DIM"; }
expect_rc "a player already there returns at once" 0 \
  wait_for_dimension minecraft:overworld 6
expect_rc "a crossing that never happens gives up rather than hanging" 1 \
  wait_for_dimension adventure:the_crucible 6
case "$DIM_NOTE" in
  *"still in minecraft:overworld"*)
    self_pass "and says which world the player was actually in" ;;
  *) self_fail "wrong note for a crossing that never happened: $DIM_NOTE" ;;
esac
# shellcheck disable=SC2329  # stub shadows the real state_refresh for this fixture
state_refresh() { STATE_REASON="the server would not answer"; return 1; }
expect_rc "a server that stops answering ends the wait, it does not spin" 1 \
  wait_for_dimension adventure:the_crucible 6
case "$DIM_NOTE" in
  *"would not write e2e-state"*)
    self_pass "and the reason is the server's, not a timeout it never had" ;;
  *) self_fail "wrong note for an unreadable server: $DIM_NOTE" ;;
esac

say "wait_for_state — settles on a value, and reports the one it saw"
# shellcheck disable=SC2329  # stub shadows the real state_refresh for this fixture
state_refresh() { return 0; }
# shellcheck disable=SC2329  # stub shadows the real state_read for this fixture
state_read() { printf '3'; }
expect_rc "a value that never arrives gives up" 1 wait_for_state '.anything' 0 4
expect_rc "and SETTLED carries what was there instead" 0 test "$SETTLED" = "3"
# shellcheck disable=SC2329  # stub shadows the real state_read for this fixture
state_read() { printf '0'; }
expect_rc "a value that is already right returns at once" 0 wait_for_state '.anything' 0 4

say "links_records — a missing file is 0, not a crash"
expect_rc "no portal_links.json yet reads 0" 0 test "$(links_records)" = "0"
mkdir -p "$CONSUMER_DIR/data/config"
printf '[{"x":1},{"x":2},{"x":3}]\n' > "$CONSUMER_DIR/data/config/portal_links.json"
expect_rc "three records read 3" 0 test "$(links_records)" = "3"

# ---------------------------------------------------------------------------
# The counters lib.sh accumulated belong to the assertions under test. The
# run's verdict is this file's own.
# shellcheck disable=SC2034  # both are read by lib.sh's EXIT trap
PASSES=$SELF_OK
# shellcheck disable=SC2034
FAILURES=$SELF_BAD
printf '\nself-test cases: %d passed, %d failed\n' "$SELF_OK" "$SELF_BAD"
finish
