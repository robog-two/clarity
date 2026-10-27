# syntax=docker/dockerfile:1
#
# renderd: turns a report revision (Markdown + style spec) into PDF, Word, OpenDocument or plain text
# with pandoc (Typst for PDFs; no LaTeX, it is far too big). A Python standard-library HTTP server
# (computers/render/renderd.py) on :8950, bearer key from RENDER_KEY_FILE. It has no network but the
# backend's internal `render` network. Build from the repository root:
#
#   podman build -f deploy/container/render.Containerfile -t ghcr.io/robog-two/clarity-render .
#
# pandoc and Typst are pinned and verified by sha256 (both are static binaries, so they run on
# Alpine). To bump one, change its version and both checksums together; GitHub lists them as each
# release asset's `digest` (gh api repos/jgm/pandoc/releases/tags/<v>, repos/typst/typst/releases/tags/v<v>).

ARG ALPINE_IMAGE=docker.io/library/alpine:3.24.2

# ---- fetch: verified downloads ---------------------------------------------------------------
FROM ${ALPINE_IMAGE} AS fetch
ARG TARGETARCH

ARG PANDOC_VERSION=3.12.1
ARG PANDOC_SHA256_AMD64=d0c90410e90204c9ca83b8539fac5c7aed01fd537207e4585849f8abc5df20b8
ARG PANDOC_SHA256_ARM64=445d96fd08801fe636cab1158b4f017ee320ac3b7446328b4fe2f902117b19b9

ARG TYPST_VERSION=0.15.1
ARG TYPST_SHA256_AMD64=a6d077d0a95eed5a2eba715b2dae06be954f624ccbf85758a03f389ded33118c
ARG TYPST_SHA256_ARM64=5aa8d74a3d906e60ea12a66ac2f37f8eef1b14cbad7182a745e393a10c23dcee

RUN apk add --no-cache ca-certificates curl tar xz

# Also writes pandoc's own reference documents; renderd patches a copy of them per style at render time.
RUN set -eu; \
    case "$TARGETARCH" in \
      amd64) p_arch=amd64; p_sha=$PANDOC_SHA256_AMD64; t_arch=x86_64;  t_sha=$TYPST_SHA256_AMD64 ;; \
      arm64) p_arch=arm64; p_sha=$PANDOC_SHA256_ARM64; t_arch=aarch64; t_sha=$TYPST_SHA256_ARM64 ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    mkdir -p /out/bin /dl; cd /dl; \
    curl -fsSL -o pandoc.tar.gz "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-linux-${p_arch}.tar.gz"; \
    echo "${p_sha}  pandoc.tar.gz" | sha256sum -c -; \
    tar -xzf pandoc.tar.gz; \
    cp "pandoc-${PANDOC_VERSION}/bin/pandoc" /out/bin/pandoc; \
    curl -fsSL -o typst.tar.xz "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-${t_arch}-unknown-linux-musl.tar.xz"; \
    echo "${t_sha}  typst.tar.xz" | sha256sum -c -; \
    tar -xJf typst.tar.xz; \
    cp "typst-${t_arch}-unknown-linux-musl/typst" /out/bin/typst; \
    chmod 0755 /out/bin/pandoc /out/bin/typst; \
    /out/bin/pandoc -o /out/reference.docx --print-default-data-file reference.docx; \
    /out/bin/pandoc -o /out/reference.odt --print-default-data-file reference.odt; \
    rm -rf /dl

# ---- runtime ---------------------------------------------------------------------------------
FROM ${ALPINE_IMAGE}

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/robog-two/clarity" \
      org.opencontainers.image.description="Clarity report renderer (pandoc + Typst, no network)" \
      org.opencontainers.image.licenses="NOASSERTION" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Liberation fonts: metric-compatible stand-ins for Times New Roman, Arial and Courier New in PDFs
# (Word and OpenDocument files name the real family, which the reader's computer has).
RUN apk add --no-cache python3 font-liberation

COPY --from=fetch /out/bin/pandoc /out/bin/typst /usr/local/bin/
COPY --from=fetch /out/reference.docx /out/reference.odt /opt/render/
COPY --chmod=0644 computers/render/renderd.py computers/render/sanitize.lua computers/render/titlepage.lua /opt/render/

ENV RENDER_PORT=8950 \
    RENDER_KEY_FILE=/run/secrets/render_key \
    RENDER_TIMEOUT=60 \
    RENDER_DATA=/opt/render \
    TYPST_FONT_PATHS=/usr/share/fonts \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/tmp

USER 10001:10001
WORKDIR /tmp
EXPOSE 8950
ENTRYPOINT ["python3", "/opt/render/renderd.py"]
