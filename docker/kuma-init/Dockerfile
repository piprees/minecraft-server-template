FROM python:3.14.6-alpine3.24

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="One-shot Uptime Kuma provisioner from config" \
      org.opencontainers.image.licenses="MIT"

RUN pip install --no-cache-dir 'uptime-kuma-api>=1.2.1'

COPY scripts/kuma-provision.py /app/

RUN addgroup -S app && adduser -S -G app app

USER app
WORKDIR /app
CMD ["python3", "/app/kuma-provision.py"]
