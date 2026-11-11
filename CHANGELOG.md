# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-11-11

The review pass: four readers of every line reported 44 items; each was checked, and every real
defect is fixed with a test that fails without the fix.

### Fixed

- Settings: a file path may hold any character but a control character, up to 4,096 of them, so
  the build and its tests pass from a deep checkout or a folder with a space (A1); a broken escape
  in a settings file is reported as a settings problem with exit status 2, not a stack trace (B1);
  a missing stream key gives one problem line, not a second one about stand-in values (B9).
- Forwarded addresses: IPv4 is read only as four decimal parts and an IPv6 node in `Forwarded`
  only in brackets, so `for=2001:db8::1` or `127.1` is unreadable instead of another address;
  NGINX now writes `for="..."` and brackets an IPv6 client with a `map` (B2); when every hop is a
  trusted proxy, the first hop's `proto` is used (B3); trusted proxies refuse the short forms.
- Routes: `Content-Type` must be exactly `application/json`, with or without parameters (B4); a
  problem about the whole body has no leading space (B5); a repeated field is named, and any text
  after the object is "data after the JSON object" (B6); an unknown path is `404` for every
  method, and `HEAD` is answered like `GET` without a body (B10); the hold table keeps at most one
  hold per seat (B12); the correlation id is set before the first log line of a request (B12).
- The seat page's script retries only a lost connection, a `5xx` or a key still in progress; a
  `422` is final and a taken seat returns at once (B7, B8).
- The bench: CPU per thousand requests sent, failed ones included; a warning under a row with more
  than 1 percent failures or runs more than 2 times apart; a container restart stops the bench
  with exit status 1; the raw-socket mode sends `Host` with the port; scenario names fit the
  table's column (D6 to D10).
- The probe: `TLS failed` and `connect or TLS timeout` name what happened; a probe shorter than a
  second prints its length in milliseconds; the outage line tells "every request succeeded" from
  "no success after the first failure" (D4, D5).
- The slow clients: the status line and `Content-Length` are read across reads of any size, and a
  download is complete only when its body is as long as promised; `bodyBytes` is now
  `responseBytes` (D1 to D3).
- Scripts: `curl-timings.sh` reads a last line without a line break and both timing scripts refuse
  a run count with a leading zero (C1, C2); `stack.sh` prints `--quiet-pull` in its plan (C7)
  and counts the stack as up only on a `2xx`, not on the proxy's `502` while SeatWave starts;
  `spof.sh`'s dry run changes nothing and exits 0, and a single error line is printed once (C8).
- The build: the `test` task names `config/`, `compose.yaml` and `Dockerfile` as inputs, so an edit
  to one of them runs the configuration rules again instead of leaving the task up to date.
- Configuration: `proxy_cache_valid` is gone from `/static/`, whose lifetime is SeatWave's own
  `Cache-Control` (C11); the image's user is an ordinary user id with `/nonexistent` as its home
  (C9); the CA's keystore is `rw-------` (E1); the comments on `tmpfs` sizes and file modes say
  what was measured (C10, E1).

### Changed

- Version 1.0.1. Weak tests named by the review were strengthened or replaced (B13, C3 to C6,
  D11, D12), and `JsonCodecTest` and `SeatWaveRoutesTest` are new.
- The README and the module carry the re-measured `proxy-no-reuse` row, the outages, the static
  file and the stack tests from 1.0.1, and two checks on the slow-client numbers (D13).

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
