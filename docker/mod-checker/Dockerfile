FROM python:3.14.6-alpine3.24

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Daily mod update checker with HTML status page" \
      org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache bash curl jq

COPY scripts/check-updates.sh /app/scripts/
COPY scripts/modrinth-api.py /app/scripts/
COPY docker/mod-checker/entrypoint.sh /app/

RUN chmod +x /app/entrypoint.sh /app/scripts/check-updates.sh

WORKDIR /app
CMD ["/app/entrypoint.sh"]
