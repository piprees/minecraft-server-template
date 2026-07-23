# unmined-render — scheduled static map renderer (uNmINeD CLI)
#
# Renders every world + custom dimension to a static web map (webp tiles +
# a self-contained OpenLayers viewer) on an interval. Output is plain files:
# ideal for long-lived edge caching, no runtime server component.
#
# The uNmINeD CLI is closed-source freeware ("free for personal use") with
# no versioned download URLs — the DEV channel URL always serves the latest
# build, so image builds are not bit-reproducible. The CLI version baked
# into a release image is whatever was current at build time; the running
# binary logs its exact version at startup.
FROM debian:bookworm-slim

ARG TARGETARCH
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl jq libicu72 \
    && rm -rf /var/lib/apt/lists/*

# Map Docker arch to uNmINeD's naming (amd64 -> x64). The LINUX downloads
# are tar.gz (the macOS ones are zips — that mismatch broke the first CI
# build). Keep the CLI's own directory layout intact — it resolves its
# native libraries and templates relative to the binary.
RUN case "$TARGETARCH" in \
        amd64) UNMINED_ARCH=x64 ;; \
        arm64) UNMINED_ARCH=arm64 ;; \
        *) echo "unsupported arch: $TARGETARCH" >&2; exit 1 ;; \
    esac \
    && curl -fsSL "https://unmined.net/download/unmined-cli-linux-${UNMINED_ARCH}-dev/" -o /tmp/unmined.tar.gz \
    && tar -xzf /tmp/unmined.tar.gz -C /opt \
    && rm /tmp/unmined.tar.gz \
    && mv /opt/unmined-cli_* /opt/unmined \
    && chmod +x /opt/unmined/unmined-cli \
    # Structural check only: executing the .NET binary here would run under
    # QEMU for the non-host arch. render-loop.sh self-checks at runtime.
    && test -x /opt/unmined/unmined-cli \
    && test -d /opt/unmined/library

COPY docker/unmined-render/render-loop.sh /app/render-loop.sh
COPY docker/unmined-render/webshell/ /app/webshell/
RUN chmod +x /app/render-loop.sh

ENTRYPOINT ["/app/render-loop.sh"]
