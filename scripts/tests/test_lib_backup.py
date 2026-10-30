"""lib.sh's backup(): a timestamped copy before an overwrite, and success when there is nothing to copy."""
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def run(snippet, cwd):
    return subprocess.run(
        ["bash", "-c", f'set -euo pipefail; source "{REPO}/scripts/lib.sh"; {snippet}'],
        capture_output=True, text=True, cwd=cwd)


def test_copies_an_existing_file(tmp_path):
    (tmp_path / "a.txt").write_text("keep\n")
    result = run('backup a.txt; echo after', tmp_path)
    assert result.returncode == 0, result.stderr
    assert "backed up a.txt" in result.stdout
    assert result.stdout.rstrip().endswith("after")
    copies = list(tmp_path.glob("a.txt.bak.*"))
    assert len(copies) == 1
    assert copies[0].read_text() == "keep\n"


def test_missing_file_succeeds_under_set_e(tmp_path):
    result = run('backup missing.txt; echo after', tmp_path)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "after"
    assert list(tmp_path.glob("missing.txt.bak.*")) == []


def test_directory_is_not_copied(tmp_path):
    (tmp_path / "d").mkdir()
    result = run('backup d; echo after', tmp_path)
    assert result.returncode == 0, result.stderr
    assert list(tmp_path.glob("d.bak.*")) == []
