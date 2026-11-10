-- Founder recovery: a one-time code that carries the account of an anonymous
-- device to a new device.
--
-- `glosso.users` identifies a person by their device (`device_id`); a Beta
-- Founder who changes phone would otherwise lose the status and its lifetime
-- PRO. This table backs the "change phone" flow: the old device issues a
-- short-lived code, the new device redeems it, and the account row moves to the
-- new device (`account_id` marks the stable account). Only an HMAC of the code,
-- keyed with a server secret (GLOSSO_ACCOUNT_SECRET), is stored: the table
-- cannot be used to log in, to brute-force a code offline or to read any
-- personal data. Idempotent like the rest.
--
-- One code is active per user at a time (issuing invalidates the previous one,
-- and the unique partial index makes it a database invariant, not only a code
-- path), it is single-use (`used_at`) and it always expires. Rows past their
-- expiry are purged when codes are issued (see JdbcEntitlementStore).

create table if not exists glosso.recovery_codes (
    id          bigserial primary key,
    user_id     uuid not null references glosso.users(id) on delete cascade,
    code_hash   text not null unique,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    used_at     timestamptz
);
-- A first draft of this migration declared a non-unique index under this
-- name; the unique one below replaces it.
drop index if exists glosso.recovery_codes_user_idx;
create unique index if not exists recovery_codes_one_active_idx
    on glosso.recovery_codes (user_id)
    where used_at is null;
create index if not exists recovery_codes_expires_idx
    on glosso.recovery_codes (expires_at);
