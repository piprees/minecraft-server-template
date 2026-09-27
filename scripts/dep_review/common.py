"""common.py - contracts shared by gate.py, collect.py and apply.py.

Context: dep-review.yml runs gate -> collect -> (reviewer) -> apply. The only
state carried between runs is the review comment on the PR, identified by
STATE_PREFIX. Its payload is base64 so no reviewer text can close the HTML
comment or forge a second marker, and only comments authored by STATE_AUTHOR
are trusted.

Gotchas:
  - A change key is "<ecosystem>:<name>@<new version>". Keys are the unit of
    caching: a refreshed PR only sends keys absent from the previous state to
    the reviewer.
  - Standard library only; runs on the Actions runner's system python3.
"""
import base64
import json
import re
from pathlib import Path

STATE_PREFIX = "<!-- dep-review:state "
STATE_SUFFIX = " -->"
STATE_AUTHOR = "github-actions[bot]"
STATE_VERSION = 1

RISK_ORDER = {"low": 0, "medium": 1, "high": 2}
DECISIONS = ("accept", "hold", "reject")

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = REPO_ROOT / ".github" / "dep-review" / "policy.json"

_STATE_RE = re.compile(re.escape(STATE_PREFIX) + r"([A-Za-z0-9+/=]+)" + re.escape(STATE_SUFFIX))


def change_key(ecosystem, name, new_version):
    return f"{ecosystem}:{name}@{new_version}"


def encode_state(state):
    raw = json.dumps(state, sort_keys=True, separators=(",", ":")).encode()
    return STATE_PREFIX + base64.b64encode(raw).decode() + STATE_SUFFIX


def decode_state(body):
    """Return the state dict embedded in a comment body, or None."""
    match = _STATE_RE.search(body or "")
    if not match:
        return None
    try:
        state = json.loads(base64.b64decode(match.group(1), validate=True))
    except (ValueError, json.JSONDecodeError):
        return None
    if not isinstance(state, dict) or state.get("v") != STATE_VERSION:
        return None
    return state


def latest_state(comments):
    """Find the newest trusted state among issue comments (GitHub API shape).

    Returns (comment_id, state) or (None, None).
    """
    trusted = [
        c for c in comments
        if (c.get("user") or {}).get("login") == STATE_AUTHOR
        and decode_state(c.get("body")) is not None
    ]
    if not trusted:
        return None, None
    newest = max(trusted, key=lambda c: c.get("id", 0))
    return newest["id"], decode_state(newest["body"])


def load_policy(path=POLICY_PATH):
    with open(path) as f:
        return json.load(f)
