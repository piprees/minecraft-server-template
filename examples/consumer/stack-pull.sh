#!/usr/bin/env bash
# stack-pull.sh — Fetch and cache a pinned stack bundle from GitHub releases.
#
# This is the ONE script consumers vendor a copy of. It has no dependencies
# on lib.sh or any other file in the stack bundle.
#
# Usage:
#   ./stack-pull.sh                            # resolve the latest release
#   STACK_VERSION=v2 ./stack-pull.sh           # resolve latest v2.x.y
#   STACK_VERSION=v2.1 ./stack-pull.sh         # resolve latest v2.1.x
#   STACK_VERSION=v2.1.3 ./stack-pull.sh       # exact pin
#
# Reads STACK_VERSION from the environment or from .env in the current
# directory (unset or 'latest' = the newest release of any major;
# ./ops setup records the major line in use so upgrades stay deliberate).
# Downloads the bundle + sha256 checksum to .stack/<version>/,
# verifies the checksum, unpacks, and atomically repoints .stack/current.
#
# Idempotent: if the resolved version is already cached, no download occurs.
# Offline-tolerant: if the GitHub API fails but the version is cached, uses cache.
set -euo pipefail

REPO="piprees/minecraft-server-template"
API_URL="https://api.github.com/repos/${REPO}/releases"
STACK_DIR=".stack"

_sha256() {
  if command -v shasum > /dev/null 2>&1; then
    shasum -a 256 "$@"
  elif command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$@"
  else
    echo "ERROR: neither shasum nor sha256sum found" >&2
    return 1
  fi
}

# Auth order: GITHUB_TOKEN env var (CI / headless), gh CLI (workstation),
# unauthenticated (public repo). A token also avoids API rate limits in CI.
_fetch_releases() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl -fsSL --connect-timeout 10 --max-time 30 \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" "$API_URL" 2>/dev/null && return 0
  fi
  if command -v gh > /dev/null 2>&1 && gh auth status > /dev/null 2>&1; then
    gh api "/repos/${REPO}/releases" 2>/dev/null && return 0
  fi
  curl -fsSL --connect-timeout 10 --max-time 30 "$API_URL" 2>/dev/null
}

_resolve_version() {
  local pin="$1"
  pin="${pin#v}"

  local releases_json
  releases_json=$(_fetch_releases) || return 1

  local resolved
  resolved=$(
    echo "$releases_json" | \
    grep -oE '"tag_name"\s*:\s*"v[0-9]+\.[0-9]+\.[0-9]+"' | \
    grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | \
    sort -t. -k1,1nr -k2,2nr -k3,3nr | \
    while IFS= read -r tag_bare; do
      if [[ -z "$pin" ]] || [[ "$pin" == "latest" ]]; then
        echo "v$tag_bare" && return 0
      elif [[ "$pin" == *.*.* ]]; then
        [[ "$tag_bare" = "$pin" ]] && echo "v$tag_bare" && return 0
      else
        [[ "$tag_bare" = "$pin".* ]] && echo "v$tag_bare" && return 0
      fi
    done
  )

  if [[ -z "$resolved" ]]; then
    if [[ -z "$pin" ]] || [[ "$pin" == "latest" ]]; then
      echo "ERROR: no releases found" >&2
    else
      echo "ERROR: no release matching v${pin}" >&2
    fi
    return 1
  fi
  echo "$resolved"
}

_download_asset() {
  local tag="$1" filename="$2" dest="$3"
  # With a token, resolve the asset id and download via the API (works for
  # private repos and avoids rate limits).
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    local asset_id
    asset_id=$(curl -fsSL --connect-timeout 10 --max-time 30 \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      "${API_URL}/tags/${tag}" 2>/dev/null \
      | grep -B3 "\"name\": *\"${filename}\"" | grep -oE '"id": *[0-9]+' | head -1 | grep -oE '[0-9]+') || true
    if [[ -n "$asset_id" ]]; then
      curl -fSL --connect-timeout 10 --max-time 120 \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "Accept: application/octet-stream" \
        -o "$dest" "https://api.github.com/repos/${REPO}/releases/assets/${asset_id}" && return 0
    fi
  fi
  if command -v gh > /dev/null 2>&1 && gh auth status > /dev/null 2>&1; then
    gh release download "$tag" --repo "$REPO" --pattern "$filename" --dir "$(dirname "$dest")" 2>/dev/null \
      && [[ -f "$(dirname "$dest")/$filename" ]] \
      && mv "$(dirname "$dest")/$filename" "$dest" \
      && return 0
  fi
  local url="https://github.com/${REPO}/releases/download/${tag}/${filename}"
  curl -fSL --connect-timeout 10 --max-time 120 -o "$dest" "$url"
}

if [[ -z "${STACK_VERSION:-}" ]] && [[ -f ".env" ]]; then
  STACK_VERSION=$(grep -E '^STACK_VERSION=' .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'") || true
fi

# Default: track the latest release. Pin STACK_VERSION in .env (v2 = latest
# v2.x.y, v2.1.3 = exact) to hold a line - ./ops setup records this for you.
STACK_VERSION="${STACK_VERSION:-latest}"

echo "Resolving STACK_VERSION=${STACK_VERSION}..."

RESOLVED=""
if RESOLVED=$(_resolve_version "$STACK_VERSION"); then
  echo "Resolved to ${RESOLVED}"
else
  echo "WARNING: GitHub API unavailable, checking local cache..." >&2
  pin="${STACK_VERSION#v}"
  [[ "$pin" == "latest" ]] && pin=""
  best=""
  if [[ -d "$STACK_DIR" ]]; then
    best=$(
      for dir in "$STACK_DIR"/v*/; do
        [[ -d "$dir" ]] && basename "$dir" || true
      done | sed 's/^v//' | sort -t. -k1,1n -k2,2n -k3,3n | \
      while IFS= read -r tag_bare; do
        if [[ -z "$pin" ]]; then
          echo "v$tag_bare"
        elif [[ "$pin" == *.*.* ]]; then
          [[ "$tag_bare" = "$pin" ]] && echo "v$tag_bare" || true
        else
          [[ "$tag_bare" = "$pin".* ]] && echo "v$tag_bare" || true
        fi
      done | tail -1
    ) || true
  fi
  if [[ -z "$best" ]]; then
    echo "ERROR: no cached version matches ${STACK_VERSION} and GitHub API is unavailable" >&2
    exit 1
  fi
  RESOLVED="$best"
  echo "Using cached ${RESOLVED}"
fi

VERSION_BARE="${RESOLVED#v}"
CACHE_DIR="${STACK_DIR}/${RESOLVED}"
TARBALL="stack-v${VERSION_BARE}.tar.gz"
CHECKSUM_FILE="${TARBALL}.sha256"

if [[ -d "$CACHE_DIR/stack" ]]; then
  echo "Already cached at ${CACHE_DIR}"
else
  echo "Downloading ${TARBALL}..."
  mkdir -p "$CACHE_DIR"

  _download_asset "$RESOLVED" "$TARBALL" "$CACHE_DIR/$TARBALL"
  _download_asset "$RESOLVED" "$CHECKSUM_FILE" "$CACHE_DIR/$CHECKSUM_FILE"

  echo "Verifying checksum..."
  (cd "$CACHE_DIR" && _sha256 -c "$CHECKSUM_FILE") || {
    echo "ERROR: checksum verification failed — removing corrupted download" >&2
    rm -rf "$CACHE_DIR"
    exit 1
  }

  echo "Unpacking..."
  tar -xzf "$CACHE_DIR/$TARBALL" -C "$CACHE_DIR"
fi

ln -sfn "$RESOLVED" "${STACK_DIR}/current"

echo "${RESOLVED} ready at ${STACK_DIR}/current"
