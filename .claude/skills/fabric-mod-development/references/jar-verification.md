---
title: Jar verification
description: The artefact checks that catch an empty or unremapped Fabric jar, the mods/local-mods.manifest format, and exactly what mod-build.yml and release.yml enforce in CI
tags: [remapJar, refmap, dev-jar, mixin, mod-build.yml, release.yml, local-mods.manifest]
---

# Jar verification

Gradle's `BUILD SUCCESSFUL` says nothing about whether `remapJar` produced a real jar. On an incompatible Loom/Gradle pairing it can silently emit a jar containing only a 25-byte `MANIFEST.MF` — no classes, no refmap. This has shipped a production crash loop. Verify the artefact every time, locally and in CI.

## The three checks

Run against `build/libs/<mod>-<version>.jar` — never `build/devlibs/`:

```bash
# 1. Class count must be non-zero and, per CI's floor, at least 10
unzip -l build/libs/<mod>-<version>.jar | grep -c '\.class$'

# 2. The Loom-generated refmap must be present — its exact name is
#    <modid>-refmap.json (e.g. customdimensions-refmap.json)
unzip -l build/libs/<mod>-<version>.jar | grep refmap

# 3. Spot-check that mixin classes carry INTERMEDIARY names, not Yarn names
unzip -p build/libs/<mod>-<version>.jar path/to/SomeMixin.class | strings | grep -m3 'class_'
```

Check 3 is the one that actually distinguishes a correctly remapped jar from a dev jar: a remapped mixin class references target methods/fields by their intermediary names (`class_XXXX`, `method_XXXX`). If you only see Yarn (human-readable) names, you are looking at a dev jar that was copied to the wrong path.

## The dev-jar trap

`./gradlew build` also produces `build/devlibs/<mod>-<version>-dev.jar`. This jar:

- uses Yarn-named mappings (human-readable, matches your source code)
- carries **no refmap**
- only resolves correctly inside a Loom development environment, where Mixin's dev-time transformer can match Yarn names directly

On a real server, every mixin in a dev jar fails with:

```
could not find any targets ... No refMap loaded
```

...and the server crash-loops at boot. **The only jar that is ever safe to ship is `build/libs/<mod>-<version>.jar`.** There is no legitimate reason to copy anything out of `build/devlibs/`.

## `mods/local-mods.manifest`

Drives which Gradle projects get built and shipped, and what CI checks each one against:

```
# bundle_name|gradle_project|loom_refmap
customdimensions.jar|custom-dimensions|customdimensions-refmap.json
```

Fields, pipe-separated:

1. **bundle_name** — the filename the jar is staged as in `dist/local-mods/` and later `stack/local-mods/`, and what `data/mods/` will contain on a running server.
2. **gradle_project** — the directory under `mods/` to `cd` into and `gradle build`.
3. **loom_refmap** — the exact refmap filename `release.yml` greps for inside the built jar.

Adding a new in-house mod means adding a line here — `release.yml` iterates this file, and `build-stack-bundle.sh`'s manifest verification checks the bundle contains every `bundle_name` listed.

## What `mod-build.yml` enforces

Runs on every push/PR touching `mods/**`. Fixed to `mods/custom-dimensions` today (not manifest-driven) — extend this workflow if a second mod is added:

```bash
gradle build   # in mods/custom-dimensions

JAR=$(find build/libs -name 'customdimensions-*.jar' ! -name '*-sources.jar' ! -name '*-dev.jar')
# fails if JAR is empty (::error::No remapped jar found)

unzip -l "$JAR" | grep -q 'customdimensions-refmap.json'
# fails if refmap absent (::error::... is missing customdimensions-refmap.json)

CLASSES=$(unzip -l "$JAR" | grep -c '\.class$' || true)
[[ "$CLASSES" -lt 10 ]] && fail
# ::error::$JAR contains only $CLASSES classes — remapJar output looks empty
```

**10 classes is CI's actual floor**, not just "non-zero" — match it locally so a local pass never surprises CI.

## What `release.yml` enforces

Runs per-mod, driven by `mods/local-mods.manifest`, before the bundle is assembled:

```bash
while IFS='|' read -r JAR_NAME PROJECT REF_MAP; do
  [[ -z "$JAR_NAME" || "$JAR_NAME" == \#* ]] && continue
  (
    cd "mods/$PROJECT"
    gradle build
    JAR=$(find build/libs -name '*.jar' ! -name '*-sources.jar' ! -name '*-dev.jar')
    cp "$JAR" "../../dist/local-mods/$JAR_NAME"
  )
done < mods/local-mods.manifest
```

Then, separately, for every manifest line:

```bash
JAR="dist/local-mods/$JAR_NAME"
unzip -l "$JAR" | grep -q "$REF_MAP"      # exact refmap filename from the manifest's 3rd field
CLASSES=$(unzip -l "$JAR" | grep -c '\.class$' || true)
[[ "$CLASSES" -lt 10 ]] && fail
```

And after `build-stack-bundle.sh` runs, a final check confirms every manifest-listed jar actually made it into the release tarball:

```bash
tar -tzf "dist/stack-${VERSION}.tar.gz" | grep -qx "stack/local-mods/$JAR_NAME"
```

Three separate gates (mod-build.yml on push/PR, release.yml's build step, release.yml's bundle-contents check) exist because each catches a different failure point: a broken build, a broken jar, and a broken packaging step. Don't treat a green `mod-build.yml` run as proof the *release* pipeline will also succeed — they build independently.

## `smoke-test.yml`'s mirror

`smoke-test.yml` (called by `release.yml` as a gate before publishing, see the main SKILL.md) repeats the identical class-count/refmap check when it builds and installs `customdimensions.jar` for the boot test — so a mod that passes `mod-build.yml` but fails the smoke test's install step means something environment-specific (missing dependency mod, wrong MC_VERSION assumption) rather than a build/remap problem.
