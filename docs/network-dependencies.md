# Network dependency model

Every external network call is either eliminated, cached, or made failure-tolerant. Nothing breaks because of an API timeout or CDN outage at runtime.

Referenced from [`AGENTS.md`](../AGENTS.md).

## Operations that need network

| When | What calls out | Mitigated by |
| --- | --- | --- |
| **CI image build** | Docker Hub (base images), PyPI (`pip install`), APK repos (`apk add`) | GHCR mirrors for Docker Hub images (`mirror-images.yml`, weekly sync); PyPI/APK pins make builds deterministic; network is always available in CI runners |
| **CI deploy** | GitHub API (stack version resolution), GHCR (image pulls), SSH to server | `stack-pull.sh` retries 3x with exponential backoff, then falls back to `.stack/.resolved-cache`, then local `.stack/` dirs; GHCR mirrors avoid Docker Hub rate limits |
| **CI release** | GitHub API (release creation), GHCR (image push), git-cliff (via `orhun/git-cliff-action`) | `git-cliff-action` handles download and caching internally; release creation is inherently online |
| **CI smoke test** | Modrinth CDN (mod JARs on cache miss), Carpet mod JAR | Mod JARs cached by `actions/cache` keyed on the `modrinth-mods.txt` hash; Carpet JAR cached by version |
| **First consumer boot** | GitHub API (version resolution), GitHub Releases (bundle download), Modrinth CDN (mod JARs on first seed) | `stack-pull.sh` retry + cache fallback; mod JARs cached in the `stack-mods` volume after first resolution (version IDs are immutable) |
| **Modpack build** | Modrinth API (version resolution for resource/shader packs) | `modrinth-resolve-cache.json` committed in the repo — zero API calls on a cache hit for known pins |
| **Discord webhooks** | Discord API | Every webhook call ends `\|\| true` — a Discord outage never blocks a deploy or boot |

## Operations that are fully offline

| When | Why |
| --- | --- |
| **Server boot** | Zero network requests. `MODS_FILE`/`DATAPACKS_FILE` are empty by default, so itzg never HEAD-checks mod URLs at boot ([T4](../TROUBLESHOOTING.md#t4)). Missing jars are fetched host-side by `scripts/sync-mods.sh` between the seed and mc's start (dev-up.sh, deploy.sh step 10b, CI) — network is touched only when the mod list changed. `./scripts/cache-assets.sh --mods` keeps an extra jar snapshot. |
| **Server runtime** | Gameplay, RCON, autopause, idle-tasks, map rendering and Discord bot commands all run locally |
| **Seed rolling** | Uses the warm `defaults-seed` image; no itzg entrypoint, no network calls |
| **Config changes** | Edit `.env` or `overlay/`, then `./dev up` — the seed container uses baked-in defaults |
| **Local dev after first boot** | All images, mods and configs are cached locally |
| **Stack bundle re-use** | `stack-pull.sh` is idempotent — a resolved version already in `.stack/<version>/` is not re-downloaded |

## How each dependency is mitigated

- **Docker Hub images:** mirrored to `ghcr.io/piprees/mirrors/` weekly by `mirror-images.yml`; compose references use `${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}` so consumers can override with `docker.io`
- **Modrinth API:** eliminated from the boot path ([T4](../TROUBLESHOOTING.md#t4)); the seed container resolves pins to direct CDN URLs cached in the `stack-mods` volume; `modrinth-resolve-cache.json` is committed for the modpack build
- **Modrinth CDN (mod JARs):** cached in `data/mods/` after first download; CI caches via `actions/cache` keyed on the `modrinth-mods.txt` hash
- **GitHub API (version resolution):** `stack-pull.sh` retries 3x with exponential backoff (2s/4s/8s), then falls back to `.stack/.resolved-cache` (last successful resolution), then scans `.stack/` directories
- **GitHub Releases (bundle download):** `stack-pull.sh` retries downloads 3x; bundles are cached in `.stack/<version>/` and never re-downloaded
- **git-cliff binary:** installed via `orhun/git-cliff-action`, which handles download, caching and platform detection
- **Carpet mod (smoke test):** cached via `actions/cache` keyed on the version string
- **PyPI packages:** pinned to exact versions (`==`), needed only at image build time
- **Discord webhooks:** all `|| true` — fire and forget
- **Stack bundle (`scripts/`, `config/`):** cached in `.stack/`; downloaded only on a version change
