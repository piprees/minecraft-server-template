#!/usr/bin/env bash
# build-modpack.sh - Build the client .mrpack + download page from the manifest.
#
# Reads modpack/adventure.mrpack.json (_clientMods required/optional,
# _resourcePacks, _shaderPacks), resolves each slug's latest MC_VERSION Fabric
# build from Modrinth, bakes in overrides/ (Configured Defaults, servers.dat
# generated as real NBT), and writes modpack/dist/:
#   ${BRAND_SLUG}-<MC_VERSION>-v<gitsha>.mrpack  + ${BRAND_SLUG}-<MC_VERSION>-latest.mrpack
#   index.html (the player-facing "how to join" download page)
#
# CI runs this on the server after every successful deploy (idempotent;
# Discord ping only when mod content actually changed). pack-web (nginx)
# serves dist/ at pack.DOMAIN.
#
# Mod JARs are mirrored into dist/mods/ — both client mods (listed as the
# FIRST download URL in the pack index, Modrinth CDN second) AND server mods
# (from config/modrinth-mods.txt, resolved via the bulk /v2/versions API).
# Cloudflare edge-caches the JARs, launchers hash-verify and fall back
# automatically, so pack installs survive a Modrinth outage entirely,
# rebuilds of an unchanged list need no network, and dev-up.sh / CI can
# pre-seed data/mods/ from the mirror to avoid Modrinth rate limits.
#
# Usage:
#   ./scripts/build-modpack.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

# --- load .env ----------------------------------------------------------------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

MC_VERSION="${MC_VERSION:-1.21.1}"
PACK_VERSION="${GIT_SHA:-$(git rev-parse --short HEAD 2> /dev/null || echo unknown)}"
MANIFEST="${MANIFEST:-$PROJECT_DIR/modpack/adventure.mrpack.json}"
FABRIC_LOADER_VERSION=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['dependencies']['fabric-loader'])" 2> /dev/null || echo "0.19.3")
DIST_DIR="$PROJECT_DIR/modpack/dist"
WORK_DIR="$(mktemp -d)"
PACK_NAME="${BRAND_SLUG:-adventure}-${MC_VERSION}-v${PACK_VERSION}"

# --- check prerequisites ------------------------------------------------------
if ! command -v python3 &> /dev/null; then
  echo "python3 required. Install it first."
  exit 1
fi

if [[ ! -f "$MANIFEST" ]]; then
  echo "Manifest not found at $MANIFEST"
  exit 1
fi

echo "==> Building modpack: ${PACK_NAME}.mrpack"

# --- extract client mod slugs from manifest -----------------------------------
echo "==> Reading client mod list from manifest..."

REQUIRED_MODS=$(python3 -c "
import json, sys
with open('$MANIFEST') as f:
    data = json.load(f)
mods = data.get('_clientMods', {}).get('required', [])
for m in mods:
    print(m)
")

OPTIONAL_MODS=$(python3 -c "
import json, sys
with open('$MANIFEST') as f:
    data = json.load(f)
mods = data.get('_clientMods', {}).get('optional', [])
for m in mods:
    print(m)
")

STABLE_ONLY_SLUGS=$(python3 -c "
import json
print(','.join(json.load(open('$MANIFEST')).get('_clientMods', {}).get('stableOnly', [])))
")

echo "  Required: $(echo "$REQUIRED_MODS" | wc -l | xargs) mods"
echo "  Optional: $(echo "$OPTIONAL_MODS" | wc -l | xargs) mods"

# --- resolve Modrinth download URLs -------------------------------------------
echo ""
echo "==> Resolving download URLs from Modrinth API..."

resolve_mod() {
  local entry="$1"
  local optional="${2:-false}"
  local slug="${entry%%:*}"
  local pinned_vid=""
  [[ "$entry" == *:* ]] && pinned_vid="${entry#*:}"
  local tmpfile
  tmpfile="$(mktemp)"

  if [[ -n "$pinned_vid" ]]; then
    # Pinned version ID: resolve directly (bypass latest-version search).
    # Used when a mod's newest build has incompatible deps (e.g.
    # critters-and-companions 2.5.0 needs newer architectury than we pin).
    curl -s "https://api.modrinth.com/v2/version/${pinned_vid}" \
      -H "User-Agent: ${BRAND_SLUG:-adventure}/build-modpack" -o "$tmpfile"
    # Wrap in an array so the downstream parser is uniform
    python3 -c "import json; d=json.load(open('$tmpfile')); json.dump([d], open('$tmpfile','w'))" 2>/dev/null
  else
    # Get the latest version for our MC version + Fabric
    curl -s "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D" \
      -H "User-Agent: ${BRAND_SLUG:-adventure}/build-modpack" -o "$tmpfile"
  fi

  # Take the newest build of ANY channel by default - mod authors keep their
  # own latest sets coherent (supplementaries' release requires sodium's
  # beta; preferring releases downgraded sodium to 0.6.13 and broke launch).
  # Slugs listed in _clientMods.stableOnly get newest-RELEASE instead - for
  # mods whose pre-release channels have burned us (owo-lib alpha
  # mixin-crashed Particular).
  local result
  result=$(STABLE_ONLY="$STABLE_ONLY_SLUGS" SLUG="$slug" python3 -c "
import json, os, sys
with open('$tmpfile') as f:
    versions = json.load(f)
if not versions:
    sys.exit(1)
stable_only = set(os.environ.get('STABLE_ONLY', '').split(','))
slug = os.environ.get('SLUG', '')
if slug in stable_only:
    v = next((x for x in versions if x.get('version_type') == 'release'), versions[0])
else:
    v = versions[0]
for f in v.get('files', []):
    if f.get('primary', False):
        print(json.dumps({
            'path': 'mods/' + f['filename'],
            'hashes': {'sha1': f['hashes'].get('sha1', ''), 'sha512': f['hashes'].get('sha512', '')},
            'downloads': [f['url']],
            'fileSize': f['size'],
            '_slug': slug,
            '_versionId': v['id']
        }))
        break
" 2> /dev/null) || true

  rm -f "$tmpfile"

  if [[ -n "$result" ]]; then
    echo "  ✓ $slug"
    echo "$result"
  else
    if [[ "$optional" == "true" ]]; then
      echo "  ! $slug (no ${MC_VERSION} build - skipped, optional)" >&2
    else
      echo "  ✗ $slug (no ${MC_VERSION} build found)" >&2
    fi
  fi
}

# Collect file entries
FILES_JSON="["
FIRST=1

while IFS= read -r entry; do
  [[ -z "$entry" ]] && continue
  result=$(resolve_mod "$entry" "false" 2>&1 | grep -v '^  ' || true)
  if [[ -n "$result" ]]; then
    [[ $FIRST -eq 0 ]] && FILES_JSON+=","
    FILES_JSON+="$result"
    FIRST=0
  fi
done <<< "$REQUIRED_MODS"

while IFS= read -r entry; do
  [[ -z "$entry" ]] && continue
  result=$(resolve_mod "$entry" "true" 2>&1 | grep -v '^  ' || true)
  if [[ -n "$result" ]]; then
    [[ $FIRST -eq 0 ]] && FILES_JSON+=","
    FILES_JSON+="$result"
    FIRST=0
  fi
done <<< "$OPTIONAL_MODS"

FILES_JSON+="]"

# --- fail-fast: every manifest mod must resolve --------------------------------
RESOLVED_COUNT=$(python3 -c "import json; print(len(json.loads('''$FILES_JSON''')))")
MANIFEST_COUNT=$(( $(echo "$REQUIRED_MODS" | grep -c . || true) + $(echo "$OPTIONAL_MODS" | grep -c . || true) ))
if [[ "$RESOLVED_COUNT" -lt "$MANIFEST_COUNT" ]]; then
  MISSING=$((MANIFEST_COUNT - RESOLVED_COUNT))
  echo ""
  echo "ERROR: $MISSING of $MANIFEST_COUNT manifest mods failed to resolve." >&2
  echo "The pack would be missing mods and Fabric would refuse to launch." >&2
  echo "Check the ✗ lines above — the pinned versionId may be invalid," >&2
  echo "the Modrinth API may be down, or the modpack-builder image may" >&2
  echo "be stale (rebuild with: gh workflow run publish.yml)." >&2
  exit 1
fi

# --- mirror mod JARs + build the modrinth.index.json --------------------------
# Every mod JAR is mirrored into modpack/dist/mods/ (served by pack-web at
# https://pack.DOMAIN/mods/, edge-cached by Cloudflare - .jar is in CF's
# default cached-extension list). The index lists our mirror FIRST and the
# original Modrinth CDN URL second: Prism tries downloads[] in order and
# falls back on failure (verified against PrismLauncher source - no domain
# whitelist, multi-URL fallback), and verifies sha1/sha512 whichever source
# served the file. Net effect: installs survive a Modrinth outage (mirror
# serves) AND a droplet outage (Modrinth serves).
#
# Mirroring only fetches missing/corrupt files, so rebuilds are cheap and a
# Modrinth outage doesn't break rebuilds of an unchanged mod list. JARs no
# longer referenced by the current index are pruned.
echo ""
echo "==> Mirroring mod JARs and building modrinth.index.json..."

mkdir -p "$WORK_DIR"
MIRROR_DIR="$DIST_DIR/mods"
mkdir -p "$MIRROR_DIR"

# Write collected file entries to a temp file for safe JSON parsing
FILES_TMPFILE="$(mktemp)"
echo "$FILES_JSON" > "$FILES_TMPFILE"

python3 -c "
import hashlib, json, os, sys, urllib.request
from urllib.parse import quote

with open('$FILES_TMPFILE') as f:
    files = json.load(f)

mirror_dir = '$MIRROR_DIR'
domain = '${DOMAIN:-example.com}'
ua = '${BRAND_SLUG:-adventure}/build-modpack'

def sha512_of(path):
    h = hashlib.sha512()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

def fetch_jar(url, target, want_hash):
    try:
        req = urllib.request.Request(url, headers={'User-Agent': ua})
        with urllib.request.urlopen(req, timeout=60) as resp, open(target, 'wb') as out:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        if want_hash and sha512_of(target) != want_hash:
            os.remove(target)
            return False
        return True
    except Exception as e:
        print(f'  ! {os.path.basename(target)}: fetch failed ({e})', file=sys.stderr)
        if os.path.exists(target):
            os.remove(target)
        return False

# mirror_map: {mirror_name: original_filename} — used by dev-up.sh to
# pre-seed data/mods/ with the correct filenames itzg expects.
kept, mirror_map = set(), {}

# --- Mirror client mod JARs (content-addressed: {slug}-{versionId}.jar) ---
client_fetched, modrinth_only = 0, 0
for entry in files:
    original = entry['path'].split('/', 1)[1]
    slug = entry.get('_slug', '')
    vid = entry.get('_versionId', '')
    mirror_name = f'{slug}-{vid}.jar' if slug and vid else original
    target = os.path.join(mirror_dir, mirror_name)
    want = entry.get('hashes', {}).get('sha512', '')
    ok = os.path.isfile(target) and (not want or sha512_of(target) == want)
    if not ok:
        ok = fetch_jar(entry['downloads'][0], target, want)
        if ok:
            client_fetched += 1
    if ok:
        kept.add(mirror_name)
        mirror_map[mirror_name] = original
        entry['downloads'] = [f'https://mods.{domain}/mods/{quote(mirror_name)}'] + entry['downloads']
    else:
        modrinth_only += 1

# --- Mirror server mod JARs ---
server_mods_file = '$PROJECT_DIR/config/modrinth-mods.txt'
server_fetched = 0
if os.path.isfile(server_mods_file):
    slug_vid_pairs = []
    for line in open(server_mods_file):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('datapack:'):
            line = line[len('datapack:'):]
        parts = line.rstrip('?').split(':')
        if len(parts) == 2:
            slug_vid_pairs.append((parts[0], parts[1]))

    if slug_vid_pairs:
        vid_to_slug = {vid: slug for slug, vid in slug_vid_pairs}
        version_ids = [vid for _, vid in slug_vid_pairs]
        ids_json = json.dumps(version_ids)
        url = f'https://api.modrinth.com/v2/versions?ids={quote(ids_json)}'
        req = urllib.request.Request(url, headers={'User-Agent': ua})
        try:
            versions = json.loads(urllib.request.urlopen(req, timeout=30).read())
        except Exception as e:
            print(f'  ! Server mod bulk resolve failed ({e})', file=sys.stderr)
            versions = []

        for ver in versions:
            vid = ver['id']
            slug = vid_to_slug.get(vid, '')
            for f in ver.get('files', []):
                if f.get('primary', False):
                    original = f['filename']
                    mirror_name = f'{slug}-{vid}.jar' if slug else original
                    if mirror_name in kept:
                        break
                    target = os.path.join(mirror_dir, mirror_name)
                    want = f['hashes'].get('sha512', '')
                    ok = os.path.isfile(target) and (not want or sha512_of(target) == want)
                    if not ok:
                        ok = fetch_jar(f['url'], target, want)
                        if ok:
                            server_fetched += 1
                    if ok:
                        kept.add(mirror_name)
                        mirror_map[mirror_name] = original
                    break

# --- Write mirror map (for pre-seeding with correct filenames) ---
with open(os.path.join(mirror_dir, 'mirror-map.json'), 'w') as f:
    json.dump(mirror_map, f, indent=2, sort_keys=True)

# --- Prune mirror JARs no longer referenced by either mod list ---
pruned = 0
for existing in os.listdir(mirror_dir):
    if existing.endswith('.jar') and existing not in kept:
        os.remove(os.path.join(mirror_dir, existing))
        pruned += 1

index = {
    'formatVersion': 1,
    'game': 'minecraft',
    'versionId': '${PACK_NAME}',
    'name': '${BRAND_NAME:-Adventure Server} Modpack',
    'summary': 'Modded Minecraft ${MC_VERSION} - vanilla-plus.',
    'files': files,
    'dependencies': {
        'minecraft': '${MC_VERSION}',
        'fabric-loader': '${FABRIC_LOADER_VERSION}'
    }
}

with open('$WORK_DIR/modrinth.index.json', 'w') as f:
    json.dump(index, f, indent=2)

total = len(kept)
print(f'  {len(files)} client + {total - len(files) + modrinth_only} server mods resolved')
print(f'  {total} mirrored ({client_fetched} client + {server_fetched} server newly fetched, {pruned} pruned), {modrinth_only} client Modrinth-only')
"
rm -f "$FILES_TMPFILE"

# --- dependency coherence gate --------------------------------------------------
# Refuse to publish a pack Fabric would refuse to launch: every mod's
# depends/breaks predicates are checked against the mods actually present
# (the sodium/supplementaries incident, 2026-07-02). On conflict the build
# aborts here and the previously-published artefacts keep serving.
echo ""
echo "==> Checking mod dependency coherence..."
python3 "$SCRIPT_DIR/check-pack-coherence.py" "$MIRROR_DIR"

# --- copy static overrides (servers.dat, options.txt, configs) ----------------
OVERRIDES_SRC="$PROJECT_DIR/modpack/overrides"
if [[ -d "$OVERRIDES_SRC" ]]; then
  cp -r "$OVERRIDES_SRC" "$WORK_DIR/overrides"

  # Substitute domain and port in maplink config
  if [[ -f "$WORK_DIR/overrides/configureddefaults/config/maplink/general.json5" ]]; then
    sed_i "s|mc\.example\.com:25577|mc.${DOMAIN:-example.com}:${SERVER_PORT:-25577}|g" \
         "$WORK_DIR/overrides/configureddefaults/config/maplink/general.json5"
    sed_i "s|https://map\.example\.com|https://map.${DOMAIN:-example.com}|g" \
         "$WORK_DIR/overrides/configureddefaults/config/maplink/general.json5"
  fi

  python3 -c "
import struct, io

def write_nbt(filepath, servers):
    buf = io.BytesIO()
    def ws(s):
        e = s.encode('utf-8')
        buf.write(struct.pack('>H', len(e)))
        buf.write(e)
    buf.write(b'\x0a'); ws('')  # root compound
    buf.write(b'\x09'); ws('servers')  # list
    buf.write(b'\x0a'); buf.write(struct.pack('>i', len(servers)))  # list of compounds
    for s in servers:
        for k, v in s.items():
            buf.write(b'\x08'); ws(k); ws(v)
        buf.write(b'\x00')
    buf.write(b'\x00')  # end root
    with open(filepath, 'wb') as f:
        f.write(buf.getvalue())

write_nbt('$WORK_DIR/overrides/servers.dat', [
    {'name': '${BRAND_NAME:-Adventure Server}', 'ip': 'mc.${DOMAIN:-example.com}'},
    {'name': '${BRAND_NAME:-Adventure Server} (Local Dev)', 'ip': 'localhost:${SERVER_PORT:-25577}'},
])
" 2> /dev/null && echo "  ✓ servers.dat written"
  echo ""
  echo "==> Copied client overrides:"
  find "$WORK_DIR/overrides" -type f -not -path '*/resourcepacks/*' -not -path '*/shaderpacks/*' | while read -r f; do
    echo "  ✓ $(echo "$f" | sed "s|$WORK_DIR/overrides/||")"
  done
fi

# --- download resource packs into overrides -----------------------------------
mkdir -p "$WORK_DIR/overrides/resourcepacks"

# Entries in _resourcePacks.packs are either a plain slug (download the
# version's primary file) or an object {"slug": ..., "files": [...]} that also
# downloads the named companion files (micropacks) from the same resolved
# version, so companions can never drift out of sync with the main pack.
RESOURCE_PACKS=$(python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
packs = data.get('_resourcePacks', {}).get('packs', [])
for p in packs:
    if isinstance(p, str):
        print(p)
    else:
        print(p['slug'] + '\t' + '\t'.join(p.get('files', [])))
" 2> /dev/null || true)

if [[ -n "$RESOURCE_PACKS" ]]; then
  echo ""
  echo "==> Downloading resource packs into overrides/resourcepacks/..."

  while IFS= read -r rp_entry; do
    [[ -z "$rp_entry" ]] && continue
    slug="${rp_entry%%$'\t'*}"
    rp_extras=""
    [[ "$rp_entry" == *$'\t'* ]] && rp_extras="${rp_entry#*$'\t'}"

    # Prefer the newest build tagged for MC_VERSION - a pack's latest upload
    # can target newer Minecraft only (e.g. Fresh Animations 1.10.5 is
    # 26.x-only while 1.10.4 is the newest 1.21.1 build). Packs without
    # game-version tags fall back to the newest upload.
    rp_tmpfile="$(mktemp)"
    curl -s "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&limit=1" \
      -H "User-Agent: ${BRAND_SLUG:-adventure}/build-modpack" -o "$rp_tmpfile"
    if [[ "$(head -c 2 "$rp_tmpfile")" == "[]" ]]; then
      curl -s "https://api.modrinth.com/v2/project/${slug}/version?limit=1" \
        -H "User-Agent: ${BRAND_SLUG:-adventure}/build-modpack" -o "$rp_tmpfile"
    fi

    rp_info=$(RP_EXTRAS="$rp_extras" python3 -c "
import json, os, sys
with open('$rp_tmpfile') as f:
    versions = json.load(f)
if not versions:
    sys.exit(1)
files = versions[0].get('files', [])
picked = [f for f in files if f.get('primary', False)][:1]
for name in os.environ.get('RP_EXTRAS', '').split('\t'):
    if not name:
        continue
    match = [f for f in files if f['filename'] == name]
    if match:
        picked.append(match[0])
    else:
        print('MISSING\t' + name)
for f in picked:
    print(f['url'] + '\t' + f['filename'])
" 2> /dev/null) || true
    rm -f "$rp_tmpfile"

    if [[ -n "$rp_info" ]]; then
      while IFS=$'\t' read -r rp_url rp_filename; do
        [[ -z "$rp_url" ]] && continue
        if [[ "$rp_url" == "MISSING" ]]; then
          echo "  ✗ $slug - companion file '$rp_filename' not in the resolved version" >&2
          continue
        fi
        if curl -sL --max-time 60 -o "$WORK_DIR/overrides/resourcepacks/$rp_filename" "$rp_url"; then
          echo "  ✓ $slug ($rp_filename)"
        else
          echo "  ✗ $slug - download failed ($rp_filename)" >&2
        fi
      done <<< "$rp_info"
    else
      echo "  ! $slug - no ${MC_VERSION} build found" >&2
    fi
  done <<< "$RESOURCE_PACKS"

  # options.txt enables packs by exact filename. When a pack updates on
  # Modrinth the filename changes, and a stale options.txt entry would ship
  # the pack silently disabled - fail the build so the entry gets refreshed.
  OPTIONS_TXT="$WORK_DIR/overrides/configureddefaults/options.txt"
  if [[ -f "$OPTIONS_TXT" ]]; then
    python3 - "$OPTIONS_TXT" "$WORK_DIR/overrides/resourcepacks" << 'DRIFTEOF'
import json, os, sys
options_path, rp_dir = sys.argv[1], sys.argv[2]
line = next((l for l in open(options_path, encoding='utf-8')
             if l.startswith('resourcePacks:')), None)
if line:
    entries = json.loads(line.split(':', 1)[1])
    have = set(os.listdir(rp_dir))
    missing = [e[5:] for e in entries
               if e.startswith('file/') and e.endswith('.zip') and e[5:] not in have]
    for m in missing:
        print(f"  ✗ options.txt enables '{m}' but no such file was downloaded"
              " (pack updated on Modrinth? refresh the filename)", file=sys.stderr)
    if missing:
        sys.exit(1)
DRIFTEOF
  fi
fi

# --- download shader packs into overrides --------------------------------------
SHADER_PACKS=$(python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
packs = data.get('_shaderPacks', {}).get('packs', [])
for p in packs:
    print(p)
" 2> /dev/null || true)

if [[ -n "$SHADER_PACKS" ]]; then
  mkdir -p "$WORK_DIR/overrides/shaderpacks"
  echo ""
  echo "==> Downloading shader packs into overrides/shaderpacks/..."

  while IFS= read -r slug; do
    [[ -z "$slug" ]] && continue

    # Same resolution rule as resource packs: newest MC_VERSION-tagged build,
    # falling back to the newest upload for packs without game-version tags.
    sp_tmpfile="$(mktemp)"
    curl -s "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&limit=1" \
      -H "User-Agent: ${BRAND_SLUG:-adventure}/build-modpack" -o "$sp_tmpfile"
    if [[ "$(head -c 2 "$sp_tmpfile")" == "[]" ]]; then
      curl -s "https://api.modrinth.com/v2/project/${slug}/version?limit=1" \
        -H "User-Agent: ${BRAND_SLUG:-adventure}/build-modpack" -o "$sp_tmpfile"
    fi

    sp_info=$(python3 -c "
import json, sys
with open('$sp_tmpfile') as f:
    versions = json.load(f)
if not versions:
    sys.exit(1)
v = versions[0]
for f in v.get('files', []):
    if f.get('primary', False):
        print(f['url'])
        print(f['filename'])
        break
" 2> /dev/null) || true
    rm -f "$sp_tmpfile"

    if [[ -n "$sp_info" ]]; then
      sp_url=$(echo "$sp_info" | head -1)
      sp_filename=$(echo "$sp_info" | tail -1)
      if curl -sL --max-time 60 -o "$WORK_DIR/overrides/shaderpacks/$sp_filename" "$sp_url"; then
        echo "  ✓ $slug ($sp_filename)"
      else
        echo "  ✗ $slug - download failed" >&2
      fi
    else
      echo "  ! $slug - not found on Modrinth" >&2
    fi
  done <<< "$SHADER_PACKS"
fi

# --- package as .mrpack (ZIP with modrinth.index.json + overrides) ------------
echo ""
echo "==> Packaging ${PACK_NAME}.mrpack..."

mkdir -p "$DIST_DIR"
PACK_FILE="$DIST_DIR/${PACK_NAME}.mrpack"

(cd "$WORK_DIR" && zip -r "$PACK_FILE" modrinth.index.json overrides/)

# --- create 'latest' symlink --------------------------------------------------
LATEST_LINK="$DIST_DIR/${BRAND_SLUG:-adventure}-${MC_VERSION}-latest.mrpack"
ln -sf "${PACK_NAME}.mrpack" "$LATEST_LINK"

# --- brand assets (icons, og image) -----------------------------------------------
# Ships web-ready assets (icon.svg, og-image.jpg, favicon.ico, apple-touch-icon.png)
# to the site root so nav-proxy and OG tags can reference them.
if [[ -d "$PROJECT_DIR/assets" ]]; then
  for asset in "$PROJECT_DIR/assets/"*.{svg,png,ico,jpg,jpeg} ; do
    [[ -f "$asset" ]] && cp "$asset" "$DIST_DIR/"
  done
fi

# --- copy font assets to dist -------------------------------------------------
mkdir -p "$DIST_DIR/fonts"
if [[ -d "$PROJECT_DIR/modpack/template/fonts" ]]; then
  cp "$PROJECT_DIR/modpack/template/fonts/"*.woff2 "$DIST_DIR/fonts/" 2>/dev/null || true
fi

# --- create a polished index.html for the download page -----------------------
# The template lives in modpack/template/index.html for easy local preview and
# iteration (open it in a browser directly). Variables are substituted at build
# time by sed. The template uses \${VAR} syntax matching the heredoc convention.
DOMAIN_VAL="${DOMAIN:-example.com}"
BRAND_NAME_VAL="${BRAND_NAME:-Adventure Server}"
BRAND_SLUG_VAL="${BRAND_SLUG:-adventure}"
DISCORD_INVITE_VAL="${DISCORD_INVITE_URL:-}"
# Use python for template substitution — sed breaks on spaces, ampersands, pipes in values
python3 -c "
import sys
tpl = open(sys.argv[1]).read()
for k, v in [('MC_VERSION','${MC_VERSION}'),('PACK_NAME','${PACK_NAME}'),
             ('DOMAIN','${DOMAIN_VAL}'),('BRAND_NAME','${BRAND_NAME_VAL}'),
             ('BRAND_SLUG','${BRAND_SLUG_VAL}'),('DISCORD_INVITE_URL','${DISCORD_INVITE_VAL}')]:
    tpl = tpl.replace('\${' + k + '}', v)
open(sys.argv[2], 'w').write(tpl)
" "$PROJECT_DIR/modpack/template/index.html" "$DIST_DIR/index.html"
echo "  \u2713 Download page generated from template"

# --- create a branded 404 page -----------------------------------------------
cat > "$DIST_DIR/404.html" << 'NOTFOUND'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Page Not Found</title>
    <meta name="robots" content="noindex, nofollow">
    <meta name="theme-color" content="#0c1319">
    <link rel="icon" href="/favicon.ico">
    <style>
        *{margin:0;padding:0;box-sizing:border-box}
        body{font-family:system-ui,-apple-system,sans-serif;background:#0c1319;color:#c5cdd8;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding-top:44px}
        h1{font-family:Georgia,"Times New Roman",serif;font-size:1.5rem;color:#e8ecf1;margin-bottom:.5rem;letter-spacing:.02em}
        p{color:#7a8999;max-width:28rem;text-align:center;line-height:1.6}
        a{color:#5a9a70;text-decoration:none}
        a:hover{color:#70b088}
    </style>
</head>
<body>
    <h1>Page not found</h1>
    <p>That page doesn't exist. <a href="/">Back to the download page</a>.</p>
</body>
</html>
NOTFOUND

# --- generate the packwiz pack (auto-update index) -----------------------------
# packwiz-installer runs as a Prism pre-launch task and syncs the instance
# against this index on EVERY launch: adds, updates, and removes mods,
# hash-verified, and never touches files it doesn't manage - so player
# keybinds/settings are structurally safe. All download URLs point at our
# Cloudflare-cached mirror (pack.DOMAIN/mods/), so auto-updates work even
# when Modrinth is down. The .toml index files aren't edge-cached (not in
# CF's default extension list), so update signals are always fresh.
echo ""
echo "==> Generating packwiz auto-update index..."
PACKWIZ_DIR="$DIST_DIR/packwiz"
mkdir -p "$PACKWIZ_DIR"
python3 - "$WORK_DIR" "$PACKWIZ_DIR" "$MC_VERSION" "$FABRIC_LOADER_VERSION" "$PACK_VERSION" << 'PWEOF'
import hashlib, json, os, shutil, sys

work, pw, mc_version, loader_version, pack_version = sys.argv[1:6]

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

def t(s):  # TOML basic string (json escaping is valid TOML)
    return json.dumps(s)

# Regenerate managed subtrees; keep the cached bootstrap jar and pack.toml
for sub in ('mods', 'configureddefaults', 'resourcepacks', 'shaderpacks'):
    shutil.rmtree(os.path.join(pw, sub), ignore_errors=True)
os.makedirs(os.path.join(pw, 'mods'), exist_ok=True)

index_entries = []

# 1. Mods: one metafile each, download URL = mirror-first entry from the index
mrindex = json.load(open(os.path.join(work, 'modrinth.index.json')))
for f in mrindex['files']:
    jar = f['path'].split('/', 1)[1]
    stem = jar[:-4] if jar.endswith('.jar') else jar
    rel = 'mods/' + stem + '.pw.toml'
    body = (
        'name = ' + t(stem) + '\n'
        'filename = ' + t(jar) + '\n'
        'side = "both"\n\n'
        '[download]\n'
        'url = ' + t(f['downloads'][0]) + '\n'
        'hash-format = "sha512"\n'
        'hash = ' + t(f['hashes']['sha512']) + '\n'
    )
    path = os.path.join(pw, rel)
    with open(path, 'w') as out:
        out.write(body)
    index_entries.append((rel, sha256_file(path), True, False))

# 2. Non-mod files from overrides (configureddefaults tree, servers.dat,
#    resourcepacks, shaderpacks). servers.dat is preserve=true so a player's
#    own server-list additions survive; everything else here is pack-managed.
ov = os.path.join(work, 'overrides')
for root, dirs, files in os.walk(ov):
    for name in files:
        src = os.path.join(root, name)
        rel = os.path.relpath(src, ov).replace(os.sep, '/')
        if name == '.DS_Store':
            continue
        dst = os.path.join(pw, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        preserve = (rel == 'servers.dat')
        index_entries.append((rel, sha256_file(dst), False, preserve))

# 3. index.toml
lines = ['hash-format = "sha256"', '']
for rel, digest, metafile, preserve in sorted(index_entries):
    lines.append('[[files]]')
    lines.append('file = ' + t(rel))
    lines.append('hash = ' + t(digest))
    if metafile:
        lines.append('metafile = true')
    if preserve:
        lines.append('preserve = true')
    lines.append('')
with open(os.path.join(pw, 'index.toml'), 'w') as out:
    out.write('\n'.join(lines))

# 4. pack.toml (points at the index with its hash)
index_hash = sha256_file(os.path.join(pw, 'index.toml'))
pack_toml = (
    'name = "${BRAND_NAME:-Adventure Server}"\n'
    'author = ""\n'
    'version = ' + t(pack_version) + '\n'
    'pack-format = "packwiz:1.1.0"\n\n'
    '[versions]\n'
    'minecraft = ' + t(mc_version) + '\n'
    'fabric = ' + t(loader_version) + '\n\n'
    '[index]\n'
    'file = "index.toml"\n'
    'hash-format = "sha256"\n'
    'hash = ' + t(index_hash) + '\n'
)
with open(os.path.join(pw, 'pack.toml'), 'w') as out:
    out.write(pack_toml)

mods = sum(1 for e in index_entries if e[2])
print(f'  packwiz index: {mods} mods + {len(index_entries) - mods} files')
PWEOF

# Pinned packwiz-installer-bootstrap (cached; GitHub needed at build time only)
BOOTSTRAP_JAR="$PACKWIZ_DIR/packwiz-installer-bootstrap.jar"
if [[ ! -f "$BOOTSTRAP_JAR" ]]; then
  echo "  Fetching packwiz-installer-bootstrap v0.0.3..."
  curl -sL --max-time 120 -o "$BOOTSTRAP_JAR" \
    "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar" \
    || { rm -f "$BOOTSTRAP_JAR"; echo "  ! bootstrap download failed - instance zip will be skipped" >&2; }
fi

# --- build the one-click Prism instance zip ------------------------------------
# The .mrpack format can't carry launcher-level settings (verified against
# Prism's ModrinthInstanceCreationTask: it reads only the index + overrides),
# so we ship a Prism/MMC-format instance zip: the icon, the ZGC Java args
# (the "crash a few seconds after joining" fix), auto-join, the Java-compat
# override, and packwiz-installer as a pre-launch task. Import once - the
# instance then self-updates from the packwiz index on every launch via the
# CDN. Deliberately slim: no mods baked in; packwiz pulls everything on
# first boot from the edge-cached mirror.
INSTANCE_ZIP="$DIST_DIR/${PACK_NAME}-prism-instance.zip"
if [[ -f "$BOOTSTRAP_JAR" ]]; then
  echo ""
  echo "==> Building Prism instance zip (self-updating via packwiz)..."
  INST_DIR="$(mktemp -d)"
  mkdir -p "$INST_DIR/.minecraft"

  cat > "$INST_DIR/instance.cfg" << CFGEOF
[General]
ConfigVersion=1.3
InstanceType=OneSix
name=${BRAND_NAME:-Adventure Server}
iconKey=fox_legacy
OverrideJavaArgs=true
JvmArgs=-XX:+UseZGC -XX:+ZGenerational
IgnoreJavaCompatibility=true
JoinServerOnLaunch=true
JoinServerOnLaunchAddress=mc.${DOMAIN:-example.com}
OverrideCommands=true
PreLaunchCommand="\"\$INST_JAVA\" -jar packwiz-installer-bootstrap.jar -s client https://pack.${DOMAIN:-example.com}/packwiz/pack.toml"
ManagedPack=false
CFGEOF

  cat > "$INST_DIR/mmc-pack.json" << MMCEOF
{
    "components": [
        { "cachedName": "LWJGL 3", "cachedVersion": "3.3.3", "cachedVolatile": true, "dependencyOnly": true, "uid": "org.lwjgl3", "version": "3.3.3" },
        { "cachedName": "Minecraft", "cachedRequires": [ { "suggests": "3.3.3", "uid": "org.lwjgl3" } ], "cachedVersion": "${MC_VERSION}", "important": true, "uid": "net.minecraft", "version": "${MC_VERSION}" },
        { "cachedName": "Intermediary Mappings", "cachedRequires": [ { "equals": "${MC_VERSION}", "uid": "net.minecraft" } ], "cachedVersion": "${MC_VERSION}", "cachedVolatile": true, "dependencyOnly": true, "uid": "net.fabricmc.intermediary", "version": "${MC_VERSION}" },
        { "cachedName": "Fabric Loader", "cachedRequires": [ { "uid": "net.fabricmc.intermediary" } ], "cachedVersion": "${FABRIC_LOADER_VERSION}", "uid": "net.fabricmc.fabric-loader", "version": "${FABRIC_LOADER_VERSION}" }
    ],
    "formatVersion": 1
}
MMCEOF

  cp "$BOOTSTRAP_JAR" "$INST_DIR/.minecraft/packwiz-installer-bootstrap.jar"
  cp -r "$WORK_DIR/overrides/." "$INST_DIR/.minecraft/"

  # Only the current version is kept — prune old instance zips and mrpacks
  rm -f "$DIST_DIR"/${BRAND_SLUG:-adventure}-*-prism-instance.zip
  for old in "$DIST_DIR"/${BRAND_SLUG:-adventure}-*-v*.mrpack; do
    [[ -f "$old" ]] || continue
    [[ "$old" == "$PACK_FILE" ]] && continue
    rm -f "$old"
  done
  (cd "$INST_DIR" && zip -qr "$INSTANCE_ZIP" .)
  rm -rf "$INST_DIR"
  echo "  ✓ $(basename "$INSTANCE_ZIP") ($(du -h "$INSTANCE_ZIP" | cut -f1))"
else
  echo ""
  echo "==> Skipping Prism instance zip (bootstrap jar unavailable)"
fi

# --- clean up -----------------------------------------------------------------
rm -rf "$WORK_DIR"

echo ""
echo "=================================================================="
echo " Modpack built: ${PACK_FILE}"
echo " Latest link:   ${LATEST_LINK}"
echo " Download page: modpack/dist/index.html"
echo ""
echo " To serve: the pack-web container (nginx) mounts modpack/dist/"
echo " Friends download from: https://pack.${DOMAIN:-example.com}/"
echo "=================================================================="
