#!/usr/bin/env python3
"""check-winner-themes.py - Flag a rolled winner that scores higher by demoting
the biomes its dimension is about.

Context: RollPipeline.promoteBest picks on `percentage`, and nothing in that
score weights a dimension's defining biomes. Measured on the smoke roll:
the_weeping_vault's winner scored 11 points higher while
incendium:weeping_valley - the biome the dimension is named after - fell from
20.0% to 5.6%, and the three vanilla nether biomes grew. That is a better
number and a worse world, and across a whole-pack roll it happens unattended.

The author's own statement of what a dimension is about is its
seedRoll.spawnFilter, so that is what this compares. A biome collapsing from a
real share to nearly nothing is the second signal, independent of the filter.

Both readings come from the candidate cards in the SAME hash directory - never
across hashes, because a card under an older hash was measured against a config
that no longer exists (TROUBLESHOOTING.md#t60, #t62).

Usage:
  scripts/check-winner-themes.py [--consumer PATH] [--platform PATH]
                                 [--filter-drop N] [--biome-drop N] [--json]

  --filter-drop N  flag when aggregate spawn-filter share falls by more than N
                   percentage points (default 5.0)
  --biome-drop N   flag when a single biome above 10% loses more than N of its
                   own share, proportionally (default 0.5, i.e. halved)

Exits 1 if any winner is flagged, so it can gate an export. A flag is a
question for a human, not proof of a bad seed - read the named shares.

Template-only; deliberately NOT in the bundle MANIFEST.
"""
import argparse
import glob
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import consumer_dir  # noqa: E402

DIMS = "config/custom-dimensions/dimensions"


def newest_hash_dir(consumer, slug):
    """The most recent bank directory holding this dimension. One hash dir
    holds one dimension - InputHash appends dimension=<name>."""
    pattern = str(consumer / ".seed-rolling" / "candidates" / "*")
    best, best_m = None, -1
    for h in glob.glob(pattern):
        d = os.path.join(h, "adventure__" + slug)
        if os.path.isdir(d):
            m = os.path.getmtime(h)
            if m > best_m:
                best, best_m = h, m
    return best


def card(consumer, slug, seed):
    h = newest_hash_dir(consumer, slug)
    if not h:
        return None
    p = os.path.join(h, "adventure__" + slug, f"{seed}.json")
    if not os.path.isfile(p):
        return None
    try:
        return json.load(open(p))
    except (OSError, ValueError):
        return None


def biomes_of(c):
    return ((c or {}).get("facts", {}) or {}).get("biomes", {}) or {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--consumer", default=None)
    ap.add_argument("--platform", default=None)
    ap.add_argument("--filter-drop", type=float, default=5.0)
    ap.add_argument("--biome-drop", type=float, default=0.5)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    consumer = Path(args.consumer) if args.consumer else Path(consumer_dir())
    platform = Path(args.platform) if args.platform else Path(__file__).resolve().parent.parent
    overlay = consumer / "overlay" / DIMS

    if not overlay.is_dir():
        print(f"FAIL  no overlay dimensions directory at {overlay}")
        return 1

    flagged, clean, unreadable = [], [], []

    for path in sorted(overlay.glob("*.json")):
        if path.name.endswith("_thumb.json"):
            continue
        slug = path.stem
        base_path = platform / DIMS / f"{slug}.json"
        if not base_path.is_file():
            unreadable.append((slug, "no platform config"))
            continue
        try:
            over = json.loads(path.read_text()).get("overrides") or {}
            base = json.loads(base_path.read_text())
        except (OSError, ValueError) as exc:
            unreadable.append((slug, str(exc)))
            continue

        win_seed, inc_seed = over.get("seed"), base.get("seed")
        if win_seed is None or inc_seed is None or win_seed == inc_seed:
            continue  # nothing promoted, or the incumbent won

        wc, ic = card(consumer, slug, win_seed), card(consumer, slug, inc_seed)
        if wc is None or ic is None:
            unreadable.append((slug, "winner or incumbent card missing in the newest hash dir"))
            continue

        ws, isv = biomes_of(wc).get("shares") or {}, biomes_of(ic).get("shares") or {}
        want = ((base.get("seedRoll") or {}).get("spawnFilter")) or []

        wf = sum(ws.get(b, 0.0) for b in want) * 100
        inf = sum(isv.get(b, 0.0) for b in want) * 100
        filter_delta = wf - inf

        collapsed = []
        for b, before in isv.items():
            if before * 100 < 10.0:
                continue
            after = ws.get(b, 0.0)
            if after < before * (1.0 - args.biome_drop):
                collapsed.append((b, before * 100, after * 100))

        # The spawn filter is the author's own statement of what the dimension
        # is about, so it triggers. A biome shrinking because a filter biome
        # grew into its ground is the intended outcome, not a fault - those are
        # reported as context. Without a filter there is nothing else to go on,
        # so a collapse triggers instead.
        reasons, context = [], []
        if want and filter_delta < -args.filter_drop:
            reasons.append(f"spawn-filter share {inf:.2f}% -> {wf:.2f}% ({filter_delta:+.2f}pp)")
        for b, a, z in sorted(collapsed, key=lambda r: r[1] - r[2], reverse=True):
            line = f"{b} {a:.2f}% -> {z:.2f}%"
            (reasons if not want else context).append(line)
        if reasons and context:
            reasons.extend(f"(also) {c}" for c in context)

        rec = {
            "dimension": slug,
            "incumbent": inc_seed,
            "winner": win_seed,
            "filter_before": round(inf, 3),
            "filter_after": round(wf, 3),
            "reasons": reasons,
        }
        (flagged if reasons else clean).append(rec)

    total = len(flagged) + len(clean) + len(unreadable)
    if args.json:
        json.dump({"examined": total, "flagged": flagged, "clean": len(clean),
                   "unreadable": unreadable, "exit": 1 if flagged else 0},
                  sys.stdout, indent=2)
        print()
        return 1 if flagged else 0

    print(f"Examined {total} promoted winner(s) against their incumbents")
    print(f"  clean          {len(clean)} of {total}")
    print(f"  flagged        {len(flagged)} of {total}")
    print(f"  unreadable     {len(unreadable)} of {total}")
    for slug, err in unreadable:
        print(f"    {slug}: {err}")

    if flagged:
        print("\nFLAGGED — a higher score that demotes what the dimension is about:")
        for r in flagged:
            print(f"\n  {r['dimension']}  (incumbent {r['incumbent']} -> winner {r['winner']})")
            for reason in r["reasons"]:
                print(f"      {reason}")
        print("\n  Read the named shares. Keep the incumbent, or re-roll that dimension.")
    else:
        print("\nPASS  no winner demotes its dimension's defining biomes")
    return 1 if flagged else 0


if __name__ == "__main__":
    sys.exit(main())
