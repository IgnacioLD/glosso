-- Manual rollback for V3__content_pool.sql. Never discovered by the runner.
drop view if exists glosso.v_gap;
drop table if exists glosso.generation_jobs;
drop table if exists glosso.content_variants;
drop table if exists glosso.content;
