-- Reverse of V1__control_plane.sql. Applied manually (psql -f) when a rollback
-- is needed; the automatic runner only applies up migrations.
--
-- The glosso schema and pgcrypto are shared with glosso.analytics_event, so they
-- are deliberately left in place. Only the control-plane tables are dropped.

drop table if exists glosso.need_classes;
drop table if exists glosso.user_needs;
drop table if exists glosso.phoneme_profiles;
drop table if exists glosso.learner_profiles;
drop table if exists glosso.users;
