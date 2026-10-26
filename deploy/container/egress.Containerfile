# syntax=docker/dockerfile:1
#
# The egress proxy: Squid, the only container with a route to the internet (besides the connectors
# and Caddy). The browser may reach any *public* host through it; the backend only the model API.
# Build from the repository root:
#
#   podman build -f deploy/container/egress.Containerfile -t ghcr.io/robog-two/clarity-egress .
#
# Alpine and Squid are pinned. To bump: `apk policy squid` inside the new Alpine image.
FROM docker.io/library/alpine:3.23.7

ARG SQUID_VERSION=7.6-r0

RUN apk add --no-cache squid=${SQUID_VERSION} \
    && rm -rf /etc/squid/squid.conf* /etc/squid/errorpage.css

COPY computers/egress/entrypoint.sh /usr/local/bin/egress-entrypoint
COPY computers/egress/squid.conf.tmpl /usr/local/share/egress/squid.conf.tmpl

# Non-root. Everything Squid writes (config, logs, spool) goes to tmpfs under /tmp.
USER 10001:10001
EXPOSE 3128
HEALTHCHECK --interval=10s --timeout=3s --retries=6 --start-period=5s \
    CMD ["nc", "-z", "127.0.0.1", "3130"]
ENTRYPOINT ["/usr/local/bin/egress-entrypoint"]
