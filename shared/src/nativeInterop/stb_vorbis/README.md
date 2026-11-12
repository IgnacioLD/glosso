# stb_vorbis (vendored)

Ogg Vorbis decoder for the iOS app and the web app: the content audio (lessons, drills, stories) is
Ogg Vorbis, which AVFoundation cannot play and Safari cannot decode. Android plays it with
`MediaPlayer` and does not use this code.

| | |
| --- | --- |
| File | `stb_vorbis.c`, unmodified, version 1.22 |
| Upstream | https://github.com/nothings/stb |
| Pinned commit | `2c980bb59875b0d32144a71867fbdebb2f77cd20` (the file last changed in `1ee679ca2ef753a528db5ba6801e1067b40481b8`, 2021-07-12) |
| Download | `https://raw.githubusercontent.com/nothings/stb/2c980bb59875b0d32144a71867fbdebb2f77cd20/stb_vorbis.c` |
| SHA-256 | `4c7cb2ff1f7011e9d67950446b7eb9ca044f2e464d76bfbb0b84dd2e23e65636` |
| Licence | MIT (Copyright (c) 2017 Sean Barrett) or public domain, at our choice; used under MIT. Catalogued as `native:stb-vorbis` in `tools/licensing/collect.py`, so it is listed in the in-app Licences screen. |

## How it is built

- `build-static.sh` compiles it per iOS slice (`iphoneos` arm64, `iphonesimulator` arm64, minimum
  iOS 12.0, Kotlin/Native's own minimum) into `shared/build/stb_vorbis/<target>/libstb_vorbis.a`, run
  by the `:shared:compileStbVorbis<Target>` tasks.
- `../cinterop/stbvorbis.def` parses only the public API (`STB_VORBIS_HEADER_ONLY`) and embeds the
  static library in the klib, so the app links it with no extra flags.
- Both use `STB_VORBIS_NO_STDIO` and `STB_VORBIS_NO_PUSHDATA_API`: the app decodes from memory only.
- The Kotlin side is `shared/src/iosMain/.../audio/OggVorbis.kt` (`VorbisDecoder`, `OggPages`).

## The web build

- `build-wasm.sh` compiles it with `zig cc` (wasm32-wasi, its own libc) into
  `shared/src/wasmJsMain/resources/stb_vorbis.wasm`. The module imports nothing, exports `malloc`, `free`
  and `stb_vorbis_decode_memory`, and is about 80 KB.
- The output is committed, so a web build needs no C toolchain. Rerun the script only when
  `stb_vorbis.c` changes.
- `glosso-web.mjs` loads it and turns each decoded clip into a WAV for an `<audio>` element.

## Security

stb_vorbis 1.22 has public advisories for crafted files (CVE-2023-45675 to CVE-2023-45682, found by
fuzzing, unfixed upstream). The app only decodes audio that comes from its own asset server over
HTTPS (played from the URL or saved in a downloaded lesson), and `OggPages` rejects any stream with a
bad page checksum, a missing or cut last page, or bytes that are not Ogg pages before the decoder
sees it. A local mutation fuzz of a real clip (3,400 mutated files under AddressSanitizer) found no
memory errors, only oversized allocation requests that fail cleanly.

## Updating

Download the file at a new commit, check it, update the table above (commit, SHA-256, version) and
the name in `tools/licensing/collect.py`, then run `python3 tools/licensing/collect.py` and
`./gradlew :shared:iosSimulatorArm64Test`.
