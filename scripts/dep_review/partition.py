#!/usr/bin/env python3
"""partition.py - split a weekly mod re-pin into a regular half and a worldgen half.

Context: mod-updates.yml re-pins every mod with `pin-mod-versions.sh --apply`.
Consumers float on every minor platform release, and worldgen cannot be undone
on chunks already generated (TROUBLESHOOTING.md#d2), so the re-pin is split:
regular updates go to the auto-mergeable PR (mod-updates/auto), worldgen
updates to mod-updates/next-major, merged by a human when cutting a major.
Anything uncertain is worldgen. Each changed slug is classified, first match
wins:
  1. a resource/shader pack                          -> regular
  2. listed in policy worldgen_slugs                 -> worldgen
  3. its section is in never_automerge_sections      -> worldgen
  4. added or removed                                -> worldgen
  5. the worldgen data inside the old and new files  -> worldgen if it differs,
     regular if identical (entries under data/<ns>/{worldgen,structure(s),
     tags/worldgen,dimension,dimension_type}/ and
     data/<ns>/{fabric,forge,neoforge}/biome_modifier(s)/, nested jars in
     META-INF/jars/ included)
  6. any failure in 5, or --offline                  -> worldgen

Outputs in --out-dir:
  regular/config/modrinth-mods.txt, regular/modpack/adventure.mrpack.json
      the working-tree files with every worldgen slug reverted to --old-ref
  worldgen/...  the same two files with every regular slug reverted instead
  partition.json  {"regular": [...], "worldgen": [...], "old_ref": REF}

Usage (from the repo root, after pin-mod-versions.sh has rewritten the files):
  python3 scripts/dep_review/partition.py --old-ref REF --out-dir DIR \
      [--cache DIR] [--policy PATH] [--offline] [--github-output PATH]

Gotchas:
  - A slug's server and client pins always land in the same variant. When the
    two sides moved to different versions, the slug is worldgen if either
    side's comparison says so.
  - A client-only change is classified by the slug's section in the server
    list, when it has one.
  - Reverting an added slug removes its line with the comment block directly
    above it. Restoring a removed slug puts its line and comment block back
    after the nearest preceding entry from the old file, in its old position.
  - Jars are cached in --cache by sha1 and verified on every read; without
    --cache they go to a temporary directory.
  - The script exits non-zero if the two variants together do not reproduce
    the new pins exactly, or if a variant with no changes differs from the
    old files anywhere but `_holds`. Both variants carry the new `_holds`
    (mod-updates.yml carries dep-review's holds in before re-pinning).
  - Standard library only.
"""
import argparse
import copy
import hashlib
import io
import json
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import collect  # noqa: E402
import common  # noqa: E402

MODS_TXT = collect.MODS_TXT
MANIFEST = collect.MANIFEST
DOWNLOAD_TIMEOUT = 120
DOWNLOAD_RETRIES = 2
NESTED_DEPTH = 3

WORLDGEN_RE = re.compile(
    r"^(?:[^/]+/)?data/[^/]+/(?:worldgen|structures?|tags/worldgen|dimension|dimension_type)/"
    r"|^(?:[^/]+/)?data/[^/]+/(?:fabric|forge|neoforge)/biome_modifiers?/")
NESTED_JAR_RE = re.compile(r"^META-INF/jars/[^/]+\.jar$")


def sha1(data):
    return hashlib.sha1(data).hexdigest()


# --- jar comparison --------------------------------------------------------------

def worldgen_entries(data, depth=0):
    """{path: sha1} for every worldgen data entry in a zip, nested jars included."""
    out = {}
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            name = info.filename
            if WORLDGEN_RE.match(name):
                out[name] = sha1(zf.read(info))
            elif depth < NESTED_DEPTH and NESTED_JAR_RE.match(name):
                for inner, digest in worldgen_entries(zf.read(info), depth + 1).items():
                    key = "META-INF/jars/*!/" + inner
                    out[key] = sha1((out[key] + digest).encode()) if key in out else digest
    return out


def diff_entries(old, new):
    return sorted(p for p in set(old) | set(new) if old.get(p) != new.get(p))


def download(url):
    for attempt in range(DOWNLOAD_RETRIES + 1):
        req = urllib.request.Request(url, headers={"User-Agent": collect.USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as resp:
                return resp.read()
        except (urllib.error.URLError, TimeoutError):
            if attempt >= DOWNLOAD_RETRIES:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError(f"unreachable: {url}")


class JarComparer:
    """Compares the worldgen data of two Modrinth versions.

    `fetch_json(url)` and `download(url) -> bytes` are injectable for tests.
    """

    def __init__(self, cache_dir, fetch_json=collect.http_get_json, download=download):
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.modrinth = collect.Modrinth(fetch=fetch_json, sleep=lambda _s: None)
        self.download = download
        self.meta = {}
        self.meta_error = None
        self.entries = {}
        self.bytes_downloaded = 0
        self.cache_hits = 0

    def prefetch(self, vids):
        try:
            self.meta.update(self.modrinth.versions([v for v in vids if v]))
        except Exception as exc:  # noqa: BLE001 - every failure is reported as worldgen
            self.meta_error = f"version metadata: {exc}"

    def _file(self, vid):
        meta = self.meta.get(vid)
        if meta is None:
            raise LookupError(self.meta_error or f"version {vid} not on Modrinth")
        files = meta.get("files") or []
        chosen = next((f for f in files if f.get("primary")), files[0] if files else None)
        if not chosen or not chosen.get("url") or not (chosen.get("hashes") or {}).get("sha1"):
            raise LookupError(f"version {vid} has no file with a sha1")
        return chosen["url"], chosen["hashes"]["sha1"].lower()

    def _bytes(self, vid):
        url, want = self._file(vid)
        path = self.cache_dir / f"{want}.bin"
        if path.exists():
            data = path.read_bytes()
            if sha1(data) == want:
                self.cache_hits += 1
                return data
        data = self.download(url)
        self.bytes_downloaded += len(data)
        if sha1(data) != want:
            raise ValueError(f"sha1 mismatch for {vid}")
        path.write_bytes(data)
        return data

    def worldgen(self, vid):
        if vid not in self.entries:
            try:
                self.entries[vid] = worldgen_entries(self._bytes(vid))
            except zipfile.BadZipFile as exc:
                self.entries[vid] = ValueError(f"{vid} is not a zip: {exc}")
            except Exception as exc:  # noqa: BLE001 - every failure is reported as worldgen
                self.entries[vid] = exc
        result = self.entries[vid]
        if isinstance(result, Exception):
            raise result
        return result

    def compare(self, old, new):
        """(is_worldgen, why)."""
        if not old or not new:
            return True, "could not compare jars: no version id"
        try:
            changed = diff_entries(self.worldgen(old), self.worldgen(new))
        except Exception as exc:  # noqa: BLE001
            return True, f"could not compare jars: {exc}"
        if changed:
            return True, f"worldgen data changed: {len(changed)} files, e.g. {changed[0]}"
        return False, "no worldgen data change"


# --- classification --------------------------------------------------------------

def _rule(change, policy, sections):
    """First matching rule 1-4 as (is_worldgen, why), or None to compare jars."""
    if change["ecosystem"] == "pack":
        return False, "resource/shader pack"
    if change["name"] in policy.get("worldgen_slugs", []):
        return True, "listed in worldgen_slugs"
    section = (change.get("details") or {}).get("section") or sections.get(change["name"])
    if section in policy.get("never_automerge_sections", []):
        return True, f"section {section}"
    if change["old"] is None or change["new"] is None:
        return True, "mod added/removed"
    return None


def classify(changes, policy, sections, comparer, offline):
    """[(key, is_worldgen, why, [changes])] per (ecosystem, slug), sorted."""
    grouped = {}
    for change in changes:
        grouped.setdefault((change["ecosystem"], change["name"]), []).append(change)
    pending = [c for group in grouped.values() for c in group if _rule(c, policy, sections) is None]
    if pending and not offline:
        comparer.prefetch({v for c in pending for v in (c["old"], c["new"])})
    out = []
    for key in sorted(grouped):
        verdicts = []
        for change in grouped[key]:
            verdict = _rule(change, policy, sections)
            if verdict is None:
                verdict = (True, "could not compare jars: --offline") if offline \
                    else comparer.compare(change["old"], change["new"])
            verdicts.append(verdict)
        worldgen = [v for v in verdicts if v[0]]
        is_wg, why = (True, worldgen[0][1]) if worldgen else (False, verdicts[0][1])
        out.append((key, is_wg, why, grouped[key]))
    return out


# --- server list rewriting -------------------------------------------------------

def _line_entry(line):
    """(slug, token_start, token_end) for an entry line, else None; parsed as collect does."""
    stripped = line.strip()
    if not stripped or stripped.startswith("#"):
        return None
    body = line.partition("#")[0]
    token = body.strip()
    start = body.index(token)
    entry = token.rstrip("?")
    if entry.startswith("datapack:"):
        entry = entry[len("datapack:"):]
    return entry.partition(":")[0], start, start + len(token)


def _attached_comment_start(lines, idx):
    """First index of the comment block collect attaches to the entry at idx."""
    start = idx
    while start > 0:
        prev = lines[start - 1].strip()
        if not prev.startswith("#") or collect.SECTION_RE.match(prev) or collect.RULE_RE.match(prev):
            break
        start -= 1
    return start


def _find(lines, slug):
    for i, line in enumerate(lines):
        entry = _line_entry(line)
        if entry and entry[0] == slug:
            return i
    return None


def _swap_token(line, old_line):
    _slug, start, end = _line_entry(line)
    _oslug, ostart, oend = _line_entry(old_line)
    token, rest = old_line[ostart:oend], line[end:]
    if rest.strip().startswith("#"):
        gap = len(rest) - len(rest.lstrip())
        gap = max(1, gap - (len(token) - (end - start)))
        rest = " " * gap + rest.lstrip()
    return line[:start] + token + rest


def revert_server(new_txt, old_txt, slugs):
    """new_txt with every slug in `slugs` put back to its old_txt state."""
    lines = new_txt.split("\n")
    old_lines = old_txt.split("\n")
    for slug in sorted(slugs):
        idx, oidx = _find(lines, slug), _find(old_lines, slug)
        if idx is not None and oidx is not None:
            lines[idx] = _swap_token(lines[idx], old_lines[oidx])
        elif idx is not None:
            start = _attached_comment_start(lines, idx)
            del lines[start:idx + 1]
            if 0 < start < len(lines) and not lines[start - 1].strip() and not lines[start].strip():
                del lines[start]
        elif oidx is not None:
            ostart = _attached_comment_start(old_lines, oidx)
            block = old_lines[ostart:oidx + 1]
            lines[_insert_at(lines, old_lines, ostart):0] = block
    return "\n".join(lines)


def _insert_at(lines, old_lines, ostart):
    """Where a block that started at old_lines[ostart] belongs in lines."""
    for anchor in range(ostart - 1, -1, -1):
        entry = _line_entry(old_lines[anchor])
        if entry is None:
            continue
        pos = _find(lines, entry[0])
        if pos is None:
            continue
        between = old_lines[anchor + 1:ostart]
        pos += 1
        if lines[pos:pos + len(between)] == between:
            pos += len(between)
        return pos
    section = None
    for line in old_lines[:ostart]:
        match = collect.SECTION_RE.match(line.strip())
        if match:
            section = line
    if section is not None and section in lines:
        return lines.index(section) + 1
    return len(lines) - (1 if lines and lines[-1] == "" else 0)


# --- manifest rewriting ----------------------------------------------------------

def _pin_str(slug, vid):
    return f"{slug}:{vid}" if vid else slug


def _entry_slug(entry):
    return collect._pin(entry)[0]


def _lists(manifest, ecosystem):
    if ecosystem == "mod":
        groups = manifest.get("_clientMods") or {}
        return [(("_clientMods", g), groups.get(g)) for g in ("required", "optional")
                if isinstance(groups.get(g), list)]
    out = []
    for key in ("_resourcePacks", "_shaderPacks"):
        packs = (manifest.get(key) or {}).get("packs")
        if isinstance(packs, list):
            out.append(((key, "packs"), packs))
    return out


def _revert_entry(lst, idx, old_entry):
    if isinstance(lst[idx], dict) and isinstance(old_entry, dict):
        entry = copy.deepcopy(lst[idx])
        entry["slug"] = old_entry.get("slug")
        lst[idx] = entry
    else:
        lst[idx] = copy.deepcopy(old_entry)


def revert_manifest(new_obj, old_obj, keys):
    """A copy of new_obj with every (ecosystem, slug) in `keys` put back to old_obj's state."""
    out = copy.deepcopy(new_obj)
    for ecosystem, slug in sorted(keys):
        new_lists = dict(_lists(out, ecosystem))
        old_lists = dict(_lists(old_obj, ecosystem))
        for where in sorted(set(new_lists) | set(old_lists)):
            lst, old_lst = new_lists.get(where), old_lists.get(where) or []
            if lst is None:
                continue
            idx = next((i for i, e in enumerate(lst) if _entry_slug(e) == slug), None)
            oidx = next((i for i, e in enumerate(old_lst) if _entry_slug(e) == slug), None)
            if idx is not None and oidx is not None:
                _revert_entry(lst, idx, old_lst[oidx])
            elif idx is not None:
                del lst[idx]
            elif oidx is not None:
                pos = 0
                for anchor in range(oidx - 1, -1, -1):
                    aslug = _entry_slug(old_lst[anchor])
                    found = next((i for i, e in enumerate(lst) if _entry_slug(e) == aslug), None)
                    if found is not None:
                        pos = found + 1
                        break
                lst.insert(pos, copy.deepcopy(old_lst[oidx]))
    return out


def dump_manifest(obj):
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


# --- invariant -------------------------------------------------------------------

def pin_state(txt, manifest_text):
    """{(ecosystem, slug): (server entry, client vid, pack vid)} for every slug on any side."""
    server = collect.parse_server_list(txt)
    m = collect.parse_manifest(manifest_text)
    state = {}
    for slug in set(server) | set(m["client"]):
        s = server.get(slug)
        state[("mod", slug)] = ((s["vid"], s["optional"], s["kind"]) if s else None,
                                m["client"].get(slug, "absent"), None)
    for slug, pack in m["packs"].items():
        state[("pack", slug)] = (None, None, pack["vid"])
    return state


def _sans_holds(files):
    """(server list, manifest without `_holds`): both variants carry the carried-over holds."""
    txt, manifest = files
    obj = json.loads(manifest)
    obj.pop("_holds", None)
    return txt, dump_manifest(obj)


def check_invariant(old, new, regular, worldgen, reg_keys, wg_keys):
    """Raise AssertionError unless each variant carries exactly its own slugs' new pins."""
    problems = []
    if reg_keys & wg_keys:
        problems.append(f"slugs in both variants: {sorted(reg_keys & wg_keys)}")
    states = {name: pin_state(*files) for name, files in
              (("old", old), ("new", new), ("regular", regular), ("worldgen", worldgen))}
    for key in sorted(set().union(*states.values())):
        o, n = states["old"].get(key), states["new"].get(key)
        r, w = states["regular"].get(key), states["worldgen"].get(key)
        if o != n and key not in reg_keys | wg_keys:
            problems.append(f"{key} changed but is in neither variant")
        want_r = n if key in reg_keys else o
        want_w = n if key in wg_keys else o
        if r != want_r or w != want_w:
            problems.append(f"{key}: regular={r} worldgen={w}, want {want_r}/{want_w}")
    for name, keys, files in (("regular", reg_keys, regular), ("worldgen", wg_keys, worldgen)):
        if not keys and _sans_holds(files) != _sans_holds(old):
            problems.append(f"{name} variant has no changes but differs from the old files")
    if problems:
        raise AssertionError("partition invariant broken:\n  " + "\n  ".join(problems))


# --- main ------------------------------------------------------------------------

def partition(old_txt, new_txt, old_manifest, new_manifest, policy, comparer, offline=False,
              log=print):
    """Returns (report, {"regular": (txt, manifest), "worldgen": (txt, manifest)})."""
    changes = collect.mod_changes(old_txt, new_txt, old_manifest, new_manifest)
    sections = {slug: e["section"] for side in (collect.parse_server_list(old_txt),
                                                collect.parse_server_list(new_txt))
                for slug, e in side.items() if e["section"]}
    verdicts = classify(changes, policy, sections, comparer, offline)
    report = {"regular": [], "worldgen": []}
    for (ecosystem, slug), is_wg, why, group in verdicts:
        row = {"slug": slug, "ecosystem": ecosystem, "old": group[0]["old"], "new": group[0]["new"],
               "why": why}
        if len(group) > 1:
            row["sides"] = [{"sides": c["sides"], "old": c["old"], "new": c["new"]} for c in group]
        report["worldgen" if is_wg else "regular"].append(row)
        log(f"  {slug}: {'worldgen' if is_wg else 'regular'} - {why}")
    reg_keys = {(r["ecosystem"], r["slug"]) for r in report["regular"]}
    wg_keys = {(r["ecosystem"], r["slug"]) for r in report["worldgen"]}
    old_obj, new_obj = json.loads(old_manifest), json.loads(new_manifest)

    def variant(revert_keys):
        txt = revert_server(new_txt, old_txt, {s for e, s in revert_keys if e == "mod"})
        return txt, dump_manifest(revert_manifest(new_obj, old_obj, revert_keys))

    variants = {"regular": variant(wg_keys), "worldgen": variant(reg_keys)}
    check_invariant((old_txt, old_manifest), (new_txt, new_manifest), variants["regular"],
                    variants["worldgen"], reg_keys, wg_keys)
    return report, variants


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--old-ref", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--cache")
    ap.add_argument("--policy", default=str(common.POLICY_PATH))
    ap.add_argument("--offline", action="store_true")
    ap.add_argument("--github-output")
    args = ap.parse_args(argv)

    old_txt, old_manifest = collect.git_show(args.old_ref, MODS_TXT), collect.git_show(args.old_ref, MANIFEST)
    if old_txt is None or old_manifest is None:
        sys.exit(f"partition.py: cannot read {MODS_TXT} and {MANIFEST} at {args.old_ref}")
    new_txt = Path(MODS_TXT).read_text(encoding="utf-8")
    new_manifest = Path(MANIFEST).read_text(encoding="utf-8")
    policy = common.load_policy(args.policy)

    started = time.monotonic()
    with tempfile.TemporaryDirectory() as tmp:
        comparer = JarComparer(args.cache or tmp)
        report, variants = partition(old_txt, new_txt, old_manifest, new_manifest, policy, comparer,
                                     offline=args.offline)
    report["old_ref"] = args.old_ref

    out = Path(args.out_dir)
    for name, (txt, manifest) in variants.items():
        (out / name / "config").mkdir(parents=True, exist_ok=True)
        (out / name / "modpack").mkdir(parents=True, exist_ok=True)
        (out / name / MODS_TXT).write_text(txt, encoding="utf-8")
        (out / name / MANIFEST).write_text(manifest, encoding="utf-8")
    (out / "partition.json").write_text(json.dumps(report, indent=2) + "\n")
    if args.github_output:
        with open(args.github_output, "a") as f:
            f.write(f"regular={len(report['regular'])}\nworldgen={len(report['worldgen'])}\n")
    print(f"partition: {len(report['regular'])} regular, {len(report['worldgen'])} worldgen; "
          f"{comparer.bytes_downloaded / 1e6:.1f} MB downloaded, {comparer.cache_hits} cached jars, "
          f"{time.monotonic() - started:.1f}s")


if __name__ == "__main__":
    main()
