#!/usr/bin/env python3
"""viewer-server.py — serve .seedtest/viewer.html with winner picking.

A deliberately rudimentary localhost server: GET serves the seedtest
directory (viewer.html, renders/), POST /pick records a human winner
choice and immediately re-finalises so the pick lands in the config
(and the viewer regenerates with the pin).

    ./viewer-server.py --config <multiverse_config.json> --seedtest <dir>
                       [--port 8765] [--write-config]

POST /pick body: {"dim": "<name>", "seed": "<seed>"} — seed null/empty
clears the override (back to the score ranking). Picks persist in
<seedtest>/winner-overrides.json and are honoured by every later
finalise, including roll-all's end-of-run one.

Gotchas: binds 127.0.0.1 only — this is a local tool, not a web app.
Started/stopped by roll-all.sh alongside the live reporter; safe to run
standalone after a roll too (./dev seed-report leaves the data behind).
"""
import argparse
import json
import subprocess
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent


class ViewerHandler(SimpleHTTPRequestHandler):
    # Set by main()
    seedtest = ""
    finalise_args: list = []

    def log_message(self, format, *args):  # noqa: A002 — quiet server
        pass

    def do_POST(self):
        if self.path != "/pick":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            dim = str(body["dim"])
            seed = body.get("seed")
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim, seed}")
            return

        overrides_path = Path(self.seedtest) / "winner-overrides.json"
        try:
            overrides = json.loads(overrides_path.read_text())
        except (OSError, json.JSONDecodeError):
            overrides = {}
        if seed:
            overrides[dim] = str(seed)
        else:
            overrides.pop(dim, None)
        tmp = overrides_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(overrides, indent=2) + "\n")
        tmp.replace(overrides_path)

        # Re-finalise so the pick lands in the config + viewer immediately.
        r = subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                           "finalise", *self.finalise_args],
                          capture_output=True, text=True)
        payload = json.dumps({"ok": r.returncode == 0, "dim": dim,
                              "seed": seed, "overrides": overrides}).encode()
        self.send_response(200 if r.returncode == 0 else 500)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--write-config", action="store_true")
    args = ap.parse_args()

    finalise_args = ["--config", args.config, "--seedtest", args.seedtest, "--viewer"]
    if args.write_config:
        finalise_args.append("--write-config")

    handler = partial(ViewerHandler, directory=args.seedtest)
    ViewerHandler.seedtest = args.seedtest
    ViewerHandler.finalise_args = finalise_args
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"viewer server: http://127.0.0.1:{args.port}/viewer.html", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
