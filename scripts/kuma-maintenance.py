#!/usr/bin/env python3
"""kuma-maintenance.py - start/stop a manual maintenance window in Uptime Kuma.

Called by deploy.sh around full deploys so Kuma shows "maintenance" (and
suppresses its up/down Discord alerts) instead of announcing every mid-deploy
state flap. Runs inside the kuma-init image, which has uptime-kuma-api:

  docker compose ... run --rm --no-deps kuma-init python3 /app/kuma-maintenance.py start
  docker compose ... run --rm --no-deps kuma-init python3 /app/kuma-maintenance.py stop

Idempotent both ways: start reuses an existing window matched by TITLE;
stop deletes every window with that TITLE. A Kuma outage must never fail a
deploy - deploy.sh invokes this with `|| true`.

Auth mirrors kuma-provision.py: KUMA_API_KEY (socket.io session token from
./scripts/kuma-token.sh, NOT the Prometheus API key) or username/password.
"""

import os
import sys
from datetime import datetime, timezone

TITLE = "Deploy in progress"


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else ""
    if action not in ("start", "stop"):
        sys.exit("usage: kuma-maintenance.py start|stop")

    from uptime_kuma_api import UptimeKumaApi, MaintenanceStrategy

    api = UptimeKumaApi(os.environ.get("KUMA_URL", "http://uptime-kuma:3001"), timeout=30)
    token = os.environ.get("KUMA_API_KEY", "")
    if token:
        api.login_by_token(token)
    else:
        api.login(os.environ.get("KUMA_USERNAME", "admin"), os.environ.get("KUMA_PASSWORD", ""))

    try:
        existing = [m for m in api.get_maintenances() if m.get("title") == TITLE]

        if action == "stop":
            if not existing:
                print("no active deploy maintenance window")
            for m in existing:
                api.delete_maintenance(m["id"])
                print(f"maintenance window {m['id']} closed")
            return

        if existing:
            print(f"maintenance window already active (id {existing[0]['id']})")
            return

        # MANUAL strategy stays active until deleted; Kuma still requires a
        # dateRange, so stamp the start time.
        now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        result = api.add_maintenance(
            title=TITLE,
            description="Automated deploy - monitors silenced until it completes",
            strategy=MaintenanceStrategy.MANUAL,
            active=True,
            intervalDay=1,
            dateRange=[now],
            weekdays=[],
            daysOfMonth=[],
        )
        mid = result["maintenanceID"]

        monitors = [{"id": m["id"]} for m in api.get_monitors()]
        if monitors:
            api.add_monitor_maintenance(mid, monitors)
        pages = [{"id": p["id"]} for p in api.get_status_pages()]
        if pages:
            api.add_status_page_maintenance(mid, pages)
        print(f"maintenance window {mid} active ({len(monitors)} monitors, {len(pages)} status pages)")
    finally:
        api.disconnect()


main()
