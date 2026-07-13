#!/usr/bin/env python3
"""Discord bot: role sync, /register, /mc admin commands, and in-game command logging.

Runs in the discord-sync container (both compose profiles) with this file
bind-mounted read-only - changes need a force-recreate, not a restart
(deploy.sh and the CI infra step both do this).

What it does:
  - /register + /unregister: Discord<->Minecraft username linking, verified
    against the Mojang API, persisted to /data/discord-players.json and
    mirrored into dcintegration's LinkedPlayers.json
  - Role sync loop (60s): @Player -> whitelist, @Admin -> whitelist + op,
    removal reverses both, all via RCON
  - /mc command group: predefined admin commands (status, say, restart,
    give, tempban, ...) - @Admin role required, audit-logged to #minecraft,
    no free-text RCON passthrough
  - Log watcher: tails the mc container log and relays in-game admin
    activity to Discord - both legacy "issued server command" lines
    (COMMAND_LOG_ALLOWLIST) and 1.21's logAdminCommands feedback
    broadcasts like "[Name: Gave 1 [Totem] to Name]" (ADMIN_ACTION_RE)

Architecture constraints (violating these has caused real outages):
  - This bot SHARES its token with the dcintegration Fabric mod (chat
    bridge). Slash commands are guild-scoped and owned HERE; setup_hook
    purges the global registry every boot because the mod's command feature,
    if enabled, bulk-overwrites the registry on every mc boot and wipes our
    commands. deploy.sh forces the mod's [commands] enabled=false.
  - RCON goes through ThreadSafeRcon: stock mcrcon arms SIGALRM, which
    raises "signal only works in main thread" under asyncio.to_thread.
  - RCON returning None usually means the server is autopaused, not down -
    handle it gracefully (see /mc status).
"""

import asyncio
import json
import logging
import os
import re
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import aiohttp
import discord
from discord import app_commands
from mcrcon import MCRcon

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    stream=sys.stdout,
)
# Suppress discord.py's "voice will NOT be supported" warnings — we
# don't use voice and intentionally don't install PyNaCl/davey.
logging.getLogger("discord.client").setLevel(logging.ERROR)
log = logging.getLogger("discord-sync")

# === Config ===================================================================

DISCORD_BOT_TOKEN = os.environ["DISCORD_BOT_TOKEN"]
GUILD_ID = int(os.environ["DISCORD_GUILD_ID"])
CHANNEL_ID = int(os.environ.get("DISCORD_CHANNEL_ID", "0"))
RCON_HOST = os.environ.get("RCON_HOST", "mc")
RCON_PORT = int(os.environ.get("RCON_PORT", "25575"))
RCON_PASSWORD = os.environ["RCON_PASSWORD"]
SYNC_INTERVAL = int(os.environ.get("SYNC_INTERVAL", "60"))
DATA_PATH = Path(os.environ.get("DATA_PATH", "/data/discord-players.json"))
LINKED_PLAYERS_PATH = Path("/data/DiscordIntegration-Data/LinkedPlayers.json")

PLAYER_ROLE = "Player"
ADMIN_ROLE = "Admin"
MOJANG_API = "https://api.mojang.com/users/profiles/minecraft/"

# Chunky pre-generation markers (written by idle-tasks.sh on completion)
CHUNKY_MARKERS = {
    "Overworld": Path("/data/.chunky-complete"),
    "Nether": Path("/data/.chunky-nether-complete"),
    "End": Path("/data/.chunky-end-complete"),
    "Paradise Lost": Path("/data/.chunky-paradise-lost-complete"),
}
CHUNKY_WORLD_TO_LABEL = {
    "minecraft:overworld": "Overworld",
    "minecraft:the_nether": "Nether",
    "minecraft:the_end": "End",
    "paradise_lost:paradise_lost": "Paradise Lost",
}
CHUNKY_TASK_FILES = {
    "Overworld": Path("/data/config/chunky/tasks/minecraft/overworld.properties"),
    "Nether": Path("/data/config/chunky/tasks/minecraft/the_nether.properties"),
    "End": Path("/data/config/chunky/tasks/minecraft/the_end.properties"),
    "Paradise Lost": Path("/data/config/chunky/tasks/paradise_lost/paradise_lost.properties"),
}

# === Validation ===============================================================

MC_USERNAME_RE = re.compile(r"^[a-zA-Z0-9_]{3,16}$")
MC_IDENTIFIER_RE = re.compile(r"^[a-z][a-z0-9_.]*:[a-z][a-z0-9_/.]*$")
MC_BARE_ID_RE = re.compile(r"^[a-z][a-z0-9_]*$")
SANITISE_RE = re.compile(r"[^\x20-\x7e]")

RESTART_CONTAINERS = ["mc", "nav-proxy", "pack-web", "cloudflared", "mod-checker"]

# Impactful admin/OP commands to relay to Discord (see COMMANDS.md)
COMMAND_LOG_ALLOWLIST = re.compile(
    r"^/("
    # OP-only vanilla commands
    r"op|deop|gamemode|tp|teleport|give|kill|kick|ban|pardon"
    r"|difficulty|gamerule|time|weather|whitelist"
    r"|setworldspawn|worldborder|summon|effect|enchant"
    r"|experience|xp|clear|fill|setblock|clone"
    r"|save-all|save-off|save-on|stop|reload|tick"
    r"|spreadplayers|spawnpoint|tellraw|title"
    # LuckPerms
    r"|lp"
    # Essential Commands admin cheats
    r"|fly|heal|feed|repair|invuln|extinguish|day|night"
    # Enchanting commands
    r"|ec enchant"
    # Player data wipe
    r"|delete_all_player_data"
    r")\b",
    re.IGNORECASE,
)
COMMAND_LOG_RE = re.compile(r"(\w+) issued server command: (.+)")
# Vanilla 1.21 does NOT log "issued server command" lines for player
# commands. Admin actions surface as feedback broadcasts instead (gated by
# the logAdminCommands gamerule, on by default):
#   [PlayerName: Set own game mode to Creative Mode]
#   [PlayerName: Gave 1 [Totem of Undying] to PlayerName]
# Anchored to the full log4j2 prefix (log4j2 logPattern) so a
# chat line containing "]: [...]" can't spoof it - chat renders as
# "]: <name> msg", never "]: [" immediately after the prefix. Rcon and
# Server actors are excluded: /mc commands are already audited at the
# source with the Discord admin's name (audit()), and relaying Rcon
# would double-post.
ADMIN_ACTION_RE = re.compile(
    r"^\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\]: \[(\w{1,16}): (.+)\]\s*$"
)
ADMIN_ACTION_EXCLUDE = {"Rcon", "Server"}


def validate_player(name: str) -> str | None:
    if MC_USERNAME_RE.match(name):
        return name
    return None


def validate_identifier(raw: str) -> str | None:
    """Validate and normalise a Minecraft identifier (item, effect, enchantment, entity)."""
    raw = raw.lower().strip()
    if MC_IDENTIFIER_RE.match(raw):
        return raw
    if MC_BARE_ID_RE.match(raw):
        return f"minecraft:{raw}"
    return None


def sanitise_message(text: str, max_len: int = 200) -> str:
    text = SANITISE_RE.sub("", text)
    if text.startswith("/"):
        text = text.lstrip("/")
    text = text.replace("@everyone", "everyone").replace("@here", "here")
    return text[:max_len]


# === Cooldowns and timers =====================================================

_cooldowns: dict[str, float] = {}
_pending_pardons: dict[str, asyncio.Task] = {}
_pending_gamemode: dict[str, asyncio.Task] = {}


def check_cooldown(key: str, seconds: int) -> int | None:
    """Return remaining seconds if on cooldown, else set cooldown and return None."""
    now = time.monotonic()
    expires = _cooldowns.get(key, 0)
    if now < expires:
        return int(expires - now)
    _cooldowns[key] = now + seconds
    return None


# === RCON helpers =============================================================

class ThreadSafeRcon(MCRcon):
    """MCRcon without SIGALRM timeouts.

    mcrcon arms SIGALRM in __init__ and _read, which raises ValueError
    ("signal only works in main thread") when called via asyncio.to_thread.
    Use a socket timeout instead."""

    def __init__(self, host: str, password: str, port: int = 25575, timeout: int = 5) -> None:
        self.host = host
        self.password = password
        self.port = port
        self.tlsmode = 0
        self.timeout = timeout

    def connect(self) -> None:
        self.socket = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self._send(3, self.password)

    def _read(self, length: int) -> bytes:
        data = b""
        while len(data) < length:
            data += self.socket.recv(length - len(data))
        return data


def rcon_command(cmd: str) -> str | None:
    try:
        with ThreadSafeRcon(RCON_HOST, RCON_PASSWORD, port=RCON_PORT) as mcr:
            resp = mcr.command(cmd)
            log.info("RCON %s -> %s", cmd, resp.strip())
            return resp
    except Exception as e:
        log.warning("RCON failed (%s): %s", cmd, e)
        return None


def rcon_batch(commands: list[str]) -> dict[str, str | None]:
    results = {}
    try:
        with ThreadSafeRcon(RCON_HOST, RCON_PASSWORD, port=RCON_PORT) as mcr:
            for cmd in commands:
                try:
                    resp = mcr.command(cmd)
                    log.info("RCON %s -> %s", cmd, resp.strip())
                    results[cmd] = resp
                except Exception as e:
                    log.warning("RCON command failed (%s): %s", cmd, e)
                    results[cmd] = None
    except Exception as e:
        log.warning("RCON connection failed: %s", e)
        for cmd in commands:
            results[cmd] = None
    return results


async def async_rcon(cmd: str) -> str | None:
    return await asyncio.to_thread(rcon_command, cmd)


async def async_rcon_batch(cmds: list[str]) -> dict[str, str | None]:
    return await asyncio.to_thread(rcon_batch, cmds)


async def get_online_players() -> list[str]:
    result = await async_rcon("list")
    if not result:
        return []
    match = re.search(r":\s*(.*)", result)
    if not match or not match.group(1).strip():
        return []
    return [p.strip() for p in match.group(1).split(",") if p.strip()]


async def get_player_pos(player: str) -> tuple[int, int, int] | None:
    result = await async_rcon(f"data get entity {player} Pos")
    if not result:
        return None
    nums = re.findall(r"(-?\d+(?:\.\d+)?)", result)
    if len(nums) >= 3:
        return (int(float(nums[0])), int(float(nums[1])), int(float(nums[2])))
    return None


def parse_spark_health(text: str) -> dict[str, str]:
    info: dict[str, str] = {}
    if not text:
        return info
    tps = re.search(r"\*?([\d.]+),", text)
    if tps:
        info["tps"] = tps.group(1)
    mspt = re.search(r"Average:\s*([\d.]+)ms", text)
    if mspt:
        info["mspt"] = mspt.group(1)
    mem = re.search(r"Used:\s*(\d+)\s*MB\s*/\s*(\d+)\s*MB", text)
    if mem:
        used_gb = int(mem.group(1)) / 1024
        total_gb = int(mem.group(2)) / 1024
        info["memory"] = f"{used_gb:.1f}G/{total_gb:.1f}G"
    return info


def parse_chunky_progress(text: str) -> dict[str, str]:
    """Parse Chunky progress RCON output into structured fields."""
    info: dict[str, str] = {}
    if not text:
        return info
    if "No tasks running" in text:
        info["idle"] = "true"
        return info
    world = re.search(r"Task running for (\S+)", text)
    if world:
        info["world"] = world.group(1)
    pct = re.search(r"\((\d+(?:\.\d+)?)%\)", text)
    if pct:
        info["percent"] = pct.group(1)
    eta = re.search(r"ETA:\s*(\S+)", text)
    if eta:
        info["eta"] = eta.group(1)
    rate = re.search(r"Rate:\s*([\d.]+)", text)
    if rate:
        info["rate"] = rate.group(1)
    return info


def get_chunky_task_info(dim: str) -> dict[str, str] | None:
    """Read saved Chunky task properties from disk to get paused progress."""
    task_file = CHUNKY_TASK_FILES.get(dim)
    if not task_file or not task_file.exists():
        return None
    try:
        props: dict[str, str] = {}
        for line in task_file.read_text().splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
        chunks = int(props.get("chunks", "0"))
        radius = float(props.get("radius", "0"))
        if radius <= 0:
            return None
        total = ((2 * radius) / 16) ** 2
        pct = min(100.0, (chunks / total) * 100) if total > 0 else 0
        cancelled = props.get("cancelled", "false") == "true"
        return {"percent": f"{pct:.1f}", "cancelled": str(cancelled).lower()}
    except Exception:
        return None


def get_bluemap_sidecar_status() -> list[str]:
    """Status lines for the standalone bluemap sidecar container.

    BlueMap runs as a CLI sidecar (no RCON interface); the container's
    state and recent render log lines are the observable status.
    """
    try:
        result = subprocess.run(
            ["docker", "inspect", "bluemap", "--format",
             "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode != 0:
            return ["    (sidecar not found)"]
        state = result.stdout.strip() or "unknown"
        lines = [f"    renderer: {state}"]
        logs = subprocess.run(
            ["docker", "logs", "bluemap", "--tail", "50"],
            capture_output=True, text=True, timeout=5,
        )
        progress = re.findall(r"Rendering.*?(\d+(?:\.\d+)?)\s*%|Update finished", logs.stdout + logs.stderr)
        if progress:
            last = progress[-1]
            lines.append(f"    rendering ({last}%)" if last else "    up to date")
        return lines
    except Exception:
        return ["    (unavailable)"]


def get_container_uptime(container: str) -> str:
    """Get human-readable uptime from a Docker container's start time."""
    try:
        result = subprocess.run(
            ["docker", "inspect", container, "--format", "{{.State.StartedAt}}"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode != 0:
            return "?"
        started = datetime.fromisoformat(result.stdout.strip().replace("Z", "+00:00"))
        delta = datetime.now(timezone.utc) - started
        days = delta.days
        hours, remainder = divmod(delta.seconds, 3600)
        minutes = remainder // 60
        if days > 0:
            return f"{days}d {hours}h"
        if hours > 0:
            return f"{hours}h {minutes}m"
        return f"{minutes}m"
    except Exception:
        return "?"


def get_last_backup_time() -> str:
    """Get the last successful backup time from mc-backup container logs."""
    try:
        result = subprocess.run(
            ["docker", "logs", "mc-backup", "--tail", "50"],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode != 0:
            return "?"
        output = result.stdout + result.stderr
        matches = re.findall(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+\d{4}).*snapshot\s+\w+\s+saved", output)
        if not matches:
            return "no snapshots"
        last = datetime.fromisoformat(matches[-1])
        delta = datetime.now(timezone.utc) - last
        days = delta.days
        hours, remainder = divmod(delta.seconds, 3600)
        minutes = remainder // 60
        if days > 0:
            return f"{days}d {hours}h ago"
        if hours > 0:
            return f"{hours}h {minutes}m ago"
        return f"{minutes}m ago"
    except Exception:
        return "?"


# === Mojang API ===============================================================

async def resolve_mc_uuid(username: str) -> str | None:
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(f"{MOJANG_API}{username}") as resp:
                if resp.status == 200:
                    data = await resp.json()
                    raw = data["id"]
                    return f"{raw[:8]}-{raw[8:12]}-{raw[12:16]}-{raw[16:20]}-{raw[20:]}"
                if resp.status == 404:
                    return None
                log.warning("Mojang API returned %d for %s", resp.status, username)
                return None
    except Exception as e:
        log.warning("Mojang API lookup failed for %s: %s", username, e)
        return None


# === DCIntegration LinkedPlayers ==============================================

def update_linked_players(discord_id: str, mc_uuid: str | None, remove: bool = False) -> None:
    if not LINKED_PLAYERS_PATH.parent.exists():
        LINKED_PLAYERS_PATH.parent.mkdir(parents=True, exist_ok=True)

    links: list[dict] = []
    if LINKED_PLAYERS_PATH.exists():
        try:
            links = json.loads(LINKED_PLAYERS_PATH.read_text())
        except (json.JSONDecodeError, ValueError):
            links = []

    links = [entry for entry in links if entry.get("discordID") != discord_id]

    if not remove and mc_uuid:
        links.append({
            "discordID": discord_id,
            "mcPlayerUUID": mc_uuid,
            "floodgateUUID": "",
            "settings": {
                "useDiscordNameInChannel": True,
                "ignoreDiscordChatIngame": False,
                "ignoreReactions": False,
                "pingSound": True,
                "hideFromDiscord": False,
            },
        })

    LINKED_PLAYERS_PATH.write_text(json.dumps(links, indent=2) + "\n")
    log.info("Updated LinkedPlayers.json - %d entries", len(links))


# === Persistence helpers ======================================================

def load_mappings() -> dict[str, str]:
    if DATA_PATH.exists():
        return json.loads(DATA_PATH.read_text())
    return {}


def save_mappings(mappings: dict[str, str]) -> None:
    DATA_PATH.parent.mkdir(parents=True, exist_ok=True)
    DATA_PATH.write_text(json.dumps(mappings, indent=2) + "\n")


def member_roles(member: discord.Member) -> set[str]:
    return {r.name for r in member.roles}


# === Bot class (role sync) ====================================================

class SyncBot(discord.Client):
    def __init__(self) -> None:
        intents = discord.Intents.default()
        intents.members = True
        super().__init__(intents=intents)
        self.tree = app_commands.CommandTree(self)
        self._sync_task: asyncio.Task | None = None
        self._last_sync_state: dict[str, tuple[bool, bool]] = {}

    async def setup_hook(self) -> None:
        guild = discord.Object(id=GUILD_ID)
        self.tree.add_command(mc_group)
        self.tree.copy_global_to(guild=guild)
        await self.tree.sync(guild=guild)
        log.info("Slash commands synced to guild %s", GUILD_ID)
        # The dcintegration mod shares this bot token and, if its command
        # feature is ever enabled, bulk-overwrites the application's command
        # registry on mc boot - wiping our guild commands. Guild commands are
        # the only source of truth: purge the global registry every startup.
        self.tree.clear_commands(guild=None)
        await self.tree.sync()
        log.info("Global command registry purged (guild commands are canonical)")

    async def on_ready(self) -> None:
        log.info("Logged in as %s (id=%s)", self.user, self.user.id)
        if self._sync_task is None:
            self._sync_task = asyncio.create_task(self._sync_loop())
            asyncio.create_task(self._command_log_watcher())

    async def _sync_loop(self) -> None:
        await self.wait_until_ready()
        while not self.is_closed():
            try:
                await self._do_sync()
            except Exception:
                log.exception("Sync cycle failed")
            await asyncio.sleep(SYNC_INTERVAL)

    async def _do_sync(self) -> None:
        guild = self.get_guild(GUILD_ID)
        if guild is None:
            log.warning("Guild %s not found - skipping sync", GUILD_ID)
            return

        mappings = load_mappings()
        if not mappings:
            return

        commands = []
        new_state: dict[str, tuple[bool, bool]] = {}

        for discord_id, mc_name in list(mappings.items()):
            if not MC_USERNAME_RE.match(mc_name):
                log.warning("Skipping invalid username from mappings: %r", mc_name)
                continue

            member = guild.get_member(int(discord_id))
            if member is None:
                continue

            roles = member_roles(member)
            has_player = PLAYER_ROLE in roles
            has_admin = ADMIN_ROLE in roles
            current = (has_player, has_admin)
            previous = self._last_sync_state.get(discord_id)

            if current == previous:
                continue

            if has_player or has_admin:
                commands.append(f"whitelist add {mc_name}")
            if has_admin:
                commands.append(f"op {mc_name}")
            if not has_player and not has_admin:
                commands.append(f"whitelist remove {mc_name}")
                commands.append(f"deop {mc_name}")
            if has_player and not has_admin:
                commands.append(f"deop {mc_name}")

            new_state[discord_id] = current

        if commands:
            await async_rcon_batch(commands)
            self._last_sync_state.update(new_state)
            log.info(
                "Sync cycle - %d commands sent for %d state changes",
                len(commands),
                len(new_state),
            )


    async def _command_log_watcher(self) -> None:
        """Tail the mc container log and relay admin commands to Discord."""
        await self.wait_until_ready()
        log.info("Command log watcher started")
        while not self.is_closed():
            try:
                proc = await asyncio.create_subprocess_exec(
                    "docker", "logs", "mc", "-f", "--since", "1s",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.STDOUT,
                )
                if not proc.stdout:
                    await proc.wait()
                    continue
                async for raw_line in proc.stdout:
                    line = raw_line.decode("utf-8", errors="replace").strip()
                    match = COMMAND_LOG_RE.search(line)
                    if match:
                        player, command = match.group(1), match.group(2)
                        if COMMAND_LOG_ALLOWLIST.match(command):
                            await audit_msg(f"**{player}** ran `{command}`")
                        continue
                    action = ADMIN_ACTION_RE.search(line)
                    if action and action.group(1) not in ADMIN_ACTION_EXCLUDE:
                        await audit_msg(f"**{action.group(1)}** {action.group(2)}")
                await proc.wait()
            except Exception:
                log.exception("Command log watcher error")
            await asyncio.sleep(5)


bot = SyncBot()


# === Audit logging ============================================================

async def audit(interaction: discord.Interaction, action: str) -> None:
    if not CHANNEL_ID:
        return
    try:
        channel = bot.get_channel(CHANNEL_ID)
        if channel and isinstance(channel, discord.TextChannel):
            await channel.send(f"**{interaction.user.display_name}** {action}")
    except Exception:
        log.exception("Failed to send audit message")


async def audit_msg(message: str) -> None:
    if not CHANNEL_ID:
        return
    try:
        channel = bot.get_channel(CHANNEL_ID)
        if channel and isinstance(channel, discord.TextChannel):
            await channel.send(message)
    except Exception:
        log.exception("Failed to send audit message")


# === Confirmation view ========================================================

class ConfirmView(discord.ui.View):
    def __init__(self) -> None:
        super().__init__(timeout=30)
        self.value: bool | None = None

    @discord.ui.button(label="Confirm", style=discord.ButtonStyle.danger)
    async def confirm_btn(
        self, interaction: discord.Interaction, button: discord.ui.Button
    ) -> None:
        self.value = True
        self.stop()
        await interaction.response.defer()

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel_btn(
        self, interaction: discord.Interaction, button: discord.ui.Button
    ) -> None:
        self.value = False
        self.stop()
        await interaction.response.defer()


# === Admin check ==============================================================

def is_admin():
    async def predicate(interaction: discord.Interaction) -> bool:
        if not isinstance(interaction.user, discord.Member):
            return False
        return ADMIN_ROLE in member_roles(interaction.user)

    return app_commands.check(predicate)


# === /mc command group ========================================================

mc_group = app_commands.Group(
    name="mc", description="Server admin commands (Admin role required)"
)


# --- /mc status ---------------------------------------------------------------

@mc_group.command(name="status", description="Server health, online players, and roster")
@is_admin()
async def mc_status(interaction: discord.Interaction) -> None:
    await interaction.response.defer()

    rcon_results = await async_rcon_batch(["spark health", "list", "chunky progress"])
    health_raw = rcon_results.get("spark health")
    list_raw = rcon_results.get("list")
    chunky_raw = rcon_results.get("chunky progress")

    health = parse_spark_health(health_raw or "")
    tps = health.get("tps", "?")
    mspt = health.get("mspt", "?")
    memory = health.get("memory", "?")
    server_up = health_raw is not None

    # Uptime and backup (Docker queries, run in thread to avoid blocking)
    uptime, last_backup = await asyncio.gather(
        asyncio.to_thread(get_container_uptime, "mc"),
        asyncio.to_thread(get_last_backup_time),
    )

    # Online players from batch result
    online: list[str] = []
    if list_raw:
        match = re.search(r":\s*(.*)", list_raw)
        if match and match.group(1).strip():
            online = [p.strip() for p in match.group(1).split(",") if p.strip()]

    # Player coords
    player_lines = []
    if online:
        for p in online:
            pos = await get_player_pos(p)
            if pos:
                player_lines.append(f"  {p} ({pos[0]}, {pos[1]}, {pos[2]})")
            else:
                player_lines.append(f"  {p}")

    # Chunky pre-generation status
    chunky = parse_chunky_progress(chunky_raw or "")
    active_label = CHUNKY_WORLD_TO_LABEL.get(chunky.get("world", ""), "")
    all_done = True
    chunky_lines = []
    for dim in ("Overworld", "Nether", "End", "Paradise Lost"):
        if CHUNKY_MARKERS[dim].exists():
            chunky_lines.append(f"    {dim:14s} done")
        else:
            all_done = False
            if dim == active_label:
                pct = chunky.get("percent", "?")
                eta = chunky.get("eta", "")
                detail = f"{pct}%"
                if eta:
                    detail += f"  ETA {eta}"
                chunky_lines.append(f"    {dim:14s} {detail} (running)")
            else:
                saved = get_chunky_task_info(dim)
                if saved:
                    chunky_lines.append(f"    {dim:14s} {saved['percent']}% (paused)")
                else:
                    chunky_lines.append(f"    {dim:14s} queued")

    # BlueMap render status (standalone sidecar - queried via Docker, not RCON)
    bluemap_lines = await asyncio.to_thread(get_bluemap_sidecar_status)

    # Roster from mappings
    mappings = load_mappings()
    guild = interaction.guild
    roster_lines = []
    for discord_id, mc_name in sorted(mappings.items(), key=lambda x: x[1].lower()):
        member = guild.get_member(int(discord_id)) if guild else None
        discord_name = f"@{member.display_name}" if member else f"<{discord_id}>"
        roles = member_roles(member) if member else set()
        role = "Admin" if ADMIN_ROLE in roles else "Player" if PLAYER_ROLE in roles else "None"
        status = "online" if mc_name in online else "offline"
        roster_lines.append(f"  {mc_name}  <->  {discord_name}  {role}  {status}")

    status_icon = "Online" if server_up else "Offline/Paused"
    parts = [
        "**Server Status**",
        "```",
        f"  {status_icon} | Uptime: {uptime} | Memory: {memory}",
        f"  TPS: {tps} | MSPT: {mspt}ms | Last backup: {last_backup}",
        "",
        f"  Players ({len(online)}):",
    ]
    parts.extend(player_lines or ["  (none)"])
    parts.append("")
    pregen_label = "Pre-generation (done):" if all_done else "Pre-generation:"
    parts.append(f"  {pregen_label}")
    parts.extend(chunky_lines)
    parts.append("")
    parts.append("  Map renders:")
    parts.extend(bluemap_lines)
    parts.append("")
    parts.append(f"  Roster ({len(mappings)} registered):")
    parts.extend(roster_lines or ["  (none)"])
    parts.append("```")

    await interaction.followup.send("\n".join(parts))


# --- /mc say ------------------------------------------------------------------

@mc_group.command(name="say", description="Broadcast a message to in-game chat")
@is_admin()
@app_commands.describe(message="Message to broadcast (max 200 chars)")
async def mc_say(interaction: discord.Interaction, message: str) -> None:
    clean = sanitise_message(message)
    if not clean:
        await interaction.response.send_message("Message is empty after sanitisation.", ephemeral=True)
        return
    await async_rcon(f"say [{interaction.user.display_name}] {clean}")
    await interaction.response.send_message(f"Broadcast: {clean}", ephemeral=True)
    await audit(interaction, f"broadcast: {clean}")


# --- /mc whisper --------------------------------------------------------------

@mc_group.command(name="whisper", description="Send a private message to a player in-game")
@is_admin()
@app_commands.describe(player="Minecraft username", message="Private message (max 200 chars)")
async def mc_whisper(interaction: discord.Interaction, player: str, message: str) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    clean = sanitise_message(message)
    if not clean:
        await interaction.response.send_message("Message is empty after sanitisation.", ephemeral=True)
        return
    result = await async_rcon(f"msg {player} [{interaction.user.display_name}] {clean}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(f"Whispered to **{player}**: {clean}", ephemeral=True)
    await audit(interaction, f"whispered to **{player}**")


# --- /mc weather --------------------------------------------------------------

@mc_group.command(name="weather", description="Change the weather")
@is_admin()
@app_commands.choices(choice=[
    app_commands.Choice(name="Clear", value="clear"),
    app_commands.Choice(name="Rain", value="rain"),
    app_commands.Choice(name="Thunder", value="thunder"),
])
async def mc_weather(interaction: discord.Interaction, choice: app_commands.Choice[str]) -> None:
    await async_rcon(f"weather {choice.value}")
    await interaction.response.send_message(f"Weather set to **{choice.name}**.", ephemeral=True)
    await audit(interaction, f"set weather to **{choice.name}**")


# --- /mc tempban --------------------------------------------------------------

@mc_group.command(name="tempban", description="Temporarily ban a player (default 10 minutes)")
@is_admin()
@app_commands.describe(player="Minecraft username", minutes="Duration in minutes (1-1440, default 10)")
async def mc_tempban(interaction: discord.Interaction, player: str, minutes: int = 10) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    minutes = max(1, min(1440, minutes))

    view = ConfirmView()
    await interaction.response.send_message(
        f"Ban **{player}** for **{minutes}** minutes?", view=view, ephemeral=True
    )
    timed_out = await view.wait()
    if timed_out or not view.value:
        await interaction.edit_original_response(content="Cancelled.", view=None)
        return

    await async_rcon(f"kick {player} Temporarily banned for {minutes} minutes")
    await async_rcon(f"ban {player} Temporarily banned for {minutes} minutes")
    await interaction.edit_original_response(
        content=f"Banned **{player}** for **{minutes}** minutes.", view=None
    )
    await audit(interaction, f"temp-banned **{player}** for **{minutes}** minutes")

    # Cancel any existing pardon timer for this player
    existing = _pending_pardons.pop(player.lower(), None)
    if existing:
        existing.cancel()

    async def auto_pardon() -> None:
        await asyncio.sleep(minutes * 60)
        await async_rcon(f"pardon {player}")
        _pending_pardons.pop(player.lower(), None)
        await audit_msg(f"Auto-pardoned **{player}** (temp-ban expired)")
        log.info("Auto-pardoned %s after %d minutes", player, minutes)

    _pending_pardons[player.lower()] = asyncio.create_task(auto_pardon())


# --- /mc pardon ---------------------------------------------------------------

@mc_group.command(name="pardon", description="Unban a player")
@is_admin()
@app_commands.describe(player="Minecraft username")
async def mc_pardon(interaction: discord.Interaction, player: str) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    # Cancel any pending auto-pardon timer
    existing = _pending_pardons.pop(player.lower(), None)
    if existing:
        existing.cancel()
    await async_rcon(f"pardon {player}")
    await interaction.response.send_message(f"Pardoned **{player}**.", ephemeral=True)
    await audit(interaction, f"pardoned **{player}**")


# --- /mc restart --------------------------------------------------------------

@mc_group.command(name="restart", description="Restart the game server and web services (10 min cooldown)")
@is_admin()
async def mc_restart(interaction: discord.Interaction) -> None:
    remaining = check_cooldown("restart", 600)
    if remaining:
        await interaction.response.send_message(
            f"Restart on cooldown - try again in **{remaining}s**.", ephemeral=True
        )
        return

    view = ConfirmView()
    await interaction.response.send_message(
        "Restart the server? Players will be kicked after a 30s countdown.", view=view, ephemeral=True
    )
    timed_out = await view.wait()
    if timed_out or not view.value:
        _cooldowns.pop("restart", None)
        await interaction.edit_original_response(content="Cancelled.", view=None)
        return

    await interaction.edit_original_response(content="Restart initiated.", view=None)
    asyncio.create_task(_do_restart(interaction))


async def _do_restart(interaction: discord.Interaction) -> None:
    admin_name = interaction.user.display_name
    try:
        await audit_msg(f"**{admin_name}** is restarting the server...")

        countdown = [("30 seconds", 20), ("10 seconds", 5), ("5", 1), ("4", 1), ("3", 1), ("2", 1), ("1", 1)]
        for msg, delay in countdown:
            await async_rcon(f"say Server restarting in {msg}...")
            await asyncio.sleep(delay)

        await async_rcon("kick @a Server restarting - back shortly")
        await asyncio.sleep(2)
        await async_rcon("save-all flush")
        await asyncio.sleep(5)

        await asyncio.to_thread(
            subprocess.run, ["docker", "restart", "mc"], check=True, timeout=120
        )

        # Wait for RCON to come back
        for attempt in range(30):
            await asyncio.sleep(10)
            result = await async_rcon("list")
            if result is not None:
                log.info("Server healthy after restart (attempt %d)", attempt + 1)
                break
        else:
            await audit_msg(f"Server did not respond after 5 minutes. Check manually.")
            return

        # Restart web services
        await asyncio.to_thread(
            subprocess.run,
            ["docker", "restart", "nav-proxy", "pack-web", "cloudflared", "mod-checker"],
            check=False,
            timeout=60,
        )

        await audit_msg("Server back online.")
    except Exception as e:
        log.exception("Restart failed")
        await audit_msg(f"Restart failed: {e}")


# --- /mc map-refresh ----------------------------------------------------------

@mc_group.command(name="map-refresh", description="Restart the map renderer (5 min cooldown)")
@is_admin()
async def mc_map_refresh(interaction: discord.Interaction) -> None:
    remaining = check_cooldown("map-refresh", 300)
    if remaining:
        await interaction.response.send_message(
            f"Map refresh on cooldown - try again in **{remaining}s**.", ephemeral=True
        )
        return
    # BlueMap runs as a CLI sidecar; restarting the container makes it
    # re-scan every map for changes (its file watcher covers normal updates).
    await interaction.response.send_message("Map renderer restarting - it will re-scan all maps.", ephemeral=True)
    await asyncio.to_thread(
        subprocess.run, ["docker", "restart", "bluemap"],
        capture_output=True, timeout=60,
    )
    await audit(interaction, "restarted the map renderer")


# --- /mc heal -----------------------------------------------------------------

@mc_group.command(name="heal", description="Fully restore a player's health")
@is_admin()
@app_commands.describe(player="Minecraft username (must be online)")
async def mc_heal(interaction: discord.Interaction, player: str) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    result = await async_rcon(f"heal {player}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(f"Healed **{player}**.", ephemeral=True)
    await audit(interaction, f"healed **{player}**")


# --- /mc feed -----------------------------------------------------------------

@mc_group.command(name="feed", description="Fully restore a player's hunger")
@is_admin()
@app_commands.describe(player="Minecraft username (must be online)")
async def mc_feed(interaction: discord.Interaction, player: str) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    result = await async_rcon(f"feed {player}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(f"Fed **{player}**.", ephemeral=True)
    await audit(interaction, f"fed **{player}**")


# --- /mc fly ------------------------------------------------------------------

@mc_group.command(name="fly", description="Toggle creative flight for a player")
@is_admin()
@app_commands.describe(player="Minecraft username (must be online)")
@app_commands.choices(toggle=[
    app_commands.Choice(name="On", value="true"),
    app_commands.Choice(name="Off", value="false"),
])
async def mc_fly(interaction: discord.Interaction, player: str, toggle: app_commands.Choice[str]) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    await async_rcon(f"fly {player} {toggle.value}")
    state = "on" if toggle.value == "true" else "off"
    await interaction.response.send_message(f"Flight **{state}** for **{player}**.", ephemeral=True)
    await audit(interaction, f"set flight **{state}** for **{player}**")


# --- /mc invuln ---------------------------------------------------------------

@mc_group.command(name="invuln", description="Toggle invulnerability for a player")
@is_admin()
@app_commands.describe(player="Minecraft username (must be online)")
@app_commands.choices(toggle=[
    app_commands.Choice(name="On", value="true"),
    app_commands.Choice(name="Off", value="false"),
])
async def mc_invuln(interaction: discord.Interaction, player: str, toggle: app_commands.Choice[str]) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    await async_rcon(f"invuln {player} {toggle.value}")
    state = "on" if toggle.value == "true" else "off"
    await interaction.response.send_message(f"Invulnerability **{state}** for **{player}**.", ephemeral=True)
    await audit(interaction, f"set invulnerability **{state}** for **{player}**")


# --- /mc extinguish -----------------------------------------------------------

@mc_group.command(name="extinguish", description="Put out fire on a player")
@is_admin()
@app_commands.describe(player="Minecraft username (must be online)")
async def mc_extinguish(interaction: discord.Interaction, player: str) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    await async_rcon(f"extinguish {player}")
    await interaction.response.send_message(f"Extinguished **{player}**.", ephemeral=True)
    await audit(interaction, f"extinguished **{player}**")


# --- /mc kill -----------------------------------------------------------------

@mc_group.command(name="kill", description="Kill a player (with confirmation)")
@is_admin()
@app_commands.describe(player="Minecraft username (must be online)")
async def mc_kill(interaction: discord.Interaction, player: str) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    view = ConfirmView()
    await interaction.response.send_message(f"Kill **{player}**?", view=view, ephemeral=True)
    timed_out = await view.wait()
    if timed_out or not view.value:
        await interaction.edit_original_response(content="Cancelled.", view=None)
        return
    await async_rcon(f"kill {player}")
    await interaction.edit_original_response(content=f"Killed **{player}**.", view=None)
    await audit(interaction, f"killed **{player}**")


# --- /mc give -----------------------------------------------------------------

@mc_group.command(name="give", description="Give a player an item")
@is_admin()
@app_commands.describe(
    player="Minecraft username (must be online)",
    item="Item ID (e.g. diamond_sword, minecraft:golden_apple)",
    count="Amount (1-64, default 1)",
)
async def mc_give(interaction: discord.Interaction, player: str, item: str, count: int = 1) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    item_id = validate_identifier(item)
    if not item_id:
        await interaction.response.send_message(
            "Invalid item ID. Use format like `diamond_sword` or `minecraft:golden_apple`.",
            ephemeral=True,
        )
        return
    count = max(1, min(64, count))
    result = await async_rcon(f"give {player} {item_id} {count}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(
        f"Gave **{player}** {count}x `{item_id}`.", ephemeral=True
    )
    await audit(interaction, f"gave **{player}** {count}x `{item_id}`")


# --- /mc effect ---------------------------------------------------------------

@mc_group.command(name="effect", description="Apply a status effect to a player")
@is_admin()
@app_commands.describe(
    player="Minecraft username (must be online)",
    effect="Effect ID (e.g. speed, minecraft:strength)",
    seconds="Duration in seconds (1-3600)",
    amplifier="Effect level 0-5 (0 = level I, default 0)",
)
async def mc_effect(
    interaction: discord.Interaction, player: str, effect: str, seconds: int, amplifier: int = 0
) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    effect_id = validate_identifier(effect)
    if not effect_id:
        await interaction.response.send_message(
            "Invalid effect ID. Use format like `speed` or `minecraft:strength`.",
            ephemeral=True,
        )
        return
    seconds = max(1, min(3600, seconds))
    amplifier = max(0, min(5, amplifier))
    result = await async_rcon(f"effect give {player} {effect_id} {seconds} {amplifier}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(
        f"Applied `{effect_id}` level {amplifier + 1} to **{player}** for {seconds}s.", ephemeral=True
    )
    await audit(
        interaction,
        f"applied `{effect_id}` level {amplifier + 1} to **{player}** for {seconds}s",
    )


# --- /mc enchant --------------------------------------------------------------

@mc_group.command(name="enchant", description="Enchant the item a player is holding")
@is_admin()
@app_commands.describe(
    player="Minecraft username (must be online, holding the item)",
    enchantment="Enchantment ID (e.g. sharpness, minecraft:efficiency)",
    level="Enchantment level",
)
async def mc_enchant(interaction: discord.Interaction, player: str, enchantment: str, level: int = 1) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    ench_id = validate_identifier(enchantment)
    if not ench_id:
        await interaction.response.send_message(
            "Invalid enchantment ID. Use format like `sharpness` or `minecraft:efficiency`.",
            ephemeral=True,
        )
        return
    level = max(1, min(255, level))
    result = await async_rcon(f"execute as {player} run ec enchant {ench_id} {level}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(
        f"Applied `{ench_id}` level {level} to **{player}**'s held item.", ephemeral=True
    )
    await audit(interaction, f"enchanted **{player}**'s held item with `{ench_id}` {level}")


# --- /mc gamemode -------------------------------------------------------------

@mc_group.command(name="gamemode", description="Change a player's game mode (optional auto-revert)")
@is_admin()
@app_commands.describe(
    player="Minecraft username (must be online)",
    mode="Game mode",
    minutes="Auto-revert to survival after N minutes (1-60, omit for permanent)",
)
@app_commands.choices(mode=[
    app_commands.Choice(name="Survival", value="survival"),
    app_commands.Choice(name="Creative", value="creative"),
    app_commands.Choice(name="Adventure", value="adventure"),
    app_commands.Choice(name="Spectator", value="spectator"),
])
async def mc_gamemode(
    interaction: discord.Interaction,
    player: str,
    mode: app_commands.Choice[str],
    minutes: int | None = None,
) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return

    result = await async_rcon(f"gamemode {mode.value} {player}")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return

    # Cancel any existing gamemode revert for this player
    existing = _pending_gamemode.pop(player.lower(), None)
    if existing:
        existing.cancel()

    if minutes is not None and mode.value != "survival":
        minutes = max(1, min(60, minutes))

        async def auto_revert() -> None:
            await asyncio.sleep(minutes * 60)
            await async_rcon(f"gamemode survival {player}")
            _pending_gamemode.pop(player.lower(), None)
            await audit_msg(f"Reverted **{player}** to survival (timed gamemode expired)")

        _pending_gamemode[player.lower()] = asyncio.create_task(auto_revert())
        await interaction.response.send_message(
            f"Set **{player}** to **{mode.name}** for **{minutes}** minutes.", ephemeral=True
        )
        await audit(interaction, f"set **{player}** to **{mode.name}** for **{minutes}** minutes")
    else:
        await interaction.response.send_message(
            f"Set **{player}** to **{mode.name}**.", ephemeral=True
        )
        await audit(interaction, f"set **{player}** to **{mode.name}**")


# --- /mc xp ------------------------------------------------------------------

@mc_group.command(name="xp", description="Give a player experience levels")
@is_admin()
@app_commands.describe(
    player="Minecraft username (must be online)",
    amount="Experience levels to add (1-100)",
)
async def mc_xp(interaction: discord.Interaction, player: str, amount: int) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    amount = max(1, min(100, amount))
    result = await async_rcon(f"experience add {player} {amount} levels")
    if result is not None and "No entity was found" in result:
        await interaction.response.send_message(f"**{player}** is not online.", ephemeral=True)
        return
    await interaction.response.send_message(
        f"Gave **{player}** {amount} experience levels.", ephemeral=True
    )
    await audit(interaction, f"gave **{player}** {amount} XP levels")


# --- /mc claims ---------------------------------------------------------------

@mc_group.command(name="claims", description="Set a player's maximum claim chunks")
@is_admin()
@app_commands.describe(
    player="Minecraft username",
    count="Maximum claim chunks (1-128)",
)
async def mc_claims(interaction: discord.Interaction, player: str, count: int) -> None:
    if not validate_player(player):
        await interaction.response.send_message("Invalid player name.", ephemeral=True)
        return
    count = max(1, min(128, count))
    await async_rcon(f"lp user {player} permission set xaero.pac_max_claims {count}")
    await interaction.response.send_message(
        f"Set **{player}**'s max claims to **{count}** chunks.", ephemeral=True
    )
    await audit(interaction, f"set **{player}**'s max claims to **{count}** chunks")


# --- /mc border ---------------------------------------------------------------

@mc_group.command(name="border", description="Set the player world border radius (does not affect pre-gen)")
@is_admin()
@app_commands.describe(radius="Radius in blocks from (0,0). Min 100, max 500,000. Omit to show current.")
async def mc_border(interaction: discord.Interaction, radius: int | None = None) -> None:
    if radius is None:
        result = await async_rcon("worldborder get")
        await interaction.response.send_message(
            f"Current vanilla world border: {result or 'unknown (server paused?)'}",
            ephemeral=True,
        )
        return
    if radius < 100 or radius > 500_000:
        await interaction.response.send_message(
            "Radius must be between 100 and 500,000 blocks.", ephemeral=True
        )
        return
    diameter = radius * 2
    nether_radius = radius // 8
    await async_rcon_batch([
        "worldborder center 0 0",
        f"worldborder set {diameter}",
        "chunky world minecraft:overworld",
        "chunky center 0 0",
        f"chunky radius {radius}",
        "chunky shape square",
        "chunky border add",
        "chunky world minecraft:the_nether",
        "chunky center 0 0",
        f"chunky radius {nether_radius}",
        "chunky shape square",
        "chunky border add",
    ])
    await interaction.response.send_message(
        f"Player border set to **{radius}** blocks "
        f"(overworld {diameter} diameter, nether {nether_radius} radius).\n"
        f"Resets to `PLAYER_BORDER_RADIUS` on next deploy.",
        ephemeral=True,
    )
    await audit(interaction, f"set player border to **{radius}** blocks (nether {nether_radius})")


# === /register and /unregister (with audit notifications) =====================

@bot.tree.command(name="register", description="Link your Discord account to a Minecraft username")
@app_commands.describe(minecraft_username="Your Minecraft Java Edition username (3-16 characters)")
async def register(interaction: discord.Interaction, minecraft_username: str) -> None:
    if not MC_USERNAME_RE.match(minecraft_username):
        await interaction.response.send_message(
            "That doesn't look like a valid Minecraft username. "
            "It should be 3-16 characters: letters, numbers, and underscores only.",
            ephemeral=True,
        )
        return

    mappings = load_mappings()
    discord_id = str(interaction.user.id)

    existing_name = mappings.get(discord_id)
    if existing_name and existing_name.lower() == minecraft_username.lower():
        await interaction.response.send_message(
            f"You're already registered as **{existing_name}**.",
            ephemeral=True,
        )
        return

    for uid, name in mappings.items():
        if name.lower() == minecraft_username.lower() and uid != discord_id:
            await interaction.response.send_message(
                f"**{minecraft_username}** is already registered to another Discord user.",
                ephemeral=True,
            )
            return

    await interaction.response.defer(ephemeral=True)

    mc_uuid = await resolve_mc_uuid(minecraft_username)
    if mc_uuid is None:
        await interaction.followup.send(
            f"**{minecraft_username}** wasn't found on Mojang's servers. "
            "Check the spelling - it must be your Java Edition username.",
        )
        return

    mappings[discord_id] = minecraft_username
    save_mappings(mappings)
    update_linked_players(discord_id, mc_uuid)
    log.info("Registered: %s (%s) -> %s (UUID: %s)", interaction.user, discord_id, minecraft_username, mc_uuid)

    await interaction.followup.send(
        f"Registered **{minecraft_username}** - you'll be whitelisted when you get the {PLAYER_ROLE} role.",
    )
    await audit_msg(
        f"**{interaction.user.display_name}** registered as **{minecraft_username}**"
    )


@bot.tree.command(name="unregister", description="Remove your Minecraft username link")
async def unregister(interaction: discord.Interaction) -> None:
    mappings = load_mappings()
    discord_id = str(interaction.user.id)

    mc_name = mappings.pop(discord_id, None)
    if mc_name is None:
        await interaction.response.send_message(
            "You don't have a Minecraft username registered.",
            ephemeral=True,
        )
        return

    save_mappings(mappings)
    update_linked_players(discord_id, mc_uuid=None, remove=True)
    await async_rcon(f"whitelist remove {mc_name}")
    await async_rcon(f"deop {mc_name}")
    log.info("Unregistered: %s (%s) was %s", interaction.user, discord_id, mc_name)

    await interaction.response.send_message(
        f"Unregistered **{mc_name}**. You've been removed from the whitelist.",
        ephemeral=True,
    )
    await audit_msg(
        f"**{interaction.user.display_name}** unregistered (was **{mc_name}**)"
    )


# === Error handler ============================================================

@bot.tree.error
async def on_app_command_error(
    interaction: discord.Interaction, error: app_commands.AppCommandError
) -> None:
    if isinstance(error, app_commands.CheckFailure):
        if not interaction.response.is_done():
            await interaction.response.send_message(
                f"You need the **{ADMIN_ROLE}** role to use `/mc` commands.", ephemeral=True
            )
    else:
        log.exception("Command error: %s", error)
        if not interaction.response.is_done():
            await interaction.response.send_message("Something went wrong.", ephemeral=True)


# === Main =====================================================================

if __name__ == "__main__":
    bot.run(DISCORD_BOT_TOKEN, log_handler=None)
