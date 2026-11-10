# Glosso on F-Droid

The Android app has two product flavors in `:androidApp`:

| Flavor | For | Differences |
|---|---|---|
| `playstore` | Google Play | Sign in with Google ("Change phone"), through Credential Manager and Play services. Usage statistics on by default, with the onboarding notice. |
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
  downloads, the HTTP cache and content version, downloaded lessons, cached
  config and account, the linked Google account shown on the phone and unsent
  statistics. The scoring model is kept only when its SHA-256 is the released one
  or the one the new server publishes. Progress is local and is kept.

**Downloading the model and content.** The Inclusion Policy only restricts
downloading "additional executable binary files ... without explicit user
consent". The ONNX model (Apache-2.0) and the lessons are data, downloaded only
when the learner asks. `NonFreeAssets` applies to assets under non-commercial or
no-derivatives licences: the model is Apache-2.0; the lesson texts and audio
(authored in `glosso-data`) need a stated free licence, otherwise a reviewer may
apply it (owner decision). The model can also be imported from a local file
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

**Self-updater.** None. The server's version policy is only a dismissible notice
with no link and no download.

**Signing and reproducible builds.** Optional
([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)). Without
them F-Droid signs with its own key, so the F-Droid and Play installs cannot
update each other. Publishing developer-signed APKs (`Binaries` +
`AllowedAPKSigningKeys`) is possible later but hard to switch to after the first
release (owner decision).

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
