# android-bin — the container-side bridge client.
#
# Builds one installable unit, build/bin/: the `android-bin` binary plus a symlink
# per API name in apis.txt. That whole directory is copied into the rootfs overlay
# as one piece, which is why the binary lives inside it rather than beside it.
#
# The binary runs inside the Debian userspace, whose libc is glibc — not Android's
# bionic — so it is cross-compiled with a GNU toolchain, never the NDK:
#
#   make CXX=g++                       # on x86_64, for an amd64 rootfs
#   make CXX=aarch64-linux-gnu-g++     # for arm64
#   make CXX=arm-linux-gnueabihf-g++   # for armhf (armeabi-v7a)
#
# scripts/install-toolchain.sh installs both cross-compilers.
#
# Targets:
#   make            build build/bin/ (binary + symlinks)
#   make check      run the JSON unit tests on the host
#   make clean

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Werror
HOST_CXX ?= g++
BUILD := build

SRC := src/json.cpp src/bridge.cpp src/bridge_main.cpp
OBJ := $(patsubst src/%.cpp,$(BUILD)/%.o,$(SRC))

.PHONY: all check clean

all: symlinks

# The catalog and the symlinks come from one pass over apis.txt, so what the binary
# prints and what the build creates cannot disagree.
$(BUILD)/api_catalog.cpp $(BUILD)/.apis-generated: apis.txt generator.cpp | $(BUILD)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -o $(BUILD)/generator generator.cpp
	$(BUILD)/generator --apis apis.txt --catalog-out $(BUILD)/api_catalog.cpp
	@touch $(BUILD)/.apis-generated

# bridge_main.cpp includes the generated catalog, so it waits for generation.
$(BUILD)/bridge_main.o: $(BUILD)/.apis-generated

$(BUILD)/api_catalog.o: $(BUILD)/api_catalog.cpp
	$(CXX) $(CXXFLAGS) -Isrc -c $< -o $@

$(BUILD)/%.o: src/%.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) -Isrc -I$(BUILD) -c $< -o $@

$(BUILD)/bin/android-bin: $(OBJ) $(BUILD)/api_catalog.o
	@mkdir -p $(BUILD)/bin
	$(CXX) $(CXXFLAGS) -o $@ $^

symlinks: $(BUILD)/.apis-generated $(BUILD)/bin/android-bin
	$(BUILD)/generator --apis apis.txt --symlink-dir $(BUILD)/bin --target android-bin

$(BUILD):
	mkdir -p $(BUILD)

# The JSON reader is the one part with logic worth testing off-device; the bridge
# client needs a live socket and is covered by the on-device suite.
check: | $(BUILD)
	$(HOST_CXX) -std=c++17 -O2 -Wall -Wextra -Werror -Isrc \
		-o $(BUILD)/json_test src/json.cpp test/json_test.cpp
	$(BUILD)/json_test

clean:
	rm -rf $(BUILD)
