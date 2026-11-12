# The web app

The browser build of the app: the same shared Compose Multiplatform code as Android and iOS
(`:shared`), compiled to WebAssembly (`wasmJs`). On iPhone it replaces the native app for now: the
learner opens it in Safari and adds it to the Home Screen.

## Build and run

```sh
# A local server for the app to talk to (8080 may be taken; any port works).
# GLOSSO_ASSETS_DIR must be absolute: :server:run runs from server/.
PORT=8095 GLOSSO_ASSETS_DIR=$PWD/data/assets GLOSSO_CORS_ORIGINS=http://localhost:8096 ./gradlew :server:run

# The production bundle, pointed at that server, served as static files.
./gradlew :shared:wasmJsBrowserDistribution -PglossoApiBase=http://localhost:8095
cd shared/build/dist/wasmJs/productionExecutable && python3 -m http.server 8096
```

- `-PglossoApiBase` and `-PglossoApiKey` are the same inputs as the Android build; they end up in the
  generated `WebBuild` object. Pass the key through the environment
  (`ORG_GRADLE_PROJECT_glossoApiKey`, e.g. with `tools/keys/glosso-secret run`), never on the command line.
  Whatever it is, it is public in the bundle, as it is in the APK.
- `-Pglosso.web=false` leaves the web target out of `:engine` and `:shared`.
- The development bundle (`wasmJsBrowserDevelopmentRun`) does not start: webpack's eval wrapper breaks
  an `import.meta` in kotlinx-io. Use the production bundle.
- The server's content folder must hold real files, not a symlink out of `GLOSSO_ASSETS_DIR`: `/assets`
  refuses paths whose real location is outside it (an APFS clone, `cp -cR`, is instant).

## What differs from the native apps

- **Scoring is always in the cloud** (`NoOnDeviceScoring`). Nothing is downloaded for offline use
  (`AppConfig.offlineDownloads = false` hides lesson downloads, the Offline hub and the download
  settings), because a page keeps no files between visits: `AppFiles` is an in-memory okio file system.
  Settings and progress live in `localStorage` (`KeyValueStore.wasmJs.kt`).
- **No sign-in, analytics or crash reports**, as on iOS.
- **Audio** is in `shared/src/wasmJsMain/resources/glosso-web.mjs`:
  - Content clips are Ogg Vorbis. Safari says it plays Ogg and then fails to decode it, so every Ogg
    clip is decoded with `@wasm-audio-decoders/ogg-vorbis` into a WAV blob and played on an `<audio>`
    element (slow playback keeps the pitch).
  - iOS only sounds an element first started inside a tap, and the app starts clips after a network
    wait. So the first tap or key press unlocks a small pool of elements and the audio context, and
    clips borrow from that pool.
  - The microphone goes through `getUserMedia` and an AudioWorklet with echo cancellation, noise
    suppression and gain control off. The stream is opened per take, and `Wav.downsampleToPcm16` brings
    it to 16 kHz.
- **IPA symbols** that JetBrains Mono lacks come from a Noto Sans Mono subset
  (`wasmJsMain/composeResources/font`). It is preloaded as the fallback font, because a browser gives
  Skia no system fonts.
- **Back**: the browser's Back button (and Safari's edge swipe) runs the innermost `PlatformBackHandler`.

## Browser support

The app needs WebAssembly GC: Safari 18.2+ (iOS 18.2+), and current Chrome, Edge and Firefox.

## Checking it

Never let a browser play sound on the host. Drive a headless Chromium with `--mute-audio`, and use
`--use-fake-device-for-media-stream --use-file-for-fake-audio-capture=<48 kHz wav>` to "speak" a real
recording. A native clip scored through the web recorder must get the same score as the clip posted
directly to `/v1/score`.
