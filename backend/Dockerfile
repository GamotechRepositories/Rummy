# Multi-Stage Dockerfile for Rummy Platform Backend (Spring Boot + Java 21)
# -------------------------------------------------------------
# Stage 1: Build Stage
# -------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy parent and module pom files first for dependency caching
COPY pom.xml ./pom.xml
COPY game-engine/pom.xml ./game-engine/pom.xml
COPY game-service/pom.xml ./game-service/pom.xml

# Download dependencies offline
RUN mvn -B dependency:go-offline -DskipTests || true

# Copy source trees
COPY game-engine/src ./game-engine/src
COPY game-service/src ./game-service/src

# Package production executable jar
RUN mvn clean package -DskipTests -B

# -------------------------------------------------------------
# Stage 2: Runtime Production Image
# -------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Create non-root application user for production security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy compiled jar from build stage
COPY --from=builder /app/game-service/target/game-service-*.jar app.jar

# Configuration environment variables
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8081

# Expose HTTP / WebSocket port
EXPOSE 8081

USER appuser

# Container healthcheck
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
