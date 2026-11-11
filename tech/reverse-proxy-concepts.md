# Reverse proxy concepts: TLS termination, compression, caching, buffering, forwarded headers, connection reuse and the sidecar

## What it is

A **proxy** is a program that stands between two others and passes messages along. A **forward proxy** stands in front of the clients: it makes requests on their behalf (a company proxy that employees' browsers use). A **reverse proxy** stands in front of a service: clients think it is the service, and it makes the real request to the service behind it. In this repository the clients are the fans (the tools of the lab), the service is SeatWave, and the reverse proxy is NGINX.

Because every request passes through it, a reverse proxy is a good place for jobs that every service needs. Each job in this note is explained from zero: ending TLS, compressing, caching, buffering, telling the service who the client was, and reusing connections. The note also covers the **sidecar**, a second proxy placed beside the service, and the price of all of this: one extra hop and one more part that can fail.

## Why this repository uses it

Module 9 puts this question to SeatWave: what does a proxy buy, and what does it cost? Each job maps to a lab in `nginx/seatwave/labs.conf`, so each can be measured alone (the README's tables, and `measuring-a-proxy.md`). The shortest answer, with the README's numbers: the proxy saves handshake CPU on SeatWave, cuts a 36,894-byte seat map to 2,453 bytes on the wire, keeps slow clients off SeatWave's 64 worker threads, and answers repeated requests for a static poster from its own memory; it costs about one more hop, and a single proxy is a single point of failure.

## Where it appears

| Job | Where in the repository |
|---|---|
| TLS termination | `nginx/seatwave/tls.conf`, `listen 8443 ssl;` in `production.conf` and `labs.conf` |
| HTTP/2 to clients | `http2 on;` in `production.conf` |
| Compression | `nginx/seatwave/gzip.conf`, `gzip_comp_level` lines |
| Caching | `proxy_cache_path` in `nginx/nginx.conf`; `proxy_cache*` in `production.conf` |
| Buffering | `nginx/seatwave/buffers.conf`; `proxy_buffering off;` in `labs.conf` and `production.conf` |
| Forwarded headers | `nginx/seatwave/proxy-headers.conf`; `forwarded.trustedProxies` in `config/backend-container.properties` |
| Connection reuse | `nginx/seatwave/upstreams.conf` (`keepalive`) |
| Timeouts | `nginx/seatwave/timeouts.conf` |
| Sidecar | `nginx/sidecar/nginx.conf`; `sidecar` in `compose.yaml`; lab port 20906 |
| Single point of failure | `scripts/spof.sh` |

## How it works underneath

Here is the path of one request through PRODUCTION (port 20900):

```
fan  --HTTPS, TLS 1.3, HTTP/2-->  proxy (NGINX, 10.209.9.10)  --plain HTTP/1.1, reused-->  SeatWave (10.209.9.20:8080)
                                   decrypts, looks in the cache,           answers at network speed
                                   adds forwarded headers,
                                   compresses, writes to the fan at the fan's speed
```

1. The fan opens a TCP connection and runs a TLS handshake **with the proxy**. The proxy holds the certificate.
2. The proxy reads the request, looks for it in its **cache**, and on a miss opens (or reuses) a connection to SeatWave. It adds headers that say who the client was.
3. SeatWave answers at network speed. The proxy **buffers** the answer in memory, so SeatWave is free at once.
4. The proxy **compresses** the answer if the fan accepts it and writes it to the fan at the fan's own speed.
5. It writes one log line (`nginx/nginx.conf`).

## Walkthrough of this repository's use

### TLS termination

**TLS** (Transport Layer Security) encrypts a connection and proves who is on the other end. At the start, client and server run a **handshake**: they agree on keys, and the server shows a **certificate**, a document signed by an authority the client trusts that says "this public key belongs to this name". The server proves it holds the matching **private key**. After that, every byte is encrypted.

**Termination** means the proxy is the server end of the fan's TLS connection. It decrypts, handles the request, and (in most labs) speaks plain HTTP to SeatWave over the private lab network. The expensive part, the handshake, then runs on a fast NGINX instead of on SeatWave's Java process. The README's table shows the handshake phase of a new connection: 8.47 ms straight to SeatWave's own HTTPS port, 1.41 ms through the proxy. (Those are the README's numbers; this note did not repeat the bench.)

The settings, quoted from `nginx/seatwave/tls.conf`:

```nginx
ssl_certificate /etc/nginx/tls/server-chain.pem;
ssl_certificate_key /etc/nginx/tls/server-key.pem;
# TLS 1.3 only, as SeatWave's own listener in Module 5: one round trip per new connection.
ssl_protocols TLSv1.3;
# Sessions are remembered for 10 minutes in 10 MB shared by all workers (about 40,000 sessions),
# so a returning client can resume without a full handshake.
ssl_session_cache shared:seatwave_tls:10m;
ssl_session_timeout 10m;
```

- **TLS 1.3 only.** The newest version needs one round trip to set up a connection. I confirmed an older client is refused: `curl --tls-max 1.2` fails with exit status 35.
- **Session resumption.** The proxy remembers sessions in `ssl_session_cache`. A client that reconnects with a saved session skips part of the handshake. I confirmed it with `openssl s_client`: the first connection printed `New, TLSv1.3` and the second `Reused, TLSv1.3`.
- **HTTP/2 to the fan** (`http2 on;` in `production.conf`). HTTP/2 lets one TLS connection carry many requests at once. The proxy speaks HTTP/1.1 to SeatWave.
- **The certificate is a TEST certificate** made by `./gradlew testCertificates` into `build/tls`, valid for 30 days (the README says so). Curl is told to trust the test authority with `--cacert build/tls/seatwave-test-ca.pem`. A real service would use a public authority.

Lab 20906 adds a second TLS link, from the proxy to the sidecar, and `proxy_ssl_verify on;` makes the proxy check the sidecar's certificate (see `nginx-config-walkthrough-2.md`).

### Compression

**Compression** makes the body smaller before it is sent. A client announces which kinds it understands with the request header `Accept-Encoding: gzip`. If the server compresses, it says so with `Content-Encoding: gzip`. Smaller bodies cross the network faster, at the price of CPU time to compress. JSON compresses well because it is repetitive.

The pieces, from `nginx/seatwave/gzip.conf` and `proxy-headers.conf`:

```nginx
gzip on;
gzip_types application/json application/problem+json text/javascript;
gzip_min_length 1000;
# Vary: Accept-Encoding tells every cache after the proxy that the body depends on that header.
gzip_vary on;
```

```nginx
proxy_set_header Accept-Encoding "";
```

- `gzip_types` limits compression to JSON and scripts. `gzip_min_length 1000` skips small bodies: under about 1,000 bytes a response already fits in one packet (Module 5's 1,460-byte segment), so there is no round trip to save.
- `gzip_vary on;` adds `Vary: Accept-Encoding`, which tells a cache downstream that the body depends on that request header.
- The last line removes the client's `Accept-Encoding` before the request goes to SeatWave. SeatWave never compresses. The proxy compresses **after** the cache, so one cached copy serves both clients that accept gzip and clients that do not.
- The level, 1 to 9, trades CPU for size. I measured the bytes on the wire for the 36,894-byte seat map: no gzip 36,894; level 1: 2,453; level 6: 2,419; level 9: 2,351. The README's bench shows level 9 uses about six times the proxy CPU of level 1 for 4 percent fewer bytes, so PRODUCTION uses level 1.

### Caching

A **cache** keeps a copy of an answer so the next request for the same thing is answered without asking SeatWave. The **cache key** says which requests count as "the same": here the scheme, host and path with query string (`proxy_cache_key "$scheme://$host$request_uri";`). The answer to a lookup is `MISS` (not there, asked SeatWave) or `HIT` (served from the copy).

What may be cached is decided by SeatWave, through the response header `Cache-Control`. NGINX, by default, does not store a response that says `no-cache`, `no-store` or `private`, or that sets a cookie. This repository adds protections that the test `NginxConfigRulesTest` guards:

- only `GET` and `HEAD` are ever answered from the cache (`proxy_cache_methods GET HEAD;`);
- a request with an `Authorization` header neither reads nor writes the cache (`proxy_no_cache` and `proxy_cache_bypass`);
- nothing overrides SeatWave's cache headers (`proxy_ignore_headers` is absent);
- a stale entry is never served when SeatWave fails (`proxy_cache_use_stale` is absent).

What I saw: the seat map says `Cache-Control: no-cache`, so two requests gave `MISS` and `MISS`. The poster path says `Cache-Control: public, max-age=300`, so two requests gave `MISS` then `HIT`:

```
x-cache-status: MISS
x-cache-status: HIT
```

**The cache lock.** When many requests arrive for an entry that is not there yet, `proxy_cache_lock on;` lets one go to SeatWave and makes the others wait for it. That is good for a popular poster. It is harmful for a path that is never cached, because every request then waits for the one before it. PRODUCTION turns it on only under `/static/`; lab 20914 turns it on everywhere to show the damage (README: 1,162 requests per second against 6,549).

### Buffering

A **slow client** is one that reads a response slowly (a phone on a bad connection). Without buffering, the server writes the response piece by piece at the client's pace, and the thread that serves the request stays busy until the last piece is sent. SeatWave has a fixed pool of 64 worker threads. 64 slow readers use them all, and a fast request has to wait.

With **buffering** on (`nginx/seatwave/buffers.conf`), the proxy reads SeatWave's whole answer at once, at network speed, which frees the SeatWave thread, and then writes it to the slow client at that client's pace. The proxy's memory, not SeatWave's threads, is what the slow client uses.

I ran the repository's slow-client lab with 500 slow clients each reading 50 KB/s of the seat map. It runs inside the lab network with `docker compose run --rm tools /app/config/slow-<variant>.properties`. Two runs of each:

| Target | SeatWave workers busy at most | Worker-seconds in all |
|---|---|---|
| Straight to SeatWave (`direct`) | 64 of 64, 64 of 64 | 106.20 and 107.04 |
| Through the proxy, buffering on (`buffered`) | 1 of 64, 0 of 64 (a first, cold run: 58 of 64) | 1.41 and 1.12 (cold run: 35.79) |
| Through the proxy, buffering off (`unbuffered`) | 1 of 64 | 0.62 |

The seat map is small enough (36,894 bytes) that even without proxy buffering it fits in the operating system's socket buffers, so the unbuffered row is also low. The README's 300,000-byte poster run is where buffering off shows a cost. The program that measures this is described in `java-slow-clients-walkthrough.md`.

One more setting protects the proxy from slow clients: `send_timeout 10s;` limits the gap between two writes. A slow client that keeps making progress is not cut off.

Streams are the exception. The long poll and the event stream of Module 8 must not be buffered, or an event would wait in the proxy. `production.conf` has `proxy_buffering off;` for that location.

### Forwarded headers

When the proxy calls SeatWave, SeatWave sees the proxy's address as the caller. To still know the real client, the proxy adds headers: `X-Forwarded-For` (the client's address), `X-Forwarded-Proto` (`http` or `https`), the standard `Forwarded`, and `X-Request-Id`.

```
proxy_set_header X-Forwarded-For $remote_addr;
```

This is the most important line of the module. A client can send an `X-Forwarded-For` header of its own, with any address in it. If SeatWave believed every such header, anyone could pretend to be anyone. So there are two rules, one on each side:

- **The proxy overwrites.** It replaces whatever the client sent with the address it really saw. (`$proxy_add_x_forwarded_for` would append and let a forged first entry survive; the test forbids it.)
- **SeatWave believes the header only from trusted peers.** `forwarded.trustedProxies=10.209.9.10,127.0.0.1` lists the proxy and the sidecar's loopback address. A request from any other peer has its forwarded headers ignored.

I tested both rules with the forged address `203.0.113.7`: sent straight to SeatWave, the answer was `{"address":"10.209.9.1","scheme":"http","source":"peer"}` (header ignored); through the proxy, `{"address":"10.209.9.1","scheme":"https","source":"forwarded"}` (header replaced). The address `10.209.9.1` is the address Docker Desktop's forwarder shows for a request from the Mac.

### Connection reuse

Opening a TCP connection costs a round trip (the handshake). A proxy that opened a new connection to SeatWave for every request would pay it every time. `keepalive 16;` in the `upstream` block lets each worker keep up to 16 idle connections to SeatWave and reuse them. Two settings go with it, both in `proxy-headers.conf`: `proxy_http_version 1.1;` (HTTP/1.1 allows keep-alive) and `proxy_set_header Connection "";` (do not send `Connection: close`).

An experiment I ran: wait until all idle connections have timed out, send ten requests through a lab, and count the established connections from the proxy (`10.209.9.10`) to SeatWave's port 8080, read from SeatWave's `/proc/net/tcp`:

| Lab | Connections left open after ten requests |
|---|---|
| 20901, upstream with reuse | 1 |
| 20904, upstream with `keepalive 0` | 0 |

The README's bench shows what it is worth: 299 requests per second without reuse against 4,333 with it (README's numbers).

The proxy closes idle upstream connections after 20 s, below the 30 s after which SeatWave's JDK server closes them, so the proxy never sends a request on a connection that SeatWave is closing.

### Timeouts

A proxy must decide how long to wait. `nginx/seatwave/timeouts.conf` sets 2 s to connect, 5 s to send and 2.5 s to read, from SeatWave's latency budget. A wait that is too short cuts off a good answer (lab 20905: a `504` after 2.5 s for a request that needs 25 s). A wait that is too long ties up the proxy. The long poll's location has its own 30 s.

### The sidecar

A **sidecar** is a helper program that runs next to the service it helps, in the same "pod", sharing its network. Here the sidecar is a second NGINX (`nginx/sidecar/nginx.conf`) started by Compose with `network_mode: "service:backend"`, so it lives in SeatWave's network namespace: same address, same loopback.

```
proxy --TLS 1.3, port 9443--> sidecar --plain HTTP, 127.0.0.1:8080--> SeatWave
         (the inside link is encrypted)    (the loopback cannot be reached from outside)
```

It shows three things without a service mesh. The link between the proxy and the host of SeatWave is encrypted. The hop from the sidecar to SeatWave uses an address nothing outside can reach. And the two share fate: if SeatWave's container stops, the sidecar loses its network too, which is why `scripts/spof.sh` restarts the sidecar after the backend. The sidecar adds a hop, so lab 20906 is slower than 20901 (README: 3.22 ms against 2.89 ms total for a new connection).

### The price: one more hop and a single point of failure

Every part in the path is another part that can fail. `scripts/spof.sh` probes `/health` through the proxy every 100 ms for 30 s and stops one container for 10 s. The outage the client saw, from my runs:

| What stopped | What the probe saw | Outage |
|---|---|---|
| The proxy | `connection refused` 103 times | 10.409 s |
| SeatWave | `request timeout` 11 times (the probe waits 1 s) | 11.013 s |
| Nothing (two `nginx -s reload`) | 300 answers, all `200` | none |

A reload is safe; a crash of the one proxy is not. Module 10 adds a second proxy and a shared address.

## Try it yourself

I ran these on 11 November 2026 against a running stack (project name `seatwave-proxy-notes`; start yours with `scripts/stack.sh up --run --yes`, stop it with `scripts/stack.sh down --run --yes`). Timings differ on every run.

1. Compare the three states of the cache:

```
for i in 1 2; do curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20900/static/seat-map.js | grep -i x-cache; done
for i in 1 2; do curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem 'https://127.0.0.1:20900/events/1/seats?section=A' | grep -i x-cache; done
```

Output: `x-cache-status: MISS` then `x-cache-status: HIT` for the first loop (if the file was already cached by an earlier run you see `HIT` twice), and `MISS` twice for the second.

2. Show that TLS sessions resume:

```
printf 'GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' | openssl s_client -connect 127.0.0.1:20901 -tls1_3 -CAfile build/tls/seatwave-test-ca.pem -sess_out /tmp/sess.pem -ign_eof 2>/dev/null | grep -E '^(New|Reused)'
printf 'GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' | openssl s_client -connect 127.0.0.1:20901 -tls1_3 -CAfile build/tls/seatwave-test-ca.pem -sess_in /tmp/sess.pem -ign_eof 2>/dev/null | grep -E '^(New|Reused)'
rm -f /tmp/sess.pem
```

Output:

```
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Reused, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
```

3. Count the connections the proxy holds open to SeatWave. Wait about 25 s after your last request first (the idle timeout is 20 s):

```
docker exec seatwave-proxy-notes-backend-1 awk 'NR>1 && $2 ~ /:1F90$/ && $3 ~ /^0A09D10A:/ && $4=="01"' /proc/net/tcp | wc -l
```

This prints the number of established connections on SeatWave's port 8080 (hex `1F90`) whose peer is `10.209.9.10` (hex `0A09D10A`, the bytes of the address in reverse) and whose state is `01`, "established". Send ten requests to `https://127.0.0.1:20901/health` and run it again: `1`. Do the same for `https://127.0.0.1:20904/health`: `0`.

4. Watch buffering protect the workers:

```
docker compose -p seatwave-proxy-notes run --rm tools /app/config/slow-direct.properties 2>&1 | sed -n '1p;3p'
docker compose -p seatwave-proxy-notes run --rm tools /app/config/slow-buffered.properties 2>&1 | sed -n '1p;3p'
```

Output (the numbers differ each time; each run takes a few seconds):

```
target: GET http://10.209.9.20:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
SeatWave workers: at most 64 of 64 busy at once (0 readings missed); 106.20 worker-seconds in all, 0.2124 s per slow client
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
SeatWave workers: at most 1 of 64 busy at once (0 readings missed); 1.41 worker-seconds in all, 0.0028 s per slow client
```

(My very first buffered run, the first time I used the `tools` service, showed `at most 58 of 64` and 35.79 worker-seconds. I did not find out why. Run each variant twice and compare the later runs.)

5. See the single point of failure (about 30 s):

```
scripts/spof.sh proxy --run --yes
```

Output has the `connection refused` row. See `shell-scripts-walkthrough.md` for what the script does.

## Mistakes people make

- **Trusting `X-Forwarded-For` from anyone.** A header is just text a client can write. Trust it only from peers you control, and overwrite it at the first proxy.
- **Appending to `X-Forwarded-For` at the first proxy.** The client's forged value stays first in the list.
- **Caching a response that depends on who asked.** A cache key without the user, or a cached `private` response, shows one user's data to another. Here SeatWave marks such answers `private` and the proxy never stores them; requests with `Authorization` bypass the cache.
- **Caching a `POST`.** Only `GET` and `HEAD`.
- **Compressing what is already small or already compressed.** `gzip_min_length` and the short `gzip_types` list avoid wasted CPU. Images and posters are not listed.
- **Buffering a stream.** Server-sent events and long polls must go through unbuffered.
- **A proxy keep-alive longer than the back end's.** The proxy then sends a request on a connection that SeatWave closes at that moment.
- **One timeout for everything.** A fast API and a 25 s long poll need different read timeouts.
- **Forgetting the proxy is now the single point of failure.** One proxy stopped means the whole service is down for the clients.

## Check yourself

1. What is TLS termination, and what does it save SeatWave?
   *The proxy is the server end of the client's TLS connection. It decrypts and speaks plain HTTP to SeatWave, so the handshake work runs in NGINX instead of in SeatWave's Java process.*
2. Why does the proxy remove `Accept-Encoding` before calling SeatWave?
   *SeatWave never compresses. The proxy compresses after the cache, so one cached copy serves clients with and without gzip.*
3. Why must the proxy overwrite `X-Forwarded-For` rather than append?
   *A client can send its own value. Appending would keep the forged entry first. Overwriting makes the value the address the proxy really saw.*
4. How does buffering protect SeatWave's 64 workers from slow clients?
   *The proxy reads the whole answer at network speed, which frees the worker, and then delivers it to the slow client at that client's pace.*
5. Why is the proxy's upstream `keepalive_timeout` (20 s) shorter than SeatWave's idle timeout (30 s)?
   *So the proxy closes first. Otherwise it could send a request on a connection that SeatWave is closing at that moment, and the request would fail.*

## Further reading

- NGINX, reverse proxy guide: https://nginx.org/en/docs/beginners_guide.html#proxy
- NGINX, `ngx_http_proxy_module` (buffering, cache, headers): https://nginx.org/en/docs/http/ngx_http_proxy_module.html
- NGINX, `ngx_http_ssl_module`: https://nginx.org/en/docs/http/ngx_http_ssl_module.html
- NGINX, `ngx_http_gzip_module`: https://nginx.org/en/docs/http/ngx_http_gzip_module.html
- HTTP caching (RFC 9111): https://www.rfc-editor.org/rfc/rfc9111
- HTTP semantics (RFC 9110): https://www.rfc-editor.org/rfc/rfc9110
- TLS 1.3 (RFC 8446): https://www.rfc-editor.org/rfc/rfc8446
- HTTP/2 (RFC 9113): https://www.rfc-editor.org/rfc/rfc9113
- The `Forwarded` header (RFC 7239): https://www.rfc-editor.org/rfc/rfc7239
- Problem details for HTTP APIs (RFC 9457): https://www.rfc-editor.org/rfc/rfc9457
