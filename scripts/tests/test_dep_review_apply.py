#!/usr/bin/env python3
"""Tests for apply.py, the only dep-review step that acts on a PR.

The reviewer's verdict is untrusted: these tests pin that it can only make a
PR less mergeable than the policy allows, that a broken or credential-bearing
verdict fails closed, and that nothing it writes can forge markup or the state
marker in the sticky comment.
"""
import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from dep_review import apply, common  # noqa: E402

REAL_MANIFEST = common.REPO_ROOT / "modpack" / "adventure.mrpack.json"
HEAD = "a" * 40


def change(name, eco="mod", old="1.0.0", new="1.1.0", flags=()):
    return {"key": common.change_key(eco, name, new), "ecosystem": eco, "name": name,
            "old": old, "new": new, "files": [], "sides": ["server"], "flags": list(flags), "details": {}}


def judged(ch, decision="accept", risk="low", reason="Changelog is bug fixes only.", evidence=None):
    return {"key": ch["key"], "decision": decision, "risk": risk, "reason": reason,
            "evidence": evidence if evidence is not None else ["Fixes a crash on join."]}


def verdict(changes, summary="All routine.", holds_add=(), holds_remove=()):
    return {"summary": summary, "changes": changes,
            "holds_add": list(holds_add), "holds_remove": list(holds_remove)}


GREEN = {"quick": "success", "smoke": "success", "docker_build": "success",
         "checks": [{"name": "lint", "status": "completed", "conclusion": "success"}]}


class ApplyCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.manifest = self.dir / "adventure.mrpack.json"
        shutil.copyfile(REAL_MANIFEST, self.manifest)

    def run_apply(self, work, verdict_obj=None, source="mods", tests=GREEN, cached=(),
                  previous_state=None, verdict_text=None, **gate_extra):
        gate = {"ok": True, "reason": "", "skip": False, "pr": 42, "source": source,
                "head_sha": HEAD, "base_sha": "b" * 40, "head_ref": "mod-updates/auto",
                "fingerprint": "fp1", "state_comment_id": None, "previous_state": previous_state,
                "needs_smoke": False, "dockerfiles": [], "worldgen_paths": [], "files": []}
        gate.update(gate_extra)
        context = {"pr": {"number": 42, "source": source, "head_sha": HEAD, "base_sha": "b" * 40},
                   "target_mc": "1.21.1", "work": work, "cached": list(cached), "holds": [], "notes": []}
        files = {"gate.json": gate, "context.json": context, "tests.json": tests}
        for name, obj in files.items():
            (self.dir / name).write_text(json.dumps(obj))
        verdict_path = self.dir / "verdict.json"
        if verdict_text is not None:
            verdict_path.write_text(verdict_text)
        elif verdict_obj is not None:
            verdict_path.write_text(json.dumps(verdict_obj))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = apply.main([
                "--gate", str(self.dir / "gate.json"), "--context", str(self.dir / "context.json"),
                "--verdict", str(verdict_path), "--tests", str(self.dir / "tests.json"),
                "--out", str(self.dir / "plan.json"), "--comment", str(self.dir / "comment.md"),
                "--run-url", "https://github.com/o/r/actions/runs/1", "--manifest", str(self.manifest),
                "--now", "2026-09-26T00:00:00+00:00"])
        self.assertEqual(code, 0)
        self.stdout = out.getvalue()
        self.plan_text = (self.dir / "plan.json").read_text()
        self.comment = (self.dir / "comment.md").read_text()
        return json.loads(self.plan_text)

    def assertHuman(self, plan, fragment):
        self.assertEqual(plan["action"], "human")
        self.assertTrue(any(fragment in r for r in plan["reasons"]), plan["reasons"])


class ActionTests(ApplyCase):
    def test_all_accepted_low_risk_green_merges(self):
        a, b = change("sodium"), change("lithium")
        plan = self.run_apply([a, b], verdict([judged(a), judged(b)]))
        self.assertEqual(plan["action"], "merge")
        self.assertEqual(plan["reasons"], [])
        self.assertIn("Auto-merging: 2 updates accepted, all low risk, tests green.", self.comment)
        self.assertFalse(plan["publish_images"])

    def test_docker_merge_publishes_images(self):
        a = change("eclipse-temurin", eco="docker")
        plan = self.run_apply([a], verdict([judged(a)]), source="docker", dockerfiles=["docker/x/Dockerfile"])
        self.assertEqual(plan["action"], "merge")
        self.assertTrue(plan["publish_images"])

    def test_a_quick_failure_blocks(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a)]), tests=dict(GREEN, quick="failure"))
        self.assertHuman(plan, "Quick checks did not pass (failure)")
        self.assertEqual(len(plan["reasons"]), 1)

    def test_smoke_required_only_when_the_gate_asks(self):
        a = change("sodium")
        tests = dict(GREEN, smoke="skipped")
        self.assertEqual(self.run_apply([a], verdict([judged(a)]), tests=tests)["action"], "merge")
        plan = self.run_apply([a], verdict([judged(a)]), tests=tests, needs_smoke=True)
        self.assertHuman(plan, "Smoke test did not pass (skipped)")

    def test_docker_build_required_when_dockerfiles_change(self):
        a = change("python", eco="docker")
        plan = self.run_apply([a], verdict([judged(a)]), source="docker",
                              tests=dict(GREEN, docker_build="failure"), dockerfiles=["docker/x/Dockerfile"])
        self.assertHuman(plan, "Docker build did not pass")

    def test_b_a_pending_or_failed_check_blocks(self):
        a = change("sodium")
        for check in ({"name": "lint", "status": "in_progress", "conclusion": None},
                      {"name": "lint", "status": "completed", "conclusion": "failure"},
                      {"name": "lint", "status": "completed", "conclusion": "cancelled"}):
            plan = self.run_apply([a], verdict([judged(a)]), tests=dict(GREEN, checks=[check]))
            self.assertHuman(plan, "Other checks are not green: lint")
        for conclusion in ("skipped", "neutral"):
            check = {"name": "x", "status": "completed", "conclusion": conclusion}
            self.assertEqual(self.run_apply([a], verdict([judged(a)]), tests=dict(GREEN, checks=[check]))["action"],
                             "merge")

    def test_missing_tests_file_blocks(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a)]), tests=None)
        self.assertHuman(plan, "Quick checks did not pass")
        self.assertHuman(plan, "Check results are unreadable")

    def test_c_a_blocking_flag_blocks_whatever_the_reviewer_says(self):
        a = change("sodium", flags=["major"])
        plan = self.run_apply([a], verdict([judged(a)]))
        self.assertHuman(plan, "Blocking flags: mod:sodium@1.1.0 [major]")

    def test_a_non_blocking_flag_does_not_block(self):
        a = change("sodium", flags=["client-only"])
        self.assertEqual(self.run_apply([a], verdict([judged(a)]))["action"], "merge")

    def test_d_risk_above_the_ceiling_blocks(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a, risk="medium")]))
        self.assertHuman(plan, "Highest risk is medium; auto-merge allows up to low")

    def test_e_worldgen_paths_block(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a)]), worldgen_paths=["config/datapacks/x.json"])
        self.assertHuman(plan, "Touches worldgen paths: config/datapacks/x.json")

    def test_never_automerge_paths_block(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a)]), files=["config/custom-dimensions/foo.json"])
        self.assertHuman(plan, "never auto-merged: config/custom-dimensions/foo.json")

    def test_f_a_hold_on_a_mods_pr_needs_a_human(self):
        a, b = change("sodium"), change("lithium")
        plan = self.run_apply([a, b], verdict([judged(a), judged(b, decision="hold")]))
        self.assertHuman(plan, "Not accepted by the reviewer: mod:lithium@1.1.0")

    def test_f_merge_blockers_list_every_non_accept(self):
        final = {"k": {"decision": "hold", "risk": "low", "flags": []}}
        reasons = apply.merge_blockers({"worldgen_paths": []}, GREEN, common.load_policy(), final)
        self.assertEqual(reasons, ["Not every update was accepted: k."])

    def test_every_failing_reason_is_listed(self):
        a = change("sodium", flags=["prerelease"])
        plan = self.run_apply([a], verdict([judged(a, risk="high")]),
                              tests=dict(GREEN, quick="failure"), worldgen_paths=["x"])
        self.assertEqual(plan["action"], "human")
        self.assertEqual(len(plan["reasons"]), 4, plan["reasons"])
        for reason in plan["reasons"]:
            self.assertIn(apply.md(reason), self.comment)

    def test_all_rejected_dependabot_pr_closes(self):
        a = change("actions/checkout", eco="action", old="v6", new="v7")
        plan = self.run_apply([a], verdict([judged(a, decision="reject", risk="high")]), source="actions")
        self.assertEqual(plan["action"], "close")
        self.assertIn("Closing:", self.comment)

    def test_mixed_reject_on_dependabot_needs_a_human(self):
        a, b = change("requests", eco="pip"), change("urllib3", eco="pip")
        plan = self.run_apply([a, b], verdict([judged(a, decision="reject"), judged(b)]), source="pip")
        self.assertHuman(plan, "Not accepted by the reviewer: pip:requests@1.1.0")

    def test_all_rejected_mods_pr_needs_a_human_not_a_close(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a, decision="reject")]))
        self.assertEqual(plan["action"], "human")

    def test_nothing_to_review_needs_a_human(self):
        plan = self.run_apply([], verdict([]))
        self.assertHuman(plan, "There is nothing to review.")

    def test_gate_refusal_and_sha_mismatch_need_a_human(self):
        a = change("sodium")
        self.assertHuman(self.run_apply([a], verdict([judged(a)]), ok=False), "gate did not admit")
        self.assertHuman(self.run_apply([a], verdict([judged(a)]), head_sha="c" * 40), "different commit")

    def test_unreadable_gate_exits_non_zero(self):
        (self.dir / "gate.json").write_text("{")
        (self.dir / "context.json").write_text("{}")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = apply.main(["--gate", str(self.dir / "gate.json"), "--context", str(self.dir / "context.json"),
                               "--verdict", "x", "--tests", "x", "--out", str(self.dir / "p"),
                               "--comment", str(self.dir / "c"), "--run-url", "u"])
        self.assertEqual(code, 2)


class VerdictValidationTests(ApplyCase):
    def test_missing_empty_and_garbage_verdicts_fail_closed(self):
        a = change("sodium")
        cases = {None: "missing", "": "empty", "   \n": "empty", "{not json": "not valid JSON",
                 "[]": "not a JSON object", '{"summary": 1, "changes": [], "holds_add": [], "holds_remove": []}':
                 "does not match", '{"summary": "x", "changes": []}': "does not match"}
        for text, fragment in cases.items():
            if text is None:
                plan = self.run_apply([a])
            else:
                plan = self.run_apply([a], verdict_text=text)
            self.assertFalse(plan["verdict_ok"], text)
            self.assertHuman(plan, fragment)
            self.assertEqual(plan["state"]["verdicts"], {}, "placeholders are never cached")

    def test_an_unjudged_key_is_held_at_high_risk(self):
        a, b = change("sodium"), change("lithium")
        plan = self.run_apply([a, b], verdict([judged(a)]))
        self.assertHuman(plan, "mod:lithium@1.1.0")
        self.assertIn("Not judged by the reviewer.", self.comment)
        self.assertNotIn(b["key"], plan["state"]["verdicts"])
        self.assertIn(a["key"], plan["state"]["verdicts"])

    def test_duplicate_and_unknown_keys_are_ignored_and_noted(self):
        a = change("sodium")
        stray = {"key": "mod:evil@9", "decision": "accept", "risk": "low", "reason": "x", "evidence": []}
        plan = self.run_apply([a], verdict([judged(a), judged(a, decision="reject"), stray]))
        self.assertEqual(plan["action"], "human")
        self.assertTrue(any("conflicting verdicts for mod:sodium@1.1.0" in n for n in plan["notes"]))
        self.assertTrue(any("mod:evil@9" in n for n in plan["notes"]))
        self.assertEqual(set(plan["state"]["verdicts"]), set())

    def test_invalid_decision_or_risk_becomes_a_hold(self):
        a = change("sodium")
        for bad in (dict(decision="merge"), dict(risk="none"), dict(evidence="x"), dict(reason=None)):
            item = dict(judged(a), **bad)
            plan = self.run_apply([a], verdict([item]))
            self.assertEqual(plan["action"], "human", bad)
            self.assertIn("verdict was invalid", self.comment)

    def test_truncation_limits(self):
        a = change("sodium")
        item = judged(a, reason="r" * 2000, evidence=["e" * 1000] * 9)
        plan = self.run_apply([a], verdict([item], summary="s" * 5000))
        self.assertEqual(plan["action"], "merge")
        reason = plan["state"]["verdicts"][a["key"]]["reason"]
        self.assertLessEqual(len(reason), 200)
        raw = json.loads((self.dir / "verdict.json").read_text())
        v = apply.validate_verdict(raw, {a["key"]})
        self.assertEqual(len(v["summary"]), apply.SUMMARY_MAX)
        self.assertTrue(v["summary"].endswith("…"))
        entry = v["judged"][a["key"]]
        self.assertEqual(len(entry["reason"]), apply.REASON_MAX)
        self.assertEqual(len(entry["evidence"]), apply.EVIDENCE_ITEMS)
        self.assertTrue(all(len(e) == apply.EVIDENCE_MAX for e in entry["evidence"]))
        self.assertTrue(any("first 5 evidence items" in n for n in plan["notes"]))


class SecretGuardTests(ApplyCase):
    def test_every_credential_prefix_discards_the_verdict(self):
        a = change("sodium")
        for prefix in apply.SECRET_MARKERS:
            secret = prefix + "Zq7Xw9Secretvalue"
            for placement in ("summary", "reason", "evidence", "hold", "key"):
                item = judged(a)
                v = verdict([item])
                if placement == "summary":
                    v["summary"] = f"see {secret}"
                elif placement == "reason":
                    item["reason"] = secret
                elif placement == "evidence":
                    item["evidence"] = ["ok", f"token={secret}"]
                elif placement == "hold":
                    v["holds_add"] = [{"slug": "sodium", "reason": secret}]
                else:
                    v["extra"] = {secret: 1}
                plan = self.run_apply([a], v)
                self.assertFalse(plan["verdict_ok"])
                self.assertEqual(plan["reasons"], [apply.WITHHELD])
                for text in (self.plan_text, self.comment, self.stdout):
                    self.assertNotIn("Zq7Xw9", text, (prefix, placement))

    def test_an_escaped_secret_is_caught_after_decoding(self):
        a = change("sodium")
        text = json.dumps(verdict([judged(a, reason="x")])).replace('"x"', '"\\u0067hp_Zq7Xw9"')
        plan = self.run_apply([a], verdict_text=text)
        self.assertEqual(plan["reasons"], [apply.WITHHELD])
        self.assertNotIn("Zq7Xw9", self.comment + self.plan_text)


class SanitiserTests(ApplyCase):
    def test_a_forged_state_marker_cannot_appear(self):
        a = change("sodium")
        forged = "--> <!-- dep-review:state eyJ2IjoxfQ== --> <script>x</script>"
        plan = self.run_apply([a], verdict([judged(a, reason=forged, evidence=[forged])], summary=forged))
        self.assertEqual(self.comment.count(common.STATE_PREFIX), 1)
        self.assertTrue(self.comment.startswith(common.STATE_PREFIX))
        self.assertNotIn("<script>", self.comment)
        self.assertNotIn("-->", self.comment.split("\n", 1)[1])
        self.assertEqual(common.decode_state(self.comment), plan["state"])

    def test_mentions_pipes_newlines_links_and_controls(self):
        self.assertEqual(apply.md("@octocat"), "@​octocat")
        self.assertEqual(apply.md("a|b"), "a\\|b")
        self.assertEqual(apply.md("a\nb\r\nc"), "a b c")
        self.assertEqual(apply.md("[x](http://e)"), "\\[x\\](http://e)")
        self.assertEqual(apply.md("a\x00\x1b[31mb‮"), "a\\[31mb")
        self.assertEqual(apply.md("\\|"), "\\\\\\|")
        self.assertEqual(apply.md("`x`"), "\\`x\\`")

    def test_table_rows_stay_one_line_with_seven_cells(self):
        a = change("so|dium\n@team", old="1|0", new="2\n3")
        self.run_apply([a], verdict([judged(a, reason="line one\nline | two @here")]))
        rows = [line for line in self.comment.splitlines() if line.startswith("| ") and "---" not in line]
        self.assertEqual(len(rows), 2)
        for row in rows:
            self.assertEqual(row.replace("\\|", "").count("|"), 8, row)
        self.assertNotIn("@here", self.comment)
        self.assertIn("@​here", self.comment)

    def test_evidence_is_code_only_without_backticks(self):
        self.assertEqual(apply.md_code_or_text("fixes #12"), "`fixes #12`")
        self.assertEqual(apply.md_code_or_text("a `b` <c>"), "a \\`b\\` &lt;c&gt;")

    def test_run_url_must_be_https(self):
        a = change("sodium")
        self.run_apply([a], verdict([judged(a)]))
        self.assertIn("<sub>Reviewed by [dep-review](https://github.com/o/r/actions/runs/1). "
                      "Deterministic policy: .github/dep-review/policy.json.</sub>", self.comment)


class CachedVerdictTests(ApplyCase):
    def previous(self, key, flags, decision="accept"):
        return {"v": 1, "fingerprint": "fp0", "head_sha": "c" * 40,
                "verdicts": {key: {"decision": decision, "risk": "low", "reason": "fine", "flags": flags}}}

    def test_cached_accept_plus_new_accept_merges(self):
        a = change("sodium")
        key = "mod:lithium@2.0.0"
        plan = self.run_apply([a], verdict([judged(a)]),
                              cached=[{"key": key, "decision": "accept", "risk": "low", "reason": "fine"}],
                              previous_state=self.previous(key, []))
        self.assertEqual(plan["action"], "merge")
        self.assertEqual(set(plan["state"]["verdicts"]), {a["key"], key})
        self.assertIn("lithium (earlier review)", self.comment)

    def test_cached_blocking_flags_block(self):
        a = change("sodium")
        key = "mod:lithium@2.0.0"
        plan = self.run_apply([a], verdict([judged(a)]),
                              cached=[{"key": key, "decision": "accept", "risk": "low", "reason": "fine"}],
                              previous_state=self.previous(key, ["too-new"]))
        self.assertHuman(plan, "Blocking flags: mod:lithium@2.0.0 [too-new]")
        self.assertEqual(plan["state"]["verdicts"][key]["flags"], ["too-new"])

    def test_cached_without_recorded_flags_blocks(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([judged(a)]),
                              cached=[{"key": "mod:lithium@2.0.0", "decision": "accept", "risk": "low", "reason": "x"}])
        self.assertHuman(plan, "Flags unknown for earlier-reviewed updates")

    def test_cached_hold_needs_a_human(self):
        key = "mod:lithium@2.0.0"
        plan = self.run_apply([], verdict([]),
                              cached=[{"key": key, "decision": "hold", "risk": "low", "reason": "x"}],
                              previous_state=self.previous(key, [], decision="hold"))
        self.assertHuman(plan, key)

    def test_reviewer_cannot_rejudge_a_cached_key(self):
        key = "mod:lithium@2.0.0"
        rejudge = {"key": key, "decision": "accept", "risk": "low", "reason": "x", "evidence": []}
        plan = self.run_apply([], verdict([rejudge]),
                              cached=[{"key": key, "decision": "reject", "risk": "high", "reason": "x"}],
                              previous_state=self.previous(key, [], decision="reject"))
        self.assertEqual(plan["state"]["verdicts"][key]["decision"], "reject")
        self.assertTrue(any("not under review" in n for n in plan["notes"]))


class HoldTests(ApplyCase):
    def test_holding_a_mod_back_edits_only_that_entry(self):
        before = self.manifest.read_text(encoding="utf-8")
        a, b = change("sodium"), change("lithium")
        plan = self.run_apply([a, b], verdict([judged(a), judged(b, decision="hold", risk="medium")],
                                              holds_add=[{"slug": "lithium", "reason": "Breaks <x> @dev\nworldgen"}]))
        self.assertEqual(plan["action"], "rehold")
        self.assertTrue(plan["holds_changed"])
        self.assertIn("Holding 1 mod back and regenerating this PR.", self.comment)
        after = self.manifest.read_text(encoding="utf-8")
        data = json.loads(after)
        self.assertEqual(list(data["_holds"])[-1], "lithium")
        self.assertEqual(data["_holds"]["lithium"], "Breaks x @dev worldgen (dep-review: PR #42)")
        del data["_holds"]["lithium"]
        self.assertEqual(json.dumps(data, indent=2, ensure_ascii=False) + "\n", before)
        self.assertEqual(list(data), list(json.loads(before)))

    def test_releasing_a_hold(self):
        a = change("sodium")
        held = next(iter(json.loads(self.manifest.read_text())["_holds"]))
        plan = self.run_apply([a], verdict([judged(a)], holds_remove=[{"slug": held, "reason": "Blocker cleared."}]))
        self.assertEqual(plan["action"], "rehold")
        self.assertNotIn(held, json.loads(self.manifest.read_text())["_holds"])
        self.assertIn("Releasing 1 hold and regenerating this PR.", self.comment)
        self.assertEqual(plan["labels_add"], ["dep-review:reviewed", "dep-review:attention"])

    def test_holds_ignored_for_dependabot_sources(self):
        before = self.manifest.read_bytes()
        a = change("requests", eco="pip")
        held = next(iter(json.loads(before)["_holds"]))
        plan = self.run_apply([a], verdict([judged(a)], holds_remove=[{"slug": held, "reason": "x"}]), source="pip")
        self.assertEqual(plan["action"], "merge")
        self.assertEqual(self.manifest.read_bytes(), before)
        self.assertTrue(any("only to mod PRs" in n for n in plan["notes"]))

    def test_invalid_slugs_and_unheld_keys_are_rejected(self):
        before = self.manifest.read_bytes()
        a, b = change("sodium"), change("lithium")
        adds = [{"slug": "../etc", "reason": "x"}, {"slug": "Sodium", "reason": "x"},
                {"slug": "sodium", "reason": "accepted, not held"}, {"slug": "unknown-mod", "reason": "x"},
                {"slug": "x" * 65, "reason": "x"}, {"slug": "lithium\n", "reason": "x"}]
        plan = self.run_apply([a, b], verdict([judged(a), judged(b, decision="hold")], holds_add=adds,
                                              holds_remove=[{"slug": "not-held", "reason": "x"}]))
        self.assertEqual(plan["action"], "human")
        self.assertFalse(plan["holds_changed"])
        self.assertEqual(self.manifest.read_bytes(), before)
        self.assertEqual(sum("invalid slug" in n for n in plan["notes"]), 4)

    def test_an_unjudged_key_cannot_be_held(self):
        a = change("sodium")
        plan = self.run_apply([a], verdict([], holds_add=[{"slug": "sodium", "reason": "x"}]))
        self.assertEqual(plan["action"], "human")
        self.assertFalse(plan["holds_changed"])

    def test_already_held_slug_is_not_added_again(self):
        held = next(iter(json.loads(self.manifest.read_text())["_holds"]))
        a = change(held)
        plan = self.run_apply([a], verdict([judged(a, decision="hold")], holds_add=[{"slug": held, "reason": "x"}]))
        self.assertEqual(plan["action"], "human")
        self.assertTrue(any("already held" in n for n in plan["notes"]))


class OutputShapeTests(ApplyCase):
    def test_label_sets(self):
        a = change("sodium")
        expected = {"merge": ("safe", judged(a)), "human": ("attention", judged(a, decision="hold")),
                    "close": ("rejected", judged(a, decision="reject"))}
        for action, (label, item) in expected.items():
            plan = self.run_apply([a], verdict([item]), source="pip" if action == "close" else "mods")
            self.assertEqual(plan["action"], action)
            self.assertEqual(plan["labels_add"], ["dep-review:reviewed", f"dep-review:{label}"])
            self.assertEqual(sorted(plan["labels_add"][1:] + plan["labels_remove"]), sorted(apply.ALL_OUTCOME_LABELS))

    def test_state_is_the_first_line_and_round_trips(self):
        a = change("sodium", flags=["client-only"])
        plan = self.run_apply([a], verdict([judged(a)]))
        self.assertEqual(self.comment.splitlines()[0], common.encode_state(plan["state"]))
        self.assertEqual(plan["state"]["fingerprint"], "fp1")
        self.assertEqual(plan["state"]["head_sha"], HEAD)
        self.assertEqual(plan["state"]["verdicts"][a["key"]],
                         {"decision": "accept", "risk": "low", "reason": "Changelog is bug fixes only.",
                          "flags": ["client-only"], "release_impact": "patch"})
        self.assertIn("### Dependency review", self.comment)
        self.assertIn("> All routine.", self.comment)

    def test_comment_is_capped_and_keeps_non_accept_rows(self):
        work = [change(f"mod-{i:03d}") for i in range(400)]
        items = [judged(c, reason="<>&@|" * 120, evidence=["`x`" * 100] * 5) for c in work]
        items[7] = judged(work[7], decision="hold", reason="needs a look")
        plan = self.run_apply(work, verdict(items, summary="<" * 1500))
        self.assertEqual(plan["action"], "human")
        self.assertLessEqual(len(self.comment), apply.COMMENT_MAX)
        self.assertIn("mod-007", self.comment)
        self.assertIn("further updates accepted; rows omitted for length.", self.comment)
        self.assertEqual(common.decode_state(self.comment), plan["state"])
        self.assertIn("</sub>", self.comment)

    def test_too_many_non_accept_rows_are_trimmed(self):
        work = [change(f"mod-{i:03d}") for i in range(300)]
        items = [judged(c, decision="hold", reason="<" * 600) for c in work]
        plan = self.run_apply(work, verdict(items))
        self.assertLessEqual(len(self.comment), apply.COMMENT_MAX)
        self.assertIn("further rows omitted for length.", self.comment)
        self.assertEqual(common.decode_state(self.comment), plan["state"])
        self.assertEqual(len(plan["state"]["verdicts"]), 300)
        self.assertTrue(self.comment.rstrip().endswith("</sub>"))


class NextMajorTests(ApplyCase):
    """The worldgen PR is readied for a human-cut major, never merged."""

    def test_clean_worldgen_pr_is_ready_not_merged(self):
        a = change("tectonic", flags=["never-automerge"])
        plan = self.run_apply([a], verdict([judged(a)]), source="worldgen",
                              head_ref="mod-updates/next-major",
                              worldgen_paths=["config/datapack-presets/x.json"],
                              files=["config/datapack-presets/x.json"])
        self.assertEqual(plan["action"], "ready")
        self.assertIn("dep-review:ready-for-major", plan["labels_add"])
        self.assertIn("dep-review:safe", plan["labels_remove"])
        self.assertIn("Merge this only when cutting the next major version.", self.comment)

    def test_other_blockers_still_apply(self):
        a = change("tectonic", flags=["never-automerge", "prerelease"])
        plan = self.run_apply([a], verdict([judged(a)]), source="worldgen")
        self.assertHuman(plan, "prerelease")

    def test_a_failed_smoke_test_is_not_ready(self):
        a = change("terralith", flags=["never-automerge"])
        plan = self.run_apply([a], verdict([judged(a)]), source="worldgen", needs_smoke=True,
                              tests={**GREEN, "smoke": "failure"})
        self.assertHuman(plan, "Smoke test")

    def test_holds_apply_to_the_worldgen_pr(self):
        a = change("tectonic", flags=["never-automerge"])
        v = verdict([judged(a, decision="hold", risk="high")],
                    holds_add=[{"slug": "tectonic", "reason": "3.1 changes erosion defaults"}])
        plan = self.run_apply([a], v, source="worldgen")
        self.assertEqual(plan["action"], "rehold")

    def test_worldgen_flag_still_blocks_the_regular_pr(self):
        a = change("tectonic", flags=["never-automerge"])
        plan = self.run_apply([a], verdict([judged(a)]), source="mods")
        self.assertHuman(plan, "never-automerge")


def rchange(name, eco="mod", old="1.0.0", new="1.1.0", sides=("server",), flags=(), version=None,
            old_version=None):
    c = change(name, eco=eco, old=old, new=new, flags=flags)
    c["key"] = common.change_key(eco, name, new or "removed")
    c["sides"] = list(sides)
    if version:
        c["details"] = {"version_number": {"old": old_version, "new": version}}
    return c


def impact(ch, value, **kw):
    item = judged(ch, **kw)
    item["release_impact"] = value
    return item


def floor(c, source="mods"):
    return apply.release_floor({"key": c["key"], "ecosystem": c["ecosystem"], "flags": c["flags"],
                                "sides": c.get("sides"), "new": c["new"]}, source)[0]


class ReleaseFloorTests(unittest.TestCase):
    def test_actions_are_none_whatever_their_flags(self):
        self.assertEqual(floor(rchange("actions/checkout", eco="action", sides=(), flags=["major"]),
                               "actions"), "none")
        self.assertEqual(floor(rchange("actions/x", eco="action", new=None, sides=(), flags=["unparsed"]),
                               "actions"), "none")

    def test_server_only_mod_and_plain_bumps_are_patch(self):
        self.assertEqual(floor(rchange("lithium")), "patch")
        self.assertEqual(floor(rchange("python", eco="docker", sides=()), "docker"), "patch")
        self.assertEqual(floor(rchange("requests", eco="pip", sides=()), "pip"), "patch")
        self.assertEqual(floor(rchange("docker/x/Dockerfile", eco="docker", new=None, sides=(),
                                       flags=["unparsed"]), "docker"), "patch")

    def test_client_side_and_packs_are_minor(self):
        self.assertEqual(floor(rchange("sodium", sides=("server", "client"))), "minor")
        self.assertEqual(floor(rchange("sodium", sides=("client",))), "minor")
        self.assertEqual(floor(rchange("faithful", eco="pack", sides=("client",))), "minor")
        c = rchange("lithium")
        c["sides"] = None
        self.assertEqual(floor(c), "minor", "unknown sides count as client-side")

    def test_new_mod_and_dependency_major_are_minor(self):
        self.assertEqual(floor(rchange("lithium", old=None, flags=["added", "unparsed"])), "minor")
        self.assertEqual(floor(rchange("python", eco="docker", sides=(), flags=["major"]), "docker"), "minor")
        self.assertEqual(floor(rchange("requests", eco="pip", sides=(), flags=["major"]), "pip"), "minor")

    def test_worldgen_and_removals_are_major(self):
        self.assertEqual(floor(rchange("lithium"), "worldgen"), "major")
        self.assertEqual(floor(rchange("tectonic", flags=["never-automerge"])), "major")
        self.assertEqual(floor(rchange("lithium", new=None, flags=["removed", "unparsed"])), "major")
        self.assertEqual(floor(rchange("lithium", new=None)), "major")
        self.assertEqual(floor(rchange("faithful", eco="pack", flags=["removed"])), "major")

    def test_floor_reports_why(self):
        self.assertEqual(apply.release_floor({"key": "mod:x@removed", "ecosystem": "mod", "flags": ["removed"],
                                              "sides": ["server"], "new": None}, "worldgen"),
                         ("major", ["worldgen", "removed"]))


class ReleaseImpactTests(ApplyCase):
    def previous(self, key, **record):
        base = {"decision": "accept", "risk": "low", "reason": "fine", "flags": []}
        return {"v": 1, "fingerprint": "fp0", "head_sha": "c" * 40, "verdicts": {key: {**base, **record}}}

    def test_reviewer_raises_above_the_floor(self):
        a = rchange("lithium")
        plan = self.run_apply([a], verdict([impact(a, "major")], summary="x") | {"consumer_note": "Re-run X."})
        self.assertEqual(plan["action"], "merge", "impact never changes the action")
        self.assertEqual(plan["release_impact"], "major")
        self.assertEqual(plan["state"]["verdicts"][a["key"]]["release_impact"], "major")
        self.assertIn("| major (reviewer) |", self.comment)

    def test_reviewer_cannot_lower_below_the_floor(self):
        a = rchange("tectonic", flags=["never-automerge"])
        plan = self.run_apply([a], verdict([impact(a, "patch")]), source="worldgen")
        self.assertEqual(plan["action"], "ready")
        self.assertEqual(plan["release_impact"], "major")
        b = rchange("sodium", sides=("client",))
        self.assertEqual(self.run_apply([b], verdict([impact(b, "none")]))["release_impact"], "minor")

    def test_missing_or_invalid_impact_uses_the_floor(self):
        a = rchange("sodium", sides=("server", "client"))
        for value in (None, "huge", 3, ["major"]):
            item = judged(a) if value is None else impact(a, value)
            plan = self.run_apply([a], verdict([item]))
            self.assertEqual(plan["action"], "merge", value)
            self.assertEqual(plan["release_impact"], "minor", value)
            self.assertTrue(any("Used the release floor" in n for n in plan["notes"]), value)

    def test_placeholders_use_the_floor(self):
        a = rchange("sodium", sides=("client",))
        plan = self.run_apply([a], verdict_text="{")
        self.assertEqual((plan["action"], plan["release_impact"]), ("human", "minor"))

    def test_cached_keys_keep_their_stored_impact(self):
        key = "mod:lithium@2.0.0"
        cached = [{"key": key, "decision": "accept", "risk": "low", "reason": "fine", "flags": []}]
        plan = self.run_apply([], verdict([]), cached=cached,
                              previous_state=self.previous(key, release_impact="major"))
        self.assertEqual(plan["release_impact"], "major")
        self.assertEqual(plan["state"]["verdicts"][key]["release_impact"], "major")
        plan = self.run_apply([], verdict([]), cached=cached,
                              previous_state=self.previous(key, release_impact="patch"))
        self.assertEqual(plan["release_impact"], "patch")

    def test_cached_keys_without_an_impact_use_the_floor(self):
        key = "mod:lithium@2.0.0"
        cached = [{"key": key, "decision": "accept", "risk": "low", "reason": "fine", "flags": []}]
        plan = self.run_apply([], verdict([]), cached=cached, previous_state=self.previous(key))
        self.assertEqual(plan["release_impact"], "minor", "a cached mod's sides are unknown")
        plan = self.run_apply([], verdict([]), cached=cached,
                              previous_state=self.previous(key, release_impact="bogus"))
        self.assertEqual(plan["release_impact"], "minor")
        akey = "action:actions/checkout@v7"
        plan = self.run_apply([], verdict([]), source="actions",
                              cached=[{"key": akey, "decision": "accept", "risk": "low", "reason": "x", "flags": []}],
                              previous_state=self.previous(akey))
        self.assertEqual(plan["release_impact"], "none")

    def test_a_cached_impact_is_raised_to_the_current_floor(self):
        key = "mod:tectonic@2.0.0"
        cached = [{"key": key, "decision": "accept", "risk": "low", "reason": "x", "flags": ["never-automerge"]}]
        plan = self.run_apply([], verdict([]), cached=cached,
                              previous_state=self.previous(key, release_impact="patch"))
        self.assertEqual(plan["release_impact"], "major")

    def test_pr_impact_is_the_highest_change(self):
        a, b, c = rchange("lithium"), rchange("sodium", sides=("client",)), rchange("ferritecore")
        plan = self.run_apply([a, b, c], verdict([judged(a), judged(b), judged(c)]))
        self.assertEqual(plan["release_impact"], "minor")
        self.assertEqual(plan["commit_title"], "feat(deps): update 3 mods")

    def test_close_and_human_still_carry_impact_and_title(self):
        a = rchange("requests", eco="pip", sides=(), old="2.32.0", new="2.33.0")
        plan = self.run_apply([a], verdict([judged(a, decision="reject", risk="high")]), source="pip")
        self.assertEqual(plan["action"], "close")
        self.assertEqual(plan["commit_title"], "fix(deps): bump requests to 2.33.0")
        b = rchange("sodium", sides=("client",))
        plan = self.run_apply([b], verdict([judged(b, decision="hold")]))
        self.assertEqual((plan["action"], plan["release_impact"]), ("human", "minor"))
        self.assertTrue(plan["commit_title"].startswith("feat(deps): "))

    def test_comment_shows_impact_and_title_under_the_outcome(self):
        a = rchange("lithium")
        self.run_apply([a], verdict([judged(a)]))
        lines = self.comment.splitlines()
        i = next(n for n, line in enumerate(lines) if line.startswith("Auto-merging:"))
        self.assertEqual(lines[i + 1], "Release impact: **patch**. Commit title: `fix(deps): update lithium`")
        self.assertIn("| Dependency | Change (old → new) | Decision | Risk | Release | Flags | Reason |", lines)
        self.assertIn("| patch |", self.comment)


class CommitMessageTests(ApplyCase):
    def titled(self, work, source="mods", **kw):
        return self.run_apply(work, verdict([judged(c) for c in work]), source=source, **kw)

    def test_titles_for_each_impact_and_source(self):
        cases = [
            ([rchange("actions/setup-java", eco="action", old="v5", new="v6", sides=())], "actions",
             "ci(deps): bump actions/setup-java to v6"),
            ([rchange(f"actions/a{i}", eco="action", sides=()) for i in range(3)], "actions",
             "ci(deps): bump 3 actions"),
            ([rchange("python", eco="docker", old="3.14.7-alpine3.24", new="3.14.8-alpine3.24", sides=())],
             "docker", "fix(deps): bump python to 3.14.8-alpine3.24"),
            ([rchange("python", eco="docker", old="3.13", new="4.0@sha256:" + "f" * 64, sides=(),
                      flags=["major"])], "docker", "feat(deps): bump python to 4.0"),
            ([rchange("requests", eco="pip", sides=()), rchange("urllib3", eco="pip", sides=())], "pip",
             "fix(deps): bump 2 python packages"),
            ([rchange("actions/checkout", eco="action", new="0123456789abcdef0123456789abcdef01234567",
                      sides=())], "actions", "ci(deps): bump actions/checkout to 0123456"),
            ([rchange("lithium", version="mc1.21.1-0.15.1")], "mods", "fix(deps): update lithium to mc1.21.1-0.15.1"),
            ([rchange(f"m{i}") for i in range(28)], "mods", "fix(deps): update 28 mods"),
            ([rchange("lithium", old=None, flags=["added"])], "mods", "feat(deps): add lithium"),
            ([rchange("faithful", eco="pack", sides=("client",)), rchange("sodium", sides=("client",))], "mods",
             "feat(deps): update 2 mods and packs"),
            ([rchange(f"w{i}", flags=["never-automerge"]) for i in range(12)], "worldgen",
             "feat(deps)!: update 12 worldgen mods for the next major"),
            ([rchange("a"), rchange("b"), rchange("c", old=None, flags=["added"]),
              rchange("d", new=None, flags=["removed"])], "mods", "feat(deps)!: update 2 mods, add 1, remove 1"),
        ]
        for work, source, title in cases:
            plan = self.titled(work, source)
            self.assertEqual(plan["commit_title"], title)

    def test_titles_never_exceed_72_characters(self):
        long_name = "ghcr.io/" + "x" * 80
        plan = self.titled([rchange(long_name, eco="docker", new="1." + "9" * 70, sides=())], "docker")
        self.assertEqual(plan["commit_title"], "fix(deps): bump 1 docker image")
        plan = self.titled([rchange("lithium", version="v" * 70)])
        self.assertEqual(plan["commit_title"], "fix(deps): update lithium")
        for plan in (plan, self.titled([rchange("y" * 64, new=None, flags=["removed"])], "worldgen")):
            self.assertLessEqual(len(plan["commit_title"]), apply.TITLE_MAX)
            self.assertRegex(plan["commit_title"], r"^[a-z]+\(deps\)!?: [a-z]")

    def test_breaking_change_footer_only_for_major(self):
        a = rchange("tectonic", flags=["never-automerge"])
        v = verdict([judged(a)]) | {"consumer_note": "Pre-generate spawn again."}
        plan = self.run_apply([a], v, source="worldgen")
        self.assertEqual(plan["commit_body"].split("\n\n")[-1], "BREAKING CHANGE: Pre-generate spawn again.")
        self.assertEqual(plan["commit_body"].count("BREAKING CHANGE: "), 1)
        for work, source in (([rchange("lithium")], "mods"), ([rchange("sodium", sides=("client",))], "mods"),
                             ([rchange("actions/x", eco="action", sides=())], "actions")):
            plan = self.run_apply(work, verdict([judged(c) for c in work]) | {"consumer_note": "Nothing."},
                                  source=source)
            self.assertNotIn("BREAKING", plan["commit_body"])
            self.assertIn("Consumers: Nothing.", plan["commit_body"])

    def test_fallback_note_when_major_has_no_consumer_note(self):
        a, b = rchange("tectonic", flags=["never-automerge"]), rchange("terralith", flags=["never-automerge"])
        plan = self.run_apply([a, b], verdict([judged(a), judged(b)]), source="worldgen")
        self.assertEqual(plan["consumer_note"], "Worldgen updates: tectonic, terralith. New chunks generate "
                                                "differently; existing chunks are unchanged.")
        self.assertTrue(plan["commit_body"].endswith("\n\nBREAKING CHANGE: " + plan["consumer_note"]))
        c = rchange("lithium", new=None, flags=["removed"])
        plan = self.run_apply([c], verdict([judged(c)]))
        self.assertEqual(plan["consumer_note"], "Mods removed: lithium.")
        d = rchange("fabric-loader", eco="docker", sides=())
        plan = self.run_apply([d], verdict([impact(d, "major")]), source="docker")
        self.assertEqual(plan["consumer_note"], "Rated a breaking change by dep-review: fabric-loader.")
        self.assertIn("Consumer note: Rated a breaking change", self.comment)

    def test_body_lists_changes_and_truncates_with_a_count(self):
        a = rchange("lithium", old="AAAA", new="BBBB", version="0.15.1")
        plan = self.titled([a])
        self.assertEqual(plan["commit_body"], "- lithium: AAAA -> 0.15.1 (patch)")
        b = rchange("lithium", old="AAAA", new="BBBB", version="0.15.1", old_version="0.15.0")
        self.assertEqual(self.titled([b])["commit_body"], "- lithium: 0.15.0 -> 0.15.1 (patch)")
        work = [rchange(f"m{i:02d}") for i in range(60)]
        body = self.titled(work)["commit_body"].split("\n")
        self.assertEqual(len(body), apply.BODY_LINES)
        self.assertEqual(body[-1], "- and 11 more")

    def test_consumer_note_is_sanitised_and_capped(self):
        a = rchange("lithium")
        note = "<b>Ping @team</b>\n[x](http://e) " + "z" * 2000
        plan = self.run_apply([a], verdict([impact(a, "major")]) | {"consumer_note": note})
        self.assertEqual(len(plan["consumer_note"]), apply.CONSUMER_NOTE_MAX)
        self.assertNotIn("<", plan["consumer_note"])
        self.assertNotIn("\n", plan["consumer_note"])
        self.assertNotIn("@team", plan["consumer_note"] + plan["commit_body"] + self.comment)
        self.assertNotIn("<b>", self.comment)
        self.assertIn("\\[x\\]", self.comment)
        plan = self.run_apply([a], verdict([judged(a)]) | {"consumer_note": 5})
        self.assertEqual(plan["consumer_note"], "")
        self.assertTrue(any("consumer_note" in n for n in plan["notes"]))

    def test_a_ready_plan_stores_the_commit_in_the_state(self):
        a = rchange("tectonic", flags=["never-automerge"])
        plan = self.run_apply([a], verdict([judged(a)]), source="worldgen")
        self.assertEqual(plan["action"], "ready")
        commit = common.decode_state(self.comment)["commit"]
        self.assertTrue(commit["title"].startswith("feat(deps)!:"))
        self.assertIn("BREAKING CHANGE: ", commit["body"])
        self.assertEqual(commit, {"title": plan["commit_title"], "body": plan["commit_body"],
                                  "release_impact": "major"})

    def test_a_long_stored_body_keeps_its_footer_last(self):
        body = "\n".join(f"- {'m' * 60}{i}: 1 -> 2 (major)" for i in range(50))
        release = {"commit_title": "t", "release_impact": "major",
                   "commit_body": body + "\n\nBREAKING CHANGE: " + "n" * 790}
        stored = apply.stored_commit(release)["body"]
        self.assertLessEqual(len(stored), apply.STATE_BODY_MAX)
        self.assertTrue(stored.endswith("\n\nBREAKING CHANGE: " + "n" * 790))


@unittest.skipUnless(shutil.which("check-jsonschema"), "check-jsonschema is not installed")
class SchemaTests(unittest.TestCase):
    SCHEMA = common.REPO_ROOT / ".github" / "dep-review" / "verdict.schema.json"

    def check(self, *args):
        import subprocess
        return subprocess.run(["check-jsonschema", *args], capture_output=True, text=True)

    def test_schema_is_valid_and_accepts_a_sample(self):
        self.assertEqual(self.check("--check-metaschema", str(self.SCHEMA)).returncode, 0)
        with tempfile.TemporaryDirectory() as tmp:
            good = verdict([impact(change("lithium"), "patch")]) | {"consumer_note": ""}
            bad = verdict([judged(change("lithium"))]) | {"consumer_note": ""}
            worse = verdict([impact(change("lithium"), "huge")])
            for name, obj, code in (("good", good, 0), ("bad", bad, 1), ("worse", worse, 1)):
                path = Path(tmp) / f"{name}.json"
                path.write_text(json.dumps(obj))
                result = self.check("--schemafile", str(self.SCHEMA), str(path))
                self.assertEqual(result.returncode, code, name + result.stdout + result.stderr)

    def test_schema_uses_only_decoder_safe_keywords(self):
        allowed = {"$schema", "title", "type", "additionalProperties", "required", "properties", "items", "enum"}
        stack = [json.loads(self.SCHEMA.read_text())]
        while stack:
            node = stack.pop()
            self.assertLessEqual(set(node) - {"properties"}, allowed)
            stack += [v for k, v in node.items() if k in ("items", "additionalProperties") and isinstance(v, dict)]
            stack += list(node.get("properties", {}).values())


if __name__ == "__main__":
    unittest.main()
