#!/usr/bin/env python3
"""extract-registries.py — the biome, biome-tag, structure and structure-set
registries as a RUNNING server holds them.

Triggers `/customdim catalogue` over RCON in the consumer's mc container, then
copies the artefact the mod wrote into the platform repo.

  <consumer>/.seed-rolling/catalogue/registries.json
      -> config/custom-dimensions/extractors/registries.json

This is the only source that can answer a TAG question. Convention tags
(`c:*`) are populated by the Fabric Convention Tags API at runtime and appear
in no mod's data/ directory, so extract-biomes.py and extract-structure-sets.py
— which read jars — cannot resolve them and must not be used to. Most of the
pack's structures gate on `#c:is_overworld`.

Output: config/custom-dimensions/extractors/registries.json

Usage:
  ./scripts/extract-registries.py [consumer_dir] [--no-trigger] [--check]

  --no-trigger  copy the artefact already on disk instead of running the
                command (use when mc is not running locally)
  --check       exit 1 if the committed file differs, instead of rewriting it

Gotchas: - The dump describes the mods installed on THAT server. Run it
           against a consumer carrying the platform mod list, or the file
           records a smaller pack than the platform ships.
         - `stackVersion` reads `0.0.0-local` for a locally built jar. That
           records which jar produced the file, not a defect.
         - Reflects the running registries, so a mod pin bump needs a restart
           before a re-run means anything.

Template-only (platform development); not in the bundle MANIFEST.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import consumer_dir  # noqa: E402

PLATFORM_DIR = Path(__file__).resolve().parent.parent
OUTPUT = PLATFORM_DIR / "config" / "custom-dimensions" / "extractors" / "registries.json"
ARTEFACT = Path(".seed-rolling") / "catalogue" / "registries.json"


def trigger(container="mc"):
    """Run the command; a failure here is reported, never swallowed."""
    result = subprocess.run(
        ["docker", "exec", "-i", container, "rcon-cli", "customdim catalogue"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        sys.exit(f"customdim catalogue failed: {result.stderr.strip() or result.stdout.strip()}")
    print(result.stdout.strip())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("consumer", nargs="?")
    parser.add_argument("--no-trigger", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = consumer_dir(args.consumer) / ARTEFACT
    if not args.no_trigger:
        trigger()
    if not source.is_file():
        sys.exit(f"No artefact at {source} — is mc running with this jar?")

    body = source.read_text()
    data = json.loads(body)
    counts = data["counts"]

    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text() != body:
            sys.exit(f"{OUTPUT} is stale — re-run {Path(sys.argv[0]).name}")
        print(f"{OUTPUT.name} is current")
        return

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(body)
    print(f"Wrote {OUTPUT}")
    for key in ("biomes", "biomeTags", "structures", "structureSets"):
        print(f"  {key}: {counts[key]}")
    print(f"  #c:is_overworld resolves to {len(data['biomeTags'].get('c:is_overworld', []))} biomes")


if __name__ == "__main__":
    main()
