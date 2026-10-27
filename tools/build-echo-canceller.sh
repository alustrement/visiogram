#!/usr/bin/env bash
# Builds libvisio_apm.so (WebRTC's AEC3 echo canceller behind a small JNI layer) for every ABI the app ships.
#
# The result is committed under app/src/main/jniLibs, so that a normal Gradle build needs neither the NDK nor
# this script; run it again after changing app/src/main/cpp/echo_canceller.cpp or the library version.
# Needs: Android NDK (from local.properties' sdk.dir), meson, ninja, git, network access (sources and abseil).
set -euo pipefail

APM_VERSION=v2.1
API_LEVEL=26
ABIS=(arm64-v8a x86_64)

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties")
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK_DIR"/ndk/* | sort -V | tail -1)}
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
WORK=${WORK:-$ROOT/build/echo-canceller}

mkdir -p "$WORK"
if [ ! -d "$WORK/src" ]; then
    git clone --quiet --depth 1 --branch "$APM_VERSION" \
        https://gitlab.freedesktop.org/pulseaudio/webrtc-audio-processing.git "$WORK/src"
fi

for abi in "${ABIS[@]}"; do
    case $abi in
        arm64-v8a) triple=aarch64-linux-android; cpu_family=aarch64; cpu=armv8 ;;
        x86_64) triple=x86_64-linux-android; cpu_family=x86_64; cpu=x86_64 ;;
    esac
    cc=$TOOLCHAIN/bin/$triple$API_LEVEL-clang
    cross=$WORK/cross-$abi.ini
    cat > "$cross" <<CROSS
[binaries]
c = '$cc'
cpp = '$cc++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'

[built-in options]
c_args = ['-fPIC', '-ffunction-sections', '-fdata-sections']
cpp_args = ['-fPIC', '-ffunction-sections', '-fdata-sections']

[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$cpu'
endian = 'little'
CROSS

    build=$WORK/build-$abi
    if [ ! -f "$build/build.ninja" ]; then
        meson setup "$build" "$WORK/src" --cross-file "$cross" \
            --buildtype=release -Db_ndebug=true --default-library=static \
            --force-fallback-for=abseil-cpp
    fi
    ninja -C "$build"

    # echo_canceller.cpp includes AEC3's internal headers: it must see the same defines as the library itself.
    mapfile -t defines < <(python3 - "$build/compile_commands.json" <<'PY'
import json, shlex, sys
entry = next(e for e in json.load(open(sys.argv[1])) if e["file"].endswith("aec3/echo_canceller3.cc"))
print("\n".join(a for a in shlex.split(entry["command"]) if a.startswith("-D")))
PY
    )

    # The library and every abseil archive it needs, linked once into a single self-contained JNI library.
    mapfile -t archives < <(find "$build" -name '*.a' | sort)
    out=$ROOT/app/src/main/jniLibs/$abi
    mkdir -p "$out"
    "$cc++" -shared -fPIC -O2 -std=c++17 "${defines[@]}" \
        -I "$WORK/src/webrtc" -I "$build/webrtc" \
        -I "$WORK/src/subprojects/$(ls "$WORK/src/subprojects" | grep -m1 '^abseil-cpp-[0-9]')" \
        "$ROOT/app/src/main/cpp/echo_canceller.cpp" \
        -Wl,--start-group "${archives[@]}" -Wl,--end-group \
        -static-libstdc++ -llog -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 \
        -o "$out/libvisio_apm.so"
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$out/libvisio_apm.so"
    echo "$abi: $(du -h "$out/libvisio_apm.so" | cut -f1)"
done
