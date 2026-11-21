#!/usr/bin/env python3
"""discord-sync's /mc status parsers, sliced out of the shipped script by name.

Importing the module needs discord, aiohttp and mcrcon; the parsers need only re.
"""
import ast
import re
import unittest
from pathlib import Path

SYNC_PY = Path(__file__).resolve().parent.parent / "discord-sync.py"

# The 26.3 server's reply to `tick query` over RCON, as captured from the smoke stack.
TICK_QUERY_26_3 = (
    "The game is running normallyTarget tick rate: 20.0 per second.\n"
    "\x1b[0mAverage time per tick: 0.6ms (Target: 50.0ms)Percentiles: P50: 0.5ms P95: 0.8ms P99: 2.5ms. Sample: 100"
)


def load(*names):
    tree = ast.parse(SYNC_PY.read_text())
    src = SYNC_PY.read_text()
    namespace = {"re": re}
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in names:
            exec(compile(ast.get_source_segment(src, node), str(SYNC_PY), "exec"), namespace)
    return namespace


def admin_action_re():
    src = SYNC_PY.read_text()
    line = next(l for l in src.splitlines() if l.strip().startswith('r"^\\[\\d{2}:'))
    return re.compile(ast.literal_eval(line.strip()))


class AdminActionTests(unittest.TestCase):
    def test_both_log_formats_match(self):
        rx = admin_action_re()
        m = rx.search("[21:12:26] [Server thread/INFO]: [Steve: Set own game mode to Creative Mode]")
        self.assertEqual(m.groups(), ("Steve", "Set own game mode to Creative Mode"))
        m = rx.search("[21:12:26] [Server thread/INFO]: System chat: [Steve: Gave 1 [Bread] to Alex]")
        self.assertEqual(m.groups(), ("Steve", "Gave 1 [Bread] to Alex"))

    def test_chat_cannot_spoof(self):
        rx = admin_action_re()
        self.assertIsNone(rx.search("[21:12:26] [Server thread/INFO]: <Steve> [Alex: Gave 64 [Diamond] to Steve]"))
        self.assertIsNone(rx.search("[21:12:26] [Server thread/INFO]: System chat: <Steve> [Alex: x]"))


class StatusParserTests(unittest.TestCase):
    def setUp(self):
        self.ns = load("parse_spark_health", "parse_tick_query")

    def test_tick_query_gives_tps_and_mspt(self):
        self.assertEqual(self.ns["parse_tick_query"](TICK_QUERY_26_3), {"tps": "20.0", "mspt": "0.6"})

    def test_tick_query_caps_tps_by_mspt(self):
        text = "Target tick rate: 20.0 per second.\nAverage time per tick: 100.0ms (Target: 50.0ms)"
        self.assertEqual(self.ns["parse_tick_query"](text), {"tps": "10.0", "mspt": "100.0"})

    def test_tick_query_empty_and_unrelated(self):
        self.assertEqual(self.ns["parse_tick_query"](""), {})
        self.assertEqual(self.ns["parse_tick_query"]("Unknown or incomplete command"), {})

    def test_spark_silence_falls_through(self):
        self.assertNotIn("tps", self.ns["parse_spark_health"](""))

    def test_status_uses_tick_query_only_without_spark_tps(self):
        src = SYNC_PY.read_text()
        body = src[src.index("async def mc_status"):]
        body = body[:body.index("\n@")]
        self.assertIn('if "tps" not in health:', body)
        self.assertIn('async_rcon("tick query")', body)
        self.assertLess(body.index("parse_spark_health"), body.index('async_rcon("tick query")'))


if __name__ == "__main__":
    unittest.main()
