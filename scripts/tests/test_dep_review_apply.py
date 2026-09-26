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

    def test_table_rows_stay_one_line_with_six_cells(self):
        a = change("so|dium\n@team", old="1|0", new="2\n3")
        self.run_apply([a], verdict([judged(a, reason="line one\nline | two @here")]))
        rows = [line for line in self.comment.splitlines() if line.startswith("| ") and "---" not in line]
        self.assertEqual(len(rows), 2)
        for row in rows:
            self.assertEqual(row.replace("\\|", "").count("|"), 7, row)
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
                          "flags": ["client-only"]})
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


if __name__ == "__main__":
    unittest.main()
