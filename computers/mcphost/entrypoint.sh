#!/bin/sh
# Starts the mcp-host supervisor. Files it and its servers create are private by default (umask
# 077): a server's home is 0700 and nothing a server writes is readable by another.
set -eu
umask 077
exec /opt/mcphost/bin/mcphost "$@"
