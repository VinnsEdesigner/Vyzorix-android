# app-runtime/cli-wrappers — the `app-*` CLI.
#
# One binary, one symlink per command, exactly like android-bin. Built for the
# Debian userspace (glibc), so it is cross-compiled with a GNU toolchain:
#
#   make CXX=g++                       # on x86_64, for an amd64 rootfs
#   make CXX=aarch64-linux-gnu-g++     # for arm64
#   make CXX=arm-linux-gnueabihf-g++   # for armhf (armeabi-v7a)
#
# `make` produces build/bin/ — the binary plus the app-* symlinks — which is the
# installable unit copied into the rootfs overlay.

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Werror
# Link flags, so a caller can request a static link (see scripts/build-layer3.sh).
LDFLAGS ?=
BUILD := build

# The CLI's own version, stamped into the bundle and printed by `vyzorix version`.
CLI_VERSION ?= 0.1.0

LIB := ../../native/lib
INC := -I$(LIB)/include

LIB_SRC := $(LIB)/src/vyzorix_json.cpp $(LIB)/src/frame_channel.cpp

# The commands, one symlink each. Kept here rather than discovered because the set
# is the CLI's public surface and should be visible in the build.
COMMANDS := app-start app-stop app-restart app-list app-logs app-env app-create \
            app-remove app-expose app-unexpose

.PHONY: all clean

all: symlinks

$(BUILD)/bin/app-tool: app_tool.cpp $(LIB_SRC) | $(BUILD)
	@mkdir -p $(BUILD)/bin
	$(CXX) $(CXXFLAGS) $(INC) $(LDFLAGS) -o $@ $^

symlinks: $(BUILD)/bin/app-tool
	@for cmd in $(COMMANDS); do \
		ln -sf app-tool $(BUILD)/bin/$$cmd; \
	done
	@cp vyzorix $(BUILD)/bin/vyzorix
	@chmod 0755 $(BUILD)/bin/vyzorix
	@printf '%s' "$(CLI_VERSION)" > $(BUILD)/bin/VERSION
	@echo "cli-wrappers: $(words $(COMMANDS)) command(s) + vyzorix"

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD)
