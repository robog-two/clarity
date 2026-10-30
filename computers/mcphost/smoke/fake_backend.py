#!/usr/bin/env python3
"""The few backend routes a connector uses, for the mcp-host smoke test (standard library only).

PUT /ingest/v1/manifest is stored (GET /manifest returns it); GET /ingest/v1/config serves
/smoke/config.json (re-read every time, ETag = its version); actions and tests are always empty.
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG = "/smoke/config.json"
manifest = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body=None, headers=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_PUT(self):
        global manifest
        n = int(self.headers.get("Content-Length", "0"))
        if self.path == "/ingest/v1/manifest":
            manifest = json.loads(self.rfile.read(n))
            self.reply(204)
        else:
            self.reply(404, {"title": "not found"})

    def do_GET(self):
        if self.path == "/manifest":
            self.reply(200, manifest)
        elif self.path == "/ingest/v1/config":
            with open(CONFIG) as f:
                config = json.load(f)
            etag = '"%s"' % config["version"]
            if self.headers.get("If-None-Match") == etag:
                self.reply(304)
            else:
                self.reply(200, config, {"ETag": etag})
        elif self.path.startswith("/ingest/v1/actions"):
            time.sleep(5)
            self.reply(200, {"actions": []})
        elif self.path == "/ingest/v1/test":
            self.reply(204)
        else:
            self.reply(404, {"title": "not found"})

    def do_POST(self):
        self.reply(204)


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
