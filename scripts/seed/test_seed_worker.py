#!/usr/bin/env python3
"""Regression tests for seed-worker candidate outcomes."""
import socket
import unittest

import seed_worker
from seed_worker import (Rcon, RconTimeout, create_candidate, spawn_filter_rejection,
                         spawn_gate_for, worker_backoff)


class FakeRcon:
    """Minimal RCON double: a created candidate immediately answers `seed`."""

    def __init__(self, create_response):
        self.create_response = create_response
        self.commands = []

    def cmd(self, command):
        self.commands.append(command)
        if command.startswith("customdim create "):
            return self.create_response
        if command == "execute in adventure:candidate run seed":
            return "Seed: [123]"
        self.fail(f"unexpected RCON command: {command}")


class CreateCandidateTests(unittest.TestCase):
    profile = {
        "create_args": {
            "type": "nether",
            "noiseSettings": None,
            "structureDensity": None,
            "biome": None,
        }
    }

    def test_accepts_queued_and_created_success_responses(self):
        for response in (
            "Queued dimension 'candidate' (type: nether, seed: 123)",
            "Created dimension 'candidate' (type: nether, seed: 123)",
        ):
            with self.subTest(response=response):
                rcon = FakeRcon(response)

                created, reason = create_candidate(
                    rcon, "test", "adventure", "candidate", self.profile, 123)
                self.assertTrue(created)
                self.assertIsNone(reason)
                self.assertEqual(
                    rcon.commands[-1], "execute in adventure:candidate run seed")

    def test_rejects_non_success_response(self):
        rcon = FakeRcon("Failed to create dimension: invalid type")

        created, reason = create_candidate(
            rcon, "test", "adventure", "candidate", self.profile, 123)
        self.assertFalse(created)
        self.assertEqual(reason, "create command rejected: Failed to create dimension: invalid type")
        self.assertEqual(len(rcon.commands), 1)


class RejectionReasonTests(unittest.TestCase):
    def test_spawn_filter_rejection_explains_active_gate(self):
        reason = spawn_filter_rejection(
            "minecraft:nether_wastes", 204,
            ["minecraft:nether_wastes", "minecraft:crimson_forest"], 48)

        self.assertEqual(
            reason,
            "spawn filter: nearest configured biome minecraft:nether_wastes at 204 blocks; "
            "requires one of [minecraft:nether_wastes, minecraft:crimson_forest] "
            "within 48 blocks")

    def test_spawn_filter_rejection_explains_missing_match(self):
        reason = spawn_filter_rejection(
            None, None, ["terralith:bryce_canyon"], 256)

        self.assertEqual(
            reason,
            "spawn filter: no configured biome found; requires one of "
            "[terralith:bryce_canyon] within 256 blocks")


class TimeoutSocket:
    def __init__(self):
        self.sent = []
        self.closed = False

    def sendall(self, payload):
        self.sent.append(payload)

    def recv(self, _size):
        raise socket.timeout()

    def close(self):
        self.closed = True


class RconRecoveryTests(unittest.TestCase):
    def test_command_timeout_does_not_replay_the_command(self):
        sock = TimeoutSocket()
        rcon = Rcon("127.0.0.1", 25575, "test", timeout=60)
        rcon.sock = sock

        with self.assertRaises(RconTimeout):
            rcon.cmd("locate biome minecraft:plains")

        self.assertEqual(len(sock.sent), 1)
        self.assertTrue(sock.closed)
        self.assertIsNone(rcon.sock)

    def test_connection_timeout_is_typed_for_recovery(self):
        rcon = Rcon("127.0.0.1", 25575, "test", timeout=1)
        rcon._send = lambda *_args: None
        rcon._recv = lambda: (_ for _ in ()).throw(socket.timeout())
        original_create_connection = socket.create_connection
        socket.create_connection = lambda *_args, **_kwargs: TimeoutSocket()
        try:
            with self.assertRaises(RconTimeout):
                rcon.connect()
        finally:
            socket.create_connection = original_create_connection

    def test_spawn_gate_is_fixed_from_the_first_candidate(self):
        self.assertEqual(spawn_gate_for(0), (768, False))
        self.assertEqual(spawn_gate_for(4), (768, False))
        self.assertEqual(spawn_gate_for(99), (768, False))

    def test_worker_backoff_is_exponential_and_capped(self):
        self.assertEqual(worker_backoff(1), 5)
        self.assertEqual(worker_backoff(2), 10)
        self.assertEqual(worker_backoff(10), 60)


class SeedRollModeEnvTests(unittest.TestCase):
    """Which of the two ways into a dimension the container supports.

    SEED_ROLL_MODE=true makes the mod skip registerDimensions(), so no
    CONFIGURED dimension has options in the registry. Workers want that —
    they create their own candidates. warmup_structure_pools.py does not:
    `customdim load <slug>` resolves through getOrCreateDimension, which
    returns null without a log line when the options are missing, so the load
    is queued and then dropped (measured 2026-07-30: 0 of 77 loaded, and only
    the 5 self-loading worlds recorded a pool).
    """

    def run_args(self, **kwargs):
        captured = {}

        def fake_docker(*args, **_kwargs):
            if args and args[0] == "run":
                captured["run"] = list(args)
            elif args and args[0] == "port":
                return type("R", (), {"stdout": "127.0.0.1:55555"})()
            return type("R", (), {"returncode": 1, "stdout": ""})()

        original = seed_worker.docker
        seed_worker.docker = fake_docker
        try:
            seed_worker.start_container("c", "/tmp/nowhere", "6G", **kwargs)
        finally:
            seed_worker.docker = original
        return captured["run"]

    def test_workers_get_seed_roll_mode(self):
        self.assertIn("SEED_ROLL_MODE=true", self.run_args())

    def test_opting_out_removes_the_variable_entirely(self):
        args = self.run_args(seed_roll_mode=False)
        self.assertFalse([a for a in args if str(a).startswith("SEED_ROLL_MODE")])
        # The rest of the environment must be untouched — the pool warmup
        # differs in exactly one variable.
        self.assertIn("EULA=TRUE", args)
        self.assertIn("ENABLE_RCON=TRUE", args)


if __name__ == "__main__":
    unittest.main()
