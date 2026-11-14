#!/usr/bin/env python3
"""stage-dimensions.py - Stage a consumer's custom-dimensions overlay for the 26.3 mod.

Context: bundle script. deploy.sh (step 8) and dev-up.sh run it after the
platform config is in data/config/. The 26.3 mod reads definitions and
settings.json from config/custom-dimensions/dimensions/ and bridges from
config/custom-dimensions/bridges/, and nothing else; it resolves no overlay.
So the consumer's overlay/config/custom-dimensions/ is resolved here, into
those two folders:

  dimensions/<slug>.json, schema v2 ("schemaVersion": 2)
      replaces the platform definition, or adds a dimension of that slug
  dimensions/<slug>.json, {"overrides": {...}}
      merged over the platform definition: objects merge key by key, any
      other value replaces, null removes the key
  dimensions/<slug>.json, {}
      removes a platform lazy dimension (refused for a base world)
  dimensions/settings.json
      the same three forms over the platform settings.json ({} refused)
  bridges/<name>.json
      added beside the platform bridges, or replaces the one of that name

A schema v1 file (no "schemaVersion", no "overrides") is not staged: the 26.3
mod refuses a v1 definition at start, so the platform definition stays and a
WARN names the file. 1.21.1 seed-viewer files (*_thumb.json, images) and
any other path are ignored with one line each.

Usage:
  python3 scripts/stage-dimensions.py --platform <bundle config/custom-dimensions> \
      --overlay <overlay/config/custom-dimensions> --out <data/config/custom-dimensions>
  --check   resolve and report, write nothing

Output: one line per staged, removed, restored or skipped file, then a count.
Exit 0 when everything staged or was skipped with a warning; 1 on a usage
error or an unreadable platform file an override needs.

Gotchas:
  - out/.overlay-staged.json records what the last run wrote. A file the
    overlay no longer carries goes back to the platform copy, or is deleted
    when the platform has none, so removing an overlay file takes effect.
  - Whole-line // comments are allowed in definitions (as the mod reads
    them); a merged file is written without them.
  - The base worlds are overworld, the_nether, the_end and the_aviary (the
    mod's LevelIndex.baseWorldKeys); their definitions cannot be removed.
  - Standard library only; Python 3.9+.
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

BASE_WORLDS = ("overworld", "the_nether", "the_end", "the_aviary")
STATE = ".overlay-staged.json"
IGNORED_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".md", ".txt")


def read_json(path):
    """A definition's JSON, with whole-line // comments dropped."""
    text = path.read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if not line.lstrip().startswith("//")]
    return json.loads("\n".join(lines))


def merge(base, overrides):
    """base with overrides applied: objects merge, null removes, anything else replaces."""
    out = dict(base)
    for key, value in overrides.items():
        if value is None:
            out.pop(key, None)
        elif isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = merge(out[key], value)
        else:
            out[key] = value
    return out


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def kind_of(data):
    """'v2', 'overrides', 'remove' or 'v1' for an overlay definition's JSON."""
    if not isinstance(data, dict):
        return "v1"
    if data == {}:
        return "remove"
    keys = set(data) - {"_comment"}
    if keys == {"overrides"} and isinstance(data["overrides"], dict):
        return "overrides"
    if data.get("schemaVersion") == 2:
        return "v2"
    return "v1"


class Stager:
    def __init__(self, platform, overlay, out, check=False, log=print):
        self.platform, self.overlay, self.out = Path(platform), Path(overlay), Path(out)
        self.check, self.log = check, log
        self.written = {}
        self.warnings = 0

    def warn(self, text):
        self.warnings += 1
        self.log(f"  WARN {text}")

    def write(self, rel, data=None, source=None):
        dest = self.out / rel
        if not self.check:
            dest.parent.mkdir(parents=True, exist_ok=True)
            if source is not None:
                shutil.copyfile(source, dest)
            else:
                dest.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            self.written[rel] = digest(dest)
        else:
            self.written[rel] = "check"

    def definition(self, path):
        rel = f"dimensions/{path.name}"
        slug = path.stem
        try:
            data = read_json(path)
        except (OSError, ValueError) as exc:
            self.warn(f"{rel}: not readable JSON ({exc}); the platform definition stays")
            return
        kind = kind_of(data)
        platform_file = self.platform / rel
        if kind == "v1":
            self.warn(f"{rel}: a 1.21.1 (schema v1) definition; the 26.3 mod refuses it, so the platform "
                      f"definition stays. Rewrite it as a schema v2 definition or as "
                      f'{{"overrides": {{...}}}} over the platform one')
            return
        if kind == "remove":
            if slug in BASE_WORLDS or slug == "settings":
                self.warn(f"{rel}: {{}} cannot remove {slug}; the platform file stays")
                return
            dest = self.out / rel
            if not self.check and dest.is_file():
                dest.unlink()
            self.written[rel] = None
            self.log(f"  removed  {rel}")
            return
        if kind == "overrides":
            if not platform_file.is_file():
                self.warn(f"{rel}: overrides with no platform {rel} to apply them to; skipped")
                return
            try:
                base = read_json(platform_file)
            except (OSError, ValueError) as exc:
                raise SystemExit(f"ERROR: platform {rel} is not readable JSON: {exc}")
            self.write(rel, data=merge(base, data["overrides"]))
            self.log(f"  merged   {rel} ({', '.join(sorted(data['overrides']))})")
            return
        self.write(rel, source=path)
        self.log(f"  {'replaced' if platform_file.is_file() else 'added   '} {rel}")

    def bridge(self, path):
        rel = f"bridges/{path.name}"
        try:
            read_json(path)
        except (OSError, ValueError) as exc:
            self.warn(f"{rel}: not readable JSON ({exc}); skipped")
            return
        self.write(rel, source=path)
        self.log(f"  {'replaced' if (self.platform / rel).is_file() else 'added   '} {rel}")

    def restore(self, previous):
        """Put back what an earlier run staged and this one does not."""
        for rel, wrote in sorted(previous.items()):
            if rel in self.written:
                continue
            dest, platform_file = self.out / rel, self.platform / rel
            if platform_file.is_file():
                if digest(dest) != digest(platform_file):
                    if not self.check:
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(platform_file, dest)
                    self.log(f"  restored {rel} (platform copy)")
            elif dest.is_file() and (wrote is None or digest(dest) == wrote):
                if not self.check:
                    dest.unlink()
                self.log(f"  dropped  {rel} (no longer in the overlay)")

    def run(self):
        state_file = self.out / STATE
        previous = {}
        if state_file.is_file():
            try:
                previous = json.loads(state_file.read_text(encoding="utf-8")).get("files", {})
            except ValueError:
                previous = {}
        ignored = 0
        if self.overlay.is_dir():
            for path in sorted(p for p in self.overlay.rglob("*") if p.is_file()):
                rel = path.relative_to(self.overlay).as_posix()
                parts = rel.split("/")
                if len(parts) == 2 and parts[0] == "dimensions" and path.suffix == ".json" \
                        and not path.name.endswith("_thumb.json"):
                    self.definition(path)
                elif len(parts) == 2 and parts[0] == "bridges" and path.suffix == ".json":
                    self.bridge(path)
                elif path.name.endswith("_thumb.json") or path.suffix.lower() in IGNORED_SUFFIXES:
                    ignored += 1
                else:
                    self.warn(f"{rel}: the 26.3 mod reads dimensions/ and bridges/ only; ignored")
        if ignored:
            self.log(f"  ignored  {ignored} seed-viewer or documentation file(s) (nothing in 26.3 reads them)")
        self.restore(previous)
        if not self.check:
            self.out.mkdir(parents=True, exist_ok=True)
            if self.written:
                state_file.write_text(json.dumps({"files": self.written}, indent=2, sort_keys=True) + "\n",
                                      encoding="utf-8")
            elif state_file.exists():
                state_file.unlink()
        self.log(f"  custom-dimensions overlay: {len(self.written)} staged, {self.warnings} warning(s)")
        return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--platform", required=True)
    ap.add_argument("--overlay", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    if not Path(args.platform).is_dir():
        print(f"ERROR: {args.platform} is not a directory", file=sys.stderr)
        return 1
    return Stager(args.platform, args.overlay, args.out, check=args.check).run()


if __name__ == "__main__":
    sys.exit(main())
