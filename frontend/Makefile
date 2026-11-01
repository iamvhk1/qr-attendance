# ==============================================================================
#                 QR ATTENDANCE SYSTEM — FRONTEND MAKEFILE
# ==============================================================================
# Cross-platform Makefile: works on Windows, macOS, and Linux
# Matches all commands in commands.txt (Section 3)

ifeq ($(OS),Windows_NT)
    RM_DIR = cmd /C if exist $(1) rmdir /S /Q $(1)
else
    RM_DIR = rm -rf $(1)
endif

NPM ?= npm

.PHONY: all help install dev start build lint preview clean clean-all check

all: help

## ── Dependencies ───────────────────────────────────────────────────
## install: Install all npm packages listed in package.json
install:
	$(NPM) install

## ── Development & Build ─────────────────────────────────────────────
## dev: Start the Vite local development server (runs on http://localhost:5173)
dev:
	$(NPM) run dev

## start: Alias for dev
start: dev

## build: Production build (TypeScript type-check + Vite minify)
build:
	$(NPM) run build

## lint: Run Oxlint code linter
lint:
	$(NPM) run lint

## preview: Preview the production build locally
preview:
	$(NPM) run preview

## check: Run linter and verify production build compiles cleanly
check: lint build

## ── Cleanup ────────────────────────────────────────────────────────
## clean: Wipe production dist/ build output folder
clean:
	@$(call RM_DIR,dist)
	@echo "Cleaned frontend dist/ folder."

## clean-all: Wipe dist/ and node_modules/ for a completely fresh install
clean-all: clean
	@$(call RM_DIR,node_modules)
	@echo "Cleaned frontend node_modules/ folder."

## ── Help Menu ──────────────────────────────────────────────────────
## help: Show available frontend targets and usage
help:
	@echo ""
	@echo "=================================================================="
	@echo "           QR Attendance System — Frontend Makefile Targets       "
	@echo "=================================================================="
	@echo " Setup & Development:"
	@echo "   make install             Install dependencies (npm install)"
	@echo "   make dev / make start    Start Vite dev server (port 5173)"
	@echo ""
	@echo " Quality & Production:"
	@echo "   make lint                Run Oxlint code linter"
	@echo "   make build               TypeScript type-check + Vite minify"
	@echo "   make preview             Preview production build locally"
	@echo "   make check               Run linter + build verification"
	@echo ""
	@echo " Cleanup:"
	@echo "   make clean               Remove dist/ directory"
	@echo "   make clean-all           Remove dist/ and node_modules/"
	@echo "   make help                Show this help message"
	@echo "=================================================================="
	@echo ""
