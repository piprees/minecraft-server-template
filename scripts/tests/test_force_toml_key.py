#!/usr/bin/env python3
"""Tests for force-toml-key.py.

The value this guards is D6: useDensityFunctionCompiler left on generates
every custom dimension as a clone of the main world, and c2me strips the key
from its own config on every boot, so re-applying it has to work against a
file somebody else keeps rewriting.
"""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    assert spec and spec.loader, filename
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


force_toml_key = _load("force_toml_key", "force-toml-key.py")

SECTION = "[vanillaWorldGenOptimizations]"
KEY = "useDensityFunctionCompiler"


class ForceTomlKeyTests(unittest.TestCase):
    def with_file(self, contents=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "c2me.toml"
        if contents is not None:
            path.write_text(contents)
        return path

    def force(self, path, value="false"):
        return force_toml_key.force(str(path), SECTION, KEY, value)

    def test_an_absent_file_is_created_with_the_section(self):
        path = self.with_file()
        message = self.force(path)
        self.assertIn("created", message)
        self.assertIn(SECTION, path.read_text())
        self.assertIn("%s = false" % KEY, path.read_text())

    def test_an_existing_true_value_is_flipped(self):
        path = self.with_file("%s\n\t%s = true\n" % (SECTION, KEY))
        self.assertIsNotNone(self.force(path))
        self.assertIn("%s = false" % KEY, path.read_text())
        self.assertNotIn("= true", path.read_text())

    def test_a_key_already_correct_is_left_alone(self):
        path = self.with_file("%s\n\t%s = false\n" % (SECTION, KEY))
        before = path.read_text()
        self.assertIsNone(self.force(path), "an unchanged file must report nothing")
        self.assertEqual(before, path.read_text())

    def test_the_key_is_added_under_an_existing_section(self):
        path = self.with_file("%s\n\tsomethingElse = 3\n" % SECTION)
        self.assertIsNotNone(self.force(path))
        text = path.read_text()
        self.assertIn("somethingElse = 3", text)
        self.assertIn("%s = false" % KEY, text)
        self.assertEqual(text.count(SECTION), 1, "the section must not be duplicated")

    def test_a_missing_section_is_appended_without_losing_the_file(self):
        path = self.with_file("[other]\nkeep = 1\n")
        self.assertIsNotNone(self.force(path))
        text = path.read_text()
        self.assertIn("keep = 1", text)
        self.assertIn(SECTION, text)

    def test_other_keys_and_comments_survive(self):
        path = self.with_file(
            "# c2me wrote this\n%s\n\t%s = true\n\tother = 7\n# trailing\n"
            % (SECTION, KEY)
        )
        self.force(path)
        text = path.read_text()
        self.assertIn("# c2me wrote this", text)
        self.assertIn("other = 7", text)
        self.assertIn("# trailing", text)

    def test_it_is_idempotent(self):
        path = self.with_file("%s\n\t%s = true\n" % (SECTION, KEY))
        self.force(path)
        first = path.read_text()
        self.assertIsNone(self.force(path))
        self.assertEqual(first, path.read_text())

    def test_indentation_of_the_existing_line_is_preserved(self):
        path = self.with_file("%s\n    %s = true\n" % (SECTION, KEY))
        self.force(path)
        self.assertIn("    %s = false" % KEY, path.read_text())

    def test_a_commented_out_key_does_not_count_as_the_key(self):
        """A `#`-prefixed key must not be rewritten in place of the real one."""
        path = self.with_file("%s\n\t# %s = true\n" % (SECTION, KEY))
        self.force(path)
        text = path.read_text()
        self.assertIn("# %s = true" % KEY, text, "the comment must be left alone")
        self.assertRegex(text, r"(?m)^\s*%s = false" % KEY)


if __name__ == "__main__":
    unittest.main()
