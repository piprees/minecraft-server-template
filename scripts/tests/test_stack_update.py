#!/usr/bin/env python3
"""Tests for consumer template-version bumps.

Covers the pure logic in scripts/stack_update.py (target selection, bump
class, PR body, image list), the pin precedence and major guard in
deploy-reusable.yml's "Resolve stack version" step (run as the real step
script against a stub `gh`), `./dev`'s pin precedence, and
stamp-version-defaults.sh's scaffold stamping.
"""
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import stack_update as su  # noqa: E402


def rel(tag, body="", draft=False, prerelease=False):
    return {"tag_name": tag, "body": body, "draft": draft, "prerelease": prerelease,
            "html_url": "https://example.test/%s" % tag}


RELEASES = [
    rel("v6.1.0", "## v6.1.0 — 2026-09-20\n\n### Added\n\n- six-one feature\n"),
    rel("v6.0.0", "## v6.0.0 — 2026-09-10\n\n### Breaking changes\n\n- consumers must rename X\n\n"
                  "### Fixed\n\n- six-oh fix\n"),
    rel("v7.0.0-rc.1", "prerelease tag shape"),
    rel("v7.0.0", "a draft", draft=True),
    rel("v6.2.0", "flagged prerelease", prerelease=True),
    rel("v5.10.0", "## v5.10.0\n\n### Added\n\n- ten\n"),
    rel("v5.9.1", "## v5.9.1\n\n### Fixed\n\n- nine-one\n"),
    rel("v5.9.0", "## v5.9.0\n\n### Added\n\n- nine\n"),
    rel("v5.2.0", "## v5.2.0\n"),
    rel("latest-docs", "not a version"),
]


class VersionSelectionTests(unittest.TestCase):
    def test_target_is_highest_stable_semver_not_publish_order(self):
        # v5.10.0 sorts above v5.9.1 numerically, not lexically; drafts,
        # prereleases and rc tags never win.
        shuffled = list(reversed(RELEASES))
        self.assertEqual(su.plan("v5.9.0", shuffled)["target"], "v6.1.0")

    def test_skips_drafts_and_prereleases(self):
        tags = [su.fmt(v) for v, _ in su.stable_releases(RELEASES)]
        self.assertEqual(tags, ["v6.1.0", "v6.0.0", "v5.10.0", "v5.9.1", "v5.9.0", "v5.2.0"])

    def test_resolve_pin_forms(self):
        self.assertEqual(su.resolve_pin("latest", RELEASES), "v6.1.0")
        self.assertEqual(su.resolve_pin("", RELEASES), "v6.1.0")
        self.assertEqual(su.resolve_pin("v5", RELEASES), "v5.10.0")
        self.assertEqual(su.resolve_pin("5.9", RELEASES), "v5.9.1")
        self.assertEqual(su.resolve_pin("v5.9.0", RELEASES), "v5.9.0")
        self.assertIsNone(su.resolve_pin("v4", RELEASES))
        self.assertIsNone(su.resolve_pin("v5.x", RELEASES))
        self.assertIsNone(su.resolve_pin("v7", RELEASES))

    def test_up_to_date_is_none(self):
        p = su.plan("v6.1.0", RELEASES)
        self.assertEqual((p["target"], p["bump"]), ("v6.1.0", "none"))

    def test_ahead_of_every_release_stays_put(self):
        self.assertEqual(su.plan("v9.0.0", RELEASES)["bump"], "none")

    def test_plan_rejects_floating_current(self):
        with self.assertRaises(ValueError):
            su.plan("v5", RELEASES)


class ClassifyTests(unittest.TestCase):
    def test_classes(self):
        self.assertEqual(su.classify("v5.9.0", "v5.9.1"), "patch")
        self.assertEqual(su.classify("v5.9.1", "v5.10.0"), "minor")
        self.assertEqual(su.classify("v5.10.0", "v6.0.0"), "major")
        self.assertEqual(su.classify("v5.10.0", "v5.10.0"), "none")
        self.assertEqual(su.classify("v5.10.0", "v5.9.0"), "none")

    def test_plan_majors(self):
        p = su.plan("v5.9.0", RELEASES)
        self.assertEqual((p["bump"], p["current_major"], p["target_major"]), ("major", "5", "6"))


class BodyTests(unittest.TestCase):
    def test_between_is_exclusive_inclusive_newest_first(self):
        tags = [r["tag_name"] for r in su.releases_between("v5.9.0", "v6.0.0", RELEASES)]
        self.assertEqual(tags, ["v6.0.0", "v5.10.0", "v5.9.1"])

    def test_split_breaking(self):
        breaking, rest = su.split_breaking(RELEASES[1]["body"])
        self.assertEqual(breaking, "- consumers must rename X")
        self.assertIn("six-oh fix", rest)
        self.assertNotIn("rename X", rest)

    def test_major_body_puts_breaking_first_and_checklist(self):
        body = su.render_body("v5.9.0", "v6.1.0", RELEASES, deploy_major=5)
        self.assertIn("Template **major** update: `v5.9.0` → `v6.1.0`.", body)
        self.assertIn("`deploy-reusable.yml@v5` to `deploy-reusable.yml@v6`", body)
        self.assertLess(body.index("### Breaking changes"), body.index("### Release notes"))
        self.assertLess(body.index("#### [v6.1.0]"), body.index("#### [v5.9.1]"))
        self.assertNotIn("#### [v5.9.0]", body)
        self.assertNotIn("v7.0.0", body)
        # Release-body headings sit below the PR's own.
        self.assertIn("##### Added", body)
        self.assertNotIn("## v6.1.0 —", body)

    def test_minor_auto_merge_has_no_checklist(self):
        body = su.render_body("v5.9.0", "v5.10.0", RELEASES, deploy_major=5,
                              verification="ok", auto_merge=True)
        self.assertNotIn("Before merging", body)
        self.assertIn("Auto-merging: a minor bump", body)
        self.assertIn("**Deployability:** ok", body)

    def test_workflow_files_become_a_manual_step(self):
        body = su.render_body("v5.9.0", "v5.9.1", RELEASES, deploy_major=5,
                              workflow_files=[".github/workflows/update.yml"])
        self.assertIn("- [ ] Run `./dev update` locally and commit `.github/workflows`", body)
        self.assertIn("`.github/workflows/update.yml`", body)

    def test_migration_only(self):
        body = su.render_body("v6.1.0", "v6.1.0", RELEASES, deploy_major=6, migrated_from="v6")
        self.assertIn("Pins the template exactly at `v6.1.0`", body)
        self.assertIn("**Migration:**", body)
        self.assertNotIn("### Release notes", body)


class BodyBudgetTests(unittest.TestCase):
    def test_body_stays_under_githubs_limit(self):
        big = [rel("v5.%d.0" % i, "## v5.%d.0\n\n### Added\n\n" % i + "- line\n" * 2000)
               for i in range(1, 30)]
        body = su.render_body("v5.0.0", "v5.29.0", big)
        self.assertLess(len(body), 65536)
        self.assertIn("older releases down to v5.1.0", body)
        self.assertIn("#### [v5.29.0]", body)


class ImageTests(unittest.TestCase):
    COMPOSE = textwrap.dedent("""\
        services:
          seed:
            image: ${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}/defaults-seed:${IMAGE_TAG:-latest}
          mc:
            image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/itzg/minecraft-server:2026.7.0-java21
          other:
            image: "nginx:1.30"
          dup:
            image: ${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}/defaults-seed:${IMAGE_TAG:-latest}
        #   image: registry.example/commented:latest
        """)

    def test_images_at_tag(self):
        images = su.compose_images([self.COMPOSE], "5.9.1", extras=["modpack-builder"], env={})
        self.assertEqual(images, [
            "ghcr.io/piprees/minecraft-server-template/defaults-seed:5.9.1",
            "ghcr.io/piprees/minecraft-server-template/modpack-builder:5.9.1",
            "ghcr.io/piprees/mirrors/itzg/minecraft-server:2026.7.0-java21",
            "nginx:1.30",
        ])

    def test_registry_override(self):
        images = su.compose_images([self.COMPOSE], "1.0.0", env={"IMAGE_REGISTRY": "r.test/x"})
        self.assertIn("r.test/x/defaults-seed:1.0.0", images)

    def test_split_image(self):
        self.assertEqual(su.split_image("ghcr.io/a/b/c:1.2"), ("ghcr.io", "a/b/c", "1.2"))
        self.assertEqual(su.split_image("nginx:1.30"), ("registry-1.docker.io", "library/nginx", "1.30"))
        self.assertEqual(su.split_image("minio/mc"), ("registry-1.docker.io", "minio/mc", "latest"))

    def test_deploy_major(self):
        self.assertEqual(su.deploy_major("uses: o/r/.github/workflows/deploy-reusable.yml@v5\n"), 5)
        self.assertIsNone(su.deploy_major("uses: o/r/.github/workflows/deploy-reusable.yml@main\n"))


class CliTests(unittest.TestCase):
    def run_cli(self, *args):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(RELEASES, fh)
        self.addCleanup(os.unlink, fh.name)
        return subprocess.run([sys.executable, str(ROOT / "scripts/stack_update.py"), args[0],
                               "--releases", fh.name, *args[1:]],
                              capture_output=True, text=True)

    def test_plan_output_is_github_output_lines(self):
        out = self.run_cli("plan", "--current", "v5.9.1").stdout
        self.assertIn("target=v6.1.0\nbump=major\n", out)

    def test_resolve_miss_exits_nonzero(self):
        self.assertEqual(self.run_cli("resolve", "--pin", "v4").returncode, 1)


# ---- deploy-reusable.yml "Resolve stack version" -----------------------------

def resolve_step_script():
    wf = yaml.safe_load((ROOT / ".github/workflows/deploy-reusable.yml").read_text())
    for step in wf["jobs"]["deploy"]["steps"]:
        if step.get("id") == "stack":
            return step["run"]
    raise AssertionError("no step with id: stack")


class DeployResolveTests(unittest.TestCase):
    TAGS = ["v6.1.0", "v6.0.0", "v5.10.0", "v5.9.1", "v5.9.0", "v5.2.0", "v7.0.0-rc.1"]

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        bindir = self.tmp / "bin"
        bindir.mkdir()
        gh = bindir / "gh"
        # Stands in for `gh api ... --jq`: prints what the jq filter emits.
        gh.write_text("#!/usr/bin/env bash\nprintf '%s\\n' " + " ".join(self.TAGS) + "\n")
        gh.chmod(gh.stat().st_mode | stat.S_IEXEC)
        self.work = self.tmp / "repo"
        self.work.mkdir()
        self.script = self.tmp / "step.sh"
        self.script.write_text(resolve_step_script())
        self.path = "%s:%s" % (bindir, os.environ["PATH"])

    def run_step(self, pin_file=None, override="", pin_input="",
                 ref="piprees/minecraft-server-template/.github/workflows/deploy-reusable.yml@refs/tags/v5"):
        if pin_file is not None:
            (self.work / ".stack-version").write_text(pin_file)
        out_file = self.tmp / "out"
        out_file.write_text("")
        env = dict(os.environ, PATH=self.path, GITHUB_OUTPUT=str(out_file),
                   PIN_OVERRIDE=override, PIN_INPUT=pin_input,
                   JOB_CONTEXT=json.dumps({"status": "success", "workflow_ref": ref} if ref else {}))
        proc = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", str(self.script)], cwd=self.work, env=env,
                              capture_output=True, text=True)
        outputs = dict(line.split("=", 1) for line in out_file.read_text().splitlines() if "=" in line)
        return proc, outputs

    def test_file_wins_over_legacy_input(self):
        proc, out = self.run_step(pin_file="# pin\nv5.9.0\n", pin_input="v5.9.1")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(out["resolved"], "v5.9.0")
        self.assertIn("::warning::.stack-version (v5.9.0) wins", proc.stdout)

    def test_legacy_latest_input_is_silent_beside_file(self):
        proc, _ = self.run_step(pin_file="v5.9.0\n", pin_input="latest")
        self.assertNotIn("::warning::", proc.stdout)

    def test_override_wins_over_file(self):
        _, out = self.run_step(pin_file="v5.9.0\n", override="v5.2.0")
        self.assertEqual((out["resolved"], out["pin"]), ("v5.2.0", "v5.2.0"))

    def test_legacy_input_without_file(self):
        _, out = self.run_step(pin_input="v5.9")
        self.assertEqual(out["resolved"], "v5.9.1")

    def test_latest_is_held_to_workflow_major(self):
        _, out = self.run_step()
        self.assertEqual(out["resolved"], "v5.10.0")

    def test_patch_ref_counts_as_major(self):
        _, out = self.run_step(ref="o/r/.github/workflows/deploy-reusable.yml@refs/tags/v6.0.0")
        self.assertEqual(out["resolved"], "v6.1.0")

    def test_major_mismatch_fails_before_the_server(self):
        proc, out = self.run_step(pin_file="v6.0.0\n")
        self.assertEqual(proc.returncode, 1)
        self.assertNotIn("resolved", out)
        self.assertIn("Update .github/workflows/deploy.yml to deploy-reusable.yml@v6", proc.stdout)

    def test_branch_ref_skips_guard_with_notice(self):
        proc, out = self.run_step(pin_file="v6.0.0\n",
                                  ref="o/r/.github/workflows/deploy-reusable.yml@refs/heads/main")
        self.assertEqual(out["resolved"], "v6.0.0")
        self.assertIn("::notice::deploy-reusable.yml runs from branch main", proc.stdout)

    def test_missing_ref_skips_guard_and_latest_is_global(self):
        proc, out = self.run_step(ref="")
        self.assertEqual(out["resolved"], "v6.1.0")
        self.assertIn("is not a version tag", proc.stdout)

    def test_bad_pin_is_refused(self):
        proc, _ = self.run_step(pin_file="v5'; rm -rf /\n")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("is not latest, vN, vN.M or vN.M.P", proc.stdout)

    def test_unknown_release_fails(self):
        proc, _ = self.run_step(pin_file="v5.3.0\n")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("No platform release matches stack pin 'v5.3.0'", proc.stdout)


# ---- ./dev pin precedence -----------------------------------------------------

class DevPinPrecedenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        shutil.copy(ROOT / "examples/consumer/dev", self.tmp / "dev")
        scripts = self.tmp / ".stack/v0.0.1/stack/scripts"
        scripts.mkdir(parents=True)
        (self.tmp / ".stack/current").symlink_to("v0.0.1")
        # The bundle's puller, reduced to reporting the pin it was handed.
        (scripts / "stack-pull.sh").write_text('echo "PIN=${STACK_VERSION:-<unset>}"\n')

    def pull(self, ambient=None):
        env = {k: v for k, v in os.environ.items() if k != "STACK_VERSION"}
        if ambient is not None:
            env["STACK_VERSION"] = ambient
        proc = subprocess.run(["bash", str(self.tmp / "dev"), "pull"], cwd=self.tmp, env=env,
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return [l for l in proc.stdout.splitlines() if l.startswith("PIN=")][-1][4:], proc.stderr

    def test_file_beats_env_file_and_ambient(self):
        (self.tmp / ".stack-version").write_text("# exact pin\nv5.9.1\n")
        (self.tmp / ".env").write_text("STACK_VERSION='v5'\n")
        pin, err = self.pull(ambient="v4")
        self.assertEqual(pin, "v5.9.1")
        self.assertIn("from .stack-version (ignoring exported STACK_VERSION=v4)", err)

    def test_env_file_beats_ambient_without_pin_file(self):
        (self.tmp / ".env").write_text("STACK_VERSION='v5'\n")
        self.assertEqual(self.pull(ambient="v4")[0], "v5")

    def test_ambient_then_unset(self):
        self.assertEqual(self.pull(ambient="v5.2.0")[0], "v5.2.0")
        self.assertEqual(self.pull()[0], "<unset>")

    def test_empty_pin_file_falls_through(self):
        (self.tmp / ".stack-version").write_text("\n# nothing\n")
        (self.tmp / ".env").write_text("STACK_VERSION=v5\n")
        self.assertEqual(self.pull()[0], "v5")


# ---- stamp-version-defaults.sh --------------------------------------------------

class StampTests(unittest.TestCase):
    def test_stamps_pin_file_and_workflow_major(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        for rel_path in ("scripts/stamp-version-defaults.sh", ".env.example",
                         "examples/consumer/.env.example", "examples/consumer/.stack-version",
                         "examples/consumer/.github/workflows/deploy.yml"):
            (tmp / rel_path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / rel_path, tmp / rel_path)
        run = lambda: subprocess.run(["bash", str(tmp / "scripts/stamp-version-defaults.sh"), "v9.3.2"],
                                     capture_output=True, text=True)
        first = run()
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual((tmp / "examples/consumer/.stack-version").read_text(), "v9.3.2\n")
        deploy = (tmp / "examples/consumer/.github/workflows/deploy.yml").read_text()
        self.assertIn("deploy-reusable.yml@v9\n", deploy)
        self.assertEqual(su.deploy_major(deploy), 9)
        second = run()
        self.assertIn("already current at v9", second.stdout)


if __name__ == "__main__":
    unittest.main()
