# The runtime stage of deploy/container/jvm-app.Containerfile, for an app that was already built on
# the host (`./gradlew :backend:installDist`): e2e/run.sh stages `app/` (start script renamed to
# bin/app) and an empty `data/` and builds this, so CI and laptops skip the Gradle-in-container build.
FROM docker.io/library/eclipse-temurin:25-jre-alpine

COPY --chown=10001:10001 data /data
COPY --chown=root:root app /opt/app

USER 10001:10001
WORKDIR /data
VOLUME ["/data"]
ENTRYPOINT ["/opt/app/bin/app"]
