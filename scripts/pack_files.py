#!/usr/bin/env python3
"""Keep the client's pack selections pointing at the pack files actually shipped.

Context: the client modpack enables resource packs and the Iris shader by
filename — `resourcePacks:` in overrides/configureddefaults/options.txt and
`shaderPack=` in overrides/configureddefaults/config/iris.properties. Pack
filenames carry version numbers, so every re-pin in modpack/adventure.mrpack.json
renames them. build-modpack.sh runs `enable` after downloading the packs:
an options.txt entry whose file is absent follows the one downloaded file with
the same pack_key, and shaderPack is derived from the shipped Complementary zip
plus the Euphoria Patcher jar. pin-mod-versions.sh uses choose_version so a
re-pin stays on the pinned style variant (a project that publishes variants as
separate versions has several files per release with different keys).

Usage:
  python3 pack_files.py enable <work_dir>

  <work_dir> is build-modpack.sh's WORK_DIR: modrinth.index.json plus
  overrides/{configureddefaults,resourcepacks,shaderpacks}/. Any missing part is
  skipped. Exit 1 only when an enabled resource pack has no downloaded file with
  the same key (or more than one).

Gotchas:
  - options.txt is Minecraft's Gson output: `'` is written as \\u0027 (and
    <, >, &, =). A rewrite keeps that encoding; an unchanged file stays
    byte-identical.
  - Iris accepts a shader zip's full filename, and Euphoria Patcher names its
    patched copy `<zip stem> + EuphoriaPatches_<version>`. Euphoria's
    `-r<version>-` must equal the Complementary version it patches.
"""

import json
import os
import re
import sys

_FORMAT_CODE = re.compile(r"§.")
_VERSION = re.compile(r"(?<![a-z0-9])[vr]?\d+(?:\.\d+)*(?![a-z0-9])")
_SEPARATORS = re.compile(r"[^a-z0-9]+")
_COMPLEMENTARY = re.compile(r"^ComplementaryReimagined_r(.+)\.zip$")
_EUPHORIA = re.compile(r"^EuphoriaPatcher-(.+?)-r(.+?)-fabric\.jar$")
_GSON_ESCAPES = {"<": "\\u003c", ">": "\\u003e", "&": "\\u0026", "=": "\\u003d",
                 "'": "\\u0027", " ": "\\u2028", " ": "\\u2029"}


def pack_key(filename):
    """A pack file's identity with its version tokens removed.

    `Better-Leaves-9.5.zip` and `Better-Leaves-9.6.zip` share a key;
    `... (Short and Fluffy).zip` and `... (Tall).zip` do not.
    """
    name = filename.lower()
    if name.endswith(".zip"):
        name = name[:-4]
    name = _FORMAT_CODE.sub("", name)
    name = _VERSION.sub(" ", name)
    return _SEPARATORS.sub(" ", name).strip()


def primary_filename(version):
    """The primary file's name of a Modrinth version dict, or None."""
    files = version.get("files") or []
    primary = [f for f in files if f.get("primary")] or files[:1]
    return primary[0]["filename"] if primary else None


def choose_version(current_filename, versions):
    """The newest of `versions` (newest first, as Modrinth lists them) whose
    primary file has the same pack_key as `current_filename`; None if none."""
    key = pack_key(current_filename)
    for version in versions:
        name = primary_filename(version)
        if name and pack_key(name) == key:
            return version
    return None


def gson_dumps(values):
    """Serialise a list of strings the way Minecraft writes options.txt."""
    text = json.dumps(values, ensure_ascii=False, separators=(",", ":"))
    return "".join(_GSON_ESCAPES.get(ch, ch) for ch in text)


def rewrite_resource_packs(text, downloaded):
    """Point each absent `file/<name>.zip` entry at its re-versioned download.

    Returns (new_text, changes, errors); changes are (old, new) filenames,
    errors are messages for entries with no unique same-key download.
    """
    lines = text.splitlines(keepends=True)
    changes, errors = [], []
    for i, line in enumerate(lines):
        if not line.startswith("resourcePacks:"):
            continue
        body = line[len("resourcePacks:"):]
        ending = body[len(body.rstrip("\r\n")):]
        entries = json.loads(body)
        named = {e[5:] for e in entries if e.startswith("file/")}
        for j, entry in enumerate(entries):
            if not (entry.startswith("file/") and entry.endswith(".zip")):
                continue
            old = entry[5:]
            if old in downloaded:
                continue
            key = pack_key(old)
            candidates = sorted(f for f in downloaded
                                if f not in named and pack_key(f) == key)
            if len(candidates) == 1:
                entries[j] = "file/" + candidates[0]
                named.add(candidates[0])
                changes.append((old, candidates[0]))
            elif candidates:
                errors.append(f"options.txt enables '{old}' but it was not downloaded,"
                              f" and {len(candidates)} downloads could replace it: "
                              + ", ".join(f"'{c}'" for c in candidates))
            else:
                errors.append(f"options.txt enables '{old}' but no such pack was"
                              " downloaded (removed from _resourcePacks?)")
        if changes:
            lines[i] = "resourcePacks:" + gson_dumps(entries) + ending
    return "".join(lines), changes, errors


def derive_shader_pack(current, shader_zips, mod_jars):
    """Iris's shaderPack for the shipped packs: (value, warnings).

    `current` is kept when it names a downloaded pack other than Complementary.
    """
    shader_zips = set(shader_zips)
    warnings = []
    own = current if current in shader_zips else (
        current + ".zip" if current + ".zip" in shader_zips else None)
    if own and not _COMPLEMENTARY.match(own):
        return current, warnings
    complementary = {m.group(1): name for name in sorted(shader_zips)
                     if (m := _COMPLEMENTARY.match(name))}
    if not complementary:
        if current and not own:
            warnings.append(f"iris.properties selects '{current}' but no such"
                            " shader pack was downloaded")
        return current, warnings
    euphoria = [m for jar in sorted(mod_jars) if (m := _EUPHORIA.match(jar))]
    for m in euphoria:
        patches, for_version = m.group(1), m.group(2)
        if for_version in complementary:
            stem = complementary[for_version][:-len(".zip")]
            return f"{stem} + EuphoriaPatches_{patches}", warnings
    zip_name = complementary[sorted(complementary)[-1]]
    for m in euphoria:
        warnings.append(f"{m.group(0)} patches Complementary r{m.group(2)} but the"
                        f" pack ships '{zip_name}' - shader set without Euphoria")
    return zip_name, warnings


def rewrite_shader_pack(text, shader_zips, mod_jars):
    """Set the `shaderPack=` line of iris.properties: (new_text, old, new, warnings)."""
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if not line.startswith("shaderPack="):
            continue
        body = line[len("shaderPack="):]
        ending = body[len(body.rstrip("\r\n")):]
        current = body.rstrip("\r\n")
        value, warnings = derive_shader_pack(current, shader_zips, mod_jars)
        lines[i] = "shaderPack=" + value + ending
        return "".join(lines), current, value, warnings
    return text, None, None, []


def _listdir(path):
    return set(os.listdir(path)) if os.path.isdir(path) else None


def _mod_jars(work_dir):
    try:
        with open(os.path.join(work_dir, "modrinth.index.json"), encoding="utf-8") as f:
            index = json.load(f)
    except (OSError, ValueError):
        return set()
    return {e["path"][len("mods/"):] for e in index.get("files", [])
            if e.get("path", "").startswith("mods/")}


def enable(work_dir):
    """Apply both rewrites under work_dir; returns the process exit code."""
    defaults = os.path.join(work_dir, "overrides", "configureddefaults")
    status = 0

    options_path = os.path.join(defaults, "options.txt")
    packs = _listdir(os.path.join(work_dir, "overrides", "resourcepacks"))
    if os.path.isfile(options_path):
        with open(options_path, encoding="utf-8", newline="") as f:
            text = f.read()
        new_text, changes, errors = rewrite_resource_packs(text, packs or set())
        for old, new in changes:
            print(f"  ✓ options.txt: '{old}' -> '{new}'")
        for message in errors:
            print(f"  ✗ {message}", file=sys.stderr)
            status = 1
        if new_text != text:
            with open(options_path, "w", encoding="utf-8", newline="") as f:
                f.write(new_text)

    iris_path = os.path.join(defaults, "config", "iris.properties")
    shaders = _listdir(os.path.join(work_dir, "overrides", "shaderpacks"))
    if os.path.isfile(iris_path) and shaders is not None:
        with open(iris_path, encoding="utf-8", newline="") as f:
            text = f.read()
        new_text, old, new, warnings = rewrite_shader_pack(text, shaders, _mod_jars(work_dir))
        for message in warnings:
            print(f"  ! {message}", file=sys.stderr)
        if new_text != text:
            print(f"  ✓ iris.properties: shaderPack '{old}' -> '{new}'")
            with open(iris_path, "w", encoding="utf-8", newline="") as f:
                f.write(new_text)
    return status


def main(argv):
    if len(argv) != 3 or argv[1] != "enable":
        print("usage: pack_files.py enable <work_dir>", file=sys.stderr)
        return 2
    return enable(argv[2])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
