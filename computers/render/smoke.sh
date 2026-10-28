#!/bin/sh
# Smoke test for the render image: starts it hardened (read-only rootfs, no capabilities, no network
# but a published loopback port), checks the key is required, then renders every format.
#   computers/render/smoke.sh [image]
set -eu
image="${1:-ghcr.io/robog-two/clarity-render:local}"
name="clarity-render-smoke-$$"
port="${SMOKE_PORT:-18950}"
key="smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
tmp="$(mktemp -d)"
keyfile="$tmp/render_key"
printf '%s' "$key" >"$keyfile"; chmod 644 "$keyfile"
cleanup() { podman rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$tmp"; }
trap cleanup EXIT

podman run -d --name "$name" --read-only --cap-drop ALL --security-opt no-new-privileges \
  --tmpfs /tmp:rw,nosuid,size=256m --memory 768m --cpus 1 --pids-limit 128 \
  -v "$keyfile:/run/secrets/render_key:ro,z" -p "127.0.0.1:$port:8950" "$image" >/dev/null

base="http://127.0.0.1:$port"
for _ in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/healthz" || true)" = 200 ] && break
  sleep 1
done
curl -fsS "$base/healthz" | grep -q '"version"' || { echo "FAIL: healthz"; exit 1; }

body='{"source":"---\ntitle: Smoke\nauthor: Clarity\n---\n\n# One\n\nA paragraph with *emphasis*.\n\n- a\n- b\n\n![x](/run/secrets/render_key)\n","style":{"lineSpacing":2,"paragraphIndentIn":0.5,"paragraphSpacingPt":0,"titlePage":true,"pageNumbers":true},"format":"FORMAT"}'
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$base/render" -d "$(printf "%s" "$body" | sed s/FORMAT/txt/)")" = 401 ] \
  || { echo "FAIL: unauthenticated render not rejected"; exit 1; }

for format in pdf docx odt txt md; do
  out="$tmp/out.$format"
  code="$(curl -s -o "$out" -w '%{http_code}' -X POST "$base/render" -H "Authorization: Bearer $key" \
    -H 'Content-Type: application/json' -d "$(printf "%s" "$body" | sed s/FORMAT/$format/)")"
  [ "$code" = 200 ] || { echo "FAIL: $format answered $code: $(head -c 400 "$out")"; exit 1; }
  case "$format" in
    pdf) head -c 4 "$out" | grep -q '%PDF' || { echo "FAIL: pdf"; exit 1; } ;;
    docx|odt) head -c 2 "$out" | grep -q 'PK' || { echo "FAIL: $format is not a zip"; exit 1; }
      # pageNumbers: a PAGE field in the Word footer, a page-number field in the ODF master page
      if [ "$format" = docx ]; then part=word/footer-clarity.xml; mark=PAGE; else part=styles.xml; mark='text:page-number'; fi
      python3 -c 'import sys,zipfile; sys.exit(0 if sys.argv[3] in zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]).decode() else 1)' "$out" "$part" "$mark" \
        || { echo "FAIL: $format has no page numbers"; exit 1; }
      # the default heading family (Libre Caslon Text) travels inside the file
      if [ "$format" = docx ]; then fontpart=word/fonts/font1.odttf; else fontpart=Fonts/LibreCaslonText-Regular.ttf; fi
      python3 -c 'import sys,zipfile; sys.exit(0 if sys.argv[2] in zipfile.ZipFile(sys.argv[1]).namelist() else 1)' "$out" "$fontpart" \
        || { echo "FAIL: $format does not embed the heading font"; exit 1; } ;;
    txt|md) grep -q 'paragraph' "$out" || { echo "FAIL: $format text"; exit 1; } ;;
  esac
  # the key file (named as an image above) must never be read into the file
  if grep -q "$key" "$out"; then echo "FAIL: $format contains the key"; exit 1; fi
  echo "ok $format ($(wc -c <"$out") bytes)"
done
t0=$(date +%s); podman stop -t 10 "$name" >/dev/null
[ $(( $(date +%s) - t0 )) -lt 8 ] || { echo "FAIL: renderd ignored SIGTERM"; exit 1; }
echo "render smoke OK"
