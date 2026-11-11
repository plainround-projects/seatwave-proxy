# Docker Compose and YAML: `compose.yaml`

## What it is

**Docker Compose** starts several containers together from one text file. Without it you would type a long `docker run` command for every container, with its ports, memory limit, network and mounted folders. With it you write all of that down once, in a file named `compose.yaml`, and run `docker compose up`. Docker Compose is a command-line tool; the file is its input. (For containers and images, read `docker.md` first.)

The file is written in **YAML**, a format built from indentation. Two spaces under a key mean "this belongs to that key". There are three building blocks:

- `key: value` pairs, which make a **mapping** (a set of named values).
- **Lists**: lines that start with `- `.
- **Comments**: `#` to the end of the line.

Tabs are not allowed for indentation. YAML also has **anchors**: `&name` after a value gives it a name, and `*name` later inserts a copy of it. The special key `<<` merges all the keys of a named mapping into the mapping that holds the `<<`. This file uses all three.

## Why this repository uses it

The lab needs three programs that must see each other on fixed addresses: SeatWave, the NGINX reverse proxy in front of it, and a second NGINX (the sidecar) beside it. The file pins the images, gives each container a fixed address on a private network, caps its CPU and memory, takes away its rights, and publishes its ports on `127.0.0.1` only, so nothing outside this computer can reach the lab. `scripts/stack.sh` runs it (see `shell-scripts-walkthrough.md`).

## Where it appears

| Path | What it does with Compose |
|---|---|
| `compose.yaml` | The only Compose file: four services (`backend`, `sidecar`, `proxy`, `tools`) and one network (`lab`). |
| `scripts/stack.sh` | Starts and stops the project: `docker compose -p seatwave-proxy up -d --build`, `down -v`. |
| `scripts/spof.sh` | Stops and starts one service with `docker compose stop` and `start`. |
| `nginx/`, `build/tls` | Mounted into the containers as read-only folders and files. |
| `src/test/java/.../nginx/NginxConfigRulesTest.java` | Reads `compose.yaml` as text and checks ports, digests and privileges (see `tests-stack-walkthrough.md`). |

## How it works underneath

1. `docker compose up` reads `compose.yaml` in the current folder and works out the **project**. The project name here is `seatwave-proxy` (line 8). Every object Compose creates carries the label `com.docker.compose.project=<name>`, which is how `stack.sh` later finds what to remove. The option `-p <name>` replaces the name from the file (I used `-p seatwave-proxy-notes` for the runs in these notes).
2. Compose creates the **network** `lab` first. A network is a private virtual switch. Docker gives each container on it an address, here a fixed one. Containers on one network can reach each other by address, and by service name through Docker's built-in name server.
3. For each **service** it creates one container named `<project>-<service>-1`, for example `seatwave-proxy-notes-proxy-1`. It starts them in an order that respects `depends_on`.
4. A **published port** (`"127.0.0.1:20900:8443"`) tells Docker to listen on the host's address and port on the left and pass each connection to the container's port on the right.
5. A **bind mount** shows a file or folder of your computer inside the container. A **tmpfs** is a folder that lives in memory and disappears when the container stops.
6. `docker compose down -v` stops the containers, removes them, removes the network, and removes the **volumes** (Docker-managed storage) of the project. This file declares no named volumes, so `-v` has nothing to delete; it is a safety net.

## Walkthrough of this repository's use

### The header, the project name and the two anchors

```yaml
# SeatWave behind its reverse proxy (Module 9). Start and stop it with scripts/stack.sh, which
# uses the project name seatwave-proxy and brings everything down with "down -v".
# Images are pinned by tag and digest, checked on Docker Hub on 11 November 2026: nginx:1.30.6
# (the stable line, Debian trixie, built with OpenSSL 3.5.7) and SeatWave's own image, built by the
# Dockerfile from two pinned images. Every port is published on 127.0.0.1 only, from Module 9's
# range 20900 to 20999. Nothing runs privileged, nothing uses the host network or the Docker
# socket, and the only folders mounted are this project's nginx/ and build/tls, read-only.
name: seatwave-proxy

x-nginx-image: &nginx-image nginx:1.30.6@sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703

x-hardening: &hardening
  security_opt:
    - no-new-privileges:true
  read_only: true

```

- Lines 1 to 7 are comments. They say that `scripts/stack.sh` starts and stops the stack under the project name `seatwave-proxy`, that the images are pinned by tag **and** digest (checked on Docker Hub on 11 November 2026), that every port is published on `127.0.0.1` in the range 20900 to 20999 (Module 9's range), that nothing runs privileged, nothing uses the host's network or the Docker socket, and that the only folders mounted are `nginx/` and `build/tls`, read-only.
- Line 8, `name: seatwave-proxy`, is the project name described above.
- Line 10 starts with `x-nginx-image`. A top-level key that begins with `x-` is an **extension field**: Compose ignores it, so you can park reusable values there. `&nginx-image` after the colon attaches the anchor name `nginx-image` to the value that follows, the image reference `nginx:1.30.6@sha256:42c68e...`. NGINX 1.30.6 is the "stable" line. Defining it once means the proxy and the sidecar cannot drift apart.
- Lines 12 to 15 define `x-hardening` with the anchor `hardening`. It is a mapping of two settings every service shares:
  - `security_opt: - no-new-privileges:true` forbids a process in the container, and its children, from gaining more rights than it started with (for example through a program with the setuid bit).
  - `read_only: true` makes the container's root file system read-only. I checked it: `touch /etc/nginx/x` in the proxy answers `Read-only file system`. A program that must write somewhere gets a `tmpfs` or a bind mount for that one folder.

`docker compose config` prints the file after Compose has applied the merges. In its output for the sidecar, `read_only: true` and `security_opt` appear at the service's top level, which shows that `<<: *hardening` (below) worked.

### The `backend` service: SeatWave

```yaml
services:
  backend:
    <<: *hardening
    build: .
    image: seatwave-proxy-backend:1.0.0
    cap_drop:
      - ALL
    # Module 1's reference machine has 4 vCPU; SeatWave gets 4 CPUs of the Docker virtual machine.
    cpus: 4
    mem_limit: 1g
    tmpfs:
      - /tmp
    volumes:
      - ./build/tls:/run/tls:ro
    networks:
      lab:
        ipv4_address: 10.209.9.20
    ports:
      - "127.0.0.1:20910:8443"
      - "127.0.0.1:20911:8080"
      - "127.0.0.1:20912:8081"
```

- Line 17 starts the list of services; line 18 names the first one, `backend`. The name is yours. It becomes part of the container name and a name on the network.
- Line 19, `<<: *hardening`, merges the two keys of `x-hardening` into this service. That is the same as writing `security_opt` and `read_only` here.
- Line 20, `build: .`, says: build the image from the `Dockerfile` in this folder (`.` is the folder holding `compose.yaml`). Line 21, `image: seatwave-proxy-backend:1.0.0`, is the name and tag to give the result, so other services can use it by name. See `docker.md`.
- Lines 22 and 23 are `cap_drop:` with the list item `ALL`. A **capability** is one small piece of the administrator's power, such as binding a port below 1024 or changing a file's owner. `ALL` removes every one. SeatWave listens on ports above 1024 and needs none of them. (This is also why every port inside this lab is above 1024.)
- Line 24 is a comment: Module 1's reference machine has 4 virtual CPUs, so SeatWave gets 4. Line 25, `cpus: 4`, caps the container at four CPUs' worth of time. Docker stores it as `NanoCpus=4000000000`. Line 26, `mem_limit: 1g`, caps memory at one gibibyte; Docker stores `1073741824` bytes. If the program tries to use more, the kernel kills it. (The Java option `-XX:MaxRAMPercentage=50` in the `Dockerfile` is a percentage of this limit.)
- Lines 27 and 28 give the container a `tmpfs` at `/tmp`. Java and the JDK's HTTP server write temporary files there, and the root file system is read-only.
- Lines 29 and 30: `volumes:` with one bind mount, `./build/tls:/run/tls:ro`. The format is `source:target:mode`. `./build/tls` is the folder on your computer with the TEST certificates (made by `./gradlew testCertificates`); `/run/tls` is where the container sees it; `:ro` means read-only. `config/backend-container.properties` points to `/run/tls/seatwave-server.p12` (see `java-properties.md`).
- Lines 31 to 33: `networks:` attaches the service to `lab` (declared at the bottom) and `ipv4_address: 10.209.9.20` pins its address. The proxy's upstream address in `nginx/seatwave/upstreams.conf` is this one, and `forwarded.trustedProxies` in the properties file lists the proxy's address; both must be fixed, so they are.
- Lines 34 to 37: `ports:` publishes three of SeatWave's ports. Each entry is `host address : host port : container port`:
  - `"127.0.0.1:20910:8443"`: SeatWave's own HTTPS (the "SIMPLE" case, no proxy).
  - `"127.0.0.1:20911:8080"`: SeatWave's plain HTTP.
  - `"127.0.0.1:20912:8081"`: SeatWave's operations listener (`/metrics`, `/health`).

  The entries are quoted. In YAML 1.1, an unquoted `20910:8443` can be read as a number in base 60. The quotes make sure it is text. The `127.0.0.1` part is what keeps the port private: without it Docker would listen on every address of your computer, and anyone on your network could connect. `NginxConfigRulesTest` fails if a port line lacks it.

### The `sidecar` service: a second NGINX in SeatWave's network namespace

```yaml
  # The sidecar shares SeatWave's network namespace, as a second container in one pod would.
  sidecar:
    <<: *hardening
    image: *nginx-image
    entrypoint: ["nginx", "-g", "daemon off;"]
    network_mode: "service:backend"
    depends_on:
      - backend
    cap_drop:
      - ALL
    cap_add:
      - CHOWN
      - SETGID
      - SETUID
    cpus: 1
    mem_limit: 128m
    tmpfs:
      - /var/cache/nginx:size=32m
      - /run:size=1m
    volumes:
      - ./nginx/sidecar/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./build/tls:/etc/nginx/tls:ro
```

- Line 39 is a comment: the sidecar "shares SeatWave's network namespace, as a second container in one pod would". A **network namespace** is a container's own set of network cards, addresses and ports. A **pod** is Kubernetes' name for containers that run together. `reverse-proxy-concepts.md` explains the sidecar idea.
- Line 41 merges the hardening keys. Line 42, `image: *nginx-image`, inserts the anchored image reference from line 10.
- Line 43: `entrypoint: ["nginx", "-g", "daemon off;"]`. The NGINX image normally starts through `/docker-entrypoint.sh`, which runs helper scripts from `/docker-entrypoint.d` first. I read the image's metadata and the folder: the scripts are named for editing an IPv6 listen line, substituting environment variables into templates, and tuning the number of workers. This lab supplies its own finished configuration, so the line replaces the entry point and starts `nginx` directly. `-g "daemon off;"` is a global directive given on the command line: stay in the foreground. A container ends when its main program ends, so NGINX must not go to the background.
- Line 44, `network_mode: "service:backend"`, makes this container join the `backend` container's network namespace. It has no network card of its own: it shares SeatWave's address `10.209.9.20` and SeatWave's loopback `127.0.0.1`. I checked this: `curl http://127.0.0.1:8080/health` run inside the sidecar answers `{"status":"up"}`, which is SeatWave's answer. Because of this, the sidecar has no `networks:` and no `ports:`; the backend's published ports are the only ones, and port 9443, where the sidecar listens, is reached at `10.209.9.20:9443` from the proxy.
- Lines 45 and 46: `depends_on: - backend`. Compose starts `backend` first. This is only start order, not "wait until it is ready": the merged configuration shows `condition: service_started`. It must start second because the sidecar needs the backend's namespace to exist.
- Lines 47 to 52: all capabilities are dropped, then three are added back: `CHOWN`, `SETGID`, `SETUID`. NGINX's first process (the **master**) starts as `root` and starts **worker** processes as the unprivileged user `nginx` (user id 101, group `nginx`). That needs the rights to change user and group, and to hand ownership of its temporary folders to that user. I looked at the processes in the proxy: the master has user id 0 and the worker has user id 101. Dropping the other capabilities has a visible effect too: even `root` in the proxy container cannot list the cache folder that belongs to user 101 (`Permission denied`), because the "override file permissions" capability is gone.
- Lines 53 and 54: one CPU and 128 MB (`134217728` bytes). The sidecar only passes bytes along.
- Lines 55 to 57: two tmpfs folders with a size limit after the colon. `/var/cache/nginx` is where NGINX keeps its temporary files; `/run` holds the file with the master's process number (`pid /run/nginx.pid;` in `nginx/sidecar/nginx.conf`). The mount output of the proxy confirms the sizes (`size=1024k` for `/run`).
- Lines 58 to 60: two read-only mounts. One **file** (`./nginx/sidecar/nginx.conf`) replaces the image's `/etc/nginx/nginx.conf`. The folder `build/tls` appears at `/etc/nginx/tls`, which `nginx/sidecar/nginx.conf` uses for the certificate and key.

### The `proxy` service: the reverse proxy

```yaml
  proxy:
    <<: *hardening
    image: *nginx-image
    entrypoint: ["nginx", "-g", "daemon off;"]
    depends_on:
      - backend
      - sidecar
    cap_drop:
      - ALL
    cap_add:
      - CHOWN
      - SETGID
      - SETUID
    # One CPU, and one worker process in nginx.conf: every proxy number is per core. The memory
    # limit covers the tmpfs folders too (their pages count against it): the cache and the
    # temporary files of buffered responses, and the access log of a whole bench run.
    cpus: 1
    mem_limit: 1g
    tmpfs:
      - /var/cache/nginx:size=300m
      - /var/log/nginx/seatwave:size=600m
      - /run:size=1m
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/seatwave:/etc/nginx/seatwave:ro
      - ./build/tls:/etc/nginx/tls:ro
    networks:
      lab:
        ipv4_address: 10.209.9.10
    ports:
      - "127.0.0.1:20900:8443"
      - "127.0.0.1:20901:8444"
      - "127.0.0.1:20902:8080"
      - "127.0.0.1:20903:8081"
      - "127.0.0.1:20904:8445"
      - "127.0.0.1:20905:8446"
      - "127.0.0.1:20906:8447"
      - "127.0.0.1:20907:8451"
      - "127.0.0.1:20908:8456"
      - "127.0.0.1:20909:8459"
      - "127.0.0.1:20914:8460"
      - "127.0.0.1:20920:8090"
```

- Lines 62 to 65 are the same pattern as the sidecar: hardening, the pinned image, the entry point without the helper scripts. The proxy keeps the default network behavior: it is on the `lab` network (lines 88 to 90), with its own address.
- Lines 66 to 68: `depends_on` lists `backend` and `sidecar`, so both start before the proxy. Again: start order only.
- Lines 69 to 74: capabilities, the same three as the sidecar.
- Lines 75 to 77 are a comment: one CPU and one worker process in `nginx.conf`, so every proxy number is "per core". The memory limit counts the pages of the tmpfs folders too: the cache, the temporary files of buffered responses and the access log of a whole bench run. Line 78, `cpus: 1`, and line 79, `mem_limit: 1g`, set the limits. The sum of the tmpfs sizes (300 MB + 600 MB + 1 MB) stays under 1 GiB.
- Lines 80 to 83: three tmpfs folders. `/var/cache/nginx:size=300m` holds the response cache (the `proxy_cache_path` is `/var/cache/nginx/seatwave`, up to `max_size=100m`) and the temporary files. `/var/log/nginx/seatwave:size=600m` holds the access log (`access_log /var/log/nginx/seatwave/access.log seatwave;`), in memory so that writing 1.5 million log lines does not slow the proxy by touching a disk. `/run:size=1m` holds the pid file.
- Lines 84 to 87: three read-only mounts. `./nginx/nginx.conf` replaces `/etc/nginx/nginx.conf`. The folder `./nginx/seatwave` appears as `/etc/nginx/seatwave`, which is why the configuration says `include seatwave/upstreams.conf;`. And `build/tls` appears at `/etc/nginx/tls`. The image's own `/etc/nginx/mime.types` stays, because only the one file and the one folder are replaced.
- Lines 88 to 90: attach to `lab` with the fixed address `10.209.9.10`. SeatWave trusts forwarded headers only from this address.
- Lines 91 to 103: twelve published ports, all on `127.0.0.1`. The container port is the port an NGINX `server` block listens on. Here is the map (the block is in `nginx/seatwave/`):

| On your computer | In the container | Which `server` block |
|---|---|---|
| 20900 | 8443 | `production.conf`: PRODUCTION |
| 20901 | 8444 | `labs.conf`: TLS termination alone |
| 20902 | 8080 | `labs.conf`: plain HTTP, buffering on |
| 20903 | 8081 | `labs.conf`: plain HTTP, buffering off |
| 20904 | 8445 | `labs.conf`: no upstream reuse |
| 20905 | 8446 | `labs.conf`: the wrong timeout |
| 20906 | 8447 | `labs.conf`: through the sidecar |
| 20907 | 8451 | `labs.conf`: gzip level 1 |
| 20908 | 8456 | `labs.conf`: gzip level 6 |
| 20909 | 8459 | `labs.conf`: gzip level 9 |
| 20914 | 8460 | `labs.conf`: a cache lock on every path |
| 20920 | 8090 | `status.conf`: the NGINX counters |

I listed the published ports of the running proxy and they match this table. The image also declares port 80 (`EXPOSE 80`), which shows as `80/tcp` in `docker ps`; it is not published and nothing listens there.

### The `tools` service: the slow clients

```yaml
  # Runs the slow-client lab inside the lab network: docker compose run --rm tools <settings>.
  # Only started on demand (profile "tools"); it publishes no port.
  tools:
    <<: *hardening
    image: seatwave-proxy-backend:1.0.0
    profiles:
      - tools
    entrypoint: ["/opt/seatwave-java/bin/java", "-cp", "/app/lib/*", "io.plainround.seatwave.proxy.slow.SlowClientsCli"]
    cap_drop:
      - ALL
    cpus: 2
    mem_limit: 512m
    tmpfs:
      - /tmp
    networks:
      lab:
        ipv4_address: 10.209.9.30
```

- Lines 105 and 106 are comments: it runs the slow-client lab inside the lab network, started on demand with `docker compose run --rm tools <settings>`, and publishes no port.
- Line 108 is the hardening merge. Line 109 reuses the backend's image by name. There is no `build:` here, so that image must exist; `docker compose up` builds it through the `backend` service.
- Lines 110 and 111: `profiles: - tools`. A **profile** is a label. A service with a profile is skipped by a plain `docker compose up`, and starts only when you name the profile or run the service explicitly. That is why `stack.sh up` starts three containers and not four. (`docker compose config --profiles` prints `tools`.)
- Line 112 replaces the entry point with `java -cp "/app/lib/*" io.plainround.seatwave.proxy.slow.SlowClientsCli`. The image's own `CMD` is not used; the settings file you pass on the command line becomes the program's argument. See `java-slow-clients-walkthrough.md`.
- Lines 113 to 121: all capabilities dropped, two CPUs, 512 MB, a tmpfs at `/tmp`, and the fixed address `10.209.9.30` on `lab`. The slow clients run inside the lab network on purpose: Docker Desktop's port forwarder would buffer on its own and hide what the proxy's buffering does (`ARCHITECTURE.md`).

### The network

```yaml
networks:
  lab:
    ipam:
      config:
        - subnet: 10.209.9.0/24
```

- Line 123 starts the top-level `networks:` section; line 124 names the network `lab`. Compose creates it as `<project>_lab`, here `seatwave-proxy_lab`.
- Lines 125 to 127: `ipam` (IP address management) holds the address plan. `subnet: 10.209.9.0/24` means all addresses from `10.209.9.0` to `10.209.9.255`. `10.209.9.0/24` is a private range, not reachable from the internet. Docker's convention is to give the first usable address, `10.209.9.1`, to the network's gateway (the virtual router). A request that comes from your Mac through Docker Desktop reaches the containers from that address: both the README and my run show `"address":"10.209.9.1"` from `GET /lab/client`. That is also why `nginx/seatwave/status.conf` allows `10.209.9.0/24`.

## Try it yourself

I ran these in a copy of the repository on 11 November 2026 (Docker 24.0.6, Compose v2.23.0-desktop.1) with the project name `seatwave-proxy-notes`, so container names in my output read `seatwave-proxy-notes-...`. With `scripts/stack.sh`, yours read `seatwave-proxy-...`. Start the stack first: `scripts/stack.sh up --run --yes`, and stop it at the end: `scripts/stack.sh down --run --yes`. Times differ on every run.

1. List what Compose understood, then the services that start only on demand:

```
docker compose -p seatwave-proxy-notes config --services
docker compose -p seatwave-proxy-notes config --profiles
```

Output:

```
backend
sidecar
proxy
tools
```

(The first command printed the first three; the second printed `tools`.)

2. Show that the sidecar shares SeatWave's loopback:

```
docker exec seatwave-proxy-notes-sidecar-1 curl -s http://127.0.0.1:8080/health
docker exec seatwave-proxy-notes-proxy-1 curl -s http://127.0.0.1:8080/health
```

Output: both print `{"status":"up"}`. The first reached SeatWave directly, over the loopback it shares with the sidecar. The second reached the proxy's own plain-HTTP block on its port 8080, which passed the request on to SeatWave.

3. Check the limits and the read-only root file system that Docker recorded:

```
docker inspect seatwave-proxy-notes-backend-1 seatwave-proxy-notes-sidecar-1 seatwave-proxy-notes-proxy-1 --format '{{.Name}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}} readonly={{.HostConfig.ReadonlyRootfs}} cap_drop={{.HostConfig.CapDrop}} cap_add={{.HostConfig.CapAdd}}'
```

Output:

```
/seatwave-proxy-notes-backend-1 cpus=4000000000 mem=1073741824 readonly=true cap_drop=[ALL] cap_add=<no value>
/seatwave-proxy-notes-sidecar-1 cpus=1000000000 mem=134217728 readonly=true cap_drop=[ALL] cap_add=[CHOWN SETGID SETUID]
/seatwave-proxy-notes-proxy-1 cpus=1000000000 mem=1073741824 readonly=true cap_drop=[ALL] cap_add=[CHOWN SETGID SETUID]
```

4. Try to write inside the proxy's configuration folder:

```
docker exec seatwave-proxy-notes-proxy-1 touch /etc/nginx/x
```

Output: `touch: cannot touch '/etc/nginx/x': Read-only file system`

5. Run a service from a profile (the slow-client program prints four lines; `sed` keeps lines 1 and 3 here; `--rm` removes the container afterwards):

```
docker compose -p seatwave-proxy-notes run --rm tools /app/config/slow-buffered.properties 2>&1 | sed -n '1p;3p'
```

Output (the numbers differ each time):

```
target: GET http://10.209.9.10:8080/events/1/seats?section=A; 500 slow clients at once, each reading 5000 bytes every 100 ms (50 KB/s), receive buffer 4096 bytes
SeatWave workers: at most 0 of 64 busy at once (0 readings missed); 1.12 worker-seconds in all, 0.0022 s per slow client
```

## Mistakes people make

- **Publishing a port without `127.0.0.1`.** `"20910:8443"` listens on all addresses. This file writes the address on every entry, and `NginxConfigRulesTest` checks it (a mutation test I ran, which removed `127.0.0.1:` from one entry, failed as intended).
- **Wrong indentation.** In YAML, one space too few moves a key to a different parent. `docker compose config --quiet` parses the file and prints nothing when it is valid.
- **Believing `depends_on` waits for readiness.** It orders the starts only. That is why `stack.sh` loops on the health URL after `up`.
- **Writing to a read-only file system.** With `read_only: true`, every folder a program writes to needs a `tmpfs` or a mount. Missing one shows up as a program that starts and then stops.
- **Forgetting that tmpfs uses memory.** A `600m` tmpfs that fills up uses 600 MB of the container's memory limit. The comment at lines 75 to 77 records that the limit covers it.
- **Mixing `network_mode` with `ports`.** A service that joins another's namespace (`network_mode: "service:backend"`) cannot publish ports itself; publish them on the service that owns the namespace.
- **Leaving containers running.** `docker compose down -v --remove-orphans` removes everything of the project. `stack.sh down` also checks that no container, network or volume with the project's label is left.

## Check yourself

1. What does `<<: *hardening` do?
   *It merges the keys of the mapping that has the anchor `&hardening` (`security_opt` and `read_only`) into this service.*
2. Why is the sidecar given no `ports:` and no `networks:`?
   *It uses `network_mode: "service:backend"`, so it lives in the backend's network namespace and shares its address. The backend's published ports are the only ones.*
3. What does `"127.0.0.1:20900:8443"` mean?
   *Listen on 127.0.0.1, port 20900, on your computer, and pass each connection to port 8443 in the container. Only programs on this computer can connect.*
4. Why does `docker compose up` not start the `tools` service?
   *It has `profiles: [tools]`. Such a service starts only when its profile is named or when you run it explicitly with `docker compose run`.*
5. Why does the proxy need `CHOWN`, `SETGID` and `SETUID` when everything else is dropped?
   *The NGINX master starts as root and starts its workers as user `nginx`, which needs the right to change user, group and owner.*

## Further reading

- Compose file reference (the Compose Specification; this repository ran with Compose v2.23.0-desktop.1): https://docs.docker.com/reference/compose-file/
- Services: https://docs.docker.com/reference/compose-file/services/
- Fragments, extensions and merge: https://docs.docker.com/reference/compose-file/fragments/ and https://docs.docker.com/reference/compose-file/extension/
- Profiles: https://docs.docker.com/compose/how-tos/profiles/
- Networks: https://docs.docker.com/reference/compose-file/networks/
- `docker compose` command line: https://docs.docker.com/reference/cli/docker/compose/
- YAML 1.2 specification: https://yaml.org/spec/1.2.2/
