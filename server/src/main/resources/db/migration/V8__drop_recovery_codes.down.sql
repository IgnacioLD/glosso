-- Manual rollback for V8__drop_recovery_codes.sql. Never discovered by the
-- runner; applied by hand (psql -f) when a rollback is needed. Recreates the
-- V6 recovery-code table (empty), so the schema matches what V6 left behind.
create table if not exists glosso.recovery_codes (
    id          bigserial primary key,
    user_id     uuid not null references glosso.users(id) on delete cascade,
    code_hash   text not null unique,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    used_at     timestamptz
);
drop index if exists glosso.recovery_codes_user_idx;
create unique index if not exists recovery_codes_one_active_idx
    on glosso.recovery_codes (user_id)
    where used_at is null;
create index if not exists recovery_codes_expires_idx
    on glosso.recovery_codes (expires_at);
