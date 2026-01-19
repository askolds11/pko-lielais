# Multi-stage Dockerfile for Street Routing Optimizer
# Includes Java 21 for Timefold solver and Python 3 for OSMnx graph loading

# ============================================
# Stage 1: Build the application
# ============================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Create gradle.properties without hardcoded Java home (use container's Java)
RUN echo "kotlin.code.style=official" > gradle.properties && \
    echo "org.gradle.jvmargs=-Xmx4096m" >> gradle.properties

# Download dependencies (cached layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src/ src/
COPY scripts/ scripts/

# Build the application
RUN ./gradlew bootJar --no-daemon -x test

# ============================================
# Stage 2: Runtime image
# ============================================
FROM eclipse-temurin:21-jre

# Install Python, pip, curl, and build dependencies for Python packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    python3-dev \
    curl \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Create Python virtual environment and install OSMnx
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Install Python dependencies
RUN pip install --no-cache-dir \
    osmnx>=1.9.0 \
    networkx>=3.0 \
    pyrosm>=0.6.0

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/build/libs/*.jar app.jar

# Copy Python scripts
COPY scripts/ scripts/

# Create directories for data and caches
RUN mkdir -p /app/data /app/cache /app/config /app/benchmark-data /app/benchmark-report

# Environment variables
ENV JAVA_OPTS="-Xmx2g -Xms512m"
ENV SPRING_PROFILES_ACTIVE=docker
ENV PYTHON_PATH=/opt/venv/bin/python3
# OSM file path - can be overridden via environment variable
ENV OSM_FILE_PATH=/app/cache/latvia-260114.osm.pbf
# Graph cache path
ENV GRAPH_CACHE_PATH=/app/cache/graph_cache.json

# Expose ports
# 8080 - REST API and Web UI
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Default command: Run Spring Boot application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
