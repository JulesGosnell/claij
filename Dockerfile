# Dockerfile for claij
# Multi-stage build: build uberjar, then run in slim JRE

# Stage 1: Build
FROM clojure:temurin-21-tools-deps AS builder

WORKDIR /app

# Copy dependency files first (for layer caching)
COPY deps.edn ./
COPY build.clj ./

# Download dependencies
RUN clojure -P

# Copy source
COPY src/ src/
COPY resources/ resources/

# Build uberjar
RUN clojure -T:build uber

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy uberjar from builder
COPY --from=builder /app/target/claij.jar ./claij.jar

# Fly.io uses PORT env var
ENV PORT=8080

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Run
CMD ["java", "-jar", "claij.jar", "-p", "8080"]
