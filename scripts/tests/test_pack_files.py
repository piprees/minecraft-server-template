#!/usr/bin/env python3
"""Tests for pack_files.py — the pack-key identity, the options.txt and
iris.properties rewrites, the variant-preserving re-pin choice, and the CLI.

A wrong key either ships a pack silently disabled (a re-versioned file not
matched) or swaps a player's visual style (a different variant matched).
"""
import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import pack_files  # noqa: E402
from pack_files import (  # noqa: E402
    choose_version, derive_shader_pack, pack_key, rewrite_resource_packs,
    rewrite_shader_pack)

REPO = Path(__file__).resolve().parents[2]
OPTIONS = REPO / "modpack/overrides/configureddefaults/options.txt"
IRIS = REPO / "modpack/overrides/configureddefaults/config/iris.properties"

# options.txt line 51 as shipped with the PR #26 pins' predecessors.
LINE_51 = (
    'resourcePacks:["vanilla","fabric","file/server-panorama","file/Better-Leaves-9.5.zip",'
    '"file/Fancy Crops v1.3.zip","file/Simple Grass Flowers v2.0.0.zip",'
    '"file/Os\\u0027 Colorful Grasses (Short and Fluffy).zip","file/LowOnFire v26.2§8.zip",'
    '"seasons:seasonal_lush_caves","moonlight:merged_pack","continuity:default",'
    '"continuity:glass_pane_culling_fix","file/Dramatic Skys Demo 1.5.3.36.5.zip",'
    '"file/FreshAnimations_v1.10.4.zip","file/HEVI FreshAni Activator.zip",'
    '"file/HumanEraVillagersIllagers[3.91.5].zip","file/Quik\\u0027s Guards FA.zip",'
    '"natures_spirit:emissive_ores_compatibility","vinery:bushy_leaves","black_icons"]\n'
)
TEXT = "version:3955\n" + LINE_51 + "incompatibleResourcePacks:[]\nfov:0.5\n"
SHIPPED = {
    "Better-Leaves-9.5.zip", "Fancy Crops v1.3.zip", "Simple Grass Flowers v2.0.0.zip",
    "Os' Colorful Grasses (Short and Fluffy).zip", "LowOnFire v26.2§8.zip",
    "Dramatic Skys Demo 1.5.3.36.5.zip", "FreshAnimations_v1.10.4.zip",
    "HEVI FreshAni Activator.zip", "HumanEraVillagersIllagers[3.91.5].zip",
    "Quik's Guards FA.zip", "server-panorama",
}
PR26 = (SHIPPED - {"Better-Leaves-9.5.zip", "Dramatic Skys Demo 1.5.3.36.5.zip"}) | {
    "Better-Leaves-9.6.zip", "Dramatic Skys Demo 1.5.3.36.6.zip"}

EUPHORIA_OLD = "EuphoriaPatcher-1.9.3-r5.8.1-fabric.jar"
EUPHORIA_NEW = "EuphoriaPatcher-1.10.5-r5.9.3-fabric.jar"
COMP_OLD = "ComplementaryReimagined_r5.8.1.zip"
COMP_NEW = "ComplementaryReimagined_r5.9.3.zip"
CURRENT = "ComplementaryReimagined_r5.8.1 + EuphoriaPatches_1.9.3"


def entries(text):
    line = next(l for l in text.splitlines() if l.startswith("resourcePacks:"))
    return json.loads(line.split(":", 1)[1])


def version(vid, filename):
    return {"id": vid, "version_number": vid,
            "files": [{"filename": filename, "primary": True}]}


# os-colorful-grasses 1.21.1 versions, newest first, as Modrinth listed them.
GRASSES = [
    version("PQqK5ppM", "Os' Colorful Grasses (Tall).zip"),
    version("zjsHfa5V", "Os' Colorful Grasses (Short).zip"),
    version("9KieAB5z", "Os' Colorful Grasses (Mix).zip"),
    version("nsMgP48A", "Os' Colorful Grasses (MojangX).zip"),
    version("iq9TDzWh", "Os' Colorful Grasses (Mix).zip"),
    version("cvPClsvH", "Os' Colorful Grasses (Tall).zip"),
    version("7wx8gx2m", "Os' Colorful Grasses (Short).zip"),
    version("6y5oHV7t", "Os' Colorful Grasses (Short and Fluffy).zip"),
    version("BpTDmCMY", "Os' Colorful Grasses (Full and Fluffy).zip"),
    version("SuKgU0qQ", "Os' Colorful Grasses (Mojang Style).zip"),
]


class PackKeyTests(unittest.TestCase):
    SAME = [
        ("Better-Leaves-9.5.zip", "Better-Leaves-9.6.zip"),
        ("Dramatic Skys Demo 1.5.3.36.5.zip", "Dramatic Skys Demo 1.5.3.36.6.zip"),
        ("FreshAnimations_v1.10.4.zip", "FreshAnimations_v1.11.0.zip"),
        ("LowOnFire v26.2§8.zip", "LowOnFire v26.3§1.zip"),
        ("HumanEraVillagersIllagers[3.91.5].zip", "HumanEraVillagersIllagers[3.92.0].zip"),
        (COMP_OLD, COMP_NEW),
        ("Fancy Crops v1.3.zip", "Fancy Crops v1.4.zip"),
    ]
    DIFFERENT = [
        ("Os' Colorful Grasses (Short and Fluffy).zip", "Os' Colorful Grasses (Tall).zip"),
        ("Os' Colorful Grasses (Short and Fluffy).zip", "Os' Colorful Grasses (Short).zip"),
        ("Better-Leaves-9.5.zip", "Fancy Crops v1.3.zip"),
        ("HEVI FreshAni Activator.zip", "FreshAnimations_v1.10.4.zip"),
    ]

    def test_version_bumps_share_a_key(self):
        for a, b in self.SAME:
            with self.subTest(a=a, b=b):
                self.assertEqual(pack_key(a), pack_key(b))

    def test_variants_and_other_packs_do_not(self):
        for a, b in self.DIFFERENT:
            with self.subTest(a=a, b=b):
                self.assertNotEqual(pack_key(a), pack_key(b))

    def test_letters_next_to_a_v_or_r_survive(self):
        self.assertIn("villagers", pack_key("HumanEraVillagersIllagers[3.91.5].zip"))
        self.assertEqual(pack_key(COMP_OLD), "complementaryreimagined")
        self.assertEqual(pack_key("32x Pack.zip"), "32x pack")


class ResourcePackTests(unittest.TestCase):
    def test_real_line_is_unchanged_when_every_file_is_present(self):
        text = OPTIONS.read_text(encoding="utf-8")
        new, changes, errors = rewrite_resource_packs(text, SHIPPED)
        self.assertEqual((new, changes, errors), (text, [], []))

    def test_gson_encoding_round_trips_the_real_line(self):
        line = next(l for l in OPTIONS.read_text(encoding="utf-8").splitlines()
                    if l.startswith("resourcePacks:"))
        body = line.split(":", 1)[1]
        self.assertEqual(pack_files.gson_dumps(json.loads(body)), body)

    def test_pr26_renames_are_followed(self):
        new, changes, errors = rewrite_resource_packs(TEXT, PR26)
        self.assertEqual(errors, [])
        self.assertEqual(changes, [
            ("Better-Leaves-9.5.zip", "Better-Leaves-9.6.zip"),
            ("Dramatic Skys Demo 1.5.3.36.5.zip", "Dramatic Skys Demo 1.5.3.36.6.zip")])
        want = entries(TEXT)
        want[3] = "file/Better-Leaves-9.6.zip"
        want[12] = "file/Dramatic Skys Demo 1.5.3.36.6.zip"
        self.assertEqual(entries(new), want)
        expected = LINE_51.replace("Better-Leaves-9.5", "Better-Leaves-9.6").replace(
            "1.5.3.36.5", "1.5.3.36.6")
        self.assertEqual(new, "version:3955\n" + expected + "incompatibleResourcePacks:[]\nfov:0.5\n")
        self.assertIn("\\u0027", new)

    def test_crlf_endings_are_kept(self):
        text = TEXT.replace("\n", "\r\n")
        new, changes, _ = rewrite_resource_packs(text, PR26)
        self.assertEqual(len(changes), 2)
        self.assertEqual(new.count("\r\n"), text.count("\r\n"))

    def test_incompatible_line_is_untouched(self):
        text = TEXT.replace("incompatibleResourcePacks:[]",
                            'incompatibleResourcePacks:["file/Better-Leaves-9.5.zip"]')
        new, _, _ = rewrite_resource_packs(text, PR26)
        self.assertIn('incompatibleResourcePacks:["file/Better-Leaves-9.5.zip"]', new)

    def test_a_pack_with_no_download_fails(self):
        packs = SHIPPED - {"Fancy Crops v1.3.zip"}
        new, changes, errors = rewrite_resource_packs(TEXT, packs)
        self.assertEqual((new, changes), (TEXT, []))
        self.assertEqual(len(errors), 1)
        self.assertIn("Fancy Crops v1.3.zip", errors[0])

    def test_a_different_variant_is_not_a_replacement(self):
        packs = (SHIPPED - {"Os' Colorful Grasses (Short and Fluffy).zip"}) | {
            "Os' Colorful Grasses (Tall).zip"}
        _, changes, errors = rewrite_resource_packs(TEXT, packs)
        self.assertEqual(changes, [])
        self.assertEqual(len(errors), 1)

    def test_two_same_key_candidates_fail(self):
        packs = (SHIPPED - {"Better-Leaves-9.5.zip"}) | {
            "Better-Leaves-9.6.zip", "Better-Leaves-9.7.zip"}
        new, changes, errors = rewrite_resource_packs(TEXT, packs)
        self.assertEqual((new, changes), (TEXT, []))
        self.assertEqual(len(errors), 1)
        self.assertIn("2 downloads", errors[0])

    def test_a_file_another_entry_names_is_not_a_candidate(self):
        text = TEXT.replace('"file/Fancy Crops v1.3.zip"',
                            '"file/Fancy Crops v1.3.zip","file/Fancy Crops v1.2.zip"')
        new, changes, errors = rewrite_resource_packs(text, SHIPPED)
        self.assertEqual(changes, [])
        self.assertEqual(len(errors), 1)


class ShaderPackTests(unittest.TestCase):
    def test_matching_euphoria_is_selected(self):
        self.assertEqual(derive_shader_pack(CURRENT, {COMP_NEW}, {EUPHORIA_NEW, "sodium.jar"}),
                         ("ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5", []))

    def test_shipped_pins_reproduce_the_shipped_value(self):
        self.assertEqual(derive_shader_pack(CURRENT, {COMP_OLD}, {EUPHORIA_OLD}), (CURRENT, []))

    def test_without_euphoria_the_plain_zip_is_selected(self):
        self.assertEqual(derive_shader_pack(CURRENT, {COMP_NEW}, {"sodium.jar"}), (COMP_NEW, []))

    def test_a_mismatched_euphoria_warns_and_is_left_out(self):
        value, warnings = derive_shader_pack(CURRENT, {COMP_NEW}, {EUPHORIA_OLD})
        self.assertEqual(value, COMP_NEW)
        self.assertEqual(len(warnings), 1)
        self.assertIn("r5.8.1", warnings[0])

    def test_a_consumers_own_pack_is_kept(self):
        for current in ("BSL_v8.2.09", "BSL_v8.2.09.zip"):
            with self.subTest(current=current):
                self.assertEqual(
                    derive_shader_pack(current, {COMP_NEW, "BSL_v8.2.09.zip"}, {EUPHORIA_NEW}),
                    (current, []))

    def test_no_complementary_and_an_absent_selection_warns(self):
        value, warnings = derive_shader_pack(CURRENT, set(), {EUPHORIA_NEW})
        self.assertEqual(value, CURRENT)
        self.assertEqual(len(warnings), 1)

    def test_only_the_shaderpack_line_is_rewritten(self):
        text = IRIS.read_text(encoding="utf-8")
        new, old, value, _ = rewrite_shader_pack(text, {COMP_NEW}, {EUPHORIA_NEW})
        self.assertEqual(old, CURRENT)
        self.assertEqual(value, "ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5")
        self.assertEqual(new, text.replace(CURRENT, value))
        self.assertEqual(rewrite_shader_pack(text, {COMP_OLD}, {EUPHORIA_OLD})[0], text)


class ChooseVersionTests(unittest.TestCase):
    def test_colorful_grasses_stays_short_and_fluffy(self):
        chosen = choose_version("Os' Colorful Grasses (Short and Fluffy).zip", GRASSES)
        self.assertEqual(chosen["id"], "6y5oHV7t")

    def test_no_same_variant_returns_none(self):
        self.assertIsNone(choose_version("Os' Colorful Grasses (Short and Fluffy).zip", GRASSES[:7]))

    def test_the_newest_same_key_build_wins(self):
        builds = [version("new", "Better-Leaves-9.7.zip"), version("mid", "Better-Leaves-9.6.zip")]
        self.assertEqual(choose_version("Better-Leaves-9.5.zip", builds)["id"], "new")

    def test_a_version_without_a_primary_flag_uses_its_first_file(self):
        v = {"id": "x", "files": [{"filename": "Better-Leaves-9.6.zip", "primary": False}]}
        self.assertEqual(choose_version("Better-Leaves-9.5.zip", [v])["id"], "x")


class CliTests(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work)
        defaults = self.work / "overrides/configureddefaults"
        (defaults / "config").mkdir(parents=True)
        shutil.copy(OPTIONS, defaults / "options.txt")
        shutil.copy(IRIS, defaults / "config/iris.properties")
        self.options = defaults / "options.txt"
        self.iris = defaults / "config/iris.properties"
        rp = self.work / "overrides/resourcepacks"
        rp.mkdir(parents=True)
        for name in PR26:
            (rp / name).mkdir() if name == "server-panorama" else (rp / name).write_bytes(b"")
        sp = self.work / "overrides/shaderpacks"
        sp.mkdir()
        (sp / COMP_NEW).write_bytes(b"")
        index = {"files": [{"path": "mods/" + EUPHORIA_NEW}, {"path": "mods/sodium.jar"}]}
        (self.work / "modrinth.index.json").write_text(json.dumps(index))

    def run_cli(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = pack_files.main(["pack_files.py", "enable", str(self.work)])
        return code, out.getvalue(), err.getvalue()

    def test_a_bump_is_applied_and_exits_zero(self):
        code, out, err = self.run_cli()
        self.assertEqual((code, err), (0, ""))
        self.assertIn("file/Better-Leaves-9.6.zip", self.options.read_text(encoding="utf-8"))
        self.assertIn("shaderPack=ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5\n",
                      self.iris.read_text(encoding="utf-8"))
        self.assertEqual(out.count("  ✓"), 3)

    def test_a_second_run_changes_nothing(self):
        self.run_cli()
        before = (self.options.read_bytes(), self.iris.read_bytes())
        code, out, _ = self.run_cli()
        self.assertEqual((code, out), (0, ""))
        self.assertEqual((self.options.read_bytes(), self.iris.read_bytes()), before)

    def test_a_missing_pack_exits_one(self):
        (self.work / "overrides/resourcepacks/Fancy Crops v1.3.zip").unlink()
        code, _, err = self.run_cli()
        self.assertEqual(code, 1)
        self.assertIn("  ✗ options.txt enables 'Fancy Crops v1.3.zip'", err)

    def test_missing_parts_are_skipped(self):
        shutil.rmtree(self.work / "overrides/shaderpacks")
        self.iris.unlink()
        (self.work / "modrinth.index.json").unlink()
        self.assertEqual(self.run_cli()[0], 0)
        shutil.rmtree(self.work / "overrides")
        self.assertEqual(self.run_cli(), (0, "", ""))

    def test_bad_arguments_exit_two(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(pack_files.main(["pack_files.py"]), 2)


if __name__ == "__main__":
    unittest.main()
