-- Phase 2 of the adaptive content engine: the content pool and the generation
-- queue's job ledger.
--
-- Same PostgreSQL and `glosso` schema as the phase-1 control plane (V1) and the
-- TTS metadata (V2). It holds metadata ONLY: audio and images live in object
-- storage (R2/S3) and are referenced by key. `content.db` (SQLite) is still the
-- read path the app uses; this is the control plane the engine writes to.
--
-- Idempotent on purpose, so a partial application can be re-run safely. The
-- phase-1 schema runner discovers this file (V3) and applies it after V2.

create schema if not exists glosso;

-- A logical content piece. Its servable instances (one per voice / length /
-- regeneration) live in content_variants, which is what makes the pool
-- idempotent and reusable. The per-piece fields the brief lists (text, ipa,
-- answer, audio, slow audio, image) live on the variant; the piece carries the
-- identity and the Need Class it serves.
create table if not exists glosso.content (
    id                   uuid primary key default gen_random_uuid(),
    kind                 text not null default 'DRILL',
    language             text not null default 'en',
    cefr                 text,
    skill                text,
    objective            text,
    exercise_type        text,
    target_phoneme       text,
    error_pattern        text,
    replacement_phoneme  text,
    position             text,
    difficulty           real,
    status               text not null default 'DRAFT',
                         -- DRAFT|GENERATING|VALIDATING|APPROVED|ACTIVE|DEPRECATED|REJECTED
    quality_score        real,
    created_by           text,
    provider             text,
    need_class_id        bigint references glosso.need_classes(id) on delete set null,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);
create index if not exists content_pool_idx
    on glosso.content (language, cefr, skill, target_phoneme, error_pattern, position, status);
create index if not exists content_status_idx on glosso.content (status);
create index if not exists content_need_class_idx on glosso.content (need_class_id);

create table if not exists glosso.content_variants (
    id             uuid primary key default gen_random_uuid(),
    content_id     uuid not null references glosso.content(id) on delete cascade,
    variant_hash   text not null unique,
    spec           jsonb not null default '{}'::jsonb,
    text           text not null,
    ipa            text,
    answer         jsonb,
    audio_key      text,
    slow_audio_key text,
    image_key      text,
    voice          text,
    provider       text,
    model          text,
    status         text not null default 'DRAFT',
    quality_score  real,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create index if not exists content_variants_content_idx
    on glosso.content_variants (content_id, status);
create index if not exists content_variants_status_idx
    on glosso.content_variants (status);

-- One unit of generation work. `job_hash` is the idempotency key: the same hash
-- never produces two jobs. `provider`/`error` record who failed, so a dead job
-- can be diagnosed without guessing. The binary result lives in object storage.
create table if not exists glosso.generation_jobs (
    id                uuid primary key default gen_random_uuid(),
    job_hash          text not null unique,
    need_class_id     bigint references glosso.need_classes(id) on delete set null,
    content_id        uuid references glosso.content(id) on delete set null,
    variant_id        uuid references glosso.content_variants(id) on delete set null,
    task              text not null default 'tts',
    spec              jsonb not null default '{}'::jsonb,
    target_count      int  not null default 1,
    status            text not null default 'QUEUED',
                      -- QUEUED|RUNNING|DONE|FAILED|DEAD
    attempts          int  not null default 0,
    max_attempts      int  not null default 3,
    provider          text,
    model             text,
    max_cost_per_job  numeric(10,4),
    requested_at      timestamptz not null default now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    error             text
);
create index if not exists generation_jobs_status_idx
    on glosso.generation_jobs (status, requested_at);
create index if not exists generation_jobs_need_idx
    on glosso.generation_jobs (need_class_id);
create index if not exists generation_jobs_variant_idx
    on glosso.generation_jobs (variant_id);

-- Admin view: demand per Need Class against the effective pool. The 20/50 are
-- the brief's default pool sizes for a quick glance; the scheduler uses the
-- configured min/target/max, never this view, to decide.
create or replace view glosso.v_gap as
select
    nc.id              as need_class_id,
    nc.language,
    nc.cefr,
    nc.skill,
    nc.phoneme,
    nc.error_pattern,
    nc.replacement_phoneme,
    nc.position,
    nc.learner_count,
    count(c.id) filter (where c.status = 'ACTIVE') as available_effective,
    case
        when count(c.id) filter (where c.status = 'ACTIVE') < 20 then 'BELOW_MIN'
        when count(c.id) filter (where c.status = 'ACTIVE') < 50 then 'BELOW_TARGET'
        else 'OK'
    end as gap_state
from glosso.need_classes nc
left join glosso.content c
       on c.language = nc.language
      and coalesce(c.cefr, '') = coalesce(nc.cefr, '')
      and coalesce(c.skill, '') = coalesce(nc.skill, '')
      and coalesce(c.target_phoneme, '') = coalesce(nc.phoneme, '')
      and coalesce(c.error_pattern, '') = coalesce(nc.error_pattern, '')
      and coalesce(c.replacement_phoneme, '') = coalesce(nc.replacement_phoneme, '')
      and coalesce(c.position, '') = coalesce(nc.position, '')
group by nc.id, nc.language, nc.cefr, nc.skill, nc.phoneme, nc.error_pattern,
         nc.replacement_phoneme, nc.position, nc.learner_count;
