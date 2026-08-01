#!/usr/bin/env python3
# =============================================================================
# check-suppress-list.py — does every suppressed id actually exist?
# =============================================================================
#
# Purpose:
#   settings.json's `"suppress": {"structures": [...], "biomes": [...]}`
#   removes structure SET ids from every dimension's noise pools and
#   pass-throughs, and biome ids from every world's biome source (base
#   worlds included). An id that matches nothing (typo, mod removed, wrong
#   namespace) suppresses nothing — the server WARNs once at boot and
#   carries on. This checker catches the same mistake offline, before a
#   boot, by cross-referencing every suppress list on disk against the
#   extracted catalogues (extractors/structures.json + biomes.json).
#
# Context:
#   Reads files only — no Docker, no RCON, no running server. Run by
#   `./dev verify`. The catalogue ships in the stack bundle
#   (config/custom-dimensions/extractors/structures.json), so consumers have
#   it at .stack/current/stack/config/custom-dimensions/.
#
# Usage:
#   ./scripts/check-suppress-list.py --data <consumer>/data \
#       --config <custom-dimensions config dir> \
#       [--overlay <consumer overlay custom-dimensions dir>] [--json]
#
#   Every settings.json findable from those roots is checked independently:
#   the platform file, the config dir's staged overlay/, the consumer repo's
#   overlay source, and the server's staged copies under data/config/. A bad
#   id anywhere is worth flagging — merge order never fixes a typo.
#
# Exit codes:
#   0  every suppressed id resolves to a known structure set (or none listed)
#   1  at least one suppressed id matches no known structure set
#   2  bad invocation / no extractors catalogue found
#
# Gotchas:
#   - Suppress entries are structure SET ids (e.g. `mvs:barn`), not structure
#     short names. The catalogue keys are set ids; for most mods set id ==
#     structure id, which hides the distinction until it bites.
#   - Matching is case-insensitive — the mod lowercases the exclude union.
#   - The boot-time WARN checks the LIVE registry; this checks the extracted
#     catalogue. A freshly added mod not yet re-extracted can false-flag —
#     re-run the extractor before trusting a failure after a mod change.
# =============================================================================
import argparse
import json
import sys
from pathlib import Path


def strip_json_comments(text):
    """JSONC, readers-first: a line whose first non-blank characters are //
    is blanked. The SAME narrow rule (whole-line only, no trailing
    comments, no lenient parsing) lives in
    DimensionConfigLoader.stripJsonComments (Java) and
    scripts/seed/dimension_profiles.strip_json_comments — change together."""
    if "//" not in text:
        return text
    import re
    return re.sub(r"^[ \t]*//.*$", "", text, flags=re.M)


def suppress_ids(settings_file):
    """{"structures": [...], "biomes": [...]} from one settings.json, or
    None if the file is absent/unparseable/has no suppress lists. Ids are
    stripped, not lowercased — the report shows what the author wrote."""
    try:
        data = json.loads(strip_json_comments(settings_file.read_text()))
    except (OSError, json.JSONDecodeError):
        return None
    suppress = data.get("suppress") or {}
    out = {}
    for kind in ("structures", "biomes"):
        raw = suppress.get(kind)
        if isinstance(raw, list):
            out[kind] = [str(i).strip() for i in raw if str(i).strip()]
    return out or None


def catalogue_sets(extractors_file):
    """Lowercased structure-set ids from extractors/structures.json."""
    data = json.loads(extractors_file.read_text())
    sets = data.get("structure_sets")
    if not isinstance(sets, dict) or not sets:
        raise ValueError(f"{extractors_file} carries no structure_sets map")
    return {k.lower() for k in sets}


def catalogue_biomes(extractors_dir):
    """Lowercased biome ids from extractors/biomes.json, or None when the
    biome catalogue isn't present (older extractions) — biome checks are
    skipped rather than failed in that case."""
    f = extractors_dir / "biomes.json"
    if not f.exists():
        return None
    try:
        data = json.loads(f.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    biomes = data.get("biomes")
    if not isinstance(biomes, dict) or not biomes:
        return None
    return {k.lower() for k in biomes}


def settings_sources(data_dir, config_dir, overlay_dir):
    """Every settings.json worth checking, labelled. Order is platform →
    overlay source → staged server copies."""
    out = []
    if config_dir:
        out.append(("config", Path(config_dir) / "settings.json"))
        out.append(("config overlay", Path(config_dir) / "overlay" / "settings.json"))
    if overlay_dir:
        out.append(("consumer overlay", Path(overlay_dir) / "settings.json"))
    if data_dir:
        staged = Path(data_dir) / "config" / "custom-dimensions"
        out.append(("staged", staged / "settings.json"))
        out.append(("staged overlay", staged / "overlay" / "settings.json"))
    # The same physical file can be reachable twice (platform checkouts pass
    # --data and --config into one tree) — report each file once.
    seen, unique = set(), []
    for label, path in out:
        key = path.resolve()
        if key not in seen:
            seen.add(key)
            unique.append((label, path))
    return unique


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", help="a consumer's data/ directory (staged settings.json copies)")
    ap.add_argument("--config", help="custom-dimensions config directory "
                                     "(platform settings.json + extractors/)")
    ap.add_argument("--overlay", help="consumer overlay custom-dimensions directory")
    ap.add_argument("--extractors", help="explicit path to extractors/structures.json "
                                         "(default: <config>/extractors/structures.json)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    if not (args.data or args.config):
        ap.error("give --data and/or --config")

    extractors = Path(args.extractors) if args.extractors else (
        Path(args.config) / "extractors" / "structures.json" if args.config else None)
    if extractors is None or not extractors.exists():
        print(f"FAIL no structure-set catalogue at "
              f"{extractors or '<unset — give --config or --extractors>'}")
        return 2
    try:
        known = {"structures": catalogue_sets(extractors),
                 "biomes": catalogue_biomes(extractors.parent)}
    except (json.JSONDecodeError, ValueError) as e:
        print(f"FAIL unreadable catalogue: {e}")
        return 2
    kind_noun = {"structures": "structure set", "biomes": "biome"}

    checked, unknown, skipped_kinds = [], [], set()
    for label, path in settings_sources(args.data, args.config, args.overlay):
        lists = suppress_ids(path)
        if lists is None:
            continue
        for kind, ids in lists.items():
            if known[kind] is None:
                skipped_kinds.add(kind)
                continue
            bad = [i for i in ids if i.lower() not in known[kind]]
            checked.append({"source": label, "file": str(path), "kind": kind,
                            "suppressed": ids, "unknown": bad})
            unknown.extend((label, path, kind, i) for i in bad)

    if args.json:
        print(json.dumps({"catalogue": str(extractors),
                          "known": {k: len(v) if v else None for k, v in known.items()},
                          "sources": checked}, indent=2))
        return 1 if unknown else 0

    for kind in sorted(skipped_kinds):
        print(f"  SKIP suppress.{kind} (no {kind}.json catalogue alongside "
              f"{extractors.name})")
    if not checked:
        print(f"no suppress lists in any settings.json "
              f"({len(known['structures'])} sets in catalogue) — nothing to check")
        return 0
    for entry in checked:
        n = len(entry["suppressed"])
        state = "OK" if not entry["unknown"] else f"{len(entry['unknown'])} UNKNOWN"
        print(f"  {entry['source']} {entry['kind']}: {n} suppressed, {state}"
              f"  ({entry['file']})")
    for label, path, kind, bad_id in unknown:
        print(f"UNKNOWN {bad_id!r} ({label}) matches no {kind_noun[kind]} in the "
              f"catalogue — it suppresses nothing")
    if unknown:
        print(f"\n{len(unknown)} unknown id(s). "
              f"Check for typos, or re-run the extractor if mods changed.")
        return 1
    total = sum(len(e["suppressed"]) for e in checked)
    print(f"\nall suppressed ids resolve ({total} across {len(checked)} "
          f"list(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
