# Docker: images, containers, the Dockerfile and `.dockerignore`

## What it is

**Docker** is a tool that runs a program inside a **container**. A container is a normal program running on your computer, but fenced in by the operating system so that it sees only its own files, its own process list and its own network. Think of it as a sealed room: the program inside cannot see the rest of the house.

A container starts from an **image**. An image is a read-only package that holds a small file system (the program, its libraries, its settings) plus a few instructions (which program to start, as which user). An image is built in **layers**: each step of the build adds one layer on top of the one before, and Docker keeps every layer so it can reuse it next time.

A **Dockerfile** is the recipe for an image: a text file with one instruction per step. A **registry** (for example Docker Hub) is a server that stores images. An image is named `name:tag`, and the **tag** (such as `noble`) picks a version. A tag can be moved to point at a new build. A **digest** (`sha256:` followed by 64 hexadecimal characters) is the fingerprint of the exact bytes of an image. It cannot move.

## Why this repository uses it

The lesson puts a reverse proxy in front of SeatWave, and the proxy has to find SeatWave at a fixed network address. So SeatWave itself must run as a container in the same Docker network as the proxy. The `Dockerfile` turns SeatWave's jars (Java library files, see `java.md`) into an image of about 198 MB that contains only what it needs, and it runs as a user with no rights, so the lab does not teach bad habits.

## Where it appears

| Path | What it does with Docker |
|---|---|
| `Dockerfile` | The recipe for the SeatWave backend image `seatwave-proxy-backend:1.0.0`, in two stages. |
| `.dockerignore` | Lists what must stay out of the build context (the folder Docker reads when it builds), above all the TEST keys. |
| `compose.yaml` | The `backend` service builds this image (`build: .`); the `tools` service reuses it. See `docker-compose.md`. |
| `scripts/stack.sh` | Runs `docker compose ... up -d --build`, which builds the image. See `shell-scripts-walkthrough.md`. |
| `build.gradle.kts` | The task `stackPrepare` collects the jars into `build/stack/lib/` that the Dockerfile copies. See `gradle.md`. |

## How it works underneath

1. **Containers are processes.** There is no second computer inside. The Linux kernel runs the container's program like any other program, but gives it three kinds of fences. **Namespaces** give it its own view of process numbers, network cards and file system. **Control groups** (cgroups) cap how much CPU and memory it may use. **Capabilities** are the small pieces of the administrator's power; a container can have some or none of them.
2. **Docker Desktop on a Mac runs a small Linux virtual machine.** Containers need a Linux kernel, and macOS does not have one. So every container runs inside that virtual machine. The NGINX log inside the proxy container says so: `OS: Linux 6.4.16-linuxkit`. A request from your Mac to a published port also crosses Docker Desktop's port forwarder.
3. **A build sends a context.** `docker build` (and `docker compose build`) first sends a folder, the **build context**, to the builder. Only files in the context can be copied into the image. `.dockerignore` shrinks the context.
4. **Each instruction makes a layer.** `FROM` chooses the starting layers. `COPY` and `RUN` each add one. The builder caches a layer and reuses it if the instruction and its input files did not change.
5. **A multi-stage build uses several `FROM` lines.** Each `FROM` starts a new stage with a clean file system. A later stage can copy files out of an earlier one with `COPY --from=<stage>`. Only the last stage becomes the final image, so the first stage's compiler and tools are thrown away.
6. **An image is not a running thing.** `docker run` (or Compose) creates a container from it: the image's layers, plus one thin writable layer on top, plus the settings you give (ports, memory, user).

## Walkthrough of this repository's use

### The whole file in two stages

The file has a header comment and two stages.

```dockerfile
# SeatWave's lab backend as a container image, in two stages. Both base images are Docker Official
# Images, pinned by tag and digest, checked on Docker Hub on 11 November 2026 (both have arm64).
# Build the jars first: ./gradlew stackPrepare (scripts/stack.sh does it).
```

Lines 1 to 3 are comments (a line that starts with `#` is ignored by Docker). They say that both base images are **Docker Official Images**, pinned by tag and digest, that the author checked them on Docker Hub on 11 November 2026, that both have an `arm64` build (the kind an ARM-based Mac needs), and that the jars must be built first with `./gradlew stackPrepare`, which `scripts/stack.sh` does.

### Stage 1: link a small Java runtime

```dockerfile
# Stage 1: a JDK, used only to work out which Java modules SeatWave needs and to link a runtime
# with just those, so the final image carries no compiler and no unused modules.
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS runtime
COPY build/stack/lib/ /app/lib/
RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 \
        --class-path '/app/lib/*' /app/lib/seatwave-proxy-1.0.0.jar)" \
    && echo "linking modules: ${modules}" \
    && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files \
        --output /opt/seatwave-java
```

- Lines 5 and 6 are comments. They explain the goal: work out which Java **modules** SeatWave needs and link a runtime with only those. A module is a named part of the JDK, such as `java.net.http`. The JDK has dozens. Leaving out the unused ones makes the image smaller, and leaving out the compiler leaves nothing for an attacker to use.
- Line 7 is `FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350...AS runtime`. `FROM` starts a stage. `eclipse-temurin` is the image name (a free build of the JDK). The tag `25.0.5_8-jdk-noble` reads: Java 25.0.5, build 8, the full JDK (not only a runtime), on the Ubuntu 24.04 release named "noble". `@sha256:...` pins the exact bytes, so a later change of the tag cannot change this build. `AS runtime` gives the stage a name so stage 2 can copy from it. The name is a label only. This stage holds the JDK, but the files taken from it are the linked runtime.
- Line 8 is `COPY build/stack/lib/ /app/lib/`. `COPY` copies from the build context into the image. The left side is a path inside the context (the folder that holds `Dockerfile`); the right side is a path inside the image. The folder `/app/lib/` is created if it does not exist. `build/stack/lib/` holds SeatWave's jar and the libraries it needs (Jackson, SLF4J, Logback, result4j). I listed it: 3.5 MB.
- Line 9 starts a `RUN` instruction. `RUN` runs a command while building and keeps the changes as a new layer. This one is a shell command: `modules="$(jdeps ...)"` runs the command inside `$( ... )` and stores what it prints in the shell variable `modules`.
  - `jdeps` is a JDK tool that reads jars and lists the modules their classes use.
  - `--print-module-deps` prints just those modules, as one comma-separated line.
  - `--ignore-missing-deps` does not stop when a library refers to something that is not present. Optional dependencies are normal.
  - `--multi-release 25` chooses which version of a "multi-release jar" to read. Some jars hold a different copy of a class for each Java version. 25 is the one this image runs.
  - `--class-path '/app/lib/*'` tells `jdeps` where to find the libraries. The single quotes stop the shell from expanding the `*` itself. The tool understands `*` as "every jar in this folder".
  - `/app/lib/seatwave-proxy-1.0.0.jar` is the jar to analyze.
- The `\` at the end of a line means "the command continues on the next line". Lines 10 and 11 are the rest of the `jdeps` command.
- Line 11 reads `&& echo "linking modules: ${modules}" \`. `&&` means "run the next command only if the one before it succeeded". If `jdeps` fails, the build stops. The `echo` prints the list so you can see it in the build output. The real output on this machine was `linking modules: java.base,java.desktop,java.net.http,java.sql,jdk.httpserver`.
- Line 12 reads `&& jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files \`. `jlink` is the JDK tool that builds a custom runtime.
  - `--add-modules "${modules}"` names the modules to include. `jlink` also adds the modules those depend on. `java --list-modules` run in the finished image lists ten modules: `java.base`, `java.datatransfer`, `java.desktop`, `java.logging`, `java.net.http`, `java.prefs`, `java.sql`, `java.transaction.xa`, `java.xml` and `jdk.httpserver`.
  - `--strip-debug` removes debugging information from the classes. `--no-man-pages` and `--no-header-files` leave out manual pages and C header files. All three only save space.
- Line 13 is `--output /opt/seatwave-java`: the folder where the new runtime is written. It will contain `bin/java`.

### Stage 2: the plain operating system and the runtime

```dockerfile
# Stage 2: the plain operating system image and the linked runtime, run by a user without a
# login, a home folder or any rights beyond reading the application.
FROM ubuntu:noble@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin seatwave
COPY --from=runtime /opt/seatwave-java /opt/seatwave-java
COPY --from=runtime /app/lib/ /app/lib/
COPY config/ /app/config/
USER 10001
```

- Lines 15 and 16 are comments: the final image is the plain operating system image plus the linked runtime, run by a user "without a login, a home folder or any rights beyond reading the application".
- Line 17 starts stage 2 with `FROM ubuntu:noble@sha256:fe8156...`. This is Ubuntu 24.04 without Java. Its layer is about 101 MB; the linked runtime layer that arrives later is 93.8 MB. Both numbers come from `docker image history` below. Nothing from stage 1 is here yet.
- Line 18 is `RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin seatwave`. It creates an operating-system user named `seatwave`.
  - `--system` makes a service account (no password, no expiry).
  - `--uid 10001` fixes the numeric user id. A high number does not collide with any real account.
  - `--no-create-home` does not create the home folder. I checked: `/etc/passwd` names `/home/seatwave`, but the folder does not exist.
  - `--shell /usr/sbin/nologin` means nobody can log in as this user.
  - During the build, `useradd` prints `useradd warning: seatwave's uid 10001 is greater than SYS_UID_MAX 999`. It is only a warning: system accounts are normally numbered below 1000, and the build goes on.
- Line 19 is `COPY --from=runtime /opt/seatwave-java /opt/seatwave-java`. `--from=runtime` takes the files from stage 1 (named `runtime` on line 7), not from the build context. This is how the linked runtime arrives without the JDK.
- Line 20 copies the jars from stage 1 the same way. Line 21 copies the folder `config/` from the build context: the settings files, including `config/backend-container.properties` (see `java-properties.md`).
- Line 22 is `USER 10001`. Everything after it, and the container's main program, runs as user 10001. The number (not the name) is used so a platform can check that the user is not root without reading the image's `/etc/passwd`. `docker exec ... id` printed `uid=10001(seatwave) gid=999(seatwave) groups=999(seatwave)`.

### How the program starts

```dockerfile
# The exec form makes Java process 1, so docker stop's SIGTERM reaches it and its shutdown hook
# closes the listeners. TCP_NODELAY on every accepted connection (the JDK server's own switch, off
# by default): without it, a response's headers and body leave as two small writes, and on the
# sidecar's loopback link Nagle's algorithm held the body about 40 ms for each request (Section 9).
ENTRYPOINT ["/opt/seatwave-java/bin/java", "-XX:MaxRAMPercentage=50", "-Dsun.net.httpserver.nodelay=true", "-cp", "/app/lib/*"]
CMD ["io.plainround.seatwave.proxy.backend.BackendCli", "/app/config/backend-container.properties"]
```

- Lines 23 to 26 are comments. The first says why the exec form is used (see below). The second is a measured fact about this lab: the JDK's built-in HTTP server leaves **Nagle's algorithm** on by default. Nagle's algorithm makes the operating system wait a short time to join small writes into one packet. A response's headers and body leave as two small writes, and on the sidecar's loopback link the body waited about 40 ms for every request. The switch `-Dsun.net.httpserver.nodelay=true` turns it off. `ARCHITECTURE.md` records the same decision, and `reverse-proxy-concepts.md` explains the sidecar.
- Line 27 is `ENTRYPOINT [...]` in **exec form**: a JSON list. Docker starts exactly this program with exactly these arguments, with no shell in between. That makes Java **process 1** in the container. `docker stop` sends the signal SIGTERM to process 1; with a shell in the way the signal would not reach Java. The Java program has a shutdown hook (a piece of code that runs when the program is asked to stop) that closes its listeners.
  - `/opt/seatwave-java/bin/java` is the runtime that `jlink` made.
  - `-XX:MaxRAMPercentage=50` lets the Java heap grow to half of the container's memory limit. With `mem_limit: 1g` that is about 512 MiB.
  - `-Dsun.net.httpserver.nodelay=true` sets a **system property**, a named setting the JDK's HTTP server reads. It is the TCP_NODELAY switch from the comment.
  - `-cp`, `"/app/lib/*"` is the class path. Java expands `/app/lib/*` itself to every jar in the folder. This is why no shell is needed.
- Line 28 is `CMD [...]`. In exec form, `CMD` supplies the default **arguments** after the entry point: here the main class `io.plainround.seatwave.proxy.backend.BackendCli` and the settings file `/app/config/backend-container.properties`. If you pass arguments to `docker run`, they replace `CMD`. The `tools` service in `compose.yaml` replaces the entry point as well.

### The ignore file

```text
# The image needs only the collected jars and the settings; everything else stays out of the
# build context, above all build/tls, whose TEST keys must never be copied into an image.
*
!build/stack/lib/
!config/
```

- Lines 1 and 2 are comments: the image needs only the jars and the settings, and above all `build/tls` (the TEST keys made by `./gradlew testCertificates`) must never be copied into an image. Anything copied into an image stays in its layers for good, even if a later step deletes it.
- Line 3 is `*`: ignore every file and folder in the context.
- Line 4 is `!build/stack/lib/`: a `!` puts a path back. The jars are allowed.
- Line 5 is `!config/`: the settings are allowed.

I checked the result: a container started from the image and asked to list `/run/tls` answered `No such file or directory`, and `/app` held only `config` and `lib`. (The running stack mounts `build/tls` into the containers at run time, read-only. It is never part of the image.)

## Try it yourself

I ran these in a copy of the repository on 11 November 2026 with Docker 24.0.6 and Compose v2.23.0-desktop.1. Timings and layer sizes differ on every machine. I used the Compose project name `seatwave-proxy-notes`; you can leave the name out.

1. Collect the jars, then rebuild the backend image without the cache and show only the interesting lines:

```
./gradlew -q stackPrepare
docker compose -p seatwave-proxy-notes build --no-cache --progress plain backend 2>&1 | grep -E 'linking modules|useradd warning|naming to'
```

Expected output:

```
useradd warning: seatwave's uid 10001 is greater than SYS_UID_MAX 999
linking modules: java.base,java.desktop,java.net.http,java.sql,jdk.httpserver
#14 naming to docker.io/library/seatwave-proxy-backend:1.0.0 done
```

2. Read the settings Docker stored in the image:

```
docker image inspect seatwave-proxy-backend:1.0.0 --format '{{.Config.User}} | {{.Config.Entrypoint}} | {{.Config.Cmd}} | {{.Size}}'
```

Output:

```
10001 | [/opt/seatwave-java/bin/java -XX:MaxRAMPercentage=50 -Dsun.net.httpserver.nodelay=true -cp /app/lib/*] | [io.plainround.seatwave.proxy.backend.BackendCli /app/config/backend-container.properties] | 198287992
```

3. See the layers, newest first. Each of your instructions is one row:

```
docker image history seatwave-proxy-backend:1.0.0 --format '{{.Size}}  {{.CreatedBy}}' | cut -c1-80 | head -8
```

Output:

```
0B  CMD ["io.plainround.seatwave.proxy.backend.B…
0B  ENTRYPOINT ["/opt/seatwave-java/bin/java" "-…
0B  USER 10001
14.3kB  COPY config/ /app/config/ # buildkit
3.65MB  COPY /app/lib/ /app/lib/ # buildkit
93.8MB  COPY /opt/seatwave-java /opt/seatwave-java #…
4.75kB  RUN /bin/sh -c useradd --system --uid 10001 …
0B  /bin/sh -c #(nop)  CMD ["/bin/bash"]
```

4. Check that no keys are inside the image and which Java it carries (`--rm` removes the temporary container afterwards):

```
docker run --rm --entrypoint sh seatwave-proxy-backend:1.0.0 -c 'ls /app; ls /run/tls 2>&1; id'
docker run --rm --entrypoint /opt/seatwave-java/bin/java seatwave-proxy-backend:1.0.0 -version 2>&1 | head -1
```

Output:

```
config
lib
ls: cannot access '/run/tls': No such file or directory
uid=10001(seatwave) gid=999(seatwave) groups=999(seatwave)
openjdk version "25.0.5" 2026-10-20 LTS
```

## Mistakes people make

- **Using a moving tag as the base.** `ubuntu:noble` today and `ubuntu:noble` next month are different bytes. This repository adds `@sha256:...` to both `FROM` lines, and `NginxConfigRulesTest` fails the build if a `FROM` line has no digest (see `tests-stack-walkthrough.md`).
- **Copying secrets into an image.** A `COPY .` with no `.dockerignore` would take `build/tls`, with the TEST private key, into a layer. The `*` rule with `!` exceptions is a safe list, not a block list.
- **Running as root.** Without `USER`, the program runs as root inside the container. A bug in it would then give an attacker root inside the container. Here `USER 10001` and, in `compose.yaml`, all capabilities dropped limit the damage.
- **Shell form of `ENTRYPOINT`.** Writing `ENTRYPOINT java -cp ...` without brackets starts `/bin/sh -c "java ..."`, and the shell can become process 1. The shell may not pass SIGTERM on, so `docker stop` can wait ten seconds, kill the container, and the shutdown hook never runs. This file uses the exec form.
- **Expecting `RUN` to remember the shell state between lines.** Each `RUN` is a new shell. That is why the `jdeps` result is stored in `modules` and used in the same `RUN`, joined by `&&`.
- **A fat final image.** Keeping the JDK stage as the final image would add the compiler and every module. The second `FROM` leaves them behind.

## Check yourself

1. What is the difference between an image and a container?
   *An image is the read-only package; a container is a running (or stopped) instance made from it, with a thin writable layer.*
2. Why does `Dockerfile` have two `FROM` lines?
   *Stage 1 uses the full JDK to find and link only the needed Java modules. Stage 2 starts again from plain Ubuntu and copies in just the linked runtime, the jars and the settings.*
3. What does `@sha256:...` after a tag change?
   *It pins the exact image bytes. A tag can be moved to a new build; a digest cannot.*
4. Why is the `ENTRYPOINT` written as a JSON list?
   *The exec form starts Java directly as process 1, so `docker stop`'s SIGTERM reaches it and its shutdown hook can close the listeners.*
5. What does line 3 to 5 of `.dockerignore` do?
   *`*` ignores everything, and the two `!` lines put back only `build/stack/lib/` and `config/`, so `build/tls` can never enter the build context.*

## Further reading

- Dockerfile reference (instructions `FROM`, `COPY`, `RUN`, `USER`, `ENTRYPOINT`, `CMD`): https://docs.docker.com/reference/dockerfile/
- Multi-stage builds: https://docs.docker.com/build/building/multi-stage/
- `.dockerignore` and the build context: https://docs.docker.com/build/concepts/context/
- Pulling an image by digest: https://docs.docker.com/reference/cli/docker/image/pull/
- `jdeps` and `jlink` (JDK 25): https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeps.html and https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html
- `useradd`: the manual page on a Linux system, `man useradd`
