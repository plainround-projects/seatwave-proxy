# seatwave-proxy

SeatWave behind a reverse proxy: NGINX 1.30.6 in front of a small SeatWave backend, with TLS termination, gzip, a static-file cache, buffering, upstream connection reuse, forwarded headers and timeouts set from a budget, and every one of them measured before and after. The stack runs in Docker Compose; every port is published on 127.0.0.1 only. `./gradlew check` needs no Docker.

## Which module this belongs to

This is the hands-on project of Module 9, "Proxies", of the course System Design Fundamentals. The running case is SeatWave, a ticket-booking service. Module 5 measured the phases of a request with `curl -w`; Module 7 gave SeatWave its version 1 API; Module 8 added long-lived connections. This repository puts one proxy in front of SeatWave and measures what it saves (handshake CPU, bytes, worker threads, repeated static files) and what it costs (one hop, and one more part that can fail).

- `compose.yaml`: SeatWave (`backend`), the reverse proxy (`proxy`), a sidecar NGINX in SeatWave's network namespace (`sidecar`), and a `tools` service for the slow-client lab.
- `nginx/`: the proxy's configuration, one main file and small included ones; PRODUCTION on port 20900 and the lab variants, each one feature apart, on 20901 to 20909 and 20914.
- `src/main/java/io/plainround/seatwave/proxy/`: the backend, the client-address rule, the bench, the outage probe, the slow clients, the access-log reader and Module 5's report.

## Prerequisites

- JDK 25. The build was verified on Eclipse Temurin 25.0.2 on macOS (Apple silicon) on 11 November 2026. The current JDK 25 update on that date was 25.0.5 (`jdk-25.0.5+8`), which the container image uses.
- Docker with Compose, for the stack. Verified with Docker Desktop, engine 24.0.6 and Compose v2.23.0-desktop.1, whose Linux virtual machine had 12 CPUs and 7.7 GiB. On a Mac, containers run in that virtual machine, so every request from the Mac to a published port also crosses Docker Desktop's port forwarder.
- `curl` and `openssl` for the session below (verified with curl 8.7.1 and OpenSSL 4.0.3).
- Nothing else: the Gradle wrapper (9.9.1) downloads Gradle and the libraries on the first build.

## Images and dependencies

Every version is exact and was checked on 11 November 2026: images on Docker Hub (tag and digest, `arm64v8` variant present), libraries on Maven Central, every jar of `gradle.lockfile` against the OSV database (none listed).

| Image | Tag | Digest | Used for |
|---|---|---|---|
| `nginx` | `1.30.6` (stable; Debian trixie; OpenSSL 3.5.7) | `sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703` | The proxy and the sidecar |
| `eclipse-temurin` | `25.0.5_8-jdk-noble` | `sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a` | Stage 1 of the `Dockerfile`: `jdeps` and `jlink` |
| `ubuntu` | `noble` | `sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852` | Stage 2: the runtime, user 10001 |

| Coordinates | Version | Scope | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | main | Apache-2.0 | The JDK has no success-or-typed-error type; settings and log lines are refused as values. |
| `com.fasterxml.jackson:jackson-bom`, `com.fasterxml.jackson.core:jackson-databind` | 2.23.0 | main | Apache-2.0 | The JDK has no JSON parser; the hold's body binds only to a known record. |
| `org.slf4j:slf4j-api` | 2.0.20 | main | MIT | The logging interface, with a correlation id in every line. |
| `ch.qos.logback:logback-classic` | 1.6.6 | runtime | EPL-2.0 or LGPL-2.1 | The logging implementation. |
| `org.junit:junit-bom`, `org.junit.jupiter:junit-jupiter` | 6.1.3 | test | EPL-2.0 | The test engine. |
| `org.assertj:assertj-core` | 3.27.7 | test | Apache-2.0 | Assertions with clear failure messages. |
| `org.awaitility:awaitility` | 4.3.0 | test | Apache-2.0 | Waiting for a condition (a busy worker) instead of sleeping. |
| `com.diffplug.spotless` (Gradle plugin) | 8.11.0 | build | Apache-2.0 | Runs the formatter in the build. |
| `com.google.googlejavaformat:google-java-format` | 2.0.0 | build | Apache-2.0 | One enforced code style. |
| JaCoCo | 0.8.16 | build | EPL-2.0 | Line coverage, with a floor of 0.80. |

OSV listed no known vulnerability for any of the 30 jars in `gradle.lockfile`. Instancio, H2, HikariCP and Testcontainers are not used: the tests need exact values, holds are in memory, and the tests that need the real proxy run against the Compose stack in their own task.

## Build and test (no Docker)

```
./gradlew clean check
```

Formats with Spotless, compiles with `-Xlint:all -Werror`, runs the tests and fails below 80 percent line coverage. The tests start SeatWave on 127.0.0.1 on ports the system picks and read the configuration files of `nginx/` and `compose.yaml`; the tests tagged `stack` are left out. The build was also checked from a copy of the repository under a path of 270 characters and from one under a folder named `folder with space+plus`: both passed. The `test` task names `nginx/`, `config/`, `compose.yaml` and `Dockerfile` as inputs, because the tests read them: after a wrong value in any of them, `./gradlew test` runs the rules again and fails instead of reporting `UP-TO-DATE` (checked by editing a port in `compose.yaml` and `config/backend-container.properties` and the user in `Dockerfile`).

Result on 11 November 2026: 166 tests, 0 failures, line coverage 95.5 percent (2,108 of 2,208 lines). The whole test task also ran 40 times in a row beside a CPU-heavy job, with 0 failures.

```
> Task :jacocoTestReport

BUILD SUCCESSFUL in 14s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
```

## The stack

`scripts/stack.sh` is the only way in and out. It does a dry run unless given `--run`, asks before acting unless also given `--yes`, refuses to do anything when Docker does not answer, writes an audit line to `build/stack/audit.log`, and checks after `down` that no container, network or volume of the project `seatwave-proxy` is left.

```
scripts/stack.sh up --run --yes      # jars, TEST certificates, image, then backend, sidecar, proxy
scripts/stack.sh test --run --yes    # up, ./gradlew stackTest, then down, on every path
scripts/stack.sh down --run --yes    # docker compose -p seatwave-proxy down -v
```

| Port on 127.0.0.1 | What answers |
|---|---|
| 20900 | PRODUCTION: TLS 1.3, HTTP/2, gzip level 1, cache, buffering, reuse, budget timeouts |
| 20901 | TLS termination alone (HTTP/1.1, no gzip, no cache) |
| 20902, 20903 | Plain HTTP through the proxy, buffering on and off |
| 20904 | TLS, no upstream reuse (`keepalive 0`) |
| 20905 | TLS, the long poll under the API's 2.5 s read timeout (a wrong timeout) |
| 20906 | TLS, re-encrypted to the sidecar beside SeatWave |
| 20907, 20908, 20909 | TLS with gzip levels 1, 6 and 9 |
| 20914 | A cache lock on every path (a cache mistake) |
| 20920 | NGINX `stub_status` |
| 20910, 20911, 20912 | SeatWave itself: HTTPS (SIMPLE), plain HTTP, operations (`/metrics`, `/health`) |

The TEST certificate authority and server certificate are generated by `./gradlew testCertificates` into `build/tls` (ignored by git, kept out of the image by `.dockerignore`, mounted read-only). Use `--cacert build/tls/seatwave-test-ca.pem` with curl. The CA's keystore, which holds the CA's private key, is `rw-------`; the files the containers read are `rw-r--r--`. On a Linux host a bind mount keeps the host's owner and mode, and the containers read these files as other users (NGINX's master as root without `CAP_DAC_OVERRIDE`, SeatWave as uid 10001), so owner-only files would be refused there [not run: no Linux host was used]. On Docker Desktop for Mac every shared file shows as owned by the user who reads it, and the whole stack started and answered with every file `rw-------` (checked on 11 November 2026).

The proxy's `tmpfs` folders (300 MB for the cache, 600 MB for the access log) count against its 1 GiB memory limit only for the pages actually written. The cache is capped at 100 MB by `max_size`. The log grows by about 192 bytes a request (33,946 lines after the `proxy-no-reuse` row below), so the first session's 1,584,174 lines came to about 290 MiB; NGINX itself used 6.2 MiB of anonymous memory, and in the first session no container was killed for memory (`oom_kill 0` in every container).

## Every command and what it printed

A real session on 11 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other programs running. Local paths are shortened to `.` and `~`. Your numbers will differ.

Two sessions are shown. The first ran version 1.0.0. After review, version 1.0.1 was measured again wherever its fixes change what is printed: the image build and start, the NGINX checks, the forged header, the static file, the bench's `proxy-no-reuse` row, the three outages, the stack tests and the clean-up. The other outputs are the first session's; the fixes do not change those paths.

```
$ sw_vers | sed -n 2p; sysctl -n machdep.cpu.brand_string hw.ncpu; echo $(( $(sysctl -n hw.memsize) / 1073741824 )) GiB
ProductVersion:		27.2
Apple M2 Max
12
32 GiB

$ docker version --format 'engine {{.Server.Version}}, client {{.Client.Version}}'
engine 24.0.6, client 24.0.6

$ docker compose version
Docker Compose version v2.23.0-desktop.1

$ docker info --format '{{.OperatingSystem}}, {{.Architecture}}, {{.NCPU}} CPUs, {{.MemTotal}} bytes, kernel {{.KernelVersion}}'
Docker Desktop, aarch64, 12 CPUs, 8225304576 bytes, kernel 6.4.16-linuxkit

$ java -version 2>&1
openjdk version "25.0.2" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.2+10 (build 25.0.2+10-LTS, mixed mode, sharing)

$ curl --version | head -1
curl 8.7.1 (x86_64-apple-darwin26.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.69.0

$ openssl version
OpenSSL 4.0.3 29 Sep 2026 (Library: OpenSSL 4.0.3 29 Sep 2026)
```

```
$ ./gradlew -q stackPrepare && docker compose -p seatwave-proxy build --no-cache --progress plain backend 2>&1 | grep -E 'linking modules|naming to' | sed 's/^#[0-9]* [0-9.]* //'
#10 [backend runtime 3/3] RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25         --class-path '/app/lib/*' /app/lib/seatwave-proxy-1.0.1.jar)"     && echo "linking modules: ${modules}"     && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files         --output /opt/seatwave-java
linking modules: java.base,java.desktop,java.net.http,java.sql,jdk.httpserver
#14 naming to docker.io/library/seatwave-proxy-backend:1.0.1 done

$ scripts/stack.sh up --run --yes 2>&1 | tail -1
stack is up: https://127.0.0.1:20900/health answered after 1 s

$ docker compose -p seatwave-proxy ps --format '{{.Service}}  {{.Image}}  {{.Status}}'
backend  seatwave-proxy-backend:1.0.1  Up 1 second
proxy  nginx:1.30.6@sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703  Up 1 second
sidecar  nginx:1.30.6@sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703  Up 1 second

$ docker image ls seatwave-proxy-backend:1.0.1 --format '{{.Repository}}:{{.Tag}}  {{.Size}}'
seatwave-proxy-backend:1.0.1  198MB
```

```
$ docker exec seatwave-proxy-proxy-1 nginx -v 2>&1
nginx version: nginx/1.30.6

$ docker exec seatwave-proxy-proxy-1 nginx -t 2>&1
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful

$ docker exec seatwave-proxy-proxy-1 sh -c 'cp -r /etc/nginx /var/cache/nginx/copy && sed -i "s/proxy_read_timeout 2500ms;/proxy_read_timeout 2500ms/" /var/cache/nginx/copy/seatwave/timeouts.conf && nginx -t -c /var/cache/nginx/copy/nginx.conf' 2>&1
2026/11/11 07:33:52 [emerg] 30#30: unexpected end of file, expecting ";" or "}" in /var/cache/nginx/copy/seatwave/timeouts.conf:11
nginx: [emerg] unexpected end of file, expecting ";" or "}" in /var/cache/nginx/copy/seatwave/timeouts.conf:11
nginx: configuration file /var/cache/nginx/copy/nginx.conf test failed
```

```
$ curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem 'https://127.0.0.1:20910/events/1/seats?section=A' | grep -v -i '^date'
HTTP/1.1 200 OK
Sunset: Tue, 11 May 2027 00:00:00 GMT
Content-type: application/json
Deprecation: @1794355200
Server-timing: app;dur=6.88
Content-length: 36894
Link: </v1/events/1/seats?section=A>; rel="successor-version"
Cache-control: no-cache


$ curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem -H 'Accept-Encoding: gzip' 'https://127.0.0.1:20900/events/1/seats?section=A' | grep -v -i '^date'
HTTP/2 200 
server: nginx
content-type: application/json
vary: Accept-Encoding
sunset: Tue, 11 May 2027 00:00:00 GMT
deprecation: @1794355200
server-timing: app;dur=0.83
link: </v1/events/1/seats?section=A>; rel="successor-version"
cache-control: no-cache
x-cache-status: MISS
content-encoding: gzip
```

```
$ curl -s -H 'X-Forwarded-For: 203.0.113.7' http://127.0.0.1:20911/lab/client; echo
{"address":"10.209.9.1","scheme":"http","source":"peer"}

$ curl -s --cacert build/tls/seatwave-test-ca.pem -H 'X-Forwarded-For: 203.0.113.7' https://127.0.0.1:20900/lab/client; echo
{"address":"10.209.9.1","scheme":"https","source":"forwarded"}

$ curl -s --cacert build/tls/seatwave-test-ca.pem -H 'X-Forwarded-For: 203.0.113.7' https://127.0.0.1:20906/lab/client; echo
{"address":"10.209.9.1","scheme":"https","source":"forwarded"}

$ curl -s http://127.0.0.1:20912/metrics | grep forwarding
# TYPE seatwave_forwarding_headers_ignored_total counter
seatwave_forwarding_headers_ignored_total 1
# TYPE seatwave_forwarding_headers_unreadable_total counter
seatwave_forwarding_headers_unreadable_total 0
```

The lab network has no IPv6, so the `map` that writes an IPv6 client in brackets (`nginx/nginx.conf`) was run in a throwaway NGINX inside the proxy's container, with a request header in place of `$remote_addr`:

```
$ cat build/stack/maptest.conf
# The map of nginx/nginx.conf, read from a request header in place of $remote_addr, because the
# lab network has no IPv6: a throwaway NGINX on the proxy's loopback address answers with the node.
worker_processes 1;
pid /var/cache/nginx/maptest/nginx.pid;
error_log /var/cache/nginx/maptest/error.log notice;
events { worker_connections 16; }
http {
    access_log off;
    client_body_temp_path /var/cache/nginx/maptest/body;
    proxy_temp_path /var/cache/nginx/maptest/proxy;
    fastcgi_temp_path /var/cache/nginx/maptest/fastcgi;
    uwsgi_temp_path /var/cache/nginx/maptest/uwsgi;
    scgi_temp_path /var/cache/nginx/maptest/scgi;
    map $http_x_address $forwarded_node {
        ~:      "[$http_x_address]";
        default $http_x_address;
    }
    server {
        listen 127.0.0.1:8099;
        location / { return 200 "Forwarded: for=\"$forwarded_node\";proto=$scheme\n"; }
    }
}

$ docker exec -i seatwave-proxy-proxy-1 sh -c 'mkdir -p /var/cache/nginx/maptest && cat > /var/cache/nginx/maptest/nginx.conf' < build/stack/maptest.conf

$ docker exec seatwave-proxy-proxy-1 sh -c 'nginx -t -c /var/cache/nginx/maptest/nginx.conf 2>&1 | tail -1 && nginx -c /var/cache/nginx/maptest/nginx.conf && sleep 1 && curl -s -H "X-Address: 2001:db8::17" http://127.0.0.1:8099/ && curl -s -H "X-Address: 203.0.113.7" http://127.0.0.1:8099/; nginx -c /var/cache/nginx/maptest/nginx.conf -s quit; sleep 1; rm -rf /var/cache/nginx/maptest'
nginx: configuration file /var/cache/nginx/maptest/nginx.conf test is successful
Forwarded: for="[2001:db8::17]";proto=http
Forwarded: for="203.0.113.7";proto=http
2026/11/11 08:03:50 [notice] 31#31: signal process started
```

```
$ curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20900/static/seat-map.js | grep -i -E '^HTTP|x-cache|cache-control|content-length|content-encoding'
HTTP/2 200 
content-length: 4392
cache-control: public, max-age=300
x-cache-status: MISS

$ curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20900/static/seat-map.js | grep -i -E '^HTTP|x-cache|cache-control|content-length|content-encoding'
HTTP/2 200 
content-length: 4392
cache-control: public, max-age=300
x-cache-status: HIT

$ curl -s -o /dev/null -w '%{size_download} bytes, %{content_type}\n' --cacert build/tls/seatwave-test-ca.pem -H 'Accept-Encoding: gzip' https://127.0.0.1:20900/static/seat-map.js
1991 bytes, text/javascript

$ curl -s http://127.0.0.1:20912/metrics | grep 'static'
seatwave_requests_total{route="GET /static/{file}",status="200"} 1
```

Module 5's method, before and after (3 warm-up runs thrown away, then 7; median run by total):

```
target            connection protocol  runs   dns ms connect ms   tls ms first byte ms transfer ms   total ms   fastest  slowest  dominant
direct-https      new        HTTP/1.1     7     0.05       0.22     8.47          1.39        0.84      10.97     10.09    12.24  tls
direct-https      reused     HTTP/1.1     7     0.01       0.00     0.00          1.10        0.77       1.87      1.76     2.77  first byte
direct-http       new        HTTP/1.1     7     0.06       0.25     0.00          1.20        0.26       1.76      1.35     1.96  first byte
direct-http       reused     HTTP/1.1     7     0.01       0.00     0.00          0.84        0.15       1.00      0.85     1.51  first byte
proxy-http        new        HTTP/1.1     7     0.05       0.19     0.00          1.29        0.04       1.57      1.39     2.06  first byte
proxy-http        reused     HTTP/1.1     7     0.01       0.00     0.00          0.91        0.03       0.95      0.86     1.13  first byte
proxy-tls         new        HTTP/1.1     7     0.05       0.18     1.41          1.13        0.12       2.89      2.66     3.18  tls
proxy-tls         reused     HTTP/1.1     7     0.01       0.00     0.00          0.87        0.11       0.98      0.89     1.12  first byte
proxy-no-reuse    new        HTTP/1.1     7     0.09       0.19     1.51          1.28        0.12       3.18      2.87     3.71  tls
proxy-no-reuse    reused     HTTP/1.1     7     0.01       0.00     0.00          1.04        0.11       1.16      1.02     1.35  first byte
proxy-sidecar     new        HTTP/1.1     7     0.07       0.20     1.47          1.37        0.12       3.22      2.88     7.10  tls
proxy-sidecar     reused     HTTP/1.1     7     0.01       0.00     0.00          1.22        0.14       1.37      1.15     1.69  first byte
production        new        HTTP/2       7     0.05       0.21     1.43          0.95        0.04       2.68      2.58     3.02  tls
production        reused     HTTP/2       7     0.01       0.00     0.00          0.95        0.03       0.99      0.76     1.54  first byte
```

The hop from inside the proxy's container, without Docker Desktop's port forwarder:

```
target            connection protocol  runs   dns ms connect ms   tls ms first byte ms transfer ms   total ms   fastest  slowest  dominant
hop-direct        new        HTTP/1.1    15     0.01       0.15     0.00          0.88        0.04       1.08      0.99     1.90  first byte
hop-direct        reused     HTTP/1.1    15     0.00       0.00     0.00          0.55        0.02       0.57      0.46     1.23  first byte
hop-proxy         new        HTTP/1.1    15     0.01       0.08     0.00          0.97        0.03       1.08      0.94     1.28  first byte
hop-proxy         reused     HTTP/1.1    15     0.00       0.00     0.00          0.59        0.02       0.60      0.55     1.43  first byte
```

The bench (8 clients over HTTP/1.1, a 5 s warm-up, then 3 runs of 10 s, the median run by requests per second):

```
$ ./gradlew -q bench --args='config/bench.properties'
Java 25.0.2+10-LTS on Mac OS X aarch64; 8 clients over HTTP/1.1; warm-up 5 s, then 3 runs of 10 s each; each row is the median run by rps

scenario              rps   p50 ms   p99 ms  errors  wire B/resp  plain B/resp  hit %  proxy CPU ms/1k  SeatWave CPU ms/1k  proxy accepted  proxy opened  SeatWave accepted
direct-http           4555     1.51     5.30       0        36894         36894      -              0.0               254.7               0             0                  8
  runs (rps): 4555 (45550 in 10.0 s), 4491 (44915 in 10.0 s), 4614 (46143 in 10.0 s)
proxy-http            4351     1.58     5.70       0        36894         36894      -             72.6               254.2              48            47                 47
  runs (rps): 4351 (43512 in 10.0 s), 4313 (43131 in 10.0 s), 4419 (44194 in 10.0 s)
direct-tls            4454     1.56     5.19       0        36894         36894      -              0.0               295.2               0             0                  8
  runs (rps): 4353 (43537 in 10.0 s), 4461 (44616 in 10.0 s), 4454 (44558 in 10.0 s)
proxy-tls             4333     1.57     5.86       0        36894         36894      -             98.9               248.0              48            42                 42
  runs (rps): 4279 (42793 in 10.0 s), 4333 (43340 in 10.0 s), 4410 (44117 in 10.0 s)
proxy-no-reuse         299     9.08    12.80    3330        36894         36894      -           3328.0               308.2               8          2991               2991
  runs (rps): 957 (9572 in 10.0 s), 299 (2991 in 10.0 s), 4 (39 in 10.0 s)
proxy-sidecar         3908     1.79     6.32       0        36894         36894      -            113.8               238.0              40            39                 77
  runs (rps): 3788 (37886 in 10.0 s), 3908 (39091 in 10.0 s), 3930 (39306 in 10.0 s)
gzip-1                6670     1.18     2.08       0         2453         36894      -             99.5               216.3              72            64                 64
  runs (rps): 6670 (66708 in 10.0 s), 6609 (66094 in 10.0 s), 6692 (66927 in 10.0 s)
gzip-6                4914     1.57     2.88       0         2419         36894      -            187.2               219.8              56            49                 49
  runs (rps): 4966 (49663 in 10.0 s), 4891 (48921 in 10.0 s), 4914 (49146 in 10.0 s)
gzip-9                1656     4.83     6.17       0         2351         36894      -            601.7               188.6              24            17                 17
  runs (rps): 1649 (16499 in 10.0 s), 1656 (16567 in 10.0 s), 1663 (16639 in 10.0 s)
production            6549     1.19     2.37       0         2453         36894    0.0            101.8               218.1              72            66                 66
  runs (rps): 6549 (65498 in 10.0 s), 6669 (66701 in 10.0 s), 6547 (65474 in 10.0 s)
cache-lock            1162     0.72     2.34       0        36894         36894    0.0             92.7               184.3              16            13                 13
  runs (rps): 1162 (12823 in 11.0 s), 1215 (12782 in 10.5 s), 1109 (12780 in 11.5 s)
page-production       7070     1.06     2.26       0         1366         25430    0.0             88.2               278.1              72            70                 70
  runs (rps): 6960 (69609 in 10.0 s), 7070 (70704 in 10.0 s), 7441 (74417 in 10.0 s)
poster-direct          763     9.77    17.90       0       300000        300000      -              0.2               580.2               0             0                  8
  runs (rps): 752 (7529 in 10.0 s), 776 (7768 in 10.0 s), 763 (7635 in 10.0 s)
poster-cached          798     9.43    15.66       0       300000        300000  100.0            355.5                 0.4              11             0                  0
  runs (rps): 798 (7988 in 10.0 s), 771 (7712 in 10.0 s), 806 (8058 in 10.0 s)
handshake-direct       343    23.35    30.53       0           15            15      -              0.6               936.5               0             0               3431
  runs (rps): 361 (3608 in 10.0 s), 343 (3431 in 10.0 s), 342 (3421 in 10.0 s)
handshake-proxy       2606     2.92     6.48       0           15            15      -            313.7               114.3           26070            24                 24
  runs (rps): 2639 (26391 in 10.0 s), 2606 (26070 in 10.0 s), 2549 (25496 in 10.0 s)
```

That table is the first session's. Its `proxy-no-reuse` row is wrong in one column and misleading as a whole: version 1.0.0 divided the containers' CPU by the successful requests only, although the CPU was also spent on the failed ones, and it printed the median of three very different runs without a warning. The fixed bench divides by every request sent and warns under such a row. The row, measured again with 1.0.1 (Java's `Properties` keeps the last value of a repeated key, so the appended line picks the one scenario):

```
$ (cat config/bench.properties; echo 'scenario.names=proxy-no-reuse') > build/stack/bench-no-reuse.properties

$ ./gradlew -q bench --args='build/stack/bench-no-reuse.properties'
Java 25.0.2+10-LTS on Mac OS X aarch64; 8 clients over HTTP/1.1; warm-up 5 s, then 3 runs of 10 s each; each row is the median run by rps

scenario              rps   p50 ms   p99 ms  errors  wire B/resp  plain B/resp  hit %  proxy CPU ms/1k  SeatWave CPU ms/1k  proxy accepted  proxy opened  SeatWave accepted
proxy-no-reuse         695     8.97    13.42     966        36894         36894      -           1221.7               281.3               8          6965               6965
  runs (rps): 849 (8492 in 10.0 s), 695 (6965 in 10.0 s), 0 (0 in 10.0 s)
  warning: 12 percent of the requests of this run failed; the other columns describe a failing path
  warning: the runs differ by more than 2 times (0 to 849 rps), so the median is not a steady rate
```

```
$ docker logs seatwave-proxy-proxy-1 2>&1 | grep -c 'Cannot assign requested address'
5710

$ docker logs seatwave-proxy-proxy-1 2>&1 | grep 'Cannot assign requested address' | head -1 | sed 's/^[0-9/]* [0-9:]* //'
[crit] 7#7: *21313 connect() to 10.209.9.20:8080 failed (99: Cannot assign requested address) while connecting to upstream, client: 10.209.9.1, server: localhost, request: "GET /events/1/seats?section=A HTTP/1.1", upstream: "http://10.209.9.20:8080/events/1/seats?section=A", host: "127.0.0.1:20904"

$ docker exec seatwave-proxy-proxy-1 cat /proc/sys/net/ipv4/ip_local_port_range
32768	60999
```

The proxy ran out of ephemeral ports again: every closed upstream connection keeps its port 60 s in TIME_WAIT, and the third run got no request through. The no-reuse row measures that failure, not a steady cost per request.

```
$ docker exec seatwave-proxy-proxy-1 sh -c 'wc -l < /var/log/nginx/seatwave/access.log; du -sk /var/log/nginx/seatwave /var/cache/nginx 2>/dev/null; grep -E "^(anon|file|shmem) " /sys/fs/cgroup/memory.stat; cat /sys/fs/cgroup/memory.peak'
33946
6368	/var/log/nginx/seatwave
96	/var/cache/nginx
anon 6492160
file 8265728
shmem 6782976
22839296
```

```
$ docker exec seatwave-proxy-proxy-1 cat /var/log/nginx/seatwave/access.log > build/stack/access.log; wc -l < build/stack/access.log | tr -d ' '
1584174

$ ./gradlew -q logStats --args='build/stack/access.log'
port   requests  to SeatWave  SeatWave 5xx %  cache lookups  hit %  static hit %  proxy-added p50 ms  proxy-added p99 ms
8080     152549       152549            0.00              0      -             -                0.00                1.00
8443     507455       480019            0.00         507455    5.4         100.0                0.00                1.00
8444     243222       243222            0.00              0      -             -                0.00                1.00
8445      36372        36372           22.65              0      -             -                0.00                1.00
8447     135921       135921            0.00              0      -             -                0.00                1.00
8451     232995       232995            0.00              0      -             -                0.00                1.00
8456     172773       172773            0.00              0      -             -                0.00                1.00
8459      58012        58012            0.00              0      -             -                0.00                1.00
8460      44875        44875            0.00          44875    0.0             -                0.00                1.00
```

Lab 3, inside the lab network (`docker compose -p seatwave-proxy run --rm tools /app/config/slow-<variant>.properties`):

```
target: GET http://10.209.9.20:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37187 bytes each with headers; download p50 1.37 s, max 2.21 s
SeatWave workers: at most 64 of 64 busy at once (0 readings missed); 108.35 worker-seconds in all, 0.2167 s per slow client
fast probe straight to SeatWave during the run: 8 answered, 0 failed (30 s timeout); answered p50 2.96 ms, p99 1366.19 ms, max 1366.19 ms
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37221 bytes each with headers; download p50 0.77 s, max 0.83 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 0.10 worker-seconds in all, 0.0002 s per slow client
fast probe straight to SeatWave during the run: 8 answered, 0 failed (30 s timeout); answered p50 1.73 ms, p99 100.34 ms, max 100.34 ms
target: GET http://10.209.9.20:8080/static/poster-arena.bin; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 300186 bytes each with headers; download p50 23.13 s, max 45.55 s
SeatWave workers: at most 64 of 64 busy at once (0 readings missed); 2799.22 worker-seconds in all, 5.5984 s per slow client
fast probe straight to SeatWave during the run: 60 answered, 1 failed (30 s timeout); answered p50 3.03 ms, p99 9034.96 ms, max 9034.96 ms
target: GET http://10.209.9.10:8081/static/poster-arena.bin; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 300219 bytes each with headers; download p50 6.42 s, max 6.62 s
SeatWave workers: at most 64 of 64 busy at once (0 readings missed); 42.67 worker-seconds in all, 0.0853 s per slow client
fast probe straight to SeatWave during the run: 60 answered, 0 failed (30 s timeout); answered p50 2.31 ms, p99 278.62 ms, max 278.62 ms
target: GET http://10.209.9.10:8080/static/poster-arena.bin; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 300220 bytes each with headers; download p50 6.31 s, max 6.40 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 2.01 worker-seconds in all, 0.0040 s per slow client
fast probe straight to SeatWave during the run: 59 answered, 0 failed (30 s timeout); answered p50 1.86 ms, p99 95.85 ms, max 95.85 ms
```

These runs came after the bench, so SeatWave's JVM was warm. Two checks after review show what the numbers depend on.

The first buffered run on a freshly started stack is different. Three buffered runs in a row on a new stack, then two on a new stack whose SeatWave had first answered 2,000 seat maps on its own port:

```
$ scripts/stack.sh down --run --yes 2>&1 | tail -1; scripts/stack.sh up --run --yes 2>&1 | tail -1
stack is down: no container, network or volume of seatwave-proxy is left
stack is up: https://127.0.0.1:20900/health answered after 1 s

$ docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered.properties 2>&1 | grep -v -E 'Creating|Created|Container'
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37223 bytes each with headers; download p50 0.95 s, max 1.15 s
SeatWave workers: at most 47 of 64 busy at once (0 readings missed); 7.81 worker-seconds in all, 0.0156 s per slow client
fast probe straight to SeatWave during the run: 10 answered, 0 failed (30 s timeout); answered p50 1.91 ms, p99 150.10 ms, max 150.10 ms

$ docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered.properties 2>&1 | grep -v -E 'Creating|Created|Container'
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37222 bytes each with headers; download p50 0.78 s, max 0.83 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 0.79 worker-seconds in all, 0.0016 s per slow client
fast probe straight to SeatWave during the run: 8 answered, 0 failed (30 s timeout); answered p50 1.76 ms, p99 67.19 ms, max 67.19 ms

$ docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered.properties 2>&1 | grep -v -E 'Creating|Created|Container'
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37222 bytes each with headers; download p50 0.77 s, max 0.84 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 0.67 worker-seconds in all, 0.0013 s per slow client
fast probe straight to SeatWave during the run: 8 answered, 0 failed (30 s timeout); answered p50 2.33 ms, p99 101.38 ms, max 101.38 ms
$ scripts/stack.sh down --run --yes 2>&1 | tail -1; scripts/stack.sh up --run --yes 2>&1 | tail -1
stack is down: no container, network or volume of seatwave-proxy is left
stack is up: https://127.0.0.1:20900/health answered after 1 s

$ for request in $(seq 2000); do printf 'url = "http://127.0.0.1:20911/events/1/seats?section=A"\noutput = /dev/null\n'; done | curl -s -K - && echo 'answered 2000 seat maps'
answered 2000 seat maps

$ docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered.properties 2>&1 | grep -v -E 'Creating|Created|Container'
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37222 bytes each with headers; download p50 0.77 s, max 0.82 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 0.64 worker-seconds in all, 0.0013 s per slow client
fast probe straight to SeatWave during the run: 8 answered, 0 failed (30 s timeout); answered p50 1.60 ms, p99 78.82 ms, max 78.82 ms

$ docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered.properties 2>&1 | grep -v -E 'Creating|Created|Container'
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 500 complete, 0 failed, 37222 bytes each with headers; download p50 0.77 s, max 0.82 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 0.80 worker-seconds in all, 0.0016 s per slow client
fast probe straight to SeatWave during the run: 8 answered, 0 failed (30 s timeout); answered p50 2.45 ms, p99 51.25 ms, max 51.25 ms
```

On a cold JVM the first burst of 500 requests through the proxy kept up to 47 workers busy for 7.81 worker-seconds; once SeatWave's JVM had answered requests on its own port, the first run used 0.64 worker-seconds. So the first-run effect is the cold JVM, not the proxy's cold pool of upstream connections. "At most N busy" is one reading every 100 ms of a burst that lasts milliseconds, so it is a rough figure; the worker-seconds are the steadier number.

The 64-of-64 result belongs to the Docker virtual machine's Linux kernel. The same direct poster run from the Mac to a SeatWave on the Mac (`./gradlew -q serve` in a second terminal, no Docker). The settings file only allows the lab network's addresses, so a small class, compiled with `javac -d build/mac-check -cp 'build/stack/lib/*' MacLoopback.java` after `./gradlew -q stackPrepare`, builds the settings for 127.0.0.1 in code:

```java
package io.plainround.seatwave.proxy.slow;

import java.net.URI;
import java.time.Duration;

/** Lab 3's direct poster run, but from this Mac to a SeatWave started with ./gradlew serve. */
public final class MacLoopback {
  public static void main(String[] arguments) throws Exception {
    int clients = Integer.parseInt(arguments[1]);
    SlowSettings settings =
        new SlowSettings(
            URI.create("http://127.0.0.1:20931" + arguments[0]),
            clients,
            5_000,
            Duration.ofMillis(100),
            4_096,
            URI.create("http://127.0.0.1:20931/health"),
            URI.create("http://127.0.0.1:20932/metrics"),
            Duration.ofMillis(100),
            Duration.ofSeconds(120));
    System.exit(SlowClientsCli.runAndPrint(settings, System.out, System.err));
  }
}
```

```
$ java -cp 'build/mac-check:build/stack/lib/*' io.plainround.seatwave.proxy.slow.MacLoopback /static/poster-arena.bin 100
target: GET http://127.0.0.1:20931/static/poster-arena.bin; 100 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 100 complete, 0 failed, 300185 bytes each with headers; download p50 6.42 s, max 6.42 s
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 0.29 worker-seconds in all, 0.0029 s per slow client
fast probe straight to SeatWave during the run: 61 answered, 0 failed (30 s timeout); answered p50 2.91 ms, p99 10.44 ms, max 10.44 ms

$ java -cp 'build/mac-check:build/stack/lib/*' io.plainround.seatwave.proxy.slow.MacLoopback /static/poster-arena.bin 500
target: GET http://127.0.0.1:20931/static/poster-arena.bin; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
slow clients: 420 complete, 80 failed, 300186 bytes each with headers; download p50 6.47 s, max 6.47 s
SeatWave workers: at most 64 of 64 busy at once (0 readings missed); 6.15 worker-seconds in all, 0.0146 s per slow client
fast probe straight to SeatWave during the run: 62 answered, 0 failed (30 s timeout); answered p50 2.73 ms, p99 20.89 ms, max 20.89 ms
$ docker exec seatwave-proxy-backend-1 cat /proc/sys/net/ipv4/tcp_wmem
4096	16384	4194304

$ sysctl net.inet.tcp.sendspace net.inet.tcp.autosndbufmax
net.inet.tcp.sendspace: 131072
net.inet.tcp.autosndbufmax: 4194304
```

On the Mac's loopback, 100 slow clients kept at most 1 worker busy, 0.0029 s per client, against 5.5984 s in the Linux virtual machine. A worker's write of a 300 KB answer that its client reads in 6 s ended within milliseconds, so the Mac's kernel took the whole answer into its buffers and the worker was free while the client was still reading. With 500 clients, the burst of new connections filled all 64 workers for a moment and 80 clients failed; those failures were not investigated. Both kernels allow a send buffer of up to 4 MB, as printed above; which of their rules makes the difference was not found. The module's numbers are the Linux kernel's, the kernel a production SeatWave runs on.

```
$ (curl -s --cacert build/tls/seatwave-test-ca.pem -w '\n%{http_code} after %{time_total} s (port 20905, read timeout 2.5 s)\n' 'https://127.0.0.1:20905/v1/events/1/seat-changes?after=1:42' & curl -s --cacert build/tls/seatwave-test-ca.pem -w '\n%{http_code} after %{time_total} s (port 20900, read timeout 30 s)\n' 'https://127.0.0.1:20900/v1/events/1/seat-changes?after=1:42' & wait)
{"type":"https://seatwave.example/problems/upstream-timeout","title":"SeatWave did not answer in time","status":504}
504 after 2.517107 s (port 20905, read timeout 2.5 s)
{"items":[],"position":"1:42"}
200 after 25.014433 s (port 20900, read timeout 30 s)

$ docker logs seatwave-proxy-proxy-1 2>&1 | grep 'timed out' | grep seat-changes | tail -1 | sed 's/^[0-9/]* [0-9:]* //'
[error] 7#7: *134310 upstream timed out (110: Connection timed out) while reading response header from upstream, client: 10.209.9.1, server: localhost, request: "GET /v1/events/1/seat-changes?after=1:42 HTTP/1.1", upstream: "http://10.209.9.20:8080/v1/events/1/seat-changes?after=1:42", host: "127.0.0.1:20905"

$ curl -s http://127.0.0.1:20912/metrics | grep 'seat-changes"'
seatwave_requests_total{route="GET /v1/events/{eventId}/seat-changes",status="200"} 2
```

```
$ scripts/spof.sh proxy --run --yes
plan: probe https://127.0.0.1:20900/health for 30 s; stop proxy at 5 s, start it at 15 s
stopped proxy at 07:37:17 UTC for 10 s
probe: GET https://127.0.0.1:20900/health every 100 ms for 30 s, one client, HTTP/1.1 keep-alive
  0.000 s to   4.502 s  200                    x   46  latency p50     7.36 ms, max   150.33 ms
  4.609 s to   4.609 s  TLS failed             x    1  latency p50     7.32 ms, max     7.32 ms
  4.704 s to  14.910 s  connection refused     x  103  latency p50     2.11 ms, max     8.98 ms
 15.010 s to  29.910 s  200                    x  150  latency p50     8.56 ms, max    14.68 ms
outage: 10.400 s from the first failed request to the first success after the last
```

```
$ scripts/spof.sh backend --run --yes
plan: probe https://127.0.0.1:20900/health for 30 s; stop backend at 5 s, start it at 15 s
stopped backend at 07:37:47 UTC for 10 s
probe: GET https://127.0.0.1:20900/health every 100 ms for 30 s, one client, HTTP/1.1 keep-alive
  0.000 s to   4.600 s  200                    x   47  latency p50     9.20 ms, max   134.09 ms
  4.705 s to  14.729 s  request timeout        x   11  latency p50  1002.20 ms, max  1004.39 ms
 15.730 s to  29.910 s  200                    x  242  latency p50     3.47 ms, max    29.96 ms
outage: 11.025 s from the first failed request to the first success after the last
the proxy logged 10 error lines for /health while SeatWave was down; the first and the last:
[error] 7#7: *3 upstream timed out (110: Connection timed out) while connecting to upstream, client: 10.209.9.1, server: localhost, request: "GET /health HTTP/1.1", upstream: "http://10.209.9.20:8080/health", host: "127.0.0.1:20900"
[error] 7#7: *21 upstream timed out (110: Connection timed out) while connecting to upstream, client: 10.209.9.1, server: localhost, request: "GET /health HTTP/1.1", upstream: "http://10.209.9.20:8080/health", host: "127.0.0.1:20900"
```

```
$ scripts/spof.sh reload --run --yes
plan: probe https://127.0.0.1:20900/health for 30 s; nginx -t and nginx -s reload at 5 s and again 5 s later
  nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
  nginx: configuration file /etc/nginx/nginx.conf test is successful
2026/11/11 07:38:19 [notice] 16#16: signal process started
  nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
  nginx: configuration file /etc/nginx/nginx.conf test is successful
2026/11/11 07:38:24 [notice] 31#31: signal process started
probe: GET https://127.0.0.1:20900/health every 100 ms for 30 s, one client, HTTP/1.1 keep-alive
  0.000 s to  29.909 s  200                    x  300  latency p50     6.71 ms, max   130.55 ms
outage: none, every request succeeded
```

```
$ ./gradlew stackTest 2>&1 | grep -E 'PASSED|FAILED|BUILD'
StackInvariantTest > answersAStaticFileFromTheCacheOnceItHoldsIt() PASSED
StackInvariantTest > replacesAForgedHeaderWithTheAddressTheProxySaw() PASSED
StackInvariantTest > neverAnswersAPrivateResponseFromTheCache() PASSED
StackInvariantTest > neverAnswersAPostFromTheCache() PASSED
StackInvariantTest > refusesABodyOverItsLimitWithProblemDetailsAtTheProxyOrAtSeatWave() PASSED
StackInvariantTest > ignoresAForgedHeaderSentStraightToSeatWave() PASSED
BUILD SUCCESSFUL in 1s
```

```
$ scripts/stack.sh down --run --yes 2>&1 | tail -1
stack is down: no container, network or volume of seatwave-proxy is left

$ docker ps -a --filter label=com.docker.compose.project=seatwave-proxy --format '{{.Names}}' | wc -l | tr -d ' '
0

$ docker network ls --filter label=com.docker.compose.project=seatwave-proxy --format '{{.Name}}' | wc -l | tr -d ' '
0

$ docker volume ls --filter label=com.docker.compose.project=seatwave-proxy --format '{{.Name}}' | wc -l | tr -d ' '
0
```

The same tests through `scripts/stack.sh test`, which starts the stack, runs them and brings the stack down on every path:

```
$ scripts/stack.sh test --run --yes 2>&1 | grep -E 'PASSED|FAILED|BUILD|stack is'
stack is up: https://127.0.0.1:20900/health answered after 1 s
StackInvariantTest > answersAStaticFileFromTheCacheOnceItHoldsIt() PASSED
StackInvariantTest > replacesAForgedHeaderWithTheAddressTheProxySaw() PASSED
StackInvariantTest > neverAnswersAPrivateResponseFromTheCache() PASSED
StackInvariantTest > neverAnswersAPostFromTheCache() PASSED
StackInvariantTest > refusesABodyOverItsLimitWithProblemDetailsAtTheProxyOrAtSeatWave() PASSED
StackInvariantTest > ignoresAForgedHeaderSentStraightToSeatWave() PASSED
BUILD SUCCESSFUL in 1s
stack is down: no container, network or volume of seatwave-proxy is left

$ docker ps -a --filter label=com.docker.compose.project=seatwave-proxy --format '{{.Names}}' | wc -l | tr -d ' '
0

$ docker network ls --filter label=com.docker.compose.project=seatwave-proxy --format '{{.Name}}' | wc -l | tr -d ' '
0

$ docker volume ls --filter label=com.docker.compose.project=seatwave-proxy --format '{{.Name}}' | wc -l | tr -d ' '
0
```

## Other programs

```
./gradlew -q serve --args='config/backend-local.properties'   # SeatWave on this machine, ports 20930 to 20932, no Docker
./gradlew -q bench --args='config/bench.properties'            # the bench, against the stack
./gradlew -q probe --args='config/probe.properties'            # one request every 100 ms for 30 s
./gradlew -q logStats --args='build/stack/access.log'          # the proxy's numbers from its log
./gradlew -q report --args='build/timings/before-after.txt'    # Module 5's curl report
scripts/curl-timings.sh config/targets-before-after.txt 7 build/timings/before-after.txt --run --yes --reuse
scripts/hop-timings.sh 15 build/timings/hop.txt --run
scripts/spof.sh <proxy|backend|reload> --run --yes
```

SeatWave on this machine, without Docker (the second `GET /health` in the count is the start-up check that waited for the port):

```
$ ./gradlew -q serve --args='config/backend-local.properties'   (in a second terminal; stopped with Ctrl-C afterwards)
SeatWave plain HTTP on 127.0.0.1:20931
SeatWave HTTPS on 127.0.0.1:20930 (TLSv1.3)
SeatWave operations (metrics, health) on 127.0.0.1:20932
64 worker threads, trusted proxies [127.0.0.1]

$ curl -s --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20930/health; echo
{"status":"up"}

$ curl -s http://127.0.0.1:20932/metrics | grep 'GET /health'
seatwave_requests_total{route="GET /health",status="200"} 2
```

## Project structure

```
build.gradle.kts, settings.gradle.kts, gradle/      the build, the version catalog, the lock files
compose.yaml, Dockerfile, .dockerignore             the stack and SeatWave's image
nginx/                                              the proxy's and the sidecar's configuration
config/                                             settings for every program and lab
scripts/                                            stack.sh, spof.sh, curl-timings.sh, hop-timings.sh
src/main/java/io/plainround/seatwave/proxy/
  forwarded/   the client-address rule              backend/   SeatWave
  bench/       load, CPU and connection counters    probe/     the outage probe
  slow/        the slow clients                     logs/      the access-log reader
  report/      Module 5's curl report               config/, tls/, json/
src/test/java/...                                   28 test classes and the TestBackend helper; stack/ needs the running stack
```

## What is simplified

- The seat page's script (`src/main/resources/static/seat-map.js`) was not run in a browser [not run]. Its retry rule was run under Node 24.14.1 with a stand-in `fetch`: a `422` is final after one call, a `409` for a taken seat returns at once, a `409` for a request still in progress is tried again, and three `503`s or three lost connections end after three calls.
- One proxy, not a pair: the lab measures what losing it costs; the redundant pair behind a shared address is Module 10's.
- The backend keeps holds in memory and serves only the paths the labs need, in Module 7's shapes; there is no database, cancel, confirm or payment. Its Idempotency-Key table never forgets a key: at 100,000 keys it answers every new hold `503` until SeatWave restarts. The labs send a few dozen; Module 7 keeps its keys in its database, in the same transaction as the hold.
- The certificates are TEST certificates from a TEST authority, valid 30 days and made at build time; production would use a public authority and a secret store.
- The proxy runs on one CPU and SeatWave on four of the Docker virtual machine, so the numbers are per core and per reference-machine-sized node, not production capacity.

## No CI on purpose

There is no `.github/` folder and no workflow: the course's projects are built and checked locally with the commands above, and the stack needs Docker, which a reader runs on purpose.

## License

MIT; see `LICENSE`.
