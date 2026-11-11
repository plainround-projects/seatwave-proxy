# SeatWave's lab backend as a container image, in two stages. Both base images are Docker Official
# Images, pinned by tag and digest, checked on Docker Hub on 11 November 2026 (both have arm64).
# Build the jars first: ./gradlew stackPrepare (scripts/stack.sh does it).

# Stage 1: a JDK, used only to work out which Java modules SeatWave needs and to link a runtime
# with just those, so the final image carries no compiler and no unused modules.
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS runtime
COPY build/stack/lib/ /app/lib/
RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 \
        --class-path '/app/lib/*' /app/lib/seatwave-proxy-1.0.1.jar)" \
    && echo "linking modules: ${modules}" \
    && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files \
        --output /opt/seatwave-java

# Stage 2: the plain operating system image and the linked runtime, run by a user without a
# login, a home folder or any rights beyond reading the application. 10001 is above Ubuntu's
# range of system user ids (up to 999), so it is an ordinary user; /nonexistent is Debian's
# name for "no home folder", which it really does not have.
FROM ubuntu:noble@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
RUN useradd --uid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin seatwave
COPY --from=runtime /opt/seatwave-java /opt/seatwave-java
COPY --from=runtime /app/lib/ /app/lib/
COPY config/ /app/config/
USER 10001
# The exec form makes Java process 1, so docker stop's SIGTERM reaches it and its shutdown hook
# closes the listeners. TCP_NODELAY on every accepted connection (the JDK server's own switch, off
# by default): without it, a response's headers and body leave as two small writes, and on the
# sidecar's loopback link Nagle's algorithm held the body about 40 ms for each request (Section 9).
ENTRYPOINT ["/opt/seatwave-java/bin/java", "-XX:MaxRAMPercentage=50", "-Dsun.net.httpserver.nodelay=true", "-cp", "/app/lib/*"]
CMD ["io.plainround.seatwave.proxy.backend.BackendCli", "/app/config/backend-container.properties"]
