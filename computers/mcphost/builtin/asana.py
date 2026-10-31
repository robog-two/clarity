#!/usr/bin/env python3
"""Clarity's Asana MCP server: MCP over stdio (newline-delimited JSON-RPC), Python standard library only.

Settings (environment):
  ASANA_ACCESS_TOKEN  a personal access token (required for calls; never printed)
  ASANA_WORKSPACE     workspace gid to use by default (else the first of the person's workspaces)
  HTTPS_PROXY         required: every request goes through it (set by mcp-host); without it, calls fail

Output is short text lines, since the model reading it pays per token.
"""
import datetime as dt
import http.client
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# Bump with any change to the tools (mcp-host relists when it changes); BuiltinServer in ServerSpec.kt has the same.
VERSION = "1.0.0"
API = "https://app.asana.com/api/1.0"
PROTOCOL_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]
TIMEOUT = 30
MAX_RETRY_AFTER = 30
GID = r"^[0-9]{1,30}$"
DATE = r"^\d{4}-\d{2}-\d{2}$"

TASK_FIELDS = "name,completed,due_on,due_at,assignee.name,memberships.project.name,memberships.section.name"


class ToolError(Exception):
    """A failure the model should read (returned as an isError result)."""


# ---- Asana over HTTPS, through the proxy only ----------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # A redirect would carry the token to wherever it points: refuse it.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ToolError(f"Asana answered with a redirect (HTTP {code}); not followed")


def _opener():
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if not proxy:
        raise ToolError("no egress proxy is set (HTTPS_PROXY): this server reaches Asana only through Clarity's proxy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({"https": proxy}), _NoRedirect())


def _token():
    token = os.environ.get("ASANA_ACCESS_TOKEN", "").strip()
    if not token:
        raise ToolError("no Asana token is set; add a personal access token to this server in Clarity's settings")
    return token


def _clean(text):
    """Text from elsewhere, with the token and proxy password taken out, kept short."""
    text = str(text)
    for secret in (os.environ.get("ASANA_ACCESS_TOKEN", "").strip(), _proxy_password()):
        if secret:
            text = text.replace(secret, "***")
    return text[:300]


def _proxy_password():
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or ""
    try:
        return urllib.parse.urlsplit(proxy).password or ""
    except ValueError:
        return ""


def _asana_message(body):
    try:
        errors = json.loads(body).get("errors") or []
        return "; ".join(e.get("message", "") for e in errors if isinstance(e, dict))[:300]
    except (ValueError, AttributeError):
        return ""


class PremiumOnly(ToolError):
    pass


def asana(method, path, params=None, data=None, _retried=False):
    """One Asana API call; returns the response's `data` (and `next_page` via asana_page)."""
    return asana_page(method, path, params, data, _retried)[0]


def asana_page(method, path, params=None, data=None, _retried=False):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    body = json.dumps({"data": data}).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + _token(),
        "Accept": "application/json",
        "User-Agent": "clarity-asana-mcp/" + VERSION,
        **({"Content-Type": "application/json"} if body is not None else {}),
    })
    try:
        with _opener().open(req, timeout=TIMEOUT) as resp:
            payload = json.loads(resp.read() or b"{}")
            return payload.get("data"), payload.get("next_page")
    except urllib.error.HTTPError as e:
        raw = e.read() or b""
        detail = _clean(_asana_message(raw))
        suffix = f": {detail}" if detail else ""
        if e.code == 429 and not _retried:
            wait = _retry_after(e.headers.get("Retry-After"))
            if wait is not None and wait <= MAX_RETRY_AFTER:
                time.sleep(wait)
                return asana_page(method, path, params, data, _retried=True)
            raise ToolError(f"Asana is rate limiting this token; try again in {wait or 60} s")
        if e.code == 429:
            raise ToolError("Asana is still rate limiting this token; try again in a minute")
        if e.code == 401:
            raise ToolError("Asana refused the token (401): it is wrong, expired or revoked. Make a new one in Asana "
                            "(profile photo > My settings > Apps > Manage developer apps) and enter it again in Clarity")
        if e.code == 402 or (e.code == 403 and "premium" in detail.lower()):
            raise PremiumOnly(f"this needs a paid Asana plan (HTTP {e.code}){suffix}")
        if e.code == 403:
            raise ToolError(f"Asana says this account may not do that (403){suffix}")
        if e.code == 404:
            raise ToolError(f"not found in Asana (404); check the gid{suffix}")
        if e.code == 400:
            raise ToolError(f"Asana did not accept the request (400){suffix}")
        if e.code >= 500:
            raise ToolError(f"Asana had a problem (HTTP {e.code}); try again later")
        raise ToolError(f"Asana answered HTTP {e.code}{suffix}")
    except urllib.error.URLError as e:
        reason = _clean(getattr(e, "reason", e))
        if "Tunnel connection failed: 407" in reason:
            raise ToolError("Clarity's egress proxy did not accept this server's login; restart the server from Clarity's settings")
        if "Tunnel connection failed" in reason:
            raise ToolError(f"Clarity's egress proxy refused app.asana.com ({reason}); "
                            "check that app.asana.com is among the hosts this server may reach")
        raise ToolError(f"could not reach Asana through the proxy: {reason}")
    except (OSError, http.client.HTTPException) as e:
        raise ToolError(f"could not reach Asana through the proxy: {_clean(e) or type(e).__name__}")
    except ValueError:
        raise ToolError("Asana sent something that is not JSON")


def _retry_after(value):
    try:
        return max(0, int(float(value)))
    except (TypeError, ValueError):
        return None


def paged(path, params, cap):
    """Items of a paginated list, at most `cap`; and whether there were more."""
    items, offset = [], None
    while True:
        page_params = dict(params, limit=min(100, cap - len(items) + 1), offset=offset)
        data, next_page = asana_page("GET", path, page_params)
        items.extend(data or [])
        offset = (next_page or {}).get("offset")
        if len(items) > cap:
            return items[:cap], True
        if not offset:
            return items, False


# ---- formatting ----------------------------------------------------------------------------------

def _today():
    return dt.date.today()


def fmt_date(day, today=None):
    today = today or _today()
    text = f"{day.strftime('%a')} {day.day} {day.strftime('%b')}"
    return text if day.year == today.year else f"{text} {day.year}"


def due_date(task):
    if task.get("due_on"):
        try:
            return dt.date.fromisoformat(task["due_on"])
        except ValueError:
            return None
    if task.get("due_at"):
        try:
            return dt.datetime.fromisoformat(task["due_at"].replace("Z", "+00:00")).date()
        except ValueError:
            return None
    return None


def fmt_due(task):
    day = due_date(task)
    if day is None:
        return None
    text = "due " + fmt_date(day)
    if task.get("due_at"):
        try:
            text += " " + dt.datetime.fromisoformat(task["due_at"].replace("Z", "+00:00")).strftime("%H:%M UTC")
        except ValueError:
            pass
    if not task.get("completed") and day < _today():
        text += " (overdue)"
    return text


def fmt_place(task):
    places = []
    for m in task.get("memberships") or []:
        project = (m.get("project") or {}).get("name")
        section = (m.get("section") or {}).get("name")
        if project:
            places.append(f"{project} ▸ {section}" if section and section != "(no section)" and section != "Untitled section" else project)
    if not places:
        return None
    return places[0] + (f" +{len(places) - 1}" if len(places) > 1 else "")


def task_line(task, show_assignee=False):
    parts = [("✓ " if task.get("completed") else "") + (task.get("name") or "(no name)")]
    for part in (fmt_due(task), fmt_place(task)):
        if part:
            parts.append(part)
    if show_assignee and (task.get("assignee") or {}).get("name"):
        parts.append("@" + task["assignee"]["name"])
    parts.append("gid " + str(task.get("gid")))
    return " · ".join(parts)


def by_due(tasks):
    return sorted(tasks, key=lambda t: (due_date(t) is None, due_date(t) or dt.date.max))


def more(truncated, cap):
    return f"\n(first {cap} shown; ask with a higher limit or narrow it down)" if truncated else ""


# ---- context -------------------------------------------------------------------------------------

_me = {}
_lock = threading.Lock()


def me():
    with _lock:
        if not _me:
            _me.update(asana("GET", "/users/me", {"opt_fields": "name,workspaces.name"}) or {})
        return _me


def workspace(args):
    gid = args.get("workspace_gid") or os.environ.get("ASANA_WORKSPACE", "").strip()
    if gid:
        if not re.match(GID, gid):
            raise ToolError("ASANA_WORKSPACE must be a workspace gid (digits); list_workspaces shows them")
        return gid
    spaces = me().get("workspaces") or []
    if not spaces:
        raise ToolError("this Asana account has no workspaces")
    return spaces[0]["gid"]


# ---- tools ---------------------------------------------------------------------------------------

def list_workspaces(args):
    spaces = asana("GET", "/workspaces", {"opt_fields": "name,is_organization", "limit": 100}) or []
    default = os.environ.get("ASANA_WORKSPACE", "").strip() or (spaces[0]["gid"] if spaces else None)
    lines = [f"{w.get('name')} · gid {w['gid']}" + (" (default)" if w["gid"] == default else "") for w in spaces]
    return "\n".join(lines) or "No workspaces."


def list_my_tasks(args):
    cap = args.get("limit", 50)
    params = {"assignee": "me", "workspace": workspace(args), "completed_since": "now", "opt_fields": TASK_FIELDS}
    tasks, truncated = paged("/tasks", params, 500)
    days = args.get("due_within_days")
    if days is not None:
        until = _today() + dt.timedelta(days=days)
        tasks = [t for t in tasks if due_date(t) is not None and due_date(t) <= until]
    tasks = by_due(tasks)
    shown = tasks[:cap]
    window = f" due within {days} days (overdue included)" if days is not None else ""
    head = f"{len(tasks)}{'+' if truncated else ''} incomplete tasks assigned to you{window}:"
    if not tasks:
        return f"No incomplete tasks assigned to you{window}."
    return head + "\n" + "\n".join(task_line(t) for t in shown) + more(len(tasks) > cap, cap)


def get_task(args):
    gid = args["task_gid"]
    fields = TASK_FIELDS + ",notes,start_on,completed_at,parent.name,tags.name,permalink_url,num_subtasks,created_at,modified_at"
    t = asana("GET", f"/tasks/{gid}", {"opt_fields": fields}) or {}
    lines = [task_line(t, show_assignee=True)]
    if t.get("assignee") is None:
        lines.append("unassigned")
    if t.get("parent"):
        lines.append(f"subtask of {t['parent'].get('name')} · gid {t['parent']['gid']}")
    if t.get("start_on"):
        lines.append("starts " + t["start_on"])
    if t.get("tags"):
        lines.append("tags: " + ", ".join(x.get("name", "") for x in t["tags"]))
    if t.get("completed_at"):
        lines.append("completed " + t["completed_at"][:10])
    if t.get("permalink_url"):
        lines.append(t["permalink_url"])
    notes = (t.get("notes") or "").strip()
    if notes:
        limit = args.get("notes_chars", 1500)
        lines.append("notes: " + (notes[:limit] + f"… ({len(notes)} chars)" if len(notes) > limit else notes))
    if t.get("num_subtasks"):
        subs, cut = paged(f"/tasks/{gid}/subtasks", {"opt_fields": "name,completed,due_on,due_at,assignee.name"}, 30)
        lines.append(f"subtasks ({t['num_subtasks']}):")
        lines += ["  " + task_line(s, show_assignee=True) for s in subs]
        if cut:
            lines.append("  …")
    n = args.get("comments", 5)
    if n:
        stories, _ = paged(f"/tasks/{gid}/stories", {"opt_fields": "resource_subtype,text,created_at,created_by.name"}, 300)
        comments = [s for s in stories if s.get("resource_subtype") == "comment_added"][-n:]
        if comments:
            lines.append(f"latest comments ({len(comments)}):")
            for c in comments:
                text = " ".join((c.get("text") or "").split())
                lines.append(f"  {(c.get('created_by') or {}).get('name', '?')}, {(c.get('created_at') or '')[:10]}: {text[:400]}")
    return "\n".join(lines)


def search_tasks(args):
    text, cap = args["text"], args.get("limit", 20)
    ws = workspace(args)
    include_done = args.get("include_completed", False)
    params = {"text": text, "opt_fields": TASK_FIELDS, "sort_by": "modified_at", "limit": cap}
    if not include_done:
        params["completed"] = "false"
    try:
        tasks = asana("GET", f"/workspaces/{ws}/tasks/search", params) or []
        note = ""
    except PremiumOnly:
        # Search is a paid-plan feature; typeahead matches task names on every plan.
        tasks = asana("GET", f"/workspaces/{ws}/typeahead",
                      {"resource_type": "task", "query": text, "count": min(100, cap * 2), "opt_fields": TASK_FIELDS}) or []
        if not include_done:
            tasks = [t for t in tasks if not t.get("completed")]
        tasks = tasks[:cap]
        note = "\n(Asana's full search needs a paid plan, so these are name matches from Asana's typeahead; words only in descriptions or comments are missed)"
    if not tasks:
        return f"No tasks match '{text}'." + note
    return f"{len(tasks)} tasks matching '{text}':\n" + "\n".join(task_line(t, show_assignee=True) for t in tasks) + note


def list_projects(args):
    cap = args.get("limit", 50)
    params = {"workspace": workspace(args), "opt_fields": "name,team.name,archived,current_status_update.status_type"}
    if not args.get("include_archived", False):
        params["archived"] = "false"
    projects, truncated = paged("/projects", params, cap)
    lines = []
    for p in projects:
        parts = [p.get("name") or "(no name)"]
        if (p.get("team") or {}).get("name"):
            parts.append(p["team"]["name"])
        if p.get("archived"):
            parts.append("archived")
        parts.append("gid " + p["gid"])
        lines.append(" · ".join(parts))
    return ("\n".join(lines) or "No projects.") + more(truncated, cap)


def list_sections(args):
    sections, truncated = paged(f"/projects/{args['project_gid']}/sections", {"opt_fields": "name"}, 100)
    return ("\n".join(f"{s.get('name')} · gid {s['gid']}" for s in sections) or "No sections.") + more(truncated, 100)


def list_project_tasks(args):
    cap = args.get("limit", 50)
    params = {"opt_fields": TASK_FIELDS}
    if not args.get("include_completed", False):
        params["completed_since"] = "now"
    if args.get("section_gid"):
        tasks, truncated = paged(f"/sections/{args['section_gid']}/tasks", params, cap)
    else:
        tasks, truncated = paged("/tasks", dict(params, project=args["project_gid"]), cap)
    if not tasks:
        return "No tasks there."
    return "\n".join(task_line(t, show_assignee=True) for t in tasks) + more(truncated, cap)


def _assignee(value):
    if value in ("none", ""):
        return None
    return value  # "me", a user gid or an email address


def _due_fields(args, out):
    if "due_on" in args:
        out["due_on"] = None if args["due_on"] in ("none", "") else args["due_on"]
    if "due_at" in args:
        out["due_at"] = None if args["due_at"] in ("none", "") else args["due_at"]
    if out.get("due_on") and out.get("due_at"):
        raise ToolError("give due_on or due_at, not both")


def create_task(args):
    data = {"name": args["name"]}
    if args.get("notes"):
        data["notes"] = args["notes"]
    _due_fields(args, data)
    if "assignee" in args:
        data["assignee"] = _assignee(args["assignee"])
    if args.get("parent_gid"):
        t = asana("POST", f"/tasks/{args['parent_gid']}/subtasks", {"opt_fields": TASK_FIELDS + ",permalink_url"}, data)
    else:
        if args.get("section_gid") and not args.get("project_gid"):
            raise ToolError("section_gid needs its project_gid too")
        if args.get("project_gid"):
            data["projects"] = [args["project_gid"]]
            if args.get("section_gid"):
                data["memberships"] = [{"project": args["project_gid"], "section": args["section_gid"]}]
                del data["projects"]
        else:
            data["workspace"] = workspace(args)
            data.setdefault("assignee", "me")  # a task in no project would otherwise be visible to nobody
        t = asana("POST", "/tasks", {"opt_fields": TASK_FIELDS + ",permalink_url"}, data)
    t = t or {}
    return "Created: " + task_line(t, show_assignee=True) + ("\n" + t["permalink_url"] if t.get("permalink_url") else "")


def update_task(args):
    data = {}
    for key in ("name", "notes"):
        if key in args:
            data[key] = args[key]
    _due_fields(args, data)
    if "assignee" in args:
        data["assignee"] = _assignee(args["assignee"])
    if not data:
        raise ToolError("nothing to change: give at least one of name, notes, due_on, due_at, assignee")
    t = asana("PUT", f"/tasks/{args['task_gid']}", {"opt_fields": TASK_FIELDS}, data) or {}
    return "Updated: " + task_line(t, show_assignee=True)


def complete_task(args):
    done = args.get("completed", True)
    t = asana("PUT", f"/tasks/{args['task_gid']}", {"opt_fields": TASK_FIELDS}, {"completed": done}) or {}
    return ("Completed: " if done else "Reopened: ") + task_line(t)


def add_comment(args):
    story = asana("POST", f"/tasks/{args['task_gid']}/stories", {"opt_fields": "created_at"}, {"text": args["text"]}) or {}
    return f"Comment added to task {args['task_gid']} · story gid {story.get('gid')}"


def move_task_to_section(args):
    asana("POST", f"/sections/{args['section_gid']}/addTask", None, {"task": args["task_gid"]})
    return f"Moved task {args['task_gid']} to section {args['section_gid']}"


def _s(desc, pattern=None, max_len=None):
    d = {"type": "string", "description": desc}
    if pattern:
        d["pattern"] = pattern
    if max_len:
        d["maxLength"] = max_len
    return d


def _i(desc, lo, hi):
    return {"type": "integer", "description": desc, "minimum": lo, "maximum": hi}


def _b(desc):
    return {"type": "boolean", "description": desc}


WS = _s("Workspace gid; default: ASANA_WORKSPACE or the first workspace", GID)
TASK = _s("Task gid", GID)
DUE_ON = _s("Due date YYYY-MM-DD, or 'none' to clear", r"^(\d{4}-\d{2}-\d{2}|none)$")
DUE_AT = _s("Due date and time, ISO 8601 with zone (2026-11-03T17:00:00Z), or 'none' to clear",
            r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})|none)$")
ASSIGNEE = _s("'me', a user gid, an email address, or 'none' to unassign", max_len=200)


def tool(name, title, description, fn, properties, required=(), read=True, destructive=False, idempotent=False):
    annotations = {"title": title, "readOnlyHint": read, "openWorldHint": True}
    if not read:
        annotations.update(destructiveHint=destructive, idempotentHint=idempotent)
    return {
        "name": name, "title": title, "description": description,
        "inputSchema": {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False},
        "annotations": annotations, "_fn": fn,
    }


TOOLS = [
    tool("list_my_tasks", "My tasks", "Incomplete tasks assigned to me, soonest due first. Lines: name · due · project ▸ section · gid.",
         list_my_tasks, {"due_within_days": _i("Only tasks due within this many days (overdue included)", 0, 366),
                         "limit": _i("At most this many (default 50)", 1, 200), "workspace_gid": WS}),
    tool("get_task", "Task details", "One task: due, assignee, projects, notes, subtasks and the latest comments.",
         get_task, {"task_gid": TASK, "comments": _i("How many latest comments (default 5)", 0, 50),
                    "notes_chars": _i("Characters of notes to show (default 1500)", 0, 20000)}, ["task_gid"]),
    tool("search_tasks", "Find tasks", "Tasks matching words. Full-text on paid Asana plans; on free plans it matches task names only (the result says so).",
         search_tasks, {"text": _s("Words to look for", max_len=200), "include_completed": _b("Include completed tasks (default false)"),
                        "limit": _i("At most this many (default 20)", 1, 100), "workspace_gid": WS}, ["text"]),
    tool("list_projects", "Projects", "Projects in the workspace: name · team · gid.",
         list_projects, {"include_archived": _b("Include archived projects (default false)"),
                         "limit": _i("At most this many (default 50)", 1, 500), "workspace_gid": WS}),
    tool("list_sections", "Sections", "Sections (columns) of a project: name · gid.", list_sections,
         {"project_gid": _s("Project gid", GID)}, ["project_gid"]),
    tool("list_project_tasks", "Project tasks", "Tasks of a project, or of one of its sections; incomplete only unless asked.",
         list_project_tasks, {"project_gid": _s("Project gid", GID), "section_gid": _s("Only this section's tasks", GID),
                              "include_completed": _b("Include completed tasks (default false)"),
                              "limit": _i("At most this many (default 50)", 1, 500)}, ["project_gid"]),
    tool("list_workspaces", "Workspaces", "The workspaces this account is in: name · gid.", list_workspaces, {}),
    tool("create_task", "Create a task", "Creates a task (in a project and section, as a subtask, or in My Tasks when neither is given; then it is assigned to me).",
         create_task, {"name": _s("Task name", max_len=1024), "notes": _s("Description (plain text)", max_len=60000),
                       "due_on": DUE_ON, "due_at": DUE_AT, "assignee": ASSIGNEE,
                       "project_gid": _s("Project to add it to", GID), "section_gid": _s("Section of that project", GID),
                       "parent_gid": _s("Make it a subtask of this task", GID), "workspace_gid": WS}, ["name"], read=False),
    tool("update_task", "Change a task", "Changes a task's name, notes (replacing them), due date or assignee.",
         update_task, {"task_gid": TASK, "name": _s("New name", max_len=1024), "notes": _s("New description; replaces the old one", max_len=60000),
                       "due_on": DUE_ON, "due_at": DUE_AT, "assignee": ASSIGNEE}, ["task_gid"], read=False, destructive=True, idempotent=True),
    tool("complete_task", "Complete a task", "Marks a task complete (or not complete again with completed=false).",
         complete_task, {"task_gid": TASK, "completed": _b("Default true")}, ["task_gid"], read=False, idempotent=True),
    tool("add_comment", "Comment on a task", "Adds a comment to a task, posted as the token's owner.",
         add_comment, {"task_gid": TASK, "text": _s("Comment text", max_len=20000)}, ["task_gid", "text"], read=False),
    tool("move_task_to_section", "Move a task", "Moves a task to a section (of a project it is in, or adds it to that project).",
         move_task_to_section, {"task_gid": TASK, "section_gid": _s("Target section gid", GID)}, ["task_gid", "section_gid"],
         read=False, idempotent=True),
]
BY_NAME = {t["name"]: t for t in TOOLS}


def check_args(schema, args):
    """Validates against the small schema subset the tools use; returns args or raises ToolError."""
    if not isinstance(args, dict):
        raise ToolError("arguments must be an object")
    props = schema["properties"]
    unknown = [k for k in args if k not in props]
    if unknown:
        raise ToolError(f"unknown argument {', '.join(unknown)}; this tool takes: {', '.join(props) or 'nothing'}")
    for name in schema["required"]:
        if args.get(name) in (None, ""):
            raise ToolError(f"{name} is required")
    for name, value in args.items():
        p = props[name]
        kind = p["type"]
        if kind == "string":
            if not isinstance(value, str):
                raise ToolError(f"{name} must be a string")
            if "pattern" in p and not re.match(p["pattern"], value):
                raise ToolError(f"{name} '{value[:40]}' is not valid: {p['description']}")
            if len(value) > p.get("maxLength", 1 << 30):
                raise ToolError(f"{name} is longer than {p['maxLength']} characters")
        elif kind == "integer":
            if isinstance(value, float) and value.is_integer():
                value = args[name] = int(value)
            if not isinstance(value, int) or isinstance(value, bool):
                raise ToolError(f"{name} must be a whole number")
            if not p["minimum"] <= value <= p["maximum"]:
                raise ToolError(f"{name} must be between {p['minimum']} and {p['maximum']}")
        elif kind == "boolean" and not isinstance(value, bool):
            raise ToolError(f"{name} must be true or false")
    for date_field in ("due_on",):
        if args.get(date_field, "none") != "none":
            try:
                dt.date.fromisoformat(args[date_field])
            except ValueError:
                raise ToolError(f"{date_field} '{args[date_field]}' is not a date")
    return args


# ---- MCP over stdio ------------------------------------------------------------------------------

_out = threading.Lock()
_slots = threading.BoundedSemaphore(4)


def send(message):
    line = json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n"
    with _out:
        sys.stdout.write(line)
        sys.stdout.flush()


def reply(rid, result):
    send({"jsonrpc": "2.0", "id": rid, "result": result})


def error(rid, code, message):
    send({"jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}})


def call_tool(rid, params):
    try:
        name = params.get("name")
        entry = BY_NAME.get(name)
        if entry is None:
            return error(rid, -32602, f"unknown tool: {name}")
        try:
            text, failed = entry["_fn"](check_args(entry["inputSchema"], params.get("arguments") or {})), False
        except ToolError as e:
            text, failed = "Error: " + str(e), True
        except Exception as e:  # a bug here, not the person's input
            print(f"asana: {name} failed: {type(e).__name__}: {_clean(e)}", file=sys.stderr, flush=True)
            text, failed = f"Error: the Asana server failed ({type(e).__name__})", True
        reply(rid, {"content": [{"type": "text", "text": text}], "isError": failed})
    finally:
        _slots.release()


def handle(message):
    if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
        return error(message.get("id") if isinstance(message, dict) else None, -32600, "not a JSON-RPC 2.0 message")
    method, rid, params = message.get("method"), message.get("id"), message.get("params") or {}
    if method is None:
        return  # a response to something we never ask: ignore
    if "id" not in message:
        return  # notifications (initialized, cancelled, ...) need no answer
    if not isinstance(params, dict):
        return error(rid, -32602, "params must be an object")
    if method == "initialize":
        asked = params.get("protocolVersion")
        reply(rid, {
            "protocolVersion": asked if asked in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "clarity-asana", "title": "Asana", "version": VERSION},
            "instructions": "Asana tasks, projects and comments. Items carry their gid; pass it to the other tools.",
        })
    elif method == "ping":
        reply(rid, {})
    elif method == "tools/list":
        reply(rid, {"tools": [{k: v for k, v in t.items() if k != "_fn"} for t in TOOLS]})
    elif method == "tools/call":
        _slots.acquire()
        threading.Thread(target=call_tool, args=(rid, params), daemon=True).start()
    else:
        error(rid, -32601, f"method not found: {method}")


def main():
    for raw in sys.stdin.buffer:
        if not raw.strip():
            continue
        try:
            message = json.loads(raw)
        except ValueError:
            error(None, -32700, "parse error")
            continue
        if isinstance(message, list):
            error(None, -32600, "batches are not supported")
            continue
        handle(message)
    # stdin closed: let running calls finish their answer
    for _ in range(4):
        _slots.acquire()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
