#!/usr/bin/env python3
"""The Minecraft version this checkout or bundle targets, for Python scripts.

Context: .minecraft-version (one line, beside scripts/) selects the version;
scripts/minecraft-versions.tsv maps it to the Java release, the itzg image tag
and the game versions a pin may target. scripts/lib.sh carries the same rules
for bash (mc_version, mc_version_field, mc_mod_list, mc_manifest).

Usage:
  python3 scripts/minecraft_version.py                 # the version
  python3 scripts/minecraft_version.py java|image|accepted [--version V]
  python3 scripts/minecraft_version.py mod-list|manifest [--root DIR]

Gotchas:
  - The file wins over MC_VERSION in the environment; MC_VERSION is only the
    fallback when the file is missing (a container that ships scripts alone).
  - mod-list and manifest return the shared file when no
    <stem>-<version><ext> exists, so 1.21.1 keeps config/modrinth-mods.txt.
"""
import argparse
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERSION_FILE = HERE.parent / ".minecraft-version"
TABLE = HERE / "minecraft-versions.tsv"
FIELDS = ("java", "image", "accepted")


def current(version_file=VERSION_FILE):
    """The selected Minecraft version."""
    path = Path(version_file)
    if path.is_file():
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line
    env = os.environ.get("MC_VERSION", "").strip()
    if env:
        return env
    raise SystemExit(f"no Minecraft version: {path} is missing and MC_VERSION is unset")


def info(version=None, table=TABLE):
    """{'java': '21', 'image': '...', 'accepted': ('1.21.1', '1.21')} for the version."""
    version = version or current()
    for line in Path(table).read_text().splitlines():
        cols = line.split()
        if cols and not cols[0].startswith("#") and cols[0] == version:
            return {"java": cols[1], "image": cols[2], "accepted": tuple(cols[3].split(","))}
    raise SystemExit(f"Minecraft {version} has no row in {Path(table).name}")


def versioned_path(base, stem, ext, version=None):
    """base/<stem>-<version><ext> when it exists, else base/<stem><ext>."""
    version = version or current()
    candidate = Path(base) / f"{stem}-{version}{ext}"
    return candidate if candidate.is_file() else Path(base) / f"{stem}{ext}"


def mod_list(root=HERE.parent, version=None):
    """The server mod list for the version."""
    return versioned_path(Path(root) / "config", "modrinth-mods", ".txt", version)


def manifest(root=HERE.parent, version=None):
    """The pack manifest for the version."""
    return versioned_path(Path(root) / "modpack", "adventure", ".mrpack.json", version)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("field", nargs="?", choices=FIELDS + ("mod-list", "manifest"))
    ap.add_argument("--version")
    ap.add_argument("--root", default=str(HERE.parent))
    args = ap.parse_args(argv)
    version = args.version or current()
    if args.field is None:
        print(version)
    elif args.field == "mod-list":
        print(mod_list(args.root, version))
    elif args.field == "manifest":
        print(manifest(args.root, version))
    elif args.field == "accepted":
        print(",".join(info(version)["accepted"]))
    else:
        print(info(version)[args.field])
    return 0


if __name__ == "__main__":
    sys.exit(main())
