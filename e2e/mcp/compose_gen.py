#!/usr/bin/env python3
"""Writes the e2e/mcp compose file from deploy/compose.yaml (so the real wiring is what runs).

  python3 -I e2e/mcp/compose_gen.py <repo> <out> <port> <tag> <secret-prefix> <state-dir>

Keeps postgres, backend, egress and mcp-host as deploy/compose.yaml defines them (networks,
capabilities, secrets and their modes, limits), and changes only what a throwaway run needs:
  - every external secret is renamed <prefix><name> and mounted under its own name;
  - images are the locally built <tag> ones, the backend is published on 127.0.0.1:<port>;
  - the backend talks to a fake OpenRouter (service fake-model, on the `db` network) directly,
    with embeddings and decision models off, enrollment by token, and CLARITY_MCP_HOST set;
  - mcp-host may run the tests' fake server (kind local) from /opt/mcp-local.
"""
import sys

import yaml

repo, out, port, tag, prefix, state = sys.argv[1:7]
with open(f"{repo}/deploy/compose.yaml") as f:
    spec = yaml.safe_load(f)

keep = ["postgres", "backend", "egress", "mcp-host"]
services = {name: spec["services"][name] for name in keep}

def rename_secrets(svc):
    out_list = []
    for s in svc.get("secrets", []):
        if isinstance(s, str):
            out_list.append({"source": prefix + s, "target": s})
        else:
            s = dict(s)
            s["target"] = s.get("target", s["source"])
            s["source"] = prefix + s["source"]
            out_list.append(s)
    if out_list:
        svc["secrets"] = out_list

for name, svc in services.items():
    svc.pop("build", None)
    svc.pop("profiles", None)
    rename_secrets(svc)

images = {"backend": "backend", "egress": "egress", "mcp-host": "mcphost"}
for name, image in images.items():
    services[name]["image"] = f"localhost/clarity-{image}:{tag}"

backend = services["backend"]
backend["ports"] = [f"127.0.0.1:{port}:8080"]
env = backend["environment"]
env.update({
    "OPENROUTER_BASE_URL": "http://fake-model:8099/api/v1",
    "CLARITY_HTTPS_PROXY": "",
    "CLARITY_MEMORY_EMBEDDINGS": "false",
    "CLARITY_DECISIONS": "false",
    "CLARITY_ENROLLMENT_MODE": "token",
    "CLARITY_CONNECTORS_AUTO_GRANT": "false",
    "CLARITY_MCP_HOST": "http://mcp-host:8950;keyFile=/run/secrets/mcphost_key",
    "CLARITY_MCP_SERVERS": "",
    "OPENROUTER_API_KEY": "",
})
for k, v in list(env.items()):
    if isinstance(v, str) and v.startswith("${"):
        # ${VAR:-default} -> default: nothing comes from the person's deploy/.env
        env[k] = v.split(":-", 1)[1].rstrip("}") if ":-" in v else ""
backend["depends_on"] = {"postgres": {"condition": "service_healthy"}, "egress": {"condition": "service_healthy"}, "fake-model": {"condition": "service_started"}}

host = services["mcp-host"]
host["environment"]["MCPHOST_LOCAL_DIR"] = "/opt/mcp-local"
host["environment"]["CLARITY_SYNC_INTERVAL"] = "5s"
host["volumes"] = list(host["volumes"]) + [f"{repo}/computers/mcphost/src/test/resources:/opt/mcp-local:ro,z"]

services["egress"]["environment"]["CLARITY_BACKEND_EGRESS_ALLOW"] = ""

services["fake-model"] = {
    "image": "docker.io/library/python:3-alpine",
    "user": "10001:10001",
    "read_only": True,
    "cap_drop": ["ALL"],
    "command": ["python3", "/fake/fake_openrouter.py"],
    "volumes": [f"{repo}/e2e/mcp/fake_openrouter.py:/fake/fake_openrouter.py:ro,z", f"{state}:/state:rw,z"],
    "networks": ["db"],
}

used_networks = set()
for svc in services.values():
    nets = svc.get("networks", [])
    used_networks.update(nets if isinstance(nets, list) else nets.keys())
used_secrets = {s["source"] for svc in services.values() for s in svc.get("secrets", [])}
used_volumes = set()
for svc in services.values():
    for v in svc.get("volumes", []):
        name = v.split(":", 1)[0]
        if not name.startswith("/") and not name.startswith("."):
            used_volumes.add(name)

result = {
    "name": spec.get("name", "clarity"),
    "services": services,
    "networks": {n: spec["networks"][n] for n in sorted(used_networks)},
    "secrets": {s: {"external": True} for s in sorted(used_secrets)},
    "volumes": {v: spec["volumes"].get(v) or {} for v in sorted(used_volumes)},
}
with open(out, "w") as f:
    yaml.safe_dump(result, f, sort_keys=False)
print(" ".join(sorted(s[len(prefix):] for s in used_secrets)))
