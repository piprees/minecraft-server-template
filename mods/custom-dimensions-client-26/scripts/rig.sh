#!/usr/bin/env bash
# rig.sh — the companion client's end-to-end rig: screenshots of a lit portal from a real 26.3 client.
#
# Context: Linux only (Xvfb, Mesa, GNU timeout). Template-only development tooling for mods/custom-dimensions-client-26 (not in the stack bundle).
#   Boots the mods/custom-dimensions-26 dev server (runServer, offline, level-seed 20261022) unless one already
#   listens on the port, then runs the dev client once per variant under xvfb-run with Mesa llvmpipe. The client's
#   rig hook (src/main/java/.../dev/Rig.java) builds and lights a basalt frame into adventure:the_basalt_spires,
#   stands in front of it, screenshots it near and far, logs the feed counts and quits.
#   Variants: a = companion off (what an unmodded client sees), b = companion on, c = companion with Iris and the
#   Complementary Reimagined shader pack, d = companion with Distant Horizons.
#
# Usage: scripts/rig.sh [--port N] [--out DIR] [--non-interactive] [variant ...]   (default variants: a b c d)
#   Screenshots land in DIR (default /tmp/claude-0/m8-screens) as <variant>-near.png and <variant>-far.png;
#   each client log is DIR/<variant>.log, the server log DIR/server.log.
#
# Gotchas:
#   - Xvfb's GLX offers no sRGB-capable visual and the 26.3 client asks SDL for one, so the GL backend fails with
#     "Couldn't find matching GLX visual". rig/srgbshim.c, built here with gcc, stands in for libGL
#     (-Dorg.lwjgl.opengl.libname) and drops that one attribute. Every variant runs on OpenGL so Iris's path matches.
#   - The client run directory (run/) is shared by the variants; options.txt is set to skip the first-run screens.
#   - The shader pack is fetched from Modrinth for local testing only and never committed (its licence allows
#     personal use, not redistribution).
#   - Idempotent: re-running relights the frame (the fill breaks the previous portal) and overwrites the shots.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_PROJECT="$HERE/../custom-dimensions-26"
SERVER_RUN="$SERVER_PROJECT/server/run"
PORT=25665
OUT=/tmp/claude-0/m8-screens
VARIANTS=""
SHADER_VERSION=Bqen1mJX

while [ $# -gt 0 ]; do
	case "$1" in
		--port) PORT="$2"; shift 2 ;;
		--out) OUT="$2"; shift 2 ;;
		--non-interactive) shift ;;
		a|b|c|d) VARIANTS="$VARIANTS $1"; shift ;;
		*) echo "unknown argument: $1" >&2; exit 2 ;;
	esac
done
[ -n "$VARIANTS" ] || VARIANTS="a b c d"
mkdir -p "$OUT/screenshots" "$HERE/build/rig"

log() { printf '%s rig: %s\n' "$(date +%H:%M:%S)" "$*"; }

backup_if_changed() { # file, new content
	if [ -f "$1" ] && [ "$(cat "$1")" != "$2" ]; then
		cp "$1" "$1.bak.$(date +%Y%m%d%H%M%S)"
	fi
	printf '%s\n' "$2" > "$1"
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }

SHIM="$HERE/build/rig/libsrgbshim.so"
if [ ! -f "$SHIM" ] || [ "$HERE/rig/srgbshim.c" -nt "$SHIM" ]; then
	gcc -shared -fPIC -O2 "$HERE/rig/srgbshim.c" -o "$SHIM" -Wl,--no-as-needed /usr/lib/x86_64-linux-gnu/libGL.so.1 -ldl
fi

SERVER_PGID=""
if ! port_open; then
	mkdir -p "$SERVER_RUN"
	backup_if_changed "$SERVER_RUN/eula.txt" "eula=true"
	backup_if_changed "$SERVER_RUN/server.properties" "server-port=$PORT
online-mode=false
enforce-secure-profile=false
level-seed=20261022
spawn-protection=0
view-distance=8
simulation-distance=6
pause-when-empty-seconds=0
motd=customdimensions rig"
	# The offline UUID of the player Rig: UUID v3 of "OfflinePlayer:Rig".
	backup_if_changed "$SERVER_RUN/ops.json" '[{"uuid":"b4b49ac1-eec7-3c0f-a8fc-f6d9f5bb5548","name":"Rig","level":4,"bypassesPlayerLimit":false}]'
	log "starting the dev server on $PORT"
	(cd "$SERVER_PROJECT" && setsid ./gradlew :server:runServer --offline > "$OUT/server.log" 2>&1 &)
	sleep 2
	SERVER_PGID="$(pgrep -f 'gradlew :server:runServer' | head -1 || true)"
	i=0
	while [ $i -lt 90 ] && ! grep -q 'Done (' "$OUT/server.log"; do sleep 5; i=$((i + 1)); done
	grep -q 'Done (' "$OUT/server.log" || { log "the server did not start; see $OUT/server.log"; exit 1; }
fi

RUN="$HERE/run"
mkdir -p "$RUN/config" "$RUN/shaderpacks"
if [ -f "$RUN/options.txt" ]; then
	sed -i.bak -e 's/^renderDistance:.*/renderDistance:8/' -e 's/^onboardAccessibility:.*/onboardAccessibility:false/' \
		-e 's/^skipMultiplayerWarning:.*/skipMultiplayerWarning:true/' -e 's/^joinedFirstServer:.*/joinedFirstServer:true/' \
		-e 's/^tutorialStep:.*/tutorialStep:none/' -e 's/^menuBackgroundBlurriness:.*/menuBackgroundBlurriness:0/' \
		-e 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' "$RUN/options.txt"
else
	printf '%s\n' 'renderDistance:8' 'onboardAccessibility:false' 'skipMultiplayerWarning:true' 'joinedFirstServer:true' \
		'tutorialStep:none' 'menuBackgroundBlurriness:0' 'pauseOnLostFocus:false' > "$RUN/options.txt"
fi

for v in $VARIANTS; do
	extra=""
	label=""
	case "$v" in
		a) label=a-vanilla; extra="-Pcompanion=off" ;;
		b) label=b-companion ;;
		c) label=c-iris
			extra="-Piris"
			pack="$(curl -sS --max-time 30 "https://api.modrinth.com/v2/version/$SHADER_VERSION" \
				| python3 -c 'import json,sys; f=json.load(sys.stdin)["files"][0]; print(f["filename"], f["url"])')"
			name="${pack%% *}"
			url="${pack#* }"
			[ -f "$RUN/shaderpacks/$name" ] || curl -sS --max-time 300 -o "$RUN/shaderpacks/$name" "$url"
			backup_if_changed "$RUN/config/iris.properties" "enableShaders=true
shaderPack=$name" ;;
		d) label=d-dh; extra="-Pdh" ;;
	esac
	if [ "$v" != c ] && [ -f "$RUN/config/iris.properties" ]; then
		backup_if_changed "$RUN/config/iris.properties" "enableShaders=false"
	fi
	log "variant $label"
	# shellcheck disable=SC2086
	(cd "$HERE" && LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}" \
		timeout 900 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient -Prig="$PORT" \
		-PrigGl="$SHIM" -PrigShots="$OUT" -PrigLabel="$label" $extra > "$OUT/$v.log" 2>&1) || true
	if grep -q 'rig: done' "$OUT/$v.log"; then
		log "$(grep -o "done.*" "$OUT/$v.log" | tail -1)"
		{ grep -o 'companion-o[nf]*: .*' "$OUT/$v.log" || true; } | sort -u | while read -r line; do log "  $line"; done
	else
		log "variant $label did not finish; see $OUT/$v.log"
	fi
done

if [ -n "$SERVER_PGID" ]; then
	log "stopping the dev server"
	kill -- "-$SERVER_PGID" 2>/dev/null || kill "$SERVER_PGID" 2>/dev/null || true
fi
ls -1 "$OUT/screenshots"
