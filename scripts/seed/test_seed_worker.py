#!/usr/bin/env python3
"""Regression tests for seed_worker candidate creation."""
import unittest

from seed_worker import create_candidate


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

                self.assertTrue(create_candidate(
                    rcon, "test", "adventure", "candidate", self.profile, 123))
                self.assertEqual(
                    rcon.commands[-1], "execute in adventure:candidate run seed")

    def test_rejects_non_success_response(self):
        rcon = FakeRcon("Failed to create dimension: invalid type")

        self.assertFalse(create_candidate(
            rcon, "test", "adventure", "candidate", self.profile, 123))
        self.assertEqual(len(rcon.commands), 1)


if __name__ == "__main__":
    unittest.main()
