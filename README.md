# Glosso

A language course that measures your pronunciation for real. Glosso listens to each take,
recognizes the sounds you actually made, compares them with the expected ones and tells you
exactly which sound to fix. Android first, with an iOS app in progress. Languages: English
(British and American), French, German and Russian.

The same phonetic engine runs on the phone (offline, ONNX Runtime) and on the server, so a
take gets the same score in both places.

Glosso is free for everyone, with no paid tier. Support is a voluntary donation.

## Repository layout

| Module | What it is |
|---|---|
| `:engine` | Kotlin Multiplatform phonetic engine: G2P, wav2vec2 phoneme recognition (ONNX), speech gate, phonetic comparison and feedback. The only place a score is computed. |
| `:server` | Ktor API (`/v1/score`, content, assets, product config, accounts). Reuses `:engine`. |
| `:shared` | The app itself in Compose Multiplatform (screens, view models, stores, API client), shared by Android and iOS. |
| `:androidApp` | Android shell: activity, on-device scorer, download service. |
| `iosApp/` | Xcode project that runs the shared app on iOS. |
| `deploy/` | Dockerfile, Compose stack, Caddy and monitoring config for self-hosting the server. |
| `tools/` | Asset download, scoring bench, score parity, content pipeline and license tooling. |

How one take is scored:

1. **Reference IPA** from the text through the language's G2P (`engine/.../g2p/`).
2. **Recognition**: `Wav2Vec2PhonemeRecognizer` returns the heard phones with confidence and timings.
3. **Gate**: `SpeechGate` decides whether the audio can be judged at all. Unjudgeable audio is reported as "not evaluated", never as a low score.
4. **Comparison**: `PhoneticComparator` aligns expected and heard phones into perfect, close and missed, using per-language similarity and error rules.
5. **Feedback**: `FeedbackAssembler` builds the result both the app and the server return.

Everything language-specific hangs off `LanguagePack` (`engine/.../model/LanguagePack.kt`).
Adding a language is data plus a pack; see `docs/ADD_LANGUAGE.md`.

## Building

Requirements: JDK 21, the Android SDK (for the app), Python 3 (license check that runs on every
app build) and Xcode (iOS only).

```sh
# Model, vocab and CMUdict (~350 MB) into data/assets
tools/fetch-assets.sh

# Tests
./gradlew :engine:jvmTest
./gradlew :server:test
./gradlew :androidApp:testDebugUnitTest

# Run the server locally on :8080 (serves data/assets)
./gradlew :server:run

# Build the Android app against the local server (from the emulator)
./gradlew :androidApp:assembleDebug -PglossoApiBase=http://10.0.2.2:8080
```

Without `-PglossoApiBase` the app talks to the public Glosso API. Build inputs are Gradle
properties documented in `androidApp/build.gradle.kts`. The server runs with only the assets on
disk; PostgreSQL, Redis and the TTS providers are optional and each feature turns itself off
when its dependency is missing. The full list of environment variables is in
`deploy/.env.example`, and `docs/DEPLOYMENT.md` describes a production setup.

Engine tests that need the model or real audio skip themselves when the assets are not set.
`tools/engine-bench/run.sh` is the scoring bench that runs them; see
`docs/ENGINE-BENCHMARK.md`.

## Documentation

Most design docs are in Spanish. Some code comments mention research scripts and internal notes
that are not part of the public repository.

- `docs/ADD_LANGUAGE.md` - adding a language
- `docs/ENGINE-BENCHMARK.md`, `docs/SCORING-DECISION-LAYER.md`, `docs/PHONEME_PROFILE.md` - scoring
- `docs/MULTI-ACCENT.md` - scoring against whole accent systems
- `docs/CONTENT-ENGINE.md`, `docs/CONTENT_POOL.md`, `docs/TTS-LAYER.md`, `docs/TTS-CACHE.md` - content and audio generation
- `docs/PRODUCT-CONFIG-CONTRACT.md`, `docs/GOOGLE-SIGNIN.md` - product config and accounts
- `docs/BILLING.md` - why there is no paid tier
- `docs/IOS-DEV.md`, `docs/IOS-ENGINE.md` - iOS
- `docs/PRIVACY.md`, `docs/LICENSING.md` - privacy and third-party licensing

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Glosso is free software under the **GNU Affero General Public License v3.0 or later**
([LICENSE](LICENSE)). If you run a modified version of the server for others, you must offer
them its source. [NOTICE](NOTICE) lists the third-party components and their licenses, and an
additional permission for linking with the Google Play libraries the Android app uses.

The Glosso name and logo are not covered by the license: forks must use their own.
