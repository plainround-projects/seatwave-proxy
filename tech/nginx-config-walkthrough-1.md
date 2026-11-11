# NGINX configuration walkthrough, part 1: the main file and the shared pieces

This is the first of two files that explain every line of the proxy's configuration. The file was split in two because it is long: part 1 covers `nginx/nginx.conf` and the nine shared files it pulls in, ending with PRODUCTION (`production.conf`). Part 2 (`nginx-config-walkthrough-2.md`) covers the lab variants (`labs.conf`), the status page (`status.conf`) and the sidecar (`nginx/sidecar/nginx.conf`). Read `nginx.md` first if directives, contexts and `include` are new to you.

## What it is

The edge proxy's configuration is a set of text files that NGINX 1.30.6 reads when it starts and when it is told to reload. `nginx/nginx.conf` is the entry point. It sets up the worker, the log, the limits and the cache, then includes the four files that define the servers. The other files hold small named pieces (TLS, gzip, buffering, timeouts, forwarded headers, error pages, the upstreams) that many servers share, so each lab variant differs from the others by only the lines that matter.

## Why this repository uses it

The lesson measures each proxy feature before and after. Splitting the configuration into one file per feature makes a lab variant a handful of `include` lines, and a reader can see exactly which feature a port has. The pieces are checked by `NginxConfigRulesTest` without Docker (`tests-stack-walkthrough.md`).

## Where it appears

| Path | What it does |
|---|---|
| `nginx/nginx.conf` | Main file: process settings, log format, client limits, cache path, the four server includes. |
| `nginx/seatwave/upstreams.conf` | The three groups of back-end servers: SeatWave with reuse, without reuse, and the sidecar. |
| `nginx/seatwave/tls.conf` | TLS termination settings, included by every `ssl` server. |
| `nginx/seatwave/gzip.conf` | Compression settings. |
| `nginx/seatwave/buffers.conf` | Response buffering settings. |
| `nginx/seatwave/errors.conf` | The proxy's own JSON error answers. |
| `nginx/seatwave/proxy-headers.conf` | The headers sent to SeatWave. |
| `nginx/seatwave/timeouts.conf` | The three proxy timeouts, from the latency budget. |
| `nginx/seatwave/production.conf` | The PRODUCTION server on container port 8443 (published as 20900). |
| `nginx/seatwave/labs.conf`, `status.conf` | Part 2. |

## How it works underneath

1. The master process reads `/etc/nginx/nginx.conf`. When it meets `include`, it reads that file at that point. The result is one long configuration (458 non-blank lines for the edge proxy, including `mime.types`).
2. Directives in the `http` context that are not inside a `server` apply to all servers. Directives in a `server` apply to its locations. A location may override them.
3. For each request NGINX picks a server by port (each server here has its own port), then a location by path, and runs that location's directives in a fixed internal order, whatever the order in the file.
4. Files are mounted read-only into the container (`docker-compose.md`), so the only way to change the proxy is to edit the repository's files and reload.

## Walkthrough of this repository's use

### `nginx/nginx.conf`: process, events, log

```nginx
# SeatWave's reverse proxy (Module 9). The image is nginx:1.30.6 (compose.yaml); every directive
# below was checked against the NGINX documentation at nginx.org/en/docs on 11 November 2026.
# Check the whole configuration before any reload with: nginx -t

# One worker for the one CPU compose.yaml gives the proxy, so every number is "per proxy core".
worker_processes 1;
error_log /dev/stderr notice;
pid /run/nginx.pid;

events {
    # Each proxied request holds two connections, one to the client and one to SeatWave.
    worker_connections 4096;
}
```

- Lines 1 to 3 are comments. The image is `nginx:1.30.6`. The author checked every directive against the NGINX documentation on 11 November 2026. `nginx -t` checks the whole configuration and starts nothing, so run it before any reload.
- Line 5 is a comment: one worker for the one CPU `compose.yaml` gives the proxy, so every number is "per proxy core".
- Line 6, `worker_processes 1;`: start one worker process. (The usual setting, `auto`, would start one per CPU of the machine.)
- Line 7, `error_log /dev/stderr notice;`: write NGINX's own messages (start-up notes and errors, not requests) to standard error, which Docker captures, so `docker logs <container>` shows them. `notice` is the lowest severity written: the levels, from least to most severe, include `info`, `notice`, `warn`, `error`. The default is `error`; `notice` adds lines like `signal process started` on a reload.
- Line 8, `pid /run/nginx.pid;`: the file where the master writes its process number. `/run` is the small tmpfs from `compose.yaml`.
- Lines 10 to 13: the `events` block. Line 11 is a comment: each proxied request holds two connections, one to the client and one to SeatWave. Line 12, `worker_connections 4096;`, is the most connections one worker may have open at once, clients and upstreams together. At most about 2,000 proxied requests can be in flight at once on this worker. (The slow-client lab opens 500 clients, which needs 1,000, far below the limit.)

### `nginx/nginx.conf`: the `http` block

```nginx
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    server_tokens off;

    # One line per request: the times split the proxy's share from SeatWave's (rt less urt), the
    # cache status gives the hit ratio, and $request_id is also sent to SeatWave as X-Request-Id.
    log_format seatwave 'time=$time_iso8601 port=$server_port status=$status us=$upstream_status '
                        'rt=$request_time urt=$upstream_response_time uct=$upstream_connect_time '
                        'cache=$upstream_cache_status bytes=$bytes_sent id=$request_id '
                        'client=$remote_addr method=$request_method uri=$uri';
    access_log /var/log/nginx/seatwave/access.log seatwave;
```

- Line 15 opens `http {`. Everything to the matching `}` on line 62 is HTTP configuration.
- Line 16, `include /etc/nginx/mime.types;`: a file that comes with the image and maps file name endings to content types (`.js` to `text/javascript`, for example). NGINX needs it to set `Content-Type` for files it serves itself. Here almost all answers come from SeatWave with their own content type.
- Line 17, `default_type application/octet-stream;`: the type for a file whose ending is not in that list ("unknown bytes").
- Line 18, `server_tokens off;`: do not put the NGINX version in the `Server` header or on error pages. The header reads just `server: nginx` (I saw it in the responses).
- Lines 20 to 21 are a comment: one line per request; the two times split the proxy's share from SeatWave's (`rt` less `urt`), the cache status gives the hit ratio, and `$request_id` is also sent to SeatWave as `X-Request-Id`.
- Lines 22 to 25 are one `log_format` directive: the name is `seatwave` and the rest is a format string made of four quoted pieces that NGINX joins. Each `name=$variable` pair writes the name and the value:
  - `time=$time_iso8601`: the local time when the line is written, in ISO 8601 format (the container's clock is UTC, hence `+00:00`).
  - `port=$server_port`: the container port the client connected to. In the log of the PRODUCTION server it reads `8443`, not 20900.
  - `status=$status`: the status the client received.
  - `us=$upstream_status`: the status SeatWave sent (`-` when SeatWave was not asked, as for a cached answer or an error the proxy made).
  - `rt=$request_time`: seconds from the first byte read from the client to the last byte sent, to the millisecond.
  - `urt=$upstream_response_time`: seconds SeatWave took to answer. `rt` minus `urt` is the proxy's own share, which `logStats` reports as "proxy-added".
  - `uct=$upstream_connect_time`: seconds to connect to SeatWave. It is near zero when the connection was reused.
  - `cache=$upstream_cache_status`: `MISS`, `HIT`, `BYPASS`, `EXPIRED`, `STALE`, `UPDATING`, `REVALIDATED`, or `-` when no cache is used on this server.
  - `bytes=$bytes_sent`: bytes sent to the client, headers included.
  - `id=$request_id`: the random request identifier.
  - `client=$remote_addr`: the address of the peer that connected to the proxy.
  - `method=$request_method` and `uri=$uri`: the HTTP method and the path (without the query string, and with URL escapes decoded).
- Line 26, `access_log /var/log/nginx/seatwave/access.log seatwave;`: write one line per request in that format to that file. The folder is a tmpfs from `compose.yaml`.

A line I captured:

```
time=2026-11-11T05:40:14+00:00 port=8443 status=200 us=200 rt=0.001 urt=0.001 uct=0.000 cache=MISS bytes=138 id=f03fce28c49cbcb2f51747ce909eff11 client=10.209.9.1 method=GET uri=/health
```

### `nginx/nginx.conf`: connection and limit settings

```nginx
    sendfile on;
    tcp_nodelay on;
    # A client's idle keep-alive connection is kept 60 s: longer than the JDK client's 30 s
    # (Module 5), so the client, not the proxy, decides when to close.
    keepalive_timeout 60s;

    # Limits on what a client may send (failure mode 3). Module 7's body limit is 1,024 bytes;
    # the proxy allows 4 KB, so SeatWave still answers 1,025 to 4,096 bytes with its own problem
    # details, and anything larger is refused here before it costs SeatWave anything.
    client_max_body_size 4k;
    client_header_buffer_size 1k;
    large_client_header_buffers 4 8k;
    # A client that sends its request slower than this is cut off: the proxy, not SeatWave, waits.
    client_header_timeout 10s;
    client_body_timeout 10s;
    # Between two writes to a slow client; reset by every write, so a slow download still finishes.
    send_timeout 10s;
```

- Line 28, `sendfile on;`: when NGINX sends a file (for example a cached response) it asks the operating system to copy it from disk to the network directly, without passing the bytes through NGINX's own memory.
- Line 29, `tcp_nodelay on;`: turn off **Nagle's algorithm** on connections that stay open. That algorithm delays small writes to join them into bigger packets. The delay hurts request-and-response traffic. (This is the proxy-side twin of the `Dockerfile`'s `nodelay` option for SeatWave.)
- Lines 30 to 31 are a comment, and line 32, `keepalive_timeout 60s;`, is how long NGINX keeps an idle connection from a client open before closing it. 60 s is longer than the 30 s of the Java client from Module 5, so the client, not the proxy, decides when to close. Do not confuse this with the `keepalive_timeout 20s;` inside the `upstream` block (`upstreams.conf`), which is about connections to SeatWave.
- Lines 34 to 36 are a comment about limits on what a client may send: Module 7's body limit is 1,024 bytes; the proxy allows 4 KB, so SeatWave still answers 1,025 to 4,096 bytes with its own error, and anything larger is refused here before it costs SeatWave anything.
- Line 37, `client_max_body_size 4k;`: the largest request body allowed. NGINX compares it with the `Content-Length` header. A larger body gets status `413` at once. The default is `1m`. I sent 5,000 bytes: the answer was `413` from the proxy.
- Line 38, `client_header_buffer_size 1k;`, and line 39, `large_client_header_buffers 4 8k;`: the memory for reading request headers. A request line and headers must fit in one 1 KB buffer; if they do not, NGINX uses up to four buffers of 8 KB. A request line that is still too long is refused with `414`; a header field that does not fit gets `400`. These two lines write down the defaults, so the limits are a visible decision.
- Line 40 is a comment. Lines 41 and 42, `client_header_timeout 10s;` and `client_body_timeout 10s;`: how long NGINX waits between two reads while a client sends the headers or the body. If the client is slower, the request ends with `408`. The proxy waits, not SeatWave. The default of both is 60 s.
- Line 43 is a comment, and line 44, `send_timeout 10s;`, is the time between two writes to a slow client. The timer is reset by every write, so a slow download that keeps making progress still finishes. This is why the 50 KB/s readers of the slow-client lab complete (`reverse-proxy-concepts.md`).

### `nginx/nginx.conf`: the cache and the includes

```nginx
    # The static-file cache: keys in 10 MB of shared memory (about 80,000 keys), files on disk up
    # to 100 MB, and anything unused for 10 minutes is removed.
    proxy_cache_path /var/cache/nginx/seatwave levels=1:2 keys_zone=seatwave_static:10m
                     max_size=100m inactive=10m use_temp_path=off;

    # The client's address as RFC 7239 writes a node in Forwarded (seatwave/proxy-headers.conf): an
    # IPv6 address in brackets, so its colons cannot be read as a port; an IPv4 address as it is.
    map $remote_addr $forwarded_node {
        ~:      "[$remote_addr]";
        default $remote_addr;
    }

    include seatwave/upstreams.conf;
    include seatwave/production.conf;
    include seatwave/labs.conf;
    include seatwave/status.conf;
}
```

- Lines 46 to 47 are a comment: keys in 10 MB of shared memory (about 80,000 keys), files on disk up to 100 MB, anything unused for 10 minutes removed.
- Lines 48 to 49 are one `proxy_cache_path` directive. It only describes where the cache lives and how it is organized; a server turns it on with `proxy_cache seatwave_static;`.
  - `/var/cache/nginx/seatwave`: the folder for cached files (a tmpfs of 300 MB, `compose.yaml`).
  - `levels=1:2`: store files in two levels of sub-folders named from the last characters of the cache key's hash, so one folder does not hold millions of files. I looked at the folder: `6/fa/7e271d2e83b6117715397d335f486fa6`. The file name is the MD5 of the key; `6` is its last character and `fa` the two before it.
  - `keys_zone=seatwave_static:10m`: a shared-memory area named `seatwave_static`, 10 MB, that holds the keys and the metadata of the cached files (about 8,000 keys per megabyte, so 80,000). Shared memory is visible to every worker. A server refers to the zone by this name.
  - `max_size=100m`: when the cached files exceed this, the **cache manager** process removes the least recently used ones.
  - `inactive=10m`: a file not asked for in 10 minutes is removed, whatever its freshness.
  - `use_temp_path=off`: write a new cache file directly into the cache folder instead of first into a temporary folder and then moving it.
- Lines 51 to 52 are a comment, and lines 53 to 56 are a `map` block. A **map** makes a new variable whose value depends on another one, looked up for each request: `map $remote_addr $forwarded_node { ... }` sets `$forwarded_node` from the client's address. Each line inside is a pattern and a value. `~:` is a regular expression (the `~`) that matches any text containing a colon, which only an IPv6 address has; its value is `"[$remote_addr]"`, the address in square brackets. `default $remote_addr;` is the value when no pattern matched: an IPv4 address as it is. RFC 7239 requires the brackets in the `Forwarded` header, because an IPv6 address is full of colons and a node may end in `:port`; without them a reader cannot tell where the address ends. The lab network is IPv4, so the brackets were never needed here; the review pass checked the map in a throwaway NGINX inside the proxy's container, fed the address through a header because the containers have no IPv6, and it printed `for="[2001:db8::17]"` and `for="203.0.113.7"`. A `map` must sit in the `http` block, which is why it is here and not in `proxy-headers.conf`.
- Lines 58 to 61 include the four server files: `upstreams.conf` (a block that is not a server), then `production.conf`, `labs.conf` and `status.conf`. Line 62 closes `http`.

### `nginx/seatwave/upstreams.conf`

```nginx
# SeatWave behind the proxy. The address is fixed in compose.yaml, so it is also the one address
# SeatWave trusts forwarded headers from (config/backend-container.properties).
upstream seatwave {
    server 10.209.9.20:8080;
    # Up to 16 idle connections to SeatWave are kept per worker and reused, so a request through
    # the proxy pays no new TCP handshake to the upstream. (NGINX 1.29.7 and later reuse 32 by
    # default; the number and the timeout below are written out so they are a decision.)
    keepalive 16;
    # Below the 30 s after which the JDK's HTTP server closes an idle connection: the proxy must
    # close first, or it can send a request on a connection SeatWave is closing at that moment.
    keepalive_timeout 20s;
    keepalive_requests 1000;
}
```

- Lines 1 to 2 are a comment: the address is fixed in `compose.yaml`, so it is also the one address SeatWave trusts forwarded headers from (`config/backend-container.properties`).
- Line 3, `upstream seatwave {`: a named group of servers. `proxy_pass http://seatwave;` uses this name.
- Line 4, `server 10.209.9.20:8080;`: one member: SeatWave's address and plain-HTTP port. With one member there is no load balancing.
- Lines 5 to 7 are a comment, and line 8, `keepalive 16;`: each worker may keep up to 16 idle connections to SeatWave and reuse them, so a request pays no new TCP handshake to SeatWave. (The comment says NGINX 1.29.7 and later reuse 32 by default; the number is written out so it is a decision. I checked the default behavior: a throwaway upstream with no `keepalive` line kept one connection to SeatWave open after ten requests, and an upstream with `keepalive 0` kept none.)
- Lines 9 to 10 are a comment, and line 11, `keepalive_timeout 20s;`: close an idle upstream connection after 20 s. The JDK's HTTP server closes an idle connection after 30 s. If the proxy waited longer, it could send a request on a connection SeatWave is closing at that moment, and the request would fail. So the proxy must close first. `NginxConfigRulesTest` checks that every such value is below 30.
- Line 12, `keepalive_requests 1000;`: close a connection after it has served 1,000 requests.

```nginx
# The same SeatWave without connection reuse: every request opens a new connection (lab only).
# Since NGINX 1.29.7 an upstream reuses connections by default (keepalive 32), so turning reuse
# off takes an explicit zero.
upstream seatwave_no_reuse {
    server 10.209.9.20:8080;
    keepalive 0;
}

# The sidecar beside SeatWave (Chapter 9.3): reached over TLS, so the inside link is encrypted.
upstream seatwave_sidecar {
    server 10.209.9.20:9443;
    keepalive 16;
    keepalive_timeout 20s;
}
```

- Lines 15 to 17 are a comment: the same SeatWave without connection reuse; every request opens a new connection (lab only). Since NGINX 1.29.7 an upstream reuses connections by default, so turning reuse off takes an explicit zero.
- Lines 18 to 21: `upstream seatwave_no_reuse` points at the same address with `keepalive 0;`. Lab 20904 uses it (`labs.conf`).
- Line 23 is a comment, and lines 24 to 28 define `upstream seatwave_sidecar`: the sidecar listens on port 9443 inside SeatWave's namespace, so its address is `10.209.9.20:9443`. It is reached over TLS, so the inside link is encrypted. It keeps 16 idle connections and closes them after 20 s.

### `nginx/seatwave/tls.conf`

```nginx
# TLS termination: the proxy holds the TEST certificate and its key, made by
# ./gradlew testCertificates into build/tls and mounted read-only (compose.yaml).
ssl_certificate /etc/nginx/tls/server-chain.pem;
ssl_certificate_key /etc/nginx/tls/server-key.pem;
# TLS 1.3 only, as SeatWave's own listener in Module 5: one round trip per new connection.
ssl_protocols TLSv1.3;
# Sessions are remembered for 10 minutes in 10 MB shared by all workers (about 40,000 sessions),
# so a returning client can resume without a full handshake.
ssl_session_cache shared:seatwave_tls:10m;
ssl_session_timeout 10m;
```

- Lines 1 to 2 are a comment: TLS termination means the proxy holds the TEST certificate and its key (made by `./gradlew testCertificates` into `build/tls`, mounted read-only). A **certificate** is a signed statement "this key belongs to this name"; the **private key** proves ownership. See `reverse-proxy-concepts.md`.
- Line 3, `ssl_certificate /etc/nginx/tls/server-chain.pem;`: the certificate the proxy shows to clients. "Chain" means the server certificate followed by the authority that signed it.
- Line 4, `ssl_certificate_key /etc/nginx/tls/server-key.pem;`: its private key. It stays in the proxy.
- Line 5 is a comment, and line 6, `ssl_protocols TLSv1.3;`: accept TLS 1.3 only. A client that offers at most TLS 1.2 is refused: I ran `curl --tls-max 1.2` and curl exited with status 35 (the error code for a TLS handshake failure) and printed `000` for the HTTP status.
- Lines 7 to 8 are a comment, and line 9, `ssl_session_cache shared:seatwave_tls:10m;`: remember TLS sessions in 10 MB of memory shared by the workers (about 4,000 sessions per megabyte, so about 40,000). A client that comes back with a remembered session **resumes** it and skips part of the handshake. I confirmed it: `openssl s_client` with a saved session printed `Reused, TLSv1.3` on the second connection.
- Line 10, `ssl_session_timeout 10m;`: a remembered session is valid for ten minutes (the default is five).

### `nginx/seatwave/gzip.conf`

```nginx
# Compression of JSON and scripts. Below 1,000 bytes a response already fits in one packet
# (Module 5's 1,460-byte segment), so compressing it saves no round trip and costs CPU.
gzip on;
gzip_types application/json application/problem+json text/javascript;
gzip_min_length 1000;
# Vary: Accept-Encoding tells every cache after the proxy that the body depends on that header.
gzip_vary on;
```

- Lines 1 to 2 are a comment: compression of JSON and scripts. Below 1,000 bytes a response already fits in one packet (Module 5's 1,460-byte segment), so compressing it saves no round trip and costs CPU.
- Line 3, `gzip on;`: compress responses when the client says it accepts gzip (its `Accept-Encoding` request header contains `gzip`).
- Line 4, `gzip_types application/json application/problem+json text/javascript;`: which content types to compress, besides `text/html`, which is always on. Images, posters and other binary types are left alone.
- Line 5, `gzip_min_length 1000;`: do not compress a response shorter than 1,000 bytes (read from its `Content-Length`). I checked `/health` (15 bytes): no `content-encoding` header, even though I sent `Accept-Encoding: gzip`.
- Line 6 is a comment, and line 7, `gzip_vary on;`: add the header `Vary: Accept-Encoding` to responses, so any cache after the proxy knows the body depends on that header.

The level is set per server: `gzip_comp_level 1;` in PRODUCTION, and 1, 6 and 9 in the three gzip labs. The level runs from 1 (fastest, biggest output) to 9 (slowest, smallest); the README's bench table shows the CPU cost of 9.

### `nginx/seatwave/buffers.conf`

```nginx
# Buffering (on by default): the proxy reads SeatWave's whole response as fast as SeatWave sends
# it and writes it to the client at the client's speed, so a slow phone never holds a SeatWave
# worker. 8k holds the response headers; 16 buffers of 8k hold 128 KB of body in memory, and any
# more goes to a temporary file instead of back-pressure on SeatWave.
proxy_buffering on;
proxy_buffer_size 8k;
proxy_buffers 16 8k;
proxy_busy_buffers_size 16k;
```

- Lines 1 to 4 are a comment: buffering is on by default. The proxy reads SeatWave's whole response as fast as SeatWave sends it and writes it to the client at the client's speed, so a slow phone never holds a SeatWave worker. 8k holds the response headers; 16 buffers of 8k hold 128 KB of body in memory; any more goes to a temporary file instead of back-pressure on SeatWave.
- Line 5, `proxy_buffering on;`: the setting itself. The lab at port 20903 turns it off to show the difference.
- Line 6, `proxy_buffer_size 8k;`: the size of the first buffer, which holds the status line and headers of the reply.
- Line 7, `proxy_buffers 16 8k;`: 16 buffers of 8 KB per connection for the body. `16 * 8 KB = 128 KB`, which is more than the 36,894-byte seat map.
- Line 8, `proxy_busy_buffers_size 16k;`: how much of the buffered data may be "busy", that is being sent to the client, while the proxy keeps reading from SeatWave.

### `nginx/seatwave/errors.conf`

```nginx
# The proxy's own refusals in SeatWave's one error shape, problem details (RFC 9457, Module 7),
# instead of NGINX's HTML pages, so a client acts on them the same way.
error_page 413 = @body_too_large;
error_page 502 = @bad_gateway;
error_page 504 = @gateway_timeout;

location @body_too_large {
    default_type application/problem+json;
    add_header Cache-Control no-store always;
    return 413 '{"type":"https://seatwave.example/problems/body-too-large","title":"The body is over the proxy limit","status":413}';
}

location @bad_gateway {
    default_type application/problem+json;
    add_header Cache-Control no-store always;
    add_header Retry-After 1 always;
    return 502 '{"type":"https://seatwave.example/problems/upstream-unavailable","title":"SeatWave did not answer the proxy; retry","status":502}';
}

location @gateway_timeout {
    default_type application/problem+json;
    add_header Cache-Control no-store always;
    return 504 '{"type":"https://seatwave.example/problems/upstream-timeout","title":"SeatWave did not answer in time","status":504}';
}
```

- Lines 1 to 2 are a comment: the proxy's own refusals use SeatWave's one error shape, problem details (RFC 9457, Module 7), instead of NGINX's HTML pages, so a client acts on them the same way.
- Lines 3 to 5: `error_page <status> = @<location>;` three times. When NGINX is about to answer with that status (its own `413` for a body over the limit, or `502` or `504` when SeatWave cannot be reached or answers too late), it runs the named location instead. The `=` with no number keeps the status the named location sets.
- Lines 7 to 11: `location @body_too_large { ... }`. A location whose name starts with `@` is reached only through `error_page`.
  - `default_type application/problem+json;`: the content type of the answer.
  - `add_header Cache-Control no-store always;`: add that header. `always` adds it for every status; without `always`, `add_header` works only for a few success and redirect statuses. `no-store` tells every cache not to keep the answer.
  - `return 413 '{...}';`: answer with status 413 and this text as the body, at once. The JSON has `type`, `title` and `status`.
- Lines 13 to 18 are the `502 Bad Gateway` answer. It adds `Retry-After 1`, which tells a client to retry after one second. I produced it by pointing a throwaway server at a closed port and got `HTTP/1.1 502 Bad Gateway`, `Retry-After: 1` and the JSON body.
- Lines 20 to 24 are the `504` answer for an upstream that did not answer in time. It has no `Retry-After`.

Real answer to a 5,000-byte body on the PRODUCTION port (the `Date` header is removed):

```
HTTP/2 413
server: nginx
content-type: application/problem+json
content-length: 115
cache-control: no-store

{"type":"https://seatwave.example/problems/body-too-large","title":"The body is over the proxy limit","status":413}
```

### `nginx/seatwave/proxy-headers.conf`

```nginx
# Included in every location that proxies to SeatWave. NGINX inherits proxy_set_header from the
# level above only when a location sets none of its own, so one extra proxy_set_header in a
# location would silently drop all of these: hence one file, included everywhere.
proxy_http_version 1.1;
# An empty Connection header lets the upstream connection stay open for reuse (keepalive above).
proxy_set_header Connection "";
proxy_set_header Host $host;
# Overwritten, never appended: this proxy is the first trusted hop, so whatever X-Forwarded-For a
# client sent is dropped and replaced with the address the proxy really saw.
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $scheme;
# RFC 7239's standard form of the same facts. The node is always quoted, and an IPv6 address is
# also put in brackets by the map in nginx.conf, as the RFC requires (the lab network is IPv4).
proxy_set_header Forwarded "for=\"$forwarded_node\";proto=$scheme";
proxy_set_header X-Request-Id $request_id;
# SeatWave never compresses; the proxy does, once, after the cache, so one cached copy serves
# clients that accept gzip and clients that do not.
proxy_set_header Accept-Encoding "";
```

- Lines 1 to 3 are a comment: it is included in every location that proxies to SeatWave, because one extra `proxy_set_header` in a location would silently drop all of these (the inheritance rule in `nginx.md`).
- Line 4, `proxy_http_version 1.1;`: speak HTTP/1.1 to SeatWave. Version 1.1 allows keep-alive (connection reuse). NGINX's older default was 1.0.
- Line 5 is a comment, and line 6, `proxy_set_header Connection "";`: NGINX normally sets `Connection: close` on the proxied request. An empty value removes the header, so the connection stays open for reuse.
- Line 7, `proxy_set_header Host $host;`: pass on the host name the client asked for. The default would be the upstream's name.
- Lines 8 to 9 are a comment, and line 10, `proxy_set_header X-Forwarded-For $remote_addr;`: tell SeatWave the client's address. The value is **overwritten, never appended**. Whatever `X-Forwarded-For` a client sent is dropped and replaced with the address the proxy really saw. (The usual shortcut, `$proxy_add_x_forwarded_for`, appends and so would let a client forge a first entry. `NginxConfigRulesTest` fails if that variable appears.)
- Line 11, `proxy_set_header X-Forwarded-Proto $scheme;`: whether the client used `http` or `https`, since SeatWave itself sees plain HTTP.
- Lines 12 to 13 are a comment, and line 14, `proxy_set_header Forwarded "for=\"$forwarded_node\";proto=$scheme";`: RFC 7239's standard header with the same two facts. Inside an NGINX string in double quotes, `\"` is a double quote that belongs to the value, so SeatWave receives `for="10.209.9.1";proto=https`. The quotes are allowed for every address and required for an IPv6 one, which `$forwarded_node` (the `map` in `nginx.conf`) also puts in brackets: `for="[2001:db8::17]"`. The first version wrote `for=$remote_addr` without quotes or brackets, which is wrong for an IPv6 client; SeatWave also refuses an address it would have to guess at (`java-forwarded-walkthrough.md`).
- Line 15, `proxy_set_header X-Request-Id $request_id;`: the request's random identifier, so a line in the proxy's log and a line in SeatWave's log can be matched.
- Lines 16 to 17 are a comment, and line 18, `proxy_set_header Accept-Encoding "";`: remove the client's `Accept-Encoding` before asking SeatWave. SeatWave never compresses; the proxy compresses, once, after the cache, so one cached copy serves clients that accept gzip and clients that do not.

Proof that the forged header is replaced: the same request with a forged `X-Forwarded-For: 203.0.113.7` gave, straight to SeatWave, `{"address":"10.209.9.1","scheme":"http","source":"peer"}` and, through the proxy, `{"address":"10.209.9.1","scheme":"https","source":"forwarded"}`. The forged address appears in neither.

### `nginx/seatwave/timeouts.conf`

```nginx
# Timeouts from SeatWave's latency budget (Module 9, Section 6e).
# Connecting inside one data center takes about 0.5 ms (Module 3), but a lost SYN is sent again
# only after 1 s (TCP's first retransmission timeout, RFC 6298), so a connect timeout of 1 s turns
# one dropped packet into a failed request. 2 s covers one retransmission and no more.
proxy_connect_timeout 2s;
proxy_send_timeout 5s;
# SeatWave's slowest ordinary reply is the confirm: 250 ms to connect to the payment provider and
# 1,500 ms for its answer (Module 7), inside Module 2's 2,000 ms confirm budget. The proxy waits a
# little longer than that and no more, so it never cuts off a confirm that SeatWave will finish.
proxy_read_timeout 2500ms;
```

- Lines 1 to 4 are a comment: the numbers come from SeatWave's latency budget. A connection inside one data center takes about 0.5 ms (Module 3), but a lost first packet (a SYN, the "may we connect" message) is sent again only after 1 s (TCP's first retransmission timeout, RFC 6298). A connect timeout of 1 s would turn one dropped packet into a failed request. 2 s covers one retransmission and no more.
- Line 5, `proxy_connect_timeout 2s;`: the longest to wait while connecting to SeatWave. The default is 60 s.
- Line 6, `proxy_send_timeout 5s;`: the longest between two writes of the request to SeatWave.
- Lines 7 to 9 are a comment: SeatWave's slowest ordinary reply is the confirm, 250 ms to connect to the payment provider plus 1,500 ms for its answer (Module 7), inside Module 2's 2,000 ms budget. The proxy waits a little longer and no more.
- Line 10, `proxy_read_timeout 2500ms;`: the longest between two reads of the reply from SeatWave (not the total time; each piece resets it). If SeatWave is silent for longer, NGINX gives up and answers `504`.

### `nginx/seatwave/production.conf`: the server

```nginx
# PRODUCTION: TLS termination, HTTP/2 to clients, gzip, the static-file cache, buffering, reused
# upstream connections, forwarded headers and timeouts from the budget. Published on 20900.
server {
    listen 8443 ssl;
    http2 on;
    server_name localhost;

    include seatwave/tls.conf;
    include seatwave/gzip.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    gzip_comp_level 1;
```

- Lines 1 to 2 are a comment: PRODUCTION means TLS termination, HTTP/2 to clients, gzip, the static-file cache, buffering, reused upstream connections, forwarded headers and timeouts from the budget. Published on 20900.
- Line 3, `server {`, opens a virtual server.
- Line 4, `listen 8443 ssl;`: accept connections on port 8443 (on every IPv4 address of the container) and speak TLS on them. Docker publishes it as `127.0.0.1:20900`.
- Line 5, `http2 on;`: allow HTTP/2 on this server. The client and the server agree on the protocol during the TLS handshake. I checked: `curl` reports version `2` on port 20900 and `1.1` on port 20901, which has no `http2` line.
- Line 6, `server_name localhost;`: the name this server answers to. It decides nothing here because the port is unique.
- Lines 8 to 11: four `include` lines paste in TLS, gzip, error pages and buffering.
- Line 12, `gzip_comp_level 1;`: the compression level for this server (fast, smaller gain).

```nginx
    # The cache is on for the whole server, and it only keeps what SeatWave allows: a response
    # that says private, no-store or no-cache, or sets a cookie, is never stored, and only GET
    # and HEAD are ever answered from it. A request with credentials is neither stored nor served.
    proxy_cache seatwave_static;
    proxy_cache_methods GET HEAD;
    proxy_cache_key "$scheme://$host$request_uri";
    proxy_no_cache $http_authorization;
    proxy_cache_bypass $http_authorization;
    add_header X-Cache-Status $upstream_cache_status always;

    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
```

- Lines 14 to 16 are a comment: the cache is on for the whole server, and it only keeps what SeatWave allows. A response that says `private`, `no-store` or `no-cache`, or sets a cookie, is never stored. Only `GET` and `HEAD` are answered from it. A request with credentials is neither stored nor served.
- Line 17, `proxy_cache seatwave_static;`: use the cache zone declared in `nginx.conf`.
- Line 18, `proxy_cache_methods GET HEAD;`: only these methods can be cached. (`GET` and `HEAD` are the defaults. A `POST` is never in the list, and `NginxConfigRulesTest` fails if one is added.)
- Line 19, `proxy_cache_key "$scheme://$host$request_uri";`: two requests are "the same" when scheme, host and the path with its query string are the same. A query string `?section=A` and `?section=B` are two entries.
- Line 20, `proxy_no_cache $http_authorization;`: do not **save** a response if this value is not empty. `$http_authorization` is the request's `Authorization` header, so a response to a request with credentials is never stored.
- Line 21, `proxy_cache_bypass $http_authorization;`: do not **serve** from the cache if this value is not empty. With line 20, a request with credentials neither reads nor writes the cache.
- Line 22, `add_header X-Cache-Status $upstream_cache_status always;`: put the cache result into a response header so a test (and you) can see `MISS` or `HIT`.
- Lines 24 to 28: `location /`: every path that no other location claims. It includes the forwarded headers and the timeouts, then `proxy_pass http://seatwave;` sends the request to the upstream. There is no `proxy_cache_valid` here, so only what SeatWave allows by its own headers can be cached. The seat map says `Cache-Control: no-cache`, so it stays `MISS`; I asked twice and got `MISS` and `MISS`.

```nginx
    # Posters and scripts. Their lifetime is SeatWave's own Cache-Control: public, max-age=300,
    # which NGINX obeys; no proxy_cache_valid is set, so nothing here can keep a file longer.
    location /static/ {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        # One request fills a missing entry while the others wait for it, instead of all asking
        # SeatWave. Only here: on a path SeatWave never lets the proxy cache, such as the seat
        # map, every request would wait for the one before it (Section 9 measures it).
        proxy_cache_lock on;
        proxy_pass http://seatwave;
    }
```

- Lines 30 to 31 are a comment: the lifetime of a poster or script is SeatWave's own `Cache-Control: public, max-age=300`, which NGINX obeys, and no `proxy_cache_valid` is set, so the proxy cannot keep a file longer than SeatWave allows. (`proxy_cache_valid` gives a lifetime to replies that carry none. The first version set `proxy_cache_valid 200 300s;` here as a fallback, but SeatWave always sends its header, and a header's lifetime takes priority, so the line never did anything and was removed.)
- Line 32, `location /static/ {`: every path that starts with `/static/`.
- Lines 35 to 37 are a comment, and line 38, `proxy_cache_lock on;`: when many requests arrive for a missing entry, one goes to SeatWave and the others wait for the answer, instead of all asking SeatWave. It is only here: on a path SeatWave never lets the proxy cache, every request would wait for the one before it. Lab 20914 sets it for the whole server to show that cost.

```nginx
    location ~ ^/v1/events/[0-9]+/seat-changes {
        include seatwave/proxy-headers.conf;
        proxy_connect_timeout 2s;
        proxy_send_timeout 5s;
        # Module 8's long poll is held 25 s and its stream sends a heartbeat every 15 s, so the
        # read timeout sits above both, and the stream is not buffered, so no event waits here.
        proxy_read_timeout 30s;
        proxy_buffering off;
        proxy_pass http://seatwave;
    }
}
```

- Line 42, `location ~ ^/v1/events/[0-9]+/seat-changes {`: a regular expression. `~` means case-sensitive; `^` the start of the path; `[0-9]+` one or more digits (an event number). It matches the long poll and the event stream of Module 8.
- Lines 43 to 45: the forwarded headers, then the timeouts set here by hand instead of including `timeouts.conf`: connect 2 s, send 5 s.
- Lines 46 to 47 are a comment: the long poll is held 25 s and the stream sends a heartbeat every 15 s, so `proxy_read_timeout 30s;` (line 48) sits above both. With the 2.5 s of `timeouts.conf` the proxy would cut the poll off (lab 20905).
- Line 49, `proxy_buffering off;`: pass each piece to the client as it arrives, so an event never waits in a buffer.
- This location sets no cache directive of its own, so it inherits the server's `proxy_cache seatwave_static;` and `add_header X-Cache-Status ...` (lines 17 and 22). Nothing is kept, because SeatWave answers both the long poll and the stream with `Cache-Control: no-store`, and NGINX does not cache a reply it reads unbuffered. The review pass checked it on the running stack: the long poll's answer carried `cache-control: no-store` and `x-cache-status: MISS`, and so did the stream's.
- Line 50, `proxy_pass http://seatwave;`, and the closing braces on lines 51 and 52 end the location and the server.

## Try it yourself

I ran these against a running stack on 11 November 2026 (project name `seatwave-proxy-notes`; start yours with `scripts/stack.sh up --run --yes`). Timings differ on every run; the header names and the status values are what to look for. The test CA is `build/tls/seatwave-test-ca.pem`.

1. Compression. First without the header, then with it (the `Date` line is removed):

```
curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem 'https://127.0.0.1:20900/events/1/seats?section=A' | grep -v -i '^date'
curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem -H 'Accept-Encoding: gzip' 'https://127.0.0.1:20900/events/1/seats?section=A' | grep -v -i '^date'
```

The first prints `content-length: 36894` and no `content-encoding`. The second prints no `content-length` (the length is not known when streaming starts) and `content-encoding: gzip`. Both print `x-cache-status: MISS` and `vary: Accept-Encoding`.

2. The static cache. The same file twice:

```
for i in 1 2; do curl -s -D - -o /dev/null --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20900/static/seat-map.js | grep -i -E 'HTTP|x-cache|cache-control|content-length'; done
```

Output:

```
HTTP/2 200 
content-length: 3703
cache-control: public, max-age=300
x-cache-status: MISS
HTTP/2 200 
content-length: 3703
cache-control: public, max-age=300
x-cache-status: HIT
```

This run used version 1.0.0, whose script was 3,703 bytes; version 1.0.1's is 4,392 bytes, and the headers are otherwise the same.

3. A body over the limit:

```
head -c 5000 /dev/zero | tr '\0' a > /tmp/body.txt
curl -s -D - --cacert build/tls/seatwave-test-ca.pem -X POST -H 'Content-Type: application/json' --data-binary @/tmp/body.txt https://127.0.0.1:20900/v1/holds | grep -v -i '^date'
rm /tmp/body.txt
```

Output: the `HTTP/2 413` answer shown above, with the JSON body.

4. Protocol versions:

```
curl -s -o /dev/null -w '%{http_version}\n' --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20900/health
curl -s -o /dev/null -w '%{http_version}\n' --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20901/health
curl -s -o /dev/null -w '%{http_code}\n' --tls-max 1.2 --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20900/health; echo "curl exit $?"
```

Output:

```
2
1.1
000
curl exit 35
```

5. The forged header (the proxy overwrites it):

```
curl -s --cacert build/tls/seatwave-test-ca.pem -H 'X-Forwarded-For: 203.0.113.7' https://127.0.0.1:20900/lab/client; echo
curl -s -H 'X-Forwarded-For: 203.0.113.7' http://127.0.0.1:20911/lab/client; echo
```

Output:

```
{"address":"10.209.9.1","scheme":"https","source":"forwarded"}
{"address":"10.209.9.1","scheme":"http","source":"peer"}
```

## Mistakes people make

- **Adding `proxy_set_header` to one location.** It replaces the inherited list. Add the header to `proxy-headers.conf` instead.
- **Using `$proxy_add_x_forwarded_for`.** It appends to what the client sent, so a forged address survives. This repository overwrites with `$remote_addr`.
- **A proxy keep-alive timeout longer than the back end's.** The proxy then reuses a connection the back end is closing. Here 20 s stays below 30 s.
- **Caching what must not be cached.** No `proxy_ignore_headers`, no `proxy_cache_use_stale`, no `POST` in `proxy_cache_methods`; the test checks all three.
- **One read timeout for everything.** A long poll needs more than an ordinary call. PRODUCTION gives the poll its own location; lab 20905 shows the failure.
- **`proxy_cache_lock` on a path that is never cached.** Every request waits for the one before it. Lab 20914 measures it.
- **Mixing up `rt` and `urt` in the log.** `rt` includes the time spent sending to a slow client; only the difference between the two is the proxy's own share.
- **Forgetting `always` on `add_header`.** Without it the header is missing on error answers.

## Check yourself

1. Which directive in `nginx.conf` tells NGINX how many connections one worker may hold, and why is it 4096?
   *`worker_connections 4096;`. A proxied request uses two connections, so up to about 2,000 requests can be in flight on the one worker.*
2. Why does PRODUCTION have a separate location for `seat-changes`?
   *The long poll is held for 25 s and the stream sends a heartbeat every 15 s, so it needs `proxy_read_timeout 30s` and no buffering. The general 2.5 s would cut it off.*
3. What do `proxy_no_cache $http_authorization;` and `proxy_cache_bypass $http_authorization;` do together?
   *A request that carries credentials is neither answered from the cache nor stored in it.*
4. Why are the upstream's `keepalive_timeout` (20 s) and the client-side `keepalive_timeout` (60 s) different?
   *The upstream one must be shorter than SeatWave's 30 s so the proxy closes first. The client one is longer than the Java client's 30 s so the client chooses when to close.*
5. What does `error_page 502 = @bad_gateway;` do?
   *When NGINX would answer 502 (SeatWave unreachable), it runs the named location, which returns SeatWave's problem-details JSON with `Retry-After: 1`.*

## Further reading

- Core module (`worker_processes`, `listen`, `include`, `error_log`, timeouts, limits): https://nginx.org/en/docs/ngx_core_module.html and https://nginx.org/en/docs/http/ngx_http_core_module.html
- Proxy module (`proxy_pass`, `proxy_set_header`, timeouts, buffering, cache): https://nginx.org/en/docs/http/ngx_http_proxy_module.html
- Upstream module (`upstream`, `keepalive`): https://nginx.org/en/docs/http/ngx_http_upstream_module.html
- Log module (`log_format`, `access_log`): https://nginx.org/en/docs/http/ngx_http_log_module.html
- SSL module (`ssl_protocols`, `ssl_session_cache`): https://nginx.org/en/docs/http/ngx_http_ssl_module.html
- Gzip module: https://nginx.org/en/docs/http/ngx_http_gzip_module.html
- Headers module (`add_header`): https://nginx.org/en/docs/http/ngx_http_headers_module.html
- HTTP/2 module (`http2`): https://nginx.org/en/docs/http/ngx_http_v2_module.html
- RFC 9457, problem details: https://www.rfc-editor.org/rfc/rfc9457
- RFC 7239, the `Forwarded` header: https://www.rfc-editor.org/rfc/rfc7239
- RFC 6298, TCP retransmission timer: https://www.rfc-editor.org/rfc/rfc6298
