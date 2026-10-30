# syntax=docker/dockerfile:1
#
# mcp-host: installs and runs the person's MCP servers (npm, PyPI, GitHub release binaries) and
# forwards to remote ones, one uid per server, reaching the internet only through the egress proxy.
# Build from the repository root:
#
#   podman build -f deploy/container/mcphost.Containerfile -t ghcr.io/robog-two/clarity-mcphost .
#
# Node, npm, Python, uv and setpriv are pinned Alpine packages. To bump: `apk policy <pkg>` inside the
# new base image. Node 24 honours NODE_USE_ENV_PROXY (fetch and http/https through HTTPS_PROXY).

# ---- build ------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk AS build
WORKDIR /src
# Only what Gradle needs for the JVM modules (settings.gradle.kts names all of them). Tests are left
# out of the build context (.containerignore).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY shared shared
COPY connector-sdk connector-sdk
COPY backend backend
COPY connectors connectors
COPY computers/sandbox computers/sandbox
COPY computers/mcphost computers/mcphost
# Capped like local builds: the deploy builds beside the running stack on a 7 GB host.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :computers:mcphost:installDist --no-daemon --console=plain \
       -Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseParallelGC -Dfile.encoding=UTF-8" \
       -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
    && cp -r computers/mcphost/build/install/mcphost /mcphost

# ---- runtime ----------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25-jre-alpine

ARG VERSION=dev
ARG REVISION=unknown
ARG NODEJS_VERSION=24.22.1-r1
ARG NPM_VERSION=11.12.1-r0
ARG PYTHON_VERSION=3.14.10-r1
ARG UV_VERSION=0.11.19-r1
ARG SETPRIV_VERSION=2.42.3-r1
LABEL org.opencontainers.image.source="https://github.com/robog-two/clarity" \
      org.opencontainers.image.description="Clarity MCP host (third-party MCP servers, one uid each)" \
      org.opencontainers.image.licenses="NOASSERTION" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# /data: 0711 so a server can reach its own home but list nothing; /data/supervisor is the
# supervisor's alone (its sealing key, state, listings). /egress is the volume shared with the proxy.
RUN apk add --no-cache \
        nodejs=${NODEJS_VERSION} npm=${NPM_VERSION} python3=${PYTHON_VERSION} uv=${UV_VERSION} setpriv=${SETPRIV_VERSION} \
    && mkdir -p /data/servers /data/supervisor /egress \
    && chmod 0711 /data /data/servers && chmod 0700 /data/supervisor && chmod 0750 /egress

COPY --from=build /mcphost /opt/mcphost
COPY computers/mcphost/entrypoint.sh /usr/local/bin/mcphost-entrypoint

ENV MCPHOST_PORT=8950 \
    MCPHOST_KEY_FILE=/run/secrets/mcphost_key \
    MCPHOST_SERVERS_DIR=/data/servers \
    MCPHOST_EGRESS_DIR=/egress \
    MCPHOST_EGRESS_GID=10001 \
    CLARITY_STATE_DIR=/data/supervisor

# Root inside the (rootless) container, with only SETUID, SETGID, CHOWN and KILL kept (compose):
# the supervisor needs them to run each server as its own uid. See Isolation.kt.
USER 0:0
WORKDIR /data
VOLUME ["/data"]
EXPOSE 8950
ENTRYPOINT ["/usr/local/bin/mcphost-entrypoint"]
