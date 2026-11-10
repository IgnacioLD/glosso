#!/bin/sh
# Compiles the vendored stb_vorbis.c into a static library for one iOS slice.
# Called by :shared's buildStbVorbis<Target> tasks; the cinterop
# (src/nativeInterop/cinterop/stbvorbis.def) then embeds the library in the klib.
#
#   build-static.sh <sdk: iphoneos|iphonesimulator> <clang target triple> <output dir>
#
# The defines must match the .def's compilerOpts: the decoder is used from
# memory only, so the stdio and push-data APIs are compiled out.
set -eu

sdk="$1"
triple="$2"
out="$3"
here="$(cd "$(dirname "$0")" && pwd)"

# Run from an Xcode build phase, the environment carries the app's SDK and
# deployment target; the explicit sysroot and triple below are what count.
unset SDKROOT IPHONEOS_DEPLOYMENT_TARGET 2>/dev/null || true

sysroot="$(xcrun --sdk "$sdk" --show-sdk-path)"
mkdir -p "$out"
xcrun --sdk "$sdk" clang \
    -target "$triple" \
    -isysroot "$sysroot" \
    -O2 -fPIC -w \
    -DSTB_VORBIS_NO_STDIO \
    -DSTB_VORBIS_NO_PUSHDATA_API \
    -c "$here/stb_vorbis.c" \
    -o "$out/stb_vorbis.o"
rm -f "$out/libstb_vorbis.a"
xcrun --sdk "$sdk" libtool -static -o "$out/libstb_vorbis.a" "$out/stb_vorbis.o"
