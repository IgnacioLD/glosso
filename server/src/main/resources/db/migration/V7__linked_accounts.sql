-- Sign in with Google: an external account that follows the person, not the
-- phone.
--
-- A row links one external identity (`provider` + `subject`, the provider's
-- stable opaque id, e.g. the `sub` of a verified Google ID token) to one
-- `glosso.users` row. Signing in on a new device moves that user to the device,
-- exactly like a recovery code (V6), so the Beta Founder status and the
-- lifetime grants follow the person. Nothing else is stored: no email, no name,
-- no photo, no token. Idempotent like the rest.
--
-- An external identity belongs to one user, and a user has at most one
-- identity per provider.

create table if not exists glosso.linked_accounts (
    id              bigserial primary key,
    user_id         uuid not null references glosso.users(id) on delete cascade,
    provider        text not null,
    subject         text not null,
    linked_at       timestamptz not null default now(),
    last_sign_in_at timestamptz not null default now(),
    unique (provider, subject),
    unique (user_id, provider)
);
