#!/usr/bin/env bash
# ct.sh <run> <slug> [set] [as]: check-targets for a dossier from runs/<run>/full/<as or slug>; prints pass/total and misses.
CT=/home/user/minecraft-server-template/mods/custom-dimensions-client-26/scripts/check-targets.py
R=$1; S=$2; SET=${3:-w10t}; AS=${4:-$2}
T=/home/user/minecraft-server-template/mods/custom-dimensions-26/fidelity/targets/$SET/$S.json
[ -f "$T" ] || T=/tmp/claude-0/w10c1p/targets/$S.json
python3 $CT /tmp/claude-0/w10c1p/runs/$R/full/$AS/dossier.json "$T" | grep -E "^FAIL|pass$" | cut -c1-220
