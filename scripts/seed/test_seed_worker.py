#!/usr/bin/env python3
"""Regression tests for seed-worker candidate outcomes."""
import unittest

from seed_worker import create_candidate, spawn_filter_rejection


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


if __name__ == "__main__":
    unittest.main()
