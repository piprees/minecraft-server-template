#!/usr/bin/env bash
# live-logs.sh - Interactively tail production container logs from your Mac.
#
# Presents a checkbox menu of running containers (j/k/space/a/n/enter).
# Selections persist in ~/.config/minecraft-server/log-selection.
#
# Streaming modes (no flags) run forever - HUMANS ONLY. Agents and scripts
# use snapshot mode, which returns immediately:
#   ./scripts/live-logs.sh mc --tail 200
#   ./scripts/live-logs.sh mc --tail 500 --grep "ERROR|FATAL"
#   ./scripts/live-logs.sh mc --since 1h --grep "joined the game"
#
# Usage:
#   ./scripts/live-logs.sh              # interactive selection (streams)
#   ./scripts/live-logs.sh --all        # all containers (streams)
#   ./scripts/live-logs.sh mc           # single container (streams)
#   ./scripts/live-logs.sh mc --tail N [--grep PATTERN] [--since 1h]   # snapshot
#   ./scripts/live-logs.sh mc --errors                                 # recent errors/warnings
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

: "${DROPLET_HOST:?Set DROPLET_HOST in .env or environment}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
SSH_CMD="ssh ${DEPLOY_USER}@${DROPLET_HOST}"
TAIL_LINES=100
SERVER_DIR="server"
CONFIG_DIR="$HOME/.config/minecraft-server"
SELECTION_FILE="$CONFIG_DIR/log-selection"

# --- single container: snapshot mode (any flag) or stream (no flags) ----------
if [[ "${1:-}" != "" && "${1:-}" != "--all" ]]; then
  CONTAINER="$1"
  shift
  SNAP_TAIL=""
  SNAP_GREP=""
  SNAP_SINCE=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --tail) SNAP_TAIL="${2:?--tail needs a number}"; shift 2 ;;
      --grep) SNAP_GREP="${2:?--grep needs a pattern}"; shift 2 ;;
      --since) SNAP_SINCE="${2:?--since needs a duration (e.g. 1h)}"; shift 2 ;;
      --errors)
        SNAP_TAIL="${SNAP_TAIL:-500}"
        SNAP_GREP="ERROR|WARN"
        shift ;;
      *) echo "Unknown flag: $1 (see header for usage)"; exit 1 ;;
    esac
  done
  if [[ -n "${SNAP_TAIL}${SNAP_GREP}${SNAP_SINCE}" ]]; then
    CMD="sudo docker logs --tail ${SNAP_TAIL:-200}"
    [[ -n "$SNAP_SINCE" ]] && CMD="$CMD --since $SNAP_SINCE"
    CMD="$CMD $CONTAINER 2>&1"
    [[ -n "$SNAP_GREP" ]] && CMD="$CMD | grep -iE '$SNAP_GREP' | tail -200"
    # shellcheck disable=SC2029
    exec $SSH_CMD "$CMD"
  fi
  exec $SSH_CMD "sudo docker logs -f --tail $TAIL_LINES $CONTAINER"
fi

# --- fetch running containers ------------------------------------------------
CONTAINERS=$($SSH_CMD "sudo docker ps --format '{{.Names}}' | sort" 2> /dev/null)

if [[ -z "$CONTAINERS" ]]; then
  echo "No containers running on ${DROPLET_HOST}"
  exit 1
fi

ALL=()
while IFS= read -r line; do
  ALL+=("$line")
done <<< "$CONTAINERS"
COUNT=${#ALL[@]}

# --- --all shortcut -----------------------------------------------------------
if [[ "${1:-}" == "--all" ]]; then
  echo "Tailing all ${COUNT} containers on ${DROPLET_HOST}..."
  # shellcheck disable=SC2029
  exec $SSH_CMD "cd ~/${SERVER_DIR} && sudo docker compose --profile cloud logs -f --tail $TAIL_LINES"
fi

# --- load previous selection into a flat list --------------------------------
PREV_NAMES=""
if [[ -f "$SELECTION_FILE" ]]; then
  PREV_NAMES=$(cat "$SELECTION_FILE")
  HAS_PREV=1
else
  HAS_PREV=0
fi

is_prev_selected() {
  if [[ $HAS_PREV -eq 0 ]]; then return 0; fi
  echo "$PREV_NAMES" | grep -qx "$1"
}

# --- build selection state (1=selected, 0=not) in indexed arrays -------------
SELECTED=()
for i in $(seq 0 $((COUNT - 1))); do
  if is_prev_selected "${ALL[$i]}"; then
    SELECTED+=("1")
  else
    SELECTED+=("0")
  fi
done

CURSOR=0

draw_menu() {
  if [[ "${DRAWN:-0}" -eq 1 ]]; then
    printf '\033[%dA' "$((COUNT + 2))"
  fi
  DRAWN=1

  echo -e "\033[1m  Select containers (space=toggle, a=all, n=none, enter=go, q=quit)\033[0m"
  echo ""
  for i in $(seq 0 $((COUNT - 1))); do
    local name="${ALL[$i]}"
    local check=" "
    [[ "${SELECTED[$i]}" == "1" ]] && check="✓"
    local arrow="  "
    [[ $i -eq $CURSOR ]] && arrow="▸ "
    if [[ $i -eq $CURSOR ]]; then
      printf '\033[1m%s[%s] %s\033[0m\n' "$arrow" "$check" "$name"
    else
      printf '%s[%s] %s\n' "$arrow" "$check" "$name"
    fi
  done
}

# Hide cursor, restore on exit
trap 'printf "\033[?25h"; stty echo 2>/dev/null' EXIT
stty -echo 2> /dev/null || true

draw_menu

while true; do
  IFS= read -rsn1 key

  case "$key" in
    k) ((CURSOR > 0)) && ((CURSOR--)) ;;
    j) ((CURSOR < COUNT - 1)) && ((CURSOR++)) ;;
    ' ')
      if [[ "${SELECTED[$CURSOR]}" == "1" ]]; then
        SELECTED[$CURSOR]="0"
      else
        SELECTED[$CURSOR]="1"
      fi
      ;;
    a) for i in $(seq 0 $((COUNT - 1))); do SELECTED[$i]="1"; done ;;
    n) for i in $(seq 0 $((COUNT - 1))); do SELECTED[$i]="0"; done ;;
    q)
      echo ""
      exit 0
      ;;
    '') break ;;
    $'\x1b')
      # shellcheck disable=SC2034
      read -rsn1 -t 1 seq1 2>/dev/null || true
      read -rsn1 -t 1 seq2 2>/dev/null || true
      case "${seq2:-}" in
        A) ((CURSOR > 0)) && ((CURSOR--)) ;;
        B) ((CURSOR < COUNT - 1)) && ((CURSOR++)) ;;
      esac
      ;;
  esac

  draw_menu
done

stty echo 2> /dev/null || true
printf '\033[?25h'

# --- build chosen list --------------------------------------------------------
CHOSEN=()
for i in $(seq 0 $((COUNT - 1))); do
  [[ "${SELECTED[$i]}" == "1" ]] && CHOSEN+=("${ALL[$i]}")
done

if [[ ${#CHOSEN[@]} -eq 0 ]]; then
  echo "No containers selected."
  exit 0
fi

# --- save selection -----------------------------------------------------------
mkdir -p "$CONFIG_DIR"
printf '%s\n' "${CHOSEN[@]}" > "$SELECTION_FILE"

# --- tail logs ----------------------------------------------------------------
echo ""
echo "Tailing ${#CHOSEN[@]} container(s): ${CHOSEN[*]}"
echo "Press Ctrl+C to stop."
echo ""

# shellcheck disable=SC2029
exec $SSH_CMD "cd ~/${SERVER_DIR} && sudo docker compose --profile cloud logs -f --tail $TAIL_LINES ${CHOSEN[*]}"
