#!/usr/bin/env python3
"""Tests for the mod-update partition into regular and worldgen variants.

Load-bearing: the regular variant is auto-merged and reaches every consumer
on the next minor release, where a worldgen change corrupts chunk borders on
existing worlds. A slug put in the wrong variant, or a pin lost between the
two, ships that damage silently. No network: Modrinth is a fake fetch
function and jars are zips built in memory.
"""
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import urllib.parse
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dep_review"))
import collect  # noqa: E402
import common  # noqa: E402
import partition  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
REAL_TXT = (REPO / collect.MODS_TXT).read_text(encoding="utf-8")
REAL_MANIFEST = (REPO / collect.MANIFEST).read_text(encoding="utf-8")
POLICY = common.load_policy()


def make_zip(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buf.getvalue()


class FakeModrinth:
    """Serves version metadata and file bytes for {vid: zip bytes}."""

    def __init__(self, jars, bad_hash=(), fail_download=(), fail_meta=False):
        self.jars, self.bad_hash, self.fail_download = jars, set(bad_hash), set(fail_download)
        self.fail_meta = fail_meta
        self.downloads = []

    def fetch_json(self, url):
        if self.fail_meta:
            raise OSError("api down")
        ids = json.loads(urllib.parse.unquote(url.split("ids=", 1)[1]))
        out = []
        for vid in ids:
            if vid not in self.jars:
                continue
            digest = "0" * 40 if vid in self.bad_hash else hashlib.sha1(self.jars[vid]).hexdigest()
            out.append({"id": vid, "files": [
                {"primary": False, "url": f"https://cdn/{vid}-sources.jar", "hashes": {"sha1": "f" * 40}},
                {"primary": True, "url": f"https://cdn/{vid}.jar", "hashes": {"sha1": digest}}]})
        return out

    def download(self, url):
        vid = url.rsplit("/", 1)[1][:-len(".jar")]
        self.downloads.append(vid)
        if vid in self.fail_download:
            raise OSError("connection reset")
        return self.jars[vid]


class ComparerCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def comparer(self, fake):
        c = partition.JarComparer(self.tmp.name, fetch_json=fake.fetch_json, download=fake.download)
        c.prefetch(list(fake.jars))
        return c


BASE = {"fabric.mod.json": "{}", "data/m/worldgen/biome/a.json": "{\"a\":1}",
        "data/m/recipe/r.json": "{}", "com/x/A.class": "code-v1"}
BASE2 = dict(BASE, **{"com/x/A.class": "code-v2"})


class JarComparisonTest(ComparerCase):
    def compare(self, old_entries, new_entries, **kw):
        fake = FakeModrinth({"OLD": make_zip(old_entries), "NEW": make_zip(new_entries)}, **kw)
        return self.comparer(fake).compare("OLD", "NEW")

    def test_identical_worldgen_is_regular_even_if_code_and_other_data_differ(self):
        new = dict(BASE, **{"com/x/A.class": "code-v2", "data/m/recipe/r.json": "{\"x\":2}"})
        self.assertEqual(self.compare(BASE, new), (False, "no worldgen data change"))

    def test_jar_without_worldgen_data_is_regular(self):
        self.assertEqual(self.compare({"a.class": "1"}, {"a.class": "2"})[0], False)

    def test_changed_worldgen_entry(self):
        new = dict(BASE, **{"data/m/worldgen/biome/a.json": "{\"a\":2}"})
        is_wg, why = self.compare(BASE, new)
        self.assertTrue(is_wg)
        self.assertEqual(why, "worldgen data changed: 1 files, e.g. data/m/worldgen/biome/a.json")

    def test_added_and_removed_entries(self):
        added = dict(BASE, **{"data/m/structure/tower.nbt": "nbt"})
        self.assertIn("data/m/structure/tower.nbt", self.compare(BASE, added)[1])
        removed = {k: v for k, v in BASE.items() if "worldgen" not in k}
        self.assertTrue(self.compare(BASE, removed)[0])

    def test_every_worldgen_path_family_counts(self):
        for path in ("data/m/structures/old.nbt", "data/minecraft/tags/worldgen/biome/has_structure/x.json",
                     "data/m/fabric/biome_modifiers/add.json", "data/m/forge/biome_modifier/add.json",
                     "data/m/neoforge/biome_modifier/add.json", "data/m/dimension/d.json",
                     "data/m/dimension_type/t.json", "pack/data/m/worldgen/noise/n.json"):
            with self.subTest(path=path):
                self.assertTrue(self.compare(BASE, dict(BASE, **{path: "x"}))[0])

    def test_non_worldgen_tags_do_not_count(self):
        self.assertFalse(self.compare(BASE, dict(BASE, **{"data/m/tags/item/x.json": "x"}))[0])

    def test_nested_jar_worldgen_counts_and_its_file_name_does_not(self):
        inner1 = make_zip({"data/lib/worldgen/configured_feature/f.json": "1"})
        inner2 = make_zip({"data/lib/worldgen/configured_feature/f.json": "2"})
        old = dict(BASE, **{"META-INF/jars/lib-1.0.jar": inner1})
        self.assertFalse(self.compare(old, dict(BASE, **{"META-INF/jars/lib-1.1.jar": inner1}))[0])
        self.assertTrue(self.compare(old, dict(BASE, **{"META-INF/jars/lib-1.1.jar": inner2}))[0])

    def test_download_failure_is_worldgen(self):
        is_wg, why = self.compare(BASE, BASE2, fail_download={"NEW"})
        self.assertTrue(is_wg)
        self.assertEqual(why, "could not compare jars: connection reset")

    def test_hash_mismatch_is_worldgen(self):
        is_wg, why = self.compare(BASE, BASE2, bad_hash={"OLD"})
        self.assertTrue(is_wg)
        self.assertIn("sha1 mismatch for OLD", why)

    def test_bad_zip_is_worldgen(self):
        fake = FakeModrinth({"OLD": make_zip(BASE), "NEW": b"not a zip"})
        is_wg, why = self.comparer(fake).compare("OLD", "NEW")
        self.assertTrue(is_wg)
        self.assertIn("NEW is not a zip", why)

    def test_metadata_failure_and_unknown_version_are_worldgen(self):
        fake = FakeModrinth({"OLD": make_zip(BASE), "NEW": make_zip(BASE)}, fail_meta=True)
        self.assertEqual(self.comparer(fake).compare("OLD", "NEW"),
                         (True, "could not compare jars: version metadata: api down"))
        fake = FakeModrinth({"OLD": make_zip(BASE)})
        self.assertEqual(self.comparer(fake).compare("OLD", "GONE"),
                         (True, "could not compare jars: version GONE not on Modrinth"))

    def test_missing_version_id_is_worldgen(self):
        fake = FakeModrinth({"OLD": make_zip(BASE)})
        self.assertTrue(self.comparer(fake).compare("OLD", None)[0])

    def test_cache_is_reused_and_verified(self):
        fake = FakeModrinth({"OLD": make_zip(BASE), "NEW": make_zip(BASE2)})
        self.comparer(fake).compare("OLD", "NEW")
        self.assertEqual(sorted(fake.downloads), ["NEW", "OLD"])
        cached = Path(self.tmp.name) / (hashlib.sha1(fake.jars["OLD"]).hexdigest() + ".bin")
        cached.write_bytes(b"corrupt")
        second = self.comparer(fake)
        self.assertFalse(second.compare("OLD", "NEW")[0])
        self.assertEqual(sorted(fake.downloads), ["NEW", "OLD", "OLD"])
        self.assertEqual(second.cache_hits, 1)


# --- classification and variants against the real files --------------------------

def repin(txt, manifest, server=None, client=None, packs=None):
    """Real files with pins substituted: {slug: new vid}."""
    lines = txt.split("\n")
    for slug, vid in (server or {}).items():
        idx = partition._find(lines, slug)
        old = collect.parse_server_list(lines[idx])[slug]["vid"]
        lines[idx] = lines[idx].replace(f"{slug}:{old}", f"{slug}:{vid}", 1)
    m = json.loads(manifest)
    for group in ("required", "optional"):
        m["_clientMods"][group] = [f"{e.split(':')[0]}:{client[e.split(':')[0]]}"
                                   if e.split(":")[0] in (client or {}) else e
                                   for e in m["_clientMods"][group]]
    for key in ("_resourcePacks", "_shaderPacks"):
        for i, e in enumerate(m[key]["packs"]):
            pin = e["slug"] if isinstance(e, dict) else e
            slug = pin.split(":")[0]
            if slug in (packs or {}):
                new = f"{slug}:{packs[slug]}"
                m[key]["packs"][i] = dict(e, slug=new) if isinstance(e, dict) else new
    return "\n".join(lines), partition.dump_manifest(m)


class FakeComparer:
    def __init__(self, worldgen_vids=()):
        self.worldgen_vids = set(worldgen_vids)
        self.compared = []

    def prefetch(self, vids):
        pass

    def compare(self, old, new):
        self.compared.append((old, new))
        if new in self.worldgen_vids:
            return True, "worldgen data changed: 1 files, e.g. data/x/worldgen/a.json"
        return False, "no worldgen data change"


def run(old, new, comparer=None, policy=POLICY, offline=False):
    return partition.partition(old[0], new[0], old[1], new[1], policy, comparer or FakeComparer(),
                               offline=offline, log=lambda _l: None)


def rows(report):
    return {k: {r["slug"]: r["why"] for r in v} for k, v in report.items()}


class RealFilesTest(unittest.TestCase):
    OLD = (REAL_TXT, REAL_MANIFEST)

    def test_policy_sections_exist_in_the_real_server_list(self):
        sections = {e["section"] for e in collect.parse_server_list(REAL_TXT).values()}
        for name in POLICY["never_automerge_sections"]:
            self.assertIn(name, sections)

    def test_real_file_and_manifest_round_trip(self):
        self.assertEqual(partition.revert_server(REAL_TXT, REAL_TXT, {"fabric-api", "carpet"}), REAL_TXT)
        self.assertEqual(partition.dump_manifest(json.loads(REAL_MANIFEST)), REAL_MANIFEST)

    def test_classification_order(self):
        policy = dict(POLICY, worldgen_slugs=["c2me-fabric", "better-leaves", "tectonic"])
        new = repin(*self.OLD,
                    server={"c2me-fabric": "C2ME0002", "tectonic": "TECT0002", "attributefix": "ATTR0002",
                            "krypton": "KRYP0002", "lithium": "LITH0002"},
                    client={"lithium": "LITH0002"}, packs={"better-leaves": "LEAF0002"})
        report, _ = run(self.OLD, new, FakeComparer(worldgen_vids={"KRYP0002"}), policy=policy)
        got = rows(report)
        self.assertEqual(got["regular"], {"better-leaves": "resource/shader pack",
                                          "attributefix": "no worldgen data change",
                                          "lithium": "no worldgen data change"})
        self.assertEqual(got["worldgen"], {
            "c2me-fabric": "listed in worldgen_slugs", "tectonic": "listed in worldgen_slugs",
            "krypton": "worldgen data changed: 1 files, e.g. data/x/worldgen/a.json"})

    def test_section_rule_precedes_jar_comparison(self):
        new = repin(*self.OLD, server={"ati-structures-fabricforge": "ATIS0002", "lithostitched": "LITH0002"},
                    client={"lithostitched": "LITH0002"})
        comparer = FakeComparer()
        report, _ = run(self.OLD, new, comparer)
        self.assertEqual(rows(report)["worldgen"], {
            "ati-structures-fabricforge": "section boss dungeons & extra structures",
            "lithostitched": "section terrain & biomes"})
        self.assertEqual(comparer.compared, [])

    def test_client_only_change_uses_the_server_section(self):
        txt, manifest = repin(*self.OLD, client={"lithostitched": "LITH0002"})
        report, _ = run(self.OLD, (REAL_TXT, manifest))
        self.assertEqual(rows(report)["worldgen"], {"lithostitched": "section terrain & biomes"})

    def test_offline_is_worldgen(self):
        new = repin(*self.OLD, server={"krypton": "KRYP0002"})
        report, _ = run(self.OLD, new, offline=True)
        self.assertEqual(rows(report)["worldgen"], {"krypton": "could not compare jars: --offline"})

    def test_split_sides_are_worldgen_if_either_side_is(self):
        new = repin(*self.OLD, server={"fabric-api": "FAPI0002"}, client={"fabric-api": "FAPI0003"})
        report, variants = run(self.OLD, new, FakeComparer(worldgen_vids={"FAPI0003"}))
        self.assertEqual(list(rows(report)["worldgen"]), ["fabric-api"])
        self.assertEqual(len(report["worldgen"][0]["sides"]), 2)
        self.assertEqual(variants["regular"], self.OLD)
        self.assertEqual(variants["worldgen"], new)

    def test_variants(self):
        new = repin(*self.OLD,
                    server={"attributefix": "ATTR0002", "borrow-their-arrows": "BTAR0002",
                            "ati-structures-fabricforge": "ATIS0002", "fabric-api": "FAPI0002",
                            "krypton": "KRYP0002"},
                    client={"fabric-api": "FAPI0002", "sodium": "SODI0002"},
                    packs={"better-leaves": "LEAF0002"})
        report, variants = run(self.OLD, new, FakeComparer(worldgen_vids={"KRYP0002"}))
        self.assertEqual(set(rows(report)["regular"]),
                         {"attributefix", "borrow-their-arrows", "fabric-api", "sodium", "better-leaves"})
        self.assertEqual(set(rows(report)["worldgen"]), {"ati-structures-fabricforge", "krypton"})
        reg_txt, reg_manifest = variants["regular"]
        wg_txt, wg_manifest = variants["worldgen"]
        self.assertIn("\nattributefix:ATTR0002?\n", reg_txt)
        self.assertIn("\ndatapack:borrow-their-arrows:BTAR0002         # pick up arrows shot by mobs\n", reg_txt)
        self.assertIn("\ndatapack:ati-structures-fabricforge:K7cpaKjN  # ATI structures", reg_txt)
        self.assertIn("\nkrypton:Acz3ttTp\n", reg_txt)
        self.assertIn("\ndatapack:ati-structures-fabricforge:ATIS0002  # ATI structures", wg_txt)
        self.assertIn("\nkrypton:KRYP0002\n", wg_txt)
        self.assertIn("\nattributefix:XwbErf6s?\n", wg_txt)
        self.assertIn('"sodium:SODI0002"', reg_manifest)
        self.assertIn('"fabric-api:FAPI0002"', reg_manifest)
        self.assertIn('"better-leaves:LEAF0002"', reg_manifest)
        self.assertNotIn("SODI0002", wg_manifest)
        self.assertNotIn("LEAF0002", wg_manifest)
        self.assertEqual(wg_manifest, REAL_MANIFEST)
        self.assertEqual(json.loads(reg_manifest)["_holds"], json.loads(new[1])["_holds"])

    def test_empty_variant_is_byte_identical_to_old(self):
        new = repin(*self.OLD, server={"attributefix": "ATTR0002"}, packs={"better-leaves": "LEAF0002"})
        report, variants = run(self.OLD, new)
        self.assertEqual(report["worldgen"], [])
        self.assertEqual(variants["worldgen"], self.OLD)
        self.assertEqual(variants["regular"], new)

    def test_carried_hold_with_nothing_regular_does_not_break_the_invariant(self):
        m = json.loads(self.OLD[1])
        m["_holds"]["tectonic"] = "3.1 changes erosion defaults (dep-review: PR #30)"
        carried = (self.OLD[0], partition.dump_manifest(m))
        new = repin(*carried, server={"tectonic": "TECT0002"})
        report, variants = run(self.OLD, new)
        self.assertEqual(report["regular"], [])
        self.assertEqual(json.loads(variants["regular"][1])["_holds"], m["_holds"])
        self.assertEqual(variants["regular"][0], self.OLD[0])

    def test_added_and_removed_slugs_restore_old_bytes(self):
        lines = REAL_TXT.split("\n")
        carpet = partition._find(lines, "carpet")
        start = partition._attached_comment_start(lines, carpet)
        self.assertLess(start, carpet - 3)
        del lines[start:carpet + 1]
        lines.insert(partition._find(lines, "krypton") + 1, "# a brand new mod\nnew-worldgen-mod:NEWM0001")
        m = json.loads(REAL_MANIFEST)
        m["_clientMods"]["required"].remove("fabric-api:Nlt8gI9z")
        m["_clientMods"]["required"].append("new-client-mod:NEWC0001")
        m["_resourcePacks"]["packs"].pop(1)
        new = ("\n".join(lines), partition.dump_manifest(m))
        report, variants = run(self.OLD, new)
        got = rows(report)
        self.assertEqual(got["worldgen"], {s: "mod added/removed" for s in
                                           ("carpet", "fabric-api", "new-client-mod", "new-worldgen-mod")})
        self.assertEqual(len(got["regular"]), 1)
        self.assertEqual(variants["worldgen"], (new[0], partition.dump_manifest(dict(
            m, _resourcePacks=json.loads(REAL_MANIFEST)["_resourcePacks"]))))
        reg_txt, reg_manifest = variants["regular"]
        self.assertEqual(reg_txt, REAL_TXT)
        self.assertEqual(json.loads(reg_manifest)["_clientMods"], json.loads(REAL_MANIFEST)["_clientMods"])
        self.assertEqual(json.loads(reg_manifest)["_resourcePacks"]["packs"], m["_resourcePacks"]["packs"])

    def test_invariant_rejects_a_lost_pin(self):
        new = repin(*self.OLD, server={"attributefix": "ATTR0002"})
        with self.assertRaises(AssertionError):
            partition.check_invariant(self.OLD, new, self.OLD, self.OLD, {("mod", "attributefix")}, set())
        with self.assertRaises(AssertionError):
            partition.check_invariant(self.OLD, new, new, new, {("mod", "attributefix")},
                                      {("mod", "attributefix")})


class ServerLineTest(unittest.TestCase):
    def test_swap_preserves_markers_comments_and_alignment(self):
        cases = [("a:NEW00001?", "a:OLD00001?", "a:OLD00001?"),
                 ("datapack:x:NEW00001   # note", "datapack:x:OLD00001  # old note",
                  "datapack:x:OLD00001   # note"),
                 ("  s+(y)!:NEW00001  # c", "s+(y)!:OLD", "  s+(y)!:OLD       # c"),
                 ("a:NEW00001", "a:OLD00001?", "a:OLD00001?")]
        for line, old, want in cases:
            with self.subTest(line=line):
                self.assertEqual(partition._swap_token(line, old), want)

    def test_removed_slug_is_restored_in_its_old_position(self):
        old = "# === a ===\nx:1\n\n# about y\ny:1\nz:1\n\n# === b ===\n# about w\nw:1\n"
        new = "# === a ===\nx:1\n\nz:1\n\n# === b ===\n"
        self.assertEqual(partition.revert_server(new, old, {"y", "w"}), old)
        self.assertEqual(partition.revert_server(old, new, {"y", "w"}), new)


class CliTest(unittest.TestCase):
    def test_cli_writes_variants_report_and_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "repo"
            (root / "config").mkdir(parents=True)
            (root / "modpack").mkdir()
            (root / collect.MODS_TXT).write_text(REAL_TXT, encoding="utf-8")
            (root / collect.MANIFEST).write_text(REAL_MANIFEST, encoding="utf-8")
            env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t",
                       GIT_COMMITTER_EMAIL="t@t")
            for cmd in (["init", "-q"], ["add", "."], ["commit", "-qm", "base"]):
                subprocess.run(["git", *cmd], cwd=root, env=env, check=True)
            txt, manifest = repin(REAL_TXT, REAL_MANIFEST, server={"krypton": "KRYP0002"},
                                  packs={"better-leaves": "LEAF0002"})
            (root / collect.MODS_TXT).write_text(txt, encoding="utf-8")
            (root / collect.MANIFEST).write_text(manifest, encoding="utf-8")
            out, gh = Path(tmp) / "out", Path(tmp) / "gh"
            proc = subprocess.run(
                [sys.executable, str(Path(partition.__file__)), "--old-ref", "HEAD", "--out-dir", str(out),
                 "--offline", "--github-output", str(gh)], cwd=root, capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            self.assertIn("  krypton: worldgen - could not compare jars: --offline", proc.stdout)
            self.assertIn("  better-leaves: regular - resource/shader pack", proc.stdout)
            self.assertEqual(gh.read_text(), "regular=1\nworldgen=1\n")
            report = json.loads((out / "partition.json").read_text())
            self.assertEqual(report["old_ref"], "HEAD")
            self.assertEqual(report["worldgen"], [{"slug": "krypton", "ecosystem": "mod", "old": "Acz3ttTp",
                                                   "new": "KRYP0002",
                                                   "why": "could not compare jars: --offline"}])
            self.assertEqual((out / "regular" / collect.MODS_TXT).read_text(encoding="utf-8"), REAL_TXT)
            self.assertEqual((out / "regular" / collect.MANIFEST).read_text(encoding="utf-8"), manifest)
            self.assertEqual((out / "worldgen" / collect.MODS_TXT).read_text(encoding="utf-8"), txt)
            self.assertEqual((out / "worldgen" / collect.MANIFEST).read_text(encoding="utf-8"), REAL_MANIFEST)


if __name__ == "__main__":
    unittest.main()
