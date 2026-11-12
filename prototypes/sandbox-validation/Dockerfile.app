FROM docker:29-cli@sha256:2357639ad1ea7d0194134bf3292622fd4e562483966ed674e5d7c945b5f3b28d AS cli
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
COPY --from=cli /usr/local/bin/docker /usr/local/bin/docker
COPY target/classes /app/classes
COPY target/lib /app/lib
WORKDIR /app
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp/codex", "-cp", "/app/classes:/app/lib/*", "SandboxProbe"]
