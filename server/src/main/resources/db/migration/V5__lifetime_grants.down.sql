-- Manual rollback for V5__lifetime_grants.sql. Never discovered by the runner;
-- applied by hand (psql -f) when a rollback is needed. The Beta Founder status
-- itself lives in glosso.users and survives the rollback.
drop table if exists glosso.lifetime_grants;
