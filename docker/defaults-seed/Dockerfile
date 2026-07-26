FROM alpine:3.24.1

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Init container: seeds default config + overlay merge into shared volumes" \
      org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache bash rsync python3

COPY config/modrinth-mods.txt /defaults/modrinth-mods.txt
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
COPY config/custom-dimensions/settings.json    /defaults/config/custom-dimensions/settings.json
COPY config/custom-dimensions/dimensions/      /defaults/config/custom-dimensions/dimensions/
# Noise-placement data. The MOD reads its own jar-baked copies of both, so
# these are not the runtime source — they ship because the seed roller reads
# them, and in consumer mode the roller resolves config from
# data/config/custom-dimensions/ (roll-all.sh) rather than a repo checkout.
COPY config/custom-dimensions/structure-groups.json         /defaults/config/custom-dimensions/structure-groups.json
COPY config/custom-dimensions/structure-type-defaults.json  /defaults/config/custom-dimensions/structure-type-defaults.json

COPY modpack/adventure.mrpack.json /defaults/config/modpack-manifest.json

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
COPY config/openpartiesandclaims/      /defaults/config/openpartiesandclaims/
COPY config/boring_default_game_rules/ /defaults/config/boring_default_game_rules/
COPY config/voicechat/                 /defaults/config/voicechat/
COPY config/essentialcommands/         /defaults/config/essentialcommands/
COPY config/starterkit/                /defaults/config/starterkit/
COPY config/servercore/                /defaults/config/servercore/
COPY config/bountiful/                 /defaults/config/bountiful/

COPY docker/defaults-seed/seed.sh /seed.sh
COPY docker/defaults-seed/resolve-mods.py /resolve-mods.py
RUN chmod +x /seed.sh /resolve-mods.py

ENTRYPOINT ["/seed.sh"]
