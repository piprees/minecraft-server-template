---
title: Bundle contents
description: What build-stack-bundle.sh packs into the stack bundle tarball, the MANIFEST array, local mod jar validation, and exactly what lint.yml's manifest check does and doesn't catch.
tags: [bundle, manifest, build-stack-bundle, local-mods, lint, tarball]
---

# Bundle contents

`scripts/build-stack-bundle.sh` assembles the stack bundle tarball that `release.yml` attaches to every published release. It is a **template-maintenance script** — it does not ship inside the bundle it builds.

## Usage

```bash
./scripts/build-stack-bundle.sh v1.2.3
# or
VERSION=v1.2.3 ./scripts/build-stack-bundle.sh
```

Output: `dist/stack-v1.2.3.tar.gz` + `dist/stack-v1.2.3.tar.gz.sha256`.

## What goes in

1. **Every file in the `MANIFEST` array** (hard-coded list in the script) — compose files (`docker-compose.yml`, `docker-compose.local.yml`), `.env.example`, every bundle-category script (`scripts/deploy.sh`, `scripts/harden.sh`, `scripts/doctor.sh`, the whole `scripts/seed/` toolchain, etc.), and the consumer scaffold entry points (`examples/consumer/dev`, `examples/consumer/ops`) plus the files `dev update` syncs into consumer repos (`.env.example`, `.gitignore`, `AGENTS.md`, `commands.json`, `README.md`, `.github/workflows/deploy.yml`, `.github/workflows/update.yml`). The script aborts before building anything if any listed file is missing from the checkout.
2. **All of `config/`** via `rsync`, excluding `1password.env` (secrets) and `modrinth-mods.pinned.txt` (platform-internal pin cache) — this is how mod configs, `messages.json`, nginx templates, and datapacks reach consumers.
3. **In-house mod jars**, validated against `mods/local-mods.manifest` (format `JAR_NAME|PROJECT|REF_MAP` per line): every declared jar must exist in `dist/local-mods/` (built by `release.yml` before this script runs) or the build aborts; every jar actually present in `dist/local-mods/` must also be declared in the manifest, or the build aborts (undeclared-jar check). The manifest must not be empty. This is a two-way check — missing OR undeclared both fail loudly, which is deliberate: the manifest is the single source of truth for what local-mods content reaches consumers.

The tarball itself is built with GNU tar flags for reproducibility (`--sort=name --owner=0 --group=0 --mtime="2024-01-01 00:00:00"`) when `gtar`/GNU tar is available, falling back to plain `tar` with a warning if not (matters for local testing on macOS without `coreutils`/`gnu-tar` installed — CI runners always have GNU tar).

## The `MANIFEST` array trap

**A new bundle script that isn't added to `MANIFEST` in `scripts/build-stack-bundle.sh` is silently never shipped to consumers.** There's no error at build time for a script that exists in the repo but isn't listed — it just doesn't make it into the tarball. The comment in the script itself calls this out: `examples/consumer` copy loops elsewhere are guarded with `[[ -f "$src" ]]` and skip missing sources quietly — this is exactly how one consumer (`elfydd`) ran a year-old `deploy.yml` for months without anyone noticing.

## What `lint.yml`'s `bundle-manifest` job actually checks

This CI job (`.github/workflows/lint.yml`, job `bundle-manifest`) is a **partial** safety net, not an exhaustive one. It checks three specific patterns:

1. **Explicit `ops` script mappings** — every `script_file="X"` assignment in `examples/consumer/ops`'s case statement must resolve to `scripts/X.sh` being present in the `MANIFEST` (extracted via `grep -oE "scripts/[^ \")]+\.sh"` against `build-stack-bundle.sh`).
2. **Default-name `ops` commands** — every entry in `ops`'s `ALLOWED_COMMANDS` array that has *no* explicit case mapping is assumed to map to `scripts/<command>.sh` by convention, and that file must be in the manifest. (`sync` is hard-coded as an exception — it's handled inline in `ops`, not delegated to a script.)
3. **Scripts referenced by other bundle scripts** — any `$SCRIPT_DIR/<name>.sh` reference inside a script that's already in the manifest must itself be in the manifest.

**What this does NOT catch:** a script added to the repo that isn't reached through `ops` at all (e.g. something invoked directly by `deploy.sh` via a full path, a script only ever run manually, or referenced some other way than `$SCRIPT_DIR/`). If you add a bundle script that consumers need but that isn't wired through one of these three patterns, CI will pass green while the script still silently fails to ship. Always cross-check new bundle scripts against `MANIFEST` by hand — don't rely on `lint.yml` catching the omission.

## Local mods manifest format

`mods/local-mods.manifest` (pipe-delimited, `#`-prefixed lines and blank lines skipped):

```
JAR_NAME|PROJECT_DIR|REFMAP_FILENAME
```

This single file drives three separate checks across the release pipeline:

- `release.yml`'s "Build in-house mods" step iterates it to build each project and copy the **remapped** jar (never the `-dev` jar) to `dist/local-mods/<JAR_NAME>`.
- `release.yml`'s "Verify mod JAR is remapped" step checks each jar for its declared refmap filename and a minimum class count (catches Loom producing an empty/unremapped jar despite a green Gradle build).
- `build-stack-bundle.sh` cross-validates the manifest against `dist/local-mods/` (both directions) before copying jars into `stack/local-mods/` in the tarball.

Adding a new in-house mod to the release pipeline means adding a row here — everything downstream reads from this one file.
