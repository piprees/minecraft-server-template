#!/usr/bin/env bash
set -euo pipefail

rsync -a --delete /defaults/config/ /out/config/

if [[ -d /overlay/config ]]; then
  # custom-dimensions overlays must NOT clobber the platform dimension
  # files: the mod merges overlay/ against dimensions/ itself (full
  # replace vs "overrides" vs empty-{} skip), so consumer files land in
  # custom-dimensions/overlay/ instead of over the defaults.
  rsync -a --exclude=custom-dimensions /overlay/config/ /out/config/
  if [[ -d /overlay/config/custom-dimensions ]]; then
    mkdir -p /out/config/custom-dimensions/overlay
    rsync -a /overlay/config/custom-dimensions/ /out/config/custom-dimensions/overlay/
  fi
fi

defaults_file="/defaults/modrinth-mods.txt"
remove_file="/overlay/mods-remove.txt"
extra_file="/overlay/mods-extra.txt"
out_file="/out/mods/modrinth-mods.txt"

mkdir -p /out/mods

normalise() {
  sed 's/\r$//'
}

strip_comments() {
  grep -v '^\s*#' | grep -v '^\s*$' || true
}

slug_of() {
  printf '%s' "$1" | cut -d: -f1
}

declare -a lines=()
declare -A slugs=()
removed=0
added=0

while IFS= read -r line; do
  lines+=("$line")
  s=$(slug_of "$line")
  slugs["$s"]=1
done < <(normalise < "$defaults_file" | strip_comments)

declare -A remove_set=()
if [[ -f "$remove_file" ]]; then
  while IFS= read -r slug; do
    remove_set["$slug"]=1
  done < <(normalise < "$remove_file" | strip_comments)
fi

declare -A extra_by_slug=()
declare -a extra_order=()
if [[ -f "$extra_file" ]]; then
  while IFS= read -r line; do
    s=$(slug_of "$line")
    extra_by_slug["$s"]="$line"
    extra_order+=("$s")
  done < <(normalise < "$extra_file" | strip_comments)
fi

for slug in "${!remove_set[@]}"; do
  if [[ -z "${slugs[$slug]:-}" ]]; then
    echo "seed: warning: slug '$slug' in mods-remove.txt not found in defaults" >&2
  fi
done

tmp_file="/out/mods/.modrinth-mods.txt.tmp"

{
  for line in "${lines[@]}"; do
    s=$(slug_of "$line")

    if [[ -n "${remove_set[$s]:-}" ]]; then
      removed=$((removed + 1))
      continue
    fi

    if [[ -n "${extra_by_slug[$s]:-}" ]]; then
      printf '%s\n' "${extra_by_slug[$s]}"
      unset "extra_by_slug[$s]"
    else
      printf '%s\n' "$line"
    fi
  done

  for s in "${extra_order[@]}"; do
    if [[ -n "${extra_by_slug[$s]:-}" ]]; then
      printf '%s\n' "${extra_by_slug[$s]}"
      added=$((added + 1))
    fi
  done
} > "$tmp_file"

mv "$tmp_file" "$out_file"

defaults_count=${#lines[@]}
hash=$(sha256sum "$out_file" | cut -d' ' -f1)

echo "seed: $defaults_count defaults, $removed removed, $added added, sha256=$hash"

# Resolve every pin to a direct download URL (cached — a warm cache makes
# this zero API calls). Outputs mods-urls.txt / datapacks-urls.txt /
# mods-manifest.txt for the mc container's MODS_FILE / DATAPACKS_FILE and
# the deploy's stale-jar cleanup. A failed required resolution fails the
# seed (and therefore the boot) loudly.
python3 /resolve-mods.py "$out_file" /out/mods
