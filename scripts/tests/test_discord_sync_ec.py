#!/usr/bin/env python3
"""discord-sync's Essential Commands admin actions: the RCON command each
handler sends and the offline-player check, sliced out of the shipped script.
"""
import ast
import asyncio
import unittest
from pathlib import Path

SYNC_PY = Path(__file__).resolve().parent.parent / "discord-sync.py"
SRC = SYNC_PY.read_text()
TREE = ast.parse(SRC)

# Replies captured over RCON for a name nobody online has: entity arguments
# (data get entity, effect) answer the first, player arguments (give, msg,
# gamemode, experience) the second; `execute as` answers nothing.
OFFLINE_REPLY = "No entity was found"
NO_PLAYER_REPLY = "No player was found"
EXECUTE_AS_OFFLINE_REPLY = ""
ONLINE_POS_REPLY = "Bot has the following entity data: [0.5d, -60.0d, 0.5d]"


def load(*names, **namespace):
    for node in TREE.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in names:
            exec(compile(ast.get_source_segment(SRC, node), str(SYNC_PY), "exec"), namespace)
    return namespace


def handler_source(name):
    node = next(n for n in TREE.body if isinstance(n, ast.AsyncFunctionDef) and n.name == name)
    return ast.get_source_segment(SRC, node)


class EcCommandTests(unittest.TestCase):
    def setUp(self):
        self.ec = load("ec_as_player")["ec_as_player"]

    def test_heal_feed_extinguish_run_as_the_player(self):
        self.assertEqual(self.ec("heal", "Steve"), "execute as Steve run heal")
        self.assertEqual(self.ec("feed", "Steve"), "execute as Steve run feed")
        self.assertEqual(self.ec("extinguish", "Steve"), "execute as Steve run extinguish")

    def test_toggles_target_self_explicitly(self):
        self.assertEqual(self.ec("fly", "Steve", "true"), "execute as Steve run fly @s true")
        self.assertEqual(self.ec("fly", "Steve", "false"), "execute as Steve run fly @s false")
        self.assertEqual(self.ec("invuln", "Alex_2", "true"), "execute as Alex_2 run invuln @s true")
        self.assertEqual(self.ec("invuln", "Alex_2", "false"), "execute as Alex_2 run invuln @s false")


class HandlerTests(unittest.TestCase):
    CALLS = {
        "mc_heal": 'ec_as_player("heal", player)',
        "mc_feed": 'ec_as_player("feed", player)',
        "mc_fly": 'ec_as_player("fly", player, toggle.value)',
        "mc_invuln": 'ec_as_player("invuln", player, toggle.value)',
        "mc_extinguish": 'ec_as_player("extinguish", player)',
    }

    def test_each_handler_sends_its_execute_as_form(self):
        for handler, call in self.CALLS.items():
            with self.subTest(handler=handler):
                self.assertIn(f"rcon_as_online_player(player, {call})", handler_source(handler))

    def test_each_handler_reports_an_offline_player(self):
        for handler in self.CALLS:
            with self.subTest(handler=handler):
                src = handler_source(handler)
                self.assertIn("if not await rcon_as_online_player(", src)
                self.assertIn("is not online", src)

    def test_no_handler_sends_the_console_form(self):
        for handler in (*self.CALLS, "mc_enchant"):
            with self.subTest(handler=handler):
                self.assertNotIn('async_rcon(f"', handler_source(handler))


class OnlineProbeTests(unittest.TestCase):
    def run_probe(self, probe_reply):
        sent = []

        async def async_rcon(cmd):
            sent.append(cmd)
            return probe_reply if cmd.startswith("data get entity") else EXECUTE_AS_OFFLINE_REPLY

        ns = load("player_offline", "rcon_as_online_player", async_rcon=async_rcon)
        ok = asyncio.run(ns["rcon_as_online_player"]("Steve", "execute as Steve run heal"))
        return ok, sent

    def test_online_player_gets_the_command(self):
        ok, sent = self.run_probe(ONLINE_POS_REPLY)
        self.assertTrue(ok)
        self.assertEqual(sent, ["data get entity Steve Pos", "execute as Steve run heal"])

    def test_offline_player_gets_nothing(self):
        ok, sent = self.run_probe(OFFLINE_REPLY)
        self.assertFalse(ok)
        self.assertEqual(sent, ["data get entity Steve Pos"])

    def test_rcon_failure_still_sends(self):
        ok, sent = self.run_probe(None)
        self.assertTrue(ok)
        self.assertEqual(len(sent), 2)


class OfflineTests(unittest.TestCase):
    def setUp(self):
        self.offline = load("player_offline")["player_offline"]

    def test_missing_player(self):
        self.assertTrue(self.offline(OFFLINE_REPLY))
        self.assertTrue(self.offline(NO_PLAYER_REPLY))

    def test_player_argument_handlers_use_the_check(self):
        for handler in ("mc_whisper", "mc_give", "mc_effect", "mc_gamemode", "mc_xp"):
            with self.subTest(handler=handler):
                self.assertIn("if player_offline(result):", handler_source(handler))

    def test_online_replies_and_silence(self):
        for reply in (ONLINE_POS_REPLY, EXECUTE_AS_OFFLINE_REPLY, None):
            with self.subTest(reply=reply):
                self.assertFalse(self.offline(reply))


if __name__ == "__main__":
    unittest.main()
