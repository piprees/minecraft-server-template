#!/usr/bin/env python3
"""Tests for the stack version every artefact stamp and cache key resolves from.

Load-bearing in a quiet way: if this reports a release string where it should
report `dev`, caches key on a value that never moves and stale measurements
read as current — the exact failure the hand-set constants it replaced had.
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import stack_version  # noqa: E402


class VersionFileTests(unittest.TestCase):
    def with_version(self, contents):
        """Point the module at a VERSION file laid out as the bundle is."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / "scripts").mkdir()
        if contents is not None:
            (root / "VERSION").write_text(contents)
        original = stack_version._version_file
        stack_version._version_file = lambda: root / "VERSION"
        self.addCleanup(setattr, stack_version, "_version_file", original)
        return root

    def test_a_release_bundle_reports_its_tag(self):
        self.with_version("4.2.0\n")
        self.assertEqual(stack_version.stack_version(), "4.2.0")
        self.assertFalse(stack_version.is_dev())
        self.assertEqual(stack_version.cache_key(), "4.2.0")

    def test_a_linked_checkout_reports_dev(self):
        self.with_version("dev\n")
        self.assertEqual(stack_version.stack_version(), "dev")
        self.assertTrue(stack_version.is_dev())

    def test_no_version_file_is_dev_not_an_error(self):
        """A platform checkout has no VERSION at its root."""
        self.with_version(None)
        self.assertEqual(stack_version.stack_version(), "dev")
        self.assertTrue(stack_version.is_dev())

    def test_an_empty_version_file_is_dev(self):
        self.with_version("   \n")
        self.assertTrue(stack_version.is_dev())


class DevMarkerTests(unittest.TestCase):
    def test_every_marker_reads_as_dev(self):
        for marker in stack_version.DEV_MARKERS:
            self.assertTrue(stack_version.is_dev(marker), marker)

    def test_any_zero_version_reads_as_dev(self):
        """The mod's local marker must not depend on an exact spelling."""
        for value in ("0.0.0-local", "0.0.0-dev", "0.0.0"):
            self.assertTrue(stack_version.is_dev(value), value)

    def test_the_loader_fallback_reads_as_dev(self):
        """Artefacts.stackVersion answers "unknown" if the container is absent."""
        self.assertTrue(stack_version.is_dev("unknown"))

    def test_a_release_never_reads_as_dev(self):
        for version in ("4.2.0", "10.0.1", "v4.2.0"):
            self.assertFalse(stack_version.is_dev(version), version)

    def test_case_and_whitespace_do_not_smuggle_a_release_past_the_check(self):
        self.assertTrue(stack_version.is_dev(" DEV "))

    def test_cache_key_collapses_every_dev_marker_to_one_value(self):
        markers = list(stack_version.DEV_MARKERS) + ["0.0.0-local"]
        keys = {stack_version.cache_key(m) for m in markers}
        self.assertEqual(keys, {"dev"},
                         "dev builds must share one cache key, not fragment")


class GradlePropertiesTests(unittest.TestCase):
    def test_the_shipped_mod_default_is_a_dev_marker(self):
        """A release overrides it; anything else must not look like one."""
        props = (Path(__file__).resolve().parents[2]
                 / "mods/custom-dimensions/gradle.properties")
        value = None
        for line in props.read_text().splitlines():
            if line.startswith("mod_version="):
                value = line.split("=", 1)[1].strip()
        self.assertIsNotNone(value, "mod_version missing from gradle.properties")
        self.assertTrue(
            stack_version.is_dev(value),
            "mod_version=%r would stamp artefacts with a fake release" % value)


if __name__ == "__main__":
    unittest.main()
