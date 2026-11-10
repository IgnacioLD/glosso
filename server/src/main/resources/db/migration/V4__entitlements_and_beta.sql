-- Public beta: entitlement persistence and the permanent Beta Founder status.
--
-- Same PostgreSQL and `glosso` schema as V1-V3. `glosso.users` already keys the
-- anonymous device identity (`device_id`); these columns make the Beta Founder
-- status permanent on it and reserve `account_id` so real accounts, when they
-- arrive, can be linked to the existing user instead of replacing it.
--
-- Idempotent on purpose, like the previous migrations, so a partial application
-- can be re-run safely. The runner discovers this file (V4) automatically.

alter table glosso.users
    add column if not exists beta_founder         boolean not null default false,
    add column if not exists beta_founder_since   timestamptz,
    add column if not exists beta_founder_until   timestamptz,
    add column if not exists beta_founder_benefit text,
    add column if not exists account_id           text;

-- One effective entitlement per user: the server upserts the current verdict
-- here (Play purchase, beta, manual). History, if ever needed, is a separate
-- append-only table; the read model stays a single row.
create table if not exists glosso.entitlements (
    id                  bigserial primary key,
    user_id             uuid not null unique references glosso.users(id) on delete cascade,
    plan                text not null default 'FREE',
                        -- FREE|PRO|PERSONALIZED
    status              text not null default 'NONE',
                        -- NONE|ACTIVE|GRACE|CANCELED|PENDING|TRIAL
    source              text not null default 'manual',
                        -- beta|pro|play|manual
    expires_at          timestamptz,
    started_at          timestamptz not null default now(),
    auto_renew          boolean not null default false,
    provider            text,
    provider_token_hash text,
    updated_at          timestamptz not null default now()
);
create index if not exists entitlements_provider_token_idx
    on glosso.entitlements (provider_token_hash);
create index if not exists entitlements_plan_status_idx
    on glosso.entitlements (plan, status);

-- A device may later be linked to at most one account, and an account to at
-- most one device, so the account link is unique when present.
create unique index if not exists users_account_idx
    on glosso.users (account_id)
    where account_id is not null;
