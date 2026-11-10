# Billing (removed)

Glosso is free for everyone, forever. There is no paid tier, no subscription and no in-app purchase.
Anyone who wants to help keep the servers running can make a voluntary donation through Ko-fi from the
About screen; a donation unlocks nothing, because nothing is locked.

The app never sold anything: it always ran in an open beta in which the server unlocked every lesson, so
there were no paying users to migrate.

What was removed:

- the Google Play Billing client, the paywall and every Pro lock in the app;
- server-side purchase verification (`POST /v1/billing/verify`), the Real-time Developer Notifications
  webhook (`POST /v1/billing/rtdn`), and the plan, lifetime-grant and Beta Founder logic behind `/v1/me`;
- the related environment variables (`GLOSSO_PLAY_*`, `GLOSSO_BILLING_CACHE_TTL_SECONDS`, `GLOSSO_RTDN_*`,
  `GLOSSO_PRODUCT_MODE`, `GLOSSO_BETA_*`, `GLOSSO_PLANS_JSON`, `GLOSSO_LIMITS_JSON`, `GLOSSO_PRICING_JSON`).

The database was left alone. `glosso.entitlements`, `glosso.lifetime_grants` and the `beta_founder*`
columns of `glosso.users` (migrations V4 to V6) stay in place and are no longer written. The only code that
still touches them is the account move on Google sign-in, which carries those rows over to the account
instead of letting them be deleted with the placeholder user.

The current `/v1/config` and `/v1/me` shapes are in `docs/PRODUCT-CONFIG-CONTRACT.md`.
