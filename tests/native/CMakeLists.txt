# Native unit tests + micro-benchmarks for the DSP core (app/src/main/cpp/dsp, common).
# Built with the NDK toolchain as a plain Android executable and run on a device/emulator
# through adb (see tests/native/run_native_tests.ps1). No third-party test framework, so the
# suite has no extra download/supply-chain surface.
cmake_minimum_required(VERSION 3.22.1)
project(tarangfx_native_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(APP_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp CACHE PATH "Native sources under test")
file(GLOB DSP_SRC CONFIGURE_DEPENDS ${APP_CPP}/dsp/*.cpp)

add_executable(dsp_tests dsp_tests.cpp ${DSP_SRC})
target_include_directories(dsp_tests PRIVATE ${APP_CPP})
# Same optimisation flags as the shipped release library, so benchmark numbers are representative.
target_compile_options(dsp_tests PRIVATE -O3 -fno-math-errno -ffp-contract=fast -Wall -Wno-unused-parameter)
# Builds for Android (run via adb) or natively on a Linux/macOS host (CI runs it there).
if(ANDROID)
    target_link_libraries(dsp_tests PRIVATE log)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(dsp_tests PRIVATE Threads::Threads)
endif()
