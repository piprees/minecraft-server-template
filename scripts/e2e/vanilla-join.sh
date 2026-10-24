#!/usr/bin/env bash
# vanilla-join.sh - Join a running server with the real, unmodded Minecraft client.
#
# Context: template-only (smoke-test-26.yml and dep-review.yml run it; not in
# MANIFEST). The gate for RB-02: a vanilla client must join the 26.3 server and
# stay. Fabric's registry sync disconnects a vanilla client when a mod adds a
# synced registry entry without Polymer, and nothing short of a real client
# shows it. The client comes from Mojang's version manifest: the version JSON,
# client jar, libraries and asset index are each checked against their sha1,
# and cached under --cache by version. It runs under Xvfb with Mesa's software
# GL (LIBGL_ALWAYS_SOFTWARE=1), offline, as --name, straight into the server
# with --quickPlayMultiplayer. The server must run offline-mode.
#
# Pass: the server log shows "<name> joined the game", then neither the log
# nor the client reports a disconnect, kick or crash for --hold seconds. One
# screenshot (F2 through xdotool, else the X root window through ImageMagick)
# lands in --out. Fail: the reason (the server's lost-connection line, the
# client's disconnect screen text, or the timeout) goes to --out/summary.md,
# which CI appends to the job summary.
#
# Usage:
#   scripts/e2e/vanilla-join.sh [--version V] [--server host:port] [--name N]
#       [--hold SECONDS] [--join-timeout SECONDS] [--cache DIR] [--out DIR]
#       [--container mc] [--during 'command run once joined']
#
#   --version       default: mc_version (MC_VERSION_OVERRIDE, else .minecraft-version)
#   --server        default 127.0.0.1:25577 (docker-compose.yml's SERVER_PORT)
#   --hold          default 60
#   --join-timeout  default 600: software GL takes minutes to reach the title screen
#   --during        runs after the join, while the client stays connected (the
#                   admin parity check with --player uses it)
#
# Gotchas:
#   - Linux only (Xvfb with GLX, Mesa). The client renders through SDL3 with
#     an OpenGL or a Vulkan backend; both failing ends the run with the reason. Java comes from JAVA_HOME or PATH and must be
#     the version JSON's javaVersion.majorVersion.
#   - Sound objects are skipped (most of the asset bytes, and Xvfb has no
#     audio); set VANILLA_JOIN_SOUNDS=1 to fetch them.
#   - The server log is read with `docker logs <container>` since the client
#     started; a container name other than mc needs --container.
#   - Bounded throughout: every download has a timeout and every wait a limit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/../lib.sh"

VERSION=""
SERVER="127.0.0.1:25577"
NAME="VanillaGate"
HOLD=60
JOIN_TIMEOUT=600
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/vanilla-client"
OUT="vanilla-join"
CONTAINER="mc"
DURING=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2" ;;
    --server) SERVER="$2" ;;
    --name) NAME="$2" ;;
    --hold) HOLD="$2" ;;
    --join-timeout) JOIN_TIMEOUT="$2" ;;
    --cache) CACHE="$2" ;;
    --out) OUT="$2" ;;
    --container) CONTAINER="$2" ;;
    --during) DURING="$2" ;;
    *)
      sed -n '/^# Usage:/,/^# Gotchas:/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2
      exit 2
      ;;
  esac
  shift 2
done
[[ -n "$VERSION" ]] || VERSION="$(mc_version)"
mkdir -p "$OUT" "$CACHE"
OUT="$(cd "$OUT" && pwd)"
SUMMARY="$OUT/summary.md"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
MANIFEST_URL="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

fail() {
  {
    echo "### Vanilla $VERSION client join: FAIL"
    echo ""
    echo "$1"
  } > "$SUMMARY"
  echo "FAIL: $1" >&2
  exit 1
}

# fetch <url> <sha1> <dest> - download unless dest already has the sha1.
fetch() {
  local url="$1" sha="$2" dest="$3"
  if [[ -f "$dest" ]] && [[ "$(sha1sum "$dest" | cut -d' ' -f1)" == "$sha" ]]; then return 0; fi
  mkdir -p "$(dirname "$dest")"
  curl -fsSL --retry 3 --max-time 300 -o "$dest.part" "$url" || fail "download failed: $url"
  [[ "$(sha1sum "$dest.part" | cut -d' ' -f1)" == "$sha" ]] || fail "sha1 mismatch: $url"
  mv "$dest.part" "$dest"
}

echo "==> Vanilla $VERSION client (cache $CACHE)"
VDIR="$CACHE/$VERSION"
mkdir -p "$VDIR"
read -r VURL VSHA < <(curl -fsSL --max-time 60 "$MANIFEST_URL" | python3 -c '
import json, sys
v = [x for x in json.load(sys.stdin)["versions"] if x["id"] == sys.argv[1]]
print(v[0]["url"], v[0]["sha1"]) if v else print("- -")' "$VERSION") || fail "the version manifest is unreachable"
[[ "$VURL" != "-" ]] || fail "Mojang's manifest has no version $VERSION"
fetch "$VURL" "$VSHA" "$VDIR/version.json"

# One line per file: url sha1 path, plus the facts the launch needs.
python3 - "$VDIR/version.json" "$VDIR" "${VANILLA_JOIN_SOUNDS:-0}" > "$VDIR/files.txt" << 'PY'
import json, sys
v = json.load(open(sys.argv[1]))
vdir = sys.argv[2]
def allowed(rules):
    ok = not rules
    for r in rules or []:
        if "os" in r and r["os"].get("name") not in (None, "linux"):
            continue
        if "features" in r:
            continue
        ok = r["action"] == "allow"
    return ok
c = v["downloads"]["client"]
print("FILE", c["url"], c["sha1"], f"{vdir}/client.jar")
for lib in v["libraries"]:
    if not allowed(lib.get("rules")):
        continue
    a = lib.get("downloads", {}).get("artifact")
    if a:
        print("LIB", a["url"], a["sha1"], f"{vdir}/libraries/{a['path']}")
ai = v["assetIndex"]
print("INDEX", ai["url"], ai["sha1"], f"{vdir}/assets/indexes/{ai['id']}.json")
print("META", v["mainClass"], ai["id"], v.get("javaVersion", {}).get("majorVersion", 0))
PY
read -r _ MAIN_CLASS ASSET_ID JAVA_MAJOR < <(grep '^META ' "$VDIR/files.txt")
have_java="$("$JAVA" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
[[ "${have_java:-0}" -ge "$JAVA_MAJOR" ]] || fail "the client needs Java $JAVA_MAJOR; $JAVA is ${have_java:-missing}"

CP=""
while read -r kind url sha dest; do
  case "$kind" in
    FILE | LIB | INDEX) fetch "$url" "$sha" "$dest" ;;
    *) continue ;;
  esac
  [[ "$kind" == INDEX ]] || CP="${CP:+$CP:}$dest"
done < "$VDIR/files.txt"

python3 - "$VDIR/assets/indexes/$ASSET_ID.json" "${VANILLA_JOIN_SOUNDS:-0}" > "$VDIR/assets.txt" << 'PY'
import json, sys
for name, o in json.load(open(sys.argv[1]))["objects"].items():
    if sys.argv[2] != "1" and (name.startswith("minecraft/sounds/") or name.endswith(".ogg")):
        continue
    h = o["hash"]
    print(f"https://resources.download.minecraft.net/{h[:2]}/{h}", h, f"{h[:2]}/{h}")
PY
echo "  assets: $(wc -l < "$VDIR/assets.txt" | tr -d ' ') objects"
missing="$VDIR/assets-missing.txt"
: > "$missing"
while read -r url sha rel; do
  [[ -f "$VDIR/assets/objects/$rel" ]] || echo "$url $sha $rel" >> "$missing"
done < "$VDIR/assets.txt"
if [[ -s "$missing" ]]; then
  echo "  fetching $(wc -l < "$missing" | tr -d ' ') asset objects"
  # shellcheck disable=SC2016 # $0..$3 belong to the inner shell
  xargs -P 16 -n 4 sh -c 'mkdir -p "$(dirname "$3/$2")" && curl -fsS --retry 3 --max-time 120 -o "$3/$2" "$0" && [ "$(sha1sum "$3/$2" | cut -d" " -f1)" = "$1" ]' \
    < <(sed "s|\$| $VDIR/assets/objects|" "$missing") || fail "an asset object failed to download or verify"
fi

echo "==> Launching as $NAME -> $SERVER"
GAME="$OUT/game"
mkdir -p "$GAME"
printf 'onboardAccessibility:false\npauseOnLostFocus:false\nsoundCategory_master:0.0\nnarrator:0\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\n' > "$GAME/options.txt"
DISPLAY_NUM=":$((90 + RANDOM % 9))"
# GLX and RENDER give SDL a GL visual; 24-bit depth is what Mesa's llvmpipe offers.
Xvfb "$DISPLAY_NUM" -screen 0 854x480x24 +extension GLX +render -nolisten tcp > "$OUT/xvfb.log" 2>&1 &
XVFB_PID=$!
CLIENT_PID=""
cleanup() {
  [[ -z "$CLIENT_PID" ]] || kill "$CLIENT_PID" 2> /dev/null || true
  kill "$XVFB_PID" 2> /dev/null || true
}
trap cleanup EXIT
since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
UUID="$(python3 -c 'import hashlib,sys,uuid; h=bytearray(hashlib.md5(("OfflinePlayer:"+sys.argv[1]).encode()).digest()); h[6]=h[6]&0x0f|0x30; h[8]=h[8]&0x3f|0x80; print(uuid.UUID(bytes=bytes(h)))' "$NAME")"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$(mktemp -d)}"
chmod 700 "$XDG_RUNTIME_DIR"
DISPLAY="$DISPLAY_NUM" LIBGL_ALWAYS_SOFTWARE=1 "$JAVA" -Xmx2G -cp "$CP" "$MAIN_CLASS" \
  --username "$NAME" --uuid "$UUID" --accessToken 0 --userType legacy --version "$VERSION" \
  --versionType release --gameDir "$GAME" --assetsDir "$VDIR/assets" --assetIndex "$ASSET_ID" \
  --width 854 --height 480 --quickPlayMultiplayer "$SERVER" > "$OUT/client.log" 2>&1 &
CLIENT_PID=$!

server_log() { docker logs "$CONTAINER" --since "$since" 2>&1 || true; }
lost_re="$NAME( \([0-9a-f-]+\))? (lost connection|left the game)|Disconnecting .*$NAME|$NAME .*(was kicked|kicked from)"
client_reason() {
  grep -A8 'Client disconnected with reason' "$OUT/client.log" | grep -v '^[[:space:]]*$' | tr '\n' ' '
  grep -E 'Disconnected|Connection lost|disconnect|Failed to connect|Kicked|registry|Registry' "$OUT/client.log" | tail -5
}

waited=0
until server_log | grep -q "$NAME joined the game"; do
  kill -0 "$CLIENT_PID" 2> /dev/null || fail "the client exited before joining: $(tail -20 "$OUT/client.log")"
  if grep -q 'Client disconnected with reason' "$OUT/client.log"; then
    fail "the server refused the client: $(client_reason)"
  fi
  if grep -q 'Failed to create backend OpenGL' "$OUT/client.log" && grep -q 'Failed to create backend Vulkan' "$OUT/client.log"; then
    fail "the client found no rendering backend: $(grep -A1 'Failed to create backend' "$OUT/client.log" | grep -v '^--' | tr '\n' ' ')"
  fi
  if server_log | grep -qE "$lost_re"; then
    fail "the server dropped the client before it joined: $(server_log | grep -E "$lost_re" | tail -3) $(client_reason)"
  fi
  [[ $waited -lt $JOIN_TIMEOUT ]] || fail "no \"$NAME joined the game\" within ${JOIN_TIMEOUT}s. Client log tail: $(tail -15 "$OUT/client.log")"
  sleep 5
  waited=$((waited + 5))
done
echo "  joined after ${waited}s; holding ${HOLD}s"
if [[ -n "$DURING" ]]; then
  bash -c "$DURING" > "$OUT/during.log" 2>&1 || echo "  the --during command failed (see $OUT/during.log)"
fi

held=0
shot=0
while [[ $held -lt $HOLD ]]; do
  sleep 5
  held=$((held + 5))
  if server_log | grep -qE "$lost_re"; then
    fail "the server dropped the client after ${held}s: $(server_log | grep -E "$lost_re" | tail -3). Client: $(client_reason)"
  fi
  kill -0 "$CLIENT_PID" 2> /dev/null || fail "the client exited after ${held}s: $(client_reason) $(tail -10 "$OUT/client.log")"
  if [[ $shot -eq 0 && $held -ge 30 ]]; then
    shot=1
    if command -v xdotool > /dev/null; then
      DISPLAY="$DISPLAY_NUM" xdotool key F2 || true
      sleep 3
    fi
    ls "$GAME"/screenshots/*.png > /dev/null 2>&1 && cp "$(ls -t "$GAME"/screenshots/*.png | head -1)" "$OUT/screenshot.png"
    if [[ ! -f "$OUT/screenshot.png" ]] && command -v import > /dev/null; then
      DISPLAY="$DISPLAY_NUM" import -window root "$OUT/screenshot.png" || true
    fi
  fi
done

{
  echo "### Vanilla $VERSION client join: PASS"
  echo ""
  echo "\`$NAME\` joined $SERVER after ${waited}s and stayed connected for ${HOLD}s."
  [[ -f "$OUT/screenshot.png" ]] && echo "Screenshot: \`screenshot.png\` in the artifact." || echo "No screenshot (neither xdotool nor ImageMagick on PATH)."
} > "$SUMMARY"
cat "$SUMMARY"
