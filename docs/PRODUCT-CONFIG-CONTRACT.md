# Product contract: config, identity and flags

## 1. Authority

The backend is the authority for feature flags and the client version policy. The client never decides or
writes any of it. Glosso is free for everyone: there are no plans, prices, entitlements or phases (see
`docs/BILLING.md`).

## 2. User identity

The identity is the DEVICE:

- The app generates a UUID on first launch and persists it (`glosso_device_id`).
- The app sends it on every relevant call as the `X-Device-Id` header.
- The server keeps `glosso.users (device_id unique, created_at)`. The first call creates the user.
- Sign in with Google links an account to that user and moves it to a new phone (`docs/GOOGLE-SIGNIN.md`).

## 3. GET /v1/config

```json
{
  "schema": 1,
  "mode": "BETA",
  "beta": { "active": true },
  "flags": { "accounts": false },
  "client": { "minimumSupportedVersion": 2210, "recommendedVersion": 2219, "forceUpdate": false }
}
```

Rules:

- `mode` and `beta` are fixed legacy keys. App builds that still contain the paid tier unlock every lesson
  only while they read an open beta (`mode = "BETA"` or `beta.active = true`), so the server always serves
  exactly these values. Never serve `"FREE"` or `"active": false`.
- `flags` without a key = `false`. A new flag needs no app release.
- `client` is the version policy. The app only shows a soft, dismissible notice; nothing blocks.
  `recommendedVersion` never sits below `minimumSupportedVersion`.
- Served with `Cache-Control: no-cache` + `ETag`, the same pattern as `/v1/content/manifest`.
- `schema` changes only on a breaking change; clients ignore unknown keys.

## 4. GET /v1/me

Authenticated by `X-Device-Id`; `400` without it, `503` without a database.

```json
{ "linkedAccounts": ["google"] }
```

- `linkedAccounts`: the external providers linked to the account (today only `google`), never the
  identifier. If the account moved to another phone, this device is a new anonymous user and gets `[]`, so
  the app forgets its local "linked to ..." state.
- `POST /v1/auth/google` answers the same shape.
- Served with `Cache-Control: no-store`.

## 5. Configuration

Server environment variables, read once in `ProductConfig.fromEnv()`:

- `GLOSSO_MIN_CLIENT_VERSION` (default `2210`), `GLOSSO_RECOMMENDED_CLIENT_VERSION` (default `2219`),
  `GLOSSO_FORCE_UPDATE` (default `false`);
- `GLOSSO_FLAG_<NAME>` for each feature flag, served as `flags.<name>` in lower case.

An empty variable counts as unset (`docker compose` injects `""`).
