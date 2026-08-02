#!/usr/bin/env python3
# =============================================================================
# check-portal-integrity.py — assert the invariants portal_links.json must hold
# =============================================================================
#
# Purpose:
#   `config/portal_links.json` is the custom-dimensions mod's largest piece of
#   persisted state — every source zone, every registered arrival, every aura
#   site — and until now NOTHING asserted over it. Every portal regression this
#   platform has had was found by a human in game, or by a Carpet-bot script
#   written from scratch each time.
#
#   The invariants below are all stated in prose in mods/AGENTS.md § Portal
#   system. This turns them into a check that runs offline in a second.
#
# The one that has already cost a production incident:
#   A persisted `definition.frameBlock` must ALWAYS be a plain parseable id and
#   never a `#tag` form. Older jars call Identifier.of() on it in an uncaught
#   world-tick path, so a `#` in a persisted record crash-loops any server
#   running a pre-FrameMatcher build. Accept forms belong in `frameAccepts`.
#   Deploys roll back, so persisted state is a compatibility contract, not
#   just today's schema.
#
# Context:
#   Reads files only — no Docker, no RCON, no running server.
#
# Usage:
#   ./scripts/check-portal-integrity.py --data <consumer>/data
#   ./scripts/check-portal-integrity.py --links <path>/portal_links.json \
#                                       --config <path>/custom-dimensions
#
# Exit codes:
#   0  every invariant holds (including "the file does not exist yet")
#   1  at least one invariant is broken
#   2  bad invocation / unreadable input
#
# Gotchas:
#   - Records are a heterogeneous list discriminated by `recordType`. Legacy
#     return-target records predate the field and have NO recordType at all —
#     they are the `portalWorld`/`targetWorld` shape and must keep working.
#   - Absence of the file is not a failure. A fresh world has no portals until
#     someone lights one.
#   - A zone pointing at a dimension that has left the config is a WARNING,
#     not a failure: the mod reconciles orphans by unloading them, and a
#     consumer may legitimately disable a dimension via an empty overlay.
# =============================================================================
import argparse
import json
import sys
from pathlib import Path

VALID_EXIT_MODES = {"origin", "bed", "worldSpawn"}

# Base worlds are always valid targets even though they have no dimension file.
BASE_WORLDS = {
    "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
    "paradise_lost:paradise_lost",
}


class Report:
    def __init__(self, quiet=False):
        self.passed = 0
        self.failures = []
        self.warnings = []
        self.quiet = quiet

    def check(self, name, ok, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failures.append((name, detail))
            if not self.quiet:
                print(f"FAIL {name}{(' — ' + detail) if detail else ''}")

    def warn(self, name, detail=""):
        self.warnings.append((name, detail))
        if not self.quiet:
            print(f"WARN {name}{(' — ' + detail) if detail else ''}")


def load_config_dimensions(config_dir):
    """Every dimension id the config can produce, plus the base worlds."""
    ids = set(BASE_WORLDS)
    config_dir = Path(config_dir)
    settings = config_dir / "settings.json"
    namespace = "adventure"
    if settings.exists():
        try:
            namespace = json.loads(settings.read_text()).get("namespace", namespace)
        except json.JSONDecodeError:
            pass
    for sub in ("dimensions", "overlay/dimensions"):
        d = config_dir / sub
        if not d.is_dir():
            continue
        for f in d.glob("*.json"):
            try:
                body = json.loads(f.read_text())
            except json.JSONDecodeError:
                continue
            if body == {}:
                continue          # consumer-disabled
            ids.add(f"{namespace}:{f.stem.lower()}")
            if isinstance(body, dict) and body.get("dimensionId"):
                ids.add(body["dimensionId"])
    return ids


def check_records(records, known_dimensions, report):
    zones = [r for r in records if r.get("recordType") == "source-zone-v1"]
    auras = [r for r in records if r.get("recordType") == "aura-site-v1"]
    targets = [r for r in records if not r.get("recordType")]

    if not report.quiet:
        print(f"{len(zones)} source zone(s), {len(targets)} return target(s), "
              f"{len(auras)} aura site(s)")

    for i, zone in enumerate(zones):
        label = f"zone[{i}] {zone.get('sourceWorld')} -> {zone.get('targetWorld')}"
        definition = zone.get("definition") or {}

        # THE load-bearing one: a persisted frameBlock must be parseable by
        # every jar that might ever read it back, including older ones.
        frame = definition.get("frameBlock")
        report.check(
            f"{label}: frameBlock is a plain id",
            isinstance(frame, str) and frame and not frame.startswith("#"),
            f"got {frame!r} — accept forms belong in frameAccepts, a '#tag' "
            f"here crash-loops a rolled-back jar")

        exit_mode = definition.get("exitMode")
        if exit_mode is not None:
            report.check(f"{label}: exitMode is known",
                         exit_mode in VALID_EXIT_MODES,
                         f"got {exit_mode!r}, expected one of "
                         f"{sorted(VALID_EXIT_MODES)}")

        for field in ("sourceWorld", "targetWorld"):
            value = zone.get(field)
            report.check(f"{label}: {field} is a namespaced id",
                         isinstance(value, str) and ":" in value,
                         f"got {value!r}")
            if isinstance(value, str) and ":" in value and value not in known_dimensions:
                report.warn(f"{label}: {field} {value} is not in the config",
                            "the mod unloads orphans; scrub level.dat to remove "
                            "the world (TROUBLESHOOTING.md#d3)")

        single_use = definition.get("singleUse")
        left = zone.get("singleUseTicksLeft")
        if left is not None:
            report.check(f"{label}: singleUseTicksLeft is armed only when singleUse",
                         left == -1 or bool(single_use),
                         f"countdown {left} on a zone with singleUse={single_use!r}")

    for i, target in enumerate(targets):
        label = f"target[{i}] at {target.get('portalWorld')}"
        report.check(f"{label}: portalWorld is a namespaced id",
                     isinstance(target.get("portalWorld"), str)
                     and ":" in str(target.get("portalWorld")),
                     f"got {target.get('portalWorld')!r}")
        report.check(f"{label}: has a return target world",
                     isinstance(target.get("targetWorld"), str)
                     and ":" in str(target.get("targetWorld")),
                     f"got {target.get('targetWorld')!r}")
        exit_mode = target.get("exitMode")
        if exit_mode is not None:
            report.check(f"{label}: exitMode is known",
                         exit_mode in VALID_EXIT_MODES, f"got {exit_mode!r}")

    for i, aura in enumerate(auras):
        label = f"aura[{i}] in {aura.get('world')}"
        spent = aura.get("budgetSpent")
        report.check(f"{label}: budgetSpent is a non-negative number",
                     isinstance(spent, (int, float)) and spent >= 0,
                     f"got {spent!r}")
        report.check(f"{label}: has an interior",
                     bool(aura.get("interior")), "empty interior")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", help="a consumer's data/ directory (finds both inputs)")
    ap.add_argument("--links", help="portal_links.json")
    ap.add_argument("--config", help="custom-dimensions config directory")
    args = ap.parse_args()

    if args.data:
        base = Path(args.data) / "config"
        links_path = Path(args.links or base / "portal_links.json")
        config_dir = Path(args.config or base / "custom-dimensions")
    else:
        if not (args.links and args.config):
            ap.error("give --data, or both --links and --config")
        links_path = Path(args.links)
        config_dir = Path(args.config)

    if not links_path.exists():
        print(f"no portal state at {links_path} — nothing has been lit yet")
        return 0
    try:
        records = json.loads(links_path.read_text())
    except json.JSONDecodeError as e:
        print(f"FAIL portal_links.json is not valid JSON: {e}")
        return 2
    if not isinstance(records, list):
        print(f"FAIL expected a list of records, got {type(records).__name__}")
        return 2

    known = load_config_dimensions(config_dir)
    report = Report()
    check_records([r for r in records if isinstance(r, dict)], known, report)

    print(f"\n{report.passed} passed, {len(report.failures)} failed, "
          f"{len(report.warnings)} warning(s)")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
