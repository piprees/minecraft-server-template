FROM alpine:3.24.1

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Client modpack builder: .mrpack + packwiz + Prism instance" \
      org.opencontainers.image.licenses="MIT"

RUN apk upgrade --no-cache && apk add --no-cache bash curl jq zip python3

# The mod list follows .minecraft-version: modrinth-mods-<version>.txt when it
# exists, else modrinth-mods.txt (mc_mod_list in scripts/lib.sh).
COPY .minecraft-version /defaults/.minecraft-version
COPY config/modrinth-mods*.txt /tmp/mod-lists/
RUN v="$(cat /defaults/.minecraft-version)" \
 && f="/tmp/mod-lists/modrinth-mods-$v.txt" \
 && { [ -f "$f" ] || f=/tmp/mod-lists/modrinth-mods.txt; } \
 && cp "$f" /defaults/modrinth-mods.txt \
 && rm -rf /tmp/mod-lists
# The manifest follows .minecraft-version the same way (mc_manifest).
COPY modpack/adventure*.mrpack.json /tmp/manifests/
RUN v="$(cat /defaults/.minecraft-version)" \
 && f="/tmp/manifests/adventure-$v.mrpack.json" \
 && { [ -f "$f" ] || f=/tmp/manifests/adventure.mrpack.json; } \
 && mkdir -p "$(dirname /defaults/manifest.json)" \
 && cp "$f" /defaults/manifest.json \
 && rm -rf /tmp/manifests
COPY modpack/overrides/            /defaults/overrides/
# The version's pack overrides (modpack/versions/<version>/) lay over the base
# ones here, before the consumer overlay does at run time (entrypoint.sh).
COPY scripts/lib.sh                /app/lib.sh
COPY modpack/versions/             /tmp/pack-versions/
RUN bash -c '. /app/lib.sh && pack_version_overrides /tmp/pack-versions /defaults/overrides "$(cat /defaults/.minecraft-version)"' \
 && rm -rf /tmp/pack-versions
COPY modpack/template/             /defaults/template/
COPY assets/                       /defaults/assets/

COPY scripts/build-modpack.sh      /app/build-modpack.sh
COPY scripts/check-pack-coherence.py /app/check-pack-coherence.py
COPY scripts/check-client-parity.py /app/check-client-parity.py
COPY scripts/modrinth-api.py       /app/modrinth-api.py
COPY scripts/pack_files.py         /app/pack_files.py
COPY docker/modpack-builder/entrypoint.sh    /app/entrypoint.sh
COPY docker/modpack-builder/merge-manifest.py /app/merge-manifest.py
COPY docker/modpack-builder/strip-removed-mods.py /app/strip-removed-mods.py

RUN chmod +x /app/*.sh /app/*.py

WORKDIR /work
ENTRYPOINT ["/app/entrypoint.sh"]
