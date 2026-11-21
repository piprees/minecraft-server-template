#!/usr/bin/env python3
"""Tests for discord-sync's player links: Java usernames and Bedrock gamertags.

Drives the SHIPPED text: the player-links section is sliced out of
scripts/discord-sync.py by its banner comments and executed on its own.
Importing the module is not an option — it needs discord, aiohttp and mcrcon.
"""
import json
import re
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
SYNC_PY = SCRIPTS / "discord-sync.py"
MESSAGES = SCRIPTS.parent / "config" / "messages.json"
FLOODGATE_CONFIG = SCRIPTS.parent / "config" / "versions" / "26.3" / "floodgate" / "config.yml"
START = "# === Player links (Java and Bedrock)"
END = "# === End player links"


def load_section():
    text = SYNC_PY.read_text()
    if text.count(START) != 1:
        raise AssertionError(f"start anchor is not unique: {START!r}")
    i = text.index(START)
    j = text.index(END, i)
    username_re = re.search(r'^MC_USERNAME_RE = (re\.compile\(.*\))$', text, re.M).group(1)
    namespace = {"re": re}
    exec(f"MC_USERNAME_RE = {username_re}\n" + text[i:j], namespace)
    return namespace


L = load_section()


class Gamertags(unittest.TestCase):
    def test_classic_gamertags_are_accepted(self):
        for name in ("Steve", "Major Nelson", "xX Gamer 99 Xx", "A", "123456789012345"):
            self.assertTrue(L["valid_gamertag"](name), name)

    def test_malformed_gamertags_are_refused(self):
        for name in (
            "",
            " Steve",
            "Steve ",
            "Two  Spaces",
            "1234567890123456",
            "Major Nelson#881",
            "under_score",
            "dot.name",
            'Quote"Break',
            "Semi;colon",
            "New\nline",
            "Tab\tname",
            "Émile",
        ):
            self.assertFalse(L["valid_gamertag"](name), repr(name))


XUID = "2535428649631457"
FG_UUID = "00000000-0000-0000-0009-01f57c03bee1"
BEDROCK = ("bedrock", "Major Nelson", FG_UUID)
JAVA = ("java", "Steve", "Steve")


class Links(unittest.TestCase):
    def test_a_stored_string_is_a_java_link(self):
        self.assertEqual(L["parse_link"]("Steve"), JAVA)

    def test_a_stored_bedrock_link_round_trips(self):
        stored = L["make_link"]("bedrock", "Major Nelson", XUID)
        self.assertEqual(stored, {"platform": "bedrock", "name": "Major Nelson", "xuid": XUID})
        self.assertEqual(L["parse_link"](json.loads(json.dumps(stored))), BEDROCK)

    def test_a_java_link_is_stored_as_the_bare_name(self):
        self.assertEqual(L["make_link"]("java", "Steve"), "Steve")

    def test_the_floodgate_uuid_is_the_xuid_in_hex(self):
        # Measured on the 26.3 smoke stack: Floodgate logged this UUID being whitelisted.
        self.assertEqual(L["floodgate_uuid"](XUID), FG_UUID)
        self.assertEqual(L["floodgate_uuid"]("1"), "00000000-0000-0000-0000-000000000001")
        with self.assertRaises(ValueError):
            L["floodgate_uuid"](str(2**64))

    def test_invalid_stored_links_are_rejected(self):
        for stored in (
            "bad name",
            "x",
            None,
            42,
            {"platform": "bedrock", "name": 'a" ; op @a', "xuid": XUID},
            {"platform": "bedrock", "name": "Steve"},
            {"platform": "bedrock", "name": "Steve", "xuid": "12ab"},
            {"platform": "bedrock", "name": "Steve", "xuid": 2535428649631457},
            {"platform": "bedrock", "name": "Steve", "xuid": "9" * 20},
            {"platform": "java", "name": "Steve"},
            {"name": "Steve"},
        ):
            self.assertIsNone(L["parse_link"](stored), repr(stored))

    def test_same_player_compares_platform_and_name_case_insensitively(self):
        self.assertTrue(L["same_player"](JAVA, ("java", "steve", "steve")))
        self.assertFalse(L["same_player"](JAVA, ("bedrock", "Steve", FG_UUID)))


class InGameNames(unittest.TestCase):
    def test_bedrock_names_carry_the_floodgate_prefix_and_underscores(self):
        self.assertEqual(L["in_game_name"](BEDROCK), ".Major_Nelson")

    def test_java_names_are_unchanged(self):
        self.assertEqual(L["in_game_name"](JAVA), "Steve")

    def test_the_prefix_matches_the_shipped_floodgate_config(self):
        config = FLOODGATE_CONFIG.read_text()
        self.assertIn('username-prefix: "%s"' % L["FLOODGATE_PREFIX"], config)
        self.assertRegex(config, r"(?m)^replace-spaces: true$")


class Commands(unittest.TestCase):
    def test_java_role_sync_uses_the_vanilla_whitelist(self):
        self.assertEqual(L["role_sync_commands"](JAVA, True, True), ["whitelist add Steve", "op Steve"])
        self.assertEqual(L["role_sync_commands"](JAVA, False, False), ["whitelist remove Steve", "deop Steve"])
        self.assertEqual(L["role_sync_commands"](JAVA, True, False), ["whitelist add Steve", "deop Steve"])

    def test_bedrock_role_sync_uses_the_floodgate_uuid_and_the_prefixed_name(self):
        self.assertEqual(
            L["role_sync_commands"](BEDROCK, True, True),
            [f"fwhitelist add {FG_UUID}", "op .Major_Nelson"],
        )
        self.assertEqual(
            L["role_sync_commands"](BEDROCK, False, False),
            [f"fwhitelist remove {FG_UUID}", "deop .Major_Nelson"],
        )

    def test_unregister_reverses_by_platform(self):
        self.assertEqual(L["unregister_commands"](BEDROCK), [f"fwhitelist remove {FG_UUID}", "deop .Major_Nelson"])
        self.assertEqual(L["unregister_commands"](JAVA), ["whitelist remove Steve", "deop Steve"])

    def test_an_unvalidated_id_never_reaches_rcon(self):
        for link in (
            ("bedrock", "Major Nelson", "Major Nelson"),
            ("bedrock", "x", "00000000-0000-0000-0009-01f57c03bee1 run op @a"),
            ("java", "Steve op @a", "Steve op @a"),
        ):
            with self.assertRaises(ValueError):
                L["whitelist_command"](link, add=True)


class Messages(unittest.TestCase):
    def test_every_message_key_discord_sync_asks_for_exists(self):
        text = SYNC_PY.read_text()
        keys = set(re.findall(r'"(discord\.(?:register|unregister)\.[a-z_]+)"', text))
        self.assertGreaterEqual(len(keys), 10)
        messages = json.loads(MESSAGES.read_text())
        self.assertEqual(sorted(k for k in keys if k not in messages), [])

    def test_message_placeholders_are_the_ones_supplied(self):
        messages = json.loads(MESSAGES.read_text())
        supplied = {"name": "Steve", "player_role": "Player", "error": "boom"}
        for key, template in messages.items():
            if key.startswith(("discord.register.", "discord.unregister.")):
                template.format(**supplied)


if __name__ == "__main__":
    unittest.main()
