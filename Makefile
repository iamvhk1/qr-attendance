# ==============================================================================
#                 QR ATTENDANCE SYSTEM — ROOT MAKEFILE
# ==============================================================================
# Master Makefile orchestrating all project components across Windows, Mac, & Linux.
# Encapsulates every section and command from commands.txt.

# Operating System Detection & Tool Configuration
ifeq ($(OS),Windows_NT)
    PYTHON ?= python
    RM_DIR = cmd /C if exist $(1) rmdir /S /Q $(1)
else
    PYTHON ?= python3
    RM_DIR = rm -rf $(1)
endif

PORT ?= 8080
SECRET ?= changeme-admin-secret-2026
CLASS ?= PresenceServiceTest
METHOD ?= PresenceServiceTest\#returnsExistingIfAlreadyScanned
FILE ?=

.PHONY: all help prereqs check check-prereqs \
        backend-compile backend-clean-compile backend-test backend-test-class \
        backend-test-presence backend-test-session backend-test-course backend-test-student \
        backend-test-method backend-test-trace backend-run backend-start backend-run-custom \
        backend-package backend-package-verify backend-jar backend-clean h2 \
        frontend-install frontend-dev frontend-start frontend-build frontend-lint \
        frontend-preview frontend-clean frontend-clean-all \
        cli-install cli-run cli-app cli-load-test cli-clean \
        api-test api-test-ps \
        git-status git-diff git-restore git-log \
        install-all test-all build-all clean-all

all: help

# ==============================================================================
# 0. PREREQUISITES CHECK (commands.txt Section 0)
# ==============================================================================
## prereqs / check: Verify Java, Node.js, NPM, and Python installations
prereqs: check-prereqs
check: check-prereqs

check-prereqs:
	@echo ""
	@echo "============================================================"
	@echo "          0. PREREQUISITES & ENVIRONMENT CHECK              "
	@echo "============================================================"
	@echo "Java version (Requires Java 17 or Java 21):"
	@java -version
	@echo ""
	@echo "Node.js & NPM versions:"
	@node -v
	@npm -v
	@echo ""
	@echo "Python version:"
	@$(PYTHON) --version
	@echo "============================================================"
	@echo ""

# ==============================================================================
# 1. BACKEND COMMANDS (commands.txt Section 1 & Section 2)
# ==============================================================================
## backend-compile: Compile all backend code without running tests
backend-compile:
	$(MAKE) -C backend compile

## backend-clean-compile: Clean previous build artifacts and recompile
backend-clean-compile:
	$(MAKE) -C backend clean-compile

## backend-test: Run the full test suite (358 tests)
backend-test:
	$(MAKE) -C backend test

## backend-test-class: Run specific test class (usage: make backend-test-class CLASS=CourseControllerTest)
backend-test-class:
	$(MAKE) -C backend test-class CLASS=$(CLASS)

## Specific test class shortcuts
backend-test-presence:
	$(MAKE) -C backend test-presence

backend-test-session:
	$(MAKE) -C backend test-session

backend-test-course:
	$(MAKE) -C backend test-course

backend-test-student:
	$(MAKE) -C backend test-student

## backend-test-method: Run a specific test method within a class
backend-test-method:
	$(MAKE) -C backend test-method METHOD="$(METHOD)"

## backend-test-trace: Run tests with detailed stack traces on errors (-e)
backend-test-trace:
	$(MAKE) -C backend test-trace

## backend-run / backend-start: Start Spring Boot server in dev mode (port 8080)
backend-start: backend-run
backend-run:
	$(MAKE) -C backend run PORT=$(PORT)

## backend-run-custom: Start server with custom port & admin secret (usage: make backend-run-custom PORT=8081 SECRET=my-secret)
backend-run-custom:
	$(MAKE) -C backend run-custom PORT=$(PORT) SECRET=$(SECRET)

## backend-package: Package into executable JAR (skipping tests for speed)
backend-package:
	$(MAKE) -C backend package

## backend-package-verify: Package into executable JAR with full test verification
backend-package-verify:
	$(MAKE) -C backend package-verify

## backend-jar: Run the packaged JAR directly
backend-jar:
	$(MAKE) -C backend jar

## backend-clean: Wipe backend target/ and H2 data/ folders
backend-clean:
	$(MAKE) -C backend clean

## h2: Display H2 Web Console URL and login credentials
h2:
	$(MAKE) -C backend h2

# ==============================================================================
# 2. FRONTEND COMMANDS (commands.txt Section 3)
# ==============================================================================
## frontend-install: Install all frontend npm dependencies
frontend-install:
	$(MAKE) -C frontend install

## frontend-dev / frontend-start: Start Vite dev server on http://localhost:5173
frontend-start: frontend-dev
frontend-dev:
	$(MAKE) -C frontend dev

## frontend-build: Production build (TypeScript type-check + Vite minify)
frontend-build:
	$(MAKE) -C frontend build

## frontend-lint: Run Oxlint code linter
frontend-lint:
	$(MAKE) -C frontend lint

## frontend-preview: Preview production build locally
frontend-preview:
	$(MAKE) -C frontend preview

## frontend-clean: Remove frontend dist/ folder
frontend-clean:
	$(MAKE) -C frontend clean

## frontend-clean-all: Remove dist/ and node_modules/
frontend-clean-all:
	$(MAKE) -C frontend clean-all

# ==============================================================================
# 3. PYTHON CLI & LOAD TESTING (commands.txt Section 4)
# ==============================================================================
## cli-install: Install Python requirements (requests, qrcode, colorama)
cli-install:
	$(MAKE) -C cli install

## cli-run / cli-app: Run Interactive Terminal CLI (Professor flow & QR render)
cli-app: cli-run
cli-run:
	$(MAKE) -C cli run

## cli-load-test: Run concurrent load simulation (50+ students scanning & heartbeating)
cli-load-test:
	$(MAKE) -C cli load-test

## cli-clean: Clean Python __pycache__ folders
cli-clean:
	$(MAKE) -C cli clean

# ==============================================================================
# 4. DIRECT API TESTING (commands.txt Section 5)
# ==============================================================================
## api-test: Run automated 4-step smoke test (invite -> register -> login -> create course)
api-test:
	@$(PYTHON) scripts/api_test.py

## api-test-ps: Run API smoke test using native PowerShell Invoke-RestMethod
api-test-ps:
	powershell -ExecutionPolicy Bypass -File scripts/api_test.ps1

# ==============================================================================
# 5. GIT OPERATIONS (commands.txt Section 6)
# ==============================================================================
## git-status: Check working tree status
git-status:
	git status

## git-diff: Review exact line diffs
git-diff:
	git diff

## git-restore: Discard local modifications to a file (usage: make git-restore FILE=path/to/file)
git-restore:
ifeq ($(FILE),)
	@echo "Error: Please specify the file to restore."
	@echo "Usage: make git-restore FILE=path/to/file"
else
	git restore $(FILE)
endif

## git-log: View clean commit history (last 10 commits)
git-log:
	git log --oneline -n 10

# ==============================================================================
# 6. COMPOSITE WORKFLOWS
# ==============================================================================
## install-all: Install frontend npm dependencies and Python CLI requirements
install-all: frontend-install cli-install

## test-all: Run backend test suite and frontend linter
test-all: backend-test frontend-lint

## build-all: Build backend fat JAR and frontend production bundle
build-all: backend-package frontend-build

## clean-all: Clean backend target/db, frontend dist/, and Python caches
clean-all: backend-clean frontend-clean cli-clean
	@echo "Cleaned all build artifacts across backend, frontend, and cli."

# ==============================================================================
# HELP MENU
# ==============================================================================
help:
	@echo ""
	@echo "============================================================================"
	@echo "                     QR ATTENDANCE SYSTEM — COMMAND CHEAT SHEET             "
	@echo "============================================================================"
	@echo " 0. Prerequisites:"
	@echo "   make prereqs / make check      Verify Java, Node, NPM, Python versions"
	@echo ""
	@echo " 1. Backend (commands.txt Section 1):"
	@echo "   make backend-run               Start backend server (http://localhost:8080)"
	@echo "   make backend-run-custom        Start with PORT=8081 SECRET=my-secret"
	@echo "   make backend-compile           Compile backend sources without tests"
	@echo "   make backend-clean-compile     Clean build and recompile"
	@echo "   make backend-test              Run full test suite (358 tests)"
	@echo "   make backend-test-class        Run specific class (CLASS=PresenceServiceTest)"
	@echo "   make backend-test-method       Run specific method (METHOD=Class#method)"
	@echo "   make backend-test-trace        Run tests with error stack trace (-e)"
	@echo "   make backend-test-presence     Shortcut: test PresenceServiceTest"
	@echo "   make backend-test-session      Shortcut: test SessionServiceTest"
	@echo "   make backend-test-course       Shortcut: test CourseControllerTest"
	@echo "   make backend-test-student      Shortcut: test StudentScanControllerTest"
	@echo "   make backend-package           Package executable fat JAR (skip tests)"
	@echo "   make backend-package-verify    Package JAR with test verification"
	@echo "   make backend-jar               Run packaged JAR directly"
	@echo "   make backend-clean             Wipe target/ and reset H2 database"
	@echo ""
	@echo " 2. Database & Console (commands.txt Section 2):"
	@echo "   make h2                        Display H2 Web Console connection details"
	@echo ""
	@echo " 3. Frontend (commands.txt Section 3):"
	@echo "   make frontend-install          Install npm dependencies"
	@echo "   make frontend-dev              Start Vite dev server (http://localhost:5173)"
	@echo "   make frontend-build            Type-check and build production bundle"
	@echo "   make frontend-lint             Run Oxlint code linter"
	@echo "   make frontend-preview          Preview production build locally"
	@echo "   make frontend-clean            Remove frontend dist/ folder"
	@echo ""
	@echo " 4. Python CLI & Load Testing (commands.txt Section 4):"
	@echo "   make cli-install               Install pip dependencies"
	@echo "   make cli-run / make cli-app    Run interactive terminal CLI (QR display)"
	@echo "   make cli-load-test             Simulate 50+ students load test"
	@echo "   make cli-clean                 Remove Python __pycache__"
	@echo ""
	@echo " 5. Direct API Testing (commands.txt Section 5):"
	@echo "   make api-test                  Run 4-step automated API smoke test"
	@echo "   make api-test-ps               Run 4-step API smoke test via PowerShell"
	@echo ""
	@echo " 6. Git Helpers (commands.txt Section 6):"
	@echo "   make git-status                git status"
	@echo "   make git-diff                  git diff"
	@echo "   make git-restore FILE=<path>   Discard modifications to specific file"
	@echo "   make git-log                   git log --oneline -n 10"
	@echo ""
	@echo " Composite Workflows:"
	@echo "   make install-all               Install frontend and cli dependencies"
	@echo "   make test-all                  Run backend tests and frontend lint"
	@echo "   make build-all                 Build backend JAR and frontend bundle"
	@echo "   make clean-all                 Clean all build artifacts"
	@echo "============================================================================"
	@echo ""
