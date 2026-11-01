# ==============================================================================
#                 QR ATTENDANCE SYSTEM — CLI & LOAD TEST MAKEFILE
# ==============================================================================
# Cross-platform Makefile: works on Windows, macOS, and Linux
# Matches all commands in commands.txt (Section 4)

ifeq ($(OS),Windows_NT)
    PYTHON ?= python
    RM_DIR = cmd /C if exist $(1) rmdir /S /Q $(1)
else
    PYTHON ?= python3
    RM_DIR = rm -rf $(1)
endif

PIP ?= $(PYTHON) -m pip

.PHONY: all help install run app load-test clean check

all: help

## ── Dependencies ───────────────────────────────────────────────────
## install: Install Python dependencies (requests, qrcode, colorama)
install:
	$(PIP) install -r requirements.txt

## ── Execution ──────────────────────────────────────────────────────
## run: Run the Interactive Terminal CLI (Professor flow & QR rendering)
run:
	$(PYTHON) app.py

## app: Alias for run
app: run

## load-test: Run concurrent load simulation (50+ students scanning & heartbeating)
load-test:
	$(PYTHON) load_test.py

## check: Verify python environment and version
check:
	$(PYTHON) --version

## ── Cleanup ────────────────────────────────────────────────────────
## clean: Remove Python bytecode caches (__pycache__)
clean:
	@$(call RM_DIR,__pycache__)
	@echo "Cleaned Python cache directories."

## ── Help Menu ──────────────────────────────────────────────────────
## help: Show available CLI targets and usage
help:
	@echo ""
	@echo "=================================================================="
	@echo "         QR Attendance System — Python CLI Makefile Targets       "
	@echo "=================================================================="
	@echo " Setup:"
	@echo "   make install             Install requirements (pip install -r ...)"
	@echo "   make check               Display Python version"
	@echo ""
	@echo " Run:"
	@echo "   make run / make app      Interactive CLI with terminal QR render"
	@echo "   make load-test           Concurrent load simulation (50+ students)"
	@echo ""
	@echo " Cleanup:"
	@echo "   make clean               Remove __pycache__ folder"
	@echo "   make help                Show this help message"
	@echo "=================================================================="
	@echo ""
