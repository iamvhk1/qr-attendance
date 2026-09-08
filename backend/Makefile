# ============================================================
#  QR Attendance System — Makefile
#  Works on: Mac, Linux, Windows (Git Bash / WSL)
#
#  Usage:
#    make          -> start the server
#    make start    -> start the server (skip tests)
#    make test     -> run all 322 tests
#    make build    -> compile only
#    make package  -> build fat JAR
#    make clean    -> wipe target/ and data/
#    make help     -> show this help
# ============================================================

MVN := $(shell if [ -f ./mvnw ]; then echo "./mvnw"; else echo "mvn"; fi)
PORT := 8080

.PHONY: all start run test build package clean help

all: start

## start: Build and start the backend server (default)
start:
	@echo ""
	@echo " Starting QR Attendance backend on http://localhost:$(PORT)"
	@echo " H2 console: http://localhost:$(PORT)/h2-console"
	@echo " Press Ctrl+C to stop."
	@echo ""
	$(MVN) spring-boot:run -DskipTests --no-transfer-progress

run: start

## test: Run all integration and unit tests
test:
	@echo ""
	@echo " Running all tests (322 expected)..."
	@echo ""
	$(MVN) clean test --no-transfer-progress

## build: Compile only — no tests, no server start
build:
	$(MVN) clean compile -DskipTests --no-transfer-progress
	@echo " Compile done."

## package: Build runnable fat JAR in target/
package:
	$(MVN) clean package -DskipTests --no-transfer-progress
	@echo ""
	@echo " JAR built: $$(ls target/qr-attendance-*.jar)"
	@echo " Run with:  java -jar target/qr-attendance-*.jar"

## clean: Remove build output and wipe the H2 database (fresh start)
clean:
	$(MVN) clean --no-transfer-progress
	@rm -rf data/
	@echo " Cleaned target/ and data/ (H2 database)"

## help: Show available commands
help:
	@echo ""
	@echo "  QR Attendance System — available make targets:"
	@echo ""
	@grep -E '^## ' Makefile | sed 's/## /  make /'
	@echo ""
