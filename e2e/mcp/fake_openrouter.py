#!/usr/bin/env python3
"""A scripted stand-in for OpenRouter's chat completions (streamed), for e2e/mcp/run.sh. Standard library only.

It plays every role by looking at the request:
  - the lead (it has the `delegate` tool): on the person's message containing E2E-MCP it delegates once
    to a helper with servers=["Fake"]; otherwise it answers "Okay.";
  - the helper (it has mcp_fake__ tools): calls mcp_fake__lookup, then mcp_fake__create_note, then finishes;
  - the guard (its system prompt): allows (an `ask` tool is held anyway);
  - anything else: "ok".
Every request body is saved to /state/req-<n>-<role>.json for the test to inspect.
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = os.environ.get("FAKE_STATE", "/state")
lock = threading.Lock()
counter = {"n": 0, "delegated": False}


def system_text(body):
    for m in body.get("messages", []):
        if m.get("role") == "system":
            c = m.get("content")
            return c if isinstance(c, str) else " ".join(p.get("text", "") for p in c)
    return ""


def all_text(body):
    return json.dumps(body.get("messages", []))


def decide(body):
    tools = [t.get("function", {}).get("name", "") for t in body.get("tools", [])]
    tool_results = [m for m in body.get("messages", []) if m.get("role") == "tool"]
    if any(t.startswith("mcp_fake__") for t in tools):
        if len(tool_results) == 0:
            return "helper", None, ("mcp_fake__lookup", {"q": "this week"})
        if len(tool_results) == 1:
            return "helper", None, ("mcp_fake__create_note", {"text": "E2E note from the helper"})
        return "helper", "Done: I looked it up and saved the note.", None
    if "delegate" in tools:
        with lock:
            fire = "E2E-MCP" in all_text(body) and not counter["delegated"]
            if fire:
                counter["delegated"] = True
        if fire:
            return "lead", None, ("delegate", {
                "task": "Look up what is due this week on the Fake server, then save a note about it there. Report what you did.",
                "servers": ["Fake"],
                "title": "E2E MCP check",
            })
        return "lead", "Okay.", None
    if system_text(body).startswith("You are the guard"):
        return "guard", json.dumps({"decision": "allow", "lines": [], "rationale": "Fine by your rules."}), None
    return "other", "ok", None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(n) or b"{}")
        if not self.path.endswith("/chat/completions"):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        role, text, call = decide(body)
        with lock:
            counter["n"] += 1
            idx = counter["n"]
        with open(os.path.join(STATE, f"req-{idx:03d}-{role}.json"), "w") as f:
            json.dump(body, f)
        chunks = []
        if call:
            name, args = call
            chunks.append({"choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [
                {"index": 0, "id": f"call_{idx}", "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]}}]})
            chunks.append({"choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]})
        else:
            chunks.append({"choices": [{"index": 0, "delta": {"role": "assistant", "content": text}}]})
            chunks.append({"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
        chunks.append({"choices": [], "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110, "cost": 0.0},
                       "provider": "fake"})
        payload = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"
        data = payload.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


ThreadingHTTPServer(("0.0.0.0", 8099), Handler).serve_forever()
