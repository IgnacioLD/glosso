-- Manual rollback for V4__entitlements_and_beta.sql. Never discovered by the
-- runner; applied by hand (psql -f) when a rollback is needed.
drop table if exists glosso.entitlements;

alter table glosso.users
    drop column if exists account_id,
    drop column if exists beta_founder_benefit,
    drop column if exists beta_founder_until,
    drop column if exists beta_founder_since,
    drop column if exists beta_founder;
