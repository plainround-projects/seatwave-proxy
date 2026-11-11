# NGINX: what it is and how its configuration language works

## What it is

**NGINX** (pronounced "engine x") is a server program. A **server** is a program that waits for other programs to connect and ask for something. NGINX can do three jobs: it serves files from a disk (a **web server**), it passes requests on to another program and hands the answer back (a **reverse proxy**), and it can spread requests over several copies of a program (a **load balancer**). This repository uses it as a reverse proxy only.

You control NGINX with **configuration files**: plain text that lists what to listen on and what to do with each request. The text is a small language of its own. This note teaches that language from zero. The next two notes (`nginx-config-walkthrough-1.md` and `nginx-config-walkthrough-2.md`) go through every line of this repository's files. The ideas behind the proxy jobs (TLS, compression, caching, buffering) are in `reverse-proxy-concepts.md`.

## Why this repository uses it

Module 9 of the course asks what a proxy in front of a service saves and what it costs. NGINX is the proxy: the Docker image is `nginx:1.30.6` (the "stable" line, pinned by digest in `compose.yaml`). It runs twice: once as the **edge proxy** the fans talk to, once as a **sidecar** beside SeatWave. Every lab variant is a few lines of NGINX configuration, which makes each feature easy to switch on and off and to measure.

## Where it appears

| Path | What it does with NGINX |
|---|---|
| `nginx/nginx.conf` | The edge proxy's main file: process settings, the log format, limits, the cache, and four `include` lines. |
| `nginx/seatwave/*.conf` | Ten small files the main file and each other include: upstreams, TLS, gzip, buffers, error pages, forwarded headers, timeouts, PRODUCTION, the lab variants and the status page. |
| `nginx/sidecar/nginx.conf` | The sidecar's whole configuration in one file. |
| `compose.yaml` | Starts the NGINX containers and mounts these files read-only (`docker-compose.md`). |
| `src/test/java/.../nginx/NginxConfigRulesTest.java` | Reads these files as text and checks rules about them (`tests-stack-walkthrough.md`). |

## How it works underneath

1. **Processes.** When NGINX starts, one **master process** reads the configuration and starts one or more **worker processes**. The master starts as `root` and starts the workers as an unprivileged user. If a cache is configured, a **cache manager process** also runs. I listed the processes in the proxy container (reading `/proc`, because the image has no `ps` command):

```
1 uid=0 nginx: master process nginx -g daemon off;
7 uid=101 nginx: worker process
8 uid=101 nginx: cache manager process
```

   The sidecar has a master and a worker only, because it has no cache.
2. **Workers do the work.** A worker is a single thread that watches thousands of connections at once and handles whichever has data ready (an **event loop**; the log says `using the "epoll" event method`, the Linux way to watch many connections). It never waits for one slow client. `worker_processes 1;` in this repository means one worker, matching the one CPU the proxy container gets.
3. **A request goes through fixed steps.** NGINX accepts a connection on a **listening port**, reads the request line and headers, picks a **server** by port and name, picks a **location** by the path, runs the directives of that location (possibly passing the request to an upstream), and writes the response. It writes one line to the access log at the end.
4. **A proxied request holds two connections**: one to the client and one to the upstream (SeatWave). `worker_connections` counts both.
5. **The configuration is read at start and at reload.** `nginx -s reload` tells the master to read the files again. If they are valid, the master starts new workers with the new settings; the old workers stop taking new connections and exit when their current requests finish. If the files are not valid, nothing changes. The reload lab in this repository proves it: two reloads under a probe that asks every 100 ms lost no request (`scripts/spof.sh reload`, 300 answers, all `200`).
6. **`nginx -t` checks the files without starting anything.** `nginx -T` checks them and prints the whole configuration, every included file in turn, each introduced by a line `# configuration file <path>:`.

## Walkthrough of this repository's use

This note explains the language. Each rule is shown with a line from the repository.

### Directives and blocks

A configuration is made of **directives**. A **simple directive** is a name, its parameters separated by spaces, and a semicolon:

```nginx
# One worker for the one CPU compose.yaml gives the proxy, so every number is "per proxy core".
worker_processes 1;
error_log /dev/stderr notice;
pid /run/nginx.pid;
```

`worker_processes` is the name, `1` the parameter, and `;` ends it. A missing semicolon is the most common error (see the experiment below).

A **block directive** has the same shape, but instead of a semicolon it holds a group of other directives inside braces:

```nginx
events {
    # Each proxied request holds two connections, one to the client and one to SeatWave.
    worker_connections 4096;
}
```

`events` has no parameter; it holds one directive. A block that can hold other blocks is a **context**. The contexts this repository uses are:

| Context | Meaning | Example in this repository |
|---|---|---|
| main (top of the file) | settings of the whole NGINX | `worker_processes`, `error_log`, `pid` in `nginx/nginx.conf` |
| `events` | how connections are handled | `worker_connections 4096;` |
| `http` | everything about HTTP | the log format, limits, cache path, `include` lines |
| `upstream` | a named group of back-end servers | `upstream seatwave { ... }` |
| `server` | one virtual server: a port (and name) it answers on | one per port in `production.conf`, `labs.conf` and `status.conf` |
| `location` | what to do for requests whose path matches | `location /static/ { ... }` |

Each directive is allowed only in some contexts. `proxy_cache_path` may only appear in `http`; `listen` only in `server`; `proxy_pass` in `location` (and a few other places). Putting a directive in the wrong context is an error that `nginx -t` reports.

Two more kinds of line appear:

- A **comment** starts with `#` and runs to the end of the line.
- An **`include`** directive pastes in other files at that place. `include seatwave/tls.conf;` pastes the file `/etc/nginx/seatwave/tls.conf` (a relative path is read from the folder of the main configuration file; I checked this with a copy of the configuration in another folder). This repository uses it to share pieces: the same TLS settings are included into nine servers.

### Units and strings

- A **time** parameter takes a unit: `10s` (seconds), `2500ms` (milliseconds), `10m` (minutes), `1h` (hours).
- A **size** parameter takes `k` (kilobytes) or `m` (megabytes): `4k`, `300m`.
- The letter `m` means minutes for a time and megabytes for a size. Both appear here: `ssl_session_timeout 10m;` is ten minutes and `keys_zone=seatwave_static:10m` is ten megabytes. The directive decides which.
- A parameter with spaces or special characters is put in quotes, as in `proxy_set_header Connection "";`. The empty string `""` is a real value.
- Two quoted strings written one after the other are joined into one. The `log_format` line in `nginx/nginx.conf` uses this to spread one format over several lines.

### Variables

A name that starts with `$` is a **variable**: NGINX fills it in for each request. `$remote_addr` is the client's address, `$scheme` is `http` or `https`, `$host` is the host name the client asked for, `$request_uri` is the path and query string, `$request_id` is a random 32-character identifier (I checked: 32 hexadecimal characters), and `$http_authorization` is the request header `Authorization` (lower case, dashes turned into underscores, prefix `$http_`). Variables appear inside strings, such as `"$scheme://$host$request_uri"`. They are how the log format, the cache key and the forwarded headers are built.

### Which server and which location answers

1. NGINX first picks the **server** by the port the connection arrived on. If several servers share a port, it compares the request's `Host` header with their `server_name`. Here every server has its own port (`listen 8443 ssl;`, `listen 8444 ssl;`, `listen 8445 ssl;` and the rest), so `server_name localhost;` never decides anything.
2. Within the server, NGINX picks the **location** by the request path. The rules, in order:
   - A location with `=` (`location = /nginx-status`) matches only that exact path, and is chosen at once.
   - Plain locations (`location /` and `location /static/`) are **prefix** locations. NGINX finds the longest prefix that matches and remembers it.
   - Locations with `~` (`location ~ ^/v1/events/[0-9]+/seat-changes`) use a **regular expression**, a pattern language for text. They are tried in the order written. The first match wins and replaces the remembered prefix.
   - If no regular expression matches, the remembered longest prefix is used.
   - A location with a name starting with `@` (`location @bad_gateway`) is **named**. A request never matches it by its path; other directives send requests there on purpose.

Here is the PRODUCTION server's regular-expression location:

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
```

So `GET /v1/events/1/seat-changes?after=1:42` matches both `location /` (prefix) and this regular expression. The expression wins. `GET /static/seat-map.js` matches `location /` and `location /static/`; the longer prefix `/static/` is chosen, and no regular expression matches it.

### Inheritance: what a block gets from the level above

A directive written in `http` or `server` applies to everything inside, unless the inner level sets it again. That is how `proxy_buffering on;` (set once at the server level through an include) reaches every location.

There is a trap. For some directives, **a single directive at the inner level replaces the whole list from the outer level**. `proxy_set_header` and `add_header` work like this. The NGINX documentation says they are inherited from the previous level only if the current level defines none of its own. Two things in this repository follow from it:

- All the forwarded headers live in one file, `nginx/seatwave/proxy-headers.conf`, included in every proxying location. If a location had one extra `proxy_set_header` of its own, it would silently lose all the others.
- The PRODUCTION server sets `add_header X-Cache-Status $upstream_cache_status always;` at the server level. The error locations in `errors.conf` have their own `add_header Cache-Control no-store always;`, so they do not get the cache header. I saw it on the wire: the `413` answer has `cache-control: no-store` and no `x-cache-status`.

### Answering without a back end: `return` and `error_page`

`return 413 '...';` ends the request at once with that status and body text. `error_page 413 = @body_too_large;` says: when NGINX would answer with status 413, run the named location `@body_too_large` instead. The `=` with no number means "use whatever status that location produces". These two are how `errors.conf` replaces NGINX's HTML error pages with SeatWave's JSON error shape.

### The reverse proxy directives

`proxy_pass http://seatwave;` sends the request to the **upstream** named `seatwave`, a block that lists the servers behind the proxy. All the other `proxy_*` directives tune that trip: which HTTP version, which headers to send, how long to wait, whether to buffer and whether to cache. The next two notes explain each one.

## Try it yourself

I ran these inside the running proxy container on 11 November 2026 (NGINX 1.30.6). My container was named `seatwave-proxy-notes-proxy-1` because I used a copy of the stack with the project name `seatwave-proxy-notes`; with `scripts/stack.sh up --run --yes` yours is `seatwave-proxy-proxy-1`.

1. Ask NGINX for its version and check the configuration:

```
docker exec seatwave-proxy-notes-proxy-1 nginx -v 2>&1
docker exec seatwave-proxy-notes-proxy-1 nginx -t 2>&1
```

Output:

```
nginx version: nginx/1.30.6
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

2. Print the whole configuration and count its lines, then list which files it came from:

```
docker exec seatwave-proxy-notes-proxy-1 nginx -T 2>/dev/null | grep -c .
docker exec seatwave-proxy-notes-proxy-1 nginx -T 2>/dev/null | grep -E '^# configuration file'
```

Output (the first number counts the non-blank lines):

```
458
# configuration file /etc/nginx/nginx.conf:
# configuration file /etc/nginx/mime.types:
# configuration file /etc/nginx/seatwave/upstreams.conf:
# configuration file /etc/nginx/seatwave/production.conf:
# configuration file /etc/nginx/seatwave/tls.conf:
# configuration file /etc/nginx/seatwave/gzip.conf:
# configuration file /etc/nginx/seatwave/errors.conf:
# configuration file /etc/nginx/seatwave/buffers.conf:
# configuration file /etc/nginx/seatwave/proxy-headers.conf:
# configuration file /etc/nginx/seatwave/timeouts.conf:
# configuration file /etc/nginx/seatwave/labs.conf:
# configuration file /etc/nginx/seatwave/status.conf:
```

(Files that are included many times, such as `tls.conf`, are listed once, at their first use.)

3. Break a copy of the configuration on purpose. The container's root file system is read-only, so the copy goes into the writable folder `/var/cache/nginx` and is removed afterwards:

```
docker exec seatwave-proxy-notes-proxy-1 sh -c 'cp -r /etc/nginx /var/cache/nginx/copy && sed -i "s/^proxy_send_timeout 5s;/proxy_send_timout 5s;/" /var/cache/nginx/copy/seatwave/timeouts.conf && nginx -t -c /var/cache/nginx/copy/nginx.conf 2>&1; rm -rf /var/cache/nginx/copy'
```

Output (the first line is the time stamp NGINX adds, here removed):

```
[emerg] 86#86: unknown directive "proxy_send_timout" in /var/cache/nginx/copy/seatwave/timeouts.conf:6
nginx: [emerg] unknown directive "proxy_send_timout" in /var/cache/nginx/copy/seatwave/timeouts.conf:6
nginx: configuration file /var/cache/nginx/copy/nginx.conf test failed
```

The message names the file and the line (6). The real configuration was not touched. (The `README.md` of the repository shows the other classic error, a missing semicolon.)

4. Read NGINX's own counters from your Mac (the status page is published on port 20920):

```
curl -s http://127.0.0.1:20920/nginx-status
```

Output (the numbers differ):

```
Active connections: 1 
server accepts handled requests
 6 6 6 
Reading: 0 Writing: 1 Waiting: 0 
```

5. Look at the last line the access log wrote (the format is in `nginx/nginx.conf`; your line will differ):

```
docker exec seatwave-proxy-notes-proxy-1 tail -n 1 /var/log/nginx/seatwave/access.log
```

Output (mine, at that moment):

```
time=2026-11-11T05:40:14+00:00 port=8447 status=200 us=200 rt=0.003 urt=0.003 uct=0.001 cache=- bytes=263 id=ead3c3505ca1147435ca8fb0d52c3542 client=10.209.9.1 method=GET uri=/lab/client
```

## Mistakes people make

- **A missing semicolon or brace.** `nginx -t` finds it. The repository's habit: run `nginx -t` before every reload (`scripts/spof.sh reload` does it twice).
- **Reading `10m` the wrong way.** Minutes for time directives, megabytes for sizes (see above).
- **Expecting `proxy_set_header` or `add_header` to add to the outer list.** An inner one replaces the outer list. This repository keeps all forwarded headers in one included file for that reason.
- **Using `if` inside `location` for logic.** It behaves in surprising ways. This repository uses no `if` at all.
- **Guessing which location handled a request.** Remember the order: exact, then longest prefix, then the first matching regular expression. The long-poll location is a regular expression precisely so that it beats `location /`.
- **Forgetting that a reload keeps the old configuration if the new one is invalid.** A failed `nginx -s reload` leaves the old settings running. Always read the output of `nginx -t`.
- **Editing the file in the container.** Here the files are mounted read-only from `nginx/`. Edit them in the repository, then reload.

## Check yourself

1. What is the difference between a simple directive and a block directive?
   *A simple directive is a name and parameters ending with `;`. A block directive ends with a group of other directives in `{ }`.*
2. A request for `GET /static/seat-map.js` arrives at the PRODUCTION server. Which location answers?
   *`location /static/`. It is the longest matching prefix, and the regular expression for `seat-changes` does not match.*
3. What does `nginx -t` do, and what does `nginx -T` add?
   *`-t` checks the configuration and starts nothing. `-T` also prints the whole configuration, file by file.*
4. Why is every forwarded header kept in one file that every location includes?
   *Because `proxy_set_header` in an inner block replaces the whole inherited list. One extra line in a location would silently drop the others.*
5. What does `10m` mean in `ssl_session_timeout 10m;` and in `keys_zone=seatwave_static:10m`?
   *Ten minutes in the first; ten megabytes in the second.*

## Further reading

- NGINX documentation, beginner's guide: https://nginx.org/en/docs/beginners_guide.html
- Configuration file structure, contexts, directives: https://nginx.org/en/docs/beginners_guide.html#conf_structure
- How NGINX processes a request (server and location selection): https://nginx.org/en/docs/http/request_processing.html
- The `location` directive: https://nginx.org/en/docs/http/ngx_http_core_module.html#location
- Command-line parameters (`-t`, `-T`, `-s`): https://nginx.org/en/docs/switches.html
- Alphabetical index of directives: https://nginx.org/en/docs/dirindex.html
- Alphabetical index of variables: https://nginx.org/en/docs/varindex.html
- Controlling NGINX (signals, reload): https://nginx.org/en/docs/control.html
