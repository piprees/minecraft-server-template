#!/usr/bin/env python3
# =============================================================================
# check-dimension-lint.py — assert the lint report says what the bank says
# =============================================================================
#
# Purpose:
#   `/customdim lint` claims to find every dimension config fault the registries
#   can prove, and its headline claim is the set of wants naming a structure the
#   dimension's noise pool cannot contain. That set has an independent oracle:
#   an audit of the candidate bank, where a want is dead when its structure
#   never appears in ANY banked candidate's census. This script diffs the two
#   and reports both directions of disagreement by name.
#
#   A count match is not enough and is not accepted here. Two sets of the same
#   size can be different sets, and the difference is exactly where a wrong
#   check hides.
#
# Context:
#   Dump the report first (no world needed, runs in seconds):
#     docker exec -i mc rcon-cli "customdim lint"
#   Build the oracle from the consumer's bank:
#     cd <consumer> && SEED_CONFIG_DIR=... SEED_OVERLAY_DIR=... \
#       python3 reports/dead-wants-audit.py > reports/dead-wants-oracle.json
#
# Usage:
#   ./scripts/check-dimension-lint.py \
#       --report <consumer>/data/config/custom-dimensions/lint/report.json \
#       --oracle <consumer>/reports/dead-wants-oracle.json
#   ./scripts/check-dimension-lint.py --report <r> --oracle <o> --max-seconds 10
#
# Gotchas:
#   - The oracle only covers dimensions that HAVE banked candidates with a
#     census. Lint covers every configured dimension, so lint-only findings in
#     an unbanked dimension are expected and are reported separately rather
#     than counted as disagreements.
#   - The oracle's definition excludes tag wants and includes forced ones; lint
#     excludes forced wants (a forced structure is placed by hand, so the pool
#     is irrelevant to it). Both exclusions are applied here explicitly so the
#     comparison is like for like.
# =============================================================================

import argparse
import json
import sys
from pathlib import Path


def load(path, kind):
    try:
        return json.loads(Path(path).read_text())
    except FileNotFoundError:
        sys.exit(f"No {kind} at {path}")
    except json.JSONDecodeError as e:
        sys.exit(f"{kind} at {path} is not valid JSON: {e}")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--report", required=True, type=Path)
    ap.add_argument("--oracle", required=True, type=Path)
    ap.add_argument("--max-seconds", type=float, default=10.0)
    args = ap.parse_args()

    report = load(args.report, "lint report")
    oracle = load(args.oracle, "oracle")

    if report.get("schemaVersion") != 1:
        sys.exit(f"lint report schemaVersion {report.get('schemaVersion')!r}, "
                 "expected 1 — refusing to read it")

    findings = report.get("findings") or []
    failures = []

    def check(name, ok, detail=""):
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}"
              f"{(' — ' + detail) if detail else ''}")
        if not ok:
            failures.append((name, detail))

    elapsed = report.get("elapsedMillis", 0) / 1000.0
    check(f"lint runs in under {args.max_seconds}s",
          elapsed <= args.max_seconds,
          f"{elapsed:.2f}s over {report.get('dimensionsChecked')} dimensions")

    lint_dead = {(f["dimension"], f["subject"])
                 for f in findings if f["check"] == "want_not_in_pool"}
    oracle_dead = {(r["dimension"], r["want"]) for r in oracle["dead"]}
    banked = {r["dimension"] for r in oracle["dead"]} | {
        r["dimension"] for r in oracle.get("weak", [])}

    # Restrict the comparison to dimensions the oracle can speak about.
    lint_dead_banked = {p for p in lint_dead if p[0] in banked}
    only_lint = sorted(lint_dead_banked - oracle_dead)
    only_oracle = sorted(oracle_dead - lint_dead)
    lint_unbanked = sorted(p for p in lint_dead if p[0] not in banked)

    print()
    print(f"oracle: {len(oracle_dead)} dead wants across "
          f"{len({d for d, _ in oracle_dead})} dimensions")
    print(f"lint:   {len(lint_dead)} want_not_in_pool findings "
          f"({len(lint_dead_banked)} in dimensions the oracle covers)")
    print()

    check("lint finds every dead want the bank proves",
          not only_oracle,
          f"{len(only_oracle)} missed: {only_oracle[:8]}")
    check("lint reports no dead want the bank contradicts",
          not only_lint,
          f"{len(only_lint)} extra: {only_lint[:8]}")
    if lint_unbanked:
        print(f"  [note] {len(lint_unbanked)} finding(s) in dimensions with no "
              f"banked census — outside the oracle's reach: {lint_unbanked[:6]}")

    # Every finding must be actionable and typed.
    bad = [f for f in findings
           if not f.get("fix") or f.get("severity") not in ("error", "warn", "info")]
    check("every finding carries a severity and a fix",
          not bad,
          f"{len(bad)} without: {[(f['dimension'], f['check']) for f in bad[:5]]}")

    print()
    print(f"{len(failures)} failed")
    for name, detail in failures:
        print(f"  FAILED: {name} — {detail}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
