"""check-pack-coherence.py over a mirror directory: mods, datapacks, unreadables."""

import json
import subprocess
import sys
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-pack-coherence.py"


def _zip(path, files):
    with zipfile.ZipFile(path, "w") as z:
        for name, body in files.items():
            z.writestr(name, body)


def _mod(path, mod_id, version, depends=None):
    meta = {"schemaVersion": 1, "id": mod_id, "version": version, "depends": depends or {}}
    _zip(path, {"fabric.mod.json": json.dumps(meta)})


def _run(mods_dir):
    return subprocess.run([sys.executable, str(SCRIPT), str(mods_dir)], capture_output=True, text=True)


def test_datapack_in_mirror_is_skipped(tmp_path):
    _mod(tmp_path / "a-1.jar", "a", "1.0.0")
    _zip(tmp_path / "clucking-ducks-X.jar", {"pack.mcmeta": "{}", "data/x/y.json": "{}"})
    r = _run(tmp_path)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "1 datapack(s)" in r.stdout


def test_archive_without_metadata_still_fails(tmp_path):
    _mod(tmp_path / "a-1.jar", "a", "1.0.0")
    _zip(tmp_path / "broken-X.jar", {"readme.txt": "x"})
    r = _run(tmp_path)
    assert r.returncode == 1
    assert "COULD NOT BE READ" in r.stdout


def test_unmet_dependency_fails(tmp_path):
    _mod(tmp_path / "a-1.jar", "a", "1.0.0")
    _mod(tmp_path / "b-1.jar", "b", "1.0.0", {"a": ">=2.0.0"})
    r = _run(tmp_path)
    assert r.returncode == 1
    assert "b-1.jar depends on a" in r.stdout
