"""The Minecraft version helpers: scripts/minecraft_version.py and lib.sh's mc_* functions."""
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
import minecraft_version  # noqa: E402
import platform_updates  # noqa: E402

TABLE = REPO / "scripts" / "minecraft-versions.tsv"


def bash(snippet, **env):
    full = dict(os.environ, **env)
    full.pop("MC_VERSION", None) if "MC_VERSION" not in env else None
    return subprocess.run(
        ["bash", "-c", f'source "{REPO}/scripts/lib.sh"; {snippet}'],
        capture_output=True, text=True, env=full)


@pytest.fixture
def tree(tmp_path):
    (tmp_path / "config").mkdir()
    (tmp_path / "modpack").mkdir()
    (tmp_path / "config" / "modrinth-mods.txt").write_text("a:1\n")
    (tmp_path / "modpack" / "adventure.mrpack.json").write_text("{}\n")
    return tmp_path


def test_version_file_holds_one_tabled_version():
    v = minecraft_version.current()
    assert re.fullmatch(r"\d+\.\d+(\.\d+)?", v)
    assert minecraft_version.info(v)["accepted"][0] == v


def test_table_rows():
    assert minecraft_version.info("1.21.1") == {"java": "21", "image": "2026.7.0-java21", "accepted": ("1.21.1", "1.21")}
    info = minecraft_version.info("26.3")
    assert info["java"] == "25" and info["image"].endswith("-java25") and info["accepted"] == ("26.3",)


def test_unknown_version_fails():
    with pytest.raises(SystemExit):
        minecraft_version.info("9.9")


def test_missing_file_falls_back_to_env(tmp_path, monkeypatch):
    monkeypatch.setenv("MC_VERSION", "26.3")
    assert minecraft_version.current(tmp_path / "absent") == "26.3"
    monkeypatch.delenv("MC_VERSION")
    with pytest.raises(SystemExit):
        minecraft_version.current(tmp_path / "absent")


def test_file_wins_over_env(tmp_path, monkeypatch):
    f = tmp_path / ".minecraft-version"
    f.write_text("# comment\n\n26.3\n")
    monkeypatch.setenv("MC_VERSION", "1.21.1")
    assert minecraft_version.current(f) == "26.3"


def test_versioned_paths(tree):
    assert minecraft_version.mod_list(tree, "1.21.1") == tree / "config/modrinth-mods.txt"
    assert minecraft_version.manifest(tree, "26.3") == tree / "modpack/adventure.mrpack.json"
    (tree / "config/modrinth-mods-26.3.txt").write_text("b:2\n")
    assert minecraft_version.mod_list(tree, "26.3") == tree / "config/modrinth-mods-26.3.txt"
    assert minecraft_version.mod_list(tree, "1.21.1") == tree / "config/modrinth-mods.txt"


def test_platform_lists_resolve():
    assert minecraft_version.mod_list(REPO, "1.21.1") == REPO / "config/modrinth-mods.txt"
    assert minecraft_version.mod_list(REPO, "26.3") == REPO / "config/modrinth-mods-26.3.txt"


def test_bash_matches_python():
    r = bash('mc_version; mc_version_field java; mc_version_field image; mc_version_field accepted; mc_version_field image 26.3')
    assert r.returncode == 0, r.stderr
    v = minecraft_version.current()
    info = minecraft_version.info(v)
    assert r.stdout.split() == [v, info["java"], info["image"], ",".join(info["accepted"]),
                                minecraft_version.info("26.3")["image"]]


def test_bash_errors():
    assert bash("mc_version_field image 9.9").returncode == 1
    assert bash("mc_version_field colour").returncode == 1
    r = bash("mc_version", MC_VERSION_FILE="/nonexistent")
    assert r.returncode == 1 and "MC_VERSION is unset" in r.stderr
    assert bash("mc_version", MC_VERSION_FILE="/nonexistent", MC_VERSION="26.3").stdout.strip() == "26.3"


def test_bash_paths(tree):
    r = bash(f'mc_mod_list "{tree}" 26.3; mc_manifest "{tree}" 26.3')
    assert r.stdout.split() == [f"{tree}/config/modrinth-mods.txt", f"{tree}/modpack/adventure.mrpack.json"]
    (tree / "config/modrinth-mods-26.3.txt").write_text("b:2\n")
    r = bash(f'mc_mod_list "{tree}" 26.3; mc_mod_list "{tree}" 1.21.1')
    assert r.stdout.split() == [f"{tree}/config/modrinth-mods-26.3.txt", f"{tree}/config/modrinth-mods.txt"]


def test_export_follows_the_file(tmp_path):
    f = tmp_path / "v"
    f.write_text("26.3\n")
    r = bash('export_mc_version; echo "$MC_VERSION $MC_IMAGE_TAG"', MC_VERSION_FILE=str(f), MC_VERSION="1.21.1")
    assert r.stdout.split() == ["26.3", minecraft_version.info("26.3")["image"]]


def test_compose_fallbacks_match_the_1_21_1_row():
    compose = (REPO / "docker-compose.yml").read_text()
    tags = re.findall(r"itzg/minecraft-server:\$\{MC_IMAGE_TAG:-([^}]+)\}", compose)
    assert tags == [minecraft_version.info("1.21.1")["image"]]
    assert set(re.findall(r"\$\{MC_VERSION:-([^}]+)\}", compose)) == {"1.21.1"}


def test_platform_updates_reads_the_wrapped_image_line():
    line = "    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/itzg/minecraft-server:${MC_IMAGE_TAG:-2026.7.0-java21}"
    occ = platform_updates.scan_text("docker-compose.yml", line)
    assert [(o.name, o.version) for o in occ] == [("itzg/minecraft-server", "2026.7.0-java21")]
    out = platform_updates.rewrite({"docker-compose.yml": line + "\n"}, {("image", "itzg/minecraft-server"): "2026.8.0-java21"})
    assert out["docker-compose.yml"] == line.replace("2026.7.0", "2026.8.0") + "\n"
    plain = "    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/nginx:1.30.5-alpine"
    assert platform_updates.mask_line("docker-compose.yml", plain).endswith("nginx:<v>")


def test_platform_updates_moves_the_table_row_with_compose():
    text = TABLE.read_text()
    occ = platform_updates.scan_text("scripts/minecraft-versions.tsv", text)
    target = platform_updates.TARGET_MC
    assert [(o.ecosystem, o.name, o.version) for o in occ] == [
        ("image", "itzg/minecraft-server", minecraft_version.info(target)["image"])]
    out = platform_updates.rewrite({"scripts/minecraft-versions.tsv": text}, {("image", "itzg/minecraft-server"): "2099.1.0-java21"})
    assert minecraft_version.info(target, table=_write(out["scripts/minecraft-versions.tsv"]))["image"] == "2099.1.0-java21"
    assert platform_updates.IMAGE_RULES["itzg/minecraft-server"]["suffix"] == f"-java{minecraft_version.info(target)['java']}"


def _write(text, _n=[0]):
    import tempfile
    p = Path(tempfile.mkdtemp()) / "t.tsv"
    p.write_text(text)
    return p


STUB_API = '''import json, sys
target, fallbacks = sys.argv[2], sys.argv[3]
for slug in sys.stdin.read().split():
    print(json.dumps({"status": "found", "id": slug.upper()[:8] + "X", "version": "1", "matched": target}))
'''


@pytest.fixture
def pin_tree(tmp_path):
    (tmp_path / "scripts").mkdir()
    for name in ("pin-mod-versions.sh", "lib.sh", "minecraft-versions.tsv", "pack_files.py"):
        (tmp_path / "scripts" / name).write_bytes((REPO / "scripts" / name).read_bytes())
    (tmp_path / "scripts" / "modrinth-api.py").write_text(STUB_API)
    (tmp_path / ".minecraft-version").write_text("1.21.1\n")
    (tmp_path / "config").mkdir()
    (tmp_path / "modpack").mkdir()
    (tmp_path / "config" / "modrinth-mods.txt").write_text("# shared\nheld:OLD\nfree:OLD\n")
    (tmp_path / "config" / "modrinth-mods-26.3.txt").write_text("# 26.3\nheld\nfree\n")
    (tmp_path / "modpack" / "adventure.mrpack.json").write_text('{"_holds": {"held": "1.21.1 reason"}, "_clientMods": {}}\n')
    return tmp_path


def run_pin(tree, *args):
    return subprocess.run(["bash", str(tree / "scripts/pin-mod-versions.sh"), *args],
                          capture_output=True, text=True, env={k: v for k, v in os.environ.items() if k != "MC_VERSION"})


def test_pin_follows_the_version_file(pin_tree):
    r = run_pin(pin_tree)
    assert r.returncode == 0, r.stderr
    assert "Target: 1.21.1" in r.stdout and "Fallback chain: 1.21.1 1.21" in r.stdout
    assert (pin_tree / "config/modrinth-mods.pinned.txt").read_text() == "# shared\nheld:OLD\nfree:FREEX\n"
    assert "held - HELD" in r.stdout and "Re-pinning client manifest" in r.stdout


def test_pin_26_3_uses_its_own_list_without_1_21_1_holds(pin_tree):
    r = run_pin(pin_tree, "--version", "26.3")
    assert r.returncode == 0, r.stderr
    assert "Fallback chain: 26.3" in r.stdout
    assert (pin_tree / "config/modrinth-mods-26.3.pinned.txt").read_text() == "# 26.3\nheld:HELDX\nfree:FREEX\n"
    assert not (pin_tree / "config/modrinth-mods.pinned.txt").exists()
    assert "HELD" not in r.stdout and "Client manifest skipped" in r.stdout
    assert (pin_tree / "config/modrinth-mods.txt").read_text() == "# shared\nheld:OLD\nfree:OLD\n"


def test_pin_apply_writes_only_the_versioned_list(pin_tree):
    r = run_pin(pin_tree, "--version", "26.3", "--apply")
    assert r.returncode == 0, r.stderr
    assert (pin_tree / "config/modrinth-mods-26.3.txt").read_text() == "# 26.3\nheld:HELDX\nfree:FREEX\n"
    assert "Applied to: config/modrinth-mods-26.3.txt" in r.stdout


def test_pin_untabled_version_walks_the_1_21_chain(pin_tree):
    r = run_pin(pin_tree, "--version", "1.21.4")
    assert "Fallback chain: 1.21.4 1.21.3 1.21.2 1.21.1 1.21" in r.stdout
