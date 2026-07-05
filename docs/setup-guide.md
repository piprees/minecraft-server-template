# Setup guide

From zero to a running Minecraft server. Follow each section in order; skip anything marked optional that you don't need.

---

## 1. Get the repository

You have two options on GitHub:

| Method | When to use |
| --- | --- |
| **Use this template** | Starting fresh. Creates a clean repo with no commit history. Best for most people. |
| **Fork** | You want to pull upstream changes later (e.g. mod updates, script improvements). Keeps the full history and lets you merge from the original repo. |

Either way, make the new repo **private** - it will contain deployment workflows that reference your secrets.

```bash
git clone git@github.com:YOUR_USERNAME/YOUR_REPO_NAME.git
cd YOUR_REPO_NAME
```

---

## 2. Accounts you'll need

Every account below is free for the tier you need. Each one is optional depending on your setup - skip what you don't use.

| Account | Why | Required when |
| --- | --- | --- |
| **GitHub** (free) | CI/CD, hosting the repo, deployment secrets | Always - you already have this |
| **Discord** (free) | Bot for player management, chat bridge, admin commands | You want Discord integration (recommended) |
| **Cloudflare** (free tier) | DNS management, HTTP tunnel for web services (map, status page, mod pack), R2 object storage for backups | You want web services or off-site backups |
| **Hetzner Cloud** / **DigitalOcean** / any Ubuntu 24.04 box | The server itself | Always - pick one |

**Hetzner** (~€11/mo for a cx33 x86 instance) is the cheapest cloud option with free L3/L4 DDoS protection. **DigitalOcean** (~$48/mo) costs 6x more. A **local machine** costs electricity only but needs DDNS or a static IP.

---

## 3. Run the setup wizard

```bash
./scripts/setup.sh
```

The wizard is interactive and safe to re-run - it fast-forwards past anything already configured. It will:

1. Ask for your credentials (cloud provider tokens, Discord bot token, Cloudflare keys, etc.)
2. Store them in 1Password (vault `Dev`, item `Minecraft Server`)
3. Generate your `.env` file by merging `.env` (committed non-secrets) with your secrets
4. Run preflight checks to validate everything is in order

If you don't use 1Password, you can skip the wizard and populate `.env` manually from `.env.example` + `.env`.

---

## 4. Discord bot setup

If you're not using Discord integration, skip to [section 5](#5-github-production-environment).

### Create the application

1. Go to [discord.com/developers/applications](https://discord.com/developers/applications)
2. Click **New Application**, name it (e.g. "MC Server Bot"), and create it

### Get the bot token

3. Go to the **Bot** tab in the left sidebar
4. Click **Reset Token**, confirm, and copy the token - this is your `DISCORD_BOT_TOKEN`
5. Store it somewhere safe; you can't view it again

### Enable privileged intents

6. Still on the Bot tab, scroll to **Privileged Gateway Intents**
7. Enable **MESSAGE CONTENT INTENT**
8. Enable **SERVER MEMBERS INTENT**
9. Save changes

### Generate the invite URL

10. Go to **OAuth2 → URL Generator** in the left sidebar
11. Under **Scopes**, tick: `bot`, `applications.commands`
12. Under **Bot Permissions**, tick:
    - Manage Roles
    - Send Messages
    - Embed Links
    - Read Message History
    - Use Slash Commands
13. Copy the generated URL at the bottom of the page

### Invite the bot

14. Open the URL in your browser
15. Select your Discord server from the dropdown
16. Click **Authorise**

### Collect IDs

You need Discord's Developer Mode to copy IDs:

17. Open Discord → **User Settings → App Settings → Advanced → Developer Mode** → turn it on

Now collect these four IDs:

| Value | How to get it |
| --- | --- |
| `DISCORD_GUILD_ID` | Right-click your **server name** (top of the channel list) → **Copy Server ID** |
| `DISCORD_CHANNEL_ID` | Right-click your **#minecraft channel** → **Copy Channel ID** |
| `DISCORD_ADMIN_ROLE_ID` | Right-click the **Admin role** (Server Settings → Roles, or in a member's role list) → **Copy Role ID** |
| `DISCORD_PLAYER_ROLE_ID` | Right-click the **Player role** → **Copy Role ID** |

### Create the webhook

18. Open your **#minecraft** channel's settings (gear icon or right-click → Edit Channel)
19. Go to **Integrations → Webhooks → New Webhook**
20. Name it (e.g. "MC Server"), optionally set an avatar
21. Click **Copy Webhook URL** - this is your `DISCORD_WEBHOOK_URL`

---

## 5. GitHub `production` environment

The CI/CD pipeline reads secrets and variables from a GitHub environment called `production`.

### Create the environment

1. Go to your repo on GitHub
2. **Settings → Environments → New environment**
3. Name it exactly `production` and click **Configure environment**

### Add secrets

Add each of the following as an **Environment secret** (Settings → Environments → production → Environment secrets → Add secret):

| Secret | What it is |
| --- | --- |
| `DEPLOY_SSH_KEY` | The **private** key for the `deploy` user (generated during hardening - the contents of `~/.ssh/mc_deploy_key`) |
| `RCON_PASSWORD` | RCON password for the Minecraft server (generated by `setup.sh` or set manually) |
| `R2_ACCOUNT_ID` | Cloudflare account ID (Cloudflare dashboard → right sidebar) |
| `R2_BUCKET` | R2 bucket name for backups (e.g. `mc-backups`) |
| `R2_ACCESS_KEY_ID` | R2 API token access key (Cloudflare → R2 → Manage R2 API Tokens) |
| `R2_SECRET_ACCESS_KEY` | R2 API token secret key (shown once at creation) |
| `RESTIC_PASSWORD` | Encryption passphrase for restic backups - **can't be recovered if lost** |
| `DISCORD_BOT_TOKEN` | From [section 4](#get-the-bot-token) |
| `DISCORD_CHANNEL_ID` | From [section 4](#collect-ids) |
| `DISCORD_GUILD_ID` | From [section 4](#collect-ids) |
| `DISCORD_WEBHOOK_URL` | From [section 4](#create-the-webhook) |
| `KUMA_API_KEY` | Uptime Kuma **socket.io session token** (not the Prometheus API key) - generate with `./scripts/kuma-token.sh` |
| `KUMA_PASSWORD` | Uptime Kuma admin password |
| `CLOUDFLARE_API_TOKEN` | Cloudflare API token with DNS and Tunnel permissions |
| `CLOUDFLARE_ACCOUNT_ID` | Same as `R2_ACCOUNT_ID` - your Cloudflare account ID |
| `CLOUDFLARE_ZONE_ID` | Cloudflare zone ID for your domain (domain overview → right sidebar) |
| `CLOUDFLARE_TUNNEL_ID` | Cloudflare Tunnel ID (created by `./scripts/cloudflare-setup.sh`) |

### Add variables

Add each of the following as an **Environment variable** (Settings → Environments → production → Environment variables → Add variable):

| Variable                 | What it is                                                                     |
| ------------------------ | ------------------------------------------------------------------------------ |
| `DROPLET_HOST`           | Your server's IP address or hostname (e.g. `203.0.113.42` or `mc.example.com`) |
| `DEPLOY_USER`            | SSH username on the server (default: `deploy`)                                 |
| `SERVER_DIR`             | Directory name on the server (optional - defaults to the repo name)            |
| `DISCORD_ADMIN_ROLE_ID`  | From [section 4](#collect-ids)                                                 |
| `DISCORD_PLAYER_ROLE_ID` | From [section 4](#collect-ids)                                                 |

---

## 6. Provision the server

```bash
./scripts/provision.sh
```

The script prompts you to choose a provider (Hetzner, DigitalOcean, or local) and creates the server. It's idempotent - running it again won't create duplicates.

- **Hetzner**: calls `./scripts/provision-hetzner.sh` (cx33 x86 by default; offers live alternatives interactively if the type is sold out)
- **DigitalOcean**: calls `./scripts/provision-droplet.sh`
- **Local**: skips provisioning - you provide the IP of an existing Ubuntu 24.04 machine

Note the server IP address from the output. You'll need it for the next steps and for the `DROPLET_HOST` GitHub variable.

---

## 7. Harden the server

```bash
./scripts/harden.sh --remote root@SERVER_IP
```

Run this once on a fresh server. It applies:

- Creates a `deploy` user with sudo + Docker access
- SSH key-only authentication (password auth disabled)
- Root SSH login disabled
- UFW firewall (default deny; allows SSH, game port 25577/tcp, voice chat 24454/udp)
- fail2ban (4 failures → 24h ban)
- iptables rate limiting for game and voice connections
- Docker configured with `iptables=false` so containers can't bypass UFW
- systemd journal capped, 2GB swap at swappiness 10

> **CRITICAL**: Before closing your root terminal session, open a **new terminal** and verify you can SSH in as the `deploy` user:
>
> ```bash
> ssh -i ~/.ssh/mc_deploy_key deploy@SERVER_IP
> ```
>
> Root SSH is disabled after hardening. If the deploy user doesn't work and you close your root session, you'll be locked out of the server.

---

## 8. Deploy

### Prepare the server

```bash
./scripts/prepare-droplet.sh
```

The script copies the deploy key to the server, clones the repo, writes the production `.env`, and sets the GitHub Actions variables.

### Run initial setup on the server

```bash
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST \
  'cd ~/YOUR_REPO_NAME && ./scripts/initial-setup.sh'
```

The script initialises the restic backup repository, seeds config files, pulls Docker images, and runs the first deploy via `deploy.sh`.

### Set up Cloudflare

```bash
./scripts/cloudflare-setup.sh
```

The script creates (or updates) the Cloudflare Tunnel, DNS records (A, SRV, CNAMEs for subdomains), and the R2 backup bucket. After that, your web services (map, status page, mod pack) are accessible at their subdomains.

### Verify

After deployment completes:

```bash
# Check the server is healthy
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST 'docker exec -i mc rcon-cli "list"'

# Check containers are running
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST 'docker ps'
```

From this point on, pushing to `main` auto-deploys. CI picks a deploy tier (full/infra/pull) based on what changed. See the README for details.

---

## 9. Per-OS notes

### macOS

```bash
brew install docker
```

Or install [Docker Desktop](https://www.docker.com/products/docker-desktop/) if you prefer the GUI. Standard setup - no special configuration needed.

### Linux (Ubuntu/Debian)

```bash
sudo apt update
sudo apt install docker.io docker-compose-plugin
sudo usermod -aG docker $USER
# Log out and back in for the group change to take effect
```

### Windows (WSL2)

1. Install [WSL2](https://learn.microsoft.com/en-us/windows/wsl/install) if you haven't already
2. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) and enable the **WSL 2 backend** in settings
3. Open your WSL2 distribution and clone the repo inside it

**Important - network access from the LAN**: By default, WSL2 runs in a NAT'd network. The Minecraft server will only be reachable from inside WSL, not from other machines on your LAN.

**Option A (recommended):** Add `networkingMode=mirrored` to your Windows `.wslconfig` file:

```ini
# %USERPROFILE%\.wslconfig
[wsl2]
networkingMode=mirrored
```

Then restart WSL (`wsl --shutdown` in PowerShell). This makes WSL2 share the host's network, so the server listens on your LAN IP directly.

**Option B (port forwarding):** If mirrored mode causes issues, forward the ports manually from an elevated PowerShell prompt:

```powershell
# Get the WSL IP
$wslIp = (wsl hostname -I).Trim().Split(' ')[0]

# Forward game port
netsh interface portproxy add v4tov4 listenport=25577 listenaddress=0.0.0.0 connectport=25577 connectaddress=$wslIp

# Forward voice chat port (TCP side - UDP requires additional tooling)
netsh interface portproxy add v4tov4 listenport=24454 listenaddress=0.0.0.0 connectport=24454 connectaddress=$wslIp
```

> **Note:** `netsh portproxy` must be re-run after every WSL restart because the WSL IP changes. You can add it to a Windows startup script. It also only proxies TCP - for UDP voice chat, mirrored mode is the better option.

---

## 10. Home hosting

Running the server on a machine at home instead of a cloud VPS. The Cloudflare tunnel still covers HTTP web services (map, status, mod pack) but the game port (TCP) and voice chat (UDP) must be handled separately.

### Dynamic DNS

If your ISP gives you a dynamic IP, set up DDNS to keep a DNS record pointing at your current public IP:

```bash
./scripts/ddns-update.sh --install-cron
```

The script installs a cron job that updates a Cloudflare A record with your public IP. It runs every 5 minutes and only calls the API when the IP actually changes.

**Requirements:** `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ZONE_ID`, and `DOMAIN` must be set in your `.env`.

### Port forwarding

Forward these ports in your router's admin panel (usually at `192.168.1.1` or `192.168.0.1`):

| Port    | Protocol | Purpose                |
| ------- | -------- | ---------------------- |
| `25577` | TCP      | Minecraft game traffic |
| `24454` | UDP      | Simple Voice Chat      |

Point them at the local IP of the machine running the server. Also allow them through UFW on the host:

```bash
sudo ufw allow 25577/tcp
sudo ufw allow 24454/udp
```

### Private play without port forwarding (Tailscale / ZeroTier)

If you don't want to expose ports to the internet - or your ISP uses CGNAT and port forwarding isn't possible - use a mesh VPN:

1. Install [Tailscale](https://tailscale.com) or [ZeroTier](https://www.zerotier.com) on the host machine
2. Each player installs the same tool on their machine
3. Players connect using the host's **Tailscale/ZeroTier IP** as the server address (e.g. `100.x.y.z:25577`)

No router configuration, no ports exposed to the public internet. Works through CGNAT and firewalls.

### Hiding your home IP (TCPShield)

If you forward ports, players connecting directly will see your home IP address. [TCPShield](https://tcpshield.com) (free tier) acts as a TCP reverse proxy:

1. Sign up and add your server
2. Point your game DNS record at TCPShield instead of your home IP
3. Add the [`tcpshield`](https://modrinth.com/mod/tcpshield) mod to `config/modrinth-mods.txt` so the server sees players' real IPs instead of TCPShield's proxy IPs

**Limitation:** TCPShield proxies TCP only. Voice chat (UDP on port 24454) can't go through it - voice traffic goes direct. If hiding your IP is critical, use Tailscale/ZeroTier for voice chat instead.

### Undoing home exposure

If you want to stop hosting at home and move to a VPS, or just remove the public exposure:

```bash
# 1. Remove port forwards from your router's admin panel

# 2. Close the firewall rules
sudo ufw delete allow 25577/tcp
sudo ufw delete allow 24454/udp

# 3. Remove the DDNS cron job
crontab -e
# Delete the line containing ddns-update.sh, save, and exit

# 4. Disable Tailscale/ZeroTier if no longer needed
# Tailscale:
sudo tailscale down
# ZeroTier:
sudo zerotier-cli leave <network-id>
```

---

## What's next

- **Roll seeds**: `./scripts/seed/roll-seeds.sh` to find a great world seed before launch
- **Manage players**: see [COMMANDS.md](../COMMANDS.md) for the Discord `/register` flow and admin commands
- **Local development**: `./dev up` to run everything locally
- **Troubleshooting**: see the README's troubleshooting section for common issues
