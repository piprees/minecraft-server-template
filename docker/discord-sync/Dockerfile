FROM python:3.14.6-alpine3.24

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="Discord bot: role sync, /register, /mc admin commands" \
      org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache docker-cli

COPY scripts/requirements-discord-sync.txt /app/
RUN pip install --no-cache-dir -r /app/requirements-discord-sync.txt

COPY scripts/discord-sync.py /app/

RUN addgroup -S app && adduser -S -G app app

USER app
WORKDIR /app
CMD ["python3", "/app/discord-sync.py"]
