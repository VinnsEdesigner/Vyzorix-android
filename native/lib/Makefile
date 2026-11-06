# native/lib — the shared C++ used by the container tools.
#
# json, frame and apps are compiled into each tool that needs them (there is no
# shared library to install into the rootfs, and static linking keeps the tools
# self-contained). This Makefile builds and tests the library on its own, so the
# library's correctness does not depend on any consumer building.
#
# Targets:
#   make check    build and run the unit tests, on the host
#   make clean
#
# The tools that consume it are debian-engine/android-bin (already) and
# app-runtime/ (Layer 3).

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Werror
BUILD := build

SRC := src/vyzorix_json.cpp src/frame_channel.cpp src/app_registry.cpp src/app_log_rotation.cpp src/app_dir_watcher.cpp src/app_http_proxy.cpp src/app_meta_store.cpp
OBJ := $(patsubst src/%.cpp,$(BUILD)/%.o,$(SRC))

.PHONY: all check clean

all: $(OBJ)

$(BUILD)/%.o: src/%.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) -Iinclude -c $< -o $@

$(BUILD):
	mkdir -p $(BUILD)

check: | $(BUILD)
	$(CXX) $(CXXFLAGS) -Iinclude -o $(BUILD)/native_lib_test $(SRC) test/native_lib_test.cpp
	$(BUILD)/native_lib_test

clean:
	rm -rf $(BUILD)
