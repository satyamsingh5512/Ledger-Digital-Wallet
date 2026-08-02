# =====================================================================================
# Multi-stage build: compile with the full Gradle/JDK toolchain, ship only a slim JRE
# runtime image. This keeps the final image small (~200MB vs ~700MB+ for a JDK+Gradle
# image) and avoids leaking build tools, Gradle caches, or source code into production.
# =====================================================================================

# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

# Copy the wrapper and build files first so Gradle dependency resolution is cached in
# its own Docker layer, independent of source code changes (faster rebuilds).
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew && ./gradlew --version

# Warm the dependency cache before copying source, so editing a .java file doesn't
# invalidate this (typically the slowest) layer.
RUN ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as a non-root user — never run the app as root in a container.
RUN addgroup -S walletsys && adduser -S walletsys -G walletsys

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN mkdir -p /app/logs && chown -R walletsys:walletsys /app

USER walletsys

EXPOSE 8080

# Container-native memory sizing: let the JVM size its heap as a percentage of the
# container's memory limit (set via `docker run -m` / compose `mem_limit` / k8s
# resources.limits.memory) rather than a fixed -Xmx, so the same image behaves
# correctly regardless of the environment's resource allocation.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -q -O- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
