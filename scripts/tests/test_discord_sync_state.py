#!/usr/bin/env python3
"""Tests for discord-sync's role-sync state cache.

Drives the SHIPPED text: the state-recording block is sliced out of
scripts/discord-sync.py by anchor strings and run against stubs. Importing the
module is not an option here — it needs discord, aiohttp and mcrcon.

The invariant under test: a member whose RCON command was never answered keeps
its old state, so the next cycle retries. Recording it as applied is how a
player stays off the whitelist for good.
"""
import asyncio
import textwrap
import unittest
from pathlib import Path

SYNC_PY = Path(__file__).resolve().parent.parent / "discord-sync.py"

STATE_BLOCK = ("        if commands:", "            )")

# The slice must still hold these, or the extraction has shrunk past the logic
# under test and the suite would pass on nothing.
BLOCK_MUST_CONTAIN = ("async_rcon_batch", "_last_sync_state", "member_commands")


def extract():
    text = SYNC_PY.read_text()
    start, end = STATE_BLOCK
    if text.count(start) != 1:
        raise AssertionError(f"start anchor is not unique: {start!r}")
    i = text.index(start)
    j = text.index(end, i) + len(end)
    block = textwrap.dedent(text[i:j])
    for needle in BLOCK_MUST_CONTAIN:
        if needle not in block:
            raise AssertionError(f"extracted block lost {needle!r}")
    return block


class FakeSelf:
    def __init__(self):
        self._last_sync_state = {}


class FakeLog:
    def info(self, *args, **kwargs):
        pass


class SyncStateTests(unittest.TestCase):
    def run_block(self, member_commands, failures):
        """Run the shipped block; `failures` are commands the server never answered."""
        commands = [cmd for cmds in member_commands.values() for cmd in cmds]
        results = {cmd: (None if cmd in failures else "ok") for cmd in commands}

        async def async_rcon_batch(cmds):
            return results

        me = FakeSelf()
        namespace = {
            "self": me,
            "commands": commands,
            "member_commands": member_commands,
            "new_state": {mid: (True, False) for mid in member_commands},
            "async_rcon_batch": async_rcon_batch,
            "log": FakeLog(),
        }
        source = "async def _run():\n" + textwrap.indent(extract(), "    ")
        exec(compile(source, "<block>", "exec"), namespace)
        asyncio.run(namespace["_run"]())
        return me._last_sync_state

    def test_a_member_whose_command_failed_is_not_recorded(self):
        state = self.run_block(
            {"1": ["whitelist add Ann", "deop Ann"], "2": ["whitelist add Bob", "deop Bob"]},
            failures={"whitelist add Ann"},
        )
        self.assertNotIn("1", state, "a failed whitelist add must be retried next cycle")
        self.assertIn("2", state)

    def test_every_member_is_recorded_when_the_server_answers(self):
        state = self.run_block(
            {"1": ["whitelist add Ann", "deop Ann"], "2": ["whitelist add Bob", "deop Bob"]},
            failures=set(),
        )
        self.assertEqual({"1", "2"}, set(state))

    def test_a_whole_batch_failing_records_nobody(self):
        member_commands = {"1": ["whitelist add Ann"], "2": ["whitelist add Bob"]}
        state = self.run_block(
            member_commands,
            failures={"whitelist add Ann", "whitelist add Bob"},
        )
        self.assertEqual({}, state)


if __name__ == "__main__":
    unittest.main()
