# Builds tests/native with the NDK and runs the binary on the connected device/emulator.
#   .\tests\native\run_native_tests.ps1                 # x86_64 (emulator)
#   .\tests\native\run_native_tests.ps1 -Abi arm64-v8a  # real phone
#   .\tests\native\run_native_tests.ps1 -NoBench
param(
    [string]$Abi = "x86_64",
    [switch]$NoBench
)
$ErrorActionPreference = "Stop"
$sdk   = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndk   = Join-Path $sdk "ndk\27.1.12297006"
$cmake = Join-Path $sdk "cmake\3.22.1\bin\cmake.exe"
$ninja = Join-Path $sdk "cmake\3.22.1\bin\ninja.exe"
$adb   = Join-Path $sdk "platform-tools\adb.exe"
$src   = $PSScriptRoot
$build = Join-Path $src "build\$Abi"

& $cmake -S $src -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
    "-DANDROID_ABI=$Abi" "-DANDROID_PLATFORM=android-26" "-DANDROID_STL=c++_static" `
    "-DCMAKE_BUILD_TYPE=Release" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "CMake configure failed" }
& $cmake --build $build
if ($LASTEXITCODE -ne 0) { throw "Native test build failed" }

# /data/local/tmp is the standard world-executable scratch location for adb-pushed test binaries.
& $adb push (Join-Path $build "dsp_tests") /data/local/tmp/dsp_tests | Out-Null
& $adb shell chmod 755 /data/local/tmp/dsp_tests
$arg = if ($NoBench) { "--no-bench" } else { "" }
& $adb shell "/data/local/tmp/dsp_tests $arg; echo EXIT_CODE=`$?"
& $adb shell rm -f /data/local/tmp/dsp_tests
