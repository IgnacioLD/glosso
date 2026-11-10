-- V001: base del plano de control.
--
-- Crea el esquema glosso, la extension pgcrypto (UUIDs de las tablas del plano de
-- control) y la tabla de control de migraciones. Las tablas de negocio (users,
-- learner_profiles, content, generation_jobs, ...) las anade cada fase con su
-- propia V00N (ver docs/CONTENT_GENERATION_ARCHITECTURE.md sec. 5 y sec. 12).
--
-- Idempotente: se puede aplicar dos veces sin error.

create extension if not exists pgcrypto;

create schema if not exists glosso;

create table if not exists glosso.schema_migrations (
    version    text primary key,
    name       text,
    applied_at timestamptz not null default now()
);
