# ==============================================================================
#                 QR ATTENDANCE SYSTEM — BACKEND MAKEFILE
# ==============================================================================
# Cross-platform Makefile: works on Windows, macOS, and Linux
# Matches all commands in commands.txt (Section 1 & Section 2)

ifeq ($(OS),Windows_NT)
    MVN ?= $(if $(wildcard mvnw.cmd),mvnw.cmd,mvn)
    RM_DIR = cmd /C if exist $(1) rmdir /S /Q $(1)
else
    MVN ?= $(if $(wildcard ./mvnw),./mvnw,mvn)
    RM_DIR = rm -rf $(1)
endif

PORT ?= 8080
SECRET ?= changeme-admin-secret-2026
CLASS ?= PresenceServiceTest
METHOD ?= PresenceServiceTest\#returnsExistingIfAlreadyScanned
JAR_FILE := target/qr-attendance-0.0.1-SNAPSHOT.jar

.PHONY: all help compile clean-compile test test-class test-method test-trace \
        test-presence test-session test-course test-student \
        start run run-custom package package-verify jar clean h2

all: help

## ── Compilation & Cleaning ──────────────────────────────────────────
## compile: Compile all backend source code without running tests
compile:
	$(MVN) compile

## clean-compile: Clean previous build artifacts and recompile
clean-compile:
	$(MVN) clean compile

## ── Running Tests ───────────────────────────────────────────────────
## test: Run the entire test suite (all 358 tests)
test:
	$(MVN) test

## test-class: Run a specific test class (usage: make test-class CLASS=SessionServiceTest)
test-class:
	$(MVN) test -Dtest=$(CLASS)

## test-method: Run a specific test method (usage: make test-method METHOD=PresenceServiceTest#returnsExistingIfAlreadyScanned)
test-method:
	$(MVN) test -Dtest="$(METHOD)"

## test-trace: Run tests with detailed stack traces on errors (-e)
test-trace:
	$(MVN) test -e

## Specific test class shortcuts from commands.txt
test-presence:
	$(MVN) test -Dtest=PresenceServiceTest

test-session:
	$(MVN) test -Dtest=SessionServiceTest

test-course:
	$(MVN) test -Dtest=CourseControllerTest

test-student:
	$(MVN) test -Dtest=StudentScanControllerTest

## ── Running Backend Server ─────────────────────────────────────────
## start: Start Spring Boot server in dev mode (alias for run, port 8080)
start: run

## run: Start Spring Boot server in dev mode (http://localhost:8080)
run:
	@echo ""
	@echo "Starting QR Attendance backend on http://localhost:$(PORT)..."
	@echo "H2 Web Console available at http://localhost:$(PORT)/h2-console"
	@echo "Press Ctrl+C to stop."
	@echo ""
	$(MVN) spring-boot:run

## run-custom: Start server with custom port & admin secret (usage: make run-custom PORT=8081 SECRET=my-secret)
run-custom:
	@echo "Starting backend on port $(PORT) with custom admin secret..."
	$(MVN) spring-boot:run -Dspring-boot.run.arguments="--server.port=$(PORT) --app.admin.secret=$(SECRET)"

## ── Packaging & Running Executable JAR ─────────────────────────────
## package: Package executable JAR (skipping tests for speed)
package:
	$(MVN) package -DskipTests
	@echo "Packaged JAR created at $(JAR_FILE)"

## package-verify: Package executable JAR with full test verification
package-verify:
	$(MVN) package
	@echo "Packaged JAR created with test verification at $(JAR_FILE)"

$(JAR_FILE):
	$(MVN) package -DskipTests

## jar: Run the packaged JAR directly (builds first if not present)
jar: $(JAR_FILE)
	java -jar $(JAR_FILE)

## ── Database & Cleanup ──────────────────────────────────────────────
## clean: Wipe target/ build output and data/ (H2 database files)
clean:
	$(MVN) clean
	@$(call RM_DIR,data)
	@echo "Cleaned target/ and data/ directories."

## h2: Print connection guide for the H2 Web Console
h2:
	@echo ""
	@echo "============================================================"
	@echo "             H2 WEB CONSOLE CONNECTION DETAILS              "
	@echo "============================================================"
	@echo " 1. Make sure backend is running (make run)"
	@echo " 2. Open browser:  http://localhost:$(PORT)/h2-console"
	@echo " 3. Enter settings:"
	@echo "      Driver Class: org.h2.Driver"
	@echo "      JDBC URL:     jdbc:h2:file:./data/qr-attendance-db"
	@echo "      User Name:    sa"
	@echo "      Password:     (leave blank)"
	@echo " 4. Click 'Connect'"
	@echo "============================================================"
	@echo ""

## ── Help Menu ──────────────────────────────────────────────────────
## help: Show available backend targets and usage
help:
	@echo ""
	@echo "=================================================================="
	@echo "           QR Attendance System — Backend Makefile Targets        "
	@echo "=================================================================="
	@echo " Compilation & Cleaning:"
	@echo "   make compile             Compile source code without tests"
	@echo "   make clean-compile       Clean build artifacts and recompile"
	@echo "   make clean               Delete target/ and reset H2 data/"
	@echo ""
	@echo " Testing:"
	@echo "   make test                Run the full test suite (358 tests)"
	@echo "   make test-class          Run test class (CLASS=PresenceServiceTest)"
	@echo "   make test-method         Run test method (METHOD=Class#method)"
	@echo "   make test-trace          Run tests with detailed stack trace (-e)"
	@echo "   make test-presence       Run PresenceServiceTest"
	@echo "   make test-session        Run SessionServiceTest"
	@echo "   make test-course         Run CourseControllerTest"
	@echo "   make test-student        Run StudentScanControllerTest"
	@echo ""
	@echo " Running & Packaging:"
	@echo "   make run / make start    Start Spring Boot dev server (port 8080)"
	@echo "   make run-custom          Run with custom PORT and SECRET"
	@echo "   make package             Build fat JAR (skipping tests)"
	@echo "   make package-verify      Build fat JAR with full test verification"
	@echo "   make jar                 Run target/qr-attendance-0.0.1-SNAPSHOT.jar"
	@echo ""
	@echo " Database & Help:"
	@echo "   make h2                  Display H2 console connection settings"
	@echo "   make help                Show this help message"
	@echo "=================================================================="
	@echo ""
