#!/usr/bin/env python3
"""apply.py - turn the reviewer's verdict into dep-review's one set of actions.

Context: dep-review.yml runs gate -> collect -> reviewer -> apply. This script
is the only step that decides whether a dependency PR is merged, closed, held
back or handed to a human. The reviewer read untrusted upstream changelogs, so
its verdict is untrusted input: it is validated strictly, discarded whole if it
carries credential-like text, and can only make a PR less mergeable than
.github/dep-review/policy.json allows, never more.

Outputs:
  --out      plan.json: action (merge|close|human|rehold), reasons, labels,
             publish_images, holds_changed, and the state stored in the
             sticky comment for the next run.
  --comment  comment.md: the sticky review comment, state marker first line.
  --manifest edited in place only when the action is "rehold".

Usage:
  python3 scripts/dep_review/apply.py --gate gate.json \
      --context review/context.json --verdict review/verdict.json \
      --tests tests.json --out plan.json --comment comment.md \
      --run-url URL [--policy PATH] [--manifest PATH] [--now ISO8601]

  Exit 0 whenever a plan was written, "human" included; 2 when gate.json or
  context.json is unreadable.

Gotchas:
  - Only genuine reviewer judgements go into the stored state. A placeholder
    (unjudged key, invalid entry, withheld verdict) is shown and blocks the
    merge but is not cached, so the next run sends that key to the reviewer
    again.
  - Cached keys carry no flags in context.json; their flags come from the
    stored state, and a cached key with no recorded flags blocks the merge.
  - Text reaching comment.md is escaped so it cannot open HTML, forge the
    state marker, mention anyone, start a link or break a table row.
  - The manifest round-trips through json.dumps(indent=2, ensure_ascii=False)
    plus a trailing newline; key order is preserved and new holds append.
  - Standard library only; runs on the Actions runner's system python3.
"""
import argparse
import json
import re
import sys
import unicodedata
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dep_review import common  # noqa: E402

SUMMARY_MAX = 1500
REASON_MAX = 600
EVIDENCE_ITEMS = 5
EVIDENCE_MAX = 300
HOLD_REASON_MAX = 400
COMMENT_MAX = 60000
REASON_BULLET_MAX = 500
STATE_BUDGET = 30000

SECRET_MARKERS = ("sk-ant-", "ghp_", "ghs_", "gho_", "github_pat_", "-----BEGIN")
WITHHELD = "verdict withheld: contained credential-like text"
SLUG_RE = re.compile(r"[a-z0-9_-]{1,64}")
RUN_URL_RE = re.compile(r"https://[A-Za-z0-9._~:/?#@!$&'*+,;=%-]+")
SOURCES = ("mods", "actions", "docker", "pip")
HOLD_ECOSYSTEMS = ("mod", "pack")
GREEN_CONCLUSIONS = ("success", "skipped", "neutral")

LABEL_REVIEWED = "dep-review:reviewed"
OUTCOME_LABELS = {
    "merge": "dep-review:safe",
    "human": "dep-review:attention",
    "rehold": "dep-review:attention",
    "close": "dep-review:rejected",
}
ALL_OUTCOME_LABELS = ("dep-review:safe", "dep-review:attention", "dep-review:rejected")

UNJUDGED = "Not judged by the reviewer."
_BIDI = set("‎‏‪‫‬‭‮⁦⁧⁨⁩")


# --- text handling ---------------------------------------------------------

def truncate(text, limit):
    return text if len(text) <= limit else text[: limit - 1] + "…"


def plain(text):
    """One line of text: control and bidi characters gone, whitespace collapsed."""
    text = "" if text is None else str(text)
    kept = []
    for ch in text:
        if ch in "\t\n\r\f\v":
            kept.append(" ")
        elif unicodedata.category(ch) == "Cc" or ch in _BIDI:
            continue
        else:
            kept.append(ch)
    return re.sub(r"\s+", " ", "".join(kept)).strip()


def plain_lines(text):
    """Like plain() but keeps line breaks; blank-line runs collapse to one."""
    lines = [plain(line) for line in str(text or "").replace("\r\n", "\n").split("\n")]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def md(text):
    """Escape one line of untrusted text for Markdown, table cells included."""
    text = plain(text)
    text = text.replace("\\", "\\\\").replace("&", "&amp;")
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    for ch in "|[]`":
        text = text.replace(ch, "\\" + ch)
    return text.replace("@", "@​")


def md_code_or_text(text):
    """Inline code where the text cannot escape it or carry markup, else escaped text."""
    text = plain(text)
    if text and not any(ch in text for ch in "`<>&"):
        return f"`{text}`"
    return md(text)


def contains_secret(value):
    """True if any string reachable in a JSON value holds a credential marker."""
    stack = [value]
    while stack:
        item = stack.pop()
        if isinstance(item, str):
            if any(marker in item for marker in SECRET_MARKERS):
                return True
        elif isinstance(item, dict):
            stack.extend(item.keys())
            stack.extend(item.values())
        elif isinstance(item, list):
            stack.extend(item)
    return False


def plural(n, word, suffix="s"):
    return f"{n} {word}{'' if n == 1 else suffix}"


# --- inputs ----------------------------------------------------------------

def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def read_optional_json(path):
    try:
        return read_json(path)
    except (OSError, ValueError, RecursionError):
        return None


def load_verdict(path):
    """Return (raw verdict, None) or (None, reason it is unusable)."""
    try:
        text = Path(path).read_bytes().decode("utf-8", errors="replace")
    except FileNotFoundError:
        return None, "Reviewer verdict is missing."
    except OSError:
        return None, "Reviewer verdict could not be read."
    if contains_secret(text):
        return None, WITHHELD
    if not text.strip():
        return None, "Reviewer verdict is empty."
    try:
        raw = json.loads(text)
    except (ValueError, RecursionError):
        return None, "Reviewer verdict is not valid JSON."
    if contains_secret(raw):
        return None, WITHHELD
    return raw, None


def validate_change(item):
    """Return (entry, None) or (None, problem) for one reviewer judgement."""
    decision, risk = item.get("decision"), item.get("risk")
    reason, evidence = item.get("reason"), item.get("evidence")
    if decision not in common.DECISIONS:
        return None, "decision is not accept, hold or reject"
    if not isinstance(risk, str) or risk not in common.RISK_ORDER:
        return None, "risk is not low, medium or high"
    if not isinstance(reason, str):
        return None, "reason is not text"
    if not isinstance(evidence, list) or not all(isinstance(e, str) for e in evidence):
        return None, "evidence is not a list of text"
    return {
        "decision": decision,
        "risk": risk,
        "reason": truncate(plain(reason), REASON_MAX),
        "evidence": [truncate(plain(e), EVIDENCE_MAX) for e in evidence[:EVIDENCE_ITEMS]],
        "evidence_dropped": max(0, len(evidence) - EVIDENCE_ITEMS),
    }, None


def validate_holds(items, label, notes):
    valid = []
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("slug"), str) \
                or not isinstance(item.get("reason"), str):
            notes.append(f"Ignored a malformed {label} entry.")
            continue
        if not SLUG_RE.fullmatch(item["slug"]):
            notes.append(f"Ignored a {label} entry with an invalid slug: {truncate(plain(item['slug']), 80)}")
            continue
        valid.append({"slug": item["slug"], "reason": item["reason"]})
    return valid


def validate_verdict(raw, work_keys):
    """Check the verdict against the schema and the limits the schema omits."""
    result = {"ok": False, "error": None, "summary": "", "judged": {}, "invalid": {},
              "duplicates": set(), "holds_add": [], "holds_remove": [], "notes": []}
    if not isinstance(raw, dict):
        result["error"] = "Reviewer verdict is not a JSON object."
        return result
    summary, changes = raw.get("summary"), raw.get("changes")
    holds_add, holds_remove = raw.get("holds_add"), raw.get("holds_remove")
    if not isinstance(summary, str) or not all(isinstance(v, list) for v in (changes, holds_add, holds_remove)):
        result["error"] = "Reviewer verdict does not match the schema."
        return result
    notes = result["notes"]
    result["summary"] = truncate(plain_lines(summary), SUMMARY_MAX)
    keyed = []
    for item in changes:
        if not isinstance(item, dict) or not isinstance(item.get("key"), str):
            notes.append("Ignored a verdict entry without a key.")
            continue
        if item["key"] not in work_keys:
            notes.append(f"Ignored a verdict for a key not under review: {truncate(plain(item['key']), 120)}")
            continue
        keyed.append(item)
    counts = Counter(item["key"] for item in keyed)
    for item in keyed:
        key = item["key"]
        if counts[key] > 1:
            if key not in result["duplicates"]:
                notes.append(f"Ignored {counts[key]} conflicting verdicts for {key}.")
            result["duplicates"].add(key)
            continue
        entry, problem = validate_change(item)
        if problem:
            result["invalid"][key] = problem
            notes.append(f"Ignored the verdict for {key}: {problem}.")
            continue
        if entry["evidence_dropped"]:
            notes.append(f"Kept the first {EVIDENCE_ITEMS} evidence items for {key}.")
        result["judged"][key] = entry
    result["holds_add"] = validate_holds(holds_add, "holds_add", notes)
    result["holds_remove"] = validate_holds(holds_remove, "holds_remove", notes)
    result["ok"] = True
    return result


# --- decision --------------------------------------------------------------

def parse_key(key):
    """Split "<ecosystem>:<name>@<version>" for display; unknown parts are blank."""
    eco, _, rest = key.partition(":")
    name, _, version = rest.rpartition("@")
    return (name or rest or key), version


def str_list(value):
    return [plain(v) for v in value if isinstance(v, str)] if isinstance(value, list) else None


def build_final(context, gate, verdict):
    """Merge cached verdicts with this run's judgements, keyed by change key."""
    final = {}
    for change in context.get("work") or []:
        if not isinstance(change, dict) or not isinstance(change.get("key"), str):
            continue
        key = change["key"]
        if key in final:
            continue
        entry = {"key": key, "name": change.get("name"), "old": change.get("old"),
                 "new": change.get("new"), "ecosystem": change.get("ecosystem"),
                 "flags": str_list(change.get("flags")), "origin": "placeholder",
                 "decision": "hold", "risk": "high", "reason": UNJUDGED, "evidence": []}
        if verdict["ok"]:
            if key in verdict["judged"]:
                j = verdict["judged"][key]
                entry.update(decision=j["decision"], risk=j["risk"], reason=j["reason"],
                             evidence=j["evidence"], origin="new")
            elif key in verdict["duplicates"]:
                entry["reason"] = "The reviewer judged this more than once; its verdicts were ignored."
            elif key in verdict["invalid"]:
                entry["reason"] = f"The reviewer's verdict was invalid: {verdict['invalid'][key]}."
        else:
            entry["reason"] = "No usable reviewer verdict."
        final[key] = entry

    previous = gate.get("previous_state") if isinstance(gate.get("previous_state"), dict) else {}
    stored = previous.get("verdicts") if isinstance(previous.get("verdicts"), dict) else {}
    for cached in context.get("cached") or []:
        if not isinstance(cached, dict) or not isinstance(cached.get("key"), str):
            continue
        key = cached["key"]
        if key in final:
            continue
        name, version = parse_key(key)
        flags = str_list(cached.get("flags"))
        if flags is None and isinstance(stored.get(key), dict):
            flags = str_list(stored[key].get("flags"))
        entry = {"key": key, "name": name, "old": None, "new": version, "ecosystem": key.partition(":")[0],
                 "flags": flags, "origin": "cached", "evidence": [],
                 "decision": cached.get("decision"), "risk": cached.get("risk"),
                 "reason": truncate(plain(cached.get("reason")), REASON_MAX)}
        if entry["decision"] not in common.DECISIONS or not isinstance(entry["risk"], str) \
                or entry["risk"] not in common.RISK_ORDER:
            entry.update(decision="hold", risk="high", origin="placeholder",
                         reason="The earlier verdict for this update is unreadable.")
        final[key] = entry
    return final


def integrity_problems(gate, context, policy, final):
    problems = []
    if gate.get("ok") is not True or gate.get("skip"):
        problems.append("The gate did not admit this PR.")
    if gate.get("source") not in SOURCES:
        problems.append(f"Unknown PR source: {truncate(plain(gate.get('source')), 40)}.")
    pr = context.get("pr") if isinstance(context.get("pr"), dict) else {}
    if not pr:
        problems.append("The evidence bundle is missing or incomplete.")
    elif not gate.get("head_sha") or pr.get("head_sha") != gate.get("head_sha") \
            or pr.get("source", gate.get("source")) != gate.get("source"):
        problems.append("The evidence was collected for a different commit than the gate checked.")
    if not isinstance(gate.get("pr"), int) or isinstance(gate.get("pr"), bool):
        problems.append("The gate did not record a PR number.")
    if policy.get("automerge_max_risk") not in common.RISK_ORDER \
            or not isinstance(policy.get("blocking_flags"), list):
        problems.append("The review policy is unreadable.")
    if not final:
        problems.append("There is nothing to review.")
    return problems


def merge_blockers(gate, tests, policy, final):
    """Every reason the deterministic policy forbids an automatic merge."""
    reasons = []
    tests = tests if isinstance(tests, dict) else {}
    required = [("quick", "Quick checks")]
    if gate.get("needs_smoke"):
        required.append(("smoke", "Smoke test"))
    if gate.get("dockerfiles"):
        required.append(("docker_build", "Docker build"))
    for field, label in required:
        if tests.get(field) != "success":
            reasons.append(f"{label} did not pass ({truncate(plain(tests.get(field) or 'no result'), 20)}).")

    checks = tests.get("checks")
    if not isinstance(checks, list):
        reasons.append("Check results are unreadable.")
    else:
        bad = []
        for check in checks:
            if not isinstance(check, dict):
                bad.append("unreadable check")
            elif check.get("status") != "completed":
                bad.append(f"{truncate(plain(check.get('name')), 80)} ({truncate(plain(check.get('status')), 20)})")
            elif check.get("conclusion") not in GREEN_CONCLUSIONS:
                bad.append(f"{truncate(plain(check.get('name')), 80)} ({truncate(plain(check.get('conclusion')), 20)})")
        if bad:
            reasons.append(f"Other checks are not green: {', '.join(bad)}.")

    blocking = set(policy.get("blocking_flags") or [])
    flagged = [f"{k} [{', '.join(sorted(set(e['flags']) & blocking))}]"
               for k, e in final.items() if e["flags"] and set(e["flags"]) & blocking]
    if flagged:
        reasons.append(f"Blocking flags: {'; '.join(flagged)}.")
    unknown = [k for k, e in final.items() if e["flags"] is None]
    if unknown:
        reasons.append(f"Flags unknown for earlier-reviewed updates: {', '.join(unknown)}.")

    ceiling = common.RISK_ORDER.get(policy.get("automerge_max_risk"), -1)
    top = max((e["risk"] for e in final.values()), key=common.RISK_ORDER.get, default="high")
    if common.RISK_ORDER[top] > ceiling:
        reasons.append(f"Highest risk is {top}; auto-merge allows up to {policy.get('automerge_max_risk')}.")

    worldgen = gate.get("worldgen_paths")
    if worldgen or not isinstance(worldgen, list):
        reasons.append(f"Touches worldgen paths: {', '.join(plain(p) for p in worldgen or []) or 'unknown'}.")
    never = [p for p in policy.get("never_automerge_paths") or [] if isinstance(p, str)]
    touched = sorted({f for f in gate.get("files") or [] if isinstance(f, str) and any(f.startswith(p) for p in never)})
    if touched:
        reasons.append(f"Touches paths that are never auto-merged: {', '.join(touched)}.")

    not_accepted = [k for k, e in final.items() if e["decision"] != "accept"]
    if not_accepted or not final:
        reasons.append(f"Not every update was accepted: {', '.join(not_accepted) or 'none reviewed'}.")
    return reasons


def plan_holds(manifest, final, verdict, pr_number, notes):
    """Return (holds to add, holds to remove) that survive validation."""
    current = manifest.get("_holds") if isinstance(manifest.get("_holds"), dict) else {}
    held_back = {e["name"] for e in final.values()
                 if e["origin"] == "new" and e["ecosystem"] in HOLD_ECOSYSTEMS and e["decision"] == "hold"}
    adds, removes = [], []
    for item in verdict["holds_add"]:
        slug = item["slug"]
        if slug in current:
            notes.append(f"Ignored holds_add for {slug}: already held.")
        elif slug not in held_back:
            notes.append(f"Ignored holds_add for {slug}: the reviewer did not decide to hold it in this PR.")
        elif any(a["slug"] == slug for a in adds):
            notes.append(f"Ignored a repeated holds_add for {slug}.")
        else:
            reason = truncate(plain(item["reason"]).replace("<", "").replace(">", ""), HOLD_REASON_MAX)
            adds.append({"slug": slug, "reason": f"{reason} (dep-review: PR #{pr_number})"})
    for item in verdict["holds_remove"]:
        slug = item["slug"]
        if slug not in current:
            notes.append(f"Ignored holds_remove for {slug}: not held.")
        elif any(r["slug"] == slug for r in removes):
            notes.append(f"Ignored a repeated holds_remove for {slug}.")
        else:
            removes.append({"slug": slug, "reason": truncate(plain(item["reason"]), HOLD_REASON_MAX)})
    return adds, removes


def edited_manifest(manifest, adds, removes):
    holds = dict(manifest.get("_holds") or {})
    for item in removes:
        holds.pop(item["slug"], None)
    for item in adds:
        holds[item["slug"]] = item["reason"]
    out = dict(manifest)
    out["_holds"] = holds
    return json.dumps(out, indent=2, ensure_ascii=False) + "\n"


def decide(source, verdict, final, adds, removes, blockers):
    """Pick the action; returns (action, reasons). First match wins."""
    not_accepted = [k for k, e in final.items() if e["decision"] != "accept"]
    if not verdict["ok"]:
        return "human", [verdict["error"]]
    if source == "mods" and (adds or removes):
        return "rehold", []
    if source != "mods":
        if final and all(e["decision"] == "reject" for e in final.values()):
            return "close", [f"The reviewer rejected every update: {', '.join(final)}."]
        if not_accepted:
            return "human", [f"Not accepted by the reviewer: {', '.join(not_accepted)}."]
    elif not_accepted:
        return "human", [f"Not accepted by the reviewer: {', '.join(not_accepted)}."]
    if blockers:
        return "human", blockers
    return "merge", []


def state_for(gate, final):
    """The stored state: genuine judgements only, shrunk to fit the comment."""
    kept = {k: e for k, e in final.items() if e["origin"] in ("new", "cached")}
    for limit in (200, 80, 0):
        verdicts = {k: {"decision": e["decision"], "risk": e["risk"],
                        "reason": truncate(e["reason"], limit) if limit else "",
                        "flags": e["flags"] if e["flags"] is not None else []}
                    for k, e in kept.items()}
        state = {"v": common.STATE_VERSION, "fingerprint": gate.get("fingerprint"),
                 "head_sha": gate.get("head_sha"), "verdicts": verdicts}
        if len(common.encode_state(state)) <= STATE_BUDGET:
            break
    return state


# --- comment ---------------------------------------------------------------

def outcome_line(action, final, reasons, adds, removes):
    n = len(final)
    if action == "merge":
        top = max((e["risk"] for e in final.values()), key=common.RISK_ORDER.get)
        risk = "all low risk" if top == "low" else f"none above {top} risk"
        return f"Auto-merging: {plural(n, 'update')} accepted, {risk}, tests green."
    if action == "close":
        return f"Closing: the reviewer rejected all {plural(n, 'update')}."
    if action == "rehold":
        parts = []
        if adds:
            parts.append(f"Holding {plural(len(adds), 'mod')} back")
        if removes:
            parts.append(f"{'releasing' if adds else 'Releasing'} {plural(len(removes), 'hold')}")
        return f"{', '.join(parts)} and regenerating this PR."
    if len(reasons) == 1:
        return f"Needs a human: {md(truncate(reasons[0], REASON_BULLET_MAX))}"
    return f"Needs a human: {len(reasons)} problems, listed below."


def change_cell(entry):
    old, new = entry.get("old"), entry.get("new")
    old = md(old) if old not in (None, "") else "—"
    new = md(new) if new not in (None, "") else "removed"
    return f"{old} → {new}"


def table_rows(entries):
    rows = []
    for e in entries:
        name = md(e.get("name") or e["key"])
        if e["origin"] == "cached":
            name += " (earlier review)"
        change = change_cell(e) if e["origin"] != "cached" else f"→ {md(e.get('new') or '?')}"
        flags = ", ".join(md(f) for f in e["flags"]) if e["flags"] else ("unknown" if e["flags"] is None else "—")
        rows.append(f"| {name} | {change} | {e['decision']} | {e['risk']} | {flags} | {md(e['reason'])} |")
    return rows


def render_comment(plan, final, summary, notes, run_url, adds, removes,
                   evidence=True, summarise_accepts=False, max_rows=None):
    lines = [common.encode_state(plan["state"]), "", "### Dependency review", "",
             outcome_line(plan["action"], final, plan["reasons"], adds, removes), ""]
    if plan["action"] != "merge" and plan["reasons"] and (plan["action"] != "human" or len(plan["reasons"]) > 1):
        lines += [f"- {md(truncate(r, REASON_BULLET_MAX))}" for r in plan["reasons"]] + [""]

    entries = list(final.values())
    shown = [e for e in entries if not summarise_accepts or e["decision"] != "accept"]
    hidden_accepts = len(entries) - len(shown)
    rows = table_rows(shown)
    if max_rows is not None and len(rows) > max_rows:
        omitted = len(rows) - max_rows
        rows = rows[:max_rows]
    else:
        omitted = 0
    if rows:
        lines += ["| Dependency | Change (old → new) | Decision | Risk | Flags | Reason |",
                  "| --- | --- | --- | --- | --- | --- |"] + rows + [""]
    if hidden_accepts:
        lines += [f"{plural(hidden_accepts, 'further update')} accepted; rows omitted for length.", ""]
    if omitted:
        lines += [f"{plural(omitted, 'further row')} omitted for length.", ""]

    if adds:
        lines += ["**Holds added**", ""] + [f"- `{a['slug']}`: {md(a['reason'])}" for a in adds] + [""]
    if removes:
        lines += ["**Holds released**", ""] + [f"- `{r['slug']}`: {md(r['reason'])}" for r in removes] + [""]

    with_evidence = [e for e in entries if e["evidence"]]
    if evidence and with_evidence:
        lines += ["<details><summary>Evidence</summary>", ""]
        for e in with_evidence:
            lines.append(f"- {md(e.get('name') or e['key'])}")
            lines += [f"  - {md_code_or_text(item)}" for item in e["evidence"]]
        lines += ["", "</details>", ""]
    if notes:
        lines += ["<details><summary>Notes</summary>", ""]
        lines += [f"- {md(truncate(n, REASON_BULLET_MAX))}" for n in notes[:50]]
        lines += ["", "</details>", ""]
    if summary:
        lines += [("> " + md(line)) if line else ">" for line in summary.split("\n")] + [""]

    link = f"[dep-review]({run_url})" if RUN_URL_RE.fullmatch(run_url or "") else "dep-review"
    lines.append(f"<sub>Reviewed by {link}. Deterministic policy: .github/dep-review/policy.json.</sub>")
    return "\n".join(lines) + "\n"


def fit_comment(plan, final, summary, notes, run_url, adds, removes):
    attempts = [dict(), dict(evidence=False), dict(evidence=False, summarise_accepts=True)]
    for kwargs in attempts:
        body = render_comment(plan, final, summary, notes, run_url, adds, removes, **kwargs)
        if len(body) <= COMMENT_MAX:
            return body
    rows = len(final)
    while rows > 0:
        rows //= 2
        body = render_comment(plan, final, summary, notes[:5], run_url, adds, removes,
                              evidence=False, summarise_accepts=True, max_rows=rows)
        if len(body) <= COMMENT_MAX:
            return body
    footer = "\n\nComment truncated for length.\n"
    return body[: COMMENT_MAX - len(footer)] + footer


# --- entry point -----------------------------------------------------------

def build(gate, context, verdict_path, tests, policy, manifest_path, run_url, now):
    """Return (plan, comment text, new manifest text or None)."""
    work_keys = {c["key"] for c in context.get("work") or [] if isinstance(c, dict) and isinstance(c.get("key"), str)}
    raw, error = load_verdict(verdict_path)
    verdict = validate_verdict(raw, work_keys) if error is None else {
        "ok": False, "error": error, "summary": "", "judged": {}, "invalid": {}, "duplicates": set(),
        "holds_add": [], "holds_remove": [], "notes": []}
    notes = [truncate(plain(n), REASON_BULLET_MAX) for n in context.get("notes") or [] if isinstance(n, str)]
    notes += verdict["notes"]
    final = build_final(context, gate, verdict)
    source = gate.get("source")

    adds, removes = [], []
    manifest = None
    problems = integrity_problems(gate, context, policy, final)
    if verdict["ok"] and (verdict["holds_add"] or verdict["holds_remove"]):
        if source != "mods":
            notes.append("Ignored hold changes: holds apply only to mod PRs.")
        elif not problems:
            manifest = read_optional_json(manifest_path)
            if not isinstance(manifest, dict):
                problems.append("The modpack manifest is unreadable, so hold changes could not be applied.")
            else:
                adds, removes = plan_holds(manifest, final, verdict, gate.get("pr"), notes)

    if problems:
        action, reasons = "human", problems
        adds, removes = [], []
    else:
        action, reasons = decide(source, verdict, final, adds, removes,
                                 merge_blockers(gate, tests, policy, final))
    if action != "rehold":
        adds, removes = [], []

    plan = {
        "action": action,
        "reasons": reasons,
        "labels_add": [LABEL_REVIEWED, OUTCOME_LABELS[action]],
        "labels_remove": [label for label in ALL_OUTCOME_LABELS if label != OUTCOME_LABELS[action]],
        "publish_images": action == "merge" and source == "docker",
        "holds_changed": bool(adds or removes),
        "holds_added": adds,
        "holds_removed": removes,
        "verdict_ok": verdict["ok"],
        "notes": notes,
        "pr": gate.get("pr"),
        "head_sha": gate.get("head_sha"),
        "generated_at": now,
        "state": state_for(gate, final),
    }
    comment = fit_comment(plan, final, verdict["summary"], notes, run_url, adds, removes)
    new_manifest = edited_manifest(manifest, adds, removes) if adds or removes else None
    return plan, comment, new_manifest


def parse_args(argv):
    p = argparse.ArgumentParser(description="Turn a dep-review verdict into a plan.")
    p.add_argument("--gate", required=True)
    p.add_argument("--context", required=True)
    p.add_argument("--verdict", required=True)
    p.add_argument("--tests", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--comment", required=True)
    p.add_argument("--run-url", required=True)
    p.add_argument("--policy", default=str(common.POLICY_PATH))
    p.add_argument("--manifest", default=str(common.REPO_ROOT / "modpack" / "adventure.mrpack.json"))
    p.add_argument("--now", default=None, help="ISO 8601 timestamp recorded in the plan")
    return p.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        gate = read_json(args.gate)
        context = read_json(args.context)
        if not isinstance(gate, dict) or not isinstance(context, dict):
            raise ValueError("not a JSON object")
    except (OSError, ValueError) as exc:
        print(f"apply.py: cannot read gate or context: {exc}", file=sys.stderr)
        return 2
    try:
        policy = common.load_policy(args.policy)
    except (OSError, ValueError):
        policy = {}
    if not isinstance(policy, dict):
        policy = {}
    now = args.now or datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    plan, comment, new_manifest = build(gate, context, args.verdict, read_optional_json(args.tests),
                                        policy, args.manifest, args.run_url, now)
    if new_manifest is not None:
        Path(args.manifest).write_text(new_manifest, encoding="utf-8")
    Path(args.out).write_text(json.dumps(plan, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    Path(args.comment).write_text(comment, encoding="utf-8")
    print(f"dep-review apply: action={plan['action']} reasons={len(plan['reasons'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
