#!/usr/bin/env bash
# Entrypoint for the modpack-builder container.
#
# Mounts:
#   /overlay (ro)   — consumer overlay dir (may be empty/absent)
#   /work/dist (rw) — output directory
set -euo pipefail

MC_VERSION="${MC_VERSION:-1.21.1}"
DOMAIN="${DOMAIN:-example.com}"
BRAND_NAME="${BRAND_NAME:-Adventure Server}"
BRAND_SLUG="${BRAND_SLUG:-adventure}"
DISCORD_INVITE_URL="${DISCORD_INVITE_URL:-}"
SERVER_PORT="${SERVER_PORT:-25577}"
LOCAL_DOMAIN="${LOCAL_DOMAIN:-localhost}"
export PACK_VERSION="${GIT_SHA:-unknown}"

# --- prepare source tree -----------------------------------------------------
mkdir -p /work/src /work/dist

cp /defaults/manifest.json /work/src/manifest.json

if [[ -f /overlay/modpack/manifest.json ]]; then
  python3 /app/merge-manifest.py \
    /work/src/manifest.json \
    /overlay/modpack/manifest.json \
    /work/src/manifest.json
fi

if [[ -d /defaults/overrides ]]; then
  cp -r /defaults/overrides /work/src/overrides
fi

if [[ -d /overlay/modpack/overrides ]]; then
  cp -r /overlay/modpack/overrides/* /work/src/overrides/ 2>/dev/null || true
fi

if [[ -d /overlay/assets ]]; then
  ASSETS_DIR=/overlay/assets
else
  ASSETS_DIR=/defaults/assets
fi

# --- build the project structure build-modpack.sh expects --------------------
PROJECT=/work/project
mkdir -p "$PROJECT/modpack" "$PROJECT/scripts"

ln -sf /work/src/manifest.json "$PROJECT/modpack/adventure.mrpack.json"
ln -sf /work/src/overrides     "$PROJECT/modpack/overrides"
# Consumers can replace the download page wholesale:
# overlay/modpack/template/index.html
if [[ -f /overlay/modpack/template/index.html ]]; then
  ln -sf /overlay/modpack/template "$PROJECT/modpack/template"
else
  ln -sf /defaults/template      "$PROJECT/modpack/template"
fi
ln -sf /work/dist              "$PROJECT/modpack/dist"
ln -sf "$ASSETS_DIR"           "$PROJECT/assets"
ln -sf /app                    "$PROJECT/scripts"
touch "$PROJECT/.env"

# --- export env for build-modpack.sh -----------------------------------------
export MC_VERSION DOMAIN BRAND_NAME BRAND_SLUG DISCORD_INVITE_URL SERVER_PORT
export GIT_SHA="${GIT_SHA:-unknown}"
export MANIFEST=/work/src/manifest.json
export PROJECT_DIR="$PROJECT"

# --- prod build --------------------------------------------------------------
cd "$PROJECT"
bash /app/build-modpack.sh

# --- local variant -----------------------------------------------------------
# Copy the prod .mrpack and replace servers.dat with one that has both entries.
PROD_MRPACK="/work/dist/${BRAND_SLUG}-${MC_VERSION}-latest.mrpack"
LOCAL_MRPACK="/work/dist/${BRAND_SLUG}-${MC_VERSION}-local-latest.mrpack"

if [[ -f "$PROD_MRPACK" ]]; then
  # The build creates servers.dat with both entries (prod + local dev).
  # Rename the build output as the local variant, then fix the prod pack.
  cp "$PROD_MRPACK" "$LOCAL_MRPACK"

  # Replace prod pack's servers.dat with prod-only entry
  PROD_SERVERS_DAT="$(mktemp)"
  python3 -c "
import struct, io, sys

def write_nbt(filepath, servers):
    buf = io.BytesIO()
    def ws(s):
        e = s.encode('utf-8')
        buf.write(struct.pack('>H', len(e)))
        buf.write(e)
    buf.write(b'\x0a'); ws('')
    buf.write(b'\x09'); ws('servers')
    buf.write(b'\x0a'); buf.write(struct.pack('>i', len(servers)))
    for s in servers:
        for k, v in s.items():
            buf.write(b'\x08'); ws(k); ws(v)
        buf.write(b'\x00')
    buf.write(b'\x00')
    with open(filepath, 'wb') as f:
        f.write(buf.getvalue())

write_nbt(sys.argv[1], [
    {'name': '${BRAND_NAME}', 'ip': 'mc.${DOMAIN}'},
])
" "$PROD_SERVERS_DAT"

  TMPDIR_PROD="$(mktemp -d)"
  cd "$TMPDIR_PROD"
  unzip -qo "$PROD_MRPACK"
  cp "$PROD_SERVERS_DAT" overrides/servers.dat
  zip -qr "$PROD_MRPACK" modrinth.index.json overrides/
  cd /work
  rm -rf "$TMPDIR_PROD" "$PROD_SERVERS_DAT"

  echo "  ✓ Prod variant: $(basename "$PROD_MRPACK") (prod-only servers.dat)"
  echo "  ✓ Local variant: $(basename "$LOCAL_MRPACK") (prod + local dev servers.dat)"
else
  echo "  ! Skipping local variant — prod .mrpack not found" >&2
fi

echo ""
echo "==> modpack-builder complete"
ls -lh /work/dist/*.mrpack 2>/dev/null || true
