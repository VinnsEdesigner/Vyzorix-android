# app-runtime/supervisor — the Layer 3 supervisor.
#
# Built for the Debian userspace (glibc), like android-bin, so it is cross-compiled
# with a GNU toolchain, never the NDK:
#
#   make CXX=g++                       # on x86_64, for an amd64 rootfs
#   make CXX=aarch64-linux-gnu-g++     # for arm64
#   make CXX=arm-linux-gnueabihf-g++   # for armhf (armeabi-v7a)
#
# Targets:
#   make            build build/bin/supervisor
#   make check      build and run the unit tests, on the host
#   make clean

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Werror
# Link flags, so a caller can request a static link (see scripts/build-layer3.sh).
LDFLAGS ?=
BUILD := build

LIB := ../../native/lib
INC := -Iinclude -I$(LIB)/include

SRC := src/main.cpp src/manager.cpp src/host_system.cpp src/control.cpp
LIB_SRC := $(LIB)/src/json.cpp $(LIB)/src/frame.cpp $(LIB)/src/apps.cpp

.PHONY: all check clean

all: $(BUILD)/bin/supervisor

$(BUILD)/bin/supervisor: $(SRC) $(LIB_SRC) | $(BUILD)
	@mkdir -p $(BUILD)/bin
	$(CXX) $(CXXFLAGS) $(INC) $(LDFLAGS) -o $@ $^

# The manager tests use a fake host, so they need manager.cpp but not host_system.cpp.
# control_test drives the request handler directly, with no socket.
check: | $(BUILD)
	$(CXX) $(CXXFLAGS) $(INC) $(LDFLAGS) -o $(BUILD)/manager_test \
		src/manager.cpp $(LIB_SRC) test/manager_test.cpp
	$(BUILD)/manager_test
	$(CXX) $(CXXFLAGS) $(INC) $(LDFLAGS) -o $(BUILD)/control_test \
		src/control.cpp src/manager.cpp $(LIB_SRC) test/control_test.cpp
	$(BUILD)/control_test

$(BUILD):
	mkdir -p $(BUILD)

clean:
	rm -rf $(BUILD)
