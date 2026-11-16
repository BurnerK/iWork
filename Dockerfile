FROM node:26.13.1-bookworm-slim@sha256:5df79989ed685e09f0a98019db4cebfb22fb9df1a53c0161664a011f7ff6b0f1 AS web
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run typecheck && npm test && npm run build

FROM eclipse-temurin:21-jdk-jammy@sha256:432e54724c3e57d1846aeb886f4d339f3b0522931c4e64119c2797013b7cfc3c AS java
WORKDIR /build
COPY backend/ ./backend/
COPY --from=web /build/frontend/dist ./frontend/dist
RUN backend/mvnw -B -f backend/pom.xml -Pdistribution -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c
RUN groupadd --gid 10001 iwork && useradd --uid 10001 --gid iwork --no-create-home iwork \
    && mkdir -p /data/files && chown iwork:iwork /data/files
WORKDIR /app
COPY --from=java /build/backend/target/iwork-0.1.0-SNAPSHOT.jar /app/iwork.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/iwork.jar"]
