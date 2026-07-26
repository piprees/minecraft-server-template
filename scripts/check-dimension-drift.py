#!/usr/bin/env python3
# =============================================================================
# check-dimension-drift.py — is this world still the world its config describes?
# =============================================================================
#
# Purpose:
#   Worldgen is creation-time-only (TROUBLESHOOTING.md#d2). A dimension's
#   generator is serialised into level.dat when the world is created, and
#   `registerDimensions` skips keys already in the registry — so editing
#   `type`, `biomes`, `noiseSettings`, `seed` or any other creation-time field
#   changes the CONFIG and changes NOTHING about the running world.
#
#   The mod already records what each dimension was created with, in
#   `config/custom-dimensions-fingerprints.json`. This compares that against
#   the configs on disk and tells you which dimensions are lying.
#
# Why this exists:
#   2026-07-27 — a structure-placement regression suite "failed" because a
#   jungle dimension had igloos in its structure pool. The biome filter was
#   correct; the WORLD was three configs old, so the filter was filtering a
#   biome source nobody had asked for in weeks. 63 of 78 dimensions were
#   reusing stale generators and nothing anywhere said so. Hours went into
#   the wrong suspect. This check answers it in a second, offline.
#
# Context:
#   Reads files only — no Docker, no RCON, no running server. Safe to run in
#   CI against committed fixtures, and safe to run against production data.
#
# Usage:
#   ./scripts/check-dimension-drift.py --data <consumer>/data
#   ./scripts/check-dimension-drift.py \
#       --fingerprints <path>/custom-dimensions-fingerprints.json \
#       --config <path>/custom-dimensions
#   ./scripts/check-dimension-drift.py --data <consumer>/data --json
#
# Exit codes:
#   0  every created dimension matches its config
#   1  at least one dimension drifted (its world no longer matches config)
#   2  bad invocation / missing inputs
#
# Gotchas:
#   - A dimension in the config with NO fingerprint entry has simply never
#     been created. That is not drift; it is reported as `uncreated` and does
#     not fail the run.
#   - A fingerprint entry with no config file is an ORPHAN — the config was
#     deleted but the world still exists. The mod unloads orphans in a
#     managed namespace, but the level.dat entry survives until scrubbed
#     (TROUBLESHOOTING.md#d3), so this is reported and DOES fail.
#   - Only creation-time fields are compared. Portal blocks, difficulty,
#     borders and seedRoll are re-read every boot and are never drift.
#   - The comparison mirrors the mod's own fingerprint writer: values are
#     compared as the strings it stores, with "null" meaning absent.
# =============================================================================
import argparse
import json
import sys
from pathlib import Path

# The fields the mod stamps at creation time. Anything not in this list is
# re-read every boot and cannot drift.
CREATION_TIME_FIELDS = (
    "type",
    "biomes",
    "noiseSettings",
    "biomeParameters",
    "layers",
    "flatBiome",
    "checkerboardScale",
    "biomePatches",
    "settingsOverrides",
    # `seed` is creation-time too, but it is the seed roller's job to change
    # it and a re-roll is a deliberate act — reported separately, see below.
)

NULL = "null"


# MIRRORED from DimensionConfig's *Fingerprint() methods and
# DimensionFingerprints.fields(). Each of these is a bespoke string form, NOT
# JSON — writing `{"seaLevel":90}` where the mod writes `seaLevel=90` makes
# every dimension with that field look permanently drifted. Change together.
_SETTINGS_ORDER = ("seaLevel", "defaultBlock", "defaultFluid", "disableMobGeneration")


def _plain(value):
    """Java's String.valueOf for the scalars the mod stamps."""
    if value is None:
        return NULL
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _biomes(config):
    """DimensionConfig.getBiome() — the ordered list, comma-joined."""
    raw = config.get("biomes")
    if raw:
        parts = []
        for entry in raw:
            if isinstance(entry, str) and entry.strip():
                parts.append(entry.strip())
            elif isinstance(entry, dict) and entry.get("id"):
                parts.append(str(entry["id"]).strip())
        return ",".join(parts) if parts else NULL
    return _plain(config.get("biome"))


def _layers(config):
    """getLayersFingerprint: `<height>*<block>` joined by commas."""
    layers = config.get("layers")
    if not layers:
        return NULL
    return ",".join(f"{l.get('height')}*{l.get('block')}"
                    for l in layers if isinstance(l, dict))


def _settings_overrides(config):
    """getSettingsOverridesFingerprint: `key=value` pairs, fixed field order."""
    so = config.get("settingsOverrides")
    if not isinstance(so, dict):
        return NULL
    parts = [f"{k}={so[k]}" for k in _SETTINGS_ORDER if so.get(k) is not None]
    return ",".join(parts) if parts else NULL


def _biome_parameters(config):
    """getBiomeParametersFingerprint: `<biome>=<raw json object>` pairs."""
    params = {}
    for entry in config.get("biomes") or []:
        if isinstance(entry, dict) and entry.get("id") and isinstance(entry.get("parameters"), dict):
            params[str(entry["id"])] = entry["parameters"]
    if not params:
        return NULL
    return ",".join(f"{k}={json.dumps(v, separators=(',', ':'))}"
                    for k, v in params.items())


def _biome_patches(config):
    """getBiomePatchesFingerprint: `<biome>@<x>,<z>,<radius>[>replace][!scope]`."""
    patches = config.get("biomePatches")
    if not patches:
        return NULL
    out = []
    for p in patches:
        if not isinstance(p, dict):
            continue
        s = f"{p.get('biome')}@{p.get('x')},{p.get('z')},{p.get('radius')}"
        if p.get("replace"):
            s += f">{p['replace']}"
        if p.get("scope"):
            s += f"!{p['scope']}"
        out.append(s)
    return ";".join(out) if out else NULL


_FIELD_READERS = {
    "biomes": _biomes,
    "layers": _layers,
    "settingsOverrides": _settings_overrides,
    "biomeParameters": _biome_parameters,
    "biomePatches": _biome_patches,
}


def config_value(config, field):
    reader = _FIELD_READERS.get(field)
    if reader is not None:
        return reader(config)
    return _plain(config.get(field))


def load_configs(config_dir):
    """{slug: config}, staged consumer overlay resolved like the mod does."""
    config_dir = Path(config_dir)
    out = {}
    dims = config_dir / "dimensions"
    if dims.is_dir():
        for f in sorted(dims.glob("*.json")):
            try:
                out[f.stem.lower()] = json.loads(f.read_text())
            except json.JSONDecodeError as e:
                print(f"  WARN unparseable {f.name}: {e}", file=sys.stderr)
    overlay = config_dir / "overlay" / "dimensions"
    if overlay.is_dir():
        for f in sorted(overlay.glob("*.json")):
            try:
                over = json.loads(f.read_text())
            except json.JSONDecodeError:
                continue
            slug = f.stem.lower()
            if not over:
                out.pop(slug, None)          # empty {} disables the dimension
            elif isinstance(over.get("overrides"), dict):
                base = dict(out.get(slug) or {})
                base.update(over["overrides"])
                out[slug] = base
            else:
                out[slug] = over
    return out


def compare(fingerprints, configs, check_seed=False):
    drifted, uncreated, orphans, clean = [], [], [], []
    for slug, config in sorted(configs.items()):
        stamped = fingerprints.get(slug)
        if stamped is None:
            uncreated.append(slug)
            continue
        diffs = []
        fields = CREATION_TIME_FIELDS + (("seed",) if check_seed else ())
        for field in fields:
            was = str(stamped.get(field, NULL))
            now = config_value(config, field)
            if was != now:
                diffs.append((field, was, now))
        (drifted if diffs else clean).append((slug, diffs))
    for slug in sorted(fingerprints):
        if slug not in configs:
            orphans.append(slug)
    return drifted, uncreated, orphans, clean


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", help="a consumer's data/ directory (finds both inputs)")
    ap.add_argument("--fingerprints", help="custom-dimensions-fingerprints.json")
    ap.add_argument("--config", help="custom-dimensions config directory")
    ap.add_argument("--check-seed", action="store_true",
                    help="also treat a changed seed as drift (a re-roll is "
                         "normally deliberate, so it is excluded by default)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    if args.data:
        base = Path(args.data) / "config"
        fingerprints_path = args.fingerprints or base / "custom-dimensions-fingerprints.json"
        config_dir = args.config or base / "custom-dimensions"
    else:
        if not (args.fingerprints and args.config):
            ap.error("give --data, or both --fingerprints and --config")
        fingerprints_path = args.fingerprints
        config_dir = args.config

    fingerprints_path = Path(fingerprints_path)
    if not fingerprints_path.exists():
        print(f"no fingerprint file at {fingerprints_path} — no dimension has "
              f"been created yet, nothing to drift")
        return 0
    try:
        fingerprints = json.loads(fingerprints_path.read_text())
    except json.JSONDecodeError as e:
        print(f"FAIL fingerprint file is not valid JSON: {e}")
        return 2
    configs = load_configs(config_dir)
    if not configs:
        print(f"FAIL no dimension configs under {config_dir}")
        return 2

    drifted, uncreated, orphans, clean = compare(
        fingerprints, configs, check_seed=args.check_seed)

    if args.json:
        print(json.dumps({
            "drifted": [{"slug": s, "diffs": [
                {"field": f, "createdWith": w, "configNow": n} for f, w, n in d]}
                for s, d in drifted],
            "uncreated": uncreated,
            "orphans": orphans,
            "clean": [s for s, _ in clean],
        }, indent=2))
        return 1 if (drifted or orphans) else 0

    for slug, diffs in drifted:
        print(f"DRIFTED {slug}")
        for field, was, now in diffs:
            print(f"    {field}:")
            print(f"      created with: {was[:120]}")
            print(f"      config now:   {now[:120]}")
    for slug in orphans:
        print(f"ORPHAN  {slug} — world exists, config gone "
              f"(level.dat scrub needed: TROUBLESHOOTING.md#d3)")

    print(f"\n{len(clean)} match config, {len(drifted)} DRIFTED, "
          f"{len(orphans)} orphaned, {len(uncreated)} not yet created")
    if drifted or orphans:
        print("\nA drifted dimension's world does NOT match its config, and any "
              "test you run against it is measuring an older config.\n"
              "Only a world wipe re-creates it (TROUBLESHOOTING.md#d2).")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
