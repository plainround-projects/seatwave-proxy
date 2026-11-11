# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-11

### Added

- A Compose stack (`compose.yaml`) under the project name `seatwave-proxy`: NGINX 1.30.6 as the reverse
  proxy, a SeatWave backend, a sidecar NGINX in the backend's network namespace, and a `tools`
  service for the slow-client lab; every image pinned by tag and digest, every port on 127.0.0.1 in
  20900 to 20999, nothing privileged.
- The proxy's configuration under `nginx/`: TLS 1.3 termination with HTTP/2, gzip, a static-file
  cache with a lock on `/static/` only, buffering, upstream reuse with a 20 s idle timeout,
  forwarded headers written once in an included file, timeouts from a stated budget, problem
  details for `413`, `502` and `504`, and lab variants each one feature apart.
- A SeatWave backend on the JDK's HTTP server with Module 7's version 1 paths the labs need, Module
  1's seat map, Module 8's long poll and event stream, static files and an operations listener;
  packaged by a two-stage `Dockerfile` (`jdeps` and `jlink`, then a non-root runtime).
- The client-address rule: forwarded headers believed only from trusted addresses, read once
  before any route, with forged and unreadable headers counted.
- The Java bench with each container's CPU and TCP counters, the outage probe, the slow-client
  tool, the access-log reader, and Module 5's `curl -w` script and report.
- `scripts/stack.sh`, `scripts/spof.sh`, `scripts/hop-timings.sh` and `scripts/curl-timings.sh`, each a
  dry run by default, with a confirmation, a fixed target, a check before and after, and an audit
  line.
- Tests for every rule, including the configuration rules read from `nginx/` and `compose.yaml`,
  and a `stackTest` task against the running proxy, kept out of `./gradlew check`.
