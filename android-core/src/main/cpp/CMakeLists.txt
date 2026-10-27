cmake_minimum_required(VERSION 3.22.1)
project(vyzorix_core CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(vyzorix_core SHARED
    pty_native.cpp
    pty_exec.cpp
    fd_pass_native.cpp
    jni_bridge.cpp
    self_test.cpp
)

target_include_directories(vyzorix_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(vyzorix_core ${log-lib})
