FROM alpine:3.24.1

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Init container: seeds default config + overlay merge into shared volumes" \
      org.opencontainers.image.licenses="MIT"

RUN apk upgrade --no-cache && apk add --no-cache bash rsync python3

# The mod list follows .minecraft-version: modrinth-mods-<version>.txt when it
# exists, else modrinth-mods.txt (mc_mod_list in scripts/lib.sh). The
# MC_VERSION_OVERRIDE build argument replaces the file's version, as the
# environment variable does in lib.sh; empty, it changes nothing.
ARG MC_VERSION_OVERRIDE=
COPY .minecraft-version /defaults/.minecraft-version
RUN [ -z "$MC_VERSION_OVERRIDE" ] || echo "$MC_VERSION_OVERRIDE" > /defaults/.minecraft-version
COPY config/modrinth-mods*.txt /tmp/mod-lists/
RUN v="$(cat /defaults/.minecraft-version)" \
 && f="/tmp/mod-lists/modrinth-mods-$v.txt" \
 && { [ -f "$f" ] || f=/tmp/mod-lists/modrinth-mods.txt; } \
 && cp "$f" /defaults/modrinth-mods.txt \
 && rm -rf /tmp/mod-lists
# Committed resolve cache: known pins need zero Modrinth API calls
COPY config/modrinth-resolve-cache.json /defaults/modrinth-resolve-cache.json

COPY config/log4j2-adventure.xml /defaults/config/log4j2-adventure.xml
COPY config/tectonic.json        /defaults/config/tectonic.json
COPY config/messages.json        /defaults/config/messages.json
COPY config/starterkit.json5     /defaults/config/starterkit.json5
COPY config/update-check-mute.txt /defaults/config/update-check-mute.txt

COPY config/uptime-kuma/  /defaults/config/uptime-kuma/
COPY config/nginx/        /defaults/config/nginx/
COPY config/datapacks/    /defaults/config/datapacks/
# v4 per-dimension configs (custom-dimensions mod): settings.json + dimensions/
# extractors/ (authoring-only worldgen extraction data, ~2.6MB) is deliberately
# excluded — the mod never reads it, and deploy.sh/dev-up.sh already skip it
# when syncing data/config, so it would only ever bloat this image.
COPY config/custom-dimensions/settings.json      /defaults/config/custom-dimensions/settings.json
COPY config/custom-dimensions/display-names.json /defaults/config/custom-dimensions/display-names.json
COPY config/custom-dimensions/dimensions/        /defaults/config/custom-dimensions/dimensions/
# Noise-placement data. The MOD reads its own jar-baked copies of both, so
# these are not the runtime source — they ship because the seed roller reads
# them, and in consumer mode the roller resolves config from
# data/config/custom-dimensions/ (roll-all.sh) rather than a repo checkout.
COPY config/custom-dimensions/structure-groups.json         /defaults/config/custom-dimensions/structure-groups.json
COPY config/custom-dimensions/structure-type-defaults.json  /defaults/config/custom-dimensions/structure-type-defaults.json
# Measured climate windows. The mod reads these to map declared cells into the
# range a dimension's router actually samples; without the file every dimension
# keeps its cells as authored, across the whole climate space.
COPY config/custom-dimensions/climate-axes.json  /defaults/config/custom-dimensions/climate-axes.json

# The manifest follows .minecraft-version the same way (mc_manifest).
COPY modpack/adventure*.mrpack.json /tmp/manifests/
RUN v="$(cat /defaults/.minecraft-version)" \
 && f="/tmp/manifests/adventure-$v.mrpack.json" \
 && { [ -f "$f" ] || f=/tmp/manifests/adventure.mrpack.json; } \
 && mkdir -p "$(dirname /defaults/config/modpack-manifest.json)" \
 && cp "$f" /defaults/config/modpack-manifest.json \
 && rm -rf /tmp/manifests

COPY config/firespreadtweaks/          /defaults/config/firespreadtweaks/
COPY config/healingcampfire/           /defaults/config/healingcampfire/
COPY config/youritemsaresafe/          /defaults/config/youritemsaresafe/
COPY config/doubledoors/               /defaults/config/doubledoors/
COPY config/nutritiousmilk/            /defaults/config/nutritiousmilk/
COPY config/collective/                /defaults/config/collective/
COPY config/expanded_bow_enchanting/   /defaults/config/expanded_bow_enchanting/
COPY config/fallingtree/               /defaults/config/fallingtree/
COPY config/lootr/                     /defaults/config/lootr/
COPY config/groundclear/               /defaults/config/groundclear/
COPY config/nametagtweaks/             /defaults/config/nametagtweaks/
COPY config/betterdays/                /defaults/config/betterdays/
COPY config/betterfortresses-fabric-1_21.toml     /defaults/config/betterfortresses-fabric-1_21.toml
COPY config/betterjungletemples-fabric-1_21.toml  /defaults/config/betterjungletemples-fabric-1_21.toml
COPY config/bettermineshafts-fabric-1_21.toml     /defaults/config/bettermineshafts-fabric-1_21.toml
COPY config/betteroceanmonuments-fabric-1_21.toml /defaults/config/betteroceanmonuments-fabric-1_21.toml
COPY config/betterdeserttemples-fabric-1_21.toml  /defaults/config/betterdeserttemples-fabric-1_21.toml
COPY config/betterwitchhuts-fabric-1_21.toml      /defaults/config/betterwitchhuts-fabric-1_21.toml
COPY config/betterdungeons-fabric-1_21.toml       /defaults/config/betterdungeons-fabric-1_21.toml
COPY config/openpartiesandclaims/      /defaults/config/openpartiesandclaims/
COPY config/boring_default_game_rules/ /defaults/config/boring_default_game_rules/
COPY config/voicechat/                 /defaults/config/voicechat/
COPY config/essentialcommands/         /defaults/config/essentialcommands/
COPY config/starterkit/                /defaults/config/starterkit/
COPY config/servercore/                /defaults/config/servercore/
COPY config/bountiful/                 /defaults/config/bountiful/
# The version's config overlay (version_config_files in scripts/lib.sh), as
# deploy.sh applies it: config/versions/<version>/<path> replaces or adds
# /defaults/config/<path>. Configs of mods only that version's list carries
# (floodgate/, Geyser-Fabric/ on 26.3) live there alone.
COPY config/versions/ /tmp/config-versions/
RUN d="/tmp/config-versions/$(cat /defaults/.minecraft-version)" \
 && if [ -d "$d" ]; then \
      (cd "$d" && find . -type f) | while IFS= read -r f; do \
        mkdir -p "$(dirname "/defaults/config/$f")" && cp "$d/$f" "/defaults/config/$f"; \
      done; \
    fi \
 && rm -rf /tmp/config-versions

COPY docker/defaults-seed/seed.sh /seed.sh
COPY docker/defaults-seed/resolve-mods.py /resolve-mods.py
RUN chmod +x /seed.sh /resolve-mods.py

ENTRYPOINT ["/seed.sh"]
