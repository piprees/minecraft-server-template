"""The running stack's version — the identity every artefact and cache keys on.

`build-stack-bundle.sh` writes `stack/VERSION` from the release tag, so the
value moves on every release with nothing to remember. Anything that needs to
say "which build produced this" reads it from here rather than declaring its
own constant.

`./dev link` writes `dev`: a linked platform checkout has no release identity
and changes continuously, so callers must treat it as "unknown, don't compare"
via `is_dev()`. On a dev stack the escape hatch is `./dev seed-roll --reset`.
"""
from pathlib import Path

#: What the mod reports when no release built it, and what the bundle carries
#: under `./dev link`. Two of these are never evidence of the same code.
DEV_MARKERS = ("dev", "unknown", "")

#: Any 0.0.0.* build is unreleased whatever suffix it carries, so changing the
#: mod's dev marker cannot quietly start reading as a release.
DEV_PREFIX = "0.0.0"


def _version_file():
    """`stack/VERSION`, relative to where this module was shipped.

    This file is `stack/scripts/stack_version.py` in the bundle, so VERSION
    sits two parents up. A platform checkout has none, which reads as dev.
    """
    return Path(__file__).resolve().parent.parent / "VERSION"


def stack_version():
    """The bundle's version string, or `dev` when there isn't one."""
    try:
        value = _version_file().read_text().strip()
    except OSError:
        return "dev"
    return value or "dev"


def is_dev(version=None):
    """True when `version` carries no release identity to compare against."""
    if version is None:
        version = stack_version()
    value = str(version).strip().lower()
    return value in DEV_MARKERS or value.startswith(DEV_PREFIX)


def cache_key(version=None):
    """The stamp to key a cached measurement on.

    A dev stack collapses to one constant key, so caches persist across edits
    and `--reset` is how you start clean.
    """
    if version is None:
        version = stack_version()
    return "dev" if is_dev(version) else str(version).strip()
