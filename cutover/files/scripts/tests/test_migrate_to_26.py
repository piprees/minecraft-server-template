#!/usr/bin/env python3
"""Tests for migrate-to-26.sh: the 1.21.1 server and consumer repo moved to the v6 stack.

Load-bearing: the script deletes a production world. These tests pin the order
of its phases, every refusal that guards the deletion, the exact deletion list
and what survives it, resume after an interruption, owner steps, --local, and
the absence of any streaming or unbounded command.

The server is a fixture tree reached through a fake ./ops whose `ssh` runs the
command in it (HOME is the fixture, so ~/server resolves there) with fake
docker, sudo, ufw and gh on PATH. Every call is recorded.
"""
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
SCRIPT = SCRIPTS / "migrate-to-26.sh"

FAKE_OPS = r'''#!/usr/bin/env python3
import os, subprocess, sys
fx = os.environ["M26_FIXTURE"]
with open(os.path.join(fx, "calls.log"), "a") as f:
    f.write("ops " + " ".join(sys.argv[1:]) + "\n")
print("BANNER  fixture - ops " + (sys.argv[1] if len(sys.argv) > 1 else ""))
cmd = sys.argv[1] if len(sys.argv) > 1 else ""
if cmd == "ssh":
    # A dropped connection for the calls whose command holds a line of ssh-fail.
    fail = os.path.join(fx, "ssh-fail")
    if os.path.exists(fail) and any(l and l in sys.argv[2] for l in open(fail).read().splitlines()):
        print("ssh: connect to host fixture port 22: Connection reset by peer", file=sys.stderr)
        sys.exit(255)
    env = dict(os.environ, HOME=os.path.join(fx, "home"))
    sys.exit(subprocess.call(["bash", "-c", sys.argv[2]], env=env))
if cmd == "doctor":
    print("PASS fixture doctor")
    sys.exit(int(open(os.path.join(fx, "doctor-rc")).read()) if os.path.exists(os.path.join(fx, "doctor-rc")) else 0)
sys.exit(0)
'''

FAKE_DEV = r'''#!/usr/bin/env python3
import os, sys
fx = os.environ["M26_FIXTURE"]
here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(fx, "calls.log"), "a") as f:
    f.write("dev " + " ".join(sys.argv[1:]) + " STACK_VERSION=" + os.environ.get("STACK_VERSION", "") + "\n")
if sys.argv[1:2] == ["update"]:
    with open(os.path.join(here, ".github/workflows/deploy.yml"), "w") as f:
        f.write("jobs:\n  deploy:\n    uses: piprees/minecraft-server-template/.github/workflows/deploy-reusable.yml@v6\n"
                "    secrets:\n      STACK_READ_TOKEN: x\n      FLOODGATE_KEY_B64: y\n")
'''

FAKE_DOCKER = r'''#!/usr/bin/env python3
import os, sys
fx = os.environ["M26_FIXTURE"]
d = os.path.join(fx, "docker")
a = sys.argv[1:]
with open(os.path.join(fx, "calls.log"), "a") as f:
    f.write("docker " + " ".join(a) + "\n")
def running(n):
    p = os.path.join(d, n)
    return open(p).read().strip() if os.path.exists(p) else None
def setrun(n, v):
    open(os.path.join(d, n), "w").write(v)
if a[0] == "inspect":
    name = a[-1]
    st = running(name)
    if st is None: sys.exit(1)
    if "-f" in a: print({"true": "running", "false": "exited"}.get(st, st))
    sys.exit(0)
if a[0] == "ps":
    for n in sorted(os.listdir(d)):
        if os.path.isfile(os.path.join(d, n)) and running(n) == "true": print(n)
    sys.exit(0)
if a[0] == "stop": setrun(a[1], "false"); sys.exit(0)
if a[:4] == ["compose", "--profile", "cloud", "stop"]: setrun(a[4], "false"); sys.exit(0)
if a[0] == "restart":
    setrun(a[1], "true")
    if a[1].startswith("mc-backup") and not os.path.exists(os.path.join(fx, "backup-hang")):
        sid = open(os.path.join(fx, "next-snapshot")).read().strip()
        with open(os.path.join(d, "logs", a[1]), "a") as f: f.write("snapshot %s saved\n" % sid)
        with open(os.path.join(fx, "snapshots"), "a") as f: f.write(sid + "\n")
    sys.exit(0)
if a[0] == "logs":
    p = os.path.join(d, "logs", a[1])
    if os.path.exists(p): sys.stdout.write(open(p).read())
    sys.exit(0)
if a[0] == "exec":
    a = a[1:]
    if a[0] == "-u": a = a[2:]
    name, rest = a[0], a[1:]
    if running(name) != "true": sys.exit(1)
    if rest[:2] == ["restic", "snapshots"]:
        known = open(os.path.join(fx, "snapshots")).read().split() if os.path.exists(os.path.join(fx, "snapshots")) else []
        sys.exit(0 if rest[2] in known else 1)
    if rest[:2] == ["restic", "ls"]:
        print("/data/world/region/r.0.0.mca"); print("/data/world/level.dat"); sys.exit(0)
    if rest[0] == "rcon-cli":
        answers = __import__("json").load(open(os.path.join(fx, "rcon.json")))
        out = answers.get(rest[1])
        if out is None: sys.exit(1)
        print(out); sys.exit(0)
sys.exit(0)
'''

FAKE_GH = r'''#!/usr/bin/env python3
import os, sys
fx = os.environ["M26_FIXTURE"]
a = sys.argv[1:]
with open(os.path.join(fx, "calls.log"), "a") as f:
    f.write("gh " + " ".join(a) + "\n")
if a[:2] == ["run", "list"]:
    if os.path.exists(os.path.join(fx, "gh-fail")):
        print("error connecting to api.github.com", file=sys.stderr); sys.exit(1)
    if "--commit" in a: print("4242")
    else:
        p = os.path.join(fx, "gh-runs")
        print(open(p).read().strip() if os.path.exists(p) else "completed")
elif a[:2] == ["run", "view"]: print('{"status":"queued","conclusion":""}')
elif a[:2] == ["variable", "list"]:
    if os.path.exists(os.path.join(fx, "gh-var")): print("STACK_VERSION")
elif a[:2] == ["variable", "delete"]:
    os.remove(os.path.join(fx, "gh-var"))
'''

FAKE_UFW = r'''#!/usr/bin/env python3
import os, sys
fx = os.environ["M26_FIXTURE"]
a = sys.argv[1:]
with open(os.path.join(fx, "calls.log"), "a") as f:
    f.write("ufw " + " ".join(a) + "\n")
p = os.path.join(fx, "ufw-status")
if a[0] == "status":
    print("Status: active"); print(open(p).read() if os.path.exists(p) else "")
elif a[0] == "allow":
    with open(p, "a") as f: f.write(a[1] + "  ALLOW  Anywhere\n")
'''

FAKE_SUDO = '#!/usr/bin/env bash\necho "sudo $*" >> "$M26_FIXTURE/calls.log"\nexec "$@"\n'

RULES = "*filter\n# ok icmp codes\n-A ufw-before-input -p tcp --dport 25577 -j DROP\n# END RATE-LIMIT\nCOMMIT\n"

# The deletions, by group (migration-notes.md step 8), as fixture paths.
REQUIRED = [
    "data/world/region/r.0.0.mca", "data/world/level.dat", "data/unmined-web/maps/overworld/t.webp",
    "data/unmined-web/index.html", "data/unmined-web/manifest.json", "data/config/chunky/tasks/overworld.properties",
    "data/dynamic-data-pack-cache/x.json", "data/.dimension-setup/the_gauntlet", "data/.chunky-overworld-complete",
    "data/.dhpregen-overworld-complete", "data/.skip-pause", "data/config/custom-dimensions-fingerprints.json",
    "data/config/portal_links.json", "data/config/collective/collective.json", "data/config/doubledoors/x.json",
    "data/config/firespreadtweaks/x.json", "data/config/healingcampfire/x.json", "data/config/nametagtweaks/x.json",
    "data/config/nutritiousmilk/x.json", "data/config/youritemsaresafe/x.json", "data/config/collective.json5",
    "data/config/starterkit.json5",
]
TIDY = [
    "data/config/custom-dimensions/dimensions/the_gauntlet.json", "data/config/betterdays/x.json",
    "data/config/lootr/x.json", "data/config/boring_default_game_rules/config.json", "data/config/tectonic.json",
    "data/config/bountiful/x.json", "data/config/seasons.json", "data/config/configurable-difficulty/x.json",
    "data/config/bluemapsignmarkers/x.json",
]
SURVIVORS = [
    "data/mods/fabric-api.jar", "data/whitelist.json", "data/ops.json", "data/usercache.json",
    "data/banned-players.json", "data/config/geyser/config.yml", "data/unmined-web/unmined.css",
    "backups/older.tar.gz", "data/logs/latest.log",
]
SNAP = "1a2b3c4d"


class Fixture:
    def __init__(self, local=False):
        self.tmp = Path(tempfile.mkdtemp(prefix="m26-"))
        self.local = local
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, body in (("docker", FAKE_DOCKER), ("gh", FAKE_GH), ("ufw", FAKE_UFW), ("sudo", FAKE_SUDO)):
            (self.bin / name).write_text(body)
            (self.bin / name).chmod(0o755)
        (self.tmp / "docker/logs").mkdir(parents=True)
        for name in ("mc", "mc-backup-local") if local else ("mc", "mc-backup", "unmined-render"):
            (self.tmp / "docker" / name).write_text("true")
        (self.tmp / "next-snapshot").write_text(SNAP)
        self.rcon({"list": "There are 0 of a max of 20 players online:"})
        self.consumer = self.tmp / "consumer"
        self.server = self.consumer if local else self.tmp / "home/server"
        self.make_consumer()
        self.make_server()
        (self.tmp / "home/ufw").mkdir(parents=True)
        (self.tmp / "home/ufw/before.rules").write_text(RULES)
        (self.tmp / "home/ufw/before6.rules").write_text(RULES.replace("ufw-before", "ufw6-before"))

    def rcon(self, answers):
        (self.tmp / "rcon.json").write_text(json.dumps(answers))

    def write(self, root, rel, text="x"):
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def make_server(self):
        s = self.server
        for rel in REQUIRED + TIDY + SURVIVORS:
            self.write(s, rel)
        (s / ".stack/v5.27.1/stack").mkdir(parents=True, exist_ok=True)
        (s / ".stack/v5.27.1/stack/.minecraft-version").write_text("1.21.1\n")
        (s / ".stack/v6.0.0/stack").mkdir(parents=True, exist_ok=True)
        (s / ".stack/v6.0.0/stack/.minecraft-version").write_text("26.3\n")
        self.write(s, ".stack/v5.27.1/stack/scripts/backup-now.sh", "docker restart mc-backup\n")
        self.write(s, ".stack/v6.0.0/stack/scripts/backup-now.sh", "docker restart mc-backup\n")
        self.set_server_stack("v5.27.1")

    def set_server_stack(self, tag):
        link = self.server / ".stack/current"
        if link.is_symlink() or link.exists():
            link.unlink()
        link.symlink_to(tag)

    def git(self, *args, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd or self.consumer, check=True, capture_output=True, text=True).stdout

    def make_consumer(self):
        c = self.consumer
        c.mkdir(parents=True)
        remote = self.tmp / "remote.git"
        subprocess.run(["git", "init", "-q", "--bare", str(remote)], check=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")
        for name, body in (("ops", FAKE_OPS), ("dev", FAKE_DEV)):
            (c / name).write_text(body)
            (c / name).chmod(0o755)
        self.write(c, ".github/workflows/deploy.yml", "uses: piprees/minecraft-server-template/.github/workflows/deploy-reusable.yml@v5\n")
        self.write(c, ".gitignore", ".env\n.stack/\n/data/\nbackups/\n")
        self.write(c, "README.md", "elfydd-shaped\n")
        for slug in ("the_gauntlet", "the_hollow"):
            self.write(c, f"overlay/config/custom-dimensions/dimensions/{slug}_thumb.json", "{}")
            self.write(c, f"overlay/config/custom-dimensions/dimensions/{slug}_low.png", "png")
            self.write(c, f"overlay/config/custom-dimensions/dimensions/{slug}_high.png", "png")
        self.write(c, "overlay/config/deploy-banner.txt", "ELFYDD\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "init")
        self.git("remote", "add", "origin", str(remote))
        self.git("push", "-q", "-u", "origin", "main")
        if not self.local:
            (c / ".stack/v5.27.1/stack").mkdir(parents=True)
            (c / ".stack/current").symlink_to("v5.27.1")

    def run(self, *args, stdin="", apply=True):
        env = dict(os.environ, M26_FIXTURE=str(self.tmp), PATH=f"{self.bin}:{os.environ['PATH']}",
                   MIGRATE_UFW_DIR="~/ufw", HOME=str(self.tmp / "operator"))
        base = ["bash", str(SCRIPT), *args, "--target", "v6.0.0", "--attempts", "2", "--interval", "0"]
        base += ["--local", str(self.consumer)] if self.local else ["--consumer", str(self.consumer)]
        if apply:
            base.append("--apply")
        return subprocess.run(base, env=env, input=stdin, capture_output=True, text=True)

    def state(self):
        p = self.consumer / ".migrate-26.state"
        return dict(l.split("=", 1) for l in p.read_text().splitlines()) if p.exists() else {}

    def prime(self, **kv):
        p = self.consumer / ".migrate-26.state"
        p.write_text("".join(f"{k}={v}\n" for k, v in kv.items()))

    def calls(self):
        p = self.tmp / "calls.log"
        return p.read_text() if p.exists() else ""

    def snapshot_tree(self):
        out = {}
        for root in {self.server, self.consumer}:
            for p in sorted(root.rglob("*")):
                if ".git" in p.parts:
                    continue
                out[str(p)] = p.read_bytes() if p.is_file() and not p.is_symlink() else ("link:" + os.readlink(p) if p.is_symlink() else "dir")
        return out

    def archive_ready(self, snapshot=True):
        """The state archive leaves, made for real: the cold tar and its sum on the server."""
        tar = self.server / "backups/world-1.21.1-20260101.tar.gz"
        with tarfile.open(tar, "w:gz") as t:
            t.add(self.server / "data/world", arcname="data/world")
        import hashlib
        sha = hashlib.sha256(tar.read_bytes()).hexdigest()
        if snapshot:
            (self.tmp / "snapshots").write_text(SNAP + "\n")
        (self.tmp / "docker/mc").write_text("false")
        kv = dict(done_preflight=1, done_pin=1, pinned="v5.27.1", owner_release=1, done_archive=1, done_host=1,
                  world_tar="backups/world-1.21.1-20260101.tar.gz", world_sha=sha)
        kv["owner_cloud-firewall"] = 1
        if snapshot:
            kv["snapshot"] = SNAP
        self.prime(**kv)
        return sha

    def close(self):
        shutil.rmtree(self.tmp, ignore_errors=True)


class Base(unittest.TestCase):
    local = False

    def setUp(self):
        self.fx = Fixture(local=self.local)
        self.addCleanup(self.fx.close)

    def ok(self, r):
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout


class DryRunTests(Base):
    def test_plan_prints_every_step_in_order(self):
        r = self.fx.run("plan", apply=False)
        out = self.ok(r)
        ids = re.findall(r"^\s+\S*\s+(A\d|B\d|C\d|D\d+|E\d+)\s", out, re.M)
        self.assertEqual(ids, ["A1", "A2", "A3", "B4", "B5", "B6", "C7", "C7", "C8", "D9", "D10",
                               "E11", "E12", "E13", "E14", "E15", "E16"])
        for p in ("data/world/", "data/config/portal_links.json", "data/config/starterkit.json5", "data/config/tectonic.json"):
            self.assertIn(p, out)

    def test_dry_run_of_every_phase_changes_nothing(self):
        sha = self.fx.archive_ready()
        before = self.fx.snapshot_tree()
        state_before = (self.fx.consumer / ".migrate-26.state").read_text()
        for phase in ("preflight", "pin", "clean"):
            r = self.fx.run(phase, "--offhost-sha", sha, apply=False)
            self.assertIn("DRY RUN", r.stdout)
            self.assertNotIn("  RUN ", r.stdout)
        r = self.fx.run("clean", "--offhost-sha", sha, apply=False)
        self.ok(r)
        self.assertIn("would, after confirm clean: sudo rm -rf", r.stdout)
        self.assertIn("present  data/world/", r.stdout)
        self.assertEqual(self.fx.snapshot_tree(), before)
        self.assertEqual((self.fx.consumer / ".migrate-26.state").read_text(), state_before)
        self.assertNotIn("rm -rf --", self.fx.calls())

    def test_dry_run_preflight_writes_no_state(self):
        self.ok(self.fx.run("preflight", apply=False))
        self.assertFalse((self.fx.consumer / ".migrate-26.state").exists())


class OrderTests(Base):
    def test_a_phase_refuses_before_the_one_before_it(self):
        for phase, needs in (("pin", "preflight"), ("archive", "pin"), ("host", "archive"), ("clean", "host"),
                             ("consumer", "clean"), ("verify", "consumer"), ("first-backup", "verify")):
            r = self.fx.run(phase)
            self.assertEqual(r.returncode, 1, phase)
            self.assertIn(f"phase '{needs}' is not done", r.stderr)

    def test_unknown_yes_and_blanket_yes_are_refused(self):
        for flag in ("--yes-all", "--yes", "--yes-everything"):
            r = self.fx.run("preflight", flag)
            self.assertEqual(r.returncode, 2, flag)

    def test_a_confirm_step_without_its_yes_needs_the_typed_name(self):
        self.fx.archive_ready()
        sha = self.fx.state()["world_sha"]
        r = self.fx.run("clean", "--offhost-sha", sha, stdin="yes\n")
        self.assertEqual(r.returncode, 1)
        self.assertIn("'clean' not confirmed", r.stderr)
        self.assertTrue((self.fx.server / "data/world/level.dat").exists())
        r = self.fx.run("clean", "--offhost-sha", sha, "--non-interactive")
        self.assertIn("needs --yes-clean", r.stderr)
        self.assertTrue((self.fx.server / "data/world/level.dat").exists())
        self.ok(self.fx.run("clean", "--offhost-sha", sha, stdin="clean\n"))
        self.assertFalse((self.fx.server / "data/world").exists())


class CleanTests(Base):
    def refused(self, r, text):
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(text, r.stderr)
        for rel in REQUIRED:
            self.assertTrue((self.fx.server / rel).exists(), rel)

    def test_refuses_while_mc_runs(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "docker/mc").write_text("true")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "mc is running")

    def test_refuses_without_the_cold_tar(self):
        sha = self.fx.archive_ready()
        (self.fx.server / "backups/world-1.21.1-20260101.tar.gz").unlink()
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "is not on the server")

    def test_refuses_when_the_tar_no_longer_matches(self):
        sha = self.fx.archive_ready()
        with open(self.fx.server / "backups/world-1.21.1-20260101.tar.gz", "ab") as f:
            f.write(b"x")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "no longer matches")

    def test_refuses_without_or_with_a_wrong_offhost_sum(self):
        self.fx.archive_ready()
        self.refused(self.fx.run("clean", "--yes-clean"), "--offhost-sha <sum> is required")
        self.refused(self.fx.run("clean", "--offhost-sha", "0" * 64, "--yes-clean"), "is not the world tar's")

    def test_refuses_without_a_snapshot(self):
        sha = self.fx.archive_ready(snapshot=False)
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "no restic snapshot is recorded")

    def test_refuses_a_recorded_snapshot_restic_does_not_have(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "snapshots").write_text("ffffffff\n")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "not in the repository")

    def test_refuses_once_the_server_runs_v6(self):
        sha = self.fx.archive_ready()
        self.fx.set_server_stack("v6.0.0")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "not v5")

    def test_refuses_when_the_mc_probe_fails_while_later_calls_succeed(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "ssh-fail").write_text("docker inspect -f\n")
        r = self.fx.run("clean", "--offhost-sha", sha, "--yes-clean")
        self.refused(r, "cannot tell whether mc is running")
        self.assertNotIn("mc is stopped", r.stdout)
        # Only that call failed: without it the same run goes through.
        (self.fx.tmp / "ssh-fail").unlink()
        self.assertIn("ok    mc is stopped", self.ok(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean")))

    def test_refuses_when_the_mc_probe_answers_garbage(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "docker/mc").write_text("Error response from daemon: maybe")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "cannot tell whether mc is running")

    def test_refuses_when_the_mc_probe_answers_nothing(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "docker/mc").write_text("")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "cannot tell whether mc is running")

    def test_passes_when_mc_is_definitely_stopped(self):
        sha = self.fx.archive_ready()
        r = self.fx.run("clean", "--offhost-sha", sha, "--yes-clean")
        self.assertIn("ok    mc is stopped", self.ok(r))
        self.assertFalse((self.fx.server / "data/world").exists())

    def test_passes_when_mc_is_definitely_absent(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "docker/mc").unlink()
        r = self.fx.run("clean", "--offhost-sha", sha, "--yes-clean")
        self.assertIn("ok    mc is absent", self.ok(r))
        self.assertFalse((self.fx.server / "data/world").exists())

    def test_refuses_when_the_bundle_cannot_be_read(self):
        sha = self.fx.archive_ready()
        (self.fx.tmp / "ssh-fail").write_text("readlink .stack/current\n")
        self.refused(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"), "unreadable")

    def test_deletes_exactly_the_list_and_keeps_the_rest(self):
        sha = self.fx.archive_ready()
        self.ok(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean"))
        for rel in REQUIRED:
            self.assertFalse((self.fx.server / rel).exists(), rel)
        for rel in TIDY + SURVIVORS + ["backups/world-1.21.1-20260101.tar.gz"]:
            self.assertTrue((self.fx.server / rel).exists(), rel)
        self.assertEqual(self.fx.state()["done_clean"] != "", True)
        r = self.fx.run("clean", "--offhost-sha", sha, "--yes-clean")
        self.assertIn("nothing left to remove", self.ok(r))

    def test_tidy_also_removes_the_tidy_paths_only(self):
        sha = self.fx.archive_ready()
        self.ok(self.fx.run("clean", "--offhost-sha", sha, "--yes-clean", "--tidy"))
        for rel in REQUIRED + TIDY:
            self.assertFalse((self.fx.server / rel).exists(), rel)
        for rel in SURVIVORS:
            self.assertTrue((self.fx.server / rel).exists(), rel)


class ArchiveTests(Base):
    def primed(self):
        self.fx.prime(done_preflight=1, done_pin=1, pinned="v5.27.1")

    def test_owner_release_blocks_until_acknowledged(self):
        self.primed()
        r = self.fx.run("archive")
        self.assertEqual(r.returncode, 3)
        self.assertIn("Owner step due: release", r.stdout)
        self.assertIn("release.yml -f version=v6.0.0", r.stdout)
        self.assertNotIn("docker restart", self.fx.calls())

    def test_resume_after_an_interrupted_backup_and_map(self):
        self.primed()
        (self.fx.tmp / "backup-hang").write_text("1")
        r = self.fx.run("archive", "--owner-done", "release")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("no 'snapshot ... saved'", r.stdout)
        self.assertEqual(self.fx.calls().count("\ndocker restart mc-backup"), 1)
        self.assertIn("snapshot_since", self.fx.state())
        # The snapshot lands; the re-run finds it without triggering a second backup.
        (self.fx.tmp / "docker/logs/mc-backup").write_text(f"snapshot {SNAP} saved\n")
        (self.fx.tmp / "snapshots").write_text(SNAP + "\n")
        r = self.fx.run("archive")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("render pass is running", r.stdout)
        self.assertEqual(self.fx.calls().count("\ndocker restart mc-backup"), 1)
        self.assertEqual(self.fx.state()["snapshot"], SNAP)
        out = self.ok(self.fx.run("archive", "--yes-map", "--yes-stop-mc"))
        self.assertIn("ok    mc is stopped", out)
        st = self.fx.state()
        tar = self.fx.server / st["world_tar"]
        self.assertTrue(tar.exists())
        self.assertTrue((self.fx.server / st["map_tar"]).exists())
        self.assertEqual((self.fx.tmp / "docker/mc").read_text(), "false")
        self.assertIn("scp -i", out)
        self.assertIn(st["world_sha"], out)
        with tarfile.open(tar) as t:
            self.assertIn("data/world/level.dat", t.getnames())
        self.assertEqual(self.fx.calls().count("\ndocker compose --profile cloud stop mc"), 1)
        # A fourth run changes nothing.
        out = self.ok(self.fx.run("archive"))
        self.assertIn("recorded, matches", out)
        self.assertEqual(self.fx.calls().count("\ndocker compose --profile cloud stop mc"), 1)

    def test_refuses_to_stop_mc_with_players_online(self):
        self.primed()
        self.fx.prime(done_preflight=1, done_pin=1, pinned="v5.27.1", owner_release=1, snapshot=SNAP, map_tar="data/ops.json")
        (self.fx.tmp / "snapshots").write_text(SNAP + "\n")
        self.fx.rcon({"list": "There are 2 of a max of 20 players online: a, b"})
        r = self.fx.run("archive", "--yes-stop-mc")
        self.assertEqual(r.returncode, 1)
        self.assertIn("2 player(s) online", r.stderr)
        self.assertEqual((self.fx.tmp / "docker/mc").read_text(), "true")

    def test_an_unreachable_server_is_not_rcon_silence(self):
        self.fx.prime(done_preflight=1, done_pin=1, pinned="v5.27.1", owner_release=1, snapshot=SNAP, map_tar="data/ops.json")
        (self.fx.tmp / "snapshots").write_text(SNAP + "\n")
        (self.fx.tmp / "ssh-fail").write_text("rcon-cli 'list'\n")
        r = self.fx.run("archive", "--yes-stop-mc")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("cannot reach the server to ask who is online", r.stderr)
        self.assertNotIn("autopaused", r.stdout)
        self.assertEqual((self.fx.tmp / "docker/mc").read_text(), "true")

    def test_the_cold_tar_never_overwrites_a_tar_it_did_not_record(self):
        self.fx.prime(done_preflight=1, done_pin=1, pinned="v5.27.1", owner_release=1, snapshot=SNAP, map_tar="data/ops.json")
        (self.fx.tmp / "snapshots").write_text(SNAP + "\n")
        import datetime
        tar = self.fx.server / f"backups/world-1.21.1-{datetime.date.today():%Y%m%d}.tar.gz"
        tar.write_bytes(b"an earlier archive")
        r = self.fx.run("archive", "--yes-stop-mc")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("exists and is not the recorded tar", r.stderr)
        self.assertEqual(tar.read_bytes(), b"an earlier archive")
        self.assertEqual((self.fx.tmp / "docker/mc").read_text(), "true")

    def test_no_cold_tar_of_a_world_that_is_gone(self):
        self.fx.prime(done_preflight=1, done_pin=1, pinned="v5.27.1", owner_release=1, snapshot=SNAP, map_tar="data/ops.json")
        (self.fx.tmp / "snapshots").write_text(SNAP + "\n")
        (self.fx.server / "data/world/level.dat").unlink()
        r = self.fx.run("archive", "--yes-stop-mc")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("no 1.21.1 world to archive", r.stderr)

    def test_a_failed_stop_probe_takes_no_tar(self):
        self.fx.prime(done_preflight=1, done_pin=1, pinned="v5.27.1", owner_release=1, snapshot=SNAP, map_tar="data/ops.json")
        (self.fx.tmp / "snapshots").write_text(SNAP + "\n")
        (self.fx.tmp / "ssh-fail").write_text("docker inspect -f\n")
        r = self.fx.run("archive", "--yes-stop-mc")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("cannot tell whether mc is running", r.stderr)
        self.assertFalse(list((self.fx.server / "backups").glob("world-1.21.1-*")))

    def test_a_failed_run_listing_is_not_an_idle_repo(self):
        self.fx.prime(done_preflight=1)
        (self.fx.tmp / "gh-fail").write_text("1")
        r = self.fx.run("pin", "--yes-pin")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("cannot list the consumer's workflow runs", r.stderr)
        self.assertFalse((self.fx.consumer / ".stack-version").exists())

    def test_rcon_silence_is_autopause_not_failure(self):
        self.fx.rcon({})
        out = self.ok(self.fx.run("preflight"))
        self.assertIn("autopaused", out)


class FlowTests(Base):
    def test_the_whole_migration_through_the_shim(self):
        fx = self.fx
        (fx.tmp / "gh-var").write_text("1")
        self.ok(fx.run("preflight"))
        self.ok(fx.run("pin", "--yes-pin"))
        self.assertEqual((fx.consumer / ".stack-version").read_text().strip(), "v5.27.1")
        self.assertFalse((fx.tmp / "gh-var").exists())
        self.assertIn("gh run list --workflow deploy.yml --commit", fx.calls())
        self.assertEqual(fx.git("rev-list", "--count", "@{u}..HEAD").strip(), "0")
        self.assertEqual(fx.run("archive").returncode, 3)
        self.assertEqual(fx.run("archive", "--owner-done", "release").returncode, 3)
        self.ok(fx.run("archive", "--yes-map", "--yes-stop-mc"))
        out = self.ok(fx.run("host", "--yes-firewall"))
        self.assertIn("cloud-firewall", out)
        for f in ("before.rules", "before6.rules"):
            text = (fx.tmp / "home/ufw" / f).read_text()
            self.assertEqual(text.count("mc-bedrock"), 1, text)
            self.assertLess(text.index("mc-bedrock"), text.index("# END RATE-LIMIT"))
            self.assertTrue(list((fx.tmp / "home/ufw").glob(f + ".bak.*")))
        self.assertIn("19132/udp", (fx.tmp / "ufw-status").read_text())
        self.ok(fx.run("host", "--yes-firewall"))
        self.assertEqual((fx.tmp / "home/ufw/before.rules").read_text().count("mc-bedrock"), 1)
        sha = fx.state()["world_sha"]
        self.assertEqual(fx.run("clean", "--offhost-sha", sha, "--yes-clean").returncode, 3)
        out = self.ok(fx.run("clean", "--offhost-sha", sha, "--yes-clean", "--owner-done", "cloud-firewall"))
        self.assertIn("FLOODGATE_KEY_B64", out)
        self.assertEqual(fx.run("consumer", "--yes-consumer").returncode, 3)
        self.ok(fx.run("consumer", "--yes-consumer", "--owner-done", "secrets"))
        self.assertEqual((fx.consumer / ".stack-version").read_text().strip(), "v6.0.0")
        self.assertIn("dev update --scaffold-only STACK_VERSION=v6.0.0", fx.calls())
        self.assertFalse(list((fx.consumer / "overlay/config/custom-dimensions").rglob("*_thumb.json")))
        self.assertTrue((fx.consumer / "overlay/config/deploy-banner.txt").exists())
        self.assertEqual(fx.git("log", "-1", "--format=%s").strip(), "feat!: move to the v6 stack (Minecraft 26.3)")
        self.assertEqual(fx.git("rev-list", "--count", "@{u}..HEAD").strip(), "0")
        self.assertNotIn(".migrate-26.state", fx.git("ls-files"))
        # The deploy, simulated: the server on v6 with an empty 26.3 world.
        self.assertEqual(fx.run("verify").returncode, 3)
        fx.set_server_stack("v6.0.0")
        dims = fx.server / "data/config/custom-dimensions/dimensions"
        fx.write(dims, "overworld.json", '{\n  "schemaVersion": 2,\n  "seed": 4705305050088502262\n}')
        fx.write(dims, "settings.json", '{"schemaVersion": 2, "namespace": "adventure"}')
        fx.write(dims, "the_ashgrove.json", '{"schemaVersion": 2, "gameRules": {"spawn_phantoms": true}}')
        fx.write(dims, "the_aviary.json", "{}")
        fx.write(fx.server / "data", "server.properties", "level-name=world\nlevel-seed=20261022\n")
        (fx.tmp / "docker/mc").write_text("true")
        answers = {
            "list": "There are 0 of a max of 20 players online:",
            "customdim list": "82 dimensions: 4 base, 78 lazy, 4 open",
            "customdim audit": "audit: 0 stale, 0 missing, 0 orphan",
            "execute in minecraft:overworld run seed": "Seed: [4705305050088502262]",
            "execute in minecraft:the_nether run seed": "Seed: [20261022]",
            "execute in minecraft:the_end run seed": "Seed: [20261022]",
            "execute in adventure:the_aviary run seed": "Seed: [20261022]",
            "gamerule spawn_phantoms": "Gamerule spawn_phantoms is currently set to: false",
            "gamerule log_admin_commands": "Gamerule log_admin_commands is currently set to: true",
            "customdim activate the_ashgrove": "Activated adventure:the_ashgrove",
            "execute in adventure:the_ashgrove run gamerule spawn_phantoms": "Gamerule spawn_phantoms is currently set to: true",
        }
        fx.rcon(answers)
        out = self.ok(fx.run("verify"))
        self.assertIn("82 dimensions: 4 base, 78 lazy", out)
        self.assertIn("minecraft:overworld generates from 4705305050088502262, its definition's seed", out)
        self.assertIn("minecraft:the_end generates from 20261022, its definition's seed", out)
        self.assertIn("adventure:the_aviary generates from 20261022, its definition's seed", out)
        self.assertIn("adventure:the_ashgrove holds spawn_phantoms true, its own value", out)
        self.assertIn("docs/migrate-to-26.md § Pre-generation", out)
        (fx.tmp / "next-snapshot").write_text("5e6f7a8b")
        out = self.ok(fx.run("first-backup"))
        self.assertEqual(fx.state()["first_snapshot"], "5e6f7a8b")
        self.assertIn("Owner step", out.replace("Next, an owner step", "Owner step"))
        self.ok(fx.run("status", "--owner-done", "launch"))
        self.assertIn("done     owner launch", self.ok(fx.run("status", apply=False)))

    def test_consumer_refuses_when_the_world_probe_fails(self):
        fx = self.fx
        fx.archive_ready()
        fx.prime(**dict(fx.state(), done_clean=1, owner_secrets=1))
        shutil.rmtree(fx.server / "data/world")
        (fx.tmp / "ssh-fail").write_text("test -e data/world\n")
        r = fx.run("consumer", "--yes-consumer")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("cannot tell whether 'test -e data/world' holds", r.stderr)
        self.assertNotIn("git push", r.stdout)
        self.assertEqual(fx.git("log", "-1", "--format=%s").strip(), "init")

    def test_verify_reports_a_seed_mismatch_and_never_resets(self):
        # The overworld's definition says -42; its level answers 123, the seed its chunks kept.
        fx = self.fx
        fx.prime(done_preflight=1, done_pin=1, done_archive=1, done_host=1, done_clean=1, done_consumer=1)
        fx.set_server_stack("v6.0.0")
        fx.write(fx.server / "data/config/custom-dimensions/dimensions", "overworld.json", '{"seed": -42}')
        fx.write(fx.server / "data", "server.properties", "level-seed=123\n")
        fx.rcon(dict(BASE_SEEDS_123, **{"customdim list": "82 dimensions: 4 base, 78 lazy, 0 open",
                 "gamerule spawn_phantoms": "x: false", "gamerule log_admin_commands": "x: true"}))
        r = fx.run("verify")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("MISMATCH  minecraft:overworld generates from 123; its definition says -42", r.stdout)
        self.assertIn("./ops reset-seed --same-seed", r.stdout)
        self.assertNotIn("reset-seed", fx.calls())
        self.assertNotIn("done_verify", fx.state())

    def test_verify_refuses_a_lazy_level_off_the_server_wide_rule(self):
        fx = self.fx
        fx.prime(done_preflight=1, done_pin=1, done_archive=1, done_host=1, done_clean=1, done_consumer=1)
        fx.set_server_stack("v6.0.0")
        dims = fx.server / "data/config/custom-dimensions/dimensions"
        fx.write(dims, "settings.json", '{"schemaVersion": 2, "namespace": "adventure"}')
        fx.write(dims, "the_ashgrove.json", '{"schemaVersion": 2}')
        fx.write(fx.server / "data", "server.properties", "level-seed=123\n")
        fx.rcon({**BASE_SEEDS_123, "customdim list": "82 dimensions: 4 base, 78 lazy, 0 open",
                 "gamerule spawn_phantoms": "x: false", "gamerule log_admin_commands": "x: true",
                 "execute in adventure:the_ashgrove run gamerule spawn_phantoms": "x: true"})
        r = fx.run("verify")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("MISMATCH  adventure:the_ashgrove holds spawn_phantoms true; the server-wide value is false", r.stdout)
        self.assertNotIn("done_verify", fx.state())

    def test_verify_waits_on_a_silent_server(self):
        fx = self.fx
        fx.prime(done_preflight=1, done_pin=1, done_archive=1, done_host=1, done_clean=1, done_consumer=1)
        fx.set_server_stack("v6.0.0")
        fx.rcon({})
        r = fx.run("verify")
        self.assertEqual(r.returncode, 3)
        self.assertIn("autopaused or still booting", r.stdout)

    def test_no_streaming_or_unbounded_command(self):
        self.test_the_whole_migration_through_the_shim()
        calls = self.fx.calls()
        for bad in ("logs -f", "--follow", "run watch", "while true", "live-logs", "tail -f"):
            self.assertNotIn(bad, calls)
            self.assertNotIn(bad, SCRIPT.read_text())
        self.assertNotRegex(SCRIPT.read_text(), r"while\s+(true|:)")
        for line in calls.splitlines():
            if line.startswith("docker logs"):
                self.assertIn("--tail", line)


# Every base world answering the world seed 123.
BASE_SEEDS_123 = {"execute in %s run seed" % level: "Seed: [123]" for level in
                  ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "adventure:the_aviary")}


class LocalTests(Base):
    local = True

    def test_local_skips_host_and_uses_no_ssh(self):
        self.fx.prime(done_preflight=1, done_pin=1, done_archive=1)
        out = self.ok(self.fx.run("host"))
        self.assertIn("--local: no ufw and no cloud firewall on a local stack; host skipped", out)
        self.assertNotIn("ops ssh", self.fx.calls())
        self.assertNotIn("ufw", self.fx.calls())

    def test_local_runs_the_same_phases_without_ssh_or_push(self):
        fx = self.fx
        self.ok(fx.run("preflight"))
        self.ok(fx.run("pin", "--yes-pin"))
        self.ok(fx.run("archive", "--owner-done", "release", "--yes-stop-mc"))
        self.assertIn("mc-backup-local", fx.calls())
        self.ok(fx.run("host"))
        sha = fx.state()["world_sha"]
        self.ok(fx.run("clean", "--offhost-sha", sha, "--yes-clean"))
        self.assertFalse((fx.server / "data/world").exists())
        self.ok(fx.run("consumer", "--owner-done", "secrets", "--yes-consumer", "--link", "/switched"))
        calls = fx.calls()
        self.assertIn("dev link /switched", calls)
        self.assertIn("dev refresh-config", calls)
        self.assertIn("dev up", calls)
        self.assertNotIn("ops ssh", calls)
        self.assertNotIn("gh ", calls)
        self.assertEqual(fx.git("rev-list", "--count", "@{u}..HEAD").strip(), "2")


if __name__ == "__main__":
    unittest.main()
