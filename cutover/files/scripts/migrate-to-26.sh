#!/usr/bin/env bash
# migrate-to-26.sh - Bring a running 1.21.1 server and its consumer repo to the v6 (26.3) stack.
#
# Purpose: runs the migration in phases, in order, from the consumer repo on
#   the operator's machine, driving the server through ./ops (ssh, doctor,
#   logs). The 1.21.1 world never boots on 26.3 and is never deleted before
#   it is archived three ways (restic snapshot, cold tar on the host, a copy
#   off the host whose sha256 the operator types back).
#
# Context: bundle script, shipped in the v6 bundle and run BY PATH from it
#   while the consumer still runs v5, so no ./ops entry exists for it. Fetch
#   the v6 bundle without repointing .stack/current (docs/migrate-to-26.md):
#     gh release download v6.0.0 -R piprees/minecraft-server-template \
#       -p 'stack-v6.0.0.tar.gz*' -D .stack/v6.0.0
#     (cd .stack/v6.0.0 && shasum -a 256 -c stack-v6.0.0.tar.gz.sha256 && tar xzf stack-v6.0.0.tar.gz)
#   State lives in .migrate-26.state at the consumer root (git-ignored through
#   .git/info/exclude); every phase re-checks its preconditions on the server
#   instead of trusting it, so a re-run resumes.
#
# Usage (from the consumer repo):
#   M=.stack/v6.0.0/stack/scripts/migrate-to-26.sh
#   bash $M plan                         # every step, in order, with its class
#   bash $M status                       # what is done and what is next
#   bash $M <phase>                      # dry run: checks, prints what it would do
#   bash $M <phase> --apply              # do it
#   Phases, in order: preflight pin archive host clean consumer verify first-backup
#   --apply                 change things (the default is a dry run)
#   --yes-<step>            accept one confirm step without typing its name:
#                           doctor pin map stop-mc firewall clean consumer
#   --owner-done <step>     record an owner step as done (with --apply):
#                           release cloud-firewall secrets launch
#   --offhost-sha <sum>     clean: sha256 of the world tar's off-host copy
#   --tidy                  clean: also remove the tidy-up paths
#   --local <consumer-dir>  run against a linked local stack (compose local
#                           profile, no ssh, no ufw, no push)
#   --link <platform-dir>   consumer, with --local: ./dev link to this checkout
#   --consumer <dir>        the consumer repo (default: the current directory)
#   --target <vX.Y.Z>       the v6 release (default: this bundle's VERSION)
#   --lazy-slug <slug>      verify: the lazy dimension whose game rules it reads
#   --attempts N --interval S   bounded waits: N snapshots S seconds apart (6, 30)
#   --non-interactive       never prompt; a confirm step needs its --yes-<step>
#
# Exit codes: 0 phase done; 1 refused or failed; 2 usage; 3 not yet (a wait
#   ran out, or an owner step is due): run the same phase again.
#
# Gotchas:
#   - There is no blanket yes: each confirm step is named, and an unknown
#     --yes-<step> is refused rather than ignored.
#   - Every wait is a bounded number of single snapshots. RCON silence is
#     possible autopause (the JVM freezes when empty), never a failure.
#   - Never prints .env values, never writes a secret, never edits the
#     server's .env (CI generates it).
#   - The world seed (notes step 12) and lazy levels' game rules (step 13)
#     are open questions: verify measures and reports them; it never runs
#     reset-seed.
#   - Pre-generation (notes step 14) is not scripted: docs/migrate-to-26.md,
#     beside scripts/ in the bundle, says how.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PHASES="preflight pin archive host clean consumer verify first-backup"
YES_STEPS="doctor pin map stop-mc firewall clean consumer"
OWNER_STEPS="release cloud-firewall secrets launch"
BASE_SLUGS="overworld the_nether the_end the_aviary"

PHASE=""
APPLY=0
YES=" "
OWNER_DONE=""
OFFHOST_SHA=""
TIDY=0
LOCAL=0
LINK_DIR=""
CONSUMER=""
TARGET=""
LAZY_SLUG=""
ATTEMPTS=6
INTERVAL=30
NONINTERACTIVE=0

usage() { sed -n '/^# Usage/,/^# Exit codes/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }
in_list() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --yes-*)
      in_list "${1#--yes-}" "$YES_STEPS" || { echo "ERROR: $1 names no confirm step ($YES_STEPS)" >&2; exit 2; }
      YES="$YES${1#--yes-} "
      ;;
    --owner-done)
      in_list "${2:-}" "$OWNER_STEPS" || { echo "ERROR: --owner-done takes one of: $OWNER_STEPS" >&2; exit 2; }
      OWNER_DONE="$OWNER_DONE $2"
      shift
      ;;
    --offhost-sha) OFFHOST_SHA="${2:-}"; shift ;;
    --tidy) TIDY=1 ;;
    --local) LOCAL=1; CONSUMER="${2:-}"; shift ;;
    --link) LINK_DIR="${2:-}"; shift ;;
    --consumer) CONSUMER="${2:-}"; shift ;;
    --target) TARGET="${2:-}"; shift ;;
    --lazy-slug) LAZY_SLUG="${2:-}"; shift ;;
    --attempts) ATTEMPTS="${2:-}"; shift ;;
    --interval) INTERVAL="${2:-}"; shift ;;
    --non-interactive) NONINTERACTIVE=1 ;;
    -h | --help) usage; exit 0 ;;
    -*) echo "ERROR: unknown option $1" >&2; exit 2 ;;
    *)
      [[ -z "$PHASE" ]] || { echo "ERROR: one phase per run ($PHASE, $1)" >&2; exit 2; }
      PHASE="$1"
      ;;
  esac
  shift
done

[[ -n "$PHASE" ]] || { usage; exit 2; }
in_list "$PHASE" "plan status $PHASES" || { echo "ERROR: unknown phase '$PHASE' (plan status $PHASES)" >&2; exit 2; }
[[ "$ATTEMPTS" =~ ^[1-9][0-9]*$ && "$INTERVAL" =~ ^[0-9]+$ ]] || { echo "ERROR: --attempts and --interval take whole numbers" >&2; exit 2; }

CONSUMER_DIR="$(cd "${CONSUMER:-.}" && pwd)"
export CONSUMER_DIR
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
load_env

if [[ -z "$TARGET" && -f "$SCRIPT_DIR/../VERSION" ]]; then
  TARGET="v$(tr -d '[:space:]' < "$SCRIPT_DIR/../VERSION")"
  TARGET="${TARGET#vv}"
  [[ "$TARGET" == v* ]] || TARGET="v$TARGET"
fi

OPS="$CONSUMER_DIR/ops"
DEV="$CONSUMER_DIR/dev"
STATE="$CONSUMER_DIR/.migrate-26.state"
UFW_DIR="${MIGRATE_UFW_DIR:-/etc/ufw}"
if [[ $LOCAL -eq 1 ]]; then
  SERVER_CD="cd '$CONSUMER_DIR'"
  SUDO=""
  MC_CT="${CONTAINER_PREFIX:-}mc"
  BACKUP_CT="${CONTAINER_PREFIX:-}mc-backup-local"
  MAP_CT="${CONTAINER_PREFIX:-}unmined-render"
  STOP_MC="docker stop $MC_CT"
else
  SERVER_CD="cd ~/server"
  SUDO="sudo"
  MC_CT="mc"
  BACKUP_CT="mc-backup"
  MAP_CT="unmined-render"
  STOP_MC="docker compose --profile cloud stop mc"
fi
BEDROCK="${BEDROCK_PORT:-19132}"

# --- What clean deletes (migration-notes.md step 8) ---------------------------
# Paths under the server directory; a glob is expanded by the server's shell.
CLEAN_PATHS=(
  # The world and its derived state, as reset-seed.sh § 5 deletes them.
  data/world/ data/unmined-web/maps/ data/unmined-web/index.html data/unmined-web/manifest.json
  data/config/chunky/tasks/ data/dynamic-data-pack-cache/ data/.dimension-setup/
  'data/.chunky-*complete' 'data/.dhpregen-*complete' data/.skip-pause
  data/config/custom-dimensions-fingerprints.json
  # The 26.3 mod imports a 1.21.1 links file into a world that has none (PortalRegistry).
  data/config/portal_links.json
  # Collective 26.3 moves flat Serilum configs over config/serilum/ on every boot.
  data/config/collective/ data/config/doubledoors/ data/config/firespreadtweaks/
  data/config/healingcampfire/ data/config/nametagtweaks/ data/config/nutritiousmilk/
  data/config/youritemsaresafe/ data/config/collective.json5 data/config/starterkit.json5
)
TIDY_PATHS=(
  # Unread on 26.3: the deploy clears dimensions/ and bridges/ and removes overlay/.
  data/config/custom-dimensions/
  # Configs of mods the 26.3 list cuts.
  data/config/betterdays/ data/config/lootr/ data/config/boring_default_game_rules/
  data/config/tectonic.json data/config/bountiful/ data/config/seasons.json
  data/config/configurable-difficulty/ data/config/bluemapsignmarkers/
)
# The 1.21.1 seed viewer's files in a consumer overlay; stage-dimensions.py ignores them.
SEED_VIEWER_GLOBS="*_thumb.json *_low.png *_high.png"
# What `./dev update` replaces, plus the pin: the consumer commit's paths.
SCAFFOLD_PATHS=".stack-version dev ops .env.example .gitignore AGENTS.md commands.json .github/workflows/deploy.yml .github/workflows/update.yml .github/workflows/server-power.yml .github/dependabot.yml"

# --- Output -------------------------------------------------------------------
say() { printf '%s\n' "$*"; }
head_line() { printf '\n== %s\n' "$*"; }
ok() { printf '  ok    %s\n' "$*"; }
note() { printf '  note  %s\n' "$*"; }
later() {
  printf '  NOT YET  %s\n' "$*"
  say "  Run '$PHASE' again once it is."
  exit 3
}
refuse() { die "$PHASE refused: $*"; }

# --- State (key=value lines; written only with --apply) ------------------------
st_get() { [[ -f "$STATE" ]] && grep -E "^$1=" "$STATE" | tail -1 | cut -d= -f2- || true; }
st_set() {
  [[ $APPLY -eq 1 ]] || return 0
  local tmp="$STATE.tmp"
  { [[ -f "$STATE" ]] && grep -vE "^$1=" "$STATE" || true; printf '%s=%s\n' "$1" "$2"; } > "$tmp"
  mv "$tmp" "$STATE"
}
ensure_state_ignored() {
  [[ $APPLY -eq 1 && -d "$CONSUMER_DIR/.git" ]] || return 0
  git -C "$CONSUMER_DIR" check-ignore -q .migrate-26.state 2> /dev/null && return 0
  mkdir -p "$CONSUMER_DIR/.git/info"
  printf '/.migrate-26.state\n/.migrate-26.state.tmp\n' >> "$CONSUMER_DIR/.git/info/exclude"
}

# --- Doing and confirming -------------------------------------------------------
# doit <confirm-step|-> <what>: a dry run prints the line and skips (1); --apply
# asks for the named confirmation, then runs (0).
doit() {
  local step="$1" what="$2"
  if [[ $APPLY -eq 0 ]]; then
    if [[ "$step" == "-" ]]; then printf '  DRY   would: %s\n' "$what"; else printf '  DRY   would, after confirm %s: %s\n' "$step" "$what"; fi
    return 1
  fi
  [[ "$step" == "-" ]] || confirm "$step" "$what"
  printf '  RUN   %s\n' "$what"
  return 0
}
confirm() {
  local step="$1" what="$2" answer=""
  in_list "$step" "$YES" && return 0
  [[ $NONINTERACTIVE -eq 0 ]] || refuse "confirm step '$step' ($what) needs --yes-$step"
  printf '  CONFIRM %s\n  Type %s to go on: ' "$what" "$step"
  read -r answer || refuse "no confirmation for '$step'"
  [[ "$answer" == "$step" ]] || refuse "'$step' not confirmed"
}

# --- The server -----------------------------------------------------------------
# srv <command>: runs in the server directory (./ops ssh, or locally with
# --local); the output lands in SRV_OUT and the command's exit code is returned.
# Markers fence it from the dispatcher's banner. SRV_REACHED is 1 only when the
# command ran: without the closing marker nothing is known about the server.
SRV_OUT=""
SRV_REACHED=0
srv() {
  local raw rc
  raw="$(
    if [[ $LOCAL -eq 1 ]]; then
      bash -c "echo __M26_BEGIN__; $SERVER_CD && { $1 ; }; echo \"__M26_RC=\$?\"" 2>&1 || true
    else
      "$OPS" ssh "echo __M26_BEGIN__; $SERVER_CD && { $1 ; }; echo \"__M26_RC=\$?\"" 2>&1 || true
    fi
  )"
  SRV_OUT="$(printf '%s\n' "$raw" | sed -n '/^__M26_BEGIN__$/,/^__M26_RC=/p' | sed '1d;$d')"
  rc="$(printf '%s\n' "$raw" | grep -oE '^__M26_RC=[0-9]+' | tail -1 | cut -d= -f2)"
  [[ -n "$rc" ]] || { SRV_OUT="$raw"; SRV_REACHED=0; return 255; }
  SRV_REACHED=1
  return "$rc"
}
# srv_test <test expression>: 0 true, 1 false; anything else is "cannot tell" and refuses.
srv_test() {
  local rc=0
  srv "test $1" || rc=$?
  [[ $SRV_REACHED -eq 1 && ( $rc -eq 0 || $rc -eq 1 ) ]] || refuse "cannot tell whether 'test $1' holds on the server (the probe failed):"$'\n'"$SRV_OUT"
  return "$rc"
}
srv_or_die() { srv "$1" || refuse "server command failed: $1"$'\n'"$SRV_OUT"; }

# rcon <command>: one bounded RCON call; empty output is silence, not failure.
RCON_OUT=""
rcon_q() {
  [[ "$1" != *"'"* ]] || die "an RCON command must not contain a single quote"
  srv "timeout 30 docker exec $MC_CT rcon-cli '$1' 2>/dev/null" || true
  RCON_OUT="$SRV_OUT"
  RCON_REACHED=$SRV_REACHED
}
RCON_REACHED=0

server_tag() { srv "readlink .stack/current" && printf '%s' "$SRV_OUT" | tail -1 || printf 'unreadable'; }
server_mc() { srv "cat .stack/current/stack/.minecraft-version" && printf '%s' "$SRV_OUT" | tail -1 | tr -d '[:space:]' || printf 'unreadable'; }
# server_on <5|6>: the server's stack is that major (a linked dev stack by its .minecraft-version).
server_on() {
  local tag mc
  tag="$(server_tag)"
  case "$tag" in
    v"$1".*) return 0 ;;
    dev)
      mc="$(server_mc)"
      [[ "$1" == 5 && "$mc" == 1.21.1 ]] && return 0
      [[ "$1" == 6 && "$mc" == 26.3 ]] && return 0
      ;;
  esac
  return 1
}
# mc_probe: MC_STATE is running, stopped, absent or unknown. absent needs a
# listing that succeeded; a failed call, empty or unexpected output is unknown.
MC_STATE=unknown
mc_probe() {
  local rc=0 out
  srv "if docker inspect -f '{{.State.Status}}' $MC_CT 2>/dev/null; then :; else docker ps -a --format '{{.Names}}' >/dev/null && echo __ABSENT__; fi" || rc=$?
  out="$(printf '%s\n' "$SRV_OUT" | sed '/^$/d')"
  MC_STATE=unknown
  [[ $SRV_REACHED -eq 1 && $rc -eq 0 ]] || return 0
  case "$out" in
    running | restarting | paused) MC_STATE=running ;;
    exited | created | dead) MC_STATE=stopped ;;
    __ABSENT__) MC_STATE=absent ;;
  esac
}
# require_mc_stopped: refuses unless mc is known to be stopped or absent.
require_mc_stopped() {
  mc_probe
  case "$MC_STATE" in
    stopped | absent) ok "mc is $MC_STATE" ;;
    running) refuse "mc is running: $1" ;;
    *) refuse "cannot tell whether mc is running (the probe failed or answered '$(printf '%s' "$SRV_OUT" | tail -1)'): $1" ;;
  esac
}
require_nobody_online() {
  local n
  rcon_q "list"
  [[ $RCON_REACHED -eq 1 ]] || refuse "cannot reach the server to ask who is online:"$'\n'"$RCON_OUT"
  n="$(printf '%s' "$RCON_OUT" | grep -oE 'There are [0-9]+' | grep -oE '[0-9]+' | head -1 || true)"
  if [[ -z "$n" ]]; then
    note "RCON is silent: autopaused (normal when empty) or mc is stopped"
  elif [[ "$n" -gt 0 ]]; then
    refuse "$n player(s) online; the migration runs with nobody on"
  else
    ok "nobody online"
  fi
}
no_run_in_progress() {
  [[ $LOCAL -eq 0 ]] || return 0
  local runs busy
  runs="$(cd "$CONSUMER_DIR" && gh run list --limit 3 --json status --jq '.[].status' 2>&1)" || refuse "cannot list the consumer's workflow runs (gh run list): $runs"
  busy="$(printf '%s\n' "$runs" | sed '/^$/d' | grep -vc '^completed$' || true)"
  [[ "${busy:-0}" -eq 0 ]] || refuse "a workflow run is in progress in the consumer repo (gh run list --limit 3); wait for it"
  ok "no workflow run in progress"
}

# wait_snapshot <since-epoch>: bounded look for restic's "snapshot <id> saved"
# in the backup sidecar's log; prints the id.
wait_snapshot() {
  local since="$1" i id=""
  for ((i = 1; i <= ATTEMPTS; i++)); do
    srv "docker logs $BACKUP_CT --since $since --tail 200 2>&1" || true
    id="$(printf '%s\n' "$SRV_OUT" | grep -oE 'snapshot [0-9a-f]{8,} saved' | tail -1 | awk '{ print $2 }')"
    [[ -n "$id" ]] && { printf '%s' "$id"; return 0; }
    [[ $i -lt $ATTEMPTS ]] && sleep "$INTERVAL"
  done
  return 1
}
trigger_backup() {
  if [[ $LOCAL -eq 1 ]]; then
    srv_or_die "docker restart $BACKUP_CT >/dev/null"
  else
    srv_or_die "bash .stack/current/stack/scripts/backup-now.sh --quiet"
  fi
}
# take_snapshot <key>: one restic snapshot, recorded under <key>; resumes a
# triggered one instead of triggering twice.
take_snapshot() {
  local key="$1" since id count
  id="$(st_get "$key")"
  if [[ -n "$id" ]] && srv "docker exec -u 1000 $BACKUP_CT restic snapshots $id >/dev/null 2>&1"; then
    ok "restic snapshot $id (recorded, present)"
    return 0
  fi
  since="$(st_get "${key}_since")"
  if [[ -z "$since" ]] || [[ $(($(date +%s) - since)) -gt 3600 ]]; then
    since="$(date +%s)"
    doit - "trigger a backup ($BACKUP_CT restart)" || return 0
    srv "docker ps --format '{{.Names}}'" || true
    printf '%s\n' "$SRV_OUT" | grep -qx "$BACKUP_CT" || refuse "$BACKUP_CT is not running"
    trigger_backup
    st_set "${key}_since" "$since"
  else
    note "a backup was triggered at $since; looking for its snapshot"
  fi
  id="$(wait_snapshot "$since")" || later "no 'snapshot ... saved' in $BACKUP_CT's log yet ($ATTEMPTS snapshots, ${INTERVAL}s apart)"
  srv "docker exec -u 1000 $BACKUP_CT restic ls $id | grep -c /region/" || true
  count="$(printf '%s' "$SRV_OUT" | tail -1)"
  [[ "$count" =~ ^[0-9]+$ && "$count" -gt 0 ]] || refuse "snapshot $id lists no region files"
  st_set "$key" "$id"
  ok "restic snapshot $id ($count region files)"
}

# --- Owner steps and order -------------------------------------------------------
owner_text() {
  case "$1" in
    release) say "  A3  Merge the switch PR; gh workflow run release.yml -f version=v6.0.0;
      git fetch origin '+refs/tags/v6:refs/tags/v6'. The breaking-change note names the
      new world and the Bedrock port (checklist.md § Owner only)." ;;
    cloud-firewall) say "  C7  Hetzner only: hcloud firewall add-rule <SERVER_NAME>-fw --direction in --protocol udp
      --port $BEDROCK --source-ips 0.0.0.0/0 --source-ips ::/0 --description \"Bedrock (Geyser)\",
      and the same with --protocol tcp. Another provider: open $BEDROCK/udp and /tcp in its firewall, if it has one." ;;
    secrets) say "  D9  In the consumer's production environment: FLOODGATE_KEY_B64
      (base64 < key.pem | tr -d '\\n' | gh secret set FLOODGATE_KEY_B64 --env production), and
      STACK_READ_TOKEN while the template repository is private. Without the key Floodgate
      generates one at first boot into data/config/floodgate/key.pem: copy it off and set
      the secret from it before the next full deploy." ;;
    launch) say "  E16 A vanilla 26.3 client, the pack and a Bedrock device each join and cross into three
      dimensions including The Aviary; five players hold 19+ TPS (checklist.md § Owner only)." ;;
  esac
}
owner_ok() { [[ "$(st_get "owner_$1")" == 1 ]] || in_list "$1" "$OWNER_DONE"; }
# owner_gate <step>: an owner step that must be done before this phase goes on.
owner_gate() {
  owner_ok "$1" && { ok "owner step $1 done"; return 0; }
  head_line "Owner step due: $1"
  owner_text "$1"
  say "  The script never does this. When it is done: --owner-done $1 --apply"
  exit 3
}
owner_due() {
  head_line "Next, an owner step: $1"
  owner_text "$1"
  say "  Record it with --owner-done $1 --apply on the next phase."
}
need_phase() { [[ -n "$(st_get "done_$1")" ]] || refuse "phase '$1' is not done (run it first; '$0 status')"; }
finish() { st_set "done_$PHASE" "$(date +%s)"; [[ $APPLY -eq 1 ]] && say "" && say "  $PHASE: done" || { say ""; say "  dry run: nothing changed; add --apply to do it"; }; }

# --- Phases ------------------------------------------------------------------------
phase_preflight() {
  head_line "A1 Preflight (safe)"
  [[ -x "$OPS" && -x "$DEV" ]] || refuse "$CONSUMER_DIR has no ./ops and ./dev: run from the consumer repo or pass --consumer"
  [[ "$TARGET" == v6.* ]] || refuse "the target is '${TARGET:-unknown}', not a v6 release: run this script from the v6 bundle or pass --target"
  ok "target $TARGET"
  local cur
  cur="$(readlink "$CONSUMER_DIR/.stack/current" 2> /dev/null || true)"
  case "$cur" in
    v6.*) refuse "this repo's .stack/current is $cur, but ./ops must run the server's v5 scripts until the consumer phase: ./dev rollback <the v5 release>" ;;
    "") refuse "this repo has no .stack/current: ./dev pull first" ;;
  esac
  ok "local .stack/current is $cur"
  if [[ -f "$CONSUMER_DIR/.env" ]] && grep -qE '^STACK_VERSION=' "$CONSUMER_DIR/.env"; then
    note ".env carries the deprecated STACK_VERSION key; a v5 ./dev prefers it to .stack-version: delete that line before the consumer phase"
  fi
  server_on 5 || refuse "the server's bundle is $(server_tag) ($(server_mc)), not a 1.21.1 v5 one"
  ok "server bundle $(server_tag)"
  no_run_in_progress
  if [[ $LOCAL -eq 1 ]]; then
    note "--local: ./ops doctor is a production check; skipped"
  else
    local rc=0 log
    log="$(mktemp)"
    "$OPS" doctor > "$log" 2>&1 || rc=$?
    tail -n 15 "$log" | sed 's/^/    /'
    rm -f "$log"
    if [[ $rc -ne 0 ]]; then
      doit doctor "go on past ./ops doctor's FAIL lines" || true
    else
      ok "./ops doctor"
    fi
  fi
  require_nobody_online
  srv_or_die "df -Pk . | awk 'NR==2 { print \$4 }'; du -sk data/world | cut -f1"
  local free need
  free="$(printf '%s\n' "$SRV_OUT" | sed -n 1p)"
  need="$(printf '%s\n' "$SRV_OUT" | sed -n 2p)"
  [[ "$free" =~ ^[0-9]+$ && "$need" =~ ^[0-9]+$ ]] || refuse "cannot read the free disk and the size of data/world: $SRV_OUT"
  [[ "$free" -gt "$need" ]] || refuse "the cold world tar needs about ${need} KiB free; the server has ${free} KiB"
  ok "disk: ${free} KiB free, data/world ${need} KiB"
  ensure_state_ignored
  finish
}

phase_pin() {
  need_phase preflight
  head_line "A2 Pin the consumer to its running release (confirm)"
  server_on 5 || refuse "the server's bundle is $(server_tag), not v5"
  local tag pin
  tag="$(server_tag)"
  pin="$(grep -vE '^[[:space:]]*(#|$)' "$CONSUMER_DIR/.stack-version" 2> /dev/null | head -1 | tr -d '[:space:]' || true)"
  if [[ $LOCAL -eq 1 && "$tag" == dev ]]; then
    note "--local: the stack is linked (dev); there is no release to pin"
  elif [[ "$pin" == "$tag" ]]; then
    ok ".stack-version is $tag"
  else
    no_run_in_progress
    if doit pin "write .stack-version $tag, commit it$([[ $LOCAL -eq 1 ]] || echo ' and push (a pull-tier deploy of the same release)')"; then
      backup "$CONSUMER_DIR/.stack-version" > /dev/null
      printf '%s\n' "$tag" > "$CONSUMER_DIR/.stack-version"
      git -C "$CONSUMER_DIR" add -- .stack-version
      git -C "$CONSUMER_DIR" commit -q -m "chore: pin the stack to $tag before the v6 migration" -- .stack-version
      [[ $LOCAL -eq 1 ]] || push_and_resolve
    fi
  fi
  if [[ $LOCAL -eq 0 ]]; then
    if (cd "$CONSUMER_DIR" && gh variable list --json name --jq '.[].name' 2> /dev/null) | grep -qx STACK_VERSION; then
      if doit pin "gh variable delete STACK_VERSION (the repository variable a v5 deploy.yml reads)"; then
        (cd "$CONSUMER_DIR" && gh variable delete STACK_VERSION) || refuse "gh variable delete STACK_VERSION failed"
      fi
    else
      ok "no STACK_VERSION repository variable"
    fi
  fi
  st_set pinned "$tag"
  finish
  owner_due release
}

# push_and_resolve: push HEAD, then a bounded lookup of its deploy run by sha.
push_and_resolve() {
  local sha i id=""
  sha="$(git -C "$CONSUMER_DIR" rev-parse HEAD)"
  git -C "$CONSUMER_DIR" push -q || refuse "git push failed"
  for ((i = 1; i <= ATTEMPTS; i++)); do
    id="$(cd "$CONSUMER_DIR" && gh run list --workflow deploy.yml --commit "$sha" --json databaseId --jq '.[0].databaseId' 2> /dev/null || true)"
    [[ -n "$id" && "$id" != null ]] && break
    id=""
    [[ $i -lt $ATTEMPTS ]] && sleep "$INTERVAL"
  done
  if [[ -z "$id" ]]; then
    note "no deploy run for $sha yet; check once: gh run list --workflow deploy.yml --commit $sha --json databaseId,status"
  else
    (cd "$CONSUMER_DIR" && gh run view "$id" --json status,conclusion 2> /dev/null | sed 's/^/    /') || true
    note "deploy run $id; check it once with: gh run view $id --json status,conclusion"
  fi
}

phase_archive() {
  need_phase pin
  owner_gate release
  head_line "B Archive the 1.21.1 world (p9-archive.md)"
  server_on 5 || refuse "the server's bundle is $(server_tag), not v5: the world may already have booted on 26.3"
  if [[ $LOCAL -eq 0 && "$(st_get pinned)" != "$(server_tag)" ]]; then
    refuse "the server runs $(server_tag) but the pin is $(st_get pinned): run pin again"
  fi
  no_run_in_progress

  head_line "B4 Restic snapshot (safe)"
  take_snapshot snapshot

  head_line "B5 Last map render and its tar (safe)"
  local day map
  day="$(date +%Y%m%d)"
  map="$(st_get map_tar)"
  if [[ -n "$map" ]] && srv "test -s $map"; then
    ok "map tar $map"
  elif ! srv "docker inspect $MAP_CT >/dev/null 2>&1"; then
    note "no $MAP_CT container: no map to keep"
  elif [[ -z "$(st_get map_since)" ]]; then
    if doit - "force a render pass (docker restart $MAP_CT)"; then
      srv_or_die "docker restart $MAP_CT >/dev/null"
      st_set map_since "$(date +%s)"
      later "the render pass is running; './ops map status' until it is idle"
    fi
  else
    srv "docker logs $MAP_CT --tail 5 2>&1" || true
    printf '%s\n' "$SRV_OUT" | sed 's/^/    /'
    if doit map "the render pass above is idle; tar data/unmined-web to backups/map-1.21.1-$day.tar.gz"; then
      srv_or_die "mkdir -p backups && tar czf backups/map-1.21.1-$day.tar.gz data/unmined-web"
      st_set map_tar "backups/map-1.21.1-$day.tar.gz"
      ok "backups/map-1.21.1-$day.tar.gz"
    fi
  fi

  head_line "B6 Cold world tar, mc left stopped (confirm)"
  local tar sum
  tar="$(st_get world_tar)"
  sum="$(st_get world_sha)"
  if [[ -n "$tar" && -n "$sum" ]] && srv "sha256sum $tar | cut -d' ' -f1" && [[ "$(printf '%s' "$SRV_OUT" | tail -1)" == "$sum" ]]; then
    ok "world tar $tar, sha256 $sum (recorded, matches)"
  else
    tar="backups/world-1.21.1-$day.tar.gz"
    if doit stop-mc "stop mc ($STOP_MC) and leave it stopped, then tar data/world data/config to $tar"; then
      srv_test "-f data/world/level.dat" || refuse "no data/world/level.dat on the server: there is no 1.21.1 world to archive"
      ! srv_test "-e $tar" || refuse "$tar exists and is not the recorded tar: move it aside, then run archive again"
      require_nobody_online
      srv_or_die "$STOP_MC >/dev/null 2>&1"
      local i
      for ((i = 1; i <= ATTEMPTS; i++)); do
        mc_probe
        [[ "$MC_STATE" == running ]] || break
        [[ $i -lt $ATTEMPTS ]] && sleep "$INTERVAL"
      done
      [[ "$MC_STATE" != running ]] || later "mc is still running after the stop"
      require_mc_stopped "the cold tar is taken only with mc stopped"
      srv_or_die "mkdir -p backups && tar czf $tar data/world data/config && sha256sum $tar | cut -d' ' -f1"
      sum="$(printf '%s' "$SRV_OUT" | tail -1)"
      [[ "$sum" =~ ^[0-9a-f]{64}$ ]] || refuse "no sha256 for $tar"
      st_set world_tar "$tar"
      st_set world_sha "$sum"
      ok "$tar sha256 $sum"
    fi
  fi
  if [[ -n "$(st_get world_tar)" ]]; then
    say ""
    say "  Copy it off the host and check it (the clean phase asks for this sum):"
    if [[ $LOCAL -eq 1 ]]; then
      say "    cp '$CONSUMER_DIR/$(st_get world_tar)' <somewhere else> && shasum -a 256 <the copy>"
    else
      say "    scp -i ~/.ssh/\${BRAND_SLUG:+\${BRAND_SLUG}_}mc_deploy_key deploy@\"\$DROPLET_HOST\":server/$(st_get world_tar) ./"
      say "    shasum -a 256 $(basename "$(st_get world_tar)")"
    fi
    say "  expected $(st_get world_sha)"
  fi
  finish
}

phase_host() {
  need_phase archive
  head_line "C7 Bedrock firewall (confirm)"
  if [[ $LOCAL -eq 1 ]]; then
    note "--local: no ufw and no cloud firewall on a local stack; host skipped"
    finish
    return 0
  fi
  local rules6 line4 line6
  line4="$(grep -E 'hashlimit-name mc-bedrock ' "$SCRIPT_DIR/harden.sh" | head -1 | sed "s/\${BEDROCK_PORT:-19132}/$BEDROCK/")"
  line6="$(grep -E 'hashlimit-name mc-bedrock6 ' "$SCRIPT_DIR/harden.sh" | head -1 | sed "s/\${BEDROCK_PORT:-19132}/$BEDROCK/")"
  [[ -n "$line4" && -n "$line6" ]] || refuse "harden.sh beside this script has no mc-bedrock lines"
  srv "sudo ufw status" || true
  local status="$SRV_OUT" proto
  for proto in tcp udp; do
    if printf '%s\n' "$status" | grep -qE "^${BEDROCK}/${proto}[[:space:]]+ALLOW"; then
      ok "ufw allows $BEDROCK/$proto"
    elif doit firewall "sudo ufw allow $BEDROCK/$proto"; then
      srv_or_die "sudo ufw allow $BEDROCK/$proto comment 'Geyser Bedrock'"
    fi
  done
  local changed=0 file name line
  for rules6 in 0 1; do
    if [[ $rules6 -eq 0 ]]; then file="$UFW_DIR/before.rules" name=mc-bedrock line="$line4"; else file="$UFW_DIR/before6.rules" name=mc-bedrock6 line="$line6"; fi
    srv_test "-f $file" || { note "no $file"; continue; }
    srv_or_die "grep -c 'hashlimit-name $name ' $file || true"
    if [[ "$(printf '%s' "$SRV_OUT" | tail -1)" -ge 1 ]]; then
      ok "$file has the $name rate limit"
      continue
    fi
    srv_or_die "grep -c '^# END RATE-LIMIT' $file || true"
    [[ "$(printf '%s' "$SRV_OUT" | tail -1)" -eq 1 ]] || refuse "$file has no single '# END RATE-LIMIT' line: add the $name line by hand (harden.sh)"
    if doit firewall "back up $file, add the $name line before '# END RATE-LIMIT'"; then
      srv_or_die "sudo cp -p $file $file.bak.\$(date +%Y%m%d-%H%M%S) && sudo sed -i '/^# END RATE-LIMIT/i $line' $file && test \"\$(grep -c 'hashlimit-name $name ' $file)\" -eq 1"
      changed=1
    fi
  done
  if [[ $changed -eq 1 ]]; then
    srv "sudo ufw reload" || refuse "ufw reload failed: restore the newest $UFW_DIR/before*.rules.bak.* and reload"
    ok "ufw reloaded"
  fi
  finish
  owner_due cloud-firewall
}

phase_clean() {
  need_phase host
  [[ $LOCAL -eq 1 ]] || owner_gate cloud-firewall
  head_line "C8 Remove what 26.3 would misread from data/ (confirm)"
  local tar sum snap
  tar="$(st_get world_tar)"
  sum="$(st_get world_sha)"
  snap="$(st_get snapshot)"
  # Every check is made on the server now; the state file only says where to look.
  require_mc_stopped "the world is deleted only with mc stopped"
  [[ -n "$tar" && -n "$sum" ]] || refuse "no cold world tar is recorded: run archive"
  srv "test -s $tar && sha256sum $tar | cut -d' ' -f1" || refuse "the world tar $tar is not on the server"
  [[ "$(printf '%s' "$SRV_OUT" | tail -1)" == "$sum" ]] || refuse "$tar no longer matches its recorded sha256 $sum"
  ok "world tar $tar matches $sum"
  [[ -n "$OFFHOST_SHA" ]] || refuse "--offhost-sha <sum> is required: the sha256 of the copy off the host"
  [[ "$OFFHOST_SHA" == "$sum" ]] || refuse "--offhost-sha $OFFHOST_SHA is not the world tar's $sum"
  ok "the off-host copy matches"
  [[ -n "$snap" ]] || refuse "no restic snapshot is recorded: run archive"
  srv "docker exec -u 1000 $BACKUP_CT restic snapshots $snap >/dev/null 2>&1" || refuse "restic snapshot $snap is not in the repository"
  ok "restic snapshot $snap"
  server_on 5 || refuse "the server's bundle is $(server_tag), not v5"
  ok "server bundle $(server_tag)"

  local paths="${CLEAN_PATHS[*]}"
  [[ $TIDY -eq 0 ]] || paths="$paths ${TIDY_PATHS[*]}"
  srv_or_die "for p in $paths; do if [ -e \"\$p\" ]; then echo \"\$p\"; fi; done"
  if [[ -z "$SRV_OUT" ]]; then
    ok "nothing left to remove"
  else
    printf '%s\n' "$SRV_OUT" | sed 's/^/    present  /'
    if doit clean "${SUDO:+$SUDO }rm -rf the $(printf '%s\n' "$SRV_OUT" | wc -l | tr -d ' ') paths above"; then
      require_mc_stopped "the world is deleted only with mc stopped"
      srv_or_die "${SUDO:+$SUDO }rm -rf -- $paths"
      srv_or_die "for p in $paths; do if [ -e \"\$p\" ]; then echo \"\$p\"; fi; done"
      [[ -z "$SRV_OUT" ]] || refuse "still present after rm: $SRV_OUT"
      ok "removed"
    fi
  fi
  [[ $TIDY -eq 1 ]] || note "tidy-up paths kept (add --tidy to remove them): ${TIDY_PATHS[*]}"
  finish
  owner_due secrets
}

phase_consumer() {
  need_phase clean
  owner_gate secrets
  head_line "D10 The consumer patch (confirm)"
  [[ "$TARGET" == v6.* ]] || refuse "the target is '${TARGET:-unknown}', not v6"
  require_mc_stopped "the v6 deploy must find mc stopped and no 1.21.1 world"
  ! srv_test "-e data/world" || refuse "data/world exists on the server: run clean"
  ok "no data/world"
  server_on 5 || note "the server's bundle is $(server_tag): the v6 deploy has started or run"
  if [[ -f "$CONSUMER_DIR/.env" ]] && grep -qE '^STACK_VERSION=' "$CONSUMER_DIR/.env"; then
    refuse ".env carries STACK_VERSION, which a v5 ./dev prefers to .stack-version: delete that line"
  fi
  no_run_in_progress
  local cd_dir="$CONSUMER_DIR/overlay/config/custom-dimensions" pin
  pin="$(grep -vE '^[[:space:]]*(#|$)' "$CONSUMER_DIR/.stack-version" 2> /dev/null | head -1 | tr -d '[:space:]' || true)"
  if [[ "$pin" == "$TARGET" ]]; then
    ok ".stack-version is $TARGET"
  elif doit - "write .stack-version $TARGET"; then
    backup "$CONSUMER_DIR/.stack-version" > /dev/null
    printf '%s\n' "$TARGET" > "$CONSUMER_DIR/.stack-version"
  fi
  if [[ $LOCAL -eq 1 && -n "$LINK_DIR" ]]; then
    doit - "./dev link $LINK_DIR (the switched checkout)" && (cd "$CONSUMER_DIR" && "$DEV" link "$LINK_DIR")
    note "--link: the scaffold stays as it is (./dev update would unlink the stack)"
  elif [[ $LOCAL -eq 1 ]]; then
    doit - "STACK_VERSION=$TARGET ./dev update" && (cd "$CONSUMER_DIR" && STACK_VERSION="$TARGET" "$DEV" update)
  else
    doit - "STACK_VERSION=$TARGET ./dev update --scaffold-only (the v6 dev, ops, workflows)" && (cd "$CONSUMER_DIR" && STACK_VERSION="$TARGET" "$DEV" update --scaffold-only)
  fi
  if [[ $APPLY -eq 1 && ( $LOCAL -eq 0 || -z "$LINK_DIR" ) ]]; then
    local wf="$CONSUMER_DIR/.github/workflows/deploy.yml" want
    for want in 'deploy-reusable.yml@v6' 'STACK_READ_TOKEN' 'FLOODGATE_KEY_B64'; do
      grep -q "$want" "$wf" 2> /dev/null || refuse "deploy.yml has no $want after ./dev update: the scaffold did not sync"
    done
    ok "deploy.yml calls deploy-reusable.yml@v6 with both secrets"
  fi
  local viewer="" g
  if [[ -d "$cd_dir/dimensions" ]]; then
    for g in $SEED_VIEWER_GLOBS; do
      viewer="$viewer$(cd "$cd_dir/dimensions" && find . -maxdepth 1 -type f -name "$g" | sed 's#^\./##' | sort)"$'\n'
    done
  fi
  viewer="$(printf '%s' "$viewer" | sed '/^$/d')"
  if [[ -n "$viewer" ]]; then
    if doit - "git rm the $(printf '%s\n' "$viewer" | wc -l | tr -d ' ') 1.21.1 seed-viewer files in overlay/config/custom-dimensions/dimensions/"; then
      printf '%s\n' "$viewer" | while IFS= read -r g; do git -C "$CONSUMER_DIR" rm -q -- "overlay/config/custom-dimensions/dimensions/$g"; done
    fi
  else
    ok "no seed-viewer files in the overlay"
  fi
  if [[ -d "$cd_dir" ]] && [[ -n "$(find "$cd_dir" -type f 2> /dev/null)" ]]; then
    note "kept in overlay/config/custom-dimensions/ (a v2 override is staged; a 1.21.1 file is named in the deploy log):"
    (cd "$cd_dir" && find . -type f | sed 's#^\./#    #')
  fi
  local p add=""
  for p in $SCAFFOLD_PATHS; do [[ -e "$CONSUMER_DIR/$p" ]] && add="$add $p"; done
  if [[ $APPLY -eq 1 ]]; then
    # shellcheck disable=SC2086 # add is a list of repo paths without spaces
    git -C "$CONSUMER_DIR" add -- $add
    if ! git -C "$CONSUMER_DIR" diff --cached --quiet; then
      git -C "$CONSUMER_DIR" commit -q -m "feat!: move to the v6 stack (Minecraft 26.3)"
      ok "committed $(git -C "$CONSUMER_DIR" rev-parse --short HEAD)"
    else
      ok "nothing to commit"
    fi
  fi
  if [[ $LOCAL -eq 1 ]]; then
    if doit consumer "./dev refresh-config, then ./dev up (mc starts on an empty world on the switched stack)"; then
      (cd "$CONSUMER_DIR" && "$DEV" refresh-config && "$DEV" up)
    fi
  else
    local ahead
    ahead="$(git -C "$CONSUMER_DIR" rev-list --count '@{u}..HEAD' 2> /dev/null || echo 1)"
    if [[ "$ahead" -eq 0 ]]; then
      ok "the v6 commit is pushed"
    elif doit consumer "git push: the full v6 deploy starts mc on an empty world (allow 15 minutes and more)"; then
      push_and_resolve
    fi
  fi
  finish
}

phase_verify() {
  need_phase consumer
  head_line "E11 Health (safe)"
  server_on 6 || later "the server's bundle is $(server_tag), not v6: the deploy has not finished"
  ok "server bundle $(server_tag)"
  if [[ $LOCAL -eq 0 ]]; then
    "$OPS" doctor 2>&1 | tail -n 15 | sed 's/^/    /' || true
  fi
  rcon_q "customdim list"
  local summary
  summary="$(printf '%s\n' "$RCON_OUT" | grep -oE '[0-9]+ dimensions: [0-9]+ base, [0-9]+ lazy, [0-9]+ open' | head -1 || true)"
  [[ -n "$summary" ]] || later "customdim list gave no level index (silent: autopaused or still booting)"
  ok "$summary"
  [[ "$summary" == "82 dimensions: 4 base, 78 lazy"* ]] || note "the notes expect 82 dimensions: 4 base, 78 lazy"
  rcon_q "customdim audit"
  printf '%s\n' "$RCON_OUT" | head -20 | sed 's/^/    audit  /'
  srv "docker logs $MC_CT --tail 200 2>&1" || true
  local errors
  errors="$(printf '%s\n' "$SRV_OUT" | grep -c 'ERROR' || true)"
  note "$errors ERROR line(s) in the last 200 of mc's log"
  printf '%s\n' "$SRV_OUT" | grep 'ERROR' | tail -5 | sed 's/^/    /' || true

  local blocking=0
  head_line "E12 The world seed (confirm; open fact, measured here)"
  local live def
  rcon_q "seed"
  live="$(printf '%s\n' "$RCON_OUT" | grep -oE '\[-?[0-9]+\]' | tr -d '[]' | head -1 || true)"
  srv "grep -oE '\"seed\"[[:space:]]*:[[:space:]]*-?[0-9]+' data/config/custom-dimensions/dimensions/overworld.json | head -1 | grep -oE -- '-?[0-9]+\$'" || true
  def="$(printf '%s' "$SRV_OUT" | tail -1)"
  if [[ -z "$live" || -z "$def" ]]; then
    later "no seed to compare (rcon seed: '${live}', overworld.json seed: '${def}')"
  elif [[ "$live" == "$def" ]]; then
    ok "the world seed $live is the overworld definition's"
  else
    say "  MISMATCH  the world generates from $live; the overworld definition says $def."
    say "  Before anyone joins: ./ops reset-seed $def  (this script never runs it; then run verify again)"
    blocking=1
  fi

  head_line "E13 Game rules and spawn (safe)"
  local rule want got ow_phantoms=""
  for rule in "doInsomnia false" "logAdminCommands true"; do
    # shellcheck disable=SC2046,SC2086 # two words: the rule and its value
    set -- $(mc_game_rule $rule 26.3)
    rcon_q "gamerule $1"
    got="$(printf '%s\n' "$RCON_OUT" | grep -oE '(true|false|[0-9]+)[[:space:]]*$' | tail -1 | tr -d '[:space:]' || true)"
    want="$2"
    [[ "$1" == spawn_phantoms || "$1" == doInsomnia ]] && ow_phantoms="$got|$1"
    if [[ -z "$got" ]]; then
      later "gamerule $1 gave no answer"
    elif [[ "$got" == "$want" ]]; then
      ok "gamerule $1 $got"
    else
      say "  MISMATCH  gamerule $1 is $got, mc_game_rules says $want: the deploy did not set it"
      blocking=1
    fi
  done
  local ns slug
  srv "sed -n 's/.*\"namespace\"[[:space:]]*:[[:space:]]*\"\\([a-z0-9_.-]*\\)\".*/\\1/p' data/config/custom-dimensions/dimensions/settings.json 2>/dev/null | head -1; echo; ls data/config/custom-dimensions/dimensions/ 2>/dev/null" || true
  ns="$(printf '%s\n' "$SRV_OUT" | sed -n 1p)"
  ns="${ns:-adventure}"
  slug="$LAZY_SLUG"
  if [[ -z "$slug" ]]; then
    slug="$(printf '%s\n' "$SRV_OUT" | sed 1d | grep -E '\.json$' | sed 's/\.json$//' | grep -vxE "settings|${BASE_SLUGS// /|}" | head -1 || true)"
  fi
  if [[ -z "$slug" ]]; then
    note "no lazy dimension found to read game rules in"
  else
    local rname="${ow_phantoms#*|}" ow="${ow_phantoms%%|*}" lazy
    rcon_q "customdim activate $slug"
    printf '%s\n' "$RCON_OUT" | head -3 | sed 's/^/    /'
    rcon_q "execute in $ns:$slug run gamerule $rname"
    lazy="$(printf '%s\n' "$RCON_OUT" | grep -oE '(true|false|[0-9]+)[[:space:]]*$' | tail -1 | tr -d '[:space:]' || true)"
    if [[ -z "$lazy" ]]; then
      note "open fact: $ns:$slug gave no answer for gamerule $rname; read it by hand"
    elif [[ "$lazy" == "$ow" ]]; then
      ok "open fact answered: the lazy level $ns:$slug follows the overworld ($rname $lazy)"
    else
      say "  FINDING  the lazy level $ns:$slug has $rname $lazy, the overworld $ow: lazy levels do not follow the overworld's game rules"
    fi
  fi
  [[ $blocking -eq 0 ]] || refuse "fix the mismatches above, then run verify again"
  finish
  head_line "E14 Pre-generation (confirm; not scripted)"
  say "  Not scripted. One dimension at a time, base worlds first, idle-tasks stopped:"
  say "  $SCRIPT_DIR/../docs/migrate-to-26.md § Pre-generation."
  say "  Then: first-backup."
}

phase_first_backup() {
  need_phase verify
  head_line "E15 First 26.3 backup (safe)"
  server_on 6 || refuse "the server's bundle is $(server_tag), not v6"
  take_snapshot first_snapshot
  finish
  if owner_ok launch; then say "  owner step launch done: the migration is complete"; else owner_due launch; fi
}

phase_plan() {
  say "Migration to the v6 stack (26.3), in order. safe = read-only or reversible; confirm = asks"
  say "for its name (or --yes-<step>); owner = printed, never run (--owner-done <step>)."
  say "
  preflight    A1   safe     gh run list, ./ops doctor, RCON list, disk for the cold tar, server on v5
  pin          A2   confirm  .stack-version = the server's release, commit, push; gh variable delete STACK_VERSION  [--yes-pin]
  (owner)      A3   owner    release v6.0.0                                                         [--owner-done release]
  archive      B4   safe     restic snapshot via the backup sidecar, id recorded and its region files listed
               B5   safe     map render pass, then tar data/unmined-web                             [--yes-map]
               B6   confirm  stop mc and leave it stopped, tar data/world data/config, sha256        [--yes-stop-mc]
  host         C7   confirm  ufw allow $BEDROCK/tcp+udp, the mc-bedrock lines in before*.rules, reload [--yes-firewall]
  (owner)      C7   owner    the cloud firewall (Hetzner)                                           [--owner-done cloud-firewall]
  clean        C8   confirm  checks: mc stopped, tar sha, --offhost-sha, snapshot, v5; then rm      [--yes-clean]
  (owner)      D9   owner    FLOODGATE_KEY_B64, STACK_READ_TOKEN                                     [--owner-done secrets]
  consumer     D10  confirm  .stack-version $TARGET, ./dev update, seed-viewer files out, commit, push [--yes-consumer]
  verify       E11  safe     bundle v6, ./ops doctor, customdim list and audit, mc log errors
               E12  confirm  rcon seed against the overworld definition's seed (reports; never reset-seed)
               E13  safe     game rules; one lazy level's game rules (reports)
  (pointer)    E14  confirm  pre-generation: docs/migrate-to-26.md § Pre-generation
  first-backup E15  safe     restic snapshot of the 26.3 world
  (owner)      E16  owner    launch                                                                  [--owner-done launch]"
  say ""
  say "clean removes:"
  printf '    %s\n' "${CLEAN_PATHS[@]}"
  say "clean --tidy also removes:"
  printf '    %s\n' "${TIDY_PATHS[@]}"
}

phase_status() {
  local p next="" s
  say "State: $STATE"
  for p in $PHASES; do
    s="$(st_get "done_$p")"
    if [[ -n "$s" ]]; then say "  done     $p"; else say "  pending  $p"; [[ -n "$next" ]] || next="$p"; fi
  done
  for p in $OWNER_STEPS; do
    if [[ "$(st_get "owner_$p")" == 1 ]]; then say "  done     owner $p"; else say "  pending  owner $p"; fi
  done
  for s in snapshot world_tar world_sha map_tar first_snapshot; do
    [[ -z "$(st_get "$s")" ]] || say "  $s: $(st_get "$s")"
  done
  say "Next: ${next:-nothing (owner launch, then the migration is complete)}"
}

# --- Main ------------------------------------------------------------------------------
[[ $APPLY -eq 1 ]] || in_list "$PHASE" "plan status" || say "DRY RUN (add --apply to change anything)"
[[ $LOCAL -eq 0 ]] || say "LOCAL: $CONSUMER_DIR (compose local profile; no ssh, no ufw, no push)"
for o in $OWNER_DONE; do
  [[ $APPLY -eq 1 ]] || { note "--owner-done $o is recorded with --apply"; continue; }
  st_set "owner_$o" 1
done
case "$PHASE" in
  plan) phase_plan ;;
  status) phase_status ;;
  preflight) phase_preflight ;;
  pin) phase_pin ;;
  archive) phase_archive ;;
  host) phase_host ;;
  clean) phase_clean ;;
  consumer) phase_consumer ;;
  verify) phase_verify ;;
  first-backup) phase_first_backup ;;
esac
