# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line with a **key** (the name) and a **value**, joined by an equals sign: `http.plainPort=8080`. The format comes from Java. The JDK class `java.util.Properties` reads it into a table of text keys and text values.

Everything in a properties file is text. The program decides that `8080` is a number and converts it. If the text is not a number, the conversion fails, and the program must say so. In this repository the class `SettingsReader` does the conversion and the saying (`java-config-json-tls-walkthrough.md`), and each program has a settings class that lists its keys and the range of each (`BackendSettings`, `BenchSettings`, `ProbeSettings`, `SlowSettings`).

## Why this repository uses it

Every command reads its numbers from a file in `config/`, not from the code. There are three reasons. A person can change a number without compiling anything. Every number has a comment that says why it has that value. And there are **no defaults in code**: a key that is missing is reported as a problem, never replaced silently by a hidden value, so the file is the only place a number lives. For a repository that measures a proxy, this matters: the 500 clients, the 50 KB/s link and the 100 ms sample of a lab are written next to a comment that explains them, and a reader can change one and run the lab again.

## Where it appears

| File | Read by | What it sets |
|---|---|---|
| `config/backend-local.properties` | `BackendCli` (`serve` task) | SeatWave on this machine without Docker: three ports on 127.0.0.1, the test key store, the worker pool, the trusted proxy and the slow-route timings. |
| `config/backend-container.properties` | `BackendCli`, as the `CMD` of the container image | The same keys for the container: it binds every address, uses fixed ports and trusts the proxy's address. |
| `config/bench.properties` | `BenchCli` (`bench` task) | The Java bench: timeouts, warm-up and runs, the number of clients, two container names and sixteen scenarios. |
| `config/probe.properties` | `ProbeCli` (`probe` task, started by `scripts/spof.sh`) | The outage probe: one URL, one request every 100 ms for 30 s. |
| `config/slow-direct.properties` and five more `config/slow-*.properties` | `SlowClientsCli` (`slowClients` task, or the `tools` service of the Compose file) | Lab 3: 500 slow clients, aimed at SeatWave, or through the proxy with buffering on or off; with the seat map or the poster. |

The last file in `config/`, `targets-before-after.txt`, is not a properties file; it is at the end of this note.

## How it works underneath

**The format.** The reader goes through the file line by line. I ran a small program on JDK 25 to check each rule.

- A line that starts with `#` or `!` is a **comment** and is ignored. These files use `#`.
- A blank line is ignored.
- A line like `key=value` sets a key. The separator may be `=`, `:` or white space. These files use `=`. I loaded `c: z` and got the key `c` with the value `z`.
- White space around the key and before the value is dropped. White space at the **end** of a value is kept. I loaded `sp = a b ` and got `a b ` with a trailing space. That is why the settings reader calls `strip()`.
- A `#` in the middle of a line is **not** a comment: it is part of the value. I loaded `b = x # not` and got the value `x # not`. So a comment must be on a line of its own, as in every file here.
- If a key appears twice, the last one wins. I loaded `a=1` then `a=2` and got `2`.
- A backslash at the very end of a line **continues** the value on the next line, and the white space at the start of the next line is dropped. `bench.properties` uses this once, for the list of sixteen scenario names. I loaded the real file and `scenario.names` came out as one line, `direct-http,proxy-http,...,handshake-proxy`, with no spaces and no break.
- Other backslashes start an escape (`\t` is a tab). Only that one line-end backslash appears in these files.
- Dots in a key mean nothing to the reader. `slow.targetUri` is just that name. The dots are a naming habit that groups related keys. A name like `scenario.proxy-tls.uri` is built by the code from the prefix `scenario.`, the scenario name and a suffix.
- A lookup of a key that is not there returns `null`. The settings reader turns that into the problem line `<key> is missing`.

**What the program does with it.** `SettingsReader.load` opens the file as UTF-8 text and passes the reader to `Properties.load`. A path in a settings file is relative to the folder the program starts in. Each Gradle run task sets `workingDir = rootDir` (`gradle.md`), so `build/tls/seatwave-server.p12` means the folder `build/tls` of the project.

**Units are in the key name.** A key that ends in `Millis` is a number of milliseconds, `Bytes` is bytes, `Port` is a port number, and `bytesPerTick` is bytes sent in each tick. A reader never has to guess a unit.

**A value can be a list.** A comma-separated value is read as a list: `forwarded.trustedProxies=10.209.9.10,127.0.0.1` is two addresses, and `scenario.names` is sixteen names.

**Every key is required.** The first comment of most files says so. Delete a line and the command prints `<key> is missing` and exits with status 2. One run reports all problems at once. I ran this with a damaged copy of `backend-local.properties` (the worker count set to 0, the heartbeat line deleted, and the plain port changed to equal the TLS port):

```
the settings in ../cfgtest/bad.properties have 3 problem(s):
  http.workerThreads must be from 1 to 1024, but was 0
  feed.heartbeatMillis is missing
  http.plainPort, http.tlsPort and http.adminPort must differ (0 means any free port)
```

Gradle then reports `finished with non-zero exit value 2`. With a file that does not exist the program prints `the settings file config/missing.properties does not exist`.

## Walkthrough of this repository's use

### `config/backend-local.properties`

The whole file:

```properties
# SeatWave on this machine without Docker: ./gradlew -q serve --args='config/backend-local.properties'
# Every key is required; there are no defaults in code. Everything binds to 127.0.0.1 only.
http.bindAddress=127.0.0.1
http.plainPort=20931
http.tlsPort=20930
http.adminPort=20932
tls.keystore=build/tls/seatwave-server.p12
tls.passwordFile=build/tls/store.password
# Connections the kernel holds for each listener until SeatWave accepts them.
http.backlog=1024
http.workerThreads=64
http.queueCapacity=1024
# No proxy runs in front of it here, so the only trusted address is this machine's own.
forwarded.trustedProxies=127.0.0.1
feed.longPollHoldMillis=25000
feed.heartbeatMillis=15000
feed.streamLifetimeMillis=40000
```

Key by key. The class that reads them is `BackendSettings.from`.

- `http.bindAddress=127.0.0.1` is the address the three listeners bind to. The value must be an IP address (a name would need a lookup before the server could start). `127.0.0.1` is the loopback address: only this machine can connect.
- `http.plainPort=20931`, `http.tlsPort=20930` and `http.adminPort=20932` are the three ports, each from 0 to 65,535. The plain port serves HTTP, the TLS port serves HTTPS, and the admin port serves `/metrics` and `/health` on a thread of its own. They must differ; 0 means "any free port", and two zeros do not collide. The tests use 0.
- `tls.keystore=build/tls/seatwave-server.p12` and `tls.passwordFile=build/tls/store.password` name the test key store and the file with its random password, both made by `./gradlew testCertificates` (`gradle.md`, section "The certificate task"). The value may contain only letters, digits and the signs `_ . / -`, and at most 200 characters.
- The comment on line 9 explains the next key: "Connections the kernel holds for each listener until SeatWave accepts them." `http.backlog=1024` is that queue length (1 to 65,535).
- `http.workerThreads=64` is the number of threads that run requests (1 to 1,024), and `http.queueCapacity=1024` is how many requests may wait for a free thread (1 to 100,000).
- `forwarded.trustedProxies=127.0.0.1` lists the only peers whose forwarded headers SeatWave believes (`java-forwarded-walkthrough.md`). The comment on line 13 says why it is only this machine: no proxy runs in front of SeatWave here.
- `feed.longPollHoldMillis=25000`, `feed.heartbeatMillis=15000` and `feed.streamLifetimeMillis=40000` are the timings of the slow routes of Module 8: a long poll waits 25 seconds, an event stream sends a heartbeat after 15 seconds of silence, and the lab's stream ends after 40 seconds. Each is 1 to 600,000 ms, and the lifetime must be longer than the heartbeat.

### `config/backend-container.properties`

The whole file:

```properties
# SeatWave behind the proxy, inside its container (compose.yaml). Every key is required; there are
# no defaults in code. Inside the container it binds every address; compose.yaml publishes its
# ports on 127.0.0.1 only, so nothing outside this machine can reach it.
http.bindAddress=0.0.0.0
# The proxy's upstream: plain HTTP on the lab network.
http.plainPort=8080
# SIMPLE: SeatWave answering clients itself, with its own TLS (published on 20910).
http.tlsPort=8443
# /metrics and /health on their own thread, readable while every worker is busy.
http.adminPort=8081
# The TEST keystore and its random password, made by ./gradlew testCertificates and mounted
# read-only from build/tls.
tls.keystore=/run/tls/seatwave-server.p12
tls.passwordFile=/run/tls/store.password
# Connections the kernel holds for each listener until SeatWave accepts them. The JDK's default
# is small; a burst of 500 new connections from the proxy overflowed it (Section 9).
http.backlog=1024
# Module 4's 64 worker threads, and a bounded queue in front of them.
http.workerThreads=64
http.queueCapacity=1024
# The only peers whose forwarded headers SeatWave believes: the reverse proxy's fixed address
# (compose.yaml) and the sidecar, which shares SeatWave's network namespace and so its loopback.
forwarded.trustedProxies=10.209.9.10,127.0.0.1
# Module 8: a long poll is held 25 s; the event stream sends a heartbeat after 15 s of silence.
feed.longPollHoldMillis=25000
feed.heartbeatMillis=15000
# The lab's stream ends after 40 s, long enough for two heartbeats, so no lab leaves it open.
feed.streamLifetimeMillis=40000
```

The keys are the same as above; the values differ because the program runs inside a container.

- `http.bindAddress=0.0.0.0` means "every address of this container". Inside a container, binding `127.0.0.1` would be reachable only from inside the container, not from the proxy. The comment on lines 1 to 3 says why this is safe: the Compose file publishes the ports on 127.0.0.1 only, so nothing outside this machine can reach them.
- `http.plainPort=8080`, `http.tlsPort=8443` and `http.adminPort=8081` are fixed container ports. The Compose file publishes them on the machine as 20911, 20910 and 20912. The comment on line 5 says the plain port is the proxy's upstream, and the comment on line 7 says SeatWave answers clients itself, with its own TLS, on the TLS port (this is the "before" of the lab).
- `tls.keystore=/run/tls/seatwave-server.p12` and `tls.passwordFile=/run/tls/store.password` are paths inside the container. The comment on lines 11 and 12 says the files are the test key store and its random password, "mounted read-only from build/tls". A **mount** makes a folder of the machine appear inside the container.
- `http.backlog=1024` has a longer comment than in the local file: the JDK's default queue is small, and a burst of 500 new connections from the proxy overflowed it. The 1024 comes from that measurement.
- `http.workerThreads=64` and `http.queueCapacity=1024` are Module 4's 64 worker threads and a bounded queue in front of them.
- `forwarded.trustedProxies=10.209.9.10,127.0.0.1` trusts two peers: the proxy's fixed address on the lab network, and the sidecar, a second NGINX that shares SeatWave's network namespace and so reaches it over its loopback address.
- The three `feed.` keys have the same values as in the local file; the comment on line 27 adds that the stream ends after 40 s, long enough for two heartbeats.

### `config/probe.properties`

The whole file:

```properties
# The single-point-of-failure lab (Chapter 9.4): one client asks for /health through the
# PRODUCTION proxy every 100 ms for 30 s, while scripts/spof.sh stops and starts a container.
# Every key is required; there are no defaults in code.
probe.uri=https://127.0.0.1:20900/health
tls.authorityPem=build/tls/seatwave-test-ca.pem
probe.intervalMillis=100
probe.durationMillis=30000
# A short budget, so a request that cannot connect fails within one probe interval or two.
client.connectTimeoutMillis=200
client.requestTimeoutMillis=1000
```

Read by `ProbeSettings.from`.

- `probe.uri=https://127.0.0.1:20900/health` is the one URL to ask. The value must match `http` or `https`, the address `127.0.0.1`, a port from 20900 to 20999 and a plain path. A typo cannot send the probe to another host.
- `tls.authorityPem=build/tls/seatwave-test-ca.pem` is the file with the test certificate authority that the probe trusts. It holds only a certificate, no key.
- `probe.intervalMillis=100` and `probe.durationMillis=30000` are one request every 100 ms for 30 seconds, about 300 requests.
- The comment on line 8 explains the last two keys: "A short budget, so a request that cannot connect fails within one probe interval or two." `client.connectTimeoutMillis=200` is how long a connect may take, and `client.requestTimeoutMillis=1000` is how long the whole request may take.

### The six `config/slow-*.properties` files

They have the same nine keys. I show the first one in full, then the differences.

```properties
# Lab 3: 500 slow clients straight to SeatWave. Run inside the lab network:
#   docker compose -p seatwave-proxy run --rm tools /app/config/slow-direct.properties
# Every key is required; there are no defaults in code.
# Module 1's 37 KB seat map of section A (36,894 bytes).
slow.targetUri=http://10.209.9.20:8080/events/1/seats?section=A
# 500 fans open the seat page at the same moment.
slow.clients=500
# 5,000 bytes every 100 ms is 50 KB/s, about 400 kbit/s: a weak mobile link.
slow.bytesPerTick=5000
slow.tickMillis=100
# A phone-sized receive window: the server can send only this far ahead of what the client read.
slow.receiveBufferBytes=4096
# A cheap request straight to SeatWave's main port, which waits in the same queue as the slow ones.
slow.fastProbeUri=http://10.209.9.20:8080/health
# SeatWave's operations listener, which has its own thread and answers even when workers are busy.
slow.metricsUri=http://10.209.9.20:8081/metrics
slow.sampleMillis=100
slow.timeoutMillis=120000
```

Read by `SlowSettings.from`.

- `slow.targetUri=http://10.209.9.20:8080/events/1/seats?section=A` is what every slow client requests. The value must be plain HTTP to an address of the lab network (`10.209.9.x`), because the tool runs inside the network. Here the address is SeatWave's (`10.209.9.20`) and the path is Module 1's 37 KB seat map of section A (36,894 bytes).
- `slow.clients=500` is how many clients start at once (1 to 5,000).
- `slow.bytesPerTick=5000` and `slow.tickMillis=100` set the reading speed: a client reads 5,000 bytes, then waits 100 ms. That is 50,000 bytes per second, about 400 kilobits per second, a weak mobile link. The program divides one by the other (`SlowSettings.bytesPerSecond`).
- `slow.receiveBufferBytes=4096` is the size of the socket receive buffer, a phone-sized window (1,024 to 1,000,000). The server can send only this far ahead of what the client has read.
- `slow.fastProbeUri=http://10.209.9.20:8080/health` is a cheap request sent straight to SeatWave's main port during the lab. It waits in the same queue as the slow clients, so its delay shows how busy the workers are.
- `slow.metricsUri=http://10.209.9.20:8081/metrics` is SeatWave's operations listener, which has its own thread and answers even when every worker is busy. The lab reads it to see how many workers are busy.
- `slow.sampleMillis=100` is the pause between two readings of the probe and the metrics.
- `slow.timeoutMillis=120000` is the most time a client may take, two minutes, before the run gives up on it.

The five other files differ only in the target, the timeout and the comments (the poster files have two more comment lines about the poster):

| File | `slow.targetUri` | `slow.timeoutMillis` |
|---|---|---|
| `slow-direct.properties` | `http://10.209.9.20:8080/events/1/seats?section=A` | `120000` |
| `slow-buffered.properties` | `http://10.209.9.10:8080/events/1/seats?section=A` | `120000` |
| `slow-unbuffered.properties` | `http://10.209.9.10:8081/events/1/seats?section=A` | `120000` |
| `slow-direct-poster.properties` | `http://10.209.9.20:8080/static/poster-arena.bin` | `300000` |
| `slow-buffered-poster.properties` | `http://10.209.9.10:8080/static/poster-arena.bin` | `300000` |
| `slow-unbuffered-poster.properties` | `http://10.209.9.10:8081/static/poster-arena.bin` | `300000` |

`10.209.9.10` is the proxy. Its port 8080 has proxy buffering on and its port 8081 has it off; the Compose file publishes them as 20902 and 20903. The poster is a 300,000-byte file, "larger than the kernel's socket buffers on the path, through a port with no cache, so every client is served by SeatWave", and its timeout is five minutes instead of two because the poster is about eight times larger than the seat map.

The two buffered and unbuffered files, side by side with the direct one, are the lab's whole point: the same 500 slow clients, with the proxy's buffering on, off, or no proxy. The files are shown one by one so every line is on the page:

```properties
# Lab 3: 500 slow clients through the proxy with buffering on (its port 8080, published as 20902). Run inside the lab network:
#   docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered.properties
# Every key is required; there are no defaults in code.
# Module 1's 37 KB seat map of section A (36,894 bytes).
slow.targetUri=http://10.209.9.10:8080/events/1/seats?section=A
# 500 fans open the seat page at the same moment.
slow.clients=500
# 5,000 bytes every 100 ms is 50 KB/s, about 400 kbit/s: a weak mobile link.
slow.bytesPerTick=5000
slow.tickMillis=100
# A phone-sized receive window: the server can send only this far ahead of what the client read.
slow.receiveBufferBytes=4096
# A cheap request straight to SeatWave's main port, which waits in the same queue as the slow ones.
slow.fastProbeUri=http://10.209.9.20:8080/health
# SeatWave's operations listener, which has its own thread and answers even when workers are busy.
slow.metricsUri=http://10.209.9.20:8081/metrics
slow.sampleMillis=100
slow.timeoutMillis=120000
```

```properties
# Lab 3: 500 slow clients through the proxy with buffering off (its port 8081, published as 20903). Run inside the lab network:
#   docker compose -p seatwave-proxy run --rm tools /app/config/slow-unbuffered.properties
# Every key is required; there are no defaults in code.
# Module 1's 37 KB seat map of section A (36,894 bytes).
slow.targetUri=http://10.209.9.10:8081/events/1/seats?section=A
# 500 fans open the seat page at the same moment.
slow.clients=500
# 5,000 bytes every 100 ms is 50 KB/s, about 400 kbit/s: a weak mobile link.
slow.bytesPerTick=5000
slow.tickMillis=100
# A phone-sized receive window: the server can send only this far ahead of what the client read.
slow.receiveBufferBytes=4096
# A cheap request straight to SeatWave's main port, which waits in the same queue as the slow ones.
slow.fastProbeUri=http://10.209.9.20:8080/health
# SeatWave's operations listener, which has its own thread and answers even when workers are busy.
slow.metricsUri=http://10.209.9.20:8081/metrics
slow.sampleMillis=100
slow.timeoutMillis=120000
```

```properties
# Lab 3, second part: 500 slow clients downloading the poster straight to SeatWave. Run inside the lab network:
#   docker compose -p seatwave-proxy run --rm tools /app/config/slow-direct-poster.properties
# Every key is required; there are no defaults in code.
# The 300 KB poster (300,000 bytes), larger than the kernel's socket buffers on the path, through
# a port with no cache, so every client is served by SeatWave.
slow.targetUri=http://10.209.9.20:8080/static/poster-arena.bin
# 500 fans open the seat page at the same moment.
slow.clients=500
# 5,000 bytes every 100 ms is 50 KB/s, about 400 kbit/s: a weak mobile link.
slow.bytesPerTick=5000
slow.tickMillis=100
# A phone-sized receive window: the server can send only this far ahead of what the client read.
slow.receiveBufferBytes=4096
# A cheap request straight to SeatWave's main port, which waits in the same queue as the slow ones.
slow.fastProbeUri=http://10.209.9.20:8080/health
# SeatWave's operations listener, which has its own thread and answers even when workers are busy.
slow.metricsUri=http://10.209.9.20:8081/metrics
slow.sampleMillis=100
slow.timeoutMillis=300000
```

```properties
# Lab 3, second part: 500 slow clients downloading the poster through the proxy with buffering on (its port 8080, published as 20902). Run inside the lab network:
#   docker compose -p seatwave-proxy run --rm tools /app/config/slow-buffered-poster.properties
# Every key is required; there are no defaults in code.
# The 300 KB poster (300,000 bytes), larger than the kernel's socket buffers on the path, through
# a port with no cache, so every client is served by SeatWave.
slow.targetUri=http://10.209.9.10:8080/static/poster-arena.bin
# 500 fans open the seat page at the same moment.
slow.clients=500
# 5,000 bytes every 100 ms is 50 KB/s, about 400 kbit/s: a weak mobile link.
slow.bytesPerTick=5000
slow.tickMillis=100
# A phone-sized receive window: the server can send only this far ahead of what the client read.
slow.receiveBufferBytes=4096
# A cheap request straight to SeatWave's main port, which waits in the same queue as the slow ones.
slow.fastProbeUri=http://10.209.9.20:8080/health
# SeatWave's operations listener, which has its own thread and answers even when workers are busy.
slow.metricsUri=http://10.209.9.20:8081/metrics
slow.sampleMillis=100
slow.timeoutMillis=300000
```

```properties
# Lab 3, second part: 500 slow clients downloading the poster through the proxy with buffering off (its port 8081, published as 20903). Run inside the lab network:
#   docker compose -p seatwave-proxy run --rm tools /app/config/slow-unbuffered-poster.properties
# Every key is required; there are no defaults in code.
# The 300 KB poster (300,000 bytes), larger than the kernel's socket buffers on the path, through
# a port with no cache, so every client is served by SeatWave.
slow.targetUri=http://10.209.9.10:8081/static/poster-arena.bin
# 500 fans open the seat page at the same moment.
slow.clients=500
# 5,000 bytes every 100 ms is 50 KB/s, about 400 kbit/s: a weak mobile link.
slow.bytesPerTick=5000
slow.tickMillis=100
# A phone-sized receive window: the server can send only this far ahead of what the client read.
slow.receiveBufferBytes=4096
# A cheap request straight to SeatWave's main port, which waits in the same queue as the slow ones.
slow.fastProbeUri=http://10.209.9.20:8080/health
# SeatWave's operations listener, which has its own thread and answers even when workers are busy.
slow.metricsUri=http://10.209.9.20:8081/metrics
slow.sampleMillis=100
slow.timeoutMillis=300000
```

Each first comment line gives the command that runs the lab inside the lab network: `docker compose -p seatwave-proxy run --rm tools /app/config/<file>`. This note did not run Docker, so those commands were not run.

### `config/bench.properties`

The first part: the settings that apply to every scenario.

```properties
# The Java bench (Module 9, Section 8): ./gradlew -q bench --args='config/bench.properties'
# with the stack up (scripts/stack.sh up --run). Every key is required; there are no defaults.
tls.authorityPem=build/tls/seatwave-test-ca.pem
client.connectTimeoutMillis=2000
client.requestTimeoutMillis=10000
# Each scenario: a 5 s warm-up that is thrown away, then 3 runs of 10 s; the table shows the
# median run by requests per second, and lists all three.
run.warmupMillis=5000
run.measureMillis=10000
run.repeats=3
# 8 clients at once, each sending its next request as soon as the last one is answered.
run.clients=8
counters.proxyContainer=seatwave-proxy-proxy-1
counters.backendContainer=seatwave-proxy-backend-1
scenario.names=direct-http,proxy-http,direct-tls,proxy-tls,proxy-no-reuse,proxy-sidecar,\
  gzip-1,gzip-6,gzip-9,production,cache-lock,page-production,poster-direct,poster-cached,\
  handshake-direct,handshake-proxy

```

Read by `BenchSettings.from`.

- `tls.authorityPem=build/tls/seatwave-test-ca.pem` is the test authority file the bench trusts.
- `client.connectTimeoutMillis=2000` and `client.requestTimeoutMillis=10000` are the connect and whole-request timeouts of every request (1 to 60,000 ms).
- The comment on lines 6 and 7 explains the next three keys: each scenario gets "a 5 s warm-up that is thrown away, then 3 runs of 10 s; the table shows the median run by requests per second, and lists all three." `run.warmupMillis=5000` is the warm-up (0 to 600,000 ms), `run.measureMillis=10000` is one measured run, and `run.repeats=3` is how many measured runs there are (at least 3, which is `Runs.MINIMUM_RUNS`, and at most 50).
- The comment on line 11: "8 clients at once, each sending its next request as soon as the last one is answered." `run.clients=8` is that number (1 to 2,000).
- `counters.proxyContainer=seatwave-proxy-proxy-1` and `counters.backendContainer=seatwave-proxy-backend-1` are the names of the two containers whose CPU and TCP counters the bench reads. Compose names a container `<project>-<service>-<number>`, here `seatwave-proxy` plus `proxy` or `backend` plus `1`.
- `scenario.names=...` is the list of sixteen scenario names, continued over three lines with a backslash at the end of the first two. The names may be lower-case letters, digits and dashes, up to 17 characters, the width of the bench table's first column (`Scenario.NAME`).

Each name `x` then needs three keys, `scenario.x.uri`, `scenario.x.gzip` and `scenario.x.connection`. The `uri` must match `http` or `https`, `127.0.0.1`, a port 20900 to 20999 and a plain path. The `gzip` is `yes` or `no`: whether the client sends `Accept-Encoding: gzip`. The `connection` is `reuse` (keep one connection per client and send the next request on it) or `new` (open a new connection, and so do a full handshake, for every request). The scenarios, in groups:

```properties
# The extra hop alone: plain HTTP straight to SeatWave, then through the proxy.
scenario.direct-http.uri=http://127.0.0.1:20911/events/1/seats?section=A
scenario.direct-http.gzip=no
scenario.direct-http.connection=reuse
scenario.proxy-http.uri=http://127.0.0.1:20902/events/1/seats?section=A
scenario.proxy-http.gzip=no
scenario.proxy-http.connection=reuse

```

The extra hop alone: plain HTTP straight to SeatWave on the published port 20911, then through the proxy's plain port 20902.

```properties
# TLS termination: SIMPLE (SeatWave's own TLS) against the proxy's, on reused connections.
scenario.direct-tls.uri=https://127.0.0.1:20910/events/1/seats?section=A
scenario.direct-tls.gzip=no
scenario.direct-tls.connection=reuse
scenario.proxy-tls.uri=https://127.0.0.1:20901/events/1/seats?section=A
scenario.proxy-tls.gzip=no
scenario.proxy-tls.connection=reuse

```

TLS termination: SeatWave's own TLS (port 20910) against the proxy's (20901), both with reused connections.

```properties
# Keep-alive to the upstream: the same as proxy-tls, but a new upstream connection per request.
scenario.proxy-no-reuse.uri=https://127.0.0.1:20904/events/1/seats?section=A
scenario.proxy-no-reuse.gzip=no
scenario.proxy-no-reuse.connection=reuse
```

Keep-alive to the upstream: port 20904 is the lab variant of the proxy that opens a new upstream connection for every request, while the client still reuses its own connection.

```properties
# The sidecar: the proxy re-encrypts to a second NGINX beside SeatWave.
scenario.proxy-sidecar.uri=https://127.0.0.1:20906/events/1/seats?section=A
scenario.proxy-sidecar.gzip=no
scenario.proxy-sidecar.connection=reuse
```

The sidecar: port 20906 is the proxy re-encrypting to a second NGINX beside SeatWave.

```properties
# Compression: gzip levels 1, 6 and 9 on the 37 KB seat map, bytes and the proxy's CPU.
scenario.gzip-1.uri=https://127.0.0.1:20907/events/1/seats?section=A
scenario.gzip-1.gzip=yes
scenario.gzip-1.connection=reuse
scenario.gzip-6.uri=https://127.0.0.1:20908/events/1/seats?section=A
scenario.gzip-6.gzip=yes
scenario.gzip-6.connection=reuse
scenario.gzip-9.uri=https://127.0.0.1:20909/events/1/seats?section=A
scenario.gzip-9.gzip=yes
scenario.gzip-9.connection=reuse
```

Compression: ports 20907, 20908 and 20909 are the proxy with gzip levels 1, 6 and 9 on the 37 KB seat map. `gzip=yes` is what makes the client ask for compressed answers.

```properties
# PRODUCTION on the seat map, and the cache mistake beside it: a cache lock on a dynamic path.
scenario.production.uri=https://127.0.0.1:20900/events/1/seats?section=A
scenario.production.gzip=yes
scenario.production.connection=reuse
scenario.cache-lock.uri=https://127.0.0.1:20914/events/1/seats?section=A
scenario.cache-lock.gzip=no
scenario.cache-lock.connection=reuse
# PRODUCTION on a version 1 page of 500 seats (Module 7's largest page).
scenario.page-production.uri=https://127.0.0.1:20900/v1/events/1/seats?section=A&limit=500
scenario.page-production.gzip=yes
scenario.page-production.connection=reuse
```

PRODUCTION on port 20900 for the seat map, the cache mistake beside it on port 20914 (a cache lock on a dynamic path), and PRODUCTION on a version 1 page of 500 seats (`limit=500`, Module 7's largest page). In the last URI the `&` joins two query parameters.

```properties
# The static-file cache: the 300 KB poster from SeatWave, and from the proxy's cache.
scenario.poster-direct.uri=https://127.0.0.1:20910/static/poster-arena.bin
scenario.poster-direct.gzip=no
scenario.poster-direct.connection=reuse
scenario.poster-cached.uri=https://127.0.0.1:20900/static/poster-arena.bin
scenario.poster-cached.gzip=yes
scenario.poster-cached.connection=reuse
```

The static-file cache: the 300 KB poster fetched from SeatWave (port 20910) and from the proxy's cache (port 20900).

```properties
# TLS handshakes per second: a new connection, and so a full handshake, for every request.
scenario.handshake-direct.uri=https://127.0.0.1:20910/health
scenario.handshake-direct.gzip=no
scenario.handshake-direct.connection=new
scenario.handshake-proxy.uri=https://127.0.0.1:20901/health
scenario.handshake-proxy.gzip=no
scenario.handshake-proxy.connection=new
```

TLS handshakes per second: `connection=new` makes a new connection, and so a full handshake, for every request, straight to SeatWave and through the proxy.

### `config/targets-before-after.txt`

This file is not a properties file. It is a list of targets for `scripts/curl-timings.sh`, Module 5's `curl -w` method (`shell-scripts-walkthrough.md`).

```text
# Module 9's before and after, with Module 5's curl -w method: name, URL, CA file, optional gzip.
# Before (SIMPLE): clients straight to SeatWave on its own ports.
direct-https    https://127.0.0.1:20910/events/1/seats?section=A  build/tls/seatwave-test-ca.pem
direct-http     http://127.0.0.1:20911/events/1/seats?section=A
# After: the same request through the proxy, one feature at a time, then PRODUCTION.
proxy-http      http://127.0.0.1:20902/events/1/seats?section=A
proxy-tls       https://127.0.0.1:20901/events/1/seats?section=A  build/tls/seatwave-test-ca.pem
proxy-no-reuse  https://127.0.0.1:20904/events/1/seats?section=A  build/tls/seatwave-test-ca.pem
proxy-sidecar   https://127.0.0.1:20906/events/1/seats?section=A  build/tls/seatwave-test-ca.pem
production      https://127.0.0.1:20900/events/1/seats?section=A  build/tls/seatwave-test-ca.pem  gzip
```

A line that starts with `#` is a comment. Every other line has two to four fields separated by white space: a name, a URL, an optional file with the certificate authority, and an optional word `gzip`. The script allows only `127.0.0.1` and ports 20900 to 20999, and it refuses a CA file that does not exist, so the lines with a CA file need `./gradlew testCertificates` first. The two plain HTTP lines (`direct-http`, `proxy-http`) have no CA file because there is no TLS to check. The first two lines are the "before" (clients straight to SeatWave on 20910 and 20911), the next four are the same request through the proxy one feature at a time, and the last, `production`, is the full configuration with `gzip`, so curl sends `Accept-Encoding: gzip`.

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026.

1. Count the keys of each file:

```
for f in config/*.properties; do echo "$f $(grep -c '^[a-z]' $f)"; done
```

   Expected: `config/backend-container.properties 13`, `config/backend-local.properties 13`, `config/bench.properties 58`, `config/probe.properties 6`, and `9` for each of the six `slow-*` files.

2. Check that the six slow files differ only in a few lines:

```
diff config/slow-direct.properties config/slow-buffered.properties
```

   Expected: differences in lines 1 and 2 (the comments) and line 5 (`slow.targetUri`, from `10.209.9.20` to `10.209.9.10`) and in nothing else; the timeout is the same 120000.

3. Run the program with a missing file:

```
./gradlew --offline -q serve --args='config/missing.properties'
```

   Expected: the line `the settings file config/missing.properties does not exist`, then Gradle's failure report.

4. Break a copy on purpose. Make a copy of `config/backend-local.properties` outside `config/`, set `http.workerThreads=0`, delete the line `feed.heartbeatMillis=15000`, and run `./gradlew --offline -q serve --args='<your copy>'`. Expected: the three problem lines shown in "How it works underneath", and `finished with non-zero exit value 2`.

5. Run the tests that read these very files (they start a server on ports the system picks, and need no Docker):

```
./gradlew --offline test --tests '*BackendCliTest' --tests '*BenchCliTest' --tests '*SlowClientsTest' --tests '*ProbeCliTest'
```

   Expected: `BUILD SUCCESSFUL`. These tests read `config/backend-local.properties`, `config/bench.properties` and the other files from the real folder, so a damaged file in `config/` breaks a test.

## Mistakes people make

- **Putting a comment after a value.** `http.backlog=1024 # queue` makes the value `1024 # queue`, which is not a number. Comments go on their own line.
- **A trailing space after a value.** The JDK keeps it. The settings reader strips it, but another program may not.
- **A settings rule stricter than real paths.** Version 1.0.0 required the key store and password paths to match `[A-Za-z0-9_./-]{1,200}`. In a scratch folder whose absolute path was 218 characters long, two tests failed: `BackendCliTest`, because the program printed `tls.keystore must match [A-Za-z0-9_./-]{1,200}, but was <the long path>`, and `BenchCliTest`, because the bench stopped with status 2 (bad settings) where the test expected status 1. Version 1.0.1 reads every file path with `SettingsReader.filePath`, which allows any character but a control character, up to 4,096, so a deep or oddly named folder works.
- **A space or an accent in a path.** The same pattern refuses it.
- **Using a host name where an address is required.** `http.bindAddress` and `forwarded.trustedProxies` take IP addresses only.
- **Changing a lab value without its comment.** The comment holds the reason; a changed number needs a changed reason.
- **Expecting a default.** There is none. A missing key is an error.

## Check yourself

1. *Which character starts a comment?* `#` (or `!`), and only at the start of a line.
2. *What does a backslash at the end of a line do?* It continues the value on the next line; `scenario.names` uses it.
3. *Why does the container file bind `0.0.0.0` and the local file `127.0.0.1`?* Inside a container only a bound-to-everything address can be reached from the proxy; on the machine, loopback keeps SeatWave private.
4. *How fast does a slow client read?* 5,000 bytes every 100 ms, 50,000 bytes per second.
5. *What is a scenario in `bench.properties`?* A name with three keys: a URL, `gzip` yes or no, and `connection` reuse or new.

## Further reading

- `java.util.Properties` (JDK 25), including the exact `load` rules: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html>
- `java.nio.file.Files` and `Path` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html>
