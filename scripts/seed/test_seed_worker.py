#!/usr/bin/env python3
"""Regression tests for seed-worker candidate outcomes."""
import socket
import unittest

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


if __name__ == "__main__":
    unittest.main()
