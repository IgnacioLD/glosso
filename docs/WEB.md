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
    clip is decoded with the vendored stb_vorbis, built to WebAssembly (`stb_vorbis.wasm`, see its
    README). The result is a WAV blob played on an `<audio>` element, and slow playback keeps the pitch.
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

## Installing it (PWA)

`shared/src/wasmJsMain/resources` holds the page shell:
- **`index.html`:**
  - sizes the app to the safe area (`viewport-fit=cover` with `env(safe-area-inset-*)`), clear of the notch and the home indicator;
  - shows a splash until the first frame;
  - explains itself on a browser without WebAssembly GC;
  - registers the service worker.
- **`manifest.webmanifest` and `icons/`:** the install metadata and icons, made from the iOS app icon. The maskable icon keeps the artwork inside the safe circle.
- **`sw.js`:** network first for this origin's files, so a deploy shows on the next load. The cache is only the fallback that lets the app open with no connection, where it shows its own offline state. The API is on another origin and is never touched.
  - There is one cache per release; the build fills in the version.
  - When a new release activates, only older `glosso-shell-*` caches are dropped. Compose keeps its resources in a cache of its own.

On iPhone: Safari, Share, Add to Home Screen. On desktop Chrome or Edge: the install icon in the address bar.

A first visit downloads about 5.8 MB gzipped (4.5 MB brotli). Most of it is the two wasm files (app 1.8 MB, Skia 3.3 MB gzipped), plus the fonts. The browser then serves them from its cache.

## Wide windows and keyboards

- **Layout:** from 600 dp wide, the tabs are a side rail and screens are centred at 640 dp (`Sizes.wideLayout`, `Sizes.contentMaxWidth`). Android tablets get the same layout.
- **Keyboard:** Space starts and stops a take, and Esc goes back.
- **Pointer:** buttons, rows and the record controls show the hand cursor.

## Hosting

Caddy serves the build as static files at `GLOSSO_WEB_DOMAIN` (default `app.glossostudio.com`), from
`deploy/web/current`. That is a symlink to `deploy/web/releases/<ts>`, and `deploy/web` is mounted
read-only (`deploy/Caddyfile`, `deploy/docker-compose.yml`).
- **Cache:** the hashed wasm files are cached for a year as immutable. Everything else revalidates on each
  load (`no-cache` plus ETag).
- **CSP:** strict, with no inline script; `boot.js` starts the app. `connect-src` allows only the page
  itself and `https://$GLOSSO_DOMAIN`.
- **Not served:** the source map and the raw `glosso-web.mjs` return 404.
- **CORS:** the API must allow the page's origin. Add `https://app.glossostudio.com` to
  `GLOSSO_CORS_ORIGINS` in `deploy/.env`, next to the website's origin.

A release, by hand. Production `/v1/score` takes no key today, so the build needs none:

```sh
./gradlew :shared:wasmJsBrowserDistribution    # the API base defaults to https://api.glossostudio.com
```

Then, on the VPS, in `/opt/glosso/deploy`:
1. Copy the build (without `*.map`) to `web/releases/<ts>`.
2. Switch with `ln -sfn releases/<ts> web/current.new && mv -T web/current.new web/current`. That is
   atomic, and Caddy sees it at once.
3. To roll back, point `current` at the previous release the same way.

Never replace `deploy/web` itself. The bind mount follows the directory Caddy started with, so a
new folder at that path stays invisible until Caddy is recreated.

The server must run a build that has the CORS change from PR #19, or browsers are refused.

Check a release locally first. Extract the site block into a file with `auto_https off`, run it with
`caddy run` against a local `:server:run`, and drive it with the headless Chromium below. The page must
load with no CSP violation, play clips, and get a take scored.

## Browser support

The app needs WebAssembly GC: Safari 18.2+ (iOS 18.2+), and current Chrome, Edge and Firefox.

## Checking it

Never let a browser play sound on the host. Drive a headless Chromium with `--mute-audio`, and use
`--use-fake-device-for-media-stream --use-file-for-fake-audio-capture=<48 kHz wav>` to "speak" a real
recording. A native clip scored through the web recorder must get the same score as the clip posted
directly to `/v1/score`.
