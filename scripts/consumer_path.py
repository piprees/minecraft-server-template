#!/usr/bin/env python3
"""consumer_path.py - Resolve the consumer repo root for template-only tooling.

Context: several platform-development scripts read a consumer checkout
(elfydd-shaped) to extract catalogues, check coverage, or fold rolled seeds
back into the platform defaults. They are template-only and never shipped, so
they cannot use lib.sh's resolution; this is the Python equivalent of the same
CONSUMER_DIR contract the ops/dev dispatchers set.

Usage:
  from consumer_path import consumer_dir
  root = consumer_dir(args.consumer)      # explicit path wins, else $CONSUMER_DIR

Gotchas: there is deliberately NO default. A guessed path turns "the consumer
is missing" into "the consumer is empty", and a checker that cannot tell those
apart reports success having examined nothing.

Template-only (platform development); not in the bundle MANIFEST.
"""
import os
import sys
from pathlib import Path

ENV_VAR = "CONSUMER_DIR"

_HELP = f"""\
No consumer repo given.

Set the environment variable:
    export {ENV_VAR}=~/Projects/<your-consumer-repo>

or pass the path explicitly on the command line.

A consumer repo is a checkout made from examples/consumer/ — it has an
overlay/ directory and a dev dispatcher at its root."""


def looks_like_consumer(path: Path) -> bool:
    """A consumer checkout carries an overlay/ and a dev dispatcher."""
    return (path / "overlay").is_dir() and (path / "dev").is_file()


def _resolve(explicit) -> Path:
    """Validate a non-empty path, or exit saying exactly what is wrong."""
    me = Path(sys.argv[0]).name
    path = Path(str(explicit).strip()).expanduser().resolve()

    if not path.is_dir():
        sys.exit(f"{me}: consumer repo not found: {path}\n\n{_HELP}")

    if not looks_like_consumer(path):
        sys.exit(
            f"{me}: {path} is not a consumer repo "
            f"(no overlay/ directory and dev dispatcher).\n\n{_HELP}"
        )

    return path


def consumer_dir(explicit=None) -> Path:
    """The consumer repo root. Exits non-zero if there isn't a usable one.

    Precedence: explicit argument, then $CONSUMER_DIR. Never guesses.
    """
    raw = (explicit or os.environ.get(ENV_VAR) or "").strip()
    if not raw:
        sys.exit(f"{Path(sys.argv[0]).name}: {ENV_VAR} is not set.\n\n{_HELP}")
    return _resolve(raw)


def optional_consumer_dir(explicit=None):
    """The consumer repo root, or None when none was asked for.

    For tooling that covers the platform with or without a consumer to hand.
    An unset variable is None; a path that is SET but wrong still exits, so a
    typo can never read as "no consumer configured".
    """
    raw = (explicit or os.environ.get(ENV_VAR) or "").strip()
    return _resolve(raw) if raw else None
