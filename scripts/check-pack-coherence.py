#!/usr/bin/env python3
"""Check the client pack's mods for dependency conflicts BEFORE players do.

Scans every jar in a mods directory, parses fabric.mod.json, and evaluates
each mod's `depends`/`breaks` version predicates against the mods actually
present in the pack. This is exactly the check Fabric Loader runs at launch
(the "Incompatible mods found!" screen) - running it at build time catches
resolver regressions before players hit them: a resolver can silently
downgrade one mod below another's stated minimum version and ship a pack
that crashes at launch.

Predicate support is the practical Fabric subset: *, exact, >=, >, <=, <,
~ (same minor), ^ (same major), space-separated AND, list-of-strings OR.
Unparseable predicates are reported as SKIP, never as failures.

Only conflicts BETWEEN pack mods are reported - depends on mods that aren't
in the pack (bundled jar-in-jars, fabric itself, java) are out of scope.

Usage:
  python3 scripts/check-pack-coherence.py <mods-dir>     # exit 1 on conflicts
  ssh deploy@HOST 'python3 - ~/server/modpack/dist/mods' < scripts/check-pack-coherence.py
"""
import json
import os
import re
import sys
import zipfile
from pathlib import Path


def _key(nums_str, pre):
    nums = tuple(int(p) for p in nums_str.split("."))
    # No prerelease sorts AFTER any prerelease of the same core (semver rule)
    pre_key = (1,) if not pre else (0, tuple(
        (0, int(p)) if p.isdigit() else (1, p) for p in pre.split(".")))
    return (nums + (0, 0, 0))[:4], pre_key


def parse_ver(s):
    """Loose semver: split numeric core from prerelease; build metadata ignored.

    Handles mod-style decorations: "0.6.13+mc1.21.1", "1.21.1-3.7.7" (MC
    prefix), "mc1.21.1-0.8.12-beta.2-fabric" (prefix + loader suffix). The
    core is the LAST purely-numeric dotted segment; anything after it is
    the prerelease."""
    s = s.split("+", 1)[0]
    s = re.sub(r"-(fabric|forge|neoforge|quilt)$", "", s, flags=re.I)
    segs = s.split("-")
    # An "mcX.Y.Z" segment is Minecraft metadata, not this mod's version.
    # Keep it only when it is all we have ("mc1.21.1-0.8.12" style prefixes
    # still need the rest); dropping it stops "2.2.4-mc1.21.1" reading as
    # 1.21.1 under the last-numeric-segment rule below.
    stripped = [g for g in segs if not re.fullmatch(r"mc\d+(?:\.\d+)*", g, flags=re.I)]
    if stripped:
        segs = stripped
    idx = None
    for i, seg in enumerate(segs):
        if re.fullmatch(r"(?:mc|v)?(\d+(?:\.\d+)*)", seg):
            idx = i
    if idx is None:
        return None
    core = re.sub(r"^(?:mc|v)", "", segs[idx])
    return _key(core, "-".join(segs[idx + 1:]))


def cmp_ver(a, b):
    if a is None or b is None:
        raise ValueError("unparseable")
    return -1 if a < b else (1 if a > b else 0)


def satisfies(version, predicate):
    """True/False, or None when the predicate can't be evaluated."""
    v = parse_ver(version)
    if v is None:
        return None
    preds = predicate if isinstance(predicate, list) else [predicate]
    results = []
    for pred in preds:  # list = OR
        ok = True
        for term in str(pred).split():  # space-separated = AND
            if term in ("*", ""):
                continue
            m = re.match(r"^(>=|<=|>|<|\^|~|=)?(.+)$", term)
            if not m:
                return None
            op, target = m.group(1) or "=", parse_ver(m.group(2))
            if target is None:
                return None
            try:
                c = cmp_ver(v, target)
            except ValueError:
                return None
            if op == ">=" and c < 0: ok = False
            elif op == ">" and c <= 0: ok = False
            elif op == "<=" and c > 0: ok = False
            elif op == "<" and c >= 0: ok = False
            elif op == "=" and c != 0: ok = False
            elif op == "^" and (v[0][0] != target[0][0] or c < 0): ok = False
            elif op == "~" and (v[0][:2] != target[0][:2] or c < 0): ok = False
        results.append(ok)
    return any(results)


def main():
    mods_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "modpack/dist/mods")
    installed = {}   # mod id -> (version, jar name)
    constraints = [] # (from_jar, kind, target_id, predicate)

    unreadable = []
    for jar in sorted(mods_dir.glob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as z:
                raw = z.read("fabric.mod.json").decode("utf-8", "replace")
            # Mods ship JSON Fabric accepts and json.loads does not: trailing
            # commas, and raw newlines inside description strings.
            meta = json.loads(re.sub(r",(\s*[}\]])", r"\1", raw), strict=False)
        except Exception as exc:
            unreadable.append(f"{jar.name}: {exc}")
            continue
        installed[meta["id"]] = (str(meta.get("version", "?")), jar.name)
        for kind in ("depends", "breaks"):
            for target, pred in (meta.get(kind) or {}).items():
                constraints.append((jar.name, kind, target, pred))

    # Honour the same overrides Fabric Loader honours, so the gate and the
    # runtime cannot disagree. Format is the loader's own
    # config/fabric_loader_dependencies.json: {"version":1,"overrides":{
    # "<modid>": {"-breaks": {"<target>": "<range>"}}}}. Only removals are
    # applied here; an added constraint is the author's problem, not a
    # conflict we invented.
    removed = set()
    ov_path = Path(os.environ.get(
        "LOADER_DEPENDENCY_OVERRIDES",
        "modpack/overrides/config/fabric_loader_dependencies.json"))
    if ov_path.is_file():
        try:
            ov = json.loads(ov_path.read_text(encoding="utf-8"))
            for mod_id, spec in (ov.get("overrides") or {}).items():
                for key, targets in (spec or {}).items():
                    if not key.startswith("-"):
                        continue
                    for target in (targets or {}):
                        removed.add((mod_id, key[1:], target))
        except Exception as exc:
            print(f"WARNING: could not read {ov_path}: {exc}")

    conflicts, skipped = [], 0
    for src, kind, target, pred in constraints:
        if target not in installed:
            continue  # not a pack mod - fabric loader's problem, not ours
        src_id = next((i for i, (_, j) in installed.items() if j == src), None)
        if (src_id, kind, target) in removed:
            continue  # overridden for the loader, so not a conflict here either
        version = installed[target][0]
        result = satisfies(version, pred)
        if result is None:
            skipped += 1
        elif kind == "depends" and not result:
            conflicts.append(f"{src} depends on {target} {pred!r} but {version} is present")
        elif kind == "breaks" and result:
            conflicts.append(f"{src} breaks with {target} {pred!r} and {version} is present")

    print(f"{len(installed)} mods, {len(constraints)} constraints checked, {skipped} unparseable (skipped)")
    # A jar we cannot read is a jar whose breaks we cannot evaluate, so a clean
    # report over one is a false pass — this is how sodium/betterend shipped.
    if unreadable:
        print(f"\n{len(unreadable)} JAR(S) COULD NOT BE READ — their constraints were NOT checked:")
        for u in unreadable:
            print(f"  ✗ {u}")
        sys.exit(1)
    if conflicts:
        print(f"\n{len(conflicts)} CONFLICT(S):")
        for c in conflicts:
            print(f"  ✗ {c}")
        sys.exit(1)
    print("No dependency conflicts between pack mods.")


if __name__ == "__main__":
    main()
