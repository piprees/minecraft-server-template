#!/usr/bin/env bash
# harden.sh - Baseline hardening for a fresh Ubuntu 24.04 server. Idempotent.
# Run ONCE at provision time (provision.sh calls it) - it restarts Docker,
# so never run it while CI is deploying or containers are mid-recreate.
#
# What it sets up:
#   1. deploy user (passwordless sudo, root's keys + the GitHub deploy key)
#   2. SSH: key-only, no root login (verify a new session BEFORE closing root!)
#   3. UFW: deny inbound except SSH + SERVER_PORT/tcp + VOICE_PORT/udp
#   4. fail2ban: sshd only. The MC and nginx jails ship DISABLED - they cannot
#      see a real client address (docker-proxy relays; cloudflared is outbound)
#      and their unanchored filters let a crafted chat line or header ban an
#      arbitrary IP. Rate limiting at ufw-before-input does that job instead.
#   5. unattended upgrades, 2G swap (swappiness 10), journald capped at 200MB
#   6. Docker with iptables=false (can't bypass UFW) + NAT/rate-limit rules in
#      before.rules AND before6.rules (game 6 conn/min, voice 10 pkt/min, SYN
#      30/min). The trusted-source ACCEPT is scoped to the bridge interfaces;
#      unscoped it admits anything claiming a 172.16/12 source.
#   7. restic + zip for on-demand backups
#
# Gotchas:
#   - Every value reaching the remote root shell is validated first. CALLER_IP
#     comes off the network and lands in both a root shell and jail.local.
#   - Docker is only restarted when daemon.json actually changed - a restart
#     takes mc down with no countdown, kick or save.
#   - The fail2ban jails for mc and nginx ship DISABLED. They cannot see a real
#     client address, and their unanchored filters ban whatever a chat line or
#     an X-Forwarded-For header names. Rate limiting does that job instead.
#   - Refuses to disable root login unless the deploy user has a usable key.
#
# Usage:
#   ./scripts/harden.sh --remote root@SERVER_IP     # from your Mac (uploads itself)
#   ./harden.sh                                     # on the server as root
#   ./harden.sh --non-interactive                   # no prompts (CI/wizard)
set -euo pipefail

# --- auto-remote: a bare run on a workstation targets DROPLET_HOST ------------
# On the server this script runs as root; from a Mac (or any non-root shell)
# with DROPLET_HOST known, re-exec in --remote mode so `./ops harden` just works.
if [[ "${1:-}" != "--remote" ]] && [[ "$(id -u)" != "0" ]]; then
  _PD="${CONSUMER_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
  if [[ -z "${DROPLET_HOST:-}" && -f "$_PD/.env" ]]; then
    DROPLET_HOST=$(grep -E '^DROPLET_HOST=' "$_PD/.env" | head -1 | cut -d= -f2- | tr -d "'\"")
  fi
  if [[ -n "${DROPLET_HOST:-}" ]]; then
    exec "${BASH_SOURCE[0]}" --remote "root@${DROPLET_HOST}" "$@"
  fi
fi

# --- remote execution mode: upload self and run on droplet --------------------
if [[ "${1:-}" == "--remote" ]]; then
  REMOTE_HOST="${2:?Usage: $0 --remote root@IP}"
  SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  PROJECT_DIR="${CONSUMER_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

  DEPLOY_KEY_PUB_EXPANDED="${DEPLOY_KEY_PUB:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key.pub}"
  DEPLOY_KEY_PUB_EXPANDED="${DEPLOY_KEY_PUB_EXPANDED/#\~/$HOME}"
  DEPLOY_KEY_FILE="${DEPLOY_KEY_PUB_EXPANDED%.pub}"
  DEPLOY_USER_VAL="${DEPLOY_USER:-deploy}"
  REMOTE_IP="${REMOTE_HOST#*@}"

  # Try root first; if root is locked (already hardened), use deploy+sudo
  if ssh -o ConnectTimeout=5 -o BatchMode=yes "root@${REMOTE_IP}" 'true' 2> /dev/null; then
    UPLOAD_HOST="root@${REMOTE_IP}"
    UPLOAD_DIR="/root"
  elif ssh -o ConnectTimeout=5 -o BatchMode=yes -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_IP}" 'true' 2> /dev/null; then
    UPLOAD_HOST="${DEPLOY_USER_VAL}@${REMOTE_IP}"
    UPLOAD_DIR="/tmp"
  else
    echo "Can't SSH as root or ${DEPLOY_USER_VAL} to ${REMOTE_IP}."
    exit 1
  fi

  echo "Uploading harden.sh to $UPLOAD_HOST..."
  scp -i "$DEPLOY_KEY_FILE" "$SCRIPT_PATH" "${UPLOAD_HOST}:${UPLOAD_DIR}/harden.sh" 2> /dev/null \
    || scp "$SCRIPT_PATH" "${UPLOAD_HOST}:${UPLOAD_DIR}/harden.sh"

  if [[ -f "$DEPLOY_KEY_PUB_EXPANDED" ]]; then
    echo "Uploading deploy public key..."
    scp -i "$DEPLOY_KEY_FILE" "$DEPLOY_KEY_PUB_EXPANDED" "${UPLOAD_HOST}:${UPLOAD_DIR}/mc_deploy_key.pub" 2> /dev/null \
      || scp "$DEPLOY_KEY_PUB_EXPANDED" "${UPLOAD_HOST}:${UPLOAD_DIR}/mc_deploy_key.pub"
  fi

  echo "Running harden.sh on $REMOTE_HOST..."
  # Only the four values this driver needs, read rather than sourced: `source`
  # executes the file, so a $(...) in any .env value would run on this machine,
  # and `set -a` would export the whole secret set into every child process.
  if [[ -f "$PROJECT_DIR/.env" ]]; then
    for _k in DEPLOY_USER SERVER_PORT VOICE_PORT BRAND_SLUG; do
      _v=$(grep -E "^${_k}=" "$PROJECT_DIR/.env" | head -1 | cut -d= -f2- | tr -d '\042\047' | tr -d '\n')
      [[ -n "$_v" ]] && eval "${_k}=\$_v"
    done
    unset _k _v
  fi

  # Caller's public IP, for the fail2ban whitelist. Validated strictly: this value
  # reaches a root shell (:95) and /etc/fail2ban/jail.local (:445). An unvalidated
  # body yields root RCE by two independent routes, and `0.0.0.0/0` alone disables
  # every jail. Validate here, at the source - escaping the sinks does not close both.
  CALLER_IP=$(curl -fsS -4 --max-time 10 https://cloudflare.com/cdn-cgi/trace 2> /dev/null \
    | sed -n 's/^ip=//p' | head -1 || true)
  # grep -q matches per LINE, so an embedded newline would smuggle a payload past a
  # dotted-quad test. This rejects newline, $, (, /, quotes and whitespace outright.
  case "$CALLER_IP" in
    "" | *[!0-9.]*) CALLER_IP="" ;;
  esac
  if ! printf '%s' "$CALLER_IP" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
    if [[ -n "$CALLER_IP" ]]; then
      echo "  Discarding malformed public-IP response - no self-whitelist." >&2
    fi
    CALLER_IP=""
  fi

  # Run remotely via nohup so it survives SSH drops (Docker install takes 3+ min).
  SSH_FLAGS=()
  RUN_PREFIX=""
  if [[ "$UPLOAD_HOST" == root@* ]]; then
    SSH_FLAGS=()
    RUN_PREFIX=""
  else
    SSH_FLAGS=(-i "$DEPLOY_KEY_FILE")
    RUN_PREFIX="sudo"
  fi

  # Move deploy key to /root if uploaded to /tmp (needs sudo)
  if [[ "$UPLOAD_DIR" == "/tmp" ]]; then
    ssh ${SSH_FLAGS[@]+"${SSH_FLAGS[@]}"} "$UPLOAD_HOST" "sudo cp /tmp/harden.sh /root/harden.sh; sudo cp /tmp/mc_deploy_key.pub /root/mc_deploy_key.pub 2>/dev/null; sudo chmod +x /root/harden.sh" 2> /dev/null
  fi

  # shellcheck disable=SC2029  # Deliberate client-side expansion. Every value is
  # validated before it gets here: CALLER_IP at :77, the rest come from .env.
  ssh ${SSH_FLAGS[@]+"${SSH_FLAGS[@]}"} "$UPLOAD_HOST" "${RUN_PREFIX} bash -c 'chmod +x /root/harden.sh && \
        rm -f /root/.harden-done /root/.harden-failed && \
        nohup bash -c \" \
          DEPLOY_USER=\\\"${DEPLOY_USER_VAL}\\\" \
          SERVER_PORT=\\\"${SERVER_PORT:-25577}\\\" \
          VOICE_PORT=\\\"${VOICE_PORT:-24454}\\\" \
          CALLER_IP=\\\"${CALLER_IP:-}\\\" \
          /root/harden.sh --non-interactive > /root/harden.log 2>&1 \
          && touch /root/.harden-done \
          || touch /root/.harden-failed \
        \" > /dev/null 2>&1 &'"

  echo "  Hardening started in background on the server."
  echo "  Waiting for completion (Docker install can take a few minutes)..."
  echo ""

  # Poll for completion. SSH as deploy (root may get locked mid-run).
  DEPLOY_KEY_FILE="${DEPLOY_KEY_PUB_EXPANDED%.pub}"
  DEPLOY_USER_VAL="${DEPLOY_USER:-deploy}"
  HARDEN_WAIT=0
  HARDEN_MAX=600
  while [[ $HARDEN_WAIT -lt $HARDEN_MAX ]]; do
    # Try deploy user first (root gets disabled during hardening)
    if ssh -o ConnectTimeout=5 -o BatchMode=yes -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
      'test -f /root/.harden-done 2>/dev/null || sudo test -f /root/.harden-done' 2> /dev/null; then
      echo ""
      echo "  Hardening complete. Fetching log..."
      ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
        'sudo cat /root/harden.log' 2> /dev/null | tail -20
      exit 0
    fi
    if ssh -o ConnectTimeout=5 -o BatchMode=yes -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
      'test -f /root/.harden-failed 2>/dev/null || sudo test -f /root/.harden-failed' 2> /dev/null; then
      echo ""
      echo "  Hardening FAILED. Last 30 lines of log:"
      ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
        'sudo cat /root/harden.log' 2> /dev/null | tail -30
      exit 1
    fi
    sleep 10
    HARDEN_WAIT=$((HARDEN_WAIT + 10))
    if ((HARDEN_WAIT % 60 == 0)); then
      echo "    ...still running (${HARDEN_WAIT}s / ${HARDEN_MAX}s)"
    fi
  done

  echo ""
  echo "  Timed out after ${HARDEN_MAX}s. The script is probably still running."
  echo "  Check manually: ssh ${DEPLOY_USER_VAL}@${REMOTE_HOST#*@} 'sudo cat /root/harden.log'"
  exit 1
fi

# --- must run as root ---------------------------------------------------------
if [[ "$(id -u)" -ne 0 ]]; then
  echo "This script must run as root. Try: sudo $0"
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
NON_INTERACTIVE="${1:-}"

# --- refuse to run during a deploy --------------------------------------------
# This restarts Docker, which takes a deploy's containers with it (safety rule
# 9). deploy.sh and infra-deploy.sh hold this lock via lib.sh; harden.sh is
# uploaded on its own and cannot source it, so the check is inline.
DEPLOY_LOCK="/home/${DEPLOY_USER:-deploy}/server/.deploy.lock"
# `flock -n FILE true` releases the moment true exits, leaving the ~3-15 min
# until the Docker restart unguarded. Hold fd 200 for the life of this script
# instead - the same shape as acquire_deploy_lock in lib.sh.
# Keyed on the DIRECTORY, not the file: deploy.sh creates the lock file when it
# starts, so requiring the file to pre-exist meant the first harden run took no
# lock and a deploy starting seconds later collided with it.
# Writability is checked BEFORE the exec, never with `2>` attached to it: a
# redirection error on exec is fatal, and `exec 200>> f 2>/dev/null` would
# redirect this script's stderr for the rest of its life. Same reasoning as
# acquire_deploy_lock in lib.sh. Fail open, but say so loudly.
LOCK_DIR="$(dirname "$DEPLOY_LOCK")"
if command -v flock > /dev/null 2>&1 && [[ -d "$LOCK_DIR" && -w "$LOCK_DIR" ]] \
  && { [[ ! -e "$DEPLOY_LOCK" ]] || [[ -w "$DEPLOY_LOCK" ]]; }; then
  exec 200>> "$DEPLOY_LOCK"
  if ! flock -n 200; then
    echo "ERROR: a server operation is in progress ($(cat "$DEPLOY_LOCK" 2> /dev/null || echo unknown))." >&2
    echo "  harden.sh restarts Docker. Refusing to run alongside it." >&2
    exit 1
  fi
  # Register as the holder so a concurrent deploy names us, not "unknown".
  printf '%s pid=%s started=%s\n' "harden.sh" "$$" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >&200
elif command -v flock > /dev/null 2>&1; then
  echo "  WARNING: $DEPLOY_LOCK not writable - concurrent runs are UNGUARDED." >&2
fi

# --- helper functions ---------------------------------------------------------

backup() {
  if [[ -f "$1" ]]; then
    cp -p "$1" "$1.bak.$STAMP" && echo "  backed up $1"
  fi
}

# Wait for the dpkg/apt lock to be released (fresh droplets run unattended-upgrades on boot)
wait_for_apt_lock() {
  local max_wait=120
  local waited=0
  while fuser /var/lib/dpkg/lock-frontend > /dev/null 2>&1 \
    || fuser /var/lib/apt/lists/lock > /dev/null 2>&1 \
    || fuser /var/lib/dpkg/lock > /dev/null 2>&1; do
    if [[ $waited -eq 0 ]]; then
      echo "  Waiting for apt lock (another process is installing packages)..."
    fi
    sleep 5
    waited=$((waited + 5))
    if [[ $waited -ge $max_wait ]]; then
      echo "  WARNING: apt lock held for ${max_wait}s - proceeding anyway" >&2
      break
    fi
  done
  if [[ $waited -gt 0 && $waited -lt $max_wait ]]; then
    echo "  Lock released after ${waited}s."
  fi
}

retry() {
  local -r max_attempts="${1:?}"
  local -r delay="${2:?}"
  shift 2
  local attempt=1
  while true; do
    if "$@"; then
      return 0
    fi
    if [[ $attempt -ge $max_attempts ]]; then
      echo "  FAILED after $max_attempts attempts: $*" >&2
      return 1
    fi
    echo "  Attempt $attempt/$max_attempts failed - retrying in ${delay}s..."
    sleep "$delay"
    attempt=$((attempt + 1))
  done
}

apt_install() {
  wait_for_apt_lock
  retry 3 10 env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
}

apt_update() {
  wait_for_apt_lock
  retry 3 10 apt-get update -y -qq
}

# =============================================================================
# 1. Create the deploy user
# =============================================================================
echo ""
echo "=== 1. Deploy user ==="

if [[ "$NON_INTERACTIVE" == "--non-interactive" ]]; then
  NEWUSER="${DEPLOY_USER:-deploy}"
else
  read -rp "Non-root username to create [deploy]: " NEWUSER
  NEWUSER="${NEWUSER:-deploy}"
fi

if id "$NEWUSER" &> /dev/null; then
  echo "User '$NEWUSER' already exists - continuing."
else
  adduser --disabled-password --gecos "" "$NEWUSER"
  usermod -aG sudo "$NEWUSER"
  echo "Created user '$NEWUSER' with sudo."
fi

# Grant passwordless sudo (key-only login means no password exists to type)
# Validate before it counts: a malformed sudoers file leaves this account with no
# sudo at all, and root login is disabled later in the same run.
echo "$NEWUSER ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-$NEWUSER"
chmod 0440 "/etc/sudoers.d/90-$NEWUSER"
if command -v visudo > /dev/null 2>&1 && ! visudo -c -f "/etc/sudoers.d/90-$NEWUSER"; then
  rm -f "/etc/sudoers.d/90-$NEWUSER"
  echo "ERROR: generated sudoers file is invalid - removed it. Is '$NEWUSER' a" >&2
  echo "       valid user name? Refusing to continue." >&2
  exit 1
fi

# Copy root's SSH keys to the new user
mkdir -p "/home/$NEWUSER/.ssh"
# Seed once, then leave alone. Overwriting on a re-run destroys any key added
# since, including the CI deploy key - the runner's retries then trip the sshd
# jail and ban it for a day. Merging root's file forward instead is worse: it
# holds every key attached to the provider project, permanently.
if [[ ! -s "/home/$NEWUSER/.ssh/authorized_keys" && -f /root/.ssh/authorized_keys ]]; then
  cp /root/.ssh/authorized_keys "/home/$NEWUSER/.ssh/authorized_keys"
  echo "  Seeded $NEWUSER with root's SSH keys (first run only)."
elif [[ -s "/home/$NEWUSER/.ssh/authorized_keys" ]]; then
  echo "  $NEWUSER already has authorised keys - leaving them alone."
fi

# Also install the GitHub Actions deploy key if uploaded
if [[ -f /root/mc_deploy_key.pub ]]; then
  # Append without duplicating
  if ! grep -qF "$(cat /root/mc_deploy_key.pub)" "/home/$NEWUSER/.ssh/authorized_keys" 2> /dev/null; then
    cat /root/mc_deploy_key.pub >> "/home/$NEWUSER/.ssh/authorized_keys"
    echo "  Added GitHub Actions deploy key."
  else
    echo "  Deploy key already present."
  fi
fi

chmod 700 "/home/$NEWUSER/.ssh"
chmod 600 "/home/$NEWUSER/.ssh/authorized_keys" 2> /dev/null || true
chown -R "$NEWUSER:$NEWUSER" "/home/$NEWUSER/.ssh"

# =============================================================================
# 2. SSH hardening: key-only, no root login
# =============================================================================
echo ""
echo "=== 2. SSH hardening ==="

backup /etc/ssh/sshd_config

sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config
sed -i 's/^#\?ChallengeResponseAuthentication.*/ChallengeResponseAuthentication no/' /etc/ssh/sshd_config

# Ubuntu's sshd_config starts with `Include /etc/ssh/sshd_config.d/*.conf`, and
# sshd takes the FIRST value it obtains for any keyword. A drop-in therefore
# beats the seds above - so write our own, named to sort BEFORE the distro's
# 50-cloud-init.conf. A 99- prefix would lose; that is the trap here.
if [[ -d /etc/ssh/sshd_config.d ]]; then
  cat > /etc/ssh/sshd_config.d/00-hardening.conf << 'SSHDROP'
# Managed by harden.sh. Sorts before 50-cloud-init.conf deliberately:
# sshd uses the first value it obtains, so this file must be read first.
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
KbdInteractiveAuthentication no
SSHDROP
  chmod 0644 /etc/ssh/sshd_config.d/00-hardening.conf
  echo "  Wrote /etc/ssh/sshd_config.d/00-hardening.conf (sorts before cloud-init)."
fi

# sshd reload is DEFERRED to the end of the script. Reloading here kills the
# SSH session that's running this script (even under nohup), and everything
# after this point fails. The config is written, it just won't take effect
# until we reload at the very end.
echo "  SSH config written (reload deferred to end of script)."

# =============================================================================
# 3. Firewall (UFW)
# =============================================================================
echo ""
echo "=== 3. Firewall (UFW) ==="

apt_update
apt_install ufw

ufw default deny incoming
ufw default allow outgoing

# SSH - always needed
ufw allow OpenSSH

# Game port
SERVER_PORT="${SERVER_PORT:-25577}"
ufw allow "${SERVER_PORT}/tcp" comment "Minecraft game port"
echo "  Allowed ${SERVER_PORT}/tcp (game)"

# Voice chat (Simple Voice Chat uses UDP)
VOICE_PORT="${VOICE_PORT:-24454}"
ufw allow "${VOICE_PORT}/udp" comment "Simple Voice Chat"
echo "  Allowed ${VOICE_PORT}/udp (voice)"

# Enable UFW (non-interactive: auto-confirm)
if ! echo "y" | ufw enable; then
  echo "ERROR: ufw enable failed. With iptables=false there is no filter behind" >&2
  echo "       UFW, so this box would be left fully open. Refusing to continue." >&2
  exit 1
fi
echo "  UFW enabled. Default: deny inbound, allow outbound."

# =============================================================================
# 4. Docker + Compose (before fail2ban — core functionality first)
# =============================================================================
echo ""
echo "=== 4. Docker ==="

if command -v docker &> /dev/null; then
  echo "  Docker already installed: $(docker --version)"
else
  # `bash -c` does NOT inherit pipefail, so a failed curl leaves sh reading empty
  # stdin and exiting 0 - the download failure is invisible. Set it explicitly,
  # then verify the binary actually exists.
  retry 3 15 bash -c 'set -o pipefail; curl -fsSL https://get.docker.com | sh'
  if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker install reported success but no docker binary exists." >&2
    exit 1
  fi
  echo "  Docker installed."
fi

usermod -aG docker "$NEWUSER" 2> /dev/null || true
echo "  $NEWUSER added to docker group."

# --- Docker daemon hardening ---
echo ""
echo "=== 4b. Docker daemon hardening ==="

DAEMON_JSON="/etc/docker/daemon.json"
DAEMON_JSON_BEFORE=""
if [[ -f "$DAEMON_JSON" ]]; then
  backup "$DAEMON_JSON"
  DAEMON_JSON_BEFORE="$DAEMON_JSON.bak.$STAMP"
fi

cat > "$DAEMON_JSON" << 'EOF'
{
  "iptables": false,
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF

echo "  Docker daemon.json configured:"
echo "    - iptables: false (Docker won't bypass UFW)"
echo "    - log rotation: 10m x 3 files"

sysctl -w net.ipv4.ip_forward=1 > /dev/null
if ! grep -q '^net.ipv4.ip_forward=1' /etc/sysctl.conf 2> /dev/null; then
  echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf
fi
echo "  IP forwarding enabled"

# Headroom for container file watchers; the stock 128-instance limit is tight
sysctl -w fs.inotify.max_user_instances=512 > /dev/null
sysctl -w fs.inotify.max_user_watches=65536 > /dev/null
for param in 'fs.inotify.max_user_instances=512' 'fs.inotify.max_user_watches=65536'; do
  if ! grep -q "^${param%%=*}" /etc/sysctl.conf 2> /dev/null; then
    echo "$param" >> /etc/sysctl.conf
  else
    sed -i "s/^${param%%=*}=.*/$param/" /etc/sysctl.conf
  fi
done
echo "  inotify limits raised (512 instances, 65536 watches)"

backup /etc/default/ufw
sed -i 's/DEFAULT_FORWARD_POLICY="DROP"/DEFAULT_FORWARD_POLICY="ACCEPT"/' /etc/default/ufw
if ! grep -q '^DEFAULT_FORWARD_POLICY="ACCEPT"' /etc/default/ufw; then
  echo "ERROR: could not set DEFAULT_FORWARD_POLICY - the anchor was not found." >&2
  echo "       Container networking would silently not work. Refusing to continue." >&2
  exit 1
fi
echo "  UFW forward policy set to ACCEPT"

UFW_BEFORE="/etc/ufw/before.rules"
if ! grep -q 'DOCKER-NAT' "$UFW_BEFORE" 2> /dev/null; then
  backup "$UFW_BEFORE"
  cat > /tmp/docker-nat-rules << 'NATRULES'
# NAT rules for Docker (iptables=false mode)
*nat
:POSTROUTING ACCEPT [0:0]
-A POSTROUTING -s 172.16.0.0/12 ! -d 172.16.0.0/12 -j MASQUERADE
COMMIT
# END DOCKER-NAT

NATRULES
  cat /tmp/docker-nat-rules "$UFW_BEFORE" > /tmp/before-rules-new
  mv /tmp/before-rules-new "$UFW_BEFORE"
  rm -f /tmp/docker-nat-rules
  echo "  NAT masquerade rules added to $UFW_BEFORE"
fi

if ! grep -q 'RATE-LIMIT' "$UFW_BEFORE" 2> /dev/null; then
  backup "$UFW_BEFORE"
  cat > /tmp/rate-limit-rules << RATELIMIT
# Rate limiting for game and voice ports (anti-DDoS)
# Scoped to the bridge interfaces. Unscoped, this accepts anything from the
# internet that simply claims a 172.16/12 source, ahead of all three limiters
# below - Ubuntu ships rp_filter=2 (loose), which does not stop that.
# NOTE: setting rp_filter=1 does NOT help. The kernel takes max(all, interface)
# and loose(2) beats strict(1), and udev re-applies 2 to every new interface.
# The interface scope is the whole fix. br+ is iptables' wildcard for br-<id>.
-A ufw-before-input -i docker0 -s 172.16.0.0/12 -j ACCEPT
-A ufw-before-input -i br+ -s 172.16.0.0/12 -j ACCEPT
-A ufw-before-input -p tcp --dport ${SERVER_PORT:-25577} -m state --state NEW -m hashlimit --hashlimit-above 6/minute --hashlimit-burst 6 --hashlimit-mode srcip --hashlimit-name mc-game --hashlimit-htable-expire 30000 -j DROP
-A ufw-before-input -p udp --dport ${VOICE_PORT:-24454} -m hashlimit --hashlimit-above 10/minute --hashlimit-burst 10 --hashlimit-mode srcip --hashlimit-name mc-voice --hashlimit-htable-expire 30000 -j DROP
-A ufw-before-input -p tcp --syn -m hashlimit --hashlimit-above 30/minute --hashlimit-burst 15 --hashlimit-mode srcip --hashlimit-name syn-flood --hashlimit-htable-expire 60000 -j DROP
# END RATE-LIMIT
RATELIMIT
  sed -i '/^# ok icmp codes/r /tmp/rate-limit-rules' "$UFW_BEFORE"
  rm -f /tmp/rate-limit-rules
  # sed exits 0 when the anchor is absent, inserting nothing. Without this check
  # an Ubuntu wording change silently drops all rate limiting while printing success.
  # Exactly once, not merely present: a second copy would share the htable and
  # halve the effective limit.
  if [[ "$(grep -c 'hashlimit-name mc-game' "$UFW_BEFORE")" -ne 1 ]]; then
    echo "ERROR: rate-limit rules were not inserted exactly once into $UFW_BEFORE" >&2
    echo "       (anchor '# ok icmp codes' missing, or matched more than once)." >&2
    exit 1
  fi
  echo "  Rate limiting rules added to $UFW_BEFORE"
fi

# A malformed before.rules makes this fail. With iptables=false nothing else is
# filtering, so a swallowed failure here leaves every port open while the script
# prints success. Verify the rules actually loaded rather than trusting exit 0.
UFW_BEFORE6="/etc/ufw/before6.rules"
# `ufw allow 25577/tcp` opens the port on BOTH families, but every rule above is
# written to before.rules (IPv4). Without this the game port, voice port and SSH
# are reachable over IPv6 with no rate limiting at all.
if [[ -f "$UFW_BEFORE6" ]] && ! grep -q 'RATE-LIMIT' "$UFW_BEFORE6" 2> /dev/null; then
  backup "$UFW_BEFORE6"
  cat > /tmp/rate-limit-rules6 << RATELIMIT6
# Rate limiting for game and voice ports over IPv6 (mirrors before.rules)
-A ufw6-before-input -p tcp --dport ${SERVER_PORT:-25577} -m state --state NEW -m hashlimit --hashlimit-above 6/minute --hashlimit-burst 6 --hashlimit-mode srcip --hashlimit-name mc-game6 --hashlimit-htable-expire 30000 -j DROP
-A ufw6-before-input -p udp --dport ${VOICE_PORT:-24454} -m hashlimit --hashlimit-above 10/minute --hashlimit-burst 10 --hashlimit-mode srcip --hashlimit-name mc-voice6 --hashlimit-htable-expire 30000 -j DROP
-A ufw6-before-input -p tcp --syn -m hashlimit --hashlimit-above 30/minute --hashlimit-burst 15 --hashlimit-mode srcip --hashlimit-name syn-flood6 --hashlimit-htable-expire 60000 -j DROP
# END RATE-LIMIT
RATELIMIT6
  # Anchor on the INPUT heading specifically. before6.rules carries FOUR
  # `# ok icmp codes` headings (INPUT, OUTPUT, FORWARD x2) and sed's `r` inserts
  # after every match - four copies sharing one --hashlimit-name would each spend
  # a token per packet, cutting the game limit to about a quarter of the figure
  # advertised. The v4 file has one match; this one does not.
  if [[ "$(grep -c '^# ok icmp codes for INPUT' "$UFW_BEFORE6")" -ne 1 ]]; then
    echo "ERROR: expected exactly one '# ok icmp codes for INPUT' in $UFW_BEFORE6." >&2
    exit 1
  fi
  sed -i '/^# ok icmp codes for INPUT/r /tmp/rate-limit-rules6' "$UFW_BEFORE6"
  rm -f /tmp/rate-limit-rules6
  if [[ "$(grep -c 'hashlimit-name mc-game6' "$UFW_BEFORE6")" -ne 1 ]]; then
    echo "ERROR: IPv6 rate-limit block was not inserted exactly once." >&2
    exit 1
  fi
  if ! grep -q "RATE-LIMIT" "$UFW_BEFORE6"; then
    echo "ERROR: IPv6 rate-limit rules were not inserted into $UFW_BEFORE6." >&2
    exit 1
  fi
  echo "  IPv6 rate limiting rules added to $UFW_BEFORE6"
fi

if ! ufw reload; then
  echo "ERROR: ufw reload failed - before.rules is probably malformed." >&2
  echo "       Restore ${UFW_BEFORE}.bak.${STAMP} and re-run. Refusing to continue." >&2
  exit 1
fi
if ! ufw status | grep -q "Status: active"; then
  echo "ERROR: ufw reports inactive after reload. Refusing to continue." >&2
  exit 1
fi
echo "  UFW reloaded with Docker networking and rate limiting rules"

# Restarting Docker takes mc down with none of deploy.sh's countdown, kick,
# save-off or save-all flush. On a re-run where daemon.json is unchanged there
# is nothing to apply, so do not pay that price. Safety rules 5, 8 and 9.
if ! systemctl is-active --quiet docker; then
  systemctl start docker 2> /dev/null || true
  echo "  Docker started with new daemon config."
elif [[ -n "$DAEMON_JSON_BEFORE" ]] && cmp -s "$DAEMON_JSON" "$DAEMON_JSON_BEFORE"; then
  echo "  daemon.json unchanged - NOT restarting Docker (mc keeps running)."
  rm -f "$DAEMON_JSON_BEFORE"
else
  if docker ps --format '{{.Names}}' 2> /dev/null | grep -q 'mc$'; then
    echo "  WARNING: restarting Docker with mc running - no countdown, no save." >&2
    echo "           Prefer deploy.sh or /mc restart if players may be online." >&2
  fi
  systemctl restart docker
  echo "  Docker restarted with new daemon config."
fi

# =============================================================================
# 5. fail2ban (after Docker so log-pipe services can start)
# =============================================================================
echo ""
echo "=== 5. fail2ban ==="

apt_install fail2ban

# ignoreip carries IPv6 ranges, and whether they are honoured depends on this.
# fail2ban warns when it is unset and falls back to auto; set it explicitly so
# the v6 entries are not silently inert on a host it decides is v4-only.
backup /etc/fail2ban/fail2ban.local
cat > /etc/fail2ban/fail2ban.local << 'F2BEOF'
[Definition]
allowipv6 = auto
F2BEOF

backup /etc/fail2ban/jail.local
cat > /etc/fail2ban/jail.local << EOF
[DEFAULT]
# Never ban Docker bridge networks (healthchecks, sidecars), localhost,
# or Cloudflare's IP ranges (tunnel traffic, web map, modpack downloads).
# localhost + Docker bridge + private networks
# <HOST> matches hostnames as well as addresses and usedns defaults to warn, so
# without this a crafted header makes this box resolve an attacker-chosen name.
usedns = no
ignoreip = 127.0.0.0/8 ::1 172.16.0.0/12 10.0.0.0/8
# The machine that ran setup (so SSH probes during provisioning don't self-ban)
             ${CALLER_IP:-}
# Cloudflare edge IPs (all tunnel/web traffic arrives from these; banning one
# kills the map/status/pack sites for everyone). Source: cloudflare.com/ips-v4
             173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22
             141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20
             197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13
             104.24.0.0/14 172.64.0.0/13 131.0.72.0/22
# Cloudflare IPv6 (cloudflare.com/ips-v6). Tunnel traffic arrives over v6 too.
             2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32
             2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32

[sshd]
enabled  = true
port     = ssh
backend  = systemd
bantime  = 1d
findtime = 10m
maxretry = 4

# Disabled. mc sees the Docker bridge gateway as the source for every player
# (iptables=false, no DNAT, docker-proxy relays), and that address is in ignoreip
# above - so this can never ban an abuser. The failregex is also unanchored, so a
# player typing "/8.8.8.8: ..." in chat DOES get that address banned, and the log
# pipe re-fires those bans from replayed history on every Docker restart. The
# hashlimit at ufw-before-input does this job on the real source address.
[mc-connection-spam]
enabled  = false
port     = ${SERVER_PORT:-25577}
filter   = mc-connection-spam
logpath  = /var/log/mc-docker.log
backend  = auto
bantime  = 1h
findtime = 2m
maxretry = 10

# Disabled - same reasons as mc-connection-spam above. This one also matches
# SUCCESSFUL logins ("logged in with entity id"), so it counted normal joins.
[mc-login-flood]
enabled  = false
port     = ${SERVER_PORT:-25577}
filter   = mc-login-flood
logpath  = /var/log/mc-docker.log
backend  = auto
bantime  = 6h
findtime = 5m
maxretry = 20

[nginx-exploit-scan]
# Known-bad paths (WordPress logins, .env probes, admin consoles, etc.) that
# have zero legitimate use on this site. One hit is enough - these are always
# automated scanners, never a real visitor. Real client IP comes from the
# trailing X-Forwarded-For field (cloudflared sets it) - \$remote_addr in
# these logs is just the internal Docker bridge IP of whichever proxy hop
# forwarded the request, not the actual attacker.
# Disabled. Two independent defects: <HOST> binds to the FIRST X-Forwarded-For
# element, which Cloudflare appends to rather than replaces, so it is
# attacker-supplied - one request bans any address named in that header. And no
# inbound traffic reaches 80/443 (cloudflared makes an OUTBOUND tunnel), so the
# ban lands on ports nothing arrives on. The port setting below is the only
# thing keeping the poisoned ban harmless; changing it to anyport, or banaction
# to iptables-allports, arms it against SSH and the game port.
enabled  = false
port     = http,https
filter   = nginx-exploit-scan
logpath  = /var/log/nav-proxy-nginx.log
           /var/log/pack-web-nginx.log
backend  = auto
bantime  = 1w
findtime = 1d
maxretry = 1
EOF

# --- fail2ban filter: MC connection spam (rapid connect/disconnect) ---
mkdir -p /etc/fail2ban/filter.d
cat > /etc/fail2ban/filter.d/mc-connection-spam.conf << 'EOF'
# Detects rapid connection/disconnect from the same IP.
# Reads plain-text mc container logs piped via mc-log-pipe service.
[Definition]
failregex = .*\/<HOST>:.*lost connection:
            .*\/<HOST>:.*disconnected
ignoreregex =
EOF

cat > /etc/fail2ban/filter.d/mc-login-flood.conf << 'EOF'
# Detects login flood - many connections from one IP over time.
# Reads plain-text mc container logs piped via mc-log-pipe service.
[Definition]
failregex = .*\/<HOST>:.* logged in with entity id
            .*\/<HOST>:.*com\.mojang\.authlib\.GameProfile.*lost connection
ignoreregex =
EOF

# --- fail2ban filter: nginx exploit scans (WordPress, .env, admin panels, etc.) ---
# nginx access log format (both nav-proxy and pack-web):
#   $remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent
#   "$http_referer" "$http_user_agent" "$http_x_forwarded_for"
# <HOST> is matched against the LAST field, not the first - $remote_addr here
# is always the Docker-internal bridge IP, never the real client.
cat > /etc/fail2ban/filter.d/nginx-exploit-scan.conf << 'EOF'
[Definition]
failregex = "(?:GET|POST|HEAD) (?:/wp-login\.php|/wp-admin(?:/.*)?|/wp-content(?:/.*)?|/wp-includes(?:/.*)?|/wp-json(?:/.*)?|/xmlrpc\.php|/wlwmanifest\.xml|/wordpress(?:/.*)?|/\.env|/\.git(?:/.*)?|/\.aws(?:/.*)?|/phpmyadmin(?:/.*)?|/pma(?:/.*)?|/myadmin(?:/.*)?|/administrator(?:/.*)?|/vendor/phpunit(?:/.*)?|/actuator(?:/.*)?|/telescope(?:/.*)?|/console(?:/.*)?|/geoserver(?:/.*)?|/solr(?:/.*)?|/druid(?:/.*)?|/cgi-bin(?:/.*)?|/boaform(?:/.*)?|/setup\.cgi|/HNAP1) HTTP/\d\.\d" \d{3} \d+ "[^"]*" "[^"]*" "<HOST>(?:,.*)?"
ignoreregex =
EOF

# Create systemd services that pipe container logs to files fail2ban can read.
# Docker container IDs change on recreate, so we can't glob them in fail2ban's logpath.
# These services follow each container's logs and write to a stable path.
cat > /etc/systemd/system/mc-log-pipe.service << 'SVCEOF'
[Unit]
Description=Pipe Minecraft Docker logs to /var/log/mc-docker.log for fail2ban
After=docker.service
Requires=docker.service

[Service]
ExecStart=/bin/sh -c 'docker logs -f mc 2>&1 | while IFS= read -r line; do printf "%%s %%s\n" "$(date +%%Y-%%m-%%dT%%H:%%M:%%S)" "$line"; done >> /var/log/mc-docker.log'
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
SVCEOF

# Templated unit: one instance per nginx container (nav-proxy, pack-web).
# nginx's official Docker image symlinks access.log to /dev/stdout, so
# `docker logs` already carries every request - no extra config needed there.
cat > /etc/systemd/system/nginx-log-pipe@.service << 'SVCEOF'
[Unit]
Description=Pipe %i nginx logs to /var/log/%i-nginx.log for fail2ban
After=docker.service
Requires=docker.service

[Service]
ExecStart=/bin/sh -c 'docker logs -f %i 2>&1 | while IFS= read -r line; do printf "%%s %%s\n" "$(date +%%Y-%%m-%%dT%%H:%%M:%%S)" "$line"; done >> /var/log/%i-nginx.log'
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
SVCEOF

# Logrotate for all the piped logs (keeps them small)
cat > /etc/logrotate.d/mc-docker << 'LREOF'
/var/log/mc-docker.log
/var/log/nav-proxy-nginx.log
/var/log/pack-web-nginx.log {
    daily
    # size cap as well as daily: these files gain up to 30MB per daemon restart,
    # and a daily-only rotation lets that fill the disk holding the world.
    maxsize 20M
    rotate 3
    compress
    missingok
    notifempty
    copytruncate
    create 0640 root adm
}
LREOF

touch /var/log/mc-docker.log /var/log/nav-proxy-nginx.log /var/log/pack-web-nginx.log
# These carry player IPs and request paths; do not leave them world-readable.
chmod 0640 /var/log/mc-docker.log /var/log/nav-proxy-nginx.log /var/log/pack-web-nginx.log
systemctl daemon-reload
# Log-pipe services need Docker; enable them but don't fail if Docker isn't installed yet.
# They'll start on next boot once Docker is available.
systemctl enable mc-log-pipe.service 2> /dev/null || true
systemctl enable nginx-log-pipe@nav-proxy.service 2> /dev/null || true
systemctl enable nginx-log-pipe@pack-web.service 2> /dev/null || true
if command -v docker &> /dev/null; then
  # restart, not start: daemon-reload does not restart running units and `start`
  # on an active unit is a no-op, so a unit-file change would not take effect in
  # the run that ships it.
  systemctl restart mc-log-pipe.service 2> /dev/null || true
  systemctl restart nginx-log-pipe@nav-proxy.service 2> /dev/null || true
  systemctl restart nginx-log-pipe@pack-web.service 2> /dev/null || true
  echo "  Log-pipe services active (mc, nav-proxy, pack-web -> /var/log/)"
else
  echo "  Log-pipe services enabled (will start after Docker is installed)"
fi

systemctl enable fail2ban 2> /dev/null || true
systemctl restart fail2ban 2> /dev/null || true
echo "  fail2ban active:"
echo "    - SSH: 4 failures in 10m > 24h ban"
echo "    - MC connection spam:  DISABLED (cannot see the real client address)"
echo "    - MC login flood:      DISABLED (same, and it matched successful logins)"
echo "    - nginx exploit scans: DISABLED (bans an attacker-supplied address)"
echo "    - Whitelisted: localhost, Docker networks, Cloudflare IPs"

# =============================================================================
# 5. Automatic security updates
# =============================================================================
echo ""
echo "=== 6. Unattended security upgrades ==="

apt_install unattended-upgrades
DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive unattended-upgrades
echo "  Unattended upgrades enabled."

# =============================================================================
# 5b. Swap (safety net for memory pressure)
# =============================================================================
echo ""
echo "=== 6b. Swap file ==="

SWAPFILE="/swapfile"
if swapon --show | grep -q "$SWAPFILE"; then
  echo "  Swap already active: $(swapon --show | grep "$SWAPFILE" | awk '{print $3}')"
else
  if [[ ! -f "$SWAPFILE" ]]; then
    fallocate -l 2G "$SWAPFILE"
    chmod 600 "$SWAPFILE"
    mkswap "$SWAPFILE"
    echo "  Created 2G swap file."
  fi
  swapon "$SWAPFILE"
  echo "  Swap enabled (2G)."
fi

# Outside the branch above deliberately: inside it, a box that already has swap
# active can never have a missing fstab entry or swappiness restored by a re-run.
if ! grep -q "^$SWAPFILE" /etc/fstab 2> /dev/null; then
  echo "$SWAPFILE none swap sw 0 0" >> /etc/fstab
  echo "  Added swap to /etc/fstab."
fi
# Low swappiness - only use swap under real pressure
sysctl -w vm.swappiness=10 > /dev/null
if grep -q '^vm.swappiness' /etc/sysctl.conf 2> /dev/null; then
  sed -i 's/^vm.swappiness=.*/vm.swappiness=10/' /etc/sysctl.conf
else
  echo 'vm.swappiness=10' >> /etc/sysctl.conf
fi
echo "  swappiness=10 (applied and persisted)."

# (Docker was installed and hardened in step 4, before fail2ban)

# =============================================================================
# 7. Log rotation and journald limits
# =============================================================================
echo ""
echo "=== 7. Log management ==="

# Cap journald at 200MB total. Default is 10% of disk which can be several GB.
JOURNALD_CONF="/etc/systemd/journald.conf"
if ! grep -q '^SystemMaxUse=200M' "$JOURNALD_CONF" 2> /dev/null; then
  backup "$JOURNALD_CONF"
  sed -i 's/^#\?SystemMaxUse=.*/SystemMaxUse=200M/' "$JOURNALD_CONF"
  if ! grep -q '^SystemMaxUse=' "$JOURNALD_CONF"; then
    echo 'SystemMaxUse=200M' >> "$JOURNALD_CONF"
  fi
  systemctl restart systemd-journald
  echo "  journald capped at 200MB"
fi

# Vacuum existing journal if oversized
journalctl --vacuum-size=200M 2> /dev/null || true

# Docker container logs are already limited by daemon.json (10m x 3 files).
# MC's internal logs are limited by log4j2.xml (10MB x 3 days, auto-delete).
echo "  Docker logs: 10MB × 3 files (daemon.json)"
echo "  MC logs: 10MB × 3 days (log4j2.xml)"

# =============================================================================
# 8. restic (for on-demand backups outside the mc-backup container)
# =============================================================================
echo ""
echo "=== 8. restic ==="

apt_install restic zip
echo "  restic installed: $(restic version 2> /dev/null || echo 'unknown')"
echo "  zip installed: $(zip --version 2> /dev/null | head -1 || echo 'unknown')"

# =============================================================================
# 9. Apply deferred SSH hardening (last step - kills root access)
# =============================================================================
echo ""
echo "=== 9. Applying SSH hardening ==="

# A4: nothing below is reversible without console access. Refuse to disable root
# login and password auth unless someone else can demonstrably get in.
AUTHKEYS="/home/$NEWUSER/.ssh/authorized_keys"
if [[ ! -s "$AUTHKEYS" ]]; then
  echo "ERROR: $AUTHKEYS is missing or empty." >&2
  echo "       Disabling root login now would lock this box permanently." >&2
  echo "       Add a key for $NEWUSER, then re-run. SSH policy NOT applied." >&2
  exit 1
fi
if ! ssh-keygen -l -f "$AUTHKEYS" > /dev/null 2>&1; then
  echo "ERROR: no parseable public key in $AUTHKEYS. SSH policy NOT applied." >&2
  exit 1
fi
echo "  Authorised keys for $NEWUSER:"
ssh-keygen -l -f "$AUTHKEYS" 2> /dev/null | sed "s/^/    /"

# A3: validate before reloading. The old chain escalated reload -> restart with
# every branch silenced, so a config sshd rejects left sshd STOPPED and the
# script still exited 0.
if ! sshd -t; then
  echo "ERROR: sshd rejects the config just written. NOT reloading." >&2
  echo "       Restore /etc/ssh/sshd_config.bak.$STAMP and re-run." >&2
  exit 1
fi
if ! { systemctl reload ssh || systemctl reload sshd; }; then
  echo "ERROR: sshd config is valid but the reload failed. Check: systemctl status ssh" >&2
  exit 1
fi
echo "  SSH reloaded: root login disabled, key-only auth active."
echo "  Effective policy:"
sshd -T 2> /dev/null \
  | grep -Ei "^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication)" \
  | sed "s/^/    /" || true

# =============================================================================
# Done
# =============================================================================
echo ""
echo "=================================================================="
echo " Hardening complete."
echo ""
echo " IMPORTANT: Before closing this session -"
echo "  1. Open a NEW terminal"
echo "  2. Confirm you can log in:  ssh ${NEWUSER}@<this-droplet-ip>"
echo "  3. Run: sudo whoami  (should print 'root')"
echo "  4. Only THEN close this root session"
echo ""
echo " Root SSH and password login are now DISABLED."
echo " Key-only access as '${NEWUSER}'."
echo ""
echo " Firewall allows: SSH, ${SERVER_PORT}/tcp (game), ${VOICE_PORT}/udp (voice)"
echo " Rate limiting: game port (6/min), voice (10/min), SYN flood (30/min)"
echo " fail2ban: SSH only. MC and nginx jails ship disabled - see jail.local."
echo " Docker: iptables=false (won't bypass UFW), log rotation enabled"
echo " Swap: 2G safety net (swappiness=10)"
echo " All other inbound traffic is blocked."
echo "=================================================================="
