#!/usr/bin/env python3
"""A fake Asana behind a fake HTTPS proxy, for the Asana server's tests (standard library only).

It is an HTTP proxy: CONNECT app.asana.com:443 (with the expected Proxy-Authorization) is answered,
then TLS is ended here with a certificate for app.asana.com signed by a test CA (`make_certs`), and the
requests inside the tunnel are served from a small in-memory Asana. Anything else is refused, so a
client that does not go through the proxy gets nowhere.

  python3 fake_asana.py --port 3128 --certs DIR --token TOKEN [--proxy-auth user:password | --egress-dir DIR] [--free]

With --egress-dir it checks logins the way the egress proxy does: `users/<user>` holds the password's
SHA-256, and `allow/<user>` must list app.asana.com.
"""
import hashlib
import argparse
import base64
import copy
import datetime as dt
import http.server
import json
import os
import ssl
import subprocess
import threading
import urllib.parse

TUNNEL_TARGET = "app.asana.com:443"


def make_certs(directory):
    """A test CA and a server certificate for app.asana.com; returns (ca, cert, key) paths."""
    ca_key, ca, key, csr, cert = (os.path.join(directory, n) for n in ("ca.key", "ca.pem", "server.key", "server.csr", "server.pem"))
    ext = os.path.join(directory, "server.ext")
    with open(ext, "w") as f:
        f.write("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:app.asana.com\nauthorityKeyIdentifier=keyid\n")
    run = lambda *a: subprocess.run(a, check=True, capture_output=True)  # noqa: E731
    run("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "2",
        "-subj", "/CN=Clarity test CA", "-keyout", ca_key, "-out", ca,
        "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    run("openssl", "req", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-subj", "/CN=app.asana.com",
        "-keyout", key, "-out", csr)
    run("openssl", "x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key, "-CAcreateserial", "-days", "2",
        "-extfile", ext, "-out", cert)
    for path in (ca, cert, key):
        os.chmod(path, 0o644)
    return ca, cert, key


def _today():
    return dt.date.today()


def seed():
    d = lambda n: (_today() + dt.timedelta(days=n)).isoformat()  # noqa: E731
    me = {"gid": "100", "name": "Sam Rivera"}
    pm = {"gid": "101", "name": "Priya Shah"}
    tasks = {
        "201": {"name": "Essay outline", "due_on": d(2), "assignee": me, "notes": "Three sections: intro, argument, sources.",
                "memberships": [{"project": {"gid": "301", "name": "English 101"}, "section": {"gid": "401", "name": "This week"}}]},
        "202": {"name": "Lab report", "due_on": d(-1), "assignee": me,
                "memberships": [{"project": {"gid": "302", "name": "Chemistry"}, "section": {"gid": "403", "name": "(no section)"}}]},
        "203": {"name": "Read chapter 4", "due_on": d(20), "assignee": me, "memberships": []},
        "204": {"name": "Buy printer ink", "assignee": me, "memberships": []},
        "205": {"name": "Essay draft feedback", "due_on": d(5), "assignee": pm, "completed": False,
                "memberships": [{"project": {"gid": "301", "name": "English 101"}, "section": {"gid": "402", "name": "Later"}}]},
        "206": {"name": "Old quiz", "due_on": d(-30), "assignee": me, "completed": True,
                "memberships": [{"project": {"gid": "302", "name": "Chemistry"}, "section": {"gid": "403", "name": "(no section)"}}]},
        "207": {"name": "Find three sources", "due_on": d(1), "assignee": me, "parent": {"gid": "201", "name": "Essay outline"}, "memberships": []},
    }
    for i in range(130):  # a big project, for paging and caps
        tasks[str(1000 + i)] = {"name": f"Chore {i}", "assignee": None,
                                "memberships": [{"project": {"gid": "303", "name": "Chores"}, "section": {"gid": "404", "name": "Inbox"}}]}
    for gid, t in tasks.items():
        t.setdefault("gid", gid)
        t.setdefault("completed", False)
        t.setdefault("notes", "")
        t.setdefault("parent", None)
        t["workspace"] = "1"
        t["permalink_url"] = f"https://app.asana.com/0/0/{gid}"
    stories = {"201": [
        {"gid": "501", "resource_subtype": "assigned", "text": "Sam assigned to you", "created_at": "2026-10-20T10:00:00Z", "created_by": pm},
        {"gid": "502", "resource_subtype": "comment_added", "text": "Remember the rubric", "created_at": "2026-10-21T10:00:00Z", "created_by": pm},
        {"gid": "503", "resource_subtype": "comment_added", "text": "Will do", "created_at": "2026-10-22T10:00:00Z", "created_by": me},
    ]}
    return {
        "me": dict(me, workspaces=[{"gid": "1", "name": "School"}, {"gid": "2", "name": "Home"}]),
        "users": {"100": me, "101": pm, "me": me, "sam@example.com": me},
        "tasks": tasks,
        "stories": stories,
        "projects": [
            {"gid": "301", "name": "English 101", "team": {"gid": "9", "name": "Classes"}, "archived": False},
            {"gid": "302", "name": "Chemistry", "team": {"gid": "9", "name": "Classes"}, "archived": False},
            {"gid": "303", "name": "Chores", "team": None, "archived": False},
            {"gid": "304", "name": "Last year", "team": None, "archived": True},
        ],
        "sections": {"301": [{"gid": "401", "name": "This week"}, {"gid": "402", "name": "Later"}],
                     "302": [{"gid": "403", "name": "(no section)"}], "303": [{"gid": "404", "name": "Inbox"}]},
        "next": 9000,
    }


class Fake:
    """Settings and the record of what reached it; shared by the handler threads."""

    def __init__(self, token, proxy_auth=None, premium=True, egress_dir=None):
        self.token = token
        self.proxy_auth = proxy_auth
        self.egress_dir = egress_dir
        self.premium = premium
        self.rate_limit = 0  # answer this many requests with 429 first
        self.retry_after = "1"
        self.refuse_tunnel = False
        self.data = seed()
        self.tunnels = []  # (target, proxy-authorization) of every CONNECT
        self.requests = []  # (method, path, query, authorization, body)
        self.direct = []  # requests that did not come through a tunnel
        self.lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    tunneled = False

    def log_message(self, *args):
        pass

    @property
    def fake(self):
        return self.server.fake

    def _send(self, code, payload=None, headers=None):
        body = json.dumps(payload).encode() if payload is not None else b""
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_CONNECT(self):
        auth = self.headers.get("Proxy-Authorization")
        self.fake.tunnels.append((self.path, auth))
        expected = self.fake.proxy_auth and "Basic " + base64.b64encode(self.fake.proxy_auth.encode()).decode()
        if expected and auth != expected:
            self._send(407, headers={"Proxy-Authenticate": 'Basic realm="egress"'})
            return
        if self.fake.egress_dir and not self._egress_login(auth):
            self._send(407, headers={"Proxy-Authenticate": 'Basic realm="egress"'})
            return
        if self.path != TUNNEL_TARGET or self.fake.refuse_tunnel or not self._egress_allows():
            self._send(403)
            return
        self.send_response(200, "Connection established")
        self.end_headers()
        try:
            tls = self.server.tls.wrap_socket(self.connection, server_side=True)
        except (ssl.SSLError, OSError):
            self.close_connection = True
            return
        self.connection = tls
        self.rfile = tls.makefile("rb")
        self.wfile = tls.makefile("wb")
        self.tunneled = True
        self.close_connection = False
        while not self.close_connection:
            self.handle_one_request()
        self.close_connection = True

    def _egress_login(self, auth):
        try:
            user, password = base64.b64decode((auth or "").removeprefix("Basic ")).decode().split(":", 1)
            with open(os.path.join(self.fake.egress_dir, "users", os.path.basename(user))) as f:
                ok = f.read().strip() == hashlib.sha256(password.encode()).hexdigest()
        except (ValueError, OSError):
            return False
        self.user = user
        return ok

    def _egress_allows(self):
        if not self.fake.egress_dir:
            return True
        try:
            with open(os.path.join(self.fake.egress_dir, "allow", self.user)) as f:
                return "app.asana.com" in f.read().split()
        except OSError:
            return False

    def _api(self, method):
        if not self.tunneled:
            self.fake.direct.append((method, self.path))
            self._send(400, {"errors": [{"message": "not through the proxy tunnel"}]})
            return
        url = urllib.parse.urlsplit(self.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length)) if length else None
        auth = self.headers.get("Authorization")
        with self.fake.lock:
            self.fake.requests.append((method, url.path, query, auth, body))
            if self.fake.rate_limit > 0:
                self.fake.rate_limit -= 1
                self._send(429, {"errors": [{"message": "You have made too many requests recently."}]}, {"Retry-After": self.fake.retry_after})
                return
            if auth != "Bearer " + self.fake.token:
                self._send(401, {"errors": [{"message": "Not Authorized"}]})
                return
            if not url.path.startswith("/api/1.0/"):
                self._send(404, {"errors": [{"message": "No matching route"}]})
                return
            try:
                code, payload = route(self.fake, method, url.path[len("/api/1.0"):], query, (body or {}).get("data"))
            except KeyError as e:
                code, payload = 404, {"errors": [{"message": f"{e.args[0]}: Not a recognized ID"}]}
            self._send(code, payload)

    def finish(self):
        super().finish()
        if self.tunneled:
            self.connection.close()

    def do_GET(self):
        self._api("GET")

    def do_POST(self):
        self._api("POST")

    def do_PUT(self):
        self._api("PUT")


def _page(items, query):
    limit = int(query.get("limit", 100))
    if not 1 <= limit <= 100:
        return 400, {"errors": [{"message": "limit: Must be between 1 and 100"}]}
    start = int(query.get("offset") or 0)
    chunk = items[start:start + limit]
    nxt = {"offset": str(start + limit), "path": "", "uri": ""} if start + limit < len(items) else None
    return 200, {"data": chunk, "next_page": nxt}


def _view(task):
    return copy.deepcopy(task)


def route(fake, method, path, query, data):
    db = fake.data
    parts = path.strip("/").split("/")
    tasks = db["tasks"]
    incomplete = query.get("completed_since") == "now"

    def visible(ts):
        return [_view(t) for t in ts if not (incomplete and t["completed"])]

    if method == "GET":
        if parts == ["users", "me"]:
            return 200, {"data": db["me"]}
        if parts == ["workspaces"]:
            return 200, {"data": db["me"]["workspaces"]}
        if parts == ["tasks"]:
            if "project" in query:
                found = [t for t in tasks.values() if any(m["project"]["gid"] == query["project"] for m in t["memberships"])]
            elif query.get("assignee") == "me" and "workspace" in query:
                found = [t for t in tasks.values() if (t["assignee"] or {}).get("gid") == "100" and t["workspace"] == query["workspace"]]
            else:
                return 400, {"errors": [{"message": "Must specify exactly one of project, tag, section, user task list, or assignee + workspace"}]}
            return _page(visible(found), query)
        if len(parts) == 2 and parts[0] == "tasks":
            return 200, {"data": _view(tasks[parts[1]]) | {"num_subtasks": sum(1 for t in tasks.values() if (t["parent"] or {}).get("gid") == parts[1])}}
        if len(parts) == 3 and parts[0] == "tasks" and parts[2] == "subtasks":
            tasks[parts[1]]
            return _page(visible(t for t in tasks.values() if (t["parent"] or {}).get("gid") == parts[1]), query)
        if len(parts) == 3 and parts[0] == "tasks" and parts[2] == "stories":
            tasks[parts[1]]
            return _page(db["stories"].get(parts[1], []), query)
        if len(parts) == 4 and parts[0] == "workspaces" and parts[2:] == ["tasks", "search"]:
            if not fake.premium:
                return 402, {"errors": [{"message": "Search is only available to premium users."}]}
            text = query.get("text", "").lower()
            found = [t for t in tasks.values() if text in (t["name"] + " " + t["notes"]).lower() and t["workspace"] == parts[1]]
            if query.get("completed") == "false":
                found = [t for t in found if not t["completed"]]
            return 200, {"data": [_view(t) for t in found][: int(query.get("limit", 20))]}
        if len(parts) == 3 and parts[0] == "workspaces" and parts[2] == "typeahead":
            text = query.get("query", "").lower()
            found = [t for t in tasks.values() if text in t["name"].lower() and t["workspace"] == parts[1]]
            return 200, {"data": [_view(t) for t in found][: int(query.get("count", 20))]}
        if parts == ["projects"]:
            found = [p for p in db["projects"] if not (query.get("archived") == "false" and p["archived"])]
            return _page(found, query)
        if len(parts) == 3 and parts[0] == "projects" and parts[2] == "sections":
            return _page(db["sections"][parts[1]], query)
        if len(parts) == 3 and parts[0] == "sections" and parts[2] == "tasks":
            found = [t for t in tasks.values() if any((m.get("section") or {}).get("gid") == parts[1] for m in t["memberships"])]
            return _page(visible(found), query)
    if method == "POST":
        if parts == ["tasks"] or (len(parts) == 3 and parts[0] == "tasks" and parts[2] == "subtasks"):
            gid = str(db["next"])
            db["next"] += 1
            parent = tasks[parts[1]] if len(parts) == 3 else None
            memberships = []
            for m in data.get("memberships") or [{"project": p} for p in data.get("projects") or []]:
                project = next(p for p in db["projects"] if p["gid"] == m["project"])
                section = next((s for s in db["sections"].get(m["project"], []) if s["gid"] == m.get("section")), None)
                memberships.append({"project": {"gid": project["gid"], "name": project["name"]}, "section": section})
            if not parent and not memberships and "workspace" not in data:
                return 400, {"errors": [{"message": "You should specify one of workspace, parent, projects"}]}
            assignee = data.get("assignee")
            task = {"gid": gid, "name": data["name"], "notes": data.get("notes", ""), "due_on": data.get("due_on"), "due_at": data.get("due_at"),
                    "assignee": db["users"][assignee] if assignee else None, "completed": False, "memberships": memberships,
                    "parent": {"gid": parent["gid"], "name": parent["name"]} if parent else None, "workspace": "1",
                    "permalink_url": f"https://app.asana.com/0/0/{gid}"}
            tasks[gid] = task
            return 201, {"data": _view(task)}
        if len(parts) == 3 and parts[0] == "tasks" and parts[2] == "stories":
            tasks[parts[1]]
            story = {"gid": str(db["next"]), "resource_subtype": "comment_added", "text": data["text"],
                     "created_at": "2026-10-31T09:00:00Z", "created_by": db["me"]}
            db["next"] += 1
            db["stories"].setdefault(parts[1], []).append(story)
            return 201, {"data": story}
        if len(parts) == 3 and parts[0] == "sections" and parts[2] == "addTask":
            task = tasks[data["task"]]
            for project, sections in db["sections"].items():
                for s in sections:
                    if s["gid"] == parts[1]:
                        p = next(x for x in db["projects"] if x["gid"] == project)
                        task["memberships"] = [m for m in task["memberships"] if m["project"]["gid"] != project]
                        task["memberships"].append({"project": {"gid": project, "name": p["name"]}, "section": dict(s)})
                        return 200, {"data": {}}
            raise KeyError("section")
    if method == "PUT" and len(parts) == 2 and parts[0] == "tasks":
        task = tasks[parts[1]]
        for key in ("name", "notes", "due_on", "due_at", "completed"):
            if key in data:
                task[key] = data[key]
        if "assignee" in data:
            task["assignee"] = db["users"][data["assignee"]] if data["assignee"] else None
        return 200, {"data": _view(task)}
    return 404, {"errors": [{"message": f"No matching route for {method} {path}"}]}


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, fake, cert, key):
        super().__init__(address, Handler)
        self.fake = fake
        self.tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.tls.load_cert_chain(cert, key)


def start(fake, cert, key, host="127.0.0.1", port=0):
    server = Server((host, port), fake, cert, key)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=3128)
    ap.add_argument("--certs", required=True, help="directory with server.pem and server.key (make_certs)")
    ap.add_argument("--token", required=True)
    ap.add_argument("--proxy-auth", help="user:password expected on CONNECT (default: any login)")
    ap.add_argument("--egress-dir", help="check logins against the egress proxy's files here")
    ap.add_argument("--free", action="store_true", help="answer search with 402, like a free plan")
    a = ap.parse_args()
    f = Fake(a.token, a.proxy_auth, premium=not a.free, egress_dir=a.egress_dir)
    s = Server(("0.0.0.0", a.port), f, os.path.join(a.certs, "server.pem"), os.path.join(a.certs, "server.key"))
    print(f"fake asana proxy on :{s.server_address[1]}", flush=True)
    s.serve_forever()
