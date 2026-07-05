FROM alpine:3.24

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Idle maintenance: save, BlueMap render, Chunky pre-gen" \
      org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache bash curl docker-cli

COPY scripts/idle-tasks.sh /app/

RUN chmod +x /app/idle-tasks.sh

WORKDIR /app
CMD ["bash", "/app/idle-tasks.sh"]
