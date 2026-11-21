#!/usr/bin/env python3
"""Tests for deploy.sh's Floodgate key write (step 8d).

They run the SHIPPED block, sliced out of scripts/deploy.sh by its anchors,
against a temporary SERVER_DIR.
"""

import base64
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

DEPLOY_SH = Path(__file__).resolve().parent.parent / "deploy.sh"
START = "# 8d. Floodgate key (while mc is stopped)"
END = "# 9. Enforce Discord integration config"

STUBS = r"""
set -euo pipefail
warn() { echo "WARNING: $*" >&2; }
backup() { [[ -f "$1" ]] && cp -p "$1" "$1.bak.test" && echo "  backed up $1"; }
"""

KEY = bytes(range(16))


def block():
    text = DEPLOY_SH.read_text()
    if text.count(START) != 1:
        raise AssertionError(f"start anchor is not unique: {START!r}")
    i = text.index(START)
    j = text.index(END, i)
    body = text[i:j]
    if "FLOODGATE_KEY_B64" not in body or "base64 -d" not in body:
        raise AssertionError("the anchors no longer bound the key write")
    return body


class FloodgateKeyWrite(unittest.TestCase):
    def run_block(self, existing=None, **env):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        key = root / "data" / "config" / "floodgate" / "key.pem"
        if existing is not None:
            key.parent.mkdir(parents=True)
            key.write_bytes(existing)
            key.chmod(0o644)
        script = root / "run.sh"
        script.write_text(STUBS + block())
        proc = subprocess.run(
            ["bash", str(script)],
            capture_output=True,
            text=True,
            env={"PATH": os.environ["PATH"], "SERVER_DIR": str(root), **env},
        )
        return proc, key

    def mode(self, path):
        return stat.S_IMODE(path.stat().st_mode)

    def test_unset_writes_nothing(self):
        proc, key = self.run_block()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertFalse(key.parent.exists())

    def test_empty_leaves_the_generated_key(self):
        proc, key = self.run_block(existing=b"generated", FLOODGATE_KEY_B64="")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(key.read_bytes(), b"generated")

    def test_set_writes_the_decoded_key_with_mode_600(self):
        proc, key = self.run_block(FLOODGATE_KEY_B64=base64.b64encode(KEY).decode())
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(key.read_bytes(), KEY)
        self.assertEqual(self.mode(key), 0o600)
        self.assertFalse(key.with_name("key.pem.new").exists())

    def test_a_different_key_is_backed_up_then_replaced(self):
        proc, key = self.run_block(
            existing=b"generated", FLOODGATE_KEY_B64=base64.b64encode(KEY).decode()
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(key.read_bytes(), KEY)
        self.assertEqual(key.with_name("key.pem.bak.test").read_bytes(), b"generated")

    def test_the_same_key_is_not_rewritten_or_backed_up(self):
        proc, key = self.run_block(
            existing=KEY, FLOODGATE_KEY_B64=base64.b64encode(KEY).decode()
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(key.read_bytes(), KEY)
        self.assertEqual(self.mode(key), 0o600)
        self.assertFalse(key.with_name("key.pem.bak.test").exists())
        self.assertNotIn("written", proc.stdout)

    def test_invalid_base64_warns_and_keeps_the_current_key(self):
        proc, key = self.run_block(existing=b"generated", FLOODGATE_KEY_B64="!!not base64!!")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("not valid base64", proc.stderr)
        self.assertEqual(key.read_bytes(), b"generated")
        self.assertFalse(key.with_name("key.pem.new").exists())

    def test_a_shell_payload_is_data_not_code(self):
        proc, key = self.run_block(FLOODGATE_KEY_B64="$(touch pwned)`touch pwned`")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertFalse((key.parents[3] / "pwned").exists())
        self.assertFalse(Path("pwned").exists())


if __name__ == "__main__":
    unittest.main()
