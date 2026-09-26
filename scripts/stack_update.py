#!/usr/bin/env python3
"""stack_update.py - plan a consumer's template (stack) version bump.

Context: the consumer scaffold's weekly update workflow
(examples/consumer/.github/workflows/update.yml, "Stack update" job) runs this
from the bundle as `.stack/current/stack/scripts/stack_update.py`. It holds
every decision that is pure logic, so each one is unit-tested
(scripts/tests/test_stack_update.py): which release is the target, how big
the jump is, what the PR says, and which images the target's compose needs.

Usage:
  stack_update.py fetch  --out FILE
      Every release of the template repo as one JSON array (all pages).
      Authenticates with GH_TOKEN/GITHUB_TOKEN when set.
  stack_update.py resolve --releases FILE --pin PIN
      The exact tag a pin resolves to (latest, vN, vN.M, vN.M.P) - the same
      rule stack-pull.sh and deploy-reusable.yml apply.
  stack_update.py plan --releases FILE --current vX.Y.Z
      key=value lines for $GITHUB_OUTPUT: target, bump
      (none|patch|minor|major), current_major, target_major.
  stack_update.py body --releases FILE --current vX.Y.Z --target vX.Y.Z
         [--deploy-major N] [--migrated-from PIN] [--workflow-file PATH]...
         [--verification TEXT] [--auto-merge]
      The pull-request body, Markdown, on stdout.
  stack_update.py images --tag X.Y.Z COMPOSE... [--extra NAME]... [--check]
      Every image the compose files reference at that tag, one per line;
      with --check, asks the registry for each and exits 1 if any is missing.
  stack_update.py deploy-major FILE
      The N in `deploy-reusable.yml@vN` of a consumer deploy.yml.

Gotchas: the target is the highest stable semver tag, never GitHub's
"latest" flag - that flag marks the most recently PUBLISHED release, which
is an older line whenever a patch lands on one. Drafts, prereleases and any
tag that is not exactly vX.Y.Z are ignored. Stdlib only: the bundle carries
no Python dependencies.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO = "piprees/minecraft-server-template"
API = "https://api.github.com/repos/%s/releases" % REPO

#: GitHub rejects a PR body over 65,536 characters; release notes stop
#: short of it and the whole body is cut hard at the limit.
BODY_LIMIT = 65000
BODY_BUDGET = 60000

_SEMVER = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
_DEPLOY_REF = re.compile(r"deploy-reusable\.yml@v(\d+)\b")
_IMAGE_LINE = re.compile(r"^\s*image:\s*(\S+)\s*$")
_VAR = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::?-([^}]*))?\}")
_BREAKING = re.compile(r"^#{2,4}\s*breaking changes\s*$", re.IGNORECASE)
_HEADING = re.compile(r"^#{1,4}\s")


def parse_version(tag):
    """(major, minor, patch) for an exact vX.Y.Z tag, else None."""
    m = _SEMVER.match(str(tag or "").strip())
    return tuple(int(x) for x in m.groups()) if m else None


def fmt(version):
    return "v%d.%d.%d" % version


def stable_releases(releases):
    """Published, non-prerelease vX.Y.Z releases, highest version first."""
    out = []
    for rel in releases:
        if rel.get("draft") or rel.get("prerelease"):
            continue
        version = parse_version(rel.get("tag_name"))
        if version:
            out.append((version, rel))
    out.sort(key=lambda pair: pair[0], reverse=True)
    return out


def resolve_pin(pin, releases):
    """The exact tag PIN floats to, or None when no release matches."""
    pin = str(pin or "").strip()
    bare = pin[1:] if pin.startswith("v") else pin
    parts = [] if bare in ("", "latest") else bare.split(".")
    if any(not p.isdigit() for p in parts) or len(parts) > 3:
        return None
    wanted = tuple(int(p) for p in parts)
    for version, _ in stable_releases(releases):
        if version[: len(wanted)] == wanted:
            return fmt(version)
    return None


def classify(current, target):
    """none | patch | minor | major for a move from CURRENT to TARGET."""
    cur, tgt = parse_version(current), parse_version(target)
    if cur is None or tgt is None:
        raise ValueError("not an exact version: %s -> %s" % (current, target))
    if tgt <= cur:
        return "none"
    if tgt[0] != cur[0]:
        return "major"
    if tgt[1] != cur[1]:
        return "minor"
    return "patch"


def plan(current, releases):
    """The update plan for a consumer pinned to CURRENT."""
    stable = stable_releases(releases)
    if parse_version(current) is None:
        raise ValueError("current pin is not an exact version: %s" % current)
    target = fmt(stable[0][0]) if stable else current
    if parse_version(target) <= parse_version(current):
        target = current
    return {
        "current": current,
        "target": target,
        "bump": classify(current, target),
        "current_major": str(parse_version(current)[0]),
        "target_major": str(parse_version(target)[0]),
    }


def releases_between(current, target, releases):
    """Releases after CURRENT up to and including TARGET, newest first."""
    lo, hi = parse_version(current), parse_version(target)
    return [rel for version, rel in stable_releases(releases) if lo < version <= hi]


def split_breaking(body):
    """(breaking-changes text, the rest) of one release body."""
    breaking, rest, into = [], [], None
    for line in (body or "").replace("\r\n", "\n").split("\n"):
        if _BREAKING.match(line.strip()):
            into = breaking
            continue
        if _HEADING.match(line) and into is breaking:
            into = rest
        (into if into is not None else rest).append(line)
    return "\n".join(breaking).strip(), "\n".join(rest).strip()


def _demote(text):
    """Push release-body headings below the PR's own `###` level."""
    return re.sub(r"^(#{1,3})(\s)", lambda m: "#" * (len(m.group(1)) + 2) + m.group(2), text, flags=re.M)


def render_body(current, target, releases, deploy_major=None, migrated_from=None,
                workflow_files=(), verification="", auto_merge=False):
    """The pull-request body for a CURRENT -> TARGET bump."""
    bump = classify(current, target)
    target_major = parse_version(target)[0]
    lines = []
    if bump == "none":
        lines.append("Pins the template exactly at `%s` in `.stack-version`." % target)
    else:
        lines.append("Template **%s** update: `%s` → `%s`." % (bump, current, target))
    lines.append("")
    if migrated_from is not None:
        lines += [
            "**Migration:** this repo had no `.stack-version`; deploys resolved the "
            "`STACK_VERSION` repository variable (`%s`) to `%s`. This PR creates "
            "`.stack-version`, which takes precedence from now on - delete the "
            "`STACK_VERSION` repository variable once it is merged." % (migrated_from or "latest", current),
            "",
        ]
    if verification:
        lines += ["**Deployability:** %s" % verification, ""]

    steps = []
    if deploy_major is not None and int(deploy_major) != target_major:
        steps.append("In `.github/workflows/deploy.yml`, change `deploy-reusable.yml@v%s` to "
                     "`deploy-reusable.yml@v%d` - deploys refuse a stack whose major differs "
                     "from the workflow's." % (deploy_major, target_major))
    if workflow_files:
        steps.append("Run `./dev update` locally and commit `.github/workflows` - this release "
                     "changes workflow files the update bot cannot push: "
                     + ", ".join("`%s`" % f for f in workflow_files) + ".")
    if bump == "major":
        steps.append("Read every **Breaking changes** section below and apply what it asks "
                     "of a consumer repo.")
    if steps:
        lines.append("### Before merging")
        lines.append("")
        lines += ["- [ ] " + s for s in steps]
        lines += ["- [ ] Merge - the push to `main` deploys the new stack.", ""]
    elif auto_merge:
        lines += ["Auto-merging: a %s bump with every check passed. The deploy is "
                  "dispatched straight after the merge." % bump, ""]

    notes = releases_between(current, target, releases)
    breaking = []
    for rel in notes:
        text, _ = split_breaking(rel.get("body"))
        if text:
            breaking.append((rel["tag_name"], text))
    if breaking:
        lines += ["### Breaking changes", ""]
        for tag, text in breaking:
            lines += ["#### %s" % tag, "", _demote(text), ""]
    if notes:
        lines += ["### Release notes", ""]
        size = len("\n".join(lines))
        for i, rel in enumerate(notes):
            _, rest = split_breaking(rel.get("body"))
            rest = re.sub(r"^##\s+v\d+\.\d+\.\d+.*\n+", "", rest)
            block = ["#### [%s](%s)" % (rel["tag_name"], rel.get("html_url", "")), "",
                     _demote(rest) or "_No notes._", ""]
            if size + len("\n".join(block)) > BODY_BUDGET:
                lines.append("_...and %d older releases down to %s: https://github.com/%s/releases_"
                             % (len(notes) - i, notes[-1]["tag_name"], REPO))
                break
            lines += block
            size += len("\n".join(block)) + 1
    body = "\n".join(lines).rstrip() + "\n"
    if len(body) > BODY_LIMIT:
        body = body[:BODY_LIMIT - 200] + "\n\n_Truncated: https://github.com/%s/releases_\n" % REPO
    return body


def _substitute(text, env):
    """Compose-style ${VAR}/${VAR:-default} expansion against ENV."""
    return _VAR.sub(lambda m: env.get(m.group(1)) or (m.group(2) or ""), text)


def compose_images(compose_texts, tag, extras=(), env=None):
    """Sorted unique image refs the compose files pull with IMAGE_TAG=TAG."""
    env = dict(env or {})
    env["IMAGE_TAG"] = tag
    images = set()
    for text in compose_texts:
        for line in text.splitlines():
            m = _IMAGE_LINE.match(line)
            if m:
                images.add(_substitute(m.group(1).strip("'\""), env))
    registry = _substitute("${IMAGE_REGISTRY:-ghcr.io/%s}" % REPO, env)
    for name in extras:
        images.add("%s/%s:%s" % (registry, name, tag))
    return sorted(images)


def split_image(ref):
    """(registry host, repository path, tag) of an image reference."""
    name, _, tag = ref.rpartition(":")
    if not name or "/" in tag:
        name, tag = ref, "latest"
    host, _, path = name.partition("/")
    if "." not in host and ":" not in host and host != "localhost":
        host, path = "registry-1.docker.io", name if "/" in name else "library/" + name
    return host, path, tag


def image_exists(ref, timeout=20):
    """True when the registry serves a manifest for REF (anonymous pull)."""
    host, path, tag = split_image(ref)
    accept = ", ".join([
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.docker.distribution.manifest.v2+json",
    ])
    url = "https://%s/v2/%s/manifests/%s" % (host, path, tag)
    headers = {"Accept": accept}
    for _ in range(2):
        req = urllib.request.Request(url, method="HEAD", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status == 200
        except urllib.error.HTTPError as err:
            challenge = err.headers.get("WWW-Authenticate", "")
            if err.code != 401 or "Authorization" in headers or not challenge:
                return False
            params = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
            token_url = "%s?service=%s&scope=%s" % (
                params.get("realm", ""), params.get("service", ""),
                params.get("scope", "repository:%s:pull" % path))
            with urllib.request.urlopen(token_url, timeout=timeout) as resp:
                data = json.load(resp)
            headers["Authorization"] = "Bearer " + (data.get("token") or data.get("access_token", ""))
    return False


def deploy_major(text):
    """N from `deploy-reusable.yml@vN` in a deploy.yml, or None."""
    m = _DEPLOY_REF.search(text)
    return int(m.group(1)) if m else None


def fetch_releases():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    out, page = [], 1
    while True:
        req = urllib.request.Request("%s?per_page=100&page=%d" % (API, page), headers=headers)
        with urllib.request.urlopen(req, timeout=30) as resp:
            batch = json.load(resp)
        out += batch
        if len(batch) < 100:
            return out
        page += 1


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Plan a template stack version bump.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fetch")
    p.add_argument("--out", required=True)
    p = sub.add_parser("resolve")
    p.add_argument("--releases", required=True)
    p.add_argument("--pin", default="latest")
    p = sub.add_parser("plan")
    p.add_argument("--releases", required=True)
    p.add_argument("--current", required=True)
    p = sub.add_parser("body")
    p.add_argument("--releases", required=True)
    p.add_argument("--current", required=True)
    p.add_argument("--target", required=True)
    p.add_argument("--deploy-major", type=int)
    p.add_argument("--migrated-from")
    p.add_argument("--workflow-file", action="append", default=[])
    p.add_argument("--verification", default="")
    p.add_argument("--auto-merge", action="store_true")
    p = sub.add_parser("images")
    p.add_argument("--tag", required=True)
    p.add_argument("--extra", action="append", default=[])
    p.add_argument("--check", action="store_true")
    p.add_argument("compose", nargs="+")
    p = sub.add_parser("deploy-major")
    p.add_argument("file")
    args = ap.parse_args(argv)

    if args.cmd == "fetch":
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(fetch_releases(), fh)
        return 0
    if args.cmd == "resolve":
        tag = resolve_pin(args.pin, _load(args.releases))
        if not tag:
            print("no release matches pin %r" % args.pin, file=sys.stderr)
            return 1
        print(tag)
        return 0
    if args.cmd == "plan":
        for key, value in plan(args.current, _load(args.releases)).items():
            print("%s=%s" % (key, value))
        return 0
    if args.cmd == "body":
        sys.stdout.write(render_body(
            args.current, args.target, _load(args.releases),
            deploy_major=args.deploy_major, migrated_from=args.migrated_from,
            workflow_files=args.workflow_file, verification=args.verification,
            auto_merge=args.auto_merge))
        return 0
    if args.cmd == "images":
        texts = []
        for path in args.compose:
            with open(path, encoding="utf-8") as fh:
                texts.append(fh.read())
        missing = 0
        for ref in compose_images(texts, args.tag, args.extra, os.environ):
            if not args.check:
                print(ref)
                continue
            try:
                ok = image_exists(ref)
            except (urllib.error.URLError, OSError, ValueError) as err:
                ok = False
                print("error %s: %s" % (ref, err), file=sys.stderr)
            print("%s %s" % ("ok     " if ok else "MISSING", ref))
            missing += 0 if ok else 1
        return 1 if missing else 0
    if args.cmd == "deploy-major":
        with open(args.file, encoding="utf-8") as fh:
            major = deploy_major(fh.read())
        if major is None:
            return 1
        print(major)
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
