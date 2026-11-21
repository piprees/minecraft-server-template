#!/usr/bin/env bash
# vanilla-boot-check.sh — boot the compiled datapack on a vanilla server, no mods.
#
# Purpose: proves the pack the compiler writes is an ordinary datapack. A
#   vanilla server of `minecraft_version` (gradle.properties) creates a fresh
#   world with build/datapack/customdimensions/ in world/datapacks/, and the
#   check fails if the log shows a datapack or registry load error, the pack is
#   not enabled, or any base world (minecraft:overworld, minecraft:the_nether,
#   minecraft:the_end, adventure:the_aviary) cannot be entered once the server
#   is up.
#
# Context: template-only tooling for mods/custom-dimensions-26 (M1). Inputs
#   come from Gradle: the server jar from `:compiler:vanillaData`, the pack
#   from `:compiler:compileDatapack`. Needs Java 25 (JAVA_HOME or java on PATH).
#
# Usage: scripts/vanilla-boot-check.sh [--keep]
#   --keep   leave the temporary server directory in place and print its path
#
# Gotchas:
#   - Waits at most 300 s for "Done (" and 60 s for the stop; it never loops
#     without a bound. A slow machine fails the check rather than hanging.
#   - The world is new every run, so world-creation paths are what it tests;
#     the lazy level stems under data/customdimensions/ are invisible to
#     vanilla by design and are checked by the mod's self-test instead.
#   - The server listens on a random high port so it can run beside a local
#     stack.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keep=false
[[ "${1:-}" == "--keep" ]] && keep=true

die() { echo "vanilla-boot-check: $*" >&2; exit 1; }

version="$(sed -n 's/^minecraft_version=//p' "$here/gradle.properties")"
jar="$here/compiler/build/vanilla/$version/server.jar"
pack="$here/build/datapack/customdimensions"
namespace="$(sed -n 's/.*"namespace": *"\([^"]*\)".*/\1/p' "$here/definitions/settings.json")"
[[ -f "$jar" ]] || die "no server jar at $jar; run ./gradlew :compiler:vanillaData"
[[ -f "$pack/pack.mcmeta" ]] || die "no pack at $pack; run ./gradlew :compiler:compileDatapack"

java_bin="java"
[[ -n "${JAVA_HOME:-}" ]] && java_bin="$JAVA_HOME/bin/java"
command -v "$java_bin" >/dev/null || die "java not found; set JAVA_HOME to a Java 25 JDK"

work="$(mktemp -d "${TMPDIR:-/tmp}/vanilla-boot-check.XXXXXX")"
log="$work/server.log"
pid=""
cleanup() {
	exec 3>&- 2>/dev/null || true
	if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
		kill "$pid" 2>/dev/null || true
	fi
	if $keep; then
		echo "vanilla-boot-check: server directory kept at $work"
	else
		rm -rf "$work"
	fi
}
trap cleanup EXIT

mkdir -p "$work/world/datapacks"
cp -R "$pack" "$work/world/datapacks/customdimensions"
echo 'eula=true' > "$work/eula.txt"
port=$((30000 + RANDOM % 20000))
printf '%s\n' 'online-mode=false' 'max-tick-time=-1' 'view-distance=4' 'simulation-distance=4' \
	'level-name=world' "server-port=$port" 'enable-rcon=false' 'enable-query=false' > "$work/server.properties"

mkfifo "$work/stdin"
exec 3<>"$work/stdin"
(cd "$work" && exec "$java_bin" -Xmx2G -jar "$jar" nogui <"$work/stdin" >"$log" 2>&1) &
pid=$!

started=false
for _ in $(seq 1 300); do
	if grep -q 'Done (' "$log" 2>/dev/null; then
		started=true
		break
	fi
	kill -0 "$pid" 2>/dev/null || break
	sleep 1
done

base_worlds="minecraft:overworld minecraft:the_nether minecraft:the_end ${namespace}:the_aviary"
if $started; then
	echo 'datapack list enabled' >&3
	for world in $base_worlds; do
		echo "execute in $world run say customdim-entered $world" >&3
	done
	echo 'stop' >&3
	for _ in $(seq 1 60); do
		kill -0 "$pid" 2>/dev/null || break
		sleep 1
	done
fi
if kill -0 "$pid" 2>/dev/null; then
	kill "$pid" 2>/dev/null || true
fi
wait "$pid" 2>/dev/null || true
pid=""

failures=()
$started || failures+=("the server exited or timed out before \"Done (\" (cap 300 s)")
if grep -nE 'Registry loading errors|Failed to load datapacks|Failed to parse|Couldn.t parse|Failed to decode|Unknown dimension|Exception|\[[^]]*ERROR\]' "$log" >"$work/errors.txt"; then
	failures+=("the log has load errors:"$'\n'"$(head -40 "$work/errors.txt")")
fi
grep -q 'file/customdimensions' "$log" || failures+=("the pack file/customdimensions is not listed as enabled")
for world in $base_worlds; do
	grep -q "customdim-entered $world" "$log" || failures+=("base world $world could not be entered")
done

echo "--- last 15 log lines ---"
tail -15 "$log"
echo "-------------------------"
if ((${#failures[@]} > 0)); then
	printf 'FAIL %s\n' "${failures[@]}"
	exit 1
fi
echo "PASS vanilla $version booted the compiled pack; base worlds entered: $base_worlds"
