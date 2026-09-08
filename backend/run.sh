#!/usr/bin/env bash
# ============================================================
#  QR Attendance System — Mac / Linux Launcher
#  Usage:  ./run.sh            -> Build and start the server
#          ./run.sh start      -> Start server (skip tests)
#          ./run.sh test       -> Run all tests
#          ./run.sh build      -> Compile only
#          ./run.sh clean      -> Wipe target/ and data/
# ============================================================

set -e

CMD="${1:-start}"

echo ""
echo " ============================================="
echo "  QR Attendance System — Backend"
echo "  Running: $CMD"
echo " ============================================="
echo ""

# ── Check Java ────────────────────────────────────────────
if ! java -version &>/dev/null; then
    echo " ERROR: Java not found."
    echo " Install Java 21 from https://adoptium.net/"
    exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [ "$JAVA_MAJOR" -lt 21 ] 2>/dev/null; then
    echo " WARNING: Java $JAVA_MAJOR detected. Java 21+ is required."
    echo " Install Java 21 from https://adoptium.net/"
    exit 1
fi

# ── Pick Maven binary (wrapper → system) ─────────────────
if [ -f ./mvnw ]; then
    chmod +x ./mvnw
    MVN="./mvnw"
elif command -v mvn &>/dev/null; then
    MVN="mvn"
else
    echo " ERROR: Maven not found and ./mvnw not present."
    echo " Install Maven: https://maven.apache.org/download.cgi"
    exit 1
fi

# ── Commands ──────────────────────────────────────────────
case "$CMD" in
  start|run)
    echo " Starting server on http://localhost:8080"
    echo " H2 console at  http://localhost:8080/h2-console"
    echo " Press Ctrl+C to stop."
    echo ""
    $MVN spring-boot:run -DskipTests --no-transfer-progress
    ;;

  test)
    echo " Running all tests..."
    echo ""
    $MVN clean test --no-transfer-progress
    ;;

  build)
    echo " Compiling project (no tests, no run)..."
    $MVN clean compile -DskipTests --no-transfer-progress
    echo " Build complete. JAR will be in target/"
    ;;

  package)
    echo " Building fat JAR..."
    $MVN clean package -DskipTests --no-transfer-progress
    echo ""
    echo " JAR created: target/qr-attendance-*.jar"
    echo " Run it with: java -jar target/qr-attendance-*.jar"
    ;;

  clean)
    echo " Cleaning build artifacts..."
    $MVN clean --no-transfer-progress
    if [ -d data ]; then
        rm -rf data/
        echo " Deleted data/ (H2 database files — fresh start next run)"
    fi
    echo " Clean complete."
    ;;

  *)
    echo " Unknown command: $CMD"
    echo " Valid commands: start, test, build, package, clean"
    exit 1
    ;;
esac
