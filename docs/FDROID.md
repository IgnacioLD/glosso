# Glosso on F-Droid

The Android app has two product flavors in `:androidApp`:

| Flavor | For | Differences |
|---|---|---|
| `playstore` | Google Play | Sign in with Google ("Change phone"), through Credential Manager and Play services. Usage statistics stay off until the learner accepts the consent prompt. |
| `fdroid` | F-Droid | No Google library at all, so "Change phone" is hidden. Usage statistics off until the learner turns them on in Settings. |

Everything else (lessons, cloud and on-device scoring, the model download and
import from a file) is the same in both. The build recipe for fdroiddata is
drafted in `metadata/me.shirobyte42.glosso.yml`; store texts are in
`fastlane/metadata/android/<locale>/`.

```sh
./gradlew :androidApp:assembleFdroidRelease      # what F-Droid builds (unsigned)
./gradlew :androidApp:testFdroidDebugUnitTest
./gradlew :androidApp:dependencies --configuration fdroidReleaseRuntimeClasspath
```

## The F-Droid rules that apply, and how each is handled

Read on 2026-11-10.

**Non-free libraries.** The [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
forbids "Google Play Services and Firebase and Crashlytics" and asks for "a build
flavour that does not require these dependencies". The scanner signatures
([suss.json](https://fdroid.gitlab.io/fdroid-suss/suss.json)) match
`play-services`, `com.google.android.libraries` (googleid),
`androidx.credentials:credentials-play-services-auth`, Firebase and
`com.android.billingclient`. Handling: Credential Manager, its Play services
provider and googleid are `playstoreImplementation` dependencies only, and their
code lives in `androidApp/src/playstore`. `:shared` has no Play dependency; the
sign-in seam is `accountSignIn`, which defaults to `UnavailableSignIn` and is
replaced only by the playstore flavor (`Flavor.install()`). The scanner is
flavor-aware (it only reads `implementation`/`fdroidImplementation` lines for
`gradle: [fdroid]`), so the recipe needs no `prebuild` stripping.
`FdroidFlavorTest` fails if any of those classes is on the fdroid classpath.

**APK signing block.** `fdroid scanner` flags the dependency metadata block AGP
encrypts for Google Play. `dependenciesInfo.includeInApk = false` leaves it out of
APKs; bundles for Play keep it.

**Network services.** [Anti-Features](https://f-droid.org/docs/Anti-Features/):
`NonFreeNet` is for "a proprietary network service"; the Glosso server is AGPL
and in this repository, so it does not apply. `TetheredNet` applies to an app
that "depends entirely on a service which is impossible (or not easy) to
replace", unless there is "a simple configuration option that allows pointing the
app to a running instance of an alternative, publicly available, self-hostable
server". Settings > Advanced > Server is that option, so the recipe does not
declare `TetheredNet`:

- The build input (`-PglossoApiBase`, default `https://api.glossostudio.com`) is
  only the default. The learner enters another address; the app normalizes it,
  requires `https://` (debug builds also take `http://` to a local address), and
  uses it only after `/health` and `/v1/config` answer like a Glosso server.
- All network traffic follows the setting: every call of the one HTTP client
  (`GlossoApi`) reads the server at request time (`ServerPreferences.base`), and
  that covers content, cloud scoring, lesson and model downloads and their
  digests, product config, usage statistics, crash traces and sign-in. The app
  has no other network endpoint (no CDN, no third-party SDK).
- Switching drops what came from the previous server (`ServerSwitch`): queued
  downloads, the HTTP cache and content version, cached config and account, the
  linked Google account shown on the phone and unsent statistics. Downloaded
  lessons are kept only when the new server's manifest publishes exactly the
  content fingerprint (`assetVersion`) they were downloaded for; a different
  fingerprint, none, or an unreachable server deletes them. The scoring model is kept only when its SHA-256 is the released one
  or the one the new server publishes. Progress is local and is kept.

**Downloading the model and content.** The Inclusion Policy only restricts
downloading "additional executable binary files ... without explicit user
consent". The ONNX model (Apache-2.0) and the lessons are data, downloaded only
when the learner asks. `NonFreeAssets` applies to assets under non-commercial or
no-derivatives licences: the model is Apache-2.0, but the lesson texts and audio
(authored in `glosso-data`) stay unlicensed (owner decision), so the recipe
declares `AntiFeatures: NonFreeAssets` with that reason. The model can also be imported from a local file
(below), so a phone never has to reach the server for it.

**Tracking.** Anti-Features: not applied when the feature is "opt-in (i.e. you
are asked before it happens) and disabled by default", with informed consent and
a privacy policy. The usage statistics and crash traces are first-party and
consent based, so the fdroid flavor starts with them off
(`USAGE_STATISTICS_DEFAULT = false`, `Analytics.defaultEnabled`) and onboarding
says so (`onboarding_analytics_notice_off`). The privacy policy is in the app.

**Prebuilt binaries.** "All binary dependencies including JAR files must originate
either from source compilation" or trusted Maven repositories (Inclusion Policy).
The only binary in git is `gradle/wrapper/gradle-wrapper.jar`, which the scanner
removes on its own. Repositories are only `google()`, `mavenCentral()` and the
Gradle plugin portal. The model, vocabulary and content are gitignored.

**The web app's binaries.** The repository also holds the web app (`docs/WEB.md`),
whose Ogg decoder is a committed WebAssembly build of the vendored stb_vorbis
(`shared/src/wasmJsMain/resources/stb_vorbis.wasm`, built from source by
`shared/src/nativeInterop/stb_vorbis/build-wasm.sh`). `fdroid scanner` flags it
as a binary. The Android app never uses it, so the recipe deletes it (`rm:`) and
turns the web target off (`glosso.web=false`), which also keeps the Kotlin/Wasm
toolchain (a prebuilt Node download) out of the F-Droid build.

**Self-updater.** None. The server's version policy is only a dismissible notice
with no link and no download.

**Signing and reproducible builds.** F-Droid publishes our own signed APK
([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/), owner
decision), so F-Droid, Play and direct installs update each other. F-Droid
builds the recipe unsigned, copies the signature from the APK at `Binaries` onto
it (apksigcopier) and publishes ours only if the signature verifies, that is, if
the two builds are byte-identical apart from the signature.
`AllowedAPKSigningKeys` pins the SHA-256 of the release certificate
(`apksigner verify --print-certs`). What keeps the build reproducible:

- `vcsInfo.include = false` on release: AGP's
  `META-INF/version-control-info.textproto` depends on the checkout (a git
  worktree writes `NO_VALID_GIT_FOUND`, a clone the commit hash).
- `dependenciesInfo.includeInApk = false` (above).
- No native code is compiled; `libonnxruntime.so` comes prebuilt from Maven Central.
- Checked for 2.5.5: the signed APK built in a git worktree with Homebrew
  OpenJDK 21 and an unsigned rebuild from a fresh clone in another directory,
  with the recipe's `rm:` and properties, were byte-identical apart from the
  signature (`apksigcopier compare`), also when the rebuild ran on OpenJDK 26.
  The JDK only runs the build (Java 17 bytecode, D8/R8 output does not depend on
  it), so the recipe does not pin one; Gradle 9 needs JDK 17 or newer.

**Metadata.** [Descriptions and graphics](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/):
`fastlane/metadata/android/<locale>/` with `title.txt` (max 50),
`short_description.txt` (max 80), `full_description.txt` (max 4000) and
`changelogs/<versionCode>.txt` (max 500). F-Droid reads them from the tagged
release. The recipe follows the
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and
the [fdroiddata CONTRIBUTING](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)
(`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`); `fdroid lint` and
`fdroid readmeta` pass on it with fdroiddata's `config/`.

## Importing the scoring model from a file

Wherever the model download is offered (Settings, Offline, onboarding), "Import
from a file" opens the system file picker (Storage Access Framework, no storage
permission). The learner picks the model (`wav2vec2_phoneme_int8_dyn_pertensor.onnx`)
and `phoneme_vocab.json`, together or one at a time. Each file is copied into
the app's private storage while its SHA-256 is computed and is installed only
if it matches the digest pinned in `OnDeviceScorer` (the released pack) or the
one the server publishes (`<asset>.sha256`, the same check a download uses).
Names do not matter; anything else is deleted and reported. The files are the
ones `tools/fetch-assets.sh` downloads.

Lesson packs are not importable: they are fetched per lesson from the content
manifest and have no file format of their own yet.

## Releasing a version to F-Droid

Checked for 2.5.5 (2227) on 2026-11-22: the steps below passed locally.

1. Bump `baseVersionName` and `versionCode` in `androidApp/build.gradle.kts`, add
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` (max 500
   characters) for every locale, and set the same version in the recipe's build
   entry, `CurrentVersion` and `CurrentVersionCode`.
2. Check the recipe and the build the way F-Droid runs them:
   - `fdroid readmeta` and `fdroid lint me.shirobyte42.glosso` in a directory with
     the recipe under `metadata/` and fdroiddata's `config/` (sparse clone of
     https://gitlab.com/fdroid/fdroiddata).
   - From a fresh clone of the tagged commit, delete the `rm:` paths, then
     `ANDROID_HOME=... ./gradlew :androidApp:assembleFdroidRelease -Pglosso.ios=false -Pglosso.web=false`.
   - `fdroid scanner --exit-code` on that APK, and the scanner's `scan_source` on
     the clone: the only source finding is the `rm:`'d `stb_vorbis.wasm`.
3. Build the signed APK from the release commit with the same properties and
   the `SIGNING_*` variables set (see the signing config in
   `androidApp/build.gradle.kts`), and check it against the unsigned rebuild of
   step 2: `apksigcopier compare <signed.apk> --unsigned <unsigned.apk>` (needs
   `apksigner` on `PATH`) must pass.
4. Tag the release commit `v<versionName>` and push the tag. F-Droid builds from
   tags (`UpdateCheckMode: Tags`), and the recipe's `commit:` is the tag.
5. Create the GitHub release for the tag and attach the signed APK as
   `glosso-<versionName>-fdroid.apk`, the name `Binaries` points at.
6. First submission only: open the merge request to fdroiddata with
   `metadata/me.shirobyte42.glosso.yml`. Later versions are picked up from new
   tags (`AutoUpdateMode: Version`); each still needs step 5 before F-Droid builds it.
