# Minecraft Adventure Server modpack

The client modpack is a `.mrpack` file (Modrinth format) that friends import into their launcher to get the right mods. It contains only the mods needed client-side. The server handles the rest.

## How it works

- **`adventure.mrpack.json`** is the manifest listing all client-side mods (slugs from Modrinth).
- **`scripts/build-modpack.sh`** reads the manifest, resolves download URLs from Modrinth's API, builds the `.mrpack` ZIP, and stages it in `modpack/dist/` for the pack-web container to serve.
- The modpack is served at `pack.DOMAIN` via the Cloudflare tunnel, with a versioned filename and a `latest` symlink.

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
