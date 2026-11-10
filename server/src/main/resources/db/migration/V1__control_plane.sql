-- Phase 1 of the adaptive content engine: the control plane.
--
-- Same PostgreSQL as the pre-existing analytics table, which is NOT touched. The
-- published content still lives in content.db (SQLite); these tables only hold
-- the learner signal and the aggregated, anonymous demand.
--
-- Idempotent on purpose: the runner applies each file once, but every statement
-- is guarded so a partially applied migration can be re-run safely.

create extension if not exists pgcrypto;

create table if not exists glosso.users (
    id         uuid primary key default gen_random_uuid(),
    device_id  text unique,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists glosso.learner_profiles (
    id                    bigserial primary key,
    user_id               uuid not null references glosso.users(id) on delete cascade,
    language              text not null default 'en',
    cefr                  text,
    vocabulary_score      int,
    grammar_score         int,
    comprehension_score   int,
    pronunciation_score   int,
    fluency_score         int,
    speaking_score        int,
    sample_counts         jsonb not null default '{}'::jsonb,
    updated_at            timestamptz not null default now(),
    unique (user_id, language)
);
create index if not exists learner_profiles_cefr_idx
    on glosso.learner_profiles (language, cefr);

create table if not exists glosso.phoneme_profiles (
    id                   bigserial primary key,
    user_id              uuid not null references glosso.users(id) on delete cascade,
    language             text not null default 'en',
    phoneme              text not null,
    score                int  not null default 0,
    confidence           real not null default 0,
    error_pattern        text,
    replacement_phoneme  text,
    position             text,
    attempts             int  not null default 0,
    successful_attempts  int  not null default 0,
    last_seen            timestamptz,
    trend                text,
    updated_at           timestamptz not null default now()
);
create unique index if not exists phoneme_profiles_key_idx
    on glosso.phoneme_profiles (user_id, language, phoneme,
                                coalesce(error_pattern, ''),
                                coalesce(position, ''));
create index if not exists phoneme_profiles_phoneme_idx
    on glosso.phoneme_profiles (language, phoneme, error_pattern);

create table if not exists glosso.user_needs (
    id                   bigserial primary key,
    user_id              uuid not null references glosso.users(id) on delete cascade,
    language             text not null default 'en',
    cefr                 text,
    skill                text not null,
    phoneme              text,
    error_pattern        text,
    replacement_phoneme  text,
    position             text,
    priority             real not null default 0,
    status               text not null default 'open',
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);
create index if not exists user_needs_user_idx
    on glosso.user_needs (user_id, status, skill);
create index if not exists user_needs_phoneme_idx
    on glosso.user_needs (language, skill, phoneme, error_pattern);
create index if not exists user_needs_status_idx
    on glosso.user_needs (status);

-- Anonymous unit of demand and of generation. A Need Class NEVER carries a
-- user_id: it is what travels to a provider ("A1 / θ / substitution-t / initial").
-- available_existing/available_effective/gap/gap_state/priority are the snapshot
-- the Demand Aggregator writes every cycle; min/target/max are configuration.
create table if not exists glosso.need_classes (
    id                   bigserial primary key,
    language             text not null default 'en',
    cefr                 text not null,
    skill                text not null,
    phoneme              text,
    error_pattern        text,
    replacement_phoneme  text,
    position             text,
    learner_count        int  not null default 0,
    available_existing   int  not null default 0,
    available_effective  int  not null default 0,
    gap                  int  not null default 0,
    gap_state            text not null default 'OK',
    priority             double precision not null default 0,
    last_aggregated_at   timestamptz,
    created_at           timestamptz not null default now()
);
create unique index if not exists need_classes_key_idx
    on glosso.need_classes (language, cefr, skill, coalesce(phoneme, ''),
                            coalesce(error_pattern, ''),
                            coalesce(replacement_phoneme, ''),
                            coalesce(position, ''));
create index if not exists need_classes_demand_idx
    on glosso.need_classes (language, cefr, learner_count desc);
create index if not exists need_classes_lookup_idx
    on glosso.need_classes (phoneme, error_pattern, cefr);
