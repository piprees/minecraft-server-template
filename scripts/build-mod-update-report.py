#!/usr/bin/env python3
"""build-mod-update-report.py - Build a PR body summarising Modrinth mod version changes.

Diffs two modrinth-mods.txt snapshots (before/after `pin-mod-versions.sh --apply`),
fetches changelog and metadata for each changed version from the Modrinth API, and
writes a markdown report. Also flags mods that lost 1.21.x compatibility (FIXME
comments left by pin-mod-versions.sh).

Usage:
  python3 scripts/build-mod-update-report.py --old OLD_FILE --new NEW_FILE \
      --server-env .env.example --output OUTPUT_FILE

Prints `count=N` and `fixme_count=M` to stdout (GITHUB_OUTPUT format).
"""
import argparse
import json
import re
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
USER_AGENT = "minecraft-adventure-server/mod-update-pr"
MAX_BODY_CHARS = 60000
MAX_CHANGELOG_CHARS = 1500
FIXME_RE = re.compile(r"#\s*FIXME:\s*no [\d.]+x? build - (\S+)")


def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.load(resp)


def parse_mods(path):
    mods = {}
    with open(path) as f:
        for line in f:
            stripped = line.split("#", 1)[0].strip()
            if not stripped:
                continue
            if stripped.startswith("datapack:") or stripped.startswith("resourcepack:"):
                continue
            entry = stripped.rstrip("?")
            if ":" in entry:
                slug, ver_id = entry.split(":", 1)
            else:
                slug, ver_id = entry, ""
            mods[slug] = ver_id
    return mods


def find_fixmes(path):
    fixmes = []
    with open(path) as f:
        for line in f:
            m = FIXME_RE.search(line)
            if m:
                fixmes.append(m.group(1))
    return fixmes


def read_mc_version(server_env_path):
    if server_env_path:
        try:
            with open(server_env_path) as f:
                for line in f:
                    if line.startswith("MC_VERSION="):
                        return line.strip().split("=", 1)[1]
        except FileNotFoundError:
            pass
    return "1.21.1"


def chunked(seq, size):
    for i in range(0, len(seq), size):
        yield seq[i : i + size]


def bulk_fetch(endpoint, ids):
    results = {}
    ids = sorted(set(i for i in ids if i))
    for chunk in chunked(ids, 200):
        encoded = urllib.parse.quote(json.dumps(chunk))
        try:
            data = fetch_json(f"{API}/{endpoint}?ids={encoded}")
        except (urllib.error.URLError, json.JSONDecodeError):
            continue
        for item in data:
            results[item["id"]] = item
    return results


def truncate_changelog(text, limit, more_url):
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + f"\n\n_(changelog truncated - [full changelog on Modrinth]({more_url}))_"


def blockquote(text):
    if not text:
        return "> _(no changelog provided)_"
    return "\n".join(f"> {line}" for line in text.splitlines())


def build_body(updated, fixmes, mc_version, versions, projects):
    lines = [f"## Mod version updates for Minecraft `{mc_version}` (Fabric)\n"]

    if updated:
        lines.append(
            f"**{len(updated)}** mod(s) have newer builds available. "
            "This PR is not auto-merged - review the changelogs before merging.\n"
        )
        lines.append("| Mod | Old version | New version |")
        lines.append("| --- | --- | --- |")
        for slug, old_id, new_id in updated:
            v = versions.get(new_id, {})
            old_v = versions.get(old_id, {})
            new_ver_num = v.get("version_number", new_id)
            old_ver_num = old_v.get("version_number", old_id)
            badge = ""
            version_type = v.get("version_type")
            if version_type and version_type != "release":
                badge = f" ⚠️ {version_type}"
            url = f"https://modrinth.com/mod/{slug}/version/{new_id}"
            lines.append(f"| `{slug}` | {old_ver_num} | [{new_ver_num}]({url}){badge} |")
        lines.append("")

        lines.append("<details>")
        lines.append("<summary>Changelogs</summary>\n")
        for slug, old_id, new_id in updated:
            v = versions.get(new_id, {})
            project = projects.get(v.get("project_id", ""), {})
            title = project.get("title", slug)
            url = f"https://modrinth.com/mod/{slug}/version/{new_id}"
            published = (v.get("date_published") or "")[:10] or "unknown"
            changelog = truncate_changelog(v.get("changelog", ""), MAX_CHANGELOG_CHARS, url)
            lines.append(f"### {title} (`{slug}`)")
            lines.append(f"[View on Modrinth]({url}) · published {published}\n")
            lines.append(blockquote(changelog))
            lines.append("")
        lines.append("</details>\n")

    if fixmes:
        lines.append(f"## ⚠️ Needs attention - {len(fixmes)} mod(s) lost {mc_version} compatibility\n")
        lines.append(
            f"These mods no longer have a Fabric build for `{mc_version}` and were left "
            "unchanged. Check manually:\n"
        )
        for slug in fixmes:
            lines.append(f"- [`{slug}`](https://modrinth.com/mod/{slug}/versions)")
        lines.append("")

    lines.append("---")
    lines.append(
        "_Generated automatically by `.github/workflows/mod-updates.yml` from the "
        "Modrinth API. Not auto-merged - review changelogs before merging._"
    )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--old", required=True)
    parser.add_argument("--new", required=True)
    parser.add_argument("--server-env", default=None)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    old_mods = parse_mods(args.old)
    new_mods = parse_mods(args.new)
    fixmes = find_fixmes(args.new)
    mc_version = read_mc_version(args.server_env)

    updated = []
    for slug, new_id in sorted(new_mods.items()):
        old_id = old_mods.get(slug, "")
        if new_id and old_id and new_id != old_id:
            updated.append((slug, old_id, new_id))

    if not updated and not fixmes:
        with open(args.output, "w") as f:
            f.write("No mod version changes.\n")
        print("count=0")
        print("fixme_count=0")
        return

    all_version_ids = [vid for pair in updated for vid in (pair[1], pair[2])]
    versions = bulk_fetch("versions", all_version_ids)
    project_ids = [v.get("project_id", "") for v in versions.values()]
    projects = bulk_fetch("projects", project_ids)

    body = build_body(updated, fixmes, mc_version, versions, projects)
    if len(body) > MAX_BODY_CHARS:
        body = body[:MAX_BODY_CHARS] + "\n\n_(report truncated - see the branch diff for the full list)_"

    with open(args.output, "w") as f:
        f.write(body)

    print(f"count={len(updated)}")
    print(f"fixme_count={len(fixmes)}")


if __name__ == "__main__":
    main()
