#!/usr/bin/env bash
# companion-pack.sh - Build a client pack holding only the companion and its library.
#
# Context: template-only (not in MANIFEST). The e2e rig's real-client path on
# 26.x: vanilla-join.sh --mrpack runs it, so the client is the unmodded game
# plus mods/custom-dimensions-client-26 and Fabric API, nothing else. The full
# pack (./dev pack) carries Iris, Sodium and Distant Horizons, which software
# GL under Xvfb draws too slowly to walk a portal in. The loader comes from
# minecraft-versions.tsv, Fabric API from the version's mod list (its pinned
# version id, resolved through the Modrinth API with its sha1 and sha512), the
# companion jar from scripts/local-mods.sh locate client.
#
# Usage:
#   scripts/e2e/companion-pack.sh OUT.mrpack
#
# Gotchas:
#   - Build the companion first (cd mods/custom-dimensions-client-26 && ./gradlew build).
#   - The version is mc_version: MC_VERSION_OVERRIDE, else .minecraft-version.
#   - Needs curl, python3 and zip; one Modrinth API call.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib.sh
source "$ROOT/scripts/lib.sh"
export_mc_version

OUT="${1:?usage: companion-pack.sh OUT.mrpack}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

list="$(mc_mod_list "$ROOT" "$MC_VERSION")"
api_version="$(grep -E '^fabric-api:' "$list" | head -1 | cut -d: -f2 | sed 's/[[:space:]#?].*//')"
[[ -n "$api_version" ]] || die "no fabric-api row in $list"
jar="$("$ROOT/scripts/local-mods.sh" locate client | cut -d'|' -f2 | head -1)"
[[ -f "$jar" ]] || die "no built companion jar for $MC_VERSION: build mods/custom-dimensions-client-26 first"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/overrides/mods"
cp "$jar" "$work/overrides/mods/customdimensionsclient.jar"
curl -fsS --max-time 30 -H 'User-Agent: piprees/minecraft-server-template (e2e companion pack)' \
  "https://api.modrinth.com/v2/version/$api_version" > "$work/api.json"
python3 - "$work" "$MC_VERSION" "$FABRIC_LOADER_VERSION" << 'EOF'
import json, sys
work, mc, loader = sys.argv[1:4]
v = json.load(open(f"{work}/api.json"))
f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
index = {
    "formatVersion": 1,
    "game": "minecraft",
    "versionId": "e2e-companion",
    "name": "e2e companion",
    "files": [{
        "path": f"mods/{f['filename']}",
        "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
        "env": {"client": "required", "server": "required"},
        "downloads": [f["url"]],
        "fileSize": f["size"],
    }],
    "dependencies": {"minecraft": mc, "fabric-loader": loader},
}
json.dump(index, open(f"{work}/modrinth.index.json", "w"), indent=1)
EOF
rm -f "$work/api.json" "$OUT"
(cd "$work" && zip -qr "$OUT" modrinth.index.json overrides)
echo "companion pack: $OUT (Minecraft $MC_VERSION, loader $FABRIC_LOADER_VERSION, fabric-api $api_version, $(basename "$jar"))"
