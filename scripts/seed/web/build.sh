#!/usr/bin/env bash
# =============================================================================
# build.sh — compile app.css -> app.built.css for the seed viewer
# =============================================================================
#
# Template-maintenance script. Run it when you change app.css or add classes
# to viewer_template.html / score-dimensions.py, then COMMIT app.built.css.
#
# Consumers never run this. The built stylesheet ships in the stack bundle
# and score-dimensions.py copies it into <seedtest>/assets/ at finalise time,
# so the viewer works offline, over http://127.0.0.1:8765/, and from a
# file:// open of .seedtest/index.html. Nothing fetches a CDN — the platform's
# offline-boot rule (AGENTS.md § Network dependency model) applies here too.
#
# Tailwind ships a self-contained executable, so this needs no Node and no
# npm — the repo has neither, and adding them for one internal page is not a
# trade worth making.
#
# Usage:
#   ./scripts/seed/web/build.sh            # build (downloads the CLI once)
#   ./scripts/seed/web/build.sh --watch    # rebuild on change while designing
#   TAILWIND_BIN=/path/to/tailwindcss ./scripts/seed/web/build.sh
#
# Gotchas:
#   - Tailwind purges by SCANNING. Classes assembled from fragments in Python
#     ("mrow sev" + str(n)) are invisible to it. Write them whole, or add them
#     to the safelist block at the bottom of app.css.
#   - The CLI is cached in .cache/ (git-ignored). CI does not run this; the
#     built file is committed precisely so release.yml stays free of node.
# =============================================================================
set -euo pipefail

TAILWIND_VERSION="${TAILWIND_VERSION:-v4.3.3}"
WEB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="$WEB_DIR/.cache"

resolve_cli() {
  if [[ -n "${TAILWIND_BIN:-}" && -x "${TAILWIND_BIN}" ]]; then
    printf '%s' "$TAILWIND_BIN"
    return
  fi
  local os arch asset bin
  case "$(uname -s)" in
    Darwin) os="macos" ;;
    Linux) os="linux" ;;
    *)
      echo "Unsupported OS: $(uname -s). Set TAILWIND_BIN to a tailwindcss binary." >&2
      exit 1
      ;;
  esac
  case "$(uname -m)" in
    arm64 | aarch64) arch="arm64" ;;
    x86_64 | amd64) arch="x64" ;;
    *)
      echo "Unsupported arch: $(uname -m). Set TAILWIND_BIN to a tailwindcss binary." >&2
      exit 1
      ;;
  esac
  asset="tailwindcss-${os}-${arch}"
  bin="$CACHE_DIR/${asset}-${TAILWIND_VERSION}"
  if [[ ! -x "$bin" ]]; then
    mkdir -p "$CACHE_DIR"
    echo "Fetching Tailwind ${TAILWIND_VERSION} standalone CLI (${asset})..." >&2
    curl -fsSL --connect-timeout 10 --max-time 180 -o "$bin.tmp" \
      "https://github.com/tailwindlabs/tailwindcss/releases/download/${TAILWIND_VERSION}/${asset}"
    chmod +x "$bin.tmp"
    mv "$bin.tmp" "$bin"
  fi
  printf '%s' "$bin"
}

CLI="$(resolve_cli)"
ARGS=(--input "$WEB_DIR/app.css" --output "$WEB_DIR/app.built.css")
if [[ "${1:-}" == "--watch" ]]; then
  ARGS+=(--watch)
else
  ARGS+=(--minify)
fi

"$CLI" "${ARGS[@]}"

if [[ "${1:-}" != "--watch" ]]; then
  echo "Built: scripts/seed/web/app.built.css ($(wc -c < "$WEB_DIR/app.built.css" | tr -d ' ') bytes)"
  echo "Commit it — the bundle ships the built file, not the source."
fi
