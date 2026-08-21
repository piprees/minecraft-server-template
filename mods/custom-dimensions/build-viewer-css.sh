#!/usr/bin/env bash
# build-viewer-css.sh - Compile the seed viewer's Tailwind source into the
# stylesheet the mod jar ships.
#
# PURPOSE
#   src/main/resources/seed-viewer/web/app.css is Tailwind v4 source: @import
#   'tailwindcss', an @theme block, @apply in 40 rules and --alpha() colour
#   calls. A browser reads none of that, so the source is useless as a served
#   file - 25 of those rules have a body consisting only of @apply and style
#   nothing at all. This compiles it to app.built.css, which template.html
#   links, processResources packs into the jar and SeedServer hands out at
#   /assets/app.built.css.
#
# CONTEXT
#   Run it after editing app.css and commit app.built.css with the change.
#   Nothing else is an input: the sheet declares source(none) and names every
#   utility it uses in an @apply, so no markup file is scanned and editing the
#   template, the JS or ViewerPage.java cannot change this output. Gradle never
#   calls this, so `gradle build` needs no network and no Tailwind CLI, which
#   is what keeps mod-build.yml and release.yml offline.
#
# USAGE
#   ./build-viewer-css.sh            # compile and write app.built.css
#   ./build-viewer-css.sh --check    # exit 1 if the committed file is stale
#
# GOTCHAS
#   - Network is needed once, to fetch the pinned standalone CLI (no Node, no
#     npm). It is cached under XDG_CACHE_HOME and checksum-verified on every
#     run. TAILWIND_BIN=/path/to/tailwindcss skips the download entirely.
#   - There is no .bak: the output is deterministic from committed inputs and
#     git holds the previous version. The write is atomic (temp file + mv), so
#     a failed run never leaves a half-written stylesheet.
#   - A utility class written straight into the markup styles nothing. With
#     source(none) Tailwind generates only what an @apply asks for, so the
#     markup gets component classes and those get rules in the CSS.
set -euo pipefail

TAILWIND_VERSION="4.3.3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$SCRIPT_DIR/src/main/resources/seed-viewer/web"
INPUT="$WEB_DIR/app.css"
OUTPUT="$WEB_DIR/app.built.css"

CHECK=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK=1 ;;
    -h | --help)
      sed -n '2,34p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

die() {
  echo "ERROR: $*" >&2
  exit 1
}

# Checksums come from the release's own sha256sums.txt. Pinning them here means
# a swapped asset fails the build rather than silently compiling the stylesheet
# with an unknown binary.
platform_asset() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os-$arch" in
    Darwin-arm64) echo "tailwindcss-macos-arm64" ;;
    Darwin-x86_64) echo "tailwindcss-macos-x64" ;;
    Linux-aarch64 | Linux-arm64)
      if is_musl; then echo "tailwindcss-linux-arm64-musl"; else echo "tailwindcss-linux-arm64"; fi
      ;;
    Linux-x86_64)
      if is_musl; then echo "tailwindcss-linux-x64-musl"; else echo "tailwindcss-linux-x64"; fi
      ;;
    *) die "no pinned Tailwind CLI for $os-$arch - set TAILWIND_BIN to one you trust" ;;
  esac
}

is_musl() {
  ldd --version 2>&1 | grep -qi musl
}

asset_sha256() {
  case "$1" in
    tailwindcss-macos-arm64) echo "cdf646702987a743464dff4d9c60fd4480d1c1e73dd819a9a67f1078815dce9d" ;;
    tailwindcss-macos-x64) echo "7922e0953f2110c05976e3bf58f14e643d90427575e766b7d433f5f80cbee7e1" ;;
    tailwindcss-linux-arm64) echo "55fd0b241214eff3de1e8ee4f22796662f2d2e7a49bcfca7477cfd0bac398195" ;;
    tailwindcss-linux-arm64-musl) echo "71ea4be79c9de9827545682df3e040053fb535d37c71ed2cfdedf9385a0868e0" ;;
    tailwindcss-linux-x64) echo "dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a" ;;
    tailwindcss-linux-x64-musl) echo "a04d34ceacc8f52cbe8920ad846cdeb61d3d0021dba32db0d1f77c9d9fad7a6c" ;;
    *) die "no pinned checksum for $1" ;;
  esac
}

sha256_of() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

resolve_cli() {
  if [[ -n "${TAILWIND_BIN:-}" ]]; then
    [[ -x "$TAILWIND_BIN" ]] || die "TAILWIND_BIN=$TAILWIND_BIN is not executable"
    echo "$TAILWIND_BIN"
    return
  fi

  local asset want cache bin url got
  asset="$(platform_asset)"
  want="$(asset_sha256 "$asset")"
  cache="${XDG_CACHE_HOME:-$HOME/.cache}/minecraft-server-template/tailwindcss/$TAILWIND_VERSION"
  bin="$cache/$asset"

  if [[ -x "$bin" ]] && [[ "$(sha256_of "$bin")" == "$want" ]]; then
    echo "$bin"
    return
  fi

  mkdir -p "$cache"
  url="https://github.com/tailwindlabs/tailwindcss/releases/download/v${TAILWIND_VERSION}/${asset}"
  echo "Fetching Tailwind CLI v${TAILWIND_VERSION} ($asset)..." >&2
  curl -fsSL --retry 3 -o "$bin.tmp" "$url" || die "download failed: $url"
  got="$(sha256_of "$bin.tmp")"
  if [[ "$got" != "$want" ]]; then
    rm -f "$bin.tmp"
    die "checksum mismatch for $asset (expected $want, got $got)"
  fi
  chmod +x "$bin.tmp"
  mv "$bin.tmp" "$bin"
  echo "$bin"
}

[[ -f "$INPUT" ]] || die "missing $INPUT"
CLI="$(resolve_cli)"

TMP="$(mktemp -t app.built.css.XXXXXX)"
trap 'rm -f "$TMP" "$TMP.fresh" "$TMP.have"' EXIT

"$CLI" --silent --input "$INPUT" --output "$TMP"

# A build that emits nothing is the failure this whole script exists to
# prevent, and the CLI reports success for it.
if [[ ! -s "$TMP" ]] || ! grep -q '@layer' "$TMP"; then
  die "compiled stylesheet looks empty - refusing to write $OUTPUT"
fi

BANNER="/* Generated by build-viewer-css.sh from app.css with Tailwind v${TAILWIND_VERSION}.
 * Edit app.css, not this file. */
"

if [[ $CHECK -eq 1 ]]; then
  if [[ ! -f "$OUTPUT" ]]; then
    die "$OUTPUT does not exist - run ./build-viewer-css.sh"
  fi
  # Compared on collapsed whitespace, not bytes: the assertion is that the
  # committed stylesheet carries the same rules this input compiles to, and a
  # formatting difference between two platforms' CLI builds is not a stale
  # file. Any real staleness changes the content, which this still catches.
  printf '%s' "$BANNER" | cat - "$TMP" | tr -s '[:space:]' ' ' > "$TMP.fresh"
  tr -s '[:space:]' ' ' < "$OUTPUT" > "$TMP.have"
  if ! cmp -s "$TMP.have" "$TMP.fresh"; then
    # No diff: both sides are a single line after normalising, so a diff
    # prints the whole stylesheet twice. cmp names the byte, which is enough
    # to locate the change in the rebuilt file. Its non-zero status is the
    # expected case and is discarded before pipefail can abort the script.
    echo "ERROR: app.built.css is stale - run ./build-viewer-css.sh and commit the result" >&2
    cmp "$TMP.have" "$TMP.fresh" 2>&1 | sed 's|^|  |' >&2 || true
    exit 1
  fi
  echo "app.built.css is up to date (Tailwind v${TAILWIND_VERSION})"
  exit 0
fi

printf '%s' "$BANNER" | cat - "$TMP" > "$OUTPUT.tmp"
mv "$OUTPUT.tmp" "$OUTPUT"
echo "Wrote $OUTPUT ($(wc -c < "$OUTPUT" | tr -d ' ') bytes, Tailwind v${TAILWIND_VERSION})"
