#!/usr/bin/env python3
"""gate.py - decide whether a dependency-update PR may enter dep-review, and whether it needs reviewing.

Context: first step of .github/workflows/dep-review.yml. The repository is
public, so this is a security boundary: only PRs opened by Dependabot, by
mod-updates.yml or by platform-updates.yml, touching only the files their tool
is allowed to touch and changing only the lines that tool changes, get past it. Every check fails
closed. `evaluate()` holds all the logic and is pure; the `gh api` fetch
layer around it is kept thin so tests drive `evaluate()` directly.

Usage:
  python3 scripts/dep_review/gate.py --pr N --repo OWNER/NAME --out gate.json \
      [--github-output "$GITHUB_OUTPUT"] [--force]
  --force reviews even when the fingerprint matches the last review (skip is
  always false); the previous state is still reported, so its cached verdicts
  are reused.
  python3 scripts/dep_review/gate.py --from-dir DIR --repo OWNER/NAME --out gate.json
      DIR holds pr.json, commits.json, files.json, comments.json in GitHub API
      shape, plus manifest-base.json and manifest-head.json for a mod or platform PR
      (local debugging; needs no gh and no token).

Exit 0 whenever a verdict was written, including ok=false; the workflow reads
`ok`. Non-zero only when fetching or writing fails.

Gotchas:
  - `gh api --paginate` prints one JSON array per page back to back; `_gh_json`
    decodes the concatenated values and flattens them, so no `--slurp` is needed.
  - API patches carry hunks only (no ---/+++ file headers), so every line
    starting with + or - is content and is checked, including ones that begin
    "+++" or "---".
  - Dependabot sources must keep the same dependency names on both sides of
    each file: a patch may change versions, never which action, image or
    package is used.
  - The optional-mod marker in config/modrinth-mods.txt is a trailing "?"
    (pin-mod-versions.sh, resolve-mods.py), and real slugs contain + ( ) !.
  - A human clicking "Update branch" adds a commit authored by that human, so
    the PR is rejected until Dependabot rebases it (`@dependabot rebase`).
  - Dependabot commits must carry GitHub's signature: the login check alone
    trusts whatever email a pusher writes into a commit.
  - The mod PR's manifest is compared as JSON, not line by line: a
    "key": "value" line pattern would also pass an injected download URL.
  - The platform PR's allowed files and lines are platform_updates.py's own
    pin table (PINS, IMAGE_LINE, classify_line), read from main's checkout,
    so the gate and the updater cannot drift apart.
  - The fingerprint covers changed lines only; a rebase that changes no
    content keeps it, so the previous review is reused (skip=true).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import platform_updates  # noqa: E402

MAX_COMMITS = 50
MAX_FILES = 300

DEPENDABOT = "dependabot[bot]"
ACTIONS_BOT = "github-actions[bot]"
WEB_FLOW = "web-flow"

DEPENDABOT_ECOSYSTEMS = {"github_actions": "actions", "docker": "docker", "pip": "pip"}

# Nothing of ours pushes to a Dependabot branch, so its PRs hold Dependabot's
# own commits only, and GitHub signs those.
ALLOWED_AUTHORS = {
    "actions": {DEPENDABOT},
    "docker": {DEPENDABOT},
    "pip": {DEPENDABOT},
    "mods": {ACTIONS_BOT},
    "worldgen": {ACTIONS_BOT},
    "platform": {ACTIONS_BOT},
}
ALLOWED_COMMITTERS = {
    "actions": {DEPENDABOT, WEB_FLOW},
    "docker": {DEPENDABOT, WEB_FLOW},
    "pip": {DEPENDABOT, WEB_FLOW},
    "mods": {ACTIONS_BOT},
    "worldgen": {ACTIONS_BOT},
    "platform": {ACTIONS_BOT},
}
# mod-updates.yml's two branches: regular updates, and the worldgen updates
# held for the next major release.
MOD_BRANCHES = {"mod-updates/auto": "mods", "mod-updates/next-major": "worldgen"}
MOD_SOURCES = tuple(MOD_BRANCHES.values())
# platform-updates.yml's branch: compose images, Fabric loader/yarn/fabric-api, build tools.
PLATFORM_BRANCH = "platform-updates/auto"
PLATFORM = "platform"
MANIFEST = "modpack/adventure.mrpack.json"
# Lists mod-updates.yml re-pins in place; every other part of the manifest
# except `_holds` must be identical on both sides.
PINNED_LISTS = (("_clientMods", "required"), ("_clientMods", "optional"),
                ("_resourcePacks", "packs"), ("_shaderPacks", "packs"))
PIN_ID = re.compile(r":[A-Za-z0-9]{8}$")

FILE_PATTERNS = {
    "actions": re.compile(r"^(examples/consumer/)?\.github/workflows/[^/]+\.ya?ml$"),
    "docker": re.compile(r"^docker/[^/]+/Dockerfile$"),
    "pip": re.compile(r"^(scripts/requirements[^/]*|requirements-dev|docker/[^/]+/requirements)\.txt$"),
}

# The exact `git add` list of mod-updates.yml's "Commit and push" step.
MOD_FILES = (
    "config/modrinth-mods.txt",
    "modpack/adventure.mrpack.json",
    "scripts/data/structure-sets-extracted.json",
    "config/custom-dimensions/extractors/structures.json",
    "config/custom-dimensions/structure-groups.json",
)
MOD_PREFIXES = (
    "config/datapacks/",
    "config/datapack-presets/",
    "mods/custom-dimensions/src/main/resources/",
)

LINE_PATTERNS = {
    "actions": re.compile(r"^[+-]\s*(-\s+)?uses:\s*[\w.-]+/[\w./-]+@[\w.-]+(\s+#.*)?$"),
    "docker": re.compile(r"(?i)^[+-]FROM\s+(--platform=\S+\s+)?\S+(\s+AS\s+\S+)?\s*$"),
    "pip": re.compile(r"^[+-][A-Za-z0-9_.\[\],-]+\s*(==|>=|<=|~=|!=)\s*[^\s;#]+.*$"),
}
MOD_LIST_LINE = re.compile(r"^[+-](datapack:)?[a-z0-9_.+()!-]+:[A-Za-z0-9]{8}\??(\s+#.*)?\s*$")


def changed_lines(patch):
    """Added/removed lines of an API patch (hunks only, no file headers)."""
    return [line for line in (patch or "").splitlines() if line[:1] in ("+", "-")]


def dependency_name(source, line):
    """Name of the dependency a checked patch line refers to."""
    body = line[1:].strip()
    if source == "actions":
        ref = re.sub(r"^(-\s+)?uses:\s*", "", body).split()[0]
        return "/".join(ref.split("@", 1)[0].split("/")[:2])
    if source == "docker":
        parts = body.split()
        image = parts[2] if parts[1].lower().startswith("--platform=") else parts[1]
        stage = parts[-1].lower() if len(parts) >= 4 and parts[-2].upper() == "AS" else ""
        image = image.split("@", 1)[0]
        head, _, tail = image.rpartition(":")
        if head and "/" not in tail:
            image = head
        return f"{image.lower()} AS {stage}" if stage else image.lower()
    if source == "pip":
        name = re.split(r"\s*(==|>=|<=|~=|!=)", body, maxsplit=1)[0]
        return re.sub(r"[-_.]+", "-", name.split("[", 1)[0]).lower()
    return None


def mod_file_allowed(name, status):
    if name in MOD_FILES:
        return status == "modified"
    if name.startswith(MOD_PREFIXES):
        return status in ("added", "modified", "removed")
    return False


def fingerprint(files):
    entries = []
    for f in files:
        name = f.get("filename", "")
        if f.get("patch") is None:
            entries.append(f"{name}:{f.get('sha')}")
            continue
        entries.extend(f"{name}:{line}" for line in changed_lines(f["patch"]))
    return hashlib.sha256("\n".join(sorted(entries)).encode()).hexdigest()


def identify_source(pr):
    login = (pr.get("user") or {}).get("login")
    ref = (pr.get("head") or {}).get("ref") or ""
    if login == DEPENDABOT and ref.startswith("dependabot/"):
        segment = ref.split("/")[1] if ref.count("/") >= 2 else ""
        source = DEPENDABOT_ECOSYSTEMS.get(segment)
        if not source:
            return None, f"unsupported Dependabot ecosystem {segment!r} (branch {ref})"
        return source, None
    if login == ACTIONS_BOT and ref in MOD_BRANCHES:
        return MOD_BRANCHES[ref], None
    if login == ACTIONS_BOT and ref == PLATFORM_BRANCH:
        return PLATFORM, None
    return None, f"PR by {login!r} on branch {ref!r} is not a recognised dependency-update source"


def check_pr(pr, repo):
    if pr.get("state") != "open":
        return f"PR is {pr.get('state')!r}, not open"
    if (pr.get("base") or {}).get("ref") != "main":
        return f"base branch is {(pr.get('base') or {}).get('ref')!r}, not main"
    head_repo = ((pr.get("head") or {}).get("repo") or {}).get("full_name")
    if head_repo != repo:
        return f"head repository {head_repo!r} is not {repo} (forks are not reviewed)"
    if pr.get("draft") is not False:
        return "PR is a draft"
    return None


def check_commits(source, commits):
    if not commits:
        return "PR has no commits"
    if len(commits) > MAX_COMMITS:
        return f"PR has {len(commits)} commits (limit {MAX_COMMITS})"
    for c in commits:
        sha = (c.get("sha") or "")[:12]
        author = (c.get("author") or {}).get("login")
        committer = (c.get("committer") or {}).get("login")
        if author not in ALLOWED_AUTHORS[source]:
            return f"commit {sha} authored by {author!r}, not an allowed bot"
        if committer not in ALLOWED_COMMITTERS[source]:
            return f"commit {sha} committed by {committer!r}, not an allowed bot"
        if author == DEPENDABOT and not ((c.get("commit") or {}).get("verification") or {}).get("verified"):
            return f"commit {sha} claims Dependabot but is not signed by GitHub"
    return None


def _unpinned(entry):
    """A pinned list entry with its version id masked, for shape comparison."""
    if isinstance(entry, str):
        return PIN_ID.sub(":ID", entry)
    if isinstance(entry, dict) and isinstance(entry.get("slug"), str):
        return {**entry, "slug": PIN_ID.sub(":ID", entry["slug"])}
    return entry


def check_manifest(base, head):
    """The mod PR may re-pin existing manifest entries and edit `_holds`; nothing else."""
    if not isinstance(base, dict) or not isinstance(head, dict):
        return f"{MANIFEST} could not be read on both sides"
    holds = head.get("_holds", {})
    if not isinstance(holds, dict) or not all(isinstance(v, str) for v in holds.values()):
        return f"{MANIFEST}: _holds must map slugs to reason strings"
    shapes = []
    for side in (base, head):
        side = json.loads(json.dumps(side))
        side.pop("_holds", None)
        for section, key in PINNED_LISTS:
            entries = (side.get(section) or {}).get(key)
            if isinstance(entries, list):
                side[section][key] = [_unpinned(e) for e in entries]
        shapes.append(side)
    if shapes[0] != shapes[1]:
        return f"{MANIFEST} changes more than version pins and holds"
    return None


def check_files(source, files):
    if not files:
        return "PR changes no files"
    if len(files) > MAX_FILES:
        return f"PR changes {len(files)} files (limit {MAX_FILES})"
    for f in files:
        name, status = f.get("filename") or "", f.get("status")
        if source in MOD_SOURCES:
            if not mod_file_allowed(name, status):
                return f"{name} ({status}) is outside what mod-updates.yml may change"
            if name == "config/modrinth-mods.txt":
                reason = check_mod_list_patch(f)
                if reason:
                    return reason
            continue
        if source == PLATFORM:
            if not platform_updates.allowed_path(name):
                return f"{name} is outside what platform-updates.yml may change"
            if status != "modified":
                return f"{name} is {status!r}; platform updates only modify files"
            reason = check_platform_patch(f)
            if reason:
                return reason
            continue
        if not FILE_PATTERNS[source].match(name):
            return f"{name} is outside what a Dependabot {source} update may change"
        if status != "modified":
            return f"{name} is {status!r}; Dependabot updates only modify files"
        reason = check_dependabot_patch(source, f)
        if reason:
            return reason
    return None


def check_dependabot_patch(source, f):
    name = f["filename"]
    if f.get("patch") is None:
        return f"{name} has no patch to inspect"
    lines = changed_lines(f["patch"])
    if not lines:
        return f"{name} has no changed lines"
    removed, added = [], []
    for line in lines:
        if not LINE_PATTERNS[source].match(line):
            return f"{name}: changed line is not a version bump: {line[:160]!r}"
        (added if line[0] == "+" else removed).append(dependency_name(source, line))
    if sorted(added) != sorted(removed):
        return f"{name}: dependency names differ between removed {sorted(set(removed))} and added {sorted(set(added))}"
    return None


def check_platform_patch(f):
    """Every changed line is a pin platform_updates.py rewrites, and the lines differ only in their versions."""
    name = f["filename"]
    if f.get("patch") is None:
        return f"{name} has no patch to inspect"
    lines = changed_lines(f["patch"])
    if not lines:
        return f"{name} has no changed lines"
    removed, added = [], []
    for line in lines:
        masked = platform_updates.mask_line(name, line[1:])
        if masked is None:
            return f"{name}: changed line is not a platform pin: {line[:160]!r}"
        (added if line[0] == "+" else removed).append(masked)
    if sorted(added) != sorted(removed):
        return f"{name}: lines differ in more than their versions: removed {sorted(set(removed))} added {sorted(set(added))}"
    return None


def check_platform_manifest(base, head):
    """The platform PR may change the manifest's fabric-loader dependency and nothing else."""
    if not isinstance(base, dict) or not isinstance(head, dict):
        return f"{MANIFEST} could not be read on both sides"
    loader = (head.get("dependencies") or {}).get("fabric-loader")
    if not isinstance(loader, str) or not re.fullmatch(r"\d+(\.\d+)+", loader):
        return f"{MANIFEST}: fabric-loader {loader!r} is not a release version"
    sides = []
    for side in (base, head):
        side = json.loads(json.dumps(side))
        (side.get("dependencies") or {}).pop("fabric-loader", None)
        sides.append(side)
    if sides[0] != sides[1]:
        return f"{MANIFEST} changes more than the fabric-loader dependency"
    return None


def check_mod_list_patch(f):
    if f.get("patch") is None:
        return "config/modrinth-mods.txt has no patch to inspect"
    for line in changed_lines(f["patch"]):
        text = line[1:].strip()
        if not text or text.startswith("#"):
            continue
        if not MOD_LIST_LINE.match(line):
            return f"config/modrinth-mods.txt: unexpected line {line[:160]!r}"
    return None


def evaluate(pr, commits, files, comments, repo, policy, force=False, manifests=(None, None)):
    """Pure verdict over GitHub API objects. Never raises on malformed input.

    force=True never skips; the previous state is still reported so its
    cached verdicts are reused. manifests is (base, head) of the modpack
    manifest, required for the mod PR when it changes that file.
    """
    names = [f.get("filename") or "" if isinstance(f, dict) else "" for f in files or []]
    result = {
        "ok": False,
        "reason": "",
        "skip": False,
        "pr": pr.get("number"),
        "source": None,
        "head_sha": (pr.get("head") or {}).get("sha"),
        "base_sha": (pr.get("base") or {}).get("sha"),
        "head_ref": (pr.get("head") or {}).get("ref"),
        "fingerprint": None,
        "state_comment_id": None,
        "previous_state": None,
        "needs_smoke": False,
        "dockerfiles": [],
        "worldgen_paths": [],
        "files": names,
    }
    try:
        reason = check_pr(pr, repo)
        source = None
        if not reason:
            source, reason = identify_source(pr)
        if not reason:
            result["source"] = source
            reason = check_commits(source, commits or []) or check_files(source, files or [])
        if not reason and source in MOD_SOURCES and MANIFEST in names:
            reason = check_manifest(*manifests)
        if not reason and source == PLATFORM and MANIFEST in names:
            reason = check_platform_manifest(*manifests)
    except (AttributeError, IndexError, KeyError, TypeError, ValueError) as exc:
        reason = f"malformed API data: {exc!r}"
    if reason:
        result["reason"] = reason
        return result

    prefixes = tuple(policy.get("never_automerge_paths", []))
    comment_id, state = common.latest_state(comments or [])
    result.update(
        ok=True,
        reason="accepted",
        fingerprint=fingerprint(files),
        state_comment_id=comment_id,
        previous_state=state,
        needs_smoke=source in MOD_SOURCES or source == PLATFORM or "docker/defaults-seed/Dockerfile" in names,
        dockerfiles=[n for n in names if n.endswith("/Dockerfile")],
        worldgen_paths=[n for n in names if prefixes and n.startswith(prefixes)],
    )
    result["skip"] = not force and state is not None and state.get("fingerprint") == result["fingerprint"]
    if result["skip"]:
        result["reason"] = "content unchanged since the last review"
    return result


# --- fetch layer --------------------------------------------------------------

def _gh_json(path, paginate=False):
    cmd = ["gh", "api", "-H", "Accept: application/vnd.github+json"]
    if paginate:
        cmd.append("--paginate")
    out = subprocess.run(cmd + [path], check=True, capture_output=True, text=True).stdout
    decoder, pos, values = json.JSONDecoder(), 0, []
    while pos < len(out):
        while pos < len(out) and out[pos].isspace():
            pos += 1
        if pos >= len(out):
            break
        value, pos = decoder.raw_decode(out, pos)
        values.append(value)
    if not paginate:
        return values[0]
    flat = []
    for value in values:
        flat.extend(value if isinstance(value, list) else [value])
    return flat


def fetch(repo, number):
    base = f"repos/{repo}"
    return (
        _gh_json(f"{base}/pulls/{number}"),
        _gh_json(f"{base}/pulls/{number}/commits?per_page=100", paginate=True),
        _gh_json(f"{base}/pulls/{number}/files?per_page=100", paginate=True),
        _gh_json(f"{base}/issues/{number}/comments?per_page=100", paginate=True),
    )


def fetch_manifests(repo, pr):
    """(base, head) manifest JSON at the PR's two commits."""
    sides = []
    for sha in ((pr.get("base") or {}).get("sha"), (pr.get("head") or {}).get("sha")):
        out = subprocess.run(
            ["gh", "api", "-H", "Accept: application/vnd.github.raw+json",
             f"repos/{repo}/contents/{MANIFEST}?ref={sha}"],
            check=True, capture_output=True, text=True).stdout
        sides.append(json.loads(out))
    return tuple(sides)


def load_dir(directory):
    d = Path(directory)
    return tuple(json.loads((d / f"{n}.json").read_text()) for n in ("pr", "commits", "files", "comments"))


def load_dir_manifests(directory):
    d = Path(directory)
    paths = (d / "manifest-base.json", d / "manifest-head.json")
    return tuple(json.loads(p.read_text()) if p.exists() else None for p in paths)


def write_github_output(path, result):
    def flag(v):
        return "true" if v else "false"

    with open(path, "a") as f:
        f.write(f"ok={flag(result['ok'])}\n")
        f.write(f"skip={flag(result['skip'])}\n")
        f.write(f"source={result['source'] or ''}\n")
        f.write(f"head_sha={result['head_sha'] or ''}\n")
        f.write(f"base_sha={result['base_sha'] or ''}\n")
        f.write(f"needs_smoke={flag(result['needs_smoke'])}\n")
        f.write(f"dockerfiles={json.dumps(result['dockerfiles'], separators=(',', ':'))}\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pr", type=int)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--github-output")
    ap.add_argument("--from-dir")
    ap.add_argument("--force", action="store_true", help="review even when the fingerprint is unchanged")
    ap.add_argument("--policy", default=str(common.POLICY_PATH))
    args = ap.parse_args(argv)
    if not args.from_dir and not args.pr:
        ap.error("--pr is required unless --from-dir is given")
    if not args.from_dir and not os.environ.get("GH_TOKEN"):
        print("gate: GH_TOKEN is not set", file=sys.stderr)
    pr, commits, files, comments = load_dir(args.from_dir) if args.from_dir else fetch(args.repo, args.pr)
    manifests = (None, None)
    if any(f.get("filename") == MANIFEST for f in files if isinstance(f, dict)):
        manifests = load_dir_manifests(args.from_dir) if args.from_dir else fetch_manifests(args.repo, pr)
    result = evaluate(pr, commits, files, comments, args.repo, common.load_policy(args.policy),
                      force=args.force, manifests=manifests)
    Path(args.out).write_text(json.dumps(result, indent=2) + "\n")
    if args.github_output:
        write_github_output(args.github_output, result)
    print(f"gate: ok={result['ok']} skip={result['skip']} source={result['source']} - {result['reason']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
