#!/usr/bin/env python3
"""Provision Uptime Kuma monitors, notifications, and status page.

Runs as the one-shot kuma-init container on every deploy. Idempotent AND
authoritative: config/uptime-kuma/kuma-config.json is the source of truth -
monitors removed from the config are deleted, monitors deleted via the Kuma
UI are recreated, drifted URLs/methods/parents are corrected. Always change
the config file, never just the UI.

Deliberately does NOT create game-port probes: every TCP/ping/JSON monitor
that touched the game port woke the server from autopause on its own
interval. Container-health + HTTP checks only.

Auth: KUMA_API_KEY (a socket.io session token, NOT the Prometheus API key
from Settings > API Keys; generate with ./scripts/kuma-token.sh --remote)
when present, else KUMA_USERNAME/KUMA_PASSWORD. A fresh Kuma install is
initialised automatically via api.setup() using those credentials.
Global settings apply on fresh installs only (set_settings clobbers).
"""

import json
import os
import re
import subprocess
import sys
import time
import urllib.request


CONFIG_PATH = os.environ.get(
    "KUMA_CONFIG", "/config/kuma-config.json"
)

MONITOR_TYPE_MAP = {
    "http": "HTTP",
    "port": "PORT",
    "ping": "PING",
    "docker": "DOCKER",
    "dns": "DNS",
    "group": "GROUP",
    "json-query": "JSON_QUERY",
}


def wait_for_kuma(url, timeout=90):
    """Block until Kuma responds on its entry-page endpoint."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"{url}/api/entry-page", timeout=5)
            return True
        except Exception:
            time.sleep(2)
    return False


def derive_pack_name(env):
    """Version string for the status page footer: <slug>-<mc>-v<sha>,
    identical to the pack page footer. The kuma-init container has no git
    checkout, so ask pack-web for the pack build it is actually serving
    (packwiz/pack.toml, written by build-modpack.sh). Git is a fallback
    for host-side runs; 'unknown' only if both fail."""
    pack_toml_url = env.get("PACK_TOML_URL", "http://pack-web/packwiz/pack.toml")
    for _ in range(3):
        try:
            with urllib.request.urlopen(pack_toml_url, timeout=10) as resp:
                toml_text = resp.read().decode("utf-8", "replace")
            version = re.search(r'^version\s*=\s*"([^"]+)"', toml_text, re.M)
            mc = re.search(r'^minecraft\s*=\s*"([^"]+)"', toml_text, re.M)
            if version:
                mc_ver = mc.group(1) if mc else env.get("MC_VERSION", "1.21.1")
                brand = env.get("BRAND_SLUG", "adventure")
                return f"{brand}-{mc_ver}-v{version.group(1)}"
            break  # fetched but unparseable - retrying won't help
        except Exception:
            time.sleep(2)

    mc_ver = env.get("MC_VERSION", "1.21.1")
    brand = env.get("BRAND_SLUG", "adventure")
    try:
        short_hash = subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            stderr=subprocess.DEVNULL, text=True
        ).strip()
    except Exception:
        short_hash = "unknown"
    return f"{brand}-{mc_ver}-v{short_hash}"


def load_config(env):
    """Load and interpolate the JSON config file."""
    try:
        with open(CONFIG_PATH, "r") as f:
            raw = f.read()
    except FileNotFoundError:
        print(f"ERROR: Config file not found: {CONFIG_PATH}")
        sys.exit(1)

    if "PACK_NAME" not in env:
        env["PACK_NAME"] = derive_pack_name(env)

    raw = re.sub(r"\$\{(\w+)\}", lambda m: env.get(m.group(1), m.group(0)), raw)
    return json.loads(raw)


def retry(fn, attempts=5, delay=5, label="operation"):
    """Retry a function with backoff."""
    for attempt in range(attempts):
        try:
            return fn()
        except Exception as e:
            if attempt == attempts - 1:
                print(f"  Warning: {label} failed after {attempts} attempts: {e}")
                return None
            time.sleep(delay)


def ensure_notification(api, notif_cfg, env):
    """Create or find the notification channel. Returns its ID or None."""
    existing = retry(api.get_notifications, label="fetch notifications") or []
    match = [n for n in existing if n.get("name") == notif_cfg["name"]]
    if match:
        nid = match[0]["id"]
        print(f"  Notification '{notif_cfg['name']}' already configured (id={nid})")
        return nid

    webhook_url = env.get(notif_cfg.get("webhookUrlEnv", ""), "")
    if not webhook_url:
        print(f"  Warning: No webhook URL for '{notif_cfg['name']}', skipping")
        return None

    from uptime_kuma_api import NotificationType
    try:
        result = api.add_notification(
            type=NotificationType.DISCORD,
            name=notif_cfg["name"],
            discordWebhookUrl=webhook_url,
            isDefault=notif_cfg.get("isDefault", True),
            applyExisting=notif_cfg.get("applyExisting", True),
        )
        nid = result["id"]
        print(f"  Added notification: {notif_cfg['name']} (id={nid})")
        return nid
    except Exception as e:
        print(f"  Warning: Could not add notification '{notif_cfg['name']}': {e}")
        return None


def ensure_docker_host(api):
    """Create or find the Docker host. Returns its ID or None."""
    try:
        existing = api.get_docker_hosts()
        match = [h for h in existing if h.get("name") == "Local Docker"]
        if match:
            did = match[0]["id"]
            print(f"  Docker host already configured (id={did})")
            return did
        result = api.add_docker_host(
            name="Local Docker",
            dockerType="socket",
            dockerDaemon="/var/run/docker.sock",
        )
        did = result["id"]
        print(f"  Added Docker host (id={did})")
        return did
    except Exception as e:
        print(f"  Docker host unavailable (socket not mounted?): {e}")
        return None


def get_monitor_type(type_str):
    """Map config type string to MonitorType enum."""
    from uptime_kuma_api import MonitorType
    attr = MONITOR_TYPE_MAP.get(type_str, type_str.upper()) or type_str.upper()
    return getattr(MonitorType, attr)


def build_monitor_kwargs(mon, docker_host_id):
    """Convert a monitor config dict to uptime-kuma-api kwargs."""
    kwargs = {
        "type": get_monitor_type(mon["type"]),
        "name": mon["name"],
        "interval": mon.get("interval", 60),
        "retryInterval": mon.get("retryInterval", 30),
        "maxretries": mon.get("maxretries", 3),
    }

    if mon.get("url"):
        kwargs["url"] = mon["url"]
    if mon.get("hostname"):
        kwargs["hostname"] = mon["hostname"]
    if mon.get("port"):
        kwargs["port"] = int(mon["port"])
    if mon.get("method"):
        kwargs["method"] = mon["method"]

    if mon["type"] == "docker":
        kwargs["docker_container"] = mon.get("dockerContainer", "mc")
        if docker_host_id:
            kwargs["docker_host"] = docker_host_id
        else:
            return None

    if mon["type"] == "dns":
        kwargs["dns_resolve_type"] = mon.get("dnsResolveType", "A")
        kwargs["dns_resolve_server"] = mon.get("dnsResolveServer", "1.1.1.1")

    if mon["type"] == "json-query":
        kwargs["json_path"] = mon.get("jsonPath", "")
        kwargs["expected_value"] = mon.get("expectedValue", "")

    return kwargs


def ensure_monitors(api, config, docker_host_id, notification_id):
    """Create groups and monitors from config. Returns group->monitor-id mapping."""
    existing_monitors = {}
    for attempt in range(5):
        try:
            existing_monitors = {m["name"]: m for m in api.get_monitors()}
            break
        except Exception:
            if attempt == 4:
                print("  Warning: Could not fetch monitors, creating fresh")
            time.sleep(5)

    notif_list = {notification_id: True} if notification_id else {}
    group_monitor_ids = {}

    for group_cfg in config.get("groups", []):
        group_name = group_cfg["name"]

        # Create or find the group monitor
        if group_name in existing_monitors:
            group_id = existing_monitors[group_name]["id"]
            print(f"  Group '{group_name}' already exists (id={group_id})")
        else:
            from uptime_kuma_api import MonitorType
            try:
                result = api.add_monitor(
                    type=MonitorType.GROUP,
                    name=group_name,
                    interval=group_cfg.get("interval", 60),
                    retryInterval=group_cfg.get("retryInterval", 60),
                )
                group_id = result["monitorID"]
                print(f"  Added group: {group_name} (id={group_id})")
            except Exception as e:
                print(f"  Warning: Could not add group '{group_name}': {e}")
                continue

        child_ids = []
        for mon in group_cfg.get("monitors", []):
            name = mon["name"]
            should_notify = mon.get("notify", True)

            if name in existing_monitors:
                mid = existing_monitors[name]["id"]
                existing = existing_monitors[name]

                # Update URL if it's drifted
                if mon.get("url") and existing.get("url") != mon["url"]:
                    try:
                        api.edit_monitor(mid, url=mon["url"])
                        print(f"  Updated '{name}' URL: {existing.get('url')} → {mon['url']}")
                    except Exception as e:
                        print(f"  Warning: Could not update '{name}' URL: {e}")

                # Update HTTP method if it's drifted (e.g. someone reverted a
                # GET/HEAD change via the UI without updating this file)
                if mon.get("method") and existing.get("method") != mon["method"]:
                    try:
                        api.edit_monitor(mid, method=mon["method"])
                        print(f"  Updated '{name}' method: {existing.get('method')} → {mon['method']}")
                    except Exception as e:
                        print(f"  Warning: Could not update '{name}' method: {e}")

                # Ensure parent group is set
                if existing.get("parent") != group_id:
                    try:
                        api.edit_monitor(mid, parent=group_id)
                        print(f"  Set '{name}' parent to '{group_name}'")
                    except Exception as e:
                        print(f"  Warning: Could not set parent for '{name}': {e}")

                print(f"  Monitor '{name}' already exists (id={mid})")
                child_ids.append(mid)
                continue

            kwargs = build_monitor_kwargs(mon, docker_host_id)
            if kwargs is None:
                print(f"  Skipping '{name}' (Docker host unavailable)")
                continue

            kwargs["parent"] = group_id
            if should_notify and notif_list:
                kwargs["notificationIDList"] = notif_list

            try:
                result = api.add_monitor(**kwargs)
                mid = result["monitorID"]
                child_ids.append(mid)
                print(f"  Added monitor: {name} (id={mid})")
            except Exception as e:
                print(f"  Warning: Could not add '{name}': {e}")

        group_monitor_ids[group_name] = {
            "groupId": group_id,
            "childIds": child_ids,
        }

    # Delete monitors under managed groups that are no longer in config
    config_names = set()
    for group_cfg in config.get("groups", []):
        config_names.add(group_cfg["name"])
        for mon in group_cfg.get("monitors", []):
            config_names.add(mon["name"])

    # Re-fetch monitors to get current state after adds
    try:
        current_monitors = {m["name"]: m for m in api.get_monitors()}
    except Exception:
        current_monitors = existing_monitors

    managed_group_ids = {v["groupId"] for v in group_monitor_ids.values()}
    for name, mon in current_monitors.items():
        if name in config_names:
            continue
        if mon.get("parent") not in managed_group_ids:
            continue
        try:
            api.delete_monitor(mon["id"])
            print(f"  Deleted monitor: {name} (id={mon['id']}) - removed from config")
        except Exception as e:
            print(f"  Warning: Could not delete '{name}': {e}")

    return group_monitor_ids


def ensure_status_page(api, config, group_monitor_ids):
    """Create or update the status page."""
    sp = config.get("statusPage", {})
    slug = sp.get("slug", "status")
    title = sp.get("title", "Server Status")

    try:
        existing_pages = api.get_status_pages()
        page_exists = any(p.get("slug") == slug for p in existing_pages)
    except Exception:
        page_exists = False

    if not page_exists:
        try:
            api.add_status_page(slug, title)
            print(f"  Created status page: {slug}")
        except Exception as e:
            print(f"  Warning: Could not create status page: {e}")
            return

    # Build public group list: each group gets its group monitor + children
    group_order = sp.get("groupOrder", list(group_monitor_ids.keys()))
    public_groups = []
    for group_name in group_order:
        if group_name not in group_monitor_ids:
            continue
        info = group_monitor_ids[group_name]
        monitor_list = [{"id": cid} for cid in info["childIds"]]
        public_groups.append({"name": group_name, "monitorList": monitor_list})

    if public_groups:
        try:
            save_kwargs = {
                "slug": slug,
                "title": title,
                "description": sp.get("description", ""),
                "publicGroupList": public_groups,
                "showPoweredBy": sp.get("showPoweredBy", False),
            }
            if sp.get("footerText"):
                save_kwargs["footerText"] = sp["footerText"]
            if sp.get("customCSS"):
                save_kwargs["customCSS"] = sp["customCSS"]

            api.save_status_page(**save_kwargs)
            print("  Status page configured with all monitors")
        except Exception as e:
            print(f"  Warning: Could not configure status page: {e}")


def apply_settings(api, settings, fresh_install):
    """Apply global Kuma settings. Only runs on fresh installs to avoid
    clobbering existing config - set_settings() sends ALL values with
    defaults, so partial calls reset unspecified fields."""
    if not settings or not fresh_install:
        if not fresh_install:
            print("  Settings: skipped (existing install)")
        return

    try:
        current = api.get_settings()
        # Config is authoritative for the keys it sets. Don't filter to keys
        # already in get_settings() - a fresh install omits unset keys (e.g.
        # entryPage), which silently dropped them. current is fetched first
        # so unspecified fields keep their values rather than resetting.
        current.update(settings)
        api.set_settings(**current)
        print("  Settings applied (fresh install)")
    except Exception as e:
        print(f"  Warning: Could not apply settings: {e}")


def connect_and_login(api_class, kuma_url, api_key, username, password):
    """Connect to Kuma, run first-time setup if needed, and authenticate.

    Auth order: session token (KUMA_API_KEY) if present, else
    username/password (KUMA_USERNAME/KUMA_PASSWORD). A fresh install is
    initialised with username/password via api.setup() first.
    Exits 1 on failure, exits 0 (skip) when no credentials exist at all.
    """
    api = None
    for attempt in range(5):
        try:
            api = api_class(kuma_url, timeout=30)
            break
        except Exception as e:
            if attempt == 4:
                print(f"ERROR: Socket.io connection failed after 5 attempts: {e}")
                sys.exit(1)
            time.sleep(5)

    assert api is not None

    try:
        fresh = api.need_setup()
    except Exception:
        fresh = False

    if fresh:
        if not password:
            print("Kuma has no admin account and KUMA_PASSWORD is not set.")
            print("  Skipping provisioning - set KUMA_PASSWORD in .env and re-run.")
            api.disconnect()
            sys.exit(0)
        try:
            api.setup(username, password)
            print(f"  Created Kuma admin account '{username}' (first run)")
        except Exception as e:
            print(f"ERROR: Kuma first-run setup failed: {e}")
            api.disconnect()
            sys.exit(1)

    if api_key:
        try:
            api.login_by_token(api_key)
            print("  Logged in with session token")
        except Exception as e:
            print(f"ERROR: Session token login failed: {e}")
            print("  Regenerate: ./scripts/kuma-token.sh --remote")
            api.disconnect()
            sys.exit(1)
    elif password:
        try:
            api.login(username, password)
            print(f"  Logged in as '{username}'")
        except Exception as e:
            print(f"ERROR: Username/password login failed: {e}")
            api.disconnect()
            sys.exit(1)
    else:
        print("No KUMA_API_KEY or KUMA_PASSWORD set - skipping provisioning.")
        api.disconnect()
        sys.exit(0)

    # Fresh installs need a moment for socket.io event streams to initialise
    time.sleep(5)

    try:
        api.get_monitors()
    except Exception as e:
        if "not logged in" in str(e).lower():
            print("ERROR: Session not authenticated after login.")
            print("  Regenerate: ./scripts/kuma-token.sh --remote")
            api.disconnect()
            sys.exit(1)
        raise

    return api


def main():
    try:
        from uptime_kuma_api import UptimeKumaApi
    except ImportError:
        import subprocess
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "--quiet", "uptime-kuma-api>=1.2.1"],
            stdout=subprocess.DEVNULL,
        )
        from uptime_kuma_api import UptimeKumaApi

    env = os.environ.copy()
    kuma_url = env.get("KUMA_URL", "http://uptime-kuma:3001")
    api_key = env.get("KUMA_API_KEY", "")
    username = env.get("KUMA_USERNAME", "admin")
    password = env.get("KUMA_PASSWORD", "")

    config = load_config(env)
    domain = env.get("DOMAIN", "")

    print(f"Waiting for Uptime Kuma at {kuma_url}...")
    if not wait_for_kuma(kuma_url):
        print("ERROR: Kuma did not become ready within 90s")
        sys.exit(1)

    api = connect_and_login(UptimeKumaApi, kuma_url, api_key, username, password)

    # ── Provision everything from config ──────────────────────────────────

    # Detect fresh install (no monitors yet) for settings application
    existing_count = len(retry(api.get_monitors, label="count monitors") or [])
    fresh_install = existing_count == 0
    if fresh_install:
        print("  Fresh install detected - will apply settings")

    # 1. Notifications
    notification_id = None
    for notif_cfg in config.get("notifications", []):
        nid = ensure_notification(api, notif_cfg, env)
        if nid and notification_id is None:
            notification_id = nid

    # 2. Docker host
    docker_host_id = ensure_docker_host(api)

    # 3. Groups and monitors
    group_monitor_ids = ensure_monitors(api, config, docker_host_id, notification_id)

    # 4. Status page
    ensure_status_page(api, config, group_monitor_ids)

    # 5. Global settings (fresh install only - avoids clobbering existing config)
    apply_settings(api, config.get("settings"), fresh_install)

    api.disconnect()

    print("\nUptime Kuma provisioning complete")
    if domain and domain not in ("example.com", ""):
        slug = config.get("statusPage", {}).get("slug", "status")
        print(f"  Dashboard:   https://status.{domain}")
        print(f"  Status page: https://status.{domain}/status/{slug}")


if __name__ == "__main__":
    main()
