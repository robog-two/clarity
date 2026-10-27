# syntax=docker/dockerfile:1
#
# One build file for every JVM service in the monorepo (backend, connectors/*).
# Build from the repository root:
#
#   podman build -f deploy/container/jvm-app.Containerfile \
#     --build-arg MODULE=backend --build-arg APP_NAME=clarity-backend --build-arg MODULE_DIR=backend \
#     -t ghcr.io/robog-two/clarity-backend .
#
#   podman build -f deploy/container/jvm-app.Containerfile \
#     --build-arg MODULE=connectors:example --build-arg APP_NAME=clarity-connector-example \
#     --build-arg MODULE_DIR=connectors/example -t ghcr.io/robog-two/clarity-connector-example .

# ---- build ------------------------------------------------------------------
# Always runs on the build host's native architecture: the output is architecture-independent
# bytecode, so multi-arch images need no QEMU emulation.
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk AS build

# MODULE: Gradle project path ("backend", "connectors:example").
# APP_NAME: that module's `application { applicationName }`.
# MODULE_DIR: the module's directory relative to the repo root.
ARG MODULE
ARG APP_NAME
ARG MODULE_DIR

WORKDIR /src
# Only what Gradle needs for the JVM modules (settings.gradle.kts names all of them), so a change to
# the app, docs, deploy files or the browser image does not rebuild this image. Tests are left out
# of the build context (.containerignore).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY shared shared
COPY connector-sdk connector-sdk
COPY backend backend
COPY connectors connectors
COPY computers/sandbox computers/sandbox

# The Gradle user home is a cache mount (BuildKit and Buildah): dependencies are downloaded once.
# The start script is renamed to a fixed name so ENTRYPOINT does not depend on APP_NAME.
# Capped like local builds: the deploy builds beside the running stack on a 7 GB host.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew ":${MODULE}:installDist" --no-daemon --console=plain \
       -Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseParallelGC -Dfile.encoding=UTF-8" \
       -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 \
    && mkdir -p /out \
    && cp -r "${MODULE_DIR}/build/install/${APP_NAME}" /out/app \
    && mv "/out/app/bin/${APP_NAME}" /out/app/bin/app \
    && rm -f "/out/app/bin/${APP_NAME}.bat" \
    && mkdir -p /out/data

# ---- runtime ----------------------------------------------------------------
# Alpine JRE: smallest Temurin variant, still ships a POSIX shell (needed by Gradle's start
# script) and busybox wget (compose healthchecks). No RUN steps here on purpose.
FROM docker.io/library/eclipse-temurin:25-jre-alpine

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.source="https://github.com/robog-two/clarity" \
      org.opencontainers.image.description="Clarity JVM service" \
      org.opencontainers.image.licenses="NOASSERTION" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"

# Numeric, unprivileged user; /data is the service's own state volume.
COPY --from=build --chown=10001:10001 /out/data /data
COPY --from=build --chown=root:root /out/app /opt/app

USER 10001:10001
WORKDIR /data
VOLUME ["/data"]
ENTRYPOINT ["/opt/app/bin/app"]
