# Measuring a server and a proxy, from zero

## What it is

To **measure** a server is to send it requests on purpose and write down what happened: how fast it answered, how many requests it handled each second, how many failed, and what the answering cost. To measure a **proxy** is to do the same twice, once straight to the server and once through the proxy, and to compare the two sets of numbers.

This file explains the ideas, from nothing, and ties each one to a tool of this repository: the `curl` report (`report` package), the load bench (`bench`), the outage probe (`probe`), the slow clients (`slow`) and the access-log reader (`logs`). The code of each tool is read line by line in its own file; this one says what the numbers mean and how they can mislead.

A **request** is one question a client asks (`GET /events/1/seats?section=A`) and a **response** is the answer. **Latency** is how long one request takes, from the client sending it to the client having the last byte of the answer. **Throughput** is how many requests are answered per second. A **proxy** is a program in the middle that takes the client's request, passes it on to the server, and passes the answer back.

## Why this repository uses it

Module 9 makes claims about proxies: they save handshake work, they save bytes with compression, they keep slow clients off the server's threads, they serve repeated files from a cache, and they add one more hop and one more thing that can fail. Each claim is a number or it is only an opinion. The repository puts every claim through a tool, and the README prints what the tools printed on 11 November 2026.

The measuring rules are the course's: at least three runs for any reported number (`Runs.MINIMUM_RUNS`), the median and not the mean, a warm-up thrown away, a refused number rather than a wrong one, and a table that shows the spread of the runs next to the median.

## Where it appears

| Question | Tool | Files | Where the output is |
|---|---|---|---|
| Where does the time of one request go? | `report` | `report/CurlLine.java`, `PhaseTimings.java`, `TimingReport.java` | README, "Module 5's method, before and after" |
| How many requests per second, and how fast is the middle and the slowest one percent? | `bench` | `bench/LoadRun.java`, `BenchReport.java` | README, "The bench" |
| What did the proxy and SeatWave spend in processor time and connections? | `bench` | `bench/ContainerCounters.java`, `BenchRow.java` | the same table |
| How much did the proxy add, how often did the cache answer, how often did SeatWave fail? | `logStats` | `logs/AccessLine.java`, `LogStats.java` | README, the `logStats` table |
| What happens to slow clients, and to a fast client behind them? | `slowClients` | `slow/SlowClients.java` | README, "Lab 3" |
| What does a fan see when the proxy or SeatWave stops? | `probe` | `probe/ProbeCli.java`, `Timeline.java` | README, the three `spof.sh` runs |

The notes of each tool: `java-report-logs-walkthrough-1.md` (curl report), `java-bench-walkthrough-1.md` and `-2.md`, `java-report-logs-walkthrough-2.md` (logs), `java-slow-clients-walkthrough.md`, `java-probe-walkthrough.md`.

## How it works underneath

### 1. What to measure

Six kinds of numbers cover most questions about a server.

1. **Latency**: time per request. Reported as percentiles (below), not as one number.
2. **Throughput**: requests per second.
3. **Errors**: requests that failed or got a bad status. A fast server that fails half its requests is not fast.
4. **Cost**: processor time per request. It tells you how many requests one processor core can serve, and who pays for the work (the proxy or the server).
5. **Bytes**: how much crossed the network. Compression changes this.
6. **Connections and threads**: how many connections were opened, and how many worker threads were busy. A connection costs a round trip to open, and a TLS connection costs processor time too.

These trade against each other. A setting that raises throughput may raise processor time. The tools print several kinds in one row so that a gain is never reported without its cost.

### 2. The phases of one request

One request over a **new** HTTPS connection is not one step. It is a chain:

1. **DNS**: turning the host name into an IP address.
2. **Connect**: the TCP three-way handshake, one round trip.
3. **TLS**: the TLS handshake, one round trip for TLS 1.3, which sets up the encryption.
4. **First byte**: the request goes out, the server works, and the first byte of the answer comes back. This is one round trip plus the server's time.
5. **Transfer**: the rest of the answer arrives.

A **round trip** is the time for a message to go to the other side and the reply to come back. On the same machine it is a fraction of a millisecond. Between continents it is 100 ms or more, and phases 2 to 4 each cost at least one of them. That is why connection reuse (keep-alive) and TLS termination close to the user are worth measuring.

`curl -w` prints **cumulative** clock readings: each is the time since the start. The phases are the differences. Take one real line made for these notes on this machine:

```text
local-https dns=0.000061 connect=0.000252 tls=0.003677 ttfb=0.004032 total=0.004430 http=1.1 code=200 size=36894 connects=1 ...
```

| Phase | Arithmetic | Result |
|---|---|---|
| dns | 0.000061 | 0.061 ms |
| connect | 0.000252 - 0.000061 | 0.191 ms |
| tls | 0.003677 - 0.000252 | 3.425 ms |
| first byte | 0.004032 - 0.003677 | 0.355 ms |
| transfer | 0.004430 - 0.004032 | 0.398 ms |

The largest is TLS: on a new connection, the handshake dominates. On a reused connection the second request from the same `curl` command shows `connect=0.000000 tls=0.000000`, and the time is first byte and transfer. The README's table shows the same pattern for SeatWave: `direct-https new` spends 8.47 ms in TLS out of a 10.97 ms total, and `direct-https reused` has a total of 1.87 ms.

Three clocks measure three different things, so keep them apart:

- the **client's clock** (`curl -w`): everything the user waits for, including the network;
- the **proxy's clock** (the access log's `rt` and `urt`): from the proxy's point of view;
- the **server's clock** (the `Server-Timing: app;dur=0.83` response header): only the time SeatWave spent building the answer.

The README's two example responses show the server clock: `app;dur=6.88` ms for one request and `app;dur=0.83` ms for another. Those are SeatWave's own times, without the network or the proxy.

### 3. The cost of one extra hop

A proxy adds a hop: client to proxy, proxy to server. The way to measure it is to ask the same question of the server directly and through the proxy, many times, and compare the medians. The README does this from inside the proxy's container (to avoid the Mac's port forwarder, which adds a hop of its own): `hop-direct reused` total 0.57 ms and `hop-proxy reused` 0.60 ms, so the hop costs about 0.03 ms. The first-byte phase changes from 0.55 ms to 0.59 ms. The difference is small, and the table has two digits after the point: look at the `fastest` and `slowest` columns before you trust a difference of 0.03 ms.

### 4. Many requests: closed loop, throughput and latency

A **load generator** sends many requests. There are two designs.

- A **closed loop** has a fixed number of clients. Each sends a request, waits for the answer, and sends the next. The clients never send faster than the server answers. `LoadRun` is a closed loop: 8 clients in `config/bench.properties`.
- An **open loop** sends requests at a fixed rate, whatever the server does. It models users who arrive on their own.

A closed loop answers: "what can the path do with 8 requests in flight?". The numbers obey one rule, **Little's law**: the number of requests in flight equals the throughput times the mean latency. With 8 clients and no errors:

```text
mean latency = clients / throughput
```

Check it on the README's `direct-http` row: 8 clients and 4555 requests per second give a mean latency of 8 / 4555 = 1.76 ms. The row's p50 is 1.51 ms and its p99 is 5.30 ms: the mean is above the median because a few slow requests pull it up, as it should be. For `production`: 8 / 6549 = 1.22 ms against a p50 of 1.19 ms. For `handshake-direct`: 8 / 343 = 23.3 ms against a p50 of 23.35 ms. If these did not agree, something would be wrong with the measurement (for example, clients that wait on something the timer does not see).

A closed loop has a weakness: when the server stalls, all clients wait, and nobody sends. A 5-second stall adds only 8 slow samples out of tens of thousands. A real crowd would have kept arriving. So a closed loop shows capacity but understates the damage of a stall. This is sometimes called **coordinated omission**: the measurement slows down together with the thing it measures, and so leaves out the requests it would have sent. The outage probe has the same property in one respect (it waits for each answer) and is built differently in another (it keeps a schedule and catches up).

### 5. Percentiles, and why the mean lies

Sort all latencies from fastest to slowest. The **median** (p50) is the middle one: half the requests were faster. The **99th percentile** (p99) is the value that 99 of 100 requests beat. The **mean** adds everything and divides: one 9-second request in a thousand drags it up while the median does not move.

Users feel the slow tail, not the average, so p99 is the number to watch. But p99 needs many samples. This repository uses the **nearest-rank rule**: for `n` sorted values, the p-th percentile is the value at position `ceil(p * n)`, counting from 1. For 10 values p99 is position 10, the largest. With fewer than 100 values, p99 is always the maximum. The README's slow-client lab shows this: the fast probe answered 8 times in one run, and its p99 (1366.19 ms) equals its max. A p99 from 8 samples is the maximum with a longer name. The bench has tens of thousands of samples per run, so its p99 is a real tail.

The code of the rule is in `LoadRun`:

```java
  /**
   * Returns a percentile of sorted latencies by the nearest-rank rule: the smallest value with at
   * least that share of the values at or below it.
   *
   * @param sortedNanos the latencies, sorted from fastest to slowest
   * @param share for example 0.99
   * @return the percentile, or zero when there are no values
   */
  static Duration percentile(List<Long> sortedNanos, double share) {
    if (sortedNanos.isEmpty()) {
      return Duration.ZERO;
    }
    int rank = (int) Math.ceil(share * sortedNanos.size());
    return Duration.ofNanos(sortedNanos.get(Math.max(rank, 1) - 1));
  }
```

### 6. Repeats, warm-up and spread

A single run can be unlucky: the computer was busy, the Java program was still compiling its code, the disk cache was empty. So:

- **Warm-up**: run once first and throw the result away. The bench's warm-up is 5 seconds per scenario.
- **Repeat**: run at least three times. The bench runs 3 times 10 seconds.
- **Take the median run**: not the mean of three, but the middle run itself, with all its own numbers (`BenchReport.median`). The numbers in a row then belong together.
- **Show the spread**: the line under each row lists all three throughputs. In the README, `direct-http` ran 4555, 4491 and 4614 requests per second. That is a spread of about 2.7 percent. A difference between two scenarios smaller than the spread is not a result.

Look at the README's `proxy-no-reuse` row for what a bad spread looks like: runs of 957, 299 and 4 requests per second, 3,330 errors in the median run. The median (299) is a real run, but the row says the scenario is unstable, not that it is 299. The tool does not explain this; it only shows it. The row's CPU cost per thousand (3328 ms) is also inflated: see the next section.

### 7. Cost: processor time and connections

Throughput says how fast; cost says how expensive. The bench reads two files inside each container, before and after every run, with `docker exec ... cat`:

- `/sys/fs/cgroup/cpu.stat`, line `usage_usec`: the processor time all processes of the container have used, in microseconds. A **control group** (cgroup) is the Linux kernel's way to count what a group of processes uses.
- `/proc/net/snmp`, the `Tcp:` lines: `ActiveOpens` (connections the container opened) and `PassiveOpens` (connections it accepted).

The difference between the two readings is what the run cost. The table divides processor time by the number of successful requests, in milliseconds per thousand requests (`CPU ms/1k`):

```java
  private double perThousand(double amount) {
    return result.requests() == 0 ? 0 : amount * PER_THOUSAND / result.requests();
  }
}
```

Read the README with it.

- **Where the work goes.** `proxy-http`: the proxy spends 72.6 ms per thousand requests and SeatWave 254.2, against 254.7 for SeatWave alone in `direct-http`. The proxy adds work of its own and takes none from SeatWave, for plain HTTP.
- **TLS termination.** `direct-tls`: SeatWave spends 295.2. `proxy-tls`: the proxy 98.9 and SeatWave 248.0. SeatWave's cost falls by about 47 ms per thousand, and the proxy takes on 98.9. In this lab the total went up (98.9 + 248.0 against 295.2), so the proxy did not make the work smaller; it moved part of it.
- **Handshakes.** `handshake-direct` (a new TLS connection to SeatWave for every request): 343 requests per second, SeatWave 936.5 ms per thousand. `handshake-proxy` (a new TLS connection to the proxy, which reuses its own connection to SeatWave): 2606 requests per second, proxy 313.7 and SeatWave 114.3. SeatWave accepted 3,431 connections in the first case and 24 in the second. The handshake work moved to a program that is cheaper at it.
- **Compression.** `gzip-1` sends 2,453 bytes for a 36,894-byte seat map (93 percent fewer) at 99.5 ms per thousand. `gzip-9` sends 2,351 bytes (4 percent fewer than level 1) at 601.7 ms per thousand, six times the cost, and handles 1,656 requests per second against 6,670. More effort bought almost nothing.
- **The cache.** `poster-direct`: SeatWave spends 580.2 ms per thousand to send a 300 KB file. `poster-cached` (100 percent cache hits): SeatWave 0.4, the proxy 355.5.

Two cautions. First, processor time is divided by **successful** requests, but it counts the work for failed ones too. When a scenario has errors (`proxy-no-reuse`: 2,991 good requests, 3,330 errors), the cost per good request is overstated. Check the `errors` column first. Second, the readings are taken by running `cat` inside the container, which adds a little processor time to the counter; the amount is small against a 10-second run, but it is not zero.

### 8. Operators' numbers: the access log

A benchmark uses a made-up load. The proxy's **access log** records what it really did, one line per request. The `logStats` tool reads a copy and prints, per port, how many requests, how many reached SeatWave, the share of 5xx answers from SeatWave, the cache hit ratio and the time the proxy itself added.

The **time the proxy added** is `request_time - upstream_response_time`. Two cautions. It includes the time to write the answer to the client, so a slow client inflates it. And NGINX writes it with millisecond resolution, so a value of `1.00` means "one tick of the clock", not a measured millisecond. The README's table shows `0.00` at p50 and `1.00` at p99 for every port: the proxy added less than a tick for most requests.

The hit ratio is over the **cache lookups**, not over all requests, and the static hit ratio is over lookups of `/static/` paths. In the README, port 8443 shows `5.4` overall and `100.0` for static files: almost every static lookup was a hit (the first lookup of each file is a miss), while the dynamic seat map, which SeatWave marks `no-cache`, is not served from the cache.

### 9. Threads, queues and slow clients

SeatWave answers with a fixed pool of 64 worker threads. A worker is busy from the moment it takes a request until it has written the last byte. If the client reads slowly, the worker waits. The slow-client lab measures that cost directly: SeatWave counts `seatwave_workers_busy` and `seatwave_worker_busy_seconds_total` on its operations listener. The sampler reads the busy count every 100 ms, and the run reads the seconds counter before and after.

The README's numbers for 500 slow clients (50 KB/s each) on the seat map: straight to SeatWave, 64 of 64 workers busy and 108.35 worker-seconds, which is 0.2167 s per slow client; through the buffering proxy, 1 of 64 busy and 0.10 worker-seconds, which is 0.0002 s per client. 108.35 / 500 = 0.2167, so the per-client figure follows from the total. A **worker-second** is one worker busy for one second: a currency that shows what each slow client cost the server.

While this goes on, one fast probe asks for `/health`. Its latency is what a normal visitor would see: p50 2.96 ms but a worst case of 1366.19 ms behind the slow clients. The probe waits for each answer before it sends the next. When one answer takes 9 seconds, it sends nothing for 9 seconds, so there are very few samples of the bad time: 60 answered in the poster run, 1 failed. Count samples before you quote a tail.

### 10. Outages

The **outage probe** sends one request every 100 ms on a schedule and records how each ended. The result is a list of spans. The outage is the time from the first failed request to the first success after the last. The README's runs: stopping the proxy for 10 s gave `connection lost` x 2, then `connection refused` x 103, then 200s again: outage 10.502 s. Stopping SeatWave gave `request timeout` x 11 (about 1 s each, the probe's timeout) and an outage of 11.032 s. Reloading NGINX's configuration twice during a probe gave no outage: 300 requests, all 200.

How a failure looks matters. A refused connection is instant (the operating system answers at once). A timeout takes as long as the timeout. A user and a retry rule experience them differently.

### 11. What can mislead you

- **One run.** Use three or more, and show them.
- **No warm-up.** The first seconds are slow.
- **A noisy machine.** Other programs change the numbers. The README says the session ran "with other programs running". Compare scenarios from the same session only.
- **Mean for a skewed distribution.** Use percentiles.
- **A tail from few samples.** Count them.
- **A hidden hop.** On a Mac, a published port of a container goes through Docker Desktop's port forwarder, which buffers. The README measures the hop from inside the container for this reason. The slow-client lab runs inside the lab network for the same reason.
- **A closed loop on a stalled server.** It under-reports the damage.
- **Different work.** Gzip level 9 against level 1 sends different bytes. Compare like with like and print the bytes.
- **Errors left out.** Latency and bytes count only successes; read the `errors` column.
- **A lab result taken for production capacity.** The proxy runs on one CPU and SeatWave on four of the virtual machine's. The numbers are per core in a lab.
- **Kernel differences.** The slow-client program set a small receive buffer on a Mac and still found only one busy worker; the Linux kernel of Docker's virtual machine held the sender back. The same program can show different things on different kernels.

## Walkthrough of this repository's use

Take one claim and follow it from question to number.

**Claim: "TLS termination at the proxy saves SeatWave's processor time."**

1. The question needs two scenarios that differ in one thing. `config/bench.properties` has `direct-tls` (HTTPS straight to SeatWave on 20910) and `proxy-tls` (HTTPS to the proxy on 20901). Both ask for the same seat map. Neither compresses. Both reuse connections.
2. The bench starts 8 clients, runs 5 seconds of warm-up, then 3 runs of 10 seconds. Before and after each run it reads the counters of both containers.
3. `BenchReport` picks the median run by requests per second and prints `rps`, `p50`, `p99`, `errors`, the bytes, the CPU per thousand requests of the proxy and of SeatWave, and the connection counts.
4. You read: `direct-tls` 4454 rps, SeatWave 295.2; `proxy-tls` 4333 rps, proxy 98.9, SeatWave 248.0. The throughput is within the spread of the runs (4353 to 4461 against 4279 to 4410). SeatWave's cost fell by 47.2 ms per thousand; the proxy cost 98.9. Overall, one more hop and more total work, with SeatWave freed of some of it.
5. The claim "saves SeatWave's processor time" is true here (295.2 to 248.0) and "saves processor time" is not (the total went up from 295.2 to 346.9). The measurement tells you which statement is true.

**Claim: "Handshake cost moves to the proxy."** Use the `handshake-*` rows: 343 against 2606 requests per second; SeatWave 936.5 against 114.3 ms per thousand; SeatWave accepted 3,431 connections against 24.

**Claim: "The proxy is a single point of failure."** Use the probe: 10.5 s without service when the proxy stops, and `connection refused` as the failure the fan sees.

**Claim: "Buffering frees workers."** Use the slow-client lab: 108.35 against 0.10 worker-seconds.

In each case the answer is a number, with the run it came from, the machine it ran on, and a reading of what it does not show.

## Try it yourself

All commands run in the repository folder. Times differ on every run and on every computer. The writer ran SeatWave on this machine and did not run the Docker stack, so the bench's own table and the labs are described from the README's captured output.

**1. Start SeatWave on this machine.** In one terminal: `./gradlew -q testCertificates`, then `./gradlew -q serve --args='config/backend-local.properties'`. The writer used a copy of that file with the ports 20980 to 20982, because other work on the machine used 20930 to 20932; the addresses below say 20980 where you would write 20930. Stop it with Ctrl-C at the end.

**2. Measure latency by hand and compute percentiles.** Send 100 requests, each on a new connection, and write down `time_total`:

```text
mkdir -p build
for i in $(seq 100); do curl -s -o /dev/null -w '%{time_total}\n' --cacert build/tls/seatwave-test-ca.pem 'https://127.0.0.1:20930/events/1/seats?section=A'; done > build/totals.txt
sort -n build/totals.txt | awk '{a[NR]=$1; s+=$1} END{printf "n %d\nmean %.6f\np50 %s\np99 %s\nmax %s\n", NR, s/NR, a[50], a[99], a[100]}'
```

```text
n 100
mean 0.004246
p50 0.004097
p99 0.007975
max 0.010607
```

`sort -n` sorts the numbers from smallest to largest. The array `a` holds the sorted values by position. With 100 values the nearest-rank positions are 50 and 99 (`ceil(0.50 * 100)` and `ceil(0.99 * 100)`), and the maximum is position 100. The mean is above the median, as the text says it usually is. Note that p99 (7.975 ms) is the second-largest value of the hundred: one sample decides it.

**3. Check Little's law on the bench's own code.** The load run in `java-bench-walkthrough-2.md` (Try it yourself, step 1) printed, with 4 clients: `local-reuse` 11794 requests per second and p50 0.31 ms; `local-new` 811 requests per second and p50 4.90 ms. Little's law gives a mean of 4 / 11794 = 0.34 ms and 4 / 811 = 4.93 ms. Both are close to their medians, and the first is a bit above, as a mean should be.

**4. Turn curl readings into phases.** `java-report-logs-walkthrough-1.md` (Try it yourself) makes curl lines for a new and a reused connection and runs `./gradlew -q report --args=build/sample-curl.txt`. The writer's table said TLS dominated the median new connection (3.43 of 4.43 ms, 77 percent) and the transfer dominated the reused one (0.35 of 0.64 ms).

**5. Read an access log.** `java-report-logs-walkthrough-2.md` (Try it yourself) writes five log lines and runs `./gradlew -q logStats --args=build/mini-access.log`. Port 8443 shows `66.7` percent hits and a proxy-added time of 3.00 ms.

**6. Make an outage and measure it.** `java-probe-walkthrough.md` (Try it yourself, step 3) pauses SeatWave for 3 seconds with `kill -STOP` and `kill -CONT` while the probe runs; the writer's probe printed `outage: 2.831 s`.

## Mistakes people make

- **Quoting a number without its conditions.** "6,549 requests per second" means 8 clients, one core for the proxy, a Docker virtual machine, a Mac, one afternoon. Say so.
- **Comparing two runs from different days.** The machine's load changed.
- **Taking the mean of percentiles.** You cannot average p99 values from several runs; take the median run, as the bench does.
- **Optimizing the number you can see.** A setting that raises requests per second and doubles processor time per request is not a win; the row shows both.
- **Trusting a tail from a handful of samples.** Count the samples.
- **Forgetting that a measurement changes the system.** A `docker exec` reading adds a little load; a probe adds requests; a log line costs a write.
- **Hiding the failures.** A latency table without an error column can show a server that fails fast as a fast server.

## Check yourself

1. Eight clients, no errors, 2,000 requests per second. What is the mean latency, and why? (8 / 2000 = 4 ms, by Little's law: requests in flight equal throughput times mean latency.)
2. Why is a p99 from 8 samples not a tail? (With fewer than 100 values the nearest-rank p99 is the maximum.)
3. `curl` prints `connect=0.000000 tls=0.000000` for a request. What does it mean, and how many new connections did it open? (The step did not happen; the connection was reused; `connects=0`.)
4. `gzip-9` sends 2,351 bytes and `gzip-1` 2,453, but `gzip-9` costs 601.7 and `gzip-1` 99.5 ms of CPU per thousand requests. What do you conclude, and what do you say about its limit? (Level 9 saves 4 percent of the bytes for six times the CPU; this is one lab, one file, one core.)
5. Why does the access log's "proxy-added time" show `1.00` at p99 for every port? (The log has millisecond resolution; `1.00` is one tick, not a measured millisecond. The time also includes sending the answer to the client.)

## Further reading

- curl documentation: `--write-out` variables, https://curl.se/docs/manpage.html#-w
- Linux kernel documentation: control group v2 and `cpu.stat`, https://docs.kernel.org/admin-guide/cgroup-v2.html
- RFC 9112, HTTP/1.1 (connections and message framing), https://www.rfc-editor.org/rfc/rfc9112
- RFC 8446, TLS 1.3 (the handshake), https://www.rfc-editor.org/rfc/rfc8446
- W3C Server Timing, https://www.w3.org/TR/server-timing/
- NGINX documentation: `ngx_http_log_module` and the variables `$request_time` and `$upstream_response_time`, https://nginx.org/en/docs/http/ngx_http_log_module.html
