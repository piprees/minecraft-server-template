"""scripts/local-mods.sh locate: the built jar of each manifest row for the selected Minecraft version."""
import os
import shutil
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]


@pytest.fixture
def tree(tmp_path):
    (tmp_path / "scripts").mkdir()
    for name in ("local-mods.sh", "lib.sh", "minecraft-versions.tsv", "minecraft_version.py"):
        src = REPO / "scripts" / name
        if src.exists():
            shutil.copy2(src, tmp_path / "scripts" / name)
    (tmp_path / ".minecraft-version").write_text("1.21.1\n")
    mods = tmp_path / "mods"
    mods.mkdir()
    (mods / "local-mods.manifest").write_text("# server\nold.jar|old-mod|old-refmap.json\n")
    (mods / "local-mods-26.3.manifest").write_text("new.jar|new-mod|-\n")
    (mods / "client-mods-26.3.manifest").write_text("newclient.jar|new-client|-\n")
    for rel in ("old-mod/build/libs/old-1.0.jar", "old-mod/build/libs/old-1.0-sources.jar",
                "new-mod/server/build/libs/new-0.1.0.jar", "new-mod/server/build/libs/new-0.1.0-dev.jar",
                "new-client/build/libs/newclient-0.1.0.jar"):
        path = mods / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"jar")
    return tmp_path


def locate(tree, kind, version=None):
    env = dict(os.environ)
    env.pop("MC_VERSION_OVERRIDE", None)
    if version:
        env["MC_VERSION_OVERRIDE"] = version
    return subprocess.run(["bash", str(tree / "scripts" / "local-mods.sh"), "locate", kind],
                          capture_output=True, text=True, env=env)


def test_selected_version_server_row(tree):
    result = locate(tree, "server")
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == [f"old.jar|{tree}/mods/old-mod/build/libs/old-1.0.jar|old-refmap.json"]


def test_override_moves_to_the_versioned_manifest_and_nested_libs(tree):
    result = locate(tree, "server", "26.3")
    assert result.returncode == 0, result.stderr
    assert result.stdout.splitlines() == [f"new.jar|{tree}/mods/new-mod/server/build/libs/new-0.1.0.jar|-"]


def test_client_rows(tree):
    assert locate(tree, "client", "26.3").stdout.splitlines() == [
        f"newclient.jar|{tree}/mods/new-client/build/libs/newclient-0.1.0.jar|-"]


def test_missing_client_manifest_is_empty(tree):
    result = locate(tree, "client")
    assert result.returncode == 0, result.stderr
    assert result.stdout == ""


def test_unbuilt_row_warns_and_is_skipped(tree):
    shutil.rmtree(tree / "mods" / "new-mod" / "server" / "build")
    result = locate(tree, "server", "26.3")
    assert result.returncode == 0
    assert result.stdout == ""
    assert "new.jar: expected one built jar in mods/new-mod, found 0" in result.stderr


def test_two_builds_are_ambiguous(tree):
    (tree / "mods" / "old-mod" / "build" / "libs" / "old-1.1.jar").write_bytes(b"jar")
    result = locate(tree, "server")
    assert result.stdout == ""
    assert "found 2" in result.stderr


def test_missing_project_warns(tree):
    shutil.rmtree(tree / "mods" / "old-mod")
    result = locate(tree, "server")
    assert result.returncode == 0
    assert "found 0" in result.stderr


def test_missing_server_manifest_fails(tree):
    (tree / "mods" / "local-mods.manifest").unlink()
    assert locate(tree, "server").returncode != 0
