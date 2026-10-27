# Workstream `egress`

Lane: `computers/egress/**` (new), `deploy/container/egress.Containerfile`, `deploy/compose.yaml`
(networks for browser, backend, egress), `computers/browser/` profile prefs (proxy settings),
backend HTTP client proxy config for the model API, `deploy/README.md`, CI/publish entries.

Why: a normal container network reaches both the internet and the person's LAN; compose/Podman
networks cannot filter destinations, and host firewall rules (DOCKER-USER / nftables) need root
and differ per engine. So egress goes through one small proxy that enforces the policy itself.

## Build

1. **Proxy container**: Squid on Alpine (pinned versions), non-root, read-only rootfs, tmpfs for
   cache/logs/run, memory 128 MB. Squid resolves names itself (clients never connect by IP to
   anything but the proxy), so DNS answers pointing at private addresses are refused too.
   - Deny destinations: 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16,
     172.16.0.0/12, 192.0.0.0/24, 192.168.0.0/16, 198.18.0.0/15, 224.0.0.0/4, 240.0.0.0/4,
     ::1/128, fc00::/7, fe80::/10, ff00::/8, ::ffff:0:0/96 mapped forms; plus the host gateway
     names (`host.containers.internal`, `host.docker.internal`).
   - Allow only ports 80 and 443 (CONNECT to 443 only); no caching; no `X-Forwarded-For`/`Via`.
   - Two ACL'd listeners (or source-network ACLs): **browser** (any public host) and **backend**
     (only `openrouter.ai`, plus an optional `CLARITY_BACKEND_EGRESS_ALLOW` list).
2. **Compose**: new internal networks `browser-proxy` (browser ↔ egress) and `backend-proxy`
   (backend ↔ egress); `egress` is the only service on the non-internal `egress` network.
   Remove `browser-egress` and `backend-egress`. Connectors keep their own egress networks (their
   servers are usually on the LAN). Profile: always on (backend needs it).
3. **Firefox**: `user.js` sets `network.proxy.type=1`, HTTP + SSL proxy `egress:3128`,
   `network.proxy.share_proxy_settings`, `network.proxy.socks_remote_dns`, no proxy bypass list,
   `network.dns.disabled=true` is not needed since there is no route without the proxy; WebRTC off
   (`media.peerconnection.enabled=false`) so nothing leaks around the proxy.
4. **Backend**: the OpenRouter clients (chat, embeddings, decisions; and MCP clients if they ever leave the internal networks)
   honor `CLARITY_HTTPS_PROXY` (the JDK HTTP client's proxy selector).
5. **Docs**: what is reachable from where, and how to allow a LAN host deliberately if ever needed.

## Tests

`computers/egress/smoke.sh` (run in CI after build): start egress + a container on the proxy
network; through the proxy, `https://example.com` succeeds; `http://10.x`, `http://192.168.x`,
`http://[fd00::1]`, `http://localhost`, a hostname resolving to a private IP (use Squid's
`hosts_file` fixture), and a non-443 CONNECT all fail with 403; from the browser and backend
containers, a direct connection to a public IP fails (no route). Backend listener rejects any
host except the allowlist.
