#!/usr/bin/env bash
# Builds stb_vorbis.c into the WebAssembly module the web app decodes Ogg Vorbis
# with (shared/src/wasmJsMain/resources/glosso-web.mjs). The output is committed,
# so a web build needs no C toolchain; rerun this only when stb_vorbis.c changes.
# Needs zig (`brew install zig`): `zig cc` targets wasm32-wasi with its own libc.
# The module imports nothing and exports malloc, free and stb_vorbis_decode_memory.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../wasmJsMain/resources/stb_vorbis.wasm"
zig cc -target wasm32-wasi -O2 \
    -DSTB_VORBIS_NO_STDIO -DSTB_VORBIS_NO_PUSHDATA_API \
    -mexec-model=reactor -Wl,--strip-all \
    -Wl,--export=stb_vorbis_decode_memory -Wl,--export=malloc -Wl,--export=free \
    -Wno-tautological-compare \
    -o "$out" "$here/stb_vorbis.c"
echo "wrote $out ($(wc -c < "$out" | tr -d ' ') bytes, zig $(zig version))"
