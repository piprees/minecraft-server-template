"""Static server for the map site with nav-proxy's fallback: a path that is not a file serves index.html."""
import http.server
import os
import sys

root = sys.argv[1]
port = int(sys.argv[2])


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=root, **k)

    def send_head(self):
        path = self.translate_path(self.path)
        if not os.path.exists(path):
            name = self.path.split("?")[0].rstrip("/").rsplit("/", 1)[-1]
            if "." not in name:
                self.path = "/index.html"
        return super().send_head()

    def log_message(self, *a):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
