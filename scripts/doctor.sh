#!/usr/bin/env bash
# doctor.sh - One-shot production health triage. Run from your Mac (or CI).
#
# One SSH round-trip runs the full checklist server-side and reports
# PASS/WARN/FAIL per item (same pattern as preflight-check.sh, but for the
# RUNNING server rather than credentials). Checks, in order:
#
#   - deployed commit vs origin/main (drift = a push that never deployed)
#   - stashed pre-deploy hotfixes (deploy.sh stashes silently - review them)
#   - disk usage (WARN >=80%, FAIL >=90%) + biggest data/ consumers
#   - every expected container: running, healthy, restart count
#   - RCON: player list + spark TPS/MSPT/memory (silence + healthy mc =
#     autopaused, which is normal when empty - reported as INFO, not failure)
#   - last restic snapshot age (FAIL >48h, WARN >26h vs the 12h schedule)
#   - Discord slash-command registry (guild must have register/unregister/mc)
#   - modpack mirror populated (DIST_DIR/mods, defaults to modpack-dist/mods)
#   - kuma-init exit code, fail2ban jail bans, recent mc log errors
#     (filtered for known-harmless mod noise)
#
# Exit codes: 0 = no failures, 1 = at least one FAIL (WARNs don't fail the
# run; the daily health.yml workflow alerts Discord on exit 1).
#
# Usage:
#   ./scripts/doctor.sh                 # uses DROPLET_HOST from .env
#   ./scripts/doctor.sh --threads       # + JVM thread dump: what is the main
#                                       #   thread doing, and is it progressing?
#                                       #   (SIGQUIT is non-invasive: the JVM
#                                       #   prints the dump and carries on)
#   DOCTOR_SSH_KEY=~/.ssh/deploy_key DROPLET_HOST=1.2.3.4 ./scripts/doctor.sh   # CI
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
SSH_KEY="${DOCTOR_SSH_KEY:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"

THREADS=0
for arg in "$@"; do
  [[ "$arg" == "--threads" ]] && THREADS=1
done

PASS=0
WARN=0
FAIL=0
SERVER_SHA=""

report() {
  local level="$1" msg="$2"
  case "$level" in
    OK)
      echo -e "  ${GREEN}✓${RESET} $msg"
      PASS=$((PASS + 1))
      ;;
    WARN)
      echo -e "  ${YELLOW}!${RESET} $msg"
      WARN=$((WARN + 1))
      ;;
    FAIL)
      echo -e "  ${RED}✗${RESET} $msg"
      FAIL=$((FAIL + 1))
      ;;
    SHA)
      SERVER_SHA="$msg"
      ;;
    *)
      echo -e "  ${BLUE}·${RESET} $msg"
      ;;
  esac
}

echo -e "\n${BOLD}Production doctor - ${DROPLET_HOST}${RESET}"
echo "=============================================="

# Everything below runs in ONE ssh session on the server, emitting
# "LEVEL<TAB>message" lines that the local loop colours and counts.
# The heredoc is quoted: nothing here expands locally.
SERVER_DIR="server"
REMOTE_OUTPUT=$(ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes \
  "${DEPLOY_USER}@${DROPLET_HOST}" bash -s -- "$SERVER_DIR" "$THREADS" << 'REMOTE' 2> /dev/null
set -u
_server_dir="$1"; cd ~/${_server_dir} || { printf 'FAIL\trepo not found at ~/%s\n' "$_server_dir"; exit 0; }
_threads="${2:-0}"
res() { printf '%s\t%s\n' "$1" "$2"; }

# --- secrets (needed for restic + Discord checks; never printed) ---
set -a
# shellcheck disable=SC1091
. ./.env 2> /dev/null || true
set +a

# --- git state ---
res SHA "$(git rev-parse HEAD 2> /dev/null || echo unknown)"
STASHES=$(git stash list 2> /dev/null | wc -l)
if [ "$STASHES" -eq 0 ]; then
  res OK "no stashed pre-deploy hotfixes"
else
  res WARN "$STASHES stashed pre-deploy change(s) on the server - review with: git stash list / git stash show -p 'stash@{0}'"
fi

# --- disk ---
USEP=$(df --output=pcent / | tail -1 | tr -dc '0-9')
if [ "$USEP" -ge 90 ]; then
  res FAIL "disk ${USEP}% used - free space urgently (check data/bluemap first)"
elif [ "$USEP" -ge 80 ]; then
  res WARN "disk ${USEP}% used"
else
  res OK "disk ${USEP}% used"
fi
res INFO "largest dirs: $(du -sh data/world data/bluemap backups modpack/dist 2> /dev/null | sort -rh | head -3 | awk '{printf "%s %s  ", $1, $2}')"

# --- containers ---
for c in mc mc-backup uptime-kuma nav-proxy cloudflared pack-web mod-checker discord-sync idle-tasks; do
  STATE=$(docker inspect "$c" --format '{{.State.Status}}' 2> /dev/null || echo missing)
  RESTARTS=$(docker inspect "$c" --format '{{.RestartCount}}' 2> /dev/null || echo 0)
  if [ "$STATE" = "missing" ]; then
    res FAIL "container $c is missing"
  elif [ "$STATE" != "running" ]; then
    res FAIL "container $c is $STATE"
  elif [ "$RESTARTS" -gt 0 ]; then
    res WARN "container $c running but restarted ${RESTARTS}x - check: docker logs $c --tail 50"
  else
    res OK "container $c running"
  fi
done
MC_HEALTH=$(docker inspect mc --format '{{.State.Health.Status}}' 2> /dev/null || echo unknown)
if [ "$MC_HEALTH" = "healthy" ]; then
  res OK "mc healthcheck: healthy"
else
  res FAIL "mc healthcheck: $MC_HEALTH"
fi

# --- RCON + performance (silence while healthy = autopause, not an outage) ---
LIST=$(timeout 8 docker exec mc rcon-cli list 2> /dev/null || true)
if [ -n "$LIST" ]; then
  res OK "RCON: $LIST"
  SPARK=$(timeout 10 docker exec mc rcon-cli "spark health" 2> /dev/null | grep -iE "tps|memory" | head -2 | tr -s ' ' | tr '\n' ' | ')
  [ -n "$SPARK" ] && res INFO "spark: $SPARK"
else
  res INFO "RCON silent - server autopaused or booting (normal when empty; mc healthcheck above is the truth)"
fi

# --- main thread + autopause plumbing (the RCON-silent triage kit) ---
# java state: T = SIGSTOPped by autopause (normal when empty), S/R = live.
# RCON silent + java live + high CPU = main thread busy/blocked, NOT paused.
JSTATE=$(docker exec mc ps -ax -o stat,comm 2> /dev/null | grep java | awk '{print $1}' || echo "?")
# (T*) balanced-paren patterns: macOS bash 3.2's $() scanner miscounts a
# bare `T*)` inside this heredoc-in-command-substitution and dies.
case "$JSTATE" in
  (T*) res INFO "java process: SIGSTOPped (autopaused — normal when empty)" ;;
  (S* | R*) res OK "java process: running (state $JSTATE)" ;;
  (*) res WARN "java process: state '$JSTATE' (container down or exec failed)" ;;
esac
ORPHANS=$(docker exec mc ps -ax -o comm 2> /dev/null | grep -c rcon-cli || echo 0)
if [ "${ORPHANS:-0}" -gt 5 ]; then
  res WARN "$ORPHANS rcon-cli processes inside mc - RCON commands are queueing behind a busy/blocked main thread"
elif [ "${ORPHANS:-0}" -gt 0 ]; then
  res INFO "$ORPHANS rcon-cli process(es) inside mc"
fi
for s in .skip-pause .skip-pause-idle .skip-pause-deploying; do
  if docker exec mc test -f "/data/$s" 2> /dev/null; then
    AGE=$(( $(date +%s) - $(docker exec mc stat -c %Y "/data/$s" 2> /dev/null || date +%s) ))
    if [ "$s" = ".skip-pause-deploying" ] && [ "$AGE" -gt 3600 ]; then
      res WARN "autopause sentinel $s is ${AGE}s old - a deploy died without cleanup (idle-tasks clears it after 60min)"
    else
      res INFO "autopause sentinel $s present (${AGE}s old)"
    fi
  fi
done

# --- JVM thread dump (--threads only; SIGQUIT = dump + carry on, never fatal) ---
if [ "$_threads" = "1" ] && [ "$JSTATE" != "?" ]; then
  JPID=$(docker exec mc ps -ax -o pid,comm 2> /dev/null | awk '/java/{print $1; exit}')
  if [ -n "$JPID" ]; then
    docker exec mc kill -3 "$JPID" 2> /dev/null
    sleep 8
    docker exec mc kill -3 "$JPID" 2> /dev/null
    sleep 2
    # Two dumps 8s apart: a rising cpu= on "Server thread" means it is
    # progressing (driving chunk tasks while parked); flat = truly stuck.
    CPUS=$(docker logs mc --since 30s 2>&1 | grep -oE '"Server thread".*cpu=[0-9.]+' | grep -oE '[0-9.]+$' | tr '\n' ' ')
    CPU1=$(echo "$CPUS" | awk '{print $1}')
    CPU2=$(echo "$CPUS" | awk '{print $NF}')
    if [ -n "$CPU1" ] && [ -n "$CPU2" ]; then
      DELTA=$(awk -v a="$CPU1" -v b="$CPU2" 'BEGIN{printf "%.0f", b-a}')
      if [ "${DELTA:-0}" -gt 100 ]; then
        res INFO "Server thread: +${DELTA}ms CPU over 8s - busy but PROGRESSING (likely worldgen/chunk cascade; wait or redeploy)"
      else
        res WARN "Server thread: +${DELTA:-0}ms CPU over 8s - effectively STUCK (deadlock or starved; a restart is likely needed)"
      fi
    fi
    docker logs mc --since 30s 2>&1 | grep -m1 -A 12 '"Server thread"' | while IFS= read -r line; do
      res INFO "  $line"
    done
  fi
fi

# --- backup age (restic on the host, creds from .env) ---
if [ -n "${R2_ACCOUNT_ID:-}" ] && [ -n "${RESTIC_PASSWORD:-}" ] && command -v restic > /dev/null; then
  AGE_H=$(timeout 30 env \
    RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}" \
    AWS_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID}" \
    AWS_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY}" \
    RESTIC_PASSWORD="${RESTIC_PASSWORD}" \
    restic snapshots --latest 1 --json 2> /dev/null \
    | python3 -c '
import json, sys, datetime
snaps = json.load(sys.stdin)
t = datetime.datetime.fromisoformat(snaps[-1]["time"])
age = datetime.datetime.now(datetime.timezone.utc) - t
print(int(age.total_seconds() // 3600))
' 2> /dev/null || echo "-1")
  if [ "$AGE_H" = "-1" ]; then
    res FAIL "could not read restic snapshots (R2 unreachable or repo broken)"
  elif [ "$AGE_H" -gt 48 ]; then
    res FAIL "last backup ${AGE_H}h ago (schedule is 12h) - check: docker logs mc-backup --tail 50"
  elif [ "$AGE_H" -gt 26 ]; then
    res WARN "last backup ${AGE_H}h ago (schedule is 12h)"
  else
    res OK "last backup ${AGE_H}h ago"
  fi
else
  res WARN "backup check skipped (restic or R2 credentials unavailable)"
fi

# --- Discord slash-command registry (the dcintegration wipe class of bug) ---
if [ -n "${DISCORD_BOT_TOKEN:-}" ] && [ -n "${DISCORD_GUILD_ID:-}" ]; then
  APP_ID=$(python3 -c '
import base64, sys
t = sys.argv[1].split(".")[0]
print(base64.b64decode(t + "=" * (-len(t) % 4)).decode())
' "$DISCORD_BOT_TOKEN" 2> /dev/null || echo "")
  CMDS=$(timeout 10 curl -s -H "Authorization: Bot ${DISCORD_BOT_TOKEN}" \
    "https://discord.com/api/v10/applications/${APP_ID}/guilds/${DISCORD_GUILD_ID}/commands" 2> /dev/null \
    | python3 -c 'import json,sys; print(",".join(sorted(c["name"] for c in json.load(sys.stdin))))' 2> /dev/null || echo "?")
  if [ "$CMDS" = "mc,register,unregister" ]; then
    res OK "Discord guild commands registered: $CMDS"
  else
    res FAIL "Discord guild commands are [$CMDS], expected [mc,register,unregister] - recreate discord-sync"
  fi
else
  res WARN "Discord registry check skipped (token/guild not in .env)"
fi

# --- modpack mirror (DIST_DIR defaults to ./modpack-dist in compose) ---
_dist_dir="${DIST_DIR:-./modpack-dist}"
NJARS=$(ls "${_dist_dir}"/mods/*.jar 2> /dev/null | wc -l)
if [ "$NJARS" -gt 0 ]; then
  res OK "modpack mirror: $NJARS JARs in ${_dist_dir}/mods"
else
  res WARN "modpack mirror empty - installs fall back to Modrinth (run build-modpack.sh)"
fi

# --- kuma-init (one-shot; non-zero exit means monitors didn't provision) ---
KEC=$(docker inspect kuma-init --format '{{.State.ExitCode}}' 2> /dev/null || echo "?")
if [ "$KEC" = "0" ]; then
  res OK "kuma-init exited 0 (monitors provisioned)"
else
  res WARN "kuma-init exit code: $KEC - check: docker logs kuma-init --tail 20"
fi

# --- fail2ban ---
if command -v fail2ban-client > /dev/null; then
  BANS=$(sudo fail2ban-client status 2> /dev/null | grep -i 'Jail list' | cut -d: -f2 | tr ',' ' ')
  TOTAL=0
  for j in $BANS; do
    N=$(sudo fail2ban-client status "$j" 2> /dev/null | grep 'Currently banned' | tr -dc '0-9' || echo 0)
    TOTAL=$((TOTAL + ${N:-0}))
  done
  res INFO "fail2ban: ${TOTAL} currently banned across jails:$BANS"
fi

# --- recent mc errors ---
# Cosmetic noise is filtered at source by log4j2-adventure.xml.
# This filter catches real errors that are noisy but non-actionable —
# they stay visible in raw logs (for diagnosis) but don't inflate
# doctor's error count. Add patterns here when a known error has
# bitten us before and we've confirmed it's safe to deprioritise.
ERRS=$(docker logs mc --tail 300 2>&1 | grep -i "ERROR" \
  | grep -v -e "No data fixer registered" -e "Error loading class" \
    -e "Parsing error loading custom advancement" -e "Couldn't load advancements" \
    -e "Error upgrading chunk" -e "Failed to load chunk" \
  | wc -l)
if [ "$ERRS" -eq 0 ]; then
  res OK "no non-trivial errors in the last 300 mc log lines"
else
  res WARN "$ERRS non-trivial error line(s) in recent mc logs - check: docker logs mc --tail 300 | grep -i error"
fi
REMOTE
) || { echo -e "  ${RED}✗${RESET} SSH to ${DEPLOY_USER}@${DROPLET_HOST} failed"; exit 1; }

while IFS=$'\t' read -r level msg; do
  [[ -z "$level" ]] && continue
  report "$level" "$msg"
done <<< "$REMOTE_OUTPUT"

# --- deploy drift (local: compare server HEAD to origin/main) ------------------
MAIN_SHA=$(git ls-remote origin main 2> /dev/null | cut -f1 || echo "")
if [[ -n "$MAIN_SHA" && -n "$SERVER_SHA" && "$SERVER_SHA" != "unknown" ]]; then
  if [[ "$MAIN_SHA" == "$SERVER_SHA" ]]; then
    report OK "server is on origin/main (${SERVER_SHA:0:7})"
  else
    report WARN "server is on ${SERVER_SHA:0:7} but origin/main is ${MAIN_SHA:0:7} - a deploy may be pending or failed (gh run list)"
  fi
else
  report WARN "could not compare server commit with origin/main"
fi

echo ""
echo "=============================================="
echo -e "Results: ${GREEN}${PASS} ok${RESET}, ${YELLOW}${WARN} warnings${RESET}, ${RED}${FAIL} failures${RESET}"
if [[ $FAIL -gt 0 ]]; then
  echo -e "${RED}${BOLD}Doctor FAILED.${RESET}"
  exit 1
fi
[[ $WARN -gt 0 ]] && echo -e "${YELLOW}Warnings need a look, but nothing is on fire.${RESET}"
exit 0
