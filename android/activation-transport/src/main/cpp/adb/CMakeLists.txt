cmake_minimum_required(VERSION 3.22.1)
project(uwu_adb LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
find_package(boringssl REQUIRED CONFIG)
add_library(uwu_adb SHARED adb_pairing.cpp)
target_compile_options(uwu_adb PRIVATE
        -Wall -Wextra -Wno-unused-parameter -Wno-writable-strings
        -fno-exceptions -fno-rtti -fvisibility=hidden
        -fdata-sections -ffunction-sections)
target_link_options(uwu_adb PRIVATE
        -Wl,--exclude-libs,ALL -Wl,--gc-sections
        -Wl,-z,max-page-size=16384)
target_link_libraries(uwu_adb PRIVATE log boringssl::crypto_static)
