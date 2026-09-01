FROM alpine:3.24.1

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Client modpack builder: .mrpack + packwiz + Prism instance" \
      org.opencontainers.image.licenses="MIT"

RUN apk upgrade --no-cache && apk add --no-cache bash curl jq zip python3

COPY modpack/adventure.mrpack.json /defaults/manifest.json
COPY config/modrinth-mods.txt      /defaults/modrinth-mods.txt
COPY modpack/overrides/            /defaults/overrides/
COPY modpack/template/             /defaults/template/
COPY assets/                       /defaults/assets/

COPY scripts/build-modpack.sh      /app/build-modpack.sh
COPY scripts/check-pack-coherence.py /app/check-pack-coherence.py
COPY scripts/check-client-parity.py /app/check-client-parity.py
COPY scripts/modrinth-api.py       /app/modrinth-api.py
COPY scripts/lib.sh                /app/lib.sh
COPY docker/modpack-builder/entrypoint.sh    /app/entrypoint.sh
COPY docker/modpack-builder/merge-manifest.py /app/merge-manifest.py
COPY docker/modpack-builder/strip-removed-mods.py /app/strip-removed-mods.py

RUN chmod +x /app/*.sh /app/*.py

WORKDIR /work
ENTRYPOINT ["/app/entrypoint.sh"]
