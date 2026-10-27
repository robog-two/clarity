# syntax=docker/dockerfile:1
#
# The agent's browser: headless Firefox ESR with uBlock Origin and Privacy Badger preinstalled,
# driven by Mozilla's Firefox DevTools MCP server (stdio only) behind supergateway, which serves it
# as streamable HTTP on :8931/mcp with a bearer key. Build from the repository root:
#
#   podman build -f deploy/container/browser.Containerfile -t ghcr.io/robog-two/clarity-browser .
#
# Every download is pinned to an exact version and verified by sha256. To bump one, change the ARG
# and its checksum together (Firefox: https://archive.mozilla.org/pub/firefox/releases/<v>/SHA256SUMS,
# add-ons: https://addons.mozilla.org/api/v5/addons/addon/<slug>/ -> current_version.file.hash).

ARG NODE_IMAGE=docker.io/library/node:24.22.1-bookworm-slim

# ---- fetch: verified downloads ---------------------------------------------------------------
FROM ${NODE_IMAGE} AS fetch
ARG TARGETARCH

ARG FIREFOX_VERSION=153.5.1esr
ARG FIREFOX_SHA256_AMD64=e289bdc532decf2b54dea9b5ac64cf6f84a62eeec4d4a9ec2f69590f61b0ff1c
ARG FIREFOX_SHA256_ARM64=e566ecb209917c0b4d11813212c5afae4383f28ca554c4ad651df044cb255cde

ARG GECKODRIVER_VERSION=0.37.1
ARG GECKODRIVER_SHA256_AMD64=e815130ea95983e162ae91843b48d3a3ce991735635fce83a647afde21e09f7e
ARG GECKODRIVER_SHA256_ARM64=8fd90b951422fbad5b56539fb344dff66eb3f986d615d8d6fde9f1f62dad610c

# uBlock Origin 1.75.0 (uBlock0@raymondhill.net) and Privacy Badger 2026.9.15 (jid1-MnnxcxisBPnSXQ@jetpack)
ARG UBLOCK_URL=https://addons.mozilla.org/firefox/downloads/file/5034826/ublock_origin-1.75.0.xpi
ARG UBLOCK_SHA256=5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287
ARG PRIVACY_BADGER_URL=https://addons.mozilla.org/firefox/downloads/file/5032646/privacy_badger17-2026.9.15.xpi
ARG PRIVACY_BADGER_SHA256=97cd8911e3486da34a532a7c7f2806cd6d0cfa117a1fbe5e8747042a94442d62

# uBlock Origin's cookie-notice lists (policies.json), baked into the profile template so they work from the
# first page after a cold start; uBO updates them itself afterwards. Pinned to a commit and verified.
# uAssets gh-pages (what ublockorigin.github.io serves) and AdGuard's FiltersRegistry, both via jsDelivr.
# The fetch stage also makes a throwaway CA and certificate that only the prewarm run trusts
# (prewarm-lists.mjs serves the pinned lists to uBO); neither is shipped in the profile.
ARG UBO_ASSETS_COMMIT=539a01fe0d80279ffa1c60c3f4638b34601af3cd
ARG UBO_COOKIES_SHA256=3ec381cfe2ad78d274c4b562ecb337e05facbb2abc4feb72713f9d5f97df6ec2
ARG EASYLIST_COOKIES_SHA256=162fac1f940746592bd68ab25806ed52568eb1b7daa30db9d4dcd402dc7c384e
ARG ADGUARD_FILTERS_COMMIT=3c6fc50acd2f72f0f69fd6e17cfb92cf91068711
ARG ADGUARD_COOKIES_SHA256=1de29a32d9b10d2846a4d82494c89b8bf237741af46d79139f579374894442f4

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl xz-utils openssl \
    && rm -rf /var/lib/apt/lists/*

RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) ff_dir=linux-x86_64; ff_sha=$FIREFOX_SHA256_AMD64; gd_name=linux64;         gd_sha=$GECKODRIVER_SHA256_AMD64 ;; \
      arm64) ff_dir=linux-aarch64; ff_sha=$FIREFOX_SHA256_ARM64; gd_name=linux-aarch64;  gd_sha=$GECKODRIVER_SHA256_ARM64 ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    mkdir -p /out /dl; cd /dl; \
    curl -fsSL -o firefox.tar.xz "https://archive.mozilla.org/pub/firefox/releases/${FIREFOX_VERSION}/${ff_dir}/en-US/firefox-${FIREFOX_VERSION}.tar.xz"; \
    echo "${ff_sha}  firefox.tar.xz" | sha256sum -c -; \
    tar -xJf firefox.tar.xz -C /out; \
    curl -fsSL -o geckodriver.tar.gz "https://github.com/mozilla/geckodriver/releases/download/v${GECKODRIVER_VERSION}/geckodriver-v${GECKODRIVER_VERSION}-${gd_name}.tar.gz"; \
    echo "${gd_sha}  geckodriver.tar.gz" | sha256sum -c -; \
    tar -xzf geckodriver.tar.gz -C /out; \
    mkdir -p /out/extensions; \
    curl -fsSL -o /out/extensions/uBlock0@raymondhill.net.xpi "$UBLOCK_URL"; \
    echo "${UBLOCK_SHA256}  /out/extensions/uBlock0@raymondhill.net.xpi" | sha256sum -c -; \
    curl -fsSL -o "/out/extensions/jid1-MnnxcxisBPnSXQ@jetpack.xpi" "$PRIVACY_BADGER_URL"; \
    echo "${PRIVACY_BADGER_SHA256}  /out/extensions/jid1-MnnxcxisBPnSXQ@jetpack.xpi" | sha256sum -c -; \
    mkdir -p /out/prewarm/lists /out/prewarm/tls; cd /out/prewarm/lists; \
    curl -fsSL -o annoyances-cookies.txt "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@${UBO_ASSETS_COMMIT}/filters/annoyances-cookies.txt"; \
    curl -fsSL -o easylist-cookies.txt "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@${UBO_ASSETS_COMMIT}/thirdparties/easylist-cookies.txt"; \
    curl -fsSL -o adguard-18.txt "https://cdn.jsdelivr.net/gh/AdguardTeam/FiltersRegistry@${ADGUARD_FILTERS_COMMIT}/platforms/extension/ublock/filters/18.txt"; \
    printf '%s  %s\n' "$UBO_COOKIES_SHA256" annoyances-cookies.txt "$EASYLIST_COOKIES_SHA256" easylist-cookies.txt "$ADGUARD_COOKIES_SHA256" adguard-18.txt | sha256sum -c -; \
    cd /out/prewarm/tls; \
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=Clarity prewarm CA" -keyout ca.key -out ca.pem 2>/dev/null; \
    openssl req -newkey rsa:2048 -nodes -subj "/CN=prewarm" -keyout leaf.key -out leaf.csr 2>/dev/null; \
    printf 'subjectAltName=DNS:ublockorigin.github.io,DNS:ublockorigin.pages.dev,DNS:cdn.jsdelivr.net,DNS:filters.adtidy.org,DNS:secure.fanboy.co.nz,DNS:raw.githubusercontent.com\nbasicConstraints=CA:FALSE\n' > ext.cnf; \
    openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2 -extfile ext.cnf -out leaf.pem 2>/dev/null; \
    rm -f ca.key leaf.csr ca.srl ext.cnf

# ---- deps: pinned npm tree (package-lock.json carries integrity hashes) ----------------------
FROM ${NODE_IMAGE} AS deps
WORKDIR /opt/mcp
COPY computers/browser/package.json computers/browser/package-lock.json ./
# --ignore-scripts: the geckodriver npm package would download a binary at install time; we ship
# a checksum-verified one instead.
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund && npm cache clean --force

# ---- runtime ---------------------------------------------------------------------------------
FROM ${NODE_IMAGE}

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/robog-two/clarity" \
      org.opencontainers.image.description="Clarity agent browser (Firefox ESR + uBlock Origin + Privacy Badger, Firefox DevTools MCP)" \
      org.opencontainers.image.licenses="NOASSERTION" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates fontconfig fonts-dejavu-core fonts-liberation fonts-noto-color-emoji \
        libgtk-3-0 libdbus-glib-1-2 libasound2 libx11-xcb1 libxtst6 libxt6 libxcomposite1 \
        libxdamage1 libxrandr2 libxcursor1 libxi6 libxfixes3 libpci3 \
    && rm -rf /var/lib/apt/lists/* \
    && fc-cache -f

COPY --from=fetch /out/firefox /opt/firefox
COPY --from=fetch /out/geckodriver /usr/local/bin/geckodriver
COPY --from=fetch /out/extensions /opt/profile-template/extensions
COPY computers/browser/profile/user.js /opt/profile-template/user.js
COPY --chmod=0644 computers/browser/policies.json /opt/firefox/distribution/policies.json
COPY --from=deps /opt/mcp /opt/mcp
COPY --chmod=0755 computers/browser/entrypoint.sh /usr/local/bin/clarity-browser
COPY --chmod=0644 computers/browser/session-prep.mjs /opt/clarity-browser/session-prep.mjs
COPY --chmod=0755 computers/browser/firefox-wrapper.sh /opt/clarity-browser/firefox
COPY computers/browser/prewarm.sh /tmp/prewarm.sh
COPY --from=fetch /out/prewarm /tmp/prewarm
COPY computers/browser/prewarm-lists.mjs /tmp/prewarm/prewarm-lists.mjs

# Run Firefox once so the add-ons are installed and past their first-run logic (see prewarm.sh).
RUN sh /tmp/prewarm.sh /opt/profile-template && rm -rf /tmp/prewarm.sh /tmp/prewarm

# Downloads: a volume shared read-only with the sandbox (compose mounts browser-downloads here).
RUN mkdir -p /downloads /profile && chown 10001:10001 /downloads /profile

# Numeric unprivileged user; everything mutable lives on /tmp (tmpfs), except /downloads (a volume).
ENV HOME=/tmp/home \
    MOZ_DISABLE_UPDATE_PROCESSING=1 \
    MOZ_CRASHREPORTER_DISABLE=1 \
    BROWSER_PORT=8931 \
    BROWSER_KEY_FILE=/run/secrets/browser_key

USER 10001:10001
WORKDIR /tmp
EXPOSE 8931
ENTRYPOINT ["/usr/local/bin/clarity-browser"]
