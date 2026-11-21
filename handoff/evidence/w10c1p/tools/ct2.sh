#!/usr/bin/env bash
# ct2.sh <variant> <slug>: both target sets for the live survey dir's dossier of <variant>; pass lines and misses.
S=/home/user/minecraft-server-template/mods/custom-dimensions-26/server/run/customdim-survey
for set in w10t w10c; do echo "  [$set]"; python3 /home/user/minecraft-server-template/mods/custom-dimensions-client-26/scripts/check-targets.py $S/$1/dossier.json /home/user/minecraft-server-template/mods/custom-dimensions-26/fidelity/targets/$set/$2.json | grep -E "^FAIL|pass$" | cut -c1-140; done
