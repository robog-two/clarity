// Build-time only (prewarm.sh). Stands in for the network while Firefox runs once on the profile
// template, so uBlock Origin downloads its cookie-notice lists from pinned, sha256-verified copies
// (fetched in the Containerfile) instead of the live internet. It answers as an HTTP proxy on
// 127.0.0.1:3199 and terminates TLS for the list hosts with a throwaway certificate that only the
// prewarm run trusts; anything it does not have is a 404, so uBO keeps its packaged copies of the
// default lists. After the run uBO updates the lists itself, through the egress proxy.
//
//   node prewarm-lists.mjs <lists-dir> <tls-dir>
import http from "node:http";
import tls from "node:tls";
import fs from "node:fs";

const [listsDir, tlsDir] = process.argv.slice(2);
const secure = tls.createSecureContext({ cert: fs.readFileSync(`${tlsDir}/leaf.pem`), key: fs.readFileSync(`${tlsDir}/leaf.key`) });
const HOSTS = new Set(["ublockorigin.github.io", "ublockorigin.pages.dev", "cdn.jsdelivr.net", "filters.adtidy.org", "secure.fanboy.co.nz", "raw.githubusercontent.com"]);

// Which pinned file answers a list URL (uBO tries its CDN mirrors first, then the origin).
const fileFor = (host, path) => {
  if (/\/filters\/annoyances-cookies(\.min)?\.txt$/.test(path)) return "annoyances-cookies.txt";
  if (/\/thirdparties\/easylist-cookies(\.min)?\.txt$/.test(path) || /fanboy-cookiemonster(_ubo)?\.txt$/.test(path)) return "easylist-cookies.txt";
  if (host === "filters.adtidy.org" && path === "/extension/ublock/filters/18.txt") return "adguard-18.txt";
  return null;
};

const app = http.createServer((req, res) => {
  const host = (req.headers.host || "").split(":")[0];
  const path = new URL(req.url, `https://${host}`).pathname;
  const file = fileFor(host, path);
  console.log(`${file ? "200 " + file : "404"} ${host}${path}`);
  if (!file) { res.writeHead(404); res.end(); return; }
  const body = fs.readFileSync(`${listsDir}/${file}`);
  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8", "Content-Length": body.length });
  res.end(body);
});

app.on("connect", (req, socket) => {
  const host = req.url.split(":")[0];
  if (!HOSTS.has(host)) { console.log(`refused ${host}`); socket.end("HTTP/1.1 403 Forbidden\r\n\r\n"); return; }
  console.log(`connect ${host}`);
  socket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
  const s = new tls.TLSSocket(socket, { isServer: true, secureContext: secure });
  s.on("error", (e) => console.log(`tls ${host}: ${e.message}`));
  app.emit("connection", s);
});

app.listen(3199, "127.0.0.1", () => console.log("prewarm list server on 127.0.0.1:3199"));
