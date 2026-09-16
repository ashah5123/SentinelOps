#!/usr/bin/env python3
"""Minimal local MCP diagnostic client for SentinelOps (Phase 13, section 14).

Speaks raw JSON-RPC 2.0 over the stdio transport (newline-delimited JSON, per the MCP spec) —
no MCP SDK dependency, so it can run anywhere Python 3 is available. Demonstrates the full local
demo flow from docs/development/mcp-server.md:

  1. Initialize an MCP session.
  2. List tools and resources.
  3. Read the system/capabilities resource.
  4. Call a read-only tool (list_incidents).
  5. Create a proposal (propose_action) against a real incident.
  6. Confirm execution is blocked until a human approves it in the operator console
     (this script only creates the proposal — approval happens via
     POST /api/v1/proposals/{id}/approve, e.g. from the console, never from here).

Usage:
  MCP_STDIO_ACCESS_TOKEN=<token> python3 mcp-diagnostic-client.py [incidentServiceJar] [incidentId]

Requires a responder- or admin-level access token (see docs/development/mcp-server.md's stdio
section for how to obtain one) and a built incident-service.jar
(services/incident-service/target/incident-service.jar, from `mvn package`).
"""
import json
import os
import subprocess
import sys
import threading

LAUNCHER_CLASS = "com.sentinelops.incident.mcp.transport.McpStdioLauncher"


class McpStdioClient:
    def __init__(self, jar_path: str):
        token = os.environ.get("MCP_STDIO_ACCESS_TOKEN")
        if not token:
            print("ERROR: MCP_STDIO_ACCESS_TOKEN is required.", file=sys.stderr)
            sys.exit(1)
        # A Spring Boot repackaged jar needs PropertiesLauncher + -Dloader.main to run a class
        # other than the application's own main() — application classes live under
        # BOOT-INF/classes/, not the jar root, so a plain "-cp jar LAUNCHER_CLASS" will not work.
        self.process = subprocess.Popen(
            [
                "java",
                f"-Dloader.main={LAUNCHER_CLASS}",
                "-cp",
                jar_path,
                "org.springframework.boot.loader.launch.PropertiesLauncher",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=None,  # let the launcher's own stderr (auth confirmation) print directly
            text=True,
            bufsize=1,
            env={**os.environ, "MCP_STDIO_ACCESS_TOKEN": token},
        )
        self._next_id = 1
        self._lock = threading.Lock()

    def _send(self, method: str, params: dict | None = None, notification: bool = False):
        message: dict = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        request_id = None
        if not notification:
            with self._lock:
                request_id = self._next_id
                self._next_id += 1
            message["id"] = request_id
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()
        return request_id

    def _read_response(self):
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError("MCP server closed its output stream unexpectedly")
        return json.loads(line)

    def call(self, method: str, params: dict | None = None):
        self._send(method, params)
        return self._read_response()

    def notify(self, method: str, params: dict | None = None):
        self._send(method, params, notification=True)

    def close(self):
        self.process.stdin.close()
        self.process.terminate()


def main():
    jar_path = sys.argv[1] if len(sys.argv) > 1 else "services/incident-service/target/incident-service.jar"
    incident_id = sys.argv[2] if len(sys.argv) > 2 else None

    client = McpStdioClient(jar_path)
    try:
        print("== initialize ==")
        init_result = client.call(
            "initialize",
            {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "sentinelops-diagnostic-client", "version": "1.0"},
            },
        )
        print(json.dumps(init_result, indent=2))
        client.notify("notifications/initialized")

        print("\n== tools/list ==")
        tools = client.call("tools/list")
        print(json.dumps([t["name"] for t in tools.get("result", {}).get("tools", [])], indent=2))

        print("\n== resources/read: sentinelops://system/capabilities ==")
        capabilities = client.call("resources/read", {"uri": "sentinelops://system/capabilities"})
        print(json.dumps(capabilities, indent=2))

        print("\n== tools/call: list_incidents ==")
        incidents = client.call("tools/call", {"name": "list_incidents", "arguments": {"size": 5}})
        print(json.dumps(incidents, indent=2))

        if incident_id:
            print(f"\n== tools/call: propose_action (ACKNOWLEDGE on {incident_id}) ==")
            proposal = client.call(
                "tools/call",
                {
                    "name": "propose_action",
                    "arguments": {
                        "incidentId": incident_id,
                        "actionType": "ACKNOWLEDGE",
                        "reason": "Diagnostic client demonstration",
                        "expectedVersion": 0,
                        "idempotencyKey": "diagnostic-client-demo-1",
                    },
                },
            )
            print(json.dumps(proposal, indent=2))
            print(
                "\nThis proposal is now PENDING. Nothing has executed. Approve it as an "
                "authorized RESPONDER/ADMIN via the operator console's Agent Proposals page, or:\n"
                "  curl -X POST http://localhost:8081/api/v1/proposals/<id>/approve "
                "-H 'Authorization: Bearer <a DIFFERENT actor's token>'"
            )
        else:
            print("\n(No incidentId argument given — skipping the propose_action demonstration.)")
    finally:
        client.close()


if __name__ == "__main__":
    main()
