# Privacy and analytics - Glosso

Summary of what data the product processes and how it is declared in Google
Play. Not legal advice.

## Audio

- Audio is sent to the server **only** to recognise and score pronunciation.
- It is processed in memory and **discarded when the request ends**. It is not
  stored, logged or reused.
- In offline mode (a later phase) the audio never leaves the device.

## First-party analytics (`/v1/events`)

We collect product events in our own database (Postgres,
`glosso.analytics_event`). **It is not shared with third parties.**

| Data | Example | Why |
|---|---|---|
| Random installation id | Random UUID created on first launch | Count unique devices |
| Session id | Random UUID | Group events from one session |
| App version / platform | `1.0.0`, `android` | Compatibility |
| Language practised | `en_GB` | Course improvement |
| Event name and properties | `score_attempt`, `score`, `level` | Analyse usage and difficulty |

**We do not collect**: Advertising ID, IMEI, MAC, `ANDROID_ID` as a stable
identifier, device model or brand, precise location, contacts, SMS, free-form
user text, error messages, or audio.

The server **does not store the IP** in the events table. Server events
(`score_attempt`, `503`, errors) are recorded the same way, without audio.

## Account: Continue with Google (optional)

It carries the account (and the Beta Founder status) to a new phone. It is
optional; the app works the same without it.

- **Continue with Google**: the user picks a Google account on the phone
  (Android Credential Manager). Only the identity is requested: the server
  verifies the ID token and stores **only** the opaque id Google assigns to the
  account (`sub`). It does **not** store the email, name, photo or the token,
  and it asks for no access to Gmail, Drive or any other service. The email is
  shown on the phone itself only.

## Retention

- Configurable with `GLOSSO_ANALYTICS_RETENTION_DAYS` (default **180 days**).
- Purged when the server starts.

## Security

- **TLS required**: the app only talks to the endpoint over HTTPS.
- `/metrics`, `/stats`, `/admin/events` and `/v1/events/summary` are protected
  with a token (`GLOSSO_ADMIN_TOKEN`); without a token they are closed.
- Size limit for events and audio; security headers (HSTS,
  `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`).

## Google Play declaration (Data safety)

- **Data collected / Personal info / User IDs**: yes, only if the user
  continues with Google (optional): the Google account id.
- **Data collected / App activity**: "Other actions" -> yes (usage events).
- **Data collected / Device or other IDs**: "Device or other IDs" -> yes
  (random installation id).
- **Data collected / App info and performance**: "Crash logs" and
  "Diagnostics" -> yes, from app 2.5.4 (error type, place in the code and
  technical call stack without messages or user data; scoring speed, Android
  version, phone or tablet). Not shared, optional, for analytics and app
  functionality.
- **Shared with third parties**: no.
- **Encrypted in transit**: yes.
- **Data deletion**: provide a support channel and, if applicable, an in-app
  button to reset the id and delete the associated data.

## Your choice

- Usage statistics are on by default and the onboarding says so. They can be
  turned off at any time in Settings > Usage statistics; the app works exactly
  the same without them, and the server then stops recording the score event
  for this device too.
- "Delete my usage data" in the same place erases every event of this device on
  our server.
- The events the app sends: app opened, session length, onboarding started and
  finished, lesson started, finished or left, exercise finished (kind, a
  10-point score band, mastered or not), pronunciation attempt scored on the
  phone (score band, mastered or not, ids of the error rules), microphone
  permission denied, paywall seen, Pro button tapped and purchase started or
  failed.

From app 2.5.4, the app also sends:

- On app open, the context: Android version, phone or tablet, dark mode on or
  off, whether the optional on-device model is installed, interface language
  and chosen accent. Never the device model, the brand or a hardware
  identifier.
- Scoring speed (milliseconds) and whether the take was scored on the phone.
- Screens viewed (from a fixed list), lesson steps reached, audio played
  (which kind of audio, normal or slow, and replaying your own recording,
  never the recording), "Your turn" and review results (recalled, partial,
  not that phrase, revealed), stories opened and how much was read
  (percentage), and the daily streak length.
- Settings changed (which one and its new value: target language, accent,
  interface language, phonetic transcription, translations, daily reminder,
  Wi-Fi-only downloads).
- Downloads of lessons or the model (started, finished or failed, size in MB,
  short error code) and model deleted.
- Visible errors by type (scoring failed, offline, not evaluated,
  microphone, content, audio) and crash reports: the type of error, the place
  in the app's code where it happened and the technical call stack (class,
  method, file and line), sent on the next launch for fatal crashes;
  never the error message, the content or anything you type.
- Notification permission granted or not and the result of signing in with
  Google (success, cancelled, no account, failed).

All of it under the same rules: only yes/no values, bounded numbers and ids,
never free text or audio; kept 180 days, and "Delete my usage data" erases
all of it, crash reports included. The server discards any other event.
