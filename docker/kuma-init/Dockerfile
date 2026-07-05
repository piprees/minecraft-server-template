FROM python:3.14-alpine

ARG REPO_URL=https://github.com/piprees/minecraft-server-template
LABEL org.opencontainers.image.source=${REPO_URL} \
      org.opencontainers.image.description="One-shot Uptime Kuma provisioner from config" \
      org.opencontainers.image.licenses="MIT"

RUN pip install --no-cache-dir 'uptime-kuma-api>=1.2.1'

COPY scripts/kuma-provision.py /app/

WORKDIR /app
CMD ["python3", "/app/kuma-provision.py"]
