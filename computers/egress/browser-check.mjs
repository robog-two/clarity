// Run inside the browser container (`podman exec -i <browser> node - < browser-check.mjs`): drives
// Firefox over the local MCP endpoint and proves the proxy is in the path.
//   - https://example.com loads (through the proxy)
//   - http://10.0.0.1/ and http://169.254.169.254/ do not (the proxy answers 403)
// Env: BROWSER_KEY (bearer key, optional).
const base = "http://127.0.0.1:8931/mcp";
const headers = { "Content-Type": "application/json", Accept: "application/json, text/event-stream" };
if (process.env.BROWSER_KEY) headers.Authorization = `Bearer ${process.env.BROWSER_KEY}`;
let sid = "";
let id = 0;

async function rpc(method, params, notify = false) {
  const body = { jsonrpc: "2.0", method, params };
  if (!notify) body.id = ++id;
  const res = await fetch(base, { method: "POST", headers: { ...headers, ...(sid ? { "Mcp-Session-Id": sid } : {}) }, body: JSON.stringify(body) });
  sid = res.headers.get("mcp-session-id") || sid;
  const text = await res.text();
  if (notify) return "";
  const line = text.split("\n").find((l) => l.startsWith("data: ")) ?? text;
  return line.replace(/^data: /, "");
}
const call = (name, args) => rpc("tools/call", { name, arguments: args });
const fail = (msg) => { console.error("FAIL: " + msg); process.exit(1); };

await rpc("initialize", { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "egress-check", version: "1" } });
await rpc("notifications/initialized", {}, true);

await call("navigate_page", { url: "https://example.com/" });
const snap = await call("take_snapshot", {});
if (!snap.includes("This domain is for use")) fail("example.com did not load through the proxy: " + snap.slice(0, 400));
console.log("ok   example.com loads through the proxy");

for (const url of ["http://10.0.0.1/", "http://169.254.169.254/latest/meta-data/"]) {
  const nav = await call("navigate_page", { url });
  const s = await call("take_snapshot", {});
  // The proxy's refusal page (or a navigation error); never a real response from the target.
  if (!/ERR_|error|refused|denied|Access Denied|not be retrieved/i.test(nav + s)) fail(`${url} was not refused: ${s.slice(0, 300)}`);
  console.log(`ok   ${url} refused`);
}
console.log("browser-through-proxy OK");
