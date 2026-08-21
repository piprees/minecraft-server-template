#!/usr/bin/env python3
"""Which layer supplies each dimension's effective `spawn`, and what it is.

The mod merges `dimensions/` (platform defaults) with `overlay/dimensions/`
(consumer overrides) at load — `MultiverseConfig.load` ->
`DimensionConfigLoader.loadAllWithSettings(configDir, configDir/overlay)`.
Reading either directory alone says nothing about the effective config.

Mirrors `DimensionConfigLoader.loadDimensions` exactly: whole-line `//` comments
stripped, overlay with a top-level `overrides` deep-merges, overlay without it
replaces, empty `{}` skips the dimension, overlay-only slugs are added, then
`settings.defaults` (borders/difficulty) merges UNDERNEATH.

Usage:
  effective-spawn.py <config-dir> [--out FILE]

  <config-dir> is the directory holding settings.json + dimensions/ + overlay/,
  e.g. ~/Projects/elfydd/data/config/custom-dimensions (what the mod loads) or
  a bundle's config/custom-dimensions merged with the consumer overlay.
"""

import argparse
import json
import sys
from pathlib import Path


def strip_json_comments(text):
    """Whole-line `//` only — the same narrow rule the loader applies."""
    if "//" not in text:
        return text
    out = []
    for line in text.split("\n"):
        out.append("" if line.strip().startswith("//") else line)
    return "\n".join(out)


def read_object(path):
    if not path.is_file():
        return None
    try:
        parsed = json.loads(strip_json_comments(path.read_text()))
    except (OSError, ValueError) as exc:
        print(f"unreadable: {path}: {exc}", file=sys.stderr)
        return None
    return parsed if isinstance(parsed, dict) else None


def deep_merge(base, over):
    result = dict(base)
    for key, value in over.items():
        existing = result.get(key)
        if isinstance(existing, dict) and isinstance(value, dict):
            result[key] = deep_merge(existing, value)
        else:
            result[key] = value
    return result


def read_dimension_files(directory):
    files = {}
    if not directory.is_dir():
        return files
    for path in sorted(directory.glob("*.json")):
        body = read_object(path)
        if body is not None:
            files[path.stem.lower()] = body
    return files


def defaults_for(settings_defaults, dimension):
    base = {}
    for key in ("borders", "difficulty"):
        if isinstance(settings_defaults.get(key), dict):
            base[key] = json.loads(json.dumps(settings_defaults[key]))
    portal = dimension.get("portal")
    if isinstance(portal, dict) and "frameBlock" in settings_defaults \
            and "frameMaterials" not in portal:
        base["portal"] = {"frameBlock": settings_defaults["frameBlock"]}
    return base


def resolve(config_dir):
    """(slug -> merged config, slug -> which layer supplied it)."""
    settings = read_object(config_dir / "settings.json") or {}
    overlay_settings = read_object(config_dir / "overlay" / "settings.json")
    if overlay_settings:
        settings = deep_merge(settings, overlay_settings)
    settings_defaults = settings.get("defaults") or {}

    platform = read_dimension_files(config_dir / "dimensions")
    overlay = read_dimension_files(config_dir / "overlay" / "dimensions")

    resolved, origin = {}, {}
    for slug, body in platform.items():
        over = overlay.get(slug)
        if over is None:
            resolved[slug], origin[slug] = body, "platform"
        elif not over:
            continue                                    # empty {} disables it
        elif isinstance(over.get("overrides"), dict):
            resolved[slug] = deep_merge(body, over["overrides"])
            origin[slug] = "platform+overrides"
        else:
            resolved[slug], origin[slug] = over, "overlay-replaced"
    for slug, over in overlay.items():
        if slug in platform or not over:
            continue
        body = over["overrides"] if isinstance(over.get("overrides"), dict) else over
        resolved[slug], origin[slug] = body, "consumer-added"

    merged = {}
    for slug, body in resolved.items():
        merged[slug] = deep_merge(defaults_for(settings_defaults, body), body)
    return merged, origin, platform, overlay


def spawn_of(body):
    spawn = body.get("spawn")
    if isinstance(spawn, list) and len(spawn) >= 3:
        try:
            return [int(spawn[0]), int(spawn[1]), int(spawn[2])]
        except (TypeError, ValueError):
            return None
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config_dir", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    merged, origin, platform, overlay = resolve(args.config_dir)

    rows = []
    for slug in sorted(merged):
        effective = spawn_of(merged[slug])
        base = spawn_of(platform.get(slug, {}))
        over_body = overlay.get(slug) or {}
        over_source = over_body.get("overrides") if isinstance(
            over_body.get("overrides"), dict) else over_body
        over = spawn_of(over_source or {})
        # Which layer put the effective value there.
        if effective is None:
            supplied = "none"
        elif over is not None and over == effective:
            supplied = "overlay"
        elif base is not None and base == effective:
            supplied = "platform"
        else:
            supplied = "merge"
        rows.append({
            "dimension": slug,
            "resolution": origin[slug],
            "effectiveSpawn": effective,
            "platformLayerSpawn": base,
            "overlayLayerSpawn": over,
            "suppliedBy": supplied,
            "nonOrigin": effective is not None and (effective[0] != 0 or effective[2] != 0),
        })

    report = {
        "configDir": str(args.config_dir),
        "dimensionsLoaded": len(rows),
        "platformLayerFiles": len(platform),
        "overlayLayerFiles": len(overlay),
        "declareASpawn": sum(1 for r in rows if r["effectiveSpawn"] is not None),
        "declareANonOriginSpawn": sum(1 for r in rows if r["nonOrigin"]),
        "nonOriginFromBaseLayerAlone":
            sum(1 for r in rows if r["platformLayerSpawn"] is not None
                and (r["platformLayerSpawn"][0] != 0 or r["platformLayerSpawn"][2] != 0)),
        "suppliedBy": {
            layer: sum(1 for r in rows if r["suppliedBy"] == layer)
            for layer in ("overlay", "platform", "merge", "none")
        },
        "dimensions": rows,
    }

    text = json.dumps(report, indent=2) + "\n"
    if args.out:
        args.out.write_text(text)
        print(f"{args.out}: {report['declareANonOriginSpawn']} of "
              f"{report['dimensionsLoaded']} declare a non-origin spawn "
              f"({report['nonOriginFromBaseLayerAlone']} measured off the base layer alone)")
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
