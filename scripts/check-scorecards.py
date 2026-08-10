#!/usr/bin/env python3
"""Assert over a bank of scorecard artefacts, with no server running.

Purpose:  Phase 5's gate is a claim about a DISTRIBUTION, not about one seed.
          A scorer that puts every dimension at 95-99% has stopped ranking and
          is indistinguishable, seed to seed, from no scorer at all. This reads
          the scorecards `customdim score` wrote and says whether that happened.
Context:  Scorecards are written to
          <consumer>/data/config/custom-dimensions/scores/<ns>__<slug>__<seed>.json
          by the mod. This never contacts a server and never parses RCON output.
Usage:    ./scripts/check-scorecards.py [scores_dir] [--json]
Gotchas:  A REJECTED or INVALID_CONFIG card has NO percentage — that is the
          point of keeping the three verdicts apart, so those cards are counted
          and excluded from the distribution rather than read as zeroes.
"""

import json
import os
import sys


def load(scores_dir):
    cards = []
    for name in sorted(os.listdir(scores_dir)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(scores_dir, name)) as fh:
            card = json.load(fh)
        card["_file"] = os.path.join(scores_dir, name)
        cards.append(card)
    return cards


def percentage(card):
    if card.get("verdict") != "SCORED":
        return None
    ceiling = card.get("ceiling") or 0.0
    if ceiling <= 0:
        return None
    return 100.0 * card.get("achieved", 0.0) / ceiling


def histogram(values, width=10):
    buckets = [0] * (100 // width + 1)
    for v in values:
        buckets[min(int(v // width), len(buckets) - 1)] += 1
    lines = []
    for i, n in enumerate(buckets):
        lo = i * width
        label = "100" if lo >= 100 else "%3d-%3d" % (lo, lo + width - 1)
        lines.append("  %7s %s %d" % (label, "#" * n, n))
    return lines


def dead_criteria(cards):
    """Criteria that returned the same value everywhere rank nothing."""
    seen = {}
    for card in cards:
        for group in card.get("groups", {}).values():
            for entry in group:
                key = entry["id"]
                value = entry.get("value")
                slot = seen.setdefault(key, {"values": set(), "outcomes": set()})
                slot["outcomes"].add(entry["outcome"])
                if value is not None:
                    slot["values"].add(round(float(value), 6))
    dead = []
    for key, slot in sorted(seen.items()):
        graded = slot["values"]
        if len(graded) == 1 and slot["outcomes"] <= {"score", "unmeasured"}:
            dead.append((key, next(iter(graded))))
        elif not graded and slot["outcomes"] <= {"pass"}:
            dead.append((key, "always pass"))
    return dead


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    scores_dir = args[0] if args else "data/config/custom-dimensions/scores"
    if not os.path.isdir(scores_dir):
        print("no scores directory at %s" % scores_dir, file=sys.stderr)
        return 2

    cards = load(scores_dir)
    if not cards:
        print("no scorecards in %s" % scores_dir, file=sys.stderr)
        return 2

    scored, rejected, invalid = [], [], []
    for card in cards:
        verdict = card.get("verdict")
        if verdict == "SCORED":
            scored.append(card)
        elif verdict == "REJECTED":
            rejected.append(card)
        else:
            invalid.append(card)

    pcts = [p for p in (percentage(c) for c in scored) if p is not None]
    if len(pcts) != len(scored):
        print("FAIL: a SCORED card carried no percentage", file=sys.stderr)
        return 1
    failures = []

    over = [(c["dimension"], p) for c, p in zip(scored, pcts)
            if p > 100.0 + 1e-9]
    if over:
        failures.append("percentages above 100: %s" % over)

    mean = sum(pcts) / len(pcts) if pcts else 0.0
    var = sum((p - mean) ** 2 for p in pcts) / len(pcts) if pcts else 0.0
    sd = var ** 0.5
    spread = (max(pcts) - min(pcts)) if pcts else 0.0

    if pcts and min(pcts) >= 95.0:
        failures.append(
            "every scored dimension is at 95%% or above (min %.1f) — the "
            "ceiling is too generous and the score has stopped ranking" % min(pcts))

    dead = dead_criteria(cards)
    if dead:
        failures.append("criteria that never vary (dead weight): %s" % dead)

    print("scorecards      %d in %s" % (len(cards), scores_dir))
    print("  SCORED        %d" % len(scored))
    print("  REJECTED      %d" % len(rejected))
    print("  INVALID_CONFIG %d" % len(invalid))
    if pcts:
        print("percentage      min %.1f  max %.1f  mean %.1f  sd %.1f  spread %.1f"
              % (min(pcts), max(pcts), mean, sd, spread))
        print("histogram (deciles):")
        for line in histogram(pcts):
            print(line)

    if "--json" in sys.argv:
        print(json.dumps({
            "n": len(cards), "scored": len(scored), "rejected": len(rejected),
            "invalid": len(invalid),
            "min": min(pcts) if pcts else None, "max": max(pcts) if pcts else None,
            "mean": mean, "sd": sd, "spread": spread,
            "dead_criteria": dead,
            "percentages": {c["dimension"]: p for c, p in zip(scored, pcts)},
        }, indent=2, sort_keys=True))

    for failure in failures:
        print("FAIL: %s" % failure, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
