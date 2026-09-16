#!/usr/bin/env python3
"""Minimal local webhook receiver for the Phase 12 demo (WEBHOOK notification channel).

Accepts any POST, logs a bounded, safe summary of what it received (never dumping the full body
to a place other containers can read), and responds 200. Also exposes GET /received as a small
JSON list of received-notification summaries, purely for the demo script to assert against —
this is not a general-purpose mock server, just enough to prove one logical notification arrived.

No third-party dependencies: only the Python standard library, so no image build/pull beyond the
official "python" base image is required.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime, timezone

MAX_STORED = 100
received = []


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        try:
            parsed = json.loads(body) if body else {}
        except json.JSONDecodeError:
            parsed = {"raw_length": len(body)}

        summary = {
            "receivedAt": datetime.now(timezone.utc).isoformat(),
            "path": self.path,
            "incidentId": parsed.get("incidentId"),
            "severity": parsed.get("severity"),
            "service": parsed.get("service"),
        }
        received.append(summary)
        del received[:-MAX_STORED]

        print(f"[webhook-sink] received notification: {summary}", flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"accepted"}')

    def do_GET(self):
        if self.path == "/received":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(received).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # avoid double-logging; the summary print above is sufficient


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 9099), Handler)
    print("[webhook-sink] listening on :9099", flush=True)
    server.serve_forever()
