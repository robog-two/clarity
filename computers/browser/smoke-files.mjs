// Smoke test helper (smoke.sh), run inside the browser container: stands in for the egress proxy on
// 127.0.0.1:3128 (smoke.sh maps the name `egress` there), so Firefox's downloads take the same path
// as in production: a proxied request for a public host. It answers every host itself:
//   /              a page with download links
//   /report.csv    a CSV sent as an attachment
//   /statement.pdf a PDF sent as an attachment
//   /inline.pdf    a PDF sent inline (what many "download statement" links do)
// The bytes are fixed so smoke.sh can compare what lands in /downloads.
import http from "node:http";

const CSV = "date,amount,payee\n2026-10-01,12.50,Water Co\n2026-10-15,40.00,Power Co\n";
// A minimal valid one-page PDF with the text "Clarity smoke statement".
const PDF = [
  "%PDF-1.4",
  "1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj",
  "2 0 obj <</Type /Pages /Kids [3 0 R] /Count 1>> endobj",
  "3 0 obj <</Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R /Resources <</Font <</F1 5 0 R>>>>>> endobj",
  "4 0 obj <</Length 58>> stream",
  "BT /F1 18 Tf 20 70 Td (Clarity smoke statement) Tj ET",
  "endstream endobj",
  "5 0 obj <</Type /Font /Subtype /Type1 /BaseFont /Helvetica>> endobj",
  "trailer <</Root 1 0 R>>",
  "%%EOF",
  "",
].join("\n");

const PAGE = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Clarity download smoke page</title></head>
<body>
  <h1>Your documents</h1>
  <p><a href="/report.csv">Download the report</a></p>
  <p><a href="/statement.pdf">Download the statement</a></p>
  <p><a href="/inline.pdf">View the inline statement</a></p>
</body></html>`;

http
  .createServer((req, res) => {
    // A proxied request carries the absolute URL; a direct one only the path.
    const path = new URL(req.url, "http://files.clarity.test").pathname;
    const send = (status, type, body, extra = {}) => {
      res.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(body), ...extra });
      res.end(body);
    };
    if (path === "/") return send(200, "text/html; charset=utf-8", PAGE);
    if (path === "/report.csv") return send(200, "text/csv", CSV, { "Content-Disposition": 'attachment; filename="report.csv"' });
    if (path === "/statement.pdf") return send(200, "application/pdf", PDF, { "Content-Disposition": 'attachment; filename="statement.pdf"' });
    if (path === "/inline.pdf") return send(200, "application/pdf", PDF);
    if (path === "/fixtures.json") return send(200, "application/json", JSON.stringify({ csv: CSV, pdf: PDF }));
    send(404, "text/plain", "not found");
  })
  .listen(3128, "127.0.0.1", () => console.log("smoke file server on 127.0.0.1:3128"));
