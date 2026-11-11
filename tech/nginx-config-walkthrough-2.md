# NGINX configuration walkthrough, part 2: the lab variants, the status page and the sidecar

This is the second of two files. It was split from one long walkthrough because the proxy's configuration is long. Part 1 (`nginx-config-walkthrough-1.md`) explains `nginx/nginx.conf` and the shared pieces (`upstreams.conf`, `tls.conf`, `gzip.conf`, `buffers.conf`, `errors.conf`, `proxy-headers.conf`, `timeouts.conf`) and PRODUCTION (`production.conf`). This part covers `nginx/seatwave/labs.conf`, `nginx/seatwave/status.conf` and `nginx/sidecar/nginx.conf`. Every directive that part 1 already explained is only named here, with a pointer back.

## What it is

`labs.conf` holds ten small `server` blocks. Each one is the same proxy with exactly one feature changed, listening on its own port, so a measurement can compare two ports and attribute the difference to that one feature. `status.conf` is a tiny server that shows NGINX's own counters. `nginx/sidecar/nginx.conf` is the complete configuration of a second NGINX, the sidecar, that runs beside SeatWave.

## Why this repository uses it

A claim such as "TLS termination costs X" or "buffering saves Y worker seconds" is only believable with a controlled comparison. The lab variants are those controls. The sidecar shows, without a service mesh, the shape of a proxy that sits next to a service and shares its fate (see `reverse-proxy-concepts.md`).

## Where it appears

| Path | What it does | Published on your computer |
|---|---|---|
| `nginx/seatwave/labs.conf` | Ten lab servers (container ports 8444, 8080, 8081, 8445, 8446, 8447, 8451, 8456, 8459, 8460) | 20901 to 20909 and 20914 |
| `nginx/seatwave/status.conf` | The counters page | 20920 |
| `nginx/sidecar/nginx.conf` | The sidecar | not published; reached through the proxy's port 20906 |
| `compose.yaml` | Maps the ports and mounts the files (`docker-compose.md`) | |
| `config/targets-before-after.txt`, `config/bench.properties` | Name the lab ports the measurements use | |

The table in `docker-compose.md` maps every published port to its server block.

## How it works underneath

1. Every `server` block that has its own `listen` port is a separate listener. NGINX does not need a `server_name` to choose between them, because the port already decides.
2. Each lab block repeats the same four parts: `listen`, then `include` lines for the shared pieces it wants, then a `location /` with the forwarded headers, the timeouts and `proxy_pass`. A lab differs by dropping, adding or changing one of these.
3. Directives at the `server` level apply to every location inside, so `include seatwave/buffers.conf;` there switches buffering for the whole server.
4. The sidecar is a **second NGINX process in a second container**, but with `network_mode: "service:backend"` in `compose.yaml` it shares SeatWave's network namespace: the same address `10.209.9.20` and the same loopback. The edge proxy reaches it at `10.209.9.20:9443`; it reaches SeatWave at `127.0.0.1:8080`, an address nothing outside the namespace can use.

## Walkthrough of this repository's use

### `labs.conf`: the four simplest variants

```nginx
# Lab variants, each one feature apart from another, each on its own port (compose.yaml publishes
# them on 127.0.0.1 only). None is a production setting.

# 20901: TLS termination alone. HTTP/1.1 to clients, no gzip, no cache, reused upstream.
server {
    listen 8444 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}

# 20902: the extra hop alone. Plain HTTP, buffering on, no gzip, no cache, reused upstream.
server {
    listen 8080;
    server_name localhost;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}

# 20903: the extra hop with buffering off: the proxy passes each piece on as it arrives.
server {
    listen 8081;
    server_name localhost;
    include seatwave/errors.conf;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_buffering off;
        proxy_pass http://seatwave;
    }
}
```

- Lines 1 to 2 are a comment: lab variants, each one feature apart from another, each on its own port, published on `127.0.0.1` only. None is a production setting.
- Line 4 is a comment for **20901: TLS termination alone**: HTTP/1.1 to clients, no gzip, no cache, reused upstream.
  - Line 5, `server {`, and line 6, `listen 8444 ssl;`: the listener (container port 8444, published as 20901) with TLS on.
  - Line 7, `server_name localhost;`: the name (it decides nothing here).
  - Lines 8 to 10: include the TLS settings, the error pages and the buffering. There is no `http2 on;`, no gzip and no `proxy_cache`, so the client speaks HTTP/1.1 and the body travels as SeatWave sent it. I checked that port 20901 answers over HTTP/1.1.
  - Lines 11 to 15: `location /` includes the forwarded headers and the timeouts, then `proxy_pass http://seatwave;` uses the upstream with connection reuse. Line 16 closes the server.
- Line 18 is a comment for **20902: the extra hop alone**: plain HTTP, buffering on, no gzip, no cache, reused upstream. Lines 19 to 29 are the same as above without `ssl` on `listen 8080;` and without the TLS include. A client that connects to port 20902 speaks plain HTTP, so the difference to SeatWave directly is the proxy hop only.
- Line 31 is a comment for **20903: the extra hop with buffering off**: the proxy passes each piece on as it arrives. Lines 32 to 42 have no `buffers.conf` include. Line 39, `proxy_buffering off;`, turns buffering off inside the location, after the two includes. For responses as small as the seat map (36,894 bytes) the difference is small: in my slow-client run through this port, at most 1 of SeatWave's 64 workers was busy, the same as with buffering on. The slow-client lab with the poster (300,000 bytes) is where the README shows the difference (`measuring-a-proxy.md`).

### `labs.conf`: no reuse and the wrong timeout

```nginx
# 20904: TLS termination with a new upstream connection for every request.
server {
    listen 8445 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave_no_reuse;
    }
}

# 20905: a wrong timeout. The long poll's location keeps the general 2.5 s read timeout of an
# ordinary API call instead of the 30 s it needs.
server {
    listen 8446 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}
```

- Lines 44 to 56 are **20904: TLS termination with a new upstream connection for every request**. Everything is as in 20901 except line 54, `proxy_pass http://seatwave_no_reuse;`, which names the upstream with `keepalive 0` (`upstreams.conf`). Every request opens and closes a TCP connection to SeatWave. The README's bench table shows what that costs: 299 requests per second against 4,333 for the reusing proxy, and 3,330 errors. (Those figures are the README's; I did not run the bench.)
- Lines 58 to 59 are a comment, and lines 60 to 71 are **20905: a wrong timeout**: "the long poll's location keeps the general 2.5 s read timeout of an ordinary API call instead of the 30 s it needs". The block is identical to 20901 apart from the port. The mistake is what it does **not** have: no special location for `seat-changes`, so the long poll meets `timeouts.conf`'s 2.5 s. I ran it:

```
curl -s --cacert build/tls/seatwave-test-ca.pem -w '\n%{http_code} after %{time_total} s\n' 'https://127.0.0.1:20905/v1/events/1/seat-changes?after=1:42'
```

Output:

```
{"type":"https://seatwave.example/problems/upstream-timeout","title":"SeatWave did not answer in time","status":504}
504 after 2.509705 s
```

The proxy gave up after 2.5 s with its own `504` JSON from `errors.conf`, though SeatWave would have answered after 25 s. The same request on port 20900 waits for SeatWave (README: `200` after 25.0 s).

### `labs.conf`: through the sidecar

```nginx
# 20906: through the sidecar. The proxy re-encrypts to the sidecar beside SeatWave and checks its
# certificate against the TEST CA, so the inside link is TLS as well.
server {
    listen 8447 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass https://seatwave_sidecar;
        proxy_ssl_protocols TLSv1.3;
        proxy_ssl_verify on;
        proxy_ssl_trusted_certificate /etc/nginx/tls/seatwave-test-ca.pem;
        # The name to check in the sidecar's certificate (a DNS name of its subjectAltName).
        proxy_ssl_name backend;
        # Reuse TLS sessions to the sidecar, so a new upstream connection skips the full handshake.
        proxy_ssl_session_reuse on;
    }
}
```

- Lines 73 to 74 are a comment for **20906: through the sidecar**: the proxy re-encrypts to the sidecar beside SeatWave and checks its certificate against the TEST authority, so the inside link is TLS as well.
- Lines 75 to 80 and 81 to 83 are the same shape as before.
- Line 84, `proxy_pass https://seatwave_sidecar;`: the `https://` in front means NGINX speaks TLS to the upstream. The upstream `seatwave_sidecar` is `10.209.9.20:9443` (`upstreams.conf`).
- Line 85, `proxy_ssl_protocols TLSv1.3;`: on the inside link too, only TLS 1.3. (The default for this directive is TLS 1.2 and 1.3.)
- Line 86, `proxy_ssl_verify on;`: check the sidecar's certificate. The default is `off`, in which case NGINX accepts any certificate, so an impostor would pass.
- Line 87, `proxy_ssl_trusted_certificate /etc/nginx/tls/seatwave-test-ca.pem;`: the authority whose signature the sidecar's certificate must carry. This is the TEST authority from `./gradlew testCertificates`.
- Line 88 is a comment and line 89, `proxy_ssl_name backend;`: the name to look for inside the sidecar's certificate. Without this line NGINX would look for the upstream group's name, `seatwave_sidecar`, which is not in the certificate. The certificate carries `backend` as one of its DNS names (its subjectAltName list), which is what makes this check pass. `backend` is also the name of the Compose service; here it is used only as a certificate name, not looked up on the network.
- Line 90 is a comment and line 91, `proxy_ssl_session_reuse on;`: reuse TLS sessions to the sidecar, so a new upstream connection skips the full handshake. (`on` is the default; written out so it is a decision.)

Proof that it works: through port 20906, SeatWave still sees the real client, not the sidecar.

```
curl -s --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:20906/lab/client; echo
```

Output:

```
{"address":"10.209.9.1","scheme":"https","source":"forwarded"}
```

### `labs.conf`: three gzip levels

```nginx
# 20907, 20908, 20909: TLS termination with gzip at levels 1, 6 and 9, for the bytes-and-CPU table.
server {
    listen 8451 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    include seatwave/gzip.conf;
    gzip_comp_level 1;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}

server {
    listen 8456 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    include seatwave/gzip.conf;
    gzip_comp_level 6;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}

server {
    listen 8459 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    include seatwave/gzip.conf;
    gzip_comp_level 9;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}
```

- Line 95 is a comment: TLS termination with gzip at levels 1, 6 and 9, for the bytes-and-CPU table (**20907, 20908, 20909**).
- Lines 96 to 109 are level 1 (container port 8451). Line 102 includes `gzip.conf` (part 1 explains it), and line 103, `gzip_comp_level 1;`, sets the level.
- Lines 111 to 124 are level 6 (port 8456), and lines 126 to 139 are level 9 (port 8459). They differ only in the port and the level.

I fetched the seat map from each port with `Accept-Encoding: gzip`:

| Port | Level | Bytes on the wire (from `size_download`) |
|---|---|---|
| 20901 | none | 36894 |
| 20907 | 1 | 2453 |
| 20908 | 6 | 2419 |
| 20909 | 9 | 2351 |

The three compressed sizes are within 5 percent of each other, but level 9 costs several times more CPU than level 1 (README's bench: proxy CPU of about 100, 187 and 602 ms per thousand requests).

### `labs.conf`: the cache mistake

```nginx
# 20914: a cache mistake. The cache lock is set for the whole server, as if every path were static,
# so concurrent requests for an uncacheable seat map wait for each other.
server {
    listen 8460 ssl;
    server_name localhost;
    include seatwave/tls.conf;
    include seatwave/errors.conf;
    include seatwave/buffers.conf;
    proxy_cache seatwave_static;
    proxy_cache_key "$scheme://$host$request_uri";
    proxy_cache_lock on;
    add_header X-Cache-Status $upstream_cache_status always;
    location / {
        include seatwave/proxy-headers.conf;
        include seatwave/timeouts.conf;
        proxy_pass http://seatwave;
    }
}
```

- Lines 141 to 142 are a comment for **20914: a cache mistake**: the cache lock is set for the whole server, as if every path were static, so concurrent requests for an uncacheable seat map wait for each other.
- Lines 143 to 148 are the usual start. Lines 149 to 152 turn on the cache for the server: `proxy_cache seatwave_static;`, the key (scheme, host and path with query), `proxy_cache_lock on;` and the `X-Cache-Status` header. Part 1 explains each.
- There is no `proxy_cache_valid`, `proxy_no_cache` or `proxy_cache_bypass` here. Lines 153 to 157 are the usual `location /`.
- The seat map says `Cache-Control: no-cache`, so it is never stored and the answer is always `MISS`. I asked twice: `MISS` and `MISS`. The harm is the lock: with the lock on, a second request for the same key waits (by default up to 5 s) for the first to finish, one after the other. The README's bench shows the result: 1,162 requests per second against 6,549 for PRODUCTION.

### `nginx/seatwave/status.conf`

```nginx
# The proxy's own counters (active, accepted and handled client connections, requests), for the
# lab network and this machine only. Published on 20920.
server {
    listen 8090;
    server_name localhost;
    access_log off;
    location = /nginx-status {
        stub_status;
        allow 10.209.9.0/24;
        allow 127.0.0.1;
        deny all;
    }
}
```

- Lines 1 to 2 are a comment: the proxy's own counters, for the lab network and this machine only. Published on 20920.
- Line 3, `server {`, and line 4, `listen 8090;`: a plain-HTTP listener on container port 8090.
- Line 5, `server_name localhost;`: as before.
- Line 6, `access_log off;`: do not write the status page's own requests to the access log, so polling it does not add lines to the numbers the log is used for.
- Line 7, `location = /nginx-status {`: an exact-match location, only the path `/nginx-status`.
- Line 8, `stub_status;`: print NGINX's basic counters as plain text. The module `ngx_http_stub_status_module` is built into the image (`nginx -V` lists `--with-http_stub_status_module`).
- Lines 9 to 11: access control. The rules are checked **in order, and the first that matches wins**.
  - `allow 10.209.9.0/24;`: anyone from the lab network. A request from your Mac arrives through Docker Desktop's forwarder and is seen as coming from `10.209.9.1`, which is in this range.
  - `allow 127.0.0.1;`: the container itself, for `docker exec ... curl`.
  - `deny all;`: everyone else gets `403`.

Output of the page (mine; your numbers differ):

```
Active connections: 1 
server accepts handled requests
 6 6 6 
Reading: 0 Writing: 1 Waiting: 0 
```

- `Active connections` is the number of client connections open now, including the one asking.
- The three numbers are, in order: connections accepted, connections handled, and requests served in total. Equal first and second numbers mean no connection was dropped for lack of resources.
- `Reading`, `Writing` and `Waiting` split the active connections: NGINX is reading a request header, writing a response, or the connection is idle (keep-alive).

### `nginx/sidecar/nginx.conf`: the process and the upstream

```nginx
# The sidecar proxy (Chapter 9.3): a second NGINX in SeatWave's own network namespace
# (network_mode: service:backend in compose.yaml). It terminates TLS on the inside link and talks
# to SeatWave over the loopback address, which nothing outside the namespace can reach.
worker_processes 1;
error_log /dev/stderr notice;
pid /run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    server_tokens off;
    access_log off;

    upstream seatwave_local {
        server 127.0.0.1:8080;
        keepalive 16;
        keepalive_timeout 20s;
    }
```

- Lines 1 to 3 are a comment: a second NGINX in SeatWave's own network namespace (`network_mode: service:backend` in `compose.yaml`). It terminates TLS on the inside link and talks to SeatWave over the loopback address, which nothing outside the namespace can reach.
- Lines 4 to 6: one worker, errors to standard error, the pid file in the tmpfs `/run` (see part 1).
- Lines 8 to 10: `events` with `worker_connections 1024;`. The sidecar needs fewer than the edge proxy.
- Line 12, `http {`.
- Line 13, `server_tokens off;`, as in the edge proxy.
- Line 14, `access_log off;`: the sidecar writes no access log. The edge proxy's log is the record. (There is no `include mime.types` or `default_type` either: the sidecar serves no files.)
- Lines 16 to 20: `upstream seatwave_local` has one member, `127.0.0.1:8080`: SeatWave's plain HTTP port, over the loopback it shares. `keepalive 16;` and `keepalive_timeout 20s;` mean the same as in the edge proxy.

### `nginx/sidecar/nginx.conf`: the TLS listener and the access rule

```nginx
    server {
        listen 9443 ssl;
        ssl_certificate /etc/nginx/tls/server-chain.pem;
        ssl_certificate_key /etc/nginx/tls/server-key.pem;
        ssl_protocols TLSv1.3;
        ssl_session_cache shared:sidecar_tls:1m;

        # Only the edge proxy may call this sidecar. The check is on the connection's real peer;
        # the realip module is not used here, because it would replace $remote_addr with the
        # forwarded client before allow and deny run, and the edge proxy would then be refused.
        allow 10.209.9.10;
        deny all;
```

- Line 22, `server {`, and line 23, `listen 9443 ssl;`: a TLS listener on port 9443. It is not published by Docker; the edge proxy reaches it at `10.209.9.20:9443`.
- Lines 24 and 25: the certificate chain and private key. They are the same TEST files the edge proxy uses, mounted read-only from `build/tls` (`compose.yaml`).
- Line 26, `ssl_protocols TLSv1.3;`: TLS 1.3 only.
- Line 27, `ssl_session_cache shared:sidecar_tls:1m;`: remember sessions in 1 MB of shared memory (about 4,000), for returning clients such as the edge proxy, which asks to reuse sessions with `proxy_ssl_session_reuse on;`.
- Lines 29 to 31 are a comment: only the edge proxy may call this sidecar. The check is on the connection's real peer. The realip module is not used here, because it would replace `$remote_addr` with the forwarded client before `allow` and `deny` run, and the edge proxy would then be refused. (The module is built into the image: `nginx -V` lists `--with-http_realip_module`; it is just not turned on.)
- Line 32, `allow 10.209.9.10;`: the edge proxy's fixed address from `compose.yaml`. Line 33, `deny all;`: everything else. I tested both. From the proxy container, `curl` to the sidecar returned `200`. From a throwaway container that I started on the same network with the address `10.209.9.40`, the sidecar answered `403`. (For that throwaway test only I told curl to skip certificate checks with `-k`; no key or password was involved.)

### `nginx/sidecar/nginx.conf`: the location

```nginx
        location / {
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host $host;
            # Passed on as the edge proxy wrote them: only the edge can reach this sidecar, and
            # SeatWave trusts the sidecar's address (127.0.0.1) and reads the client from these.
            proxy_set_header X-Forwarded-For $http_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
            proxy_set_header Forwarded $http_forwarded;
            proxy_set_header X-Request-Id $http_x_request_id;
            proxy_connect_timeout 2s;
            proxy_read_timeout 2500ms;
            proxy_pass http://seatwave_local;
        }
    }
}
```

- Line 35, `location / {`: all paths.
- Line 36, `proxy_http_version 1.1;` and line 37, `proxy_set_header Connection "";`: keep-alive to SeatWave, as in `proxy-headers.conf`.
- Line 38, `proxy_set_header Host $host;`: pass the host name on.
- Lines 39 to 40 are a comment: the headers are passed on **as the edge proxy wrote them**. Only the edge can reach this sidecar, and SeatWave trusts the sidecar's address (`127.0.0.1` in `forwarded.trustedProxies`) and reads the client from these headers.
- Lines 41 to 44: four `proxy_set_header` lines that copy an incoming header to the outgoing request: `$http_x_forwarded_for` is the value of the incoming `X-Forwarded-For` header; the other three do the same for `X-Forwarded-Proto`, `Forwarded` and `X-Request-Id`. They are copies, not rewrites: the sidecar does not know the real client address (its own `$remote_addr` is the edge proxy), so it must not overwrite what the edge wrote. This is the opposite of the edge proxy, which overwrites because it is the first trusted hop.
- Line 45, `proxy_connect_timeout 2s;`, and line 46, `proxy_read_timeout 2500ms;`: the same budget as `timeouts.conf`.
- Line 47, `proxy_pass http://seatwave_local;`: pass the request to SeatWave over the loopback. Lines 48 to 50 close the location, the server and the `http` block.

## Try it yourself

I ran these against a running stack on 11 November 2026 (project name `seatwave-proxy-notes`; start yours with `scripts/stack.sh up --run --yes`). Timings differ on every run.

1. Compare the lab ports on the same request (protocol version in the first column):

```
for p in 20900 20901; do curl -s -o /dev/null -w "$p: HTTP/%{http_version}\n" --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:$p/health; done
```

Output: `20900: HTTP/2` and `20901: HTTP/1.1`.

2. The three gzip levels, bytes on the wire:

```
for p in 20907 20908 20909; do curl -s -o /dev/null --cacert build/tls/seatwave-test-ca.pem -H 'Accept-Encoding: gzip' -w "port $p: %{size_download} bytes on the wire\n" "https://127.0.0.1:$p/events/1/seats?section=A"; done
```

Output:

```
port 20907: 2453 bytes on the wire
port 20908: 2419 bytes on the wire
port 20909: 2351 bytes on the wire
```

3. The wrong timeout (about 2.5 s of waiting): the `504` command in the section on 20905 above.
4. The sidecar's access rule. From the proxy container (allowed), with the sidecar's name checked against the certificate:

```
docker exec seatwave-proxy-notes-proxy-1 curl -s -o /dev/null -w '%{http_code}\n' --cacert /etc/nginx/tls/seatwave-test-ca.pem --resolve backend:9443:10.209.9.20 https://backend:9443/health
```

Output: `200`. (`--resolve` makes curl treat the name `backend` as the address `10.209.9.20`, so the certificate name matches.)

5. The status page, including what the proxy counted for your own requests:

```
curl -s http://127.0.0.1:20920/nginx-status
```

## Mistakes people make

- **Comparing two labs that differ by more than one feature.** The whole design of `labs.conf` avoids it. When you add a lab, change one thing.
- **Turning on `proxy_ssl_verify` and forgetting `proxy_ssl_name`.** NGINX then checks the certificate for the upstream group's name and the check fails. Line 89 fixes it.
- **Leaving `proxy_ssl_verify` off.** An inner TLS link that accepts any certificate gives encryption without identity: an impostor passes.
- **Using the realip module in front of `allow`/`deny`.** It replaces `$remote_addr`, so the rule would test the forwarded client instead of the connection's peer. The sidecar's comment records this.
- **Overwriting forwarded headers in the sidecar.** The sidecar cannot see the real client, so it must copy the edge's values, not write its own.
- **Publishing the status page.** `stub_status` shows internal numbers. It is limited to the lab network and loopback here, and published on `127.0.0.1` only.
- **Setting a cache lock where nothing is cacheable.** Lab 20914 exists to show it.

## Check yourself

1. How does a lab variant differ from PRODUCTION, and why only one feature at a time?
   *It drops, adds or changes one directive group. A measurement then compares two ports and the difference belongs to that one feature.*
2. Why does lab 20905 return `504` after 2.5 s?
   *It has no special location for the long poll, so `timeouts.conf`'s 2.5 s read timeout applies, and SeatWave would need 25 s.*
3. What do `proxy_ssl_verify on;` and `proxy_ssl_name backend;` do?
   *The first makes the proxy check the sidecar's certificate against the TEST authority. The second names the DNS name to look for in that certificate.*
4. Why does the sidecar copy `$http_x_forwarded_for` instead of using `$remote_addr`?
   *The sidecar's own peer is the edge proxy. The real client address is only in the header the edge wrote, which only the edge can send.*
5. In `status.conf`, why is `deny all;` last?
   *`allow` and `deny` are checked in order and the first match wins. Putting `deny all;` last denies everyone who did not match an `allow`.*

## Further reading

- `server` and `listen`: https://nginx.org/en/docs/http/ngx_http_core_module.html#server and https://nginx.org/en/docs/http/ngx_http_core_module.html#listen
- Proxying over TLS (`proxy_ssl_*`): https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_ssl_verify
- `stub_status`: https://nginx.org/en/docs/http/ngx_http_stub_status_module.html
- `allow` and `deny`: https://nginx.org/en/docs/http/ngx_http_access_module.html
- The realip module: https://nginx.org/en/docs/http/ngx_http_realip_module.html
- Upstream `keepalive`: https://nginx.org/en/docs/http/ngx_http_upstream_module.html#keepalive
- NGINX as a TLS terminating proxy (SSL module): https://nginx.org/en/docs/http/ngx_http_ssl_module.html
- `proxy_cache_lock`: https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_cache_lock
