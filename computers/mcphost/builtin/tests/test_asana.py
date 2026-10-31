#!/usr/bin/env python3
"""Tests of the built-in Asana server: MCP over stdio, against fake_asana through a proxy. No real Asana.

  python3 computers/mcphost/builtin/tests/test_asana.py   (also run by :computers:mcphost:test)
"""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fake_asana  # noqa: E402

SERVER = os.path.join(os.path.dirname(HERE), "asana.py")
TOKEN = "1/12345:test-token-abcdef0123456789"
PROXY_USER, PROXY_PASSWORD = "s-asana01", "proxy-pw-0123456789abcdefghij"
CERTS = tempfile.mkdtemp(prefix="asana-certs-")
CA, CERT, KEY = fake_asana.make_certs(CERTS)


class Session:
    """One server process spoken to over stdio."""

    def __init__(self, env):
        self.proc = subprocess.Popen([sys.executable, "-I", "-B", SERVER], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, env=env)
        self.next_id = 0

    def send(self, message):
        self.proc.stdin.write((json.dumps(message) + "\n").encode())
        self.proc.stdin.flush()

    def raw(self, line):
        self.proc.stdin.write(line + b"\n")
        self.proc.stdin.flush()
        return json.loads(self.proc.stdout.readline())

    def request(self, method, params=None):
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        return json.loads(self.proc.stdout.readline())

    def call(self, tool, /, **arguments):
        result = self.request("tools/call", {"name": tool, "arguments": arguments})["result"]
        return result["content"][0]["text"], result["isError"]

    def close(self):
        self.proc.stdin.close()
        self.proc.wait(10)
        stderr = self.proc.stderr.read().decode()
        self.proc.stdout.close()
        self.proc.stderr.close()
        return stderr


def env(proxy_port, **extra):
    e = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": CERTS, "SSL_CERT_FILE": CA, "ASANA_ACCESS_TOKEN": TOKEN,
         "HTTPS_PROXY": f"http://{PROXY_USER}:{PROXY_PASSWORD}@127.0.0.1:{proxy_port}"}
    e.update(extra)
    return {k: v for k, v in e.items() if v is not None}


class AsanaServerTest(unittest.TestCase):
    def setUp(self):
        self.fake = fake_asana.Fake(TOKEN, proxy_auth=f"{PROXY_USER}:{PROXY_PASSWORD}")
        self.server = fake_asana.start(self.fake, CERT, KEY)
        self.port = self.server.server_address[1]
        self.s = Session(env(self.port))
        self.s.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
        self.s.send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def tearDown(self):
        stderr = self.s.close()
        self.assertNotIn(TOKEN, stderr)
        self.server.shutdown()
        self.server.server_close()

    # ---- protocol ----

    def test_initialize_negotiates_the_version(self):
        r = self.s.request("initialize", {"protocolVersion": "2025-06-18"})["result"]
        self.assertEqual(r["protocolVersion"], "2025-06-18")
        self.assertEqual(r["serverInfo"]["name"], "clarity-asana")
        self.assertIn("tools", r["capabilities"])
        self.assertEqual(self.s.request("initialize", {"protocolVersion": "2024-11-05"})["result"]["protocolVersion"], "2024-11-05")
        # an unknown version gets the newest this server speaks
        self.assertEqual(self.s.request("initialize", {"protocolVersion": "1999-01-01"})["result"]["protocolVersion"], "2025-11-25")

    def test_ping_unknown_method_parse_errors_and_batches(self):
        self.assertEqual(self.s.request("ping")["result"], {})
        self.assertEqual(self.s.request("resources/list")["error"]["code"], -32601)
        self.assertEqual(self.s.raw(b"{not json")["error"]["code"], -32700)
        self.assertEqual(self.s.raw(b'[{"jsonrpc":"2.0","id":1,"method":"ping"}]')["error"]["code"], -32600)
        self.assertEqual(self.s.raw(b'{"id":5,"method":"ping"}')["error"]["code"], -32600)
        # still answering afterwards
        self.assertEqual(self.s.request("ping")["result"], {})

    def test_tools_are_listed_with_read_and_write_annotations(self):
        tools = {t["name"]: t for t in self.s.request("tools/list")["result"]["tools"]}
        reads = {"list_my_tasks", "get_task", "search_tasks", "list_projects", "list_sections", "list_project_tasks", "list_workspaces"}
        writes = {"create_task", "update_task", "complete_task", "add_comment", "move_task_to_section"}
        self.assertEqual(set(tools), reads | writes)
        write_verbs = {"create", "update", "delete", "remove", "send", "post", "merge", "close", "assign", "add", "set", "move",
                       "archive", "publish", "pay", "buy", "transfer", "invite", "approve", "comment", "upload", "write"}
        for name in reads:
            self.assertIs(tools[name]["annotations"]["readOnlyHint"], True, name)
            self.assertFalse(set(name.split("_")) & write_verbs, f"{name} reads like a write to the backend")
        for name in writes:
            self.assertIs(tools[name]["annotations"]["readOnlyHint"], False, name)
            self.assertIn("destructiveHint", tools[name]["annotations"])
        self.assertIs(tools["update_task"]["annotations"]["destructiveHint"], True)
        self.assertIs(tools["create_task"]["annotations"]["destructiveHint"], False)
        for t in tools.values():
            self.assertEqual(t["inputSchema"]["type"], "object")
            self.assertNotIn("_fn", t)

    def test_unknown_tool_is_a_protocol_error(self):
        r = self.s.request("tools/call", {"name": "delete_task", "arguments": {"task_gid": "1"}})
        self.assertEqual(r["error"]["code"], -32602)

    def test_bad_arguments_are_tool_errors(self):
        for args, needle in [
            ({}, "task_gid is required"),
            ({"task_gid": "abc"}, "task_gid 'abc' is not valid"),
            ({"task_gid": "201", "comments": 999}, "between 0 and 50"),
            ({"task_gid": "201", "comments": "x"}, "whole number"),
            ({"task_gid": "201", "colour": "red"}, "unknown argument colour"),
        ]:
            text, failed = self.s.call("get_task", **args)
            self.assertTrue(failed, args)
            self.assertIn(needle, text)
        text, failed = self.s.call("update_task", task_gid="201")
        self.assertTrue(failed)
        self.assertIn("nothing to change", text)
        text, failed = self.s.call("create_task", name="x", due_on="2026-13-45")
        self.assertTrue(failed)
        self.assertIn("is not a date", text)
        self.assertEqual(self.fake.requests, [])

    # ---- reads, through the proxy ----

    def test_every_request_goes_through_the_proxy_with_its_login_and_the_token(self):
        text, failed = self.s.call("list_workspaces")
        self.assertFalse(failed, text)
        self.assertIn("School · gid 1 (default)", text)
        self.assertTrue(self.fake.tunnels)
        for target, auth in self.fake.tunnels:
            self.assertEqual(target, "app.asana.com:443")
            self.assertTrue(auth.startswith("Basic "))
        self.assertEqual(self.fake.direct, [])
        self.assertEqual({r[3] for r in self.fake.requests}, {"Bearer " + TOKEN})

    def test_my_tasks_are_compact_soonest_first_and_windowed(self):
        text, failed = self.s.call("list_my_tasks")
        self.assertFalse(failed, text)
        lines = text.splitlines()
        self.assertEqual(lines[0], "5 incomplete tasks assigned to you:")
        self.assertTrue(lines[1].startswith("Lab report · due "), lines[1])
        self.assertIn("(overdue) · Chemistry · gid 202", lines[1])  # "(no section)" is left out
        today = dt.date.today()
        two = today + dt.timedelta(days=2)
        self.assertIn(f"Essay outline · due {two.strftime('%a')} {two.day} {two.strftime('%b')}", text)
        self.assertIn("English 101 ▸ This week · gid 201", text)
        self.assertTrue(lines[-1].startswith("Buy printer ink · gid 204"))  # no due date: last
        self.assertNotIn("Old quiz", text)  # completed
        self.assertNotIn("Essay draft feedback", text)  # someone else's
        q = next(r for r in self.fake.requests if r[1] == "/api/1.0/tasks")[2]
        self.assertEqual((q["assignee"], q["workspace"], q["completed_since"]), ("me", "1", "now"))

        text, _ = self.s.call("list_my_tasks", due_within_days=3)
        self.assertIn("due within 3 days", text.splitlines()[0])
        self.assertIn("Lab report", text)
        self.assertIn("Essay outline", text)
        self.assertNotIn("Read chapter 4", text)
        self.assertNotIn("Buy printer ink", text)

        text, _ = self.s.call("list_my_tasks", limit=2)
        self.assertEqual(len([line for line in text.splitlines() if "gid" in line]), 2)
        self.assertIn("first 2 shown", text)

    def test_workspace_from_the_environment(self):
        self.s.close()
        self.s = Session(env(self.port, ASANA_WORKSPACE="2"))
        text, failed = self.s.call("list_my_tasks")
        self.assertFalse(failed, text)
        self.assertEqual(text, "No incomplete tasks assigned to you.")
        self.assertEqual(next(r for r in self.fake.requests if r[1] == "/api/1.0/tasks")[2]["workspace"], "2")
        self.assertFalse(any(r[1] == "/api/1.0/users/me" for r in self.fake.requests))

    def test_get_task_has_notes_subtasks_and_latest_comments(self):
        text, failed = self.s.call("get_task", task_gid="201", comments=1)
        self.assertFalse(failed, text)
        self.assertIn("Essay outline · due", text)
        self.assertIn("@Sam Rivera", text)
        self.assertIn("notes: Three sections", text)
        self.assertIn("subtasks (1):", text)
        self.assertIn("Find three sources", text)
        self.assertIn("latest comments (1):", text)
        self.assertIn("Sam Rivera, 2026-10-22: Will do", text)
        self.assertNotIn("Remember the rubric", text)
        self.assertNotIn("assigned to you", text)  # not a comment

    def test_get_task_not_found(self):
        text, failed = self.s.call("get_task", task_gid="999")
        self.assertTrue(failed)
        self.assertIn("not found in Asana (404)", text)

    def test_search_full_text_on_paid_plans(self):
        text, failed = self.s.call("search_tasks", text="rubric")
        self.assertFalse(failed, text)
        self.assertEqual(text, "No tasks match 'rubric'.")
        text, _ = self.s.call("search_tasks", text="sections")
        self.assertIn("Essay outline", text)  # found in its notes
        self.assertNotIn("typeahead", text)

    def test_search_falls_back_to_typeahead_on_free_plans_and_says_so(self):
        self.fake.premium = False
        text, failed = self.s.call("search_tasks", text="essay")
        self.assertFalse(failed, text)
        self.assertIn("Essay outline", text)
        self.assertIn("Essay draft feedback · due", text)
        self.assertIn("@Priya Shah", text)
        self.assertIn("needs a paid plan", text)
        self.assertIn("typeahead", text)
        text, _ = self.s.call("search_tasks", text="quiz")
        self.assertIn("No tasks match", text)  # completed ones are left out
        text, _ = self.s.call("search_tasks", text="quiz", include_completed=True)
        self.assertIn("✓ Old quiz", text)

    def test_projects_sections_and_paged_project_tasks(self):
        text, _ = self.s.call("list_projects")
        self.assertIn("English 101 · Classes · gid 301", text)
        self.assertNotIn("Last year", text)
        text, _ = self.s.call("list_projects", include_archived=True)
        self.assertIn("Last year · archived · gid 304", text)
        text, _ = self.s.call("list_sections", project_gid="301")
        self.assertEqual(text, "This week · gid 401\nLater · gid 402")
        text, failed = self.s.call("list_project_tasks", project_gid="303", limit=120)
        self.assertFalse(failed, text)
        self.assertEqual(len([line for line in text.splitlines() if line.startswith("Chore ")]), 120)
        self.assertIn("first 120 shown", text)
        pages = [r for r in self.fake.requests if r[1] == "/api/1.0/tasks" and r[2].get("project") == "303"]
        self.assertEqual(len(pages), 2)
        self.assertTrue(all(int(p[2]["limit"]) <= 100 for p in pages))
        text, _ = self.s.call("list_project_tasks", project_gid="301", section_gid="402")
        self.assertIn("Essay draft feedback", text)
        self.assertNotIn("Essay outline", text)

    # ---- writes ----

    def test_create_update_complete_comment_and_move(self):
        text, failed = self.s.call("create_task", name="Revise essay", due_on="2026-11-03", project_gid="301", section_gid="402", notes="v2")
        self.assertFalse(failed, text)
        self.assertIn("Created: Revise essay · due Tue 3 Nov", text)
        self.assertIn("English 101 ▸ Later", text)
        gid = text.split("gid ")[1].split()[0]
        posted = next(r for r in self.fake.requests if r[0] == "POST")[4]["data"]
        self.assertEqual(posted["memberships"], [{"project": "301", "section": "402"}])

        text, failed = self.s.call("create_task", name="Call the dentist")
        self.assertFalse(failed, text)
        posted = [r for r in self.fake.requests if r[0] == "POST"][-1][4]["data"]
        self.assertEqual((posted["workspace"], posted["assignee"]), ("1", "me"))

        text, failed = self.s.call("create_task", name="Sub", parent_gid="201")
        self.assertFalse(failed, text)
        self.assertEqual([r for r in self.fake.requests if r[0] == "POST"][-1][1], "/api/1.0/tasks/201/subtasks")

        text, failed = self.s.call("update_task", task_gid=gid, name="Revise essay (final)", due_on="none", assignee="me")
        self.assertFalse(failed, text)
        self.assertIn("Updated: Revise essay (final) · English 101 ▸ Later · @Sam Rivera", text)
        put = [r for r in self.fake.requests if r[0] == "PUT"][-1][4]["data"]
        self.assertEqual(put, {"name": "Revise essay (final)", "due_on": None, "assignee": "me"})

        text, _ = self.s.call("complete_task", task_gid=gid)
        self.assertTrue(text.startswith("Completed: ✓ Revise essay (final)"))
        text, _ = self.s.call("complete_task", task_gid=gid, completed=False)
        self.assertTrue(text.startswith("Reopened: Revise essay (final)"))

        text, failed = self.s.call("add_comment", task_gid="201", text="Done the outline")
        self.assertFalse(failed, text)
        self.assertIn("Comment added to task 201", text)
        text, _ = self.s.call("get_task", task_gid="201")
        self.assertIn("Done the outline", text)

        text, failed = self.s.call("move_task_to_section", task_gid="201", section_gid="402")
        self.assertFalse(failed, text)
        text, _ = self.s.call("get_task", task_gid="201", comments=0)
        self.assertIn("English 101 ▸ Later", text)

    # ---- errors ----

    def test_wrong_token(self):
        self.s.close()
        self.s = Session(env(self.port, ASANA_ACCESS_TOKEN="wrong-token-value"))
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("refused the token (401)", text)
        self.assertIn("Manage developer apps", text)
        self.assertNotIn("wrong-token-value", text)

    def test_no_token(self):
        self.s.close()
        self.s = Session(env(self.port, ASANA_ACCESS_TOKEN=None))
        self.assertEqual(len(self.s.request("tools/list")["result"]["tools"]), 12)
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("no Asana token is set", text)
        self.assertEqual(self.fake.tunnels, [])

    def test_rate_limit_is_waited_out_once(self):
        self.fake.rate_limit = 1
        text, failed = self.s.call("list_workspaces")
        self.assertFalse(failed, text)
        self.assertEqual(len(self.fake.requests), 2)
        self.fake.rate_limit = 2
        text, failed = self.s.call("list_projects", workspace_gid="1")
        self.assertTrue(failed)
        self.assertIn("rate limiting", text)
        self.fake.rate_limit, self.fake.retry_after = 1, "3600"
        text, failed = self.s.call("list_projects", workspace_gid="1")
        self.assertTrue(failed)
        self.assertIn("try again in 3600 s", text)

    def test_proxy_refusing_the_host_or_the_login(self):
        self.fake.refuse_tunnel = True
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("egress proxy refused app.asana.com", text)
        self.assertNotIn(PROXY_PASSWORD, text)
        self.fake.refuse_tunnel = False
        self.s.close()
        self.s = Session(env(self.port, HTTPS_PROXY=f"http://{PROXY_USER}:wrong-password@127.0.0.1:{self.port}"))
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("did not accept this server's login", text)
        self.assertNotIn("wrong-password", text)

    def test_without_a_proxy_it_fails_closed(self):
        self.s.close()
        self.s = Session(env(self.port, HTTPS_PROXY=None))
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("no egress proxy is set", text)
        self.assertEqual(self.fake.tunnels, [])

    def test_proxy_unreachable(self):
        self.s.close()
        self.s = Session(env(self.port, HTTPS_PROXY="http://u:p@127.0.0.1:9"))
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("could not reach Asana through the proxy", text)

    def test_a_certificate_it_does_not_trust_is_refused(self):
        self.s.close()
        self.s = Session(env(self.port, SSL_CERT_FILE=os.devnull))
        text, failed = self.s.call("list_workspaces")
        self.assertTrue(failed)
        self.assertIn("CERTIFICATE_VERIFY_FAILED", text)
        self.assertEqual(self.fake.requests, [])


if __name__ == "__main__":
    unittest.main(verbosity=1)
