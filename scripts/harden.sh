#!/usr/bin/env bash
# harden.sh - Baseline hardening for a fresh Ubuntu 24.04 server. Idempotent.
# Run ONCE at provision time (provision.sh calls it) - it restarts Docker,
# so never run it while CI is deploying or containers are mid-recreate.
#
# What it sets up:
#   1. deploy user (passwordless sudo, root's keys + the GitHub deploy key)
#   2. SSH: key-only, no root login (verify a new session BEFORE closing root!)
#   3. UFW: deny inbound except SSH + SERVER_PORT/tcp + VOICE_PORT/udp
#   4. fail2ban: sshd, MC connection-spam/login-flood (fed by systemd log-pipe
#      units that mirror container logs to /var/log/), nginx exploit scans
#      (banned on the real X-Forwarded-For IP); Cloudflare/Docker/localhost
#      whitelisted
#   5. unattended upgrades, 2G swap (swappiness 10), journald capped at 200MB
#   6. Docker with iptables=false (can't bypass UFW) + NAT/rate-limit rules
#      in UFW before.rules (game 6 conn/min, voice 10 pkt/min, SYN 30/min)
#   7. restic + zip for on-demand backups
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

  DEPLOY_KEY_PUB_EXPANDED="${DEPLOY_KEY_PUB:-$HOME/.ssh/mc_deploy_key.pub}"
  DEPLOY_KEY_PUB_EXPANDED="${DEPLOY_KEY_PUB_EXPANDED/#\~/$HOME}"
  DEPLOY_KEY_FILE="${DEPLOY_KEY_PUB_EXPANDED%.pub}"
  DEPLOY_USER_VAL="${DEPLOY_USER:-deploy}"
  REMOTE_IP="${REMOTE_HOST#*@}"

  # Try root first; if root is locked (already hardened), use deploy+sudo
  if ssh -o ConnectTimeout=5 -o BatchMode=yes "root@${REMOTE_IP}" 'true' 2>/dev/null; then
    UPLOAD_HOST="root@${REMOTE_IP}"
    UPLOAD_DIR="/root"
  elif ssh -o ConnectTimeout=5 -o BatchMode=yes -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_IP}" 'true' 2>/dev/null; then
    UPLOAD_HOST="${DEPLOY_USER_VAL}@${REMOTE_IP}"
    UPLOAD_DIR="/tmp"
  else
    echo "Can't SSH as root or ${DEPLOY_USER_VAL} to ${REMOTE_IP}."
    exit 1
  fi

  echo "Uploading harden.sh to $UPLOAD_HOST..."
  scp -i "$DEPLOY_KEY_FILE" "$SCRIPT_PATH" "${UPLOAD_HOST}:${UPLOAD_DIR}/harden.sh" 2>/dev/null \
    || scp "$SCRIPT_PATH" "${UPLOAD_HOST}:${UPLOAD_DIR}/harden.sh"

  if [[ -f "$DEPLOY_KEY_PUB_EXPANDED" ]]; then
    echo "Uploading deploy public key..."
    scp -i "$DEPLOY_KEY_FILE" "$DEPLOY_KEY_PUB_EXPANDED" "${UPLOAD_HOST}:${UPLOAD_DIR}/mc_deploy_key.pub" 2>/dev/null \
      || scp "$DEPLOY_KEY_PUB_EXPANDED" "${UPLOAD_HOST}:${UPLOAD_DIR}/mc_deploy_key.pub"
  fi

  echo "Running harden.sh on $REMOTE_HOST..."
  # shellcheck disable=SC1091
  [[ -f "$PROJECT_DIR/.env" ]] && set -a && source "$PROJECT_DIR/.env" && set +a

  # Detect caller's public IP for fail2ban whitelisting
  CALLER_IP=$(curl -s -4 https://ifconfig.me 2>/dev/null || true)

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
    ssh ${SSH_FLAGS[@]+"${SSH_FLAGS[@]}"} "$UPLOAD_HOST" "sudo cp /tmp/harden.sh /root/harden.sh; sudo cp /tmp/mc_deploy_key.pub /root/mc_deploy_key.pub 2>/dev/null; sudo chmod +x /root/harden.sh" 2>/dev/null
  fi

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
        'test -f /root/.harden-done 2>/dev/null || sudo test -f /root/.harden-done' 2>/dev/null; then
      echo ""
      echo "  Hardening complete. Fetching log..."
      ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
        'sudo cat /root/harden.log' 2>/dev/null | tail -20
      exit 0
    fi
    if ssh -o ConnectTimeout=5 -o BatchMode=yes -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
        'test -f /root/.harden-failed 2>/dev/null || sudo test -f /root/.harden-failed' 2>/dev/null; then
      echo ""
      echo "  Hardening FAILED. Last 30 lines of log:"
      ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY_FILE" "${DEPLOY_USER_VAL}@${REMOTE_HOST#*@}" \
        'sudo cat /root/harden.log' 2>/dev/null | tail -30
      exit 1
    fi
    sleep 10
    HARDEN_WAIT=$((HARDEN_WAIT + 10))
    if (( HARDEN_WAIT % 60 == 0 )); then
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
echo "$NEWUSER ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-$NEWUSER"
chmod 0440 "/etc/sudoers.d/90-$NEWUSER"

# Copy root's SSH keys to the new user
mkdir -p "/home/$NEWUSER/.ssh"
if [[ -f /root/.ssh/authorized_keys ]]; then
  cp /root/.ssh/authorized_keys "/home/$NEWUSER/.ssh/authorized_keys"
  echo "  Copied root's SSH keys to $NEWUSER."
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
echo "y" | ufw enable 2> /dev/null || true
echo "  UFW enabled. Default: deny inbound, allow outbound."

# =============================================================================
# 4. Docker + Compose (before fail2ban — core functionality first)
# =============================================================================
echo ""
echo "=== 4. Docker ==="

if command -v docker &> /dev/null; then
  echo "  Docker already installed: $(docker --version)"
else
  retry 3 15 bash -c 'curl -fsSL https://get.docker.com | sh'
  echo "  Docker installed."
fi

usermod -aG docker "$NEWUSER" 2> /dev/null || true
echo "  $NEWUSER added to docker group."

# --- Docker daemon hardening ---
echo ""
echo "=== 4b. Docker daemon hardening ==="

DAEMON_JSON="/etc/docker/daemon.json"
if [[ -f "$DAEMON_JSON" ]]; then
  backup "$DAEMON_JSON"
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

# BlueMap needs one inotify watcher per map — 78+ maps exhaust the default 128 limit
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
-A ufw-before-input -s 172.16.0.0/12 -j ACCEPT
-A ufw-before-input -p tcp --dport ${SERVER_PORT:-25577} -m state --state NEW -m hashlimit --hashlimit-above 6/minute --hashlimit-burst 6 --hashlimit-mode srcip --hashlimit-name mc-game --hashlimit-htable-expire 30000 -j DROP
-A ufw-before-input -p udp --dport ${VOICE_PORT:-24454} -m hashlimit --hashlimit-above 10/minute --hashlimit-burst 10 --hashlimit-mode srcip --hashlimit-name mc-voice --hashlimit-htable-expire 30000 -j DROP
-A ufw-before-input -p tcp --syn -m hashlimit --hashlimit-above 30/minute --hashlimit-burst 15 --hashlimit-mode srcip --hashlimit-name syn-flood --hashlimit-htable-expire 60000 -j DROP
# END RATE-LIMIT
RATELIMIT
  sed -i '/^# ok icmp codes/r /tmp/rate-limit-rules' "$UFW_BEFORE"
  rm -f /tmp/rate-limit-rules
  echo "  Rate limiting rules added to $UFW_BEFORE"
fi

ufw reload 2> /dev/null || true
echo "  UFW reloaded with Docker networking and rate limiting rules"

if systemctl is-active --quiet docker; then
  systemctl restart docker
  echo "  Docker restarted with new daemon config."
else
  systemctl start docker 2>/dev/null || true
  echo "  Docker started with new daemon config."
fi

# =============================================================================
# 5. fail2ban (after Docker so log-pipe services can start)
# =============================================================================
echo ""
echo "=== 5. fail2ban ==="

apt_install fail2ban

backup /etc/fail2ban/jail.local
cat > /etc/fail2ban/jail.local << EOF
[DEFAULT]
# Never ban Docker bridge networks (healthchecks, sidecars), localhost,
# or Cloudflare's IP ranges (tunnel traffic, BlueMap, modpack downloads).
# localhost + Docker bridge + private networks
ignoreip = 127.0.0.0/8 ::1 172.16.0.0/12 10.0.0.0/8
# The machine that ran setup (so SSH probes during provisioning don't self-ban)
             ${CALLER_IP:-}
# Cloudflare edge IPs (all tunnel/web traffic arrives from these; banning one
# kills the map/status/pack sites for everyone). Source: cloudflare.com/ips-v4
             173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22
             141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20
             197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13
             104.24.0.0/14 172.64.0.0/13 131.0.72.0/22

[sshd]
enabled  = true
port     = ssh
backend  = systemd
bantime  = 1d
findtime = 10m
maxretry = 4

[mc-connection-spam]
enabled  = true
port     = ${SERVER_PORT:-25577}
filter   = mc-connection-spam
logpath  = /var/log/mc-docker.log
backend  = auto
bantime  = 1h
findtime = 2m
maxretry = 10

[mc-login-flood]
enabled  = true
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
enabled  = true
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
    rotate 3
    compress
    missingok
    notifempty
    copytruncate
}
LREOF

touch /var/log/mc-docker.log /var/log/nav-proxy-nginx.log /var/log/pack-web-nginx.log
systemctl daemon-reload
systemctl daemon-reload
# Log-pipe services need Docker; enable them but don't fail if Docker isn't installed yet.
# They'll start on next boot once Docker is available.
systemctl enable mc-log-pipe.service 2>/dev/null || true
systemctl enable nginx-log-pipe@nav-proxy.service 2>/dev/null || true
systemctl enable nginx-log-pipe@pack-web.service 2>/dev/null || true
if command -v docker &>/dev/null; then
  systemctl start mc-log-pipe.service 2>/dev/null || true
  systemctl start nginx-log-pipe@nav-proxy.service 2>/dev/null || true
  systemctl start nginx-log-pipe@pack-web.service 2>/dev/null || true
  echo "  Log-pipe services active (mc, nav-proxy, pack-web -> /var/log/)"
else
  echo "  Log-pipe services enabled (will start after Docker is installed)"
fi

systemctl enable fail2ban 2>/dev/null || true
systemctl restart fail2ban 2>/dev/null || true
echo "  fail2ban active:"
echo "    - SSH: 4 failures in 10m > 24h ban"
echo "    - MC connection spam: 10 disconnects in 2m > 1h ban"
echo "    - MC login flood: 20 connections in 5m > 6h ban"
echo "    - nginx exploit scans (wp-login.php, .env, admin panels, etc.): 1 hit > 1 week ban"
echo "    - Whitelisted: localhost, Docker networks, Cloudflare IPs"

# =============================================================================
# 5. Automatic security updates
# =============================================================================
echo ""
echo "=== 5. Unattended security upgrades ==="

apt_install unattended-upgrades
DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive unattended-upgrades
echo "  Unattended upgrades enabled."

# =============================================================================
# 5b. Swap (safety net for memory pressure)
# =============================================================================
echo ""
echo "=== 5b. Swap file ==="

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
  if ! grep -q "$SWAPFILE" /etc/fstab; then
    echo "$SWAPFILE none swap sw 0 0" >> /etc/fstab
    echo "  Added swap to /etc/fstab."
  fi
  # Low swappiness - only use swap under real pressure
  sysctl -w vm.swappiness=10 > /dev/null
  if ! grep -q 'vm.swappiness' /etc/sysctl.conf; then
    echo 'vm.swappiness=10' >> /etc/sysctl.conf
  fi
  echo "  Swap enabled (2G, swappiness=10)."
fi

  # (Docker was installed and hardened in step 4, before fail2ban)

# =============================================================================
# 7. Log rotation and journald limits
# =============================================================================
echo ""
echo "=== 7. Log management ==="

# Cap journald at 200MB total. Default is 10% of disk which can be several GB.
JOURNALD_CONF="/etc/systemd/journald.conf"
if ! grep -q '^SystemMaxUse=200M' "$JOURNALD_CONF" 2>/dev/null; then
  backup "$JOURNALD_CONF"
  sed -i 's/^#\?SystemMaxUse=.*/SystemMaxUse=200M/' "$JOURNALD_CONF"
  if ! grep -q '^SystemMaxUse=' "$JOURNALD_CONF"; then
    echo 'SystemMaxUse=200M' >> "$JOURNALD_CONF"
  fi
  systemctl restart systemd-journald
  echo "  journald capped at 200MB"
fi

# Vacuum existing journal if oversized
journalctl --vacuum-size=200M 2>/dev/null || true

# Docker container logs are already limited by daemon.json (10m x 3 files).
# MC's internal logs are limited by log4j2.xml (10MB x 3 days, auto-delete).
echo "  Docker logs: 10MB × 3 files (daemon.json)"
echo "  MC logs: 10MB × 3 days (log4j2.xml)"

# =============================================================================
# 8. restic (for on-demand backups outside the mc-backup container)
# =============================================================================
echo ""
echo "=== 7. restic ==="

apt_install restic zip
echo "  restic installed: $(restic version 2> /dev/null || echo 'unknown')"
echo "  zip installed: $(zip --version 2> /dev/null | head -1 || echo 'unknown')"

# =============================================================================
# 9. Apply deferred SSH hardening (last step - kills root access)
# =============================================================================
echo ""
echo "=== 9. Applying SSH hardening ==="
systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null \
  || systemctl restart ssh 2>/dev/null || systemctl restart sshd 2>/dev/null || true
echo "  SSH reloaded: root login disabled, key-only auth active."

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
echo " fail2ban: SSH + MC connection spam + MC login flood + nginx exploit scans"
echo " Docker: iptables=false (won't bypass UFW), log rotation enabled"
echo " Swap: 2G safety net (swappiness=10)"
echo " All other inbound traffic is blocked."
echo "=================================================================="
