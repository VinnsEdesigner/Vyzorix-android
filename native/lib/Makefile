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

SRC := src/json.cpp src/frame.cpp src/apps.cpp src/log.cpp src/watch.cpp
OBJ := $(patsubst src/%.cpp,$(BUILD)/%.o,$(SRC))

.PHONY: all check clean

all: $(OBJ)

$(BUILD)/%.o: src/%.cpp | $(BUILD)
	$(CXX) $(CXXFLAGS) -Iinclude -c $< -o $@

$(BUILD):
	mkdir -p $(BUILD)

check: | $(BUILD)
	$(CXX) $(CXXFLAGS) -Iinclude -o $(BUILD)/lib_test $(SRC) test/lib_test.cpp
	$(BUILD)/lib_test

clean:
	rm -rf $(BUILD)
