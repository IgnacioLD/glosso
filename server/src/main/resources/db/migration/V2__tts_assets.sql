-- Decoupled TTS layer: metadata for generated audio assets and their cost.
--
-- Same PostgreSQL and `glosso` schema as the phase-1 control plane (V1). It
-- holds metadata ONLY: the audio bytes live in object storage (R2/S3), never in
-- PostgreSQL.
--
-- Idempotent on purpose, so a partial application can be re-run safely. The
-- phase-1 schema runner discovers this file (V2) and applies it after V1.

create schema if not exists glosso;

create table if not exists glosso.tts_assets (
    id              text primary key,
    cache_key       text not null unique,
    content_id      text,
    text            text not null,
    language        text not null,
    provider        text not null,
    model           text,
    voice           text,
    speed           text not null,
    speed_factor    double precision not null,
    format          text not null,
    object_key      text not null,
    duration_ms     bigint,
    size_bytes      bigint not null,
    checksum        text not null,
    estimated_cost  numeric(12,6) not null default 0,
    characters      int not null default 0,
    credits         double precision,
    job_id          text,
    created_at      timestamptz not null default now()
);
create index if not exists tts_assets_provider_idx
    on glosso.tts_assets (provider, created_at desc);
create index if not exists tts_assets_content_idx
    on glosso.tts_assets (content_id);

create table if not exists glosso.tts_costs (
    id              bigserial primary key,
    job_id          text,
    content_id      text,
    provider        text not null,
    model           text,
    voice           text,
    characters      int not null default 0,
    credits         double precision,
    estimated_cost  numeric(12,6) not null default 0,
    created_at      timestamptz not null default now()
);
create index if not exists tts_costs_job_idx on glosso.tts_costs (job_id);
create index if not exists tts_costs_provider_idx on glosso.tts_costs (provider, created_at desc);
