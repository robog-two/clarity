# syntax=docker/dockerfile:1
#
# sandboxd: the agent's Linux workspace (Kotlin MCP server + common CLI tools).
# Build from the repository root:
#
#   podman build -f deploy/container/sandbox.Containerfile -t ghcr.io/robog-two/clarity-sandbox .

# ---- build ------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :computers:sandbox:installDist --no-daemon --console=plain \
    && cp -r computers/sandbox/build/install/sandboxd /sandboxd

# ---- runtime ----------------------------------------------------------------
# Ubuntu-based Temurin JRE: has apt for the tool belt the agent expects (bash, coreutils, ripgrep,
# jq, git, python3, pandoc, poppler-utils, sqlite3). No network at runtime, so everything is baked in.
FROM docker.io/library/eclipse-temurin:25-jre

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/robog-two/clarity" \
      org.opencontainers.image.description="Clarity agent sandbox (workspace MCP server)" \
      org.opencontainers.image.licenses="NOASSERTION" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        bash coreutils findutils util-linux procps ripgrep jq git python3 pandoc poppler-utils sqlite3 \
        ca-certificates file less unzip zip xz-utils diffutils patch sed gawk grep tar gzip \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /workspace && chown 10001:10001 /workspace

COPY --from=build /sandboxd /opt/sandboxd

ENV SANDBOX_PORT=8940 \
    SANDBOX_WORKSPACE=/workspace \
    SANDBOX_KEY_FILE=/run/secrets/sandbox_key \
    HOME=/workspace

USER 10001:10001
WORKDIR /workspace
VOLUME ["/workspace"]
EXPOSE 8940
ENTRYPOINT ["/opt/sandboxd/bin/sandboxd"]
