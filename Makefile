# Chuchu development tasks.
#
# Prerequisites (not installed by this Makefile):
#   - Zig 0.15.2 on PATH (override with `ZIG=/path/to/zig`)
#   - ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) for native Android builds
#   - ANDROID_HOME (or ANDROID_SDK_ROOT) and JDK 21 for Gradle targets
#   - ktfmt and ktlint on PATH for the Kotlin format/lint targets
#
# Targets fail with an explicit error when a prerequisite is missing instead of
# silently skipping the work.

SHELL := /bin/bash

ZIG ?= zig
# Overridable so CI can supply a downloaded jar: make fmt-check KTFMT="java -jar /tmp/ktfmt.jar"
KTFMT ?= ktfmt
KTLINT ?= ktlint
ANDROID_DIR := android
ZIG_DIR := zig-src
GRADLEW := ./gradlew

# Tracked sources only: the globs are quoted so git expands them (never the
# shell), which keeps untracked scratch files out of format/lint runs.
# `**` in a git pathspec needs at least one directory, so the top-level
# `android/*.gradle.kts` files need their own pattern.
KT_SOURCES := $(shell git ls-files 'android/**/*.kt')
KTS_SOURCES := $(shell git ls-files 'android/*.gradle.kts' 'android/**/*.gradle.kts')
KT_COUNT := $(words $(KT_SOURCES))

.PHONY: build app test fmt fmt-check lint native-test native-fmt-check \
	check-zig check-ndk check-kt-sources

check-zig:
	@command -v $(ZIG) >/dev/null 2>&1 || { \
		echo "error: '$(ZIG)' not found on PATH; install Zig 0.15.2 or set ZIG=/path/to/zig" >&2; \
		exit 1; \
	}

check-ndk:
	@if [ -z "$$ANDROID_NDK_HOME" ] && [ -z "$$ANDROID_NDK_ROOT" ]; then \
		echo "error: ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) is not set; point it at an Android NDK before building native code" >&2; \
		exit 1; \
	fi

check-kt-sources:
	@if [ "$(KT_COUNT)" -eq 0 ]; then \
		echo "error: no Kotlin sources found under $(ANDROID_DIR); run make from the repository root" >&2; \
		exit 1; \
	fi

build: check-zig check-ndk
	cd $(ZIG_DIR) && $(ZIG) build -Doptimize=ReleaseSmall jni

app:
	cd $(ANDROID_DIR) && $(GRADLEW) assembleDebug installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

test:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:testDebugUnitTest

fmt: check-kt-sources
	$(KTFMT) --kotlinlang-style $(KT_SOURCES)

# --dry-run is required alongside --set-exit-if-changed: without it ktfmt
# rewrites the files and only the exit code would report the drift.
fmt-check: check-kt-sources
	$(KTFMT) --kotlinlang-style --dry-run --set-exit-if-changed $(KT_SOURCES)

lint: check-kt-sources
	$(KTLINT) --editorconfig=$(ANDROID_DIR)/.editorconfig $(KT_SOURCES) $(KTS_SOURCES)

# `zig build test` runs the host-side bridge tests wired in zig-src/build.zig.
native-test: check-zig
	cd $(ZIG_DIR) && $(ZIG) build test

# `zig build fmt` is wired with check=true, so it verifies instead of rewriting.
# Apply formatting with `zig build fmt-write` (or `zig fmt src build.zig`).
native-fmt-check: check-zig
	cd $(ZIG_DIR) && $(ZIG) build fmt
