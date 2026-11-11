#!/usr/bin/env python3
"""Tests for discord-sync's start-up without Discord secrets.

Drives the SHIPPED text: the settings helpers and the main block are sliced out
of scripts/discord-sync.py by anchor strings and run against stubs. Importing
the module is not an option here — it needs discord, aiohttp and mcrcon.

The invariant under test: a stack with no Discord secrets (compose passes each
unset one as a blank string) gets a bot that logs one line and idles. An exit
there is a crash loop under the service's `restart: unless-stopped`.
"""
import logging
import os
import textwrap
import unittest
from pathlib import Path
from unittest import mock

SYNC_PY = Path(__file__).resolve().parent.parent / "discord-sync.py"

HELPERS = ("def env_int(name, default=0):", "DISCORD_BOT_TOKEN = ")
MAIN = ('if __name__ == "__main__":', None)


def slice_text(start, end):
    text = SYNC_PY.read_text()
    if text.count(start) != 1:
        raise AssertionError(f"start anchor is not unique: {start!r}")
    i = text.index(start)
    j = len(text) if end is None else text.index(end, i)
    return textwrap.dedent(text[i:j])


def helpers():
    scope = {"os": os, "log": logging.getLogger("test-discord-sync")}
    exec(slice_text(*HELPERS), scope)  # noqa: S102 - the shipped helpers
    return scope


class Idled(Exception):
    """Raised by the stub Event in place of waiting for ever."""


class FakeEvent:
    def wait(self):
        raise Idled


class FakeThreading:
    Event = FakeEvent


class FakeBot:
    def __init__(self):
        self.ran_with = None

    def run(self, token, log_handler=None):
        self.ran_with = token


def run_main(token, guild_id):
    scope = helpers()
    bot = FakeBot()
    records = []
    handler = logging.Handler()
    handler.emit = records.append
    scope["log"].addHandler(handler)
    scope.update(__name__="__main__", bot=bot, threading=FakeThreading,
                 DISCORD_BOT_TOKEN=token, GUILD_ID=guild_id)
    idled = False
    try:
        exec(slice_text(*MAIN), scope)  # noqa: S102 - the shipped main block
    except Idled:
        idled = True
    finally:
        scope["log"].removeHandler(handler)
    return bot, idled, [r.getMessage() for r in records]


class EnvInt(unittest.TestCase):
    def read(self, value, default=0):
        env = {} if value is None else {"X_SETTING": value}
        with mock.patch.dict(os.environ, env, clear=True):
            return helpers()["env_int"]("X_SETTING", default)

    def test_a_number_is_read(self):
        self.assertEqual(self.read("123456789012345678"), 123456789012345678)
        self.assertEqual(self.read("  42 \n"), 42)

    def test_unset_and_blank_are_the_default(self):
        self.assertEqual(self.read(None), 0)
        self.assertEqual(self.read(""), 0)
        self.assertEqual(self.read("   "), 0)
        self.assertEqual(self.read("", 7), 7)

    def test_a_non_number_is_the_default_not_a_crash(self):
        self.assertEqual(self.read("my-guild"), 0)


class MissingSettings(unittest.TestCase):
    def test_names_what_is_unset(self):
        missing = helpers()["missing_discord_settings"]
        self.assertEqual(missing("", 0), ["DISCORD_BOT_TOKEN", "DISCORD_GUILD_ID"])
        self.assertEqual(missing("token", 0), ["DISCORD_GUILD_ID"])
        self.assertEqual(missing("", 5), ["DISCORD_BOT_TOKEN"])
        self.assertEqual(missing("token", 5), [])


class MainBlock(unittest.TestCase):
    def test_without_secrets_it_idles_and_never_starts_the_bot(self):
        bot, idled, messages = run_main("", 0)
        self.assertTrue(idled, "the bot must wait, not return: a return is an exit and a crash loop")
        self.assertIsNone(bot.ran_with)
        self.assertEqual(len(messages), 1)
        self.assertIn("DISCORD_BOT_TOKEN, DISCORD_GUILD_ID unset", messages[0])

    def test_with_one_secret_missing_it_still_idles(self):
        for token, guild in (("token", 0), ("", 5)):
            bot, idled, _ = run_main(token, guild)
            self.assertTrue(idled)
            self.assertIsNone(bot.ran_with)

    def test_with_both_secrets_it_runs_the_bot(self):
        bot, idled, messages = run_main("token", 5)
        self.assertFalse(idled)
        self.assertEqual(bot.ran_with, "token")
        self.assertEqual(messages, [])


class ModuleConfig(unittest.TestCase):
    def test_no_discord_secret_is_read_with_a_bare_lookup(self):
        text = SYNC_PY.read_text()
        for name in ("DISCORD_BOT_TOKEN", "DISCORD_GUILD_ID", "DISCORD_CHANNEL_ID",
                     "DISCORD_PLAYER_ROLE_ID", "DISCORD_ADMIN_ROLE_ID", "RCON_PASSWORD"):
            self.assertNotIn(f'os.environ["{name}"]', text, f"{name} must not raise when unset")
            self.assertNotIn(f'int(os.environ.get("{name}"', text, f"{name} must not raise when blank")


if __name__ == "__main__":
    unittest.main()
