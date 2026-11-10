-- Manual rollback for V7__linked_accounts.sql. Never discovered by the runner;
-- applied by hand (psql -f) when a rollback is needed. The accounts, their Beta
-- Founder status and their grants stay in glosso.users and
-- glosso.lifetime_grants; only the Google links are dropped, so a learner
-- would have to sign in again to relink.
drop table if exists glosso.linked_accounts;
