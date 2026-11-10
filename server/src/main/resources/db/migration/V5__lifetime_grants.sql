-- One-time (lifetime) entitlements, separate from subscriptions.
--
-- `glosso.entitlements` holds the single subscription verdict of a user (Play
-- Billing, with renewal, grace and RTDN). A lifetime grant is a different
-- mechanism: a plan granted once, with no expiry, no renewal and no grace
-- period, never revoked except for explicit fraud (`revoked_at`, set by hand).
-- The Beta Founder benefit (PRO for life, never PERSONALIZED) is one instance
-- (`source = 'beta_founder'`); a future one-time purchase ("Pro de por vida")
-- would be another (`source = 'purchase'`, with its provider reference).
--
-- The effective plan is the highest of the in-force subscription and the
-- non-revoked grants. Idempotent like the previous migrations.

create table if not exists glosso.lifetime_grants (
    id                bigserial primary key,
    user_id           uuid not null references glosso.users(id) on delete cascade,
    plan              text not null,
                      -- PRO|PERSONALIZED
    source            text not null,
                      -- beta_founder|purchase|manual
    provider          text,
    provider_ref_hash text,
    granted_at        timestamptz not null default now(),
    revoked_at        timestamptz,
    revoke_reason     text,
    unique (user_id, plan, source)
);
create index if not exists lifetime_grants_user_idx
    on glosso.lifetime_grants (user_id)
    where revoked_at is null;

-- Founders marked before this migration get their PRO-for-life grant too.
insert into glosso.lifetime_grants (user_id, plan, source, granted_at)
select id, 'PRO', 'beta_founder', coalesce(beta_founder_since, created_at)
from glosso.users
where beta_founder
on conflict (user_id, plan, source) do nothing;

-- The founder benefit is now recorded by kind (PRO_LIFETIME), not by the old
-- display label, so the persisted value says what the founder was granted.
update glosso.users
set beta_founder_benefit = 'PRO_LIFETIME'
where beta_founder and beta_founder_benefit = 'Ventaja Beta Founder';
