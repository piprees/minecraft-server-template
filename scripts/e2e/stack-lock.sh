#!/usr/bin/env bash
# stack-lock.sh - One writer at a time for the local stack. Take the lock
# before any container action, release it after.
#
# Context: template-only. Several agents share one consumer stack, and
# `./dev up` RECREATES mc — every client in the world goes to Connection Lost
# and any measurement in flight is invalidated. Messages between agents arrive
# batched, so "tell everyone first" does not work: two agents can each believe
# they were cleared. This is the interlock that does.
#
# Usage:
#   stack-lock.sh acquire <holder> [--reason "..."] [--wait N]
#   stack-lock.sh release <holder>
#   stack-lock.sh status
#   stack-lock.sh steal <holder> --reason "..."     # only after status says STALE
#
#   <holder>   your agent name, so `status` names who to ask
#   --wait N   seconds to wait for a busy lock (default 0: fail immediately)
#
# Exit 0 when the lock is yours. Exit 1 when someone else holds it, naming
# them and how long they have had it. Exit 2 on a usage error.
#
# Gotchas: the lock is ADVISORY — it cannot stop `./dev up`, it stops a
# careful agent from racing another careful agent. A holder that dies leaves
# the lock behind, which is why `status` reports STALE past STALE_AFTER and
# `steal` exists. Releasing a lock you do not hold is refused, so a late
# release cannot free somebody else's slot. Must run on macOS bash 3.2 - no
# mapfile, no ${var,,}, no grep -P.
set -euo pipefail

LOCK_DIR="${STACK_LOCK_DIR:-/tmp/mc-stack-lock}"
LOCK_FILE="$LOCK_DIR/holder"
STALE_AFTER="${STACK_LOCK_STALE_AFTER:-900}"   # 15 min; a browser pass is ~15

now() { date +%s; }

read_lock() { # sets HOLDER, SINCE, REASON; returns 1 when free
  [ -f "$LOCK_FILE" ] || return 1
  HOLDER="$(sed -n '1p' "$LOCK_FILE")"
  SINCE="$(sed -n '2p' "$LOCK_FILE")"
  REASON="$(sed -n '3p' "$LOCK_FILE")"
  [ -n "$HOLDER" ] || return 1
  return 0
}

held_for() { echo $(( $(now) - ${SINCE:-$(now)} )); }

usage() { sed -n '2,20p' "$0"; exit 2; }

ACTION="${1:-status}"
[ $# -gt 0 ] && shift || true
HOLDER_ARG="${1:-}"
case "$ACTION" in acquire|release|steal) [ -n "$HOLDER_ARG" ] || usage; shift ;; esac

REASON_ARG=""
WAIT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --reason) shift; REASON_ARG="${1:-}" ;;
    --wait) shift; WAIT="${1:-0}" ;;
    *) usage ;;
  esac
  shift || true
done

mkdir -p "$LOCK_DIR"

case "$ACTION" in
  status)
    if read_lock; then
      age="$(held_for)"
      state="HELD"
      [ "$age" -gt "$STALE_AFTER" ] && state="STALE"
      printf '%s by %s for %ss — %s\n' "$state" "$HOLDER" "$age" "${REASON:-no reason given}"
      [ "$state" = "STALE" ] && exit 0
      exit 1
    fi
    echo "FREE"
    ;;

  acquire)
    waited=0
    while :; do
      # mkdir is the atomic test-and-set; the file is only the metadata.
      if mkdir "$LOCK_DIR/held" 2>/dev/null; then
        printf '%s\n%s\n%s\n' "$HOLDER_ARG" "$(now)" "${REASON_ARG:-}" > "$LOCK_FILE"
        printf 'ACQUIRED by %s\n' "$HOLDER_ARG"
        exit 0
      fi
      if read_lock && [ "$HOLDER" = "$HOLDER_ARG" ]; then
        printf 'ALREADY YOURS (%s, %ss)\n' "$HOLDER_ARG" "$(held_for)"
        exit 0
      fi
      if [ "$waited" -ge "$WAIT" ]; then
        read_lock || { rmdir "$LOCK_DIR/held" 2>/dev/null || true; continue; }
        printf 'BUSY: %s has held it %ss — %s\n' "$HOLDER" "$(held_for)" "${REASON:-no reason given}" >&2
        printf 'Ask them, or `stack-lock.sh status` and steal only if STALE.\n' >&2
        exit 1
      fi
      sleep 5
      waited=$(( waited + 5 ))
    done
    ;;

  release)
    if ! read_lock; then
      echo "not held; nothing to release"
      exit 0
    fi
    if [ "$HOLDER" != "$HOLDER_ARG" ]; then
      printf 'REFUSED: %s holds it, not %s\n' "$HOLDER" "$HOLDER_ARG" >&2
      exit 1
    fi
    rm -f "$LOCK_FILE"; rmdir "$LOCK_DIR/held" 2>/dev/null || true
    printf 'RELEASED by %s after %ss\n' "$HOLDER_ARG" "$(held_for)"
    ;;

  steal)
    [ -n "$REASON_ARG" ] || { echo "steal needs --reason" >&2; exit 2; }
    if read_lock; then
      age="$(held_for)"
      if [ "$age" -le "$STALE_AFTER" ]; then
        printf 'REFUSED: %s has held it only %ss (stale at %ss). Ask them.\n' \
          "$HOLDER" "$age" "$STALE_AFTER" >&2
        exit 1
      fi
      printf 'stealing from %s (held %ss)\n' "$HOLDER" "$age"
    fi
    rm -f "$LOCK_FILE"; rmdir "$LOCK_DIR/held" 2>/dev/null || true
    mkdir "$LOCK_DIR/held"
    printf '%s\n%s\n%s\n' "$HOLDER_ARG" "$(now)" "$REASON_ARG" > "$LOCK_FILE"
    printf 'ACQUIRED by %s (stolen)\n' "$HOLDER_ARG"
    ;;

  *) usage ;;
esac
