FROM alpine:3.24

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Init container: seeds default config + overlay merge into shared volumes" \
      org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache bash rsync

COPY config/modrinth-mods.txt /defaults/modrinth-mods.txt

COPY config/log4j2-adventure.xml /defaults/config/log4j2-adventure.xml
COPY config/messages.json        /defaults/config/messages.json
COPY config/starterkit.json5     /defaults/config/starterkit.json5

COPY config/uptime-kuma/  /defaults/config/uptime-kuma/
COPY config/nginx/        /defaults/config/nginx/
COPY config/datapacks/    /defaults/config/datapacks/

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
COPY config/bluemap/                   /defaults/config/bluemap/

COPY docker/defaults-seed/seed.sh /seed.sh
RUN chmod +x /seed.sh

ENTRYPOINT ["/seed.sh"]
