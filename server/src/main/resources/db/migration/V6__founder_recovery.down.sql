-- Manual rollback for V6__founder_recovery.sql. Never discovered by the runner;
-- applied by hand (psql -f) when a rollback is needed. The Beta Founder status,
-- the account link and the lifetime grants live in glosso.users and
-- glosso.lifetime_grants, so they survive the rollback; only the outstanding
-- recovery codes are dropped.
drop table if exists glosso.recovery_codes;
