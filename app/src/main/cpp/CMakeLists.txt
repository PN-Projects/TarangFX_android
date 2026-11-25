cmake_minimum_required(VERSION 3.22.1)
project(tarangfx LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# ---------------------------------------------------------------------------
# FFmpeg (custom minimal static build, see tools/ffmpeg/). Audio codecs +
# container demuxers only; network protocols are disabled at configure time.
# ---------------------------------------------------------------------------
set(FFMPEG_DIR ${TP}/ffmpeg/${ANDROID_ABI})
if(NOT EXISTS ${FFMPEG_DIR}/lib/libavformat.a)
    message(FATAL_ERROR
        "FFmpeg prebuilts not found for ${ANDROID_ABI} in ${FFMPEG_DIR}. "
        "Build them with tools/ffmpeg/build_ffmpeg_android.sh (see tools/ffmpeg/README.md).")
endif()
set(FF_LIBS avformat avcodec swresample avutil mp3lame vorbisenc vorbis opus ogg)
foreach(lib ${FF_LIBS})
    add_library(ff_${lib} STATIC IMPORTED)
    set_target_properties(ff_${lib} PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/lib/lib${lib}.a)
endforeach()

# ---------------------------------------------------------------------------
# Google Oboe (Prefab AAR)
# ---------------------------------------------------------------------------
find_package(oboe REQUIRED CONFIG)

# ---------------------------------------------------------------------------
# TagLib 2.x (static, from source)
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_BINDINGS OFF CACHE BOOL "" FORCE)
set(WITH_ZLIB ON CACHE BOOL "" FORCE)
add_subdirectory(${TP}/taglib ${CMAKE_CURRENT_BINARY_DIR}/taglib EXCLUDE_FROM_ALL)
set(TAGLIB_SRC ${TP}/taglib/taglib)
set(TAGLIB_INCLUDES
    ${TAGLIB_SRC} ${TAGLIB_SRC}/toolkit ${TAGLIB_SRC}/mpeg ${TAGLIB_SRC}/mpeg/id3v1
    ${TAGLIB_SRC}/mpeg/id3v2 ${TAGLIB_SRC}/mpeg/id3v2/frames ${TAGLIB_SRC}/flac
    ${TAGLIB_SRC}/ogg ${TAGLIB_SRC}/ogg/vorbis ${TAGLIB_SRC}/ogg/opus ${TAGLIB_SRC}/ogg/flac
    ${TAGLIB_SRC}/mp4 ${TAGLIB_SRC}/riff ${TAGLIB_SRC}/riff/wav ${TAGLIB_SRC}/riff/aiff
    ${CMAKE_CURRENT_BINARY_DIR}/taglib ${CMAKE_CURRENT_BINARY_DIR}/taglib/taglib)

# ---------------------------------------------------------------------------
# Rubber Band Library (single compilation unit; GPLv2+)
# ---------------------------------------------------------------------------
add_library(rubberband STATIC ${TP}/rubberband/single/RubberBandSingle.cpp)
target_include_directories(rubberband PUBLIC ${TP}/rubberband)
target_compile_options(rubberband PRIVATE -O3 -w)

# ---------------------------------------------------------------------------
# ONNX Runtime (headers + .so extracted from the onnxruntime-android AAR by Gradle)
# ---------------------------------------------------------------------------
set(ORT_AVAILABLE OFF)
if(DEFINED ORT_DIR AND EXISTS ${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)
    add_library(onnxruntime SHARED IMPORTED)
    set_target_properties(onnxruntime PROPERTIES
        IMPORTED_LOCATION ${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)
    set(ORT_AVAILABLE ON)
    message(STATUS "ONNX Runtime found at ${ORT_DIR}")
else()
    message(WARNING "ONNX Runtime not found (ORT_DIR='${ORT_DIR}'); AI denoise will be unavailable")
endif()

# ---------------------------------------------------------------------------
# Application native core
# ---------------------------------------------------------------------------
file(GLOB_RECURSE APP_SRC CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/common/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/io/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/engine/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/ai/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/*.cpp)

add_library(tarangfx SHARED ${APP_SRC})
target_include_directories(tarangfx PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${FFMPEG_DIR}/include
    ${TP}/json
    ${TAGLIB_INCLUDES})

# Hardening: stack protector, FORTIFY, no exec stack, full RELRO.
# Optimisation: AGP builds release variants as RelWithDebInfo (not Release), so match both.
#  * -O3 + ThinLTO lets the per-sample DSP loops inline across translation units.
#  * -fno-math-errno / -ffp-contract=fast are value-safe (no errno writes; FMA on arm64).
#  * Deliberately NOT -ffast-math: it implies -ffinite-math-only, which turns isnan/isfinite
#    checks (e.g. in nlohmann/json parsing of project files) into no-ops.
set(TFX_OPTIMIZED $<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>)
target_compile_options(tarangfx PRIVATE
    -Wall -Wno-unused-parameter -fstack-protector-strong -D_FORTIFY_SOURCE=2
    $<${TFX_OPTIMIZED}:-O3 -flto=thin -fno-math-errno -ffp-contract=fast>
    $<$<NOT:${TFX_OPTIMIZED}>:-O2>)
target_link_options(tarangfx PRIVATE
    -Wl,--gc-sections -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now
    $<${TFX_OPTIMIZED}:-flto=thin -O3>
    -Wl,-z,max-page-size=16384)  # 16 KB page-size compatibility (Android 15+)

if(ORT_AVAILABLE)
    target_include_directories(tarangfx PRIVATE ${ORT_DIR}/headers)
    target_compile_definitions(tarangfx PRIVATE HAVE_ONNXRUNTIME=1)
    target_link_libraries(tarangfx onnxruntime)
endif()

target_link_libraries(tarangfx
    oboe::oboe
    tag
    rubberband
    ff_avformat ff_avcodec ff_swresample ff_avutil
    ff_mp3lame ff_vorbisenc ff_vorbis ff_opus ff_ogg
    z m log android)
