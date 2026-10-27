#!/bin/sh
# Container healthcheck: one real proxy request to the loopback-only health listener. Squid refuses
# it (403, nothing leaves) but has to parse it and run its access rules to answer, so an answer
# means Squid is serving. A bare TCP connect (`nc -z`) would also work but Squid logs every one as
# `error:transaction-end-before-headers`; a parsed request from loopback is kept out of the access
# log by `access_log ... !from_loopback` in squid.conf.tmpl.
printf 'GET http://egress-health/ HTTP/1.0\r\n\r\n' | nc -w 2 127.0.0.1 3130 | grep -q '^HTTP/'
