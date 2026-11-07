# Minecraft Adventure Server modpack

The client modpack is a `.mrpack` file (Modrinth format) that friends import into their launcher to get the right mods. It contains only the mods needed client-side. The server handles the rest.

## How it works

- **`adventure.mrpack.json`** is the manifest listing all client-side mods (slugs from Modrinth).
- **`scripts/build-modpack.sh`** reads the manifest, resolves download URLs from Modrinth's API, builds the `.mrpack` ZIP, and stages it in `modpack/dist/` for the pack-web container to serve.
- The modpack is served at `pack.DOMAIN` via the Cloudflare tunnel, with a versioned filename and a `latest` symlink.

## Per-version files

The pack follows the selected Minecraft version (`.minecraft-version`, or `MC_VERSION_OVERRIDE`):

- **`adventure-<version>.mrpack.json`** replaces `adventure.mrpack.json` when it exists (`mc_manifest` in `scripts/lib.sh`).
- **`versions/<version>/overrides/`** replaces or adds files of `overrides/`, and **`versions/<version>/overrides-remove.txt`** lists `overrides/` paths that version leaves out (`pack_version_overrides` in `scripts/lib.sh`).
- The in-house client mods of `mods/client-mods[-<version>].manifest` ride in `overrides/mods/` (the modpack-builder image copies them from the bundle's `stack/client-mods/`).

### The 26.3 pack

`adventure-26.3.mrpack.json` is optional: an unmodded 26.3 client joins the 26.3 server, and the pack adds effects only ([RB-02, RB-03](../docs/design/rebuild-26/decisions.md)). It holds the register's kept client and optional rows ([mod-register.csv](../docs/design/rebuild-26/mod-register.csv)) with the libraries they need: Sodium, Iris, Complementary Reimagined with Euphoria Patches, Distant Horizons, Xaero's maps, EMF/ETF with Fresh Animations and Human Era villagers, sounds, cosmetics, Simple Voice Chat and the companion (`customdimensionsclient.jar`). Rows waiting for a 26.3 build are listed under `_waiting` with the list each joins. `versions/26.3/` carries the 26.3 `options.txt` (its resource packs, data version 5023) and an `iris.properties` with shaders switched off, and leaves out the settings of cut, replaced and rebuilt mods. The shader stays selectable in Iris's menu: under Mesa's software renderer the client crashes in Iris's composite pass with it on ([parity.md](../docs/design/rebuild-26/parity.md)), and no GPU run has checked it. Build it with:

```bash
MC_VERSION_OVERRIDE=26.3 ./scripts/build-modpack.sh
```

`scripts/e2e/vanilla-join.sh --mrpack <built .mrpack> --expect 'companion-accept:handshake'` installs the built pack into a real 26.3 client and joins the 26.3 smoke stack (`scripts/smoke-26.sh`).

## Versioning

Each mod change bumps `MODPACK_VERSION` in `.env`. The filename follows the pattern:

```
adventure-1.21.1-v2.mrpack
```

A `latest.mrpack` symlink always points to the newest version.

## For friends

1. Download the modpack from `https://pack.DOMAIN/adventure-1.21.1-latest.mrpack`
2. Open your Minecraft launcher (Prism, MultiMC, Modrinth App, etc.)
3. Import the `.mrpack` file
4. Launch and connect to `mc.DOMAIN`

See `https://pack.DOMAIN` for the full walkthrough.

## Rebuilding

```bash
./scripts/build-modpack.sh
```

The build script bumps the version, builds the pack, and stages it for download. On the server, restart pack-web if needed:

```bash
docker compose --profile cloud restart pack-web
```
