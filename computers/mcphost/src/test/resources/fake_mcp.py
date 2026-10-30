#!/usr/bin/env python3
"""A tiny stdio MCP server for the mcp-host tests and smoke test (standard library only).

Tools: echo, pid, env, write_file, read_file, read_environ, fetch, whoami, crash, sleep.
Settings (env): FAKE_CRASH_ON_START=1 exits at once; FAKE_PAGED=1 lists tools in two pages;
FAKE_SAY names an env variable whose value it prints to stderr at start (to test log redaction);
FAKE_ASK=1 asks its client for roots once (the host must answer "not supported").
"""
import json
import os
import sys
import time
import urllib.request

if os.environ.get("FAKE_CRASH_ON_START") == "1":
    print("fake: crashing on purpose", file=sys.stderr, flush=True)
    sys.exit(3)

say = os.environ.get("FAKE_SAY")
if say:
    print(f"fake: starting with {say}={os.environ.get(say, '')}", file=sys.stderr, flush=True)

TOOLS = [
    {"name": "echo", "description": "Echoes text.", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
     "annotations": {"readOnlyHint": True}},
    {"name": "pid", "description": "This process id.", "inputSchema": {"type": "object"}, "annotations": {"readOnlyHint": True}},
    {"name": "env", "description": "One environment variable.", "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}}}},
    {"name": "write_file", "description": "Writes a file.", "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}, "text": {"type": "string"}}},
     "annotations": {"destructiveHint": False}},
    {"name": "read_file", "description": "Reads a file.", "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}},
    {"name": "list_dir", "description": "Lists a directory.", "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}},
    {"name": "read_environ", "description": "Reads another process's environment.", "inputSchema": {"type": "object", "properties": {"pid": {"type": "integer"}}}},
    {"name": "fetch", "description": "GETs a URL through the environment's proxy.", "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}}},
    {"name": "direct", "description": "Connects to host:port without any proxy.", "inputSchema": {"type": "object", "properties": {"host": {"type": "string"}, "port": {"type": "integer"}}}},
    {"name": "whoami", "description": "uid, gid, groups and capabilities.", "inputSchema": {"type": "object"}},
    {"name": "crash", "description": "Exits.", "inputSchema": {"type": "object"}},
    {"name": "sleep", "description": "Sleeps.", "inputSchema": {"type": "object", "properties": {"seconds": {"type": "number"}}}},
]


def send(msg):
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def text(t, error=False):
    r = {"content": [{"type": "text", "text": t}]}
    if error:
        r["isError"] = True
    return r


def call(name, args):
    try:
        if name == "echo":
            return text(args.get("text", ""))
        if name == "pid":
            return text(str(os.getpid()))
        if name == "env":
            return text(os.environ.get(args["name"], "<unset>"))
        if name == "write_file":
            with open(args["path"], "w") as f:
                f.write(args["text"])
            return text("ok")
        if name == "read_file":
            with open(args["path"]) as f:
                return text(f.read())
        if name == "list_dir":
            return text("\n".join(sorted(os.listdir(args["path"]))))
        if name == "read_environ":
            with open(f"/proc/{int(args['pid'])}/environ", "rb") as f:
                return text(f.read().replace(b"\0", b"\n").decode(errors="replace"))
        if name == "fetch":
            try:
                with urllib.request.urlopen(args["url"], timeout=20) as r:
                    return text(f"status {r.status}")
            except urllib.error.HTTPError as e:
                return text(f"status {e.code}")
        if name == "direct":
            import socket
            s = socket.create_connection((args["host"], int(args.get("port", 443))), timeout=5)
            s.close()
            return text("connected")
        if name == "whoami":
            status = open("/proc/self/status").read().splitlines()
            caps = [l for l in status if l.startswith(("CapEff", "CapPrm", "CapInh", "CapAmb", "NoNewPrivs"))]
            return text(f"uid={os.getuid()} gid={os.getgid()} groups={os.getgroups()}\n" + "\n".join(caps))
        if name == "crash":
            sys.exit(4)
        if name == "sleep":
            time.sleep(float(args.get("seconds", 1)))
            return text("slept")
        return None
    except SystemExit:
        raise
    except Exception as e:  # the error is the answer
        return text(f"error: {type(e).__name__}: {e}", error=True)


asked = False
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    msg = json.loads(line)
    method, mid = msg.get("method"), msg.get("id")
    if method is None:
        continue  # a response to our own request
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": msg["params"]["protocolVersion"],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "fake", "version": "1"}}})
    elif method == "tools/list":
        if os.environ.get("FAKE_PAGED") == "1":
            cursor = (msg.get("params") or {}).get("cursor")
            page = TOOLS[6:] if cursor else TOOLS[:6]
            result = {"tools": page} if cursor else {"tools": page, "nextCursor": "2"}
        else:
            result = {"tools": TOOLS}
        send({"jsonrpc": "2.0", "id": mid, "result": result})
    elif method == "tools/call":
        if os.environ.get("FAKE_ASK") == "1" and not asked:
            asked = True
            send({"jsonrpc": "2.0", "id": "ask-1", "method": "roots/list"})
        p = msg.get("params") or {}
        r = call(p.get("name"), p.get("arguments") or {})
        if r is None:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": "unknown tool"}})
        else:
            send({"jsonrpc": "2.0", "id": mid, "result": r})
    elif method == "ping":
        send({"jsonrpc": "2.0", "id": mid, "result": {}})
    elif mid is not None:
        send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found"}})
