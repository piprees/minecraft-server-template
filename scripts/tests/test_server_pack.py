#!/usr/bin/env python3
"""Tests for server-pack.py and its wiring: the staged pack, the four values
deploy.sh and dev-up.sh export, and compose passing them to itzg.

A value that does not reach mc leaves every portal vanilla; a pack forced on
clients sends Bedrock players tripwire through Geyser.
"""
import importlib.util
import os
import re
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "server-pack.py"
spec = importlib.util.spec_from_file_location("server_pack", SCRIPT)
server_pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server_pack)


class ServerPackTest(unittest.TestCase):
    def test_self_test_passes(self):
        self.assertEqual(server_pack.self_test(), 0)

    def test_the_shell_loop_exports_every_value(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "data", "mods"))
            with zipfile.ZipFile(os.path.join(root, "data", "mods", "customdimensions-0.1.0.jar"), "w") as z:
                z.writestr(server_pack.ENTRY, b"PACK")
            script = (
                'set -euo pipefail\n'
                'while IFS=$\'\\t\' read -r name value; do export "$name=$value"; done '
                '< <(python3 "$1" "$2" https://pack.example.com)\n'
                'printf "%s\\n" "$RESOURCE_PACK" "$RESOURCE_PACK_SHA1" "$RESOURCE_PACK_ID" "$RESOURCE_PACK_PROMPT"\n')
            out = subprocess.run(["bash", "-c", script, "-", str(SCRIPT), root], capture_output=True, text=True,
                                 check=True).stdout.splitlines()
        self.assertTrue(out[0].startswith("https://pack.example.com/server-pack/portals.zip?sha1="), out)
        self.assertEqual(len(out[1]), 40)
        self.assertEqual(out[2], server_pack.PACK_ID)
        self.assertTrue(out[3].startswith('"') and out[3].endswith('"'), out[3])

    def test_compose_passes_the_values_and_never_enforces(self):
        compose = (REPO / "docker-compose.yml").read_text()
        for name in server_pack.NAMES:
            self.assertIn('%s: "${%s:-}"' % (name, name), compose)
        self.assertEqual(re.findall(r'RESOURCE_PACK_ENFORCE: "([^"]*)"', compose), ["FALSE"])

    def test_deploy_and_dev_up_stage_before_mc_starts(self):
        for rel, start in (("scripts/deploy.sh", "# 11. Start the stack"),
                           ("scripts/dev-up.sh", "# --- Start the local profile")):
            text = (REPO / rel).read_text()
            self.assertIn("server-pack.py", text, rel)
            self.assertLess(text.index("server-pack.py"), text.index(start), rel)

    def test_the_bundle_ships_it(self):
        self.assertIn("  scripts/server-pack.py\n", (REPO / "scripts" / "build-stack-bundle.sh").read_text())


if __name__ == "__main__":
    unittest.main()
