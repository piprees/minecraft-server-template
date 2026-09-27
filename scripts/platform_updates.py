#!/usr/bin/env python3
"""platform_updates.py - find and apply updates to the platform's own pins: compose images, Fabric, build tools.

Context: template-only. .github/workflows/platform-updates.yml runs it weekly
with --apply and opens the platform-updates/auto PR, which dep-review.yml then
reviews. gate.py and collect.py read PINS, IMAGE_LINE and classify_line() from
here, so this table is the one list of what that PR may change. Dependabot
covers Dockerfiles, actions and pip; this covers what it cannot parse:

  image   every ${MIRROR_REGISTRY:-…}/<upstream>:<tag> in docker-compose.yml
          (Docker Hub tags; the tag keeps its shape, rules in IMAGE_RULES)
  loader  Fabric loader: compose FABRIC_LOADER_VERSION, the client pack's
          dependencies, build-modpack.sh's fallback, mods/*/gradle.properties
          (meta.fabricmc.net, the build marked stable for 1.21.1)
  gradle  yarn_mappings (newest 1.21.1 build) and fabric_version in
          mods/*/gradle.properties; fabric_version follows the pack's
          fabric-api pin in config/modrinth-mods.txt, so the in-house mods
          build against the fabric-api the server runs
  tool    Tailwind CLI (+ its sha256 lines, from the release's own
          sha256sums.txt), packwiz-installer-bootstrap, git-cliff
          (release.yml + release-train.yml; must also be on PyPI), doctl,
          hcloud

Usage:
  python3 scripts/platform_updates.py --check            # list updates, change nothing
  python3 scripts/platform_updates.py --apply [--json F] [--markdown F]
  --json writes the updates as a list; --markdown writes the PR body.
  --root DIR runs against another checkout (default: this repository).

Exit 0 when every dependency resolved (with or without updates); 1 when any
resolver failed or duplicates could not be aligned - nothing is written then.

Gotchas:
  - Every occurrence of a dependency moves to the same version. A dependency
    whose occurrences disagree and that resolves to nothing fails the run.
  - Never downgrades, never proposes a pre-release. Minecraft stays 1.21.1 and
    itzg/minecraft-server stays -java21 (AGENTS.md § Fixed decisions).
  - Yarn marks only the newest Minecraft version's builds stable, so for
    1.21.1 the newest build is taken.
  - GitHub tags come from `git ls-remote`, not the REST API, whose
    unauthenticated limit is 60 requests an hour.
  - Standard library only.
"""
import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from collections import namedtuple
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET_MC = "1.21.1"
USER_AGENT = "adventure/platform-updates"
HTTP_TIMEOUT = 30
HUB_PAGES = 3

Pin = namedtuple("Pin", "ecosystem name path regex")
Occurrence = namedtuple("Occurrence", "ecosystem name path lineno version")

# Each regex has three groups: text before the version, the version, text after.
PINS = (
    Pin("loader", "fabric-loader", "docker-compose.yml",
        re.compile(r"^(\s+FABRIC_LOADER_VERSION:\s*\$\{FABRIC_LOADER_VERSION:-)([0-9][\w.+-]*)(\}\s*)$")),
    Pin("loader", "fabric-loader", "modpack/adventure.mrpack.json",
        re.compile(r'^(\s*"fabric-loader":\s*")([0-9][\w.+-]*)(",?\s*)$')),
    Pin("loader", "fabric-loader", "scripts/build-modpack.sh",
        re.compile(r'^(FABRIC_LOADER_VERSION=\$\(python3 -c .*\|\| echo ")([0-9][\w.+-]*)("\)\s*)$')),
    Pin("loader", "fabric-loader", "mods/*/gradle.properties",
        re.compile(r"^(loader_version=)([0-9][\w.+-]*)(\s*)$")),
    Pin("gradle", "yarn", "mods/*/gradle.properties",
        re.compile(r"^(yarn_mappings=)([0-9][\w.+-]*)(\s*)$")),
    Pin("gradle", "fabric-api", "mods/*/gradle.properties",
        re.compile(r"^(fabric_version=)([0-9][\w.+-]*)(\s*)$")),
    Pin("tool", "tailwindcss", "mods/custom-dimensions/build-viewer-css.sh",
        re.compile(r'^(TAILWIND_VERSION=")([0-9][\w.-]*)("\s*)$')),
    Pin("tool", "packwiz-installer-bootstrap", "scripts/build-modpack.sh",
        re.compile(r'^(PACKWIZ_BOOTSTRAP_VERSION=")([0-9][\w.-]*)("\s*)$')),
    # release.yml and release-train.yml read this file: GITHUB_TOKEN cannot
    # push a change to a workflow file.
    Pin("tool", "git-cliff", ".github/git-cliff-version",
        re.compile(r"^()([0-9][\w.-]*)(\s*)$")),
    Pin("tool", "doctl", "examples/consumer/.github/workflows/server-power.yml",
        re.compile(r"^(\s+DOCTL_VERSION:\s*')([0-9][\w.-]*)('\s*)$")),
    Pin("tool", "hcloud", "examples/consumer/.github/workflows/server-power.yml",
        re.compile(r"^(\s+HCLOUD_VERSION:\s*')([0-9][\w.-]*)('\s*)$")),
)
IMAGE_FILE = "docker-compose.yml"
# Groups: prefix, upstream image, tag, trailing space.
IMAGE_LINE = re.compile(
    r"^(\s+image:\s*\$\{MIRROR_REGISTRY:-[^}\s]+\}/)([a-z0-9][a-z0-9._/-]*):([A-Za-z0-9_][A-Za-z0-9_.-]*)(\s*)$")
TAILWIND_FILE = "mods/custom-dimensions/build-viewer-css.sh"
# Groups: indent, asset, text before the hash, hash, text after.
TAILWIND_SUM_LINE = re.compile(r'^(\s+)(tailwindcss-[a-z0-9-]+)(\) echo ")([0-9a-f]{64})(" ;;\s*)$')

GITHUB_REPOS = {
    "tailwindcss": "tailwindlabs/tailwindcss",
    "packwiz-installer-bootstrap": "packwiz/packwiz-installer-bootstrap",
    "git-cliff": "orhun/git-cliff",
    "doctl": "digitalocean/doctl",
    "hcloud": "hetznercloud/cli",
}

# Per-image tag rules. Without a rule an image keeps its tag's shape: the same
# non-numeric prefix, the same count of dotted numbers, the same suffix.
#   suffix   the suffix the current tag must carry (a fixed decision)
#   accept   extra filter on the numeric key
#   pattern  the tag's full shape, when it is not "<numbers><suffix>";
#            its groups are the numeric key
#   frozen   why the image is never updated
#   notes    release-notes URL; {tag} is the tag, {version} its numeric part
IMAGE_RULES = {
    "itzg/minecraft-server": {
        "suffix": "-java21",
        "notes": "https://github.com/itzg/docker-minecraft-server/releases/tag/{version}",
    },
    "itzg/mc-backup": {"notes": "https://github.com/itzg/docker-mc-backup/releases/tag/{tag}"},
    "louislam/uptime-kuma": {"notes": "https://github.com/louislam/uptime-kuma/releases/tag/{tag}"},
    "cloudflare/cloudflared": {"notes": "https://github.com/cloudflare/cloudflared/releases/tag/{tag}"},
    "nginx": {
        # An even minor is nginx's stable branch; an odd one is mainline.
        "accept": lambda key: len(key) > 1 and key[1] % 2 == 0,
        "notes": "https://nginx.org/en/CHANGES-{major}.{minor}",
    },
    "minio/minio": {
        "pattern": r"^RELEASE\.(\d{4})-(\d\d)-(\d\d)T(\d\d)-(\d\d)-(\d\d)Z$",
        "frozen": "MinIO no longer publishes images to Docker Hub; the pinned tag is served from the GHCR mirror",
    },
    "minio/mc": {
        "pattern": r"^RELEASE\.(\d{4})-(\d\d)-(\d\d)T(\d\d)-(\d\d)-(\d\d)Z$",
        "frozen": "MinIO no longer publishes images to Docker Hub; the pinned tag is served from the GHCR mirror",
    },
}

NOTES = {
    ("loader", "fabric-loader"): "https://github.com/FabricMC/fabric-loader/releases/tag/{version}",
    ("gradle", "fabric-api"): "https://modrinth.com/mod/fabric-api/version/{version}",
    ("gradle", "yarn"): None,
    ("tool", "tailwindcss"): "https://github.com/tailwindlabs/tailwindcss/releases/tag/v{version}",
    ("tool", "packwiz-installer-bootstrap"):
        "https://github.com/packwiz/packwiz-installer-bootstrap/releases/tag/v{version}",
    ("tool", "git-cliff"): "https://github.com/orhun/git-cliff/releases/tag/v{version}",
    ("tool", "doctl"): "https://github.com/digitalocean/doctl/releases/tag/v{version}",
    ("tool", "hcloud"): "https://github.com/hetznercloud/cli/releases/tag/v{version}",
}

PRERELEASE = re.compile(r"(?i)(alpha|beta|rc|dev|pre|preview|nightly|snapshot|canary|next|edge|test)")
STABLE_VERSION = re.compile(r"^v?(\d+(?:\.\d+)+)$")
TAG_SHAPE = re.compile(r"^(?P<prefix>[^0-9]*)(?P<ver>\d+(?:\.\d+)*)(?P<suffix>.*)$")


class UpdateError(Exception):
    pass


# --- files ---------------------------------------------------------------------------

def glob_regex(pattern):
    """A PINS path (with at most `*` per segment) as an anchored regex over repo paths."""
    return re.compile("^" + "/".join(
        "[^/]+" if part == "*" else re.escape(part).replace(r"\*", "[^/]*") for part in pattern.split("/")) + "$")


def pin_files(root):
    """Every existing file PINS names, relative to root, sorted."""
    out = set()
    for pin in PINS:
        matches = sorted(root.glob(pin.path))
        if not matches:
            raise UpdateError(f"{pin.path}: no file holds the {pin.name} pin")
        out.update(p.relative_to(root).as_posix() for p in matches)
    out.add(IMAGE_FILE)
    return sorted(out)


def allowed_path(path):
    """True when `path` is a file this script may change."""
    return path == IMAGE_FILE or any(glob_regex(pin.path).match(path) for pin in PINS)


def classify_line(path, line):
    """What a line of `path` pins, as a hashable key, or None when it pins nothing.

    ("image", upstream), ("sha256", asset), or (ecosystem, name). The line is
    the text without a diff marker.
    """
    line = line.rstrip("\n")
    if path == IMAGE_FILE:
        m = IMAGE_LINE.match(line)
        if m:
            return ("image", m.group(2))
    if path == TAILWIND_FILE:
        m = TAILWIND_SUM_LINE.match(line)
        if m:
            return ("sha256", m.group(2))
    for pin in PINS:
        if glob_regex(pin.path).match(path) and pin.regex.match(line):
            return (pin.ecosystem, pin.name)
    return None


def mask_line(path, line):
    """The line with its version (or checksum) replaced by "<v>", or None when it pins nothing.

    Two lines with the same mask differ only in the value this script moves.
    """
    line = line.rstrip("\n")
    if path == IMAGE_FILE:
        m = IMAGE_LINE.match(line)
        if m:
            return f"{m.group(1)}{m.group(2)}:<v>{m.group(4)}"
    if path == TAILWIND_FILE:
        m = TAILWIND_SUM_LINE.match(line)
        if m:
            return f"{m.group(1)}{m.group(2)}{m.group(3)}<v>{m.group(5)}"
    for pin in PINS:
        if glob_regex(pin.path).match(path):
            m = pin.regex.match(line)
            if m:
                return f"{m.group(1)}<v>{m.group(3)}"
    return None


def scan_text(path, text):
    """Occurrences of every pin in one file's text."""
    found = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if path == IMAGE_FILE:
            m = IMAGE_LINE.match(line)
            if m:
                found.append(Occurrence("image", m.group(2), path, lineno, m.group(3)))
                continue
        for pin in PINS:
            if glob_regex(pin.path).match(path):
                m = pin.regex.match(line)
                if m:
                    found.append(Occurrence(pin.ecosystem, pin.name, path, lineno, m.group(2)))
                    break
    return found


def scan(texts):
    """texts: {path: text}. Every pin occurrence, and each PINS entry must be found."""
    found = []
    for path in sorted(texts):
        found.extend(scan_text(path, texts[path]))
    for pin in PINS:
        rx = glob_regex(pin.path)
        paths = [p for p in texts if rx.match(p)]
        for path in paths:
            if not any(o.path == path and (o.ecosystem, o.name) == (pin.ecosystem, pin.name) for o in found):
                raise UpdateError(f"{path}: the {pin.name} pin line is missing or not in the expected form")
    return found


def tailwind_sums(text):
    return {m.group(2): m.group(4) for m in map(TAILWIND_SUM_LINE.match, text.splitlines()) if m}


def rewrite(texts, targets, sums=None):
    """New texts with every occurrence moved to targets[(ecosystem, name)] and Tailwind sums replaced."""
    out = {}
    for path, text in texts.items():
        lines = text.splitlines(keepends=True)
        for i, raw in enumerate(lines):
            end = "\n" if raw.endswith("\n") else ""
            line = raw[:-1] if end else raw
            if path == IMAGE_FILE:
                m = IMAGE_LINE.match(line)
                if m and ("image", m.group(2)) in targets:
                    lines[i] = f"{m.group(1)}{m.group(2)}:{targets[('image', m.group(2))]}{m.group(4)}{end}"
                    continue
            if path == TAILWIND_FILE and sums:
                m = TAILWIND_SUM_LINE.match(line)
                if m:
                    if m.group(2) not in sums:
                        raise UpdateError(f"{path}: no published checksum for {m.group(2)}")
                    lines[i] = f"{m.group(1)}{m.group(2)}{m.group(3)}{sums[m.group(2)]}{m.group(5)}{end}"
                    continue
            for pin in PINS:
                if (pin.ecosystem, pin.name) in targets and glob_regex(pin.path).match(path):
                    m = pin.regex.match(line)
                    if m:
                        lines[i] = f"{m.group(1)}{targets[(pin.ecosystem, pin.name)]}{m.group(3)}{end}"
                        break
        out[path] = "".join(lines)
    return out


def verify_aligned(texts, targets):
    """Every occurrence of a targeted dependency now carries its target."""
    for occ in scan(texts):
        want = targets.get((occ.ecosystem, occ.name))
        if want is not None and occ.version != want:
            raise UpdateError(f"{occ.path}:{occ.lineno}: {occ.name} is {occ.version}, expected {want}")


# --- version choice (pure) -----------------------------------------------------------

def version_key(version):
    m = STABLE_VERSION.match(version or "")
    return tuple(int(x) for x in m.group(1).split(".")) if m else None


def pick_stable(current, candidates):
    """Highest plain X.Y[.Z] candidate newer than current, else None."""
    cur = version_key(current)
    best, best_key = None, cur
    for c in candidates:
        key = version_key(c)
        if key is None or PRERELEASE.search(c):
            continue
        if best_key is None or key > best_key:
            best, best_key = c.lstrip("v"), key
    return best


def tag_shape(tag, rule):
    """(shape, numeric key) of an image tag under its rule, or (None, None)."""
    if rule.get("pattern"):
        m = re.match(rule["pattern"], tag)
        return ("pattern", tuple(int(g) for g in m.groups())) if m else (None, None)
    m = TAG_SHAPE.match(tag)
    if not m:
        return None, None
    parts = tuple(int(x) for x in m.group("ver").split("."))
    return (m.group("prefix"), len(parts), m.group("suffix")), parts


def pick_image_tag(current, candidates, rule):
    """Newest candidate tag with the current tag's shape, never older, never a pre-release."""
    shape, cur_key = tag_shape(current, rule)
    if shape is None:
        raise UpdateError(f"tag {current!r} does not have a recognised shape")
    if rule.get("suffix") and (shape == "pattern" or shape[2] != rule["suffix"]):
        raise UpdateError(f"tag {current!r} must end in {rule['suffix']!r}")
    accept = rule.get("accept") or (lambda key: True)
    best, best_key = None, cur_key
    for tag in candidates:
        cand_shape, key = tag_shape(tag, rule)
        if cand_shape != shape or PRERELEASE.search(tag) or not accept(key):
            continue
        if key > best_key:
            best, best_key = tag, key
    return best


def pick_fabric_loader(entries, current):
    """The loader meta.fabricmc.net marks stable for TARGET_MC, if newer than current."""
    stable = [e["loader"]["version"] for e in entries
              if isinstance(e, dict) and (e.get("loader") or {}).get("stable")]
    return pick_stable(current, stable[:1])


def pick_yarn(entries, current):
    """Newest yarn build for exactly TARGET_MC (stable builds first, when any exist)."""
    def build(e):
        return e.get("build") if isinstance(e.get("build"), int) else -1

    builds = [e for e in entries if isinstance(e, dict) and e.get("gameVersion") == TARGET_MC]
    pool = [e for e in builds if e.get("stable")] or builds
    if not pool:
        return None
    best = max(pool, key=build)
    cur = re.search(r"\+build\.(\d+)$", current or "")
    if cur and build(best) <= int(cur.group(1)):
        return None
    return best["version"]


def modrinth_pin(mods_txt, slug):
    """The version id pinned for `slug` in config/modrinth-mods.txt."""
    for line in mods_txt.splitlines():
        body = line.split("#", 1)[0].strip().rstrip("?")
        if body.startswith(f"{slug}:"):
            return body.split(":", 1)[1]
    return None


def parse_sha256sums(text):
    """sha256sums.txt ("<hash>  ./<asset>") -> {asset: hash}."""
    out = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            out[parts[1].lstrip("*").removeprefix("./")] = parts[0]
    return out


def notes_url(ecosystem, name, version):
    if ecosystem == "image":
        tpl = IMAGE_RULES.get(name, {}).get("notes")
        if not tpl:
            return None
        m = TAG_SHAPE.match(version)
        nums = m.group("ver").split(".") if m else ["", ""]
        return tpl.format(tag=version, version=m.group("ver") if m else version,
                          major=nums[0], minor=nums[1] if len(nums) > 1 else "")
    tpl = NOTES.get((ecosystem, name))
    return tpl.format(version=version) if tpl else None


# --- network -------------------------------------------------------------------------

class Net:
    """Every network call the resolvers make; tests pass a fake."""

    def get(self, url):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            return resp.read().decode()

    def json(self, url):
        return json.loads(self.get(url))

    def exists(self, url):
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
                return resp.status == 200
        except urllib.error.HTTPError:
            return False

    def git_tags(self, repo):
        out = subprocess.run(["git", "ls-remote", "--tags", "--refs", f"https://github.com/{repo}"],
                             check=True, capture_output=True, text=True, timeout=120).stdout
        return [line.split("refs/tags/", 1)[1] for line in out.splitlines() if "refs/tags/" in line]

    def hub_tags(self, image, name_filter=""):
        repo = image if "/" in image else f"library/{image}"
        url = f"https://hub.docker.com/v2/repositories/{repo}/tags?page_size=100&ordering=last_updated"
        if name_filter:
            url += f"&name={urllib.request.quote(name_filter)}"
        tags = []
        for _ in range(HUB_PAGES):
            page = self.json(url)
            tags.extend(t["name"] for t in page.get("results") or [])
            url = page.get("next")
            if not url:
                break
        return tags


# --- resolvers -----------------------------------------------------------------------

def resolve(ecosystem, name, current, net, root):
    """(target version or None, extra) for one dependency; `current` is its highest pinned value."""
    if ecosystem == "image":
        rule = IMAGE_RULES.get(name, {})
        if rule.get("frozen"):
            return None, {"note": rule["frozen"]}
        shape, _ = tag_shape(current, rule)
        suffix = shape[2] if isinstance(shape, tuple) else ""
        return pick_image_tag(current, net.hub_tags(name, suffix), rule), {}
    if (ecosystem, name) == ("loader", "fabric-loader"):
        return pick_fabric_loader(net.json(f"https://meta.fabricmc.net/v2/versions/loader/{TARGET_MC}"), current), {}
    if (ecosystem, name) == ("gradle", "yarn"):
        return pick_yarn(net.json(f"https://meta.fabricmc.net/v2/versions/yarn/{TARGET_MC}"), current), {}
    if (ecosystem, name) == ("gradle", "fabric-api"):
        vid = modrinth_pin((root / "config/modrinth-mods.txt").read_text(), "fabric-api")
        if not vid:
            raise UpdateError("config/modrinth-mods.txt has no fabric-api pin")
        version = net.json(f"https://api.modrinth.com/v2/version/{vid}")
        number = version.get("version_number")
        if TARGET_MC not in (version.get("game_versions") or []):
            raise UpdateError(f"fabric-api {number} ({vid}) does not target {TARGET_MC}")
        pom = f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{number}/fabric-api-{number}.pom"
        if not net.exists(pom):
            raise UpdateError(f"fabric-api {number} is not on maven.fabricmc.net")
        return (number if number != current else None), {}
    if ecosystem == "tool":
        tags = net.git_tags(GITHUB_REPOS[name])
        if name == "git-cliff":
            on_pypi = set(net.json("https://pypi.org/pypi/git-cliff/json").get("releases") or {})
            tags = [t for t in tags if t.lstrip("v") in on_pypi]
        target = pick_stable(current, tags)
        if name == "tailwindcss" and target:
            sums = parse_sha256sums(net.get(
                f"https://github.com/tailwindlabs/tailwindcss/releases/download/v{target}/sha256sums.txt"))
            return target, {"sha256": sums}
        return target, {}
    raise UpdateError(f"no resolver for {ecosystem}:{name}")


def highest(versions):
    return max(versions, key=lambda v: (version_key(v) or (), v))


def plan(texts, net, root):
    """(updates, errors). Each update: ecosystem, name, old (sorted list), new, files, notes, extra."""
    occs = scan(texts)
    deps = {}
    for o in occs:
        deps.setdefault((o.ecosystem, o.name), []).append(o)
    updates, errors = [], []
    for (eco, name), found in sorted(deps.items()):
        olds = sorted({o.version for o in found})
        try:
            target, extra = resolve(eco, name, highest(olds), net, root)
        except (UpdateError, OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
            errors.append(f"{eco}:{name}: {exc}")
            continue
        if target is None and len(olds) > 1:
            if extra.get("note"):
                errors.append(f"{eco}:{name}: pins disagree ({', '.join(olds)}) and it is not updatable: {extra['note']}")
                continue
            target = highest(olds)
        if target is None or olds == [target]:
            continue
        updates.append({"ecosystem": eco, "name": name, "old": olds, "new": target,
                        "files": sorted({o.path for o in found}), "notes": notes_url(eco, name, target),
                        "extra": extra})
    return updates, errors


def apply_plan(texts, updates):
    """New texts for `updates`, verified aligned. Raises UpdateError."""
    targets = {(u["ecosystem"], u["name"]): u["new"] for u in updates}
    sums = next((u["extra"].get("sha256") for u in updates if u["name"] == "tailwindcss"), None)
    if sums is not None:
        pinned = tailwind_sums(texts.get(TAILWIND_FILE, ""))
        missing = sorted(set(pinned) - set(sums))
        if missing:
            raise UpdateError(f"tailwindcss: the release publishes no checksum for {', '.join(missing)}")
    new = rewrite(texts, targets, sums)
    verify_aligned(new, targets)
    if sums is not None and tailwind_sums(new[TAILWIND_FILE]) != {a: sums[a] for a in tailwind_sums(new[TAILWIND_FILE])}:
        raise UpdateError("tailwindcss: checksum lines were not all replaced")
    return new


def markdown(updates):
    lines = ["Platform dependency updates from `scripts/platform_updates.py`.", "",
             "| Ecosystem | Dependency | From | To | Files |", "| --- | --- | --- | --- | --- |"]
    for u in updates:
        to = f"[{u['new']}]({u['notes']})" if u["notes"] else u["new"]
        files = "<br>".join(f"`{f}`" for f in u["files"])
        lines.append(f"| {u['ecosystem']} | `{u['name']}` | {', '.join(u['old'])} | {to} | {files} |")
    if any(u["ecosystem"] == "image" for u in updates):
        lines += ["", "New image tags were mirrored to GHCR before this PR was opened."]
    if any(u["ecosystem"] == "loader" for u in updates):
        lines += ["", "The Fabric loader moves on the server, the client pack and the in-house mod builds together."]
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="list available updates")
    mode.add_argument("--apply", action="store_true", help="rewrite the pinned files in place")
    ap.add_argument("--json", help="write the updates here as JSON")
    ap.add_argument("--markdown", help="write a PR body here")
    ap.add_argument("--root", default=str(ROOT))
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        texts = {p: (root / p).read_text() for p in pin_files(root)}
        updates, errors = plan(texts, Net(), root)
        new = apply_plan(texts, updates) if updates and not errors else texts
    except UpdateError as exc:
        print(f"platform-updates: {exc}", file=sys.stderr)
        return 1
    for u in updates:
        print(f"{u['ecosystem']:7} {u['name']:32} {', '.join(u['old'])} -> {u['new']}  ({', '.join(u['files'])})")
    for e in errors:
        print(f"ERROR {e}", file=sys.stderr)
    if not updates:
        print("platform-updates: everything is current")
    if errors:
        return 1
    if args.apply:
        for path, text in new.items():
            if text != texts[path]:
                (root / path).write_text(text)
    if args.json:
        Path(args.json).write_text(json.dumps(
            [{k: v for k, v in u.items() if k != "extra"} for u in updates], indent=2) + "\n")
    if args.markdown:
        Path(args.markdown).write_text(markdown(updates))
    return 0


if __name__ == "__main__":
    sys.exit(main())
