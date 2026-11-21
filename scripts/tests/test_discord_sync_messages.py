#!/usr/bin/env python3
"""discord-sync's messages: the image's copy with the seeded config volume's keys over it."""
import ast
import json
import logging
import re
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

SYNC_PY = Path(__file__).resolve().parent.parent / "discord-sync.py"
COMPOSE = Path(__file__).resolve().parents[2] / "docker-compose.yml"


def load(*names):
    src = SYNC_PY.read_text()
    namespace = {"json": json, "Path": Path, "log": logging.getLogger("test")}
    for node in ast.parse(src).body:
        if isinstance(node, ast.FunctionDef) and node.name in names:
            exec(compile(ast.get_source_segment(src, node), str(SYNC_PY), "exec"), namespace)
    return namespace


class LoadMessagesTests(unittest.TestCase):
    def setUp(self):
        self.load_messages = load("load_messages")["load_messages"]
        self.tmp = TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, content):
        path = self.dir / name
        path.write_text(content if isinstance(content, str) else json.dumps(content))
        return path

    def test_overlay_keys_win_and_defaults_fill_the_rest(self):
        base = self.write("base.json", {"a": "base a", "b": "base b"})
        overlay = self.write("overlay.json", {"b": "overlay b", "c": "overlay c"})
        self.assertEqual(self.load_messages((base, overlay)),
                         {"a": "base a", "b": "overlay b", "c": "overlay c"})

    def test_missing_overlay_keeps_defaults(self):
        base = self.write("base.json", {"a": "base a"})
        self.assertEqual(self.load_messages((base, self.dir / "absent.json")), {"a": "base a"})

    def test_broken_overlay_is_logged_and_skipped(self):
        base = self.write("base.json", {"a": "base a"})
        broken = self.write("broken.json", "{not json")
        with self.assertLogs("test", level="ERROR"):
            self.assertEqual(self.load_messages((base, broken)), {"a": "base a"})

    def test_non_object_overlay_is_skipped(self):
        base = self.write("base.json", {"a": "base a"})
        listing = self.write("list.json", ["a"])
        with self.assertLogs("test", level="ERROR"):
            self.assertEqual(self.load_messages((base, listing)), {"a": "base a"})

    def test_nothing_readable_is_empty(self):
        self.assertEqual(self.load_messages((self.dir / "x.json",)), {})


class ComposeTests(unittest.TestCase):
    def test_discord_sync_mounts_the_seeded_config(self):
        text = COMPOSE.read_text()
        block = re.search(r"\n  discord-sync:\n(.*?)\n  \S", text, re.S).group(1)
        self.assertIn("- stack-config:/config-vol:ro", block)


if __name__ == "__main__":
    unittest.main()
