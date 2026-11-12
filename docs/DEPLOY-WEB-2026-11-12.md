# Web app and server deploy (2026-11-12)

Record of the release that put the web app live at `https://app.glossostudio.com`. How it is built and
hosted is in `docs/WEB.md`. The deploy scripts are `web-inspect.sh`, `web-canary.sh`, `web-switch.sh`
and `web-caddy.sh`. They live in the owner's `~/glosso-work/deploy`, run on the VPS as
`ssh root@<vps> 'bash -s' < script`, and print no secrets.

## Server (`api.glossostudio.com`)

### Build and canary
- **Image:** `glosso-glosso-server:candidate-20261112` (`a5329990bc95`), built on the VPS from
  `IgnacioLD/glosso` `main` `4edb645` (via `git archive`) in `/opt/glosso-candidate-20261112`. It replaces
  `618d94afa78a` (from 2026-10-05).
- **Canary** on `127.0.0.1:18080`:
  - 90 s healthy, with the model loaded;
  - the CORS preflight for the web app's score request is allowed;
  - a real web take scores 97 on the canary and 97 on the old live server.

### Switch
- **Before the switch:**
  - rollback tag `glosso-glosso-server:rollback-20261112-155044`;
  - backups `deploy/.env.bak-20261112-155044` and `deploy/docker-compose.yml.bak-20261112-155044`.
- **Change:**
  - `GLOSSO_CORS_ORIGINS=https://app.glossostudio.com`; it was empty, so the API sent no CORS headers before;
  - only `glosso-server` was recreated (`up -d --no-deps --no-build`);
  - healthy at 20 s, `RestartCount` 0.
- **Checked from outside**, through Cloudflare:
  - `/health` ok, with recognition and analytics ready;
  - the preflight from the web origin is allowed;
  - the web take scores 97;
  - the manifest returns 200.
- **Not changed:**
  - `GLOSSO_API_KEY` is listed in `.env` but empty inside the container, so `/v1/score` takes no key, as before.
  - No schema change.

### Rollback
```sh
docker tag glosso-glosso-server:rollback-20261112-155044 glosso-glosso-server:latest
cp -p .env.bak-20261112-155044 .env
docker compose --env-file .env up -d --no-deps --no-build glosso-server   # in /opt/glosso/deploy
```

## Web app (`app.glossostudio.com`)

### What changed
- **Build:** `:shared:wasmJsBrowserDistribution` from `4edb645`, with the default API base and no key,
  copied without the source map to `/opt/glosso/deploy/web` (17 MB).
- **Caddy:**
  - The site block from the repo Caddyfile was **appended to the live Caddyfile**. The live file has sites
    the repo copy lacks (flipper, reparacionestatay.com), so it must never be replaced by the repo file.
  - `./web:/srv/web:ro` was added to the live compose file's caddy service.
  - Validated in a throwaway container, then only caddy was recreated.
  - Backups: `Caddyfile.bak-20261112-155428`, `docker-compose.yml.bak-20261112-155428`.
- **After the restart:**
  - the API, umami, status and reparacionestatay.com answer as before (200, 200, 302, 200);
  - the container reads the new Caddyfile.

### Checked on production
- **Headers:** the CSP and `microphone=(self)` are present; `index.html` is `no-cache`; the wasm is `immutable`; the source map returns 404.
- **Headless Chromium** (muted, with a fake microphone):
  - the page loads, and the normal clip, the slow clip and the take play;
  - the take is scored by the live API (100).

### Known
- **Cloudflare script:** Cloudflare injects its bot-detection script (`/cdn-cgi/challenge-platform`) into the page, and the
  CSP blocks it. This doesn't affect the app; Cloudflare just loses that signal for this host. If it matters, turn off
  JavaScript detections for `app.` in Cloudflare rather than loosening the CSP.
- **iPhone not checked:** Add to Home Screen, the microphone, and sound after the first tap are not checked on a real iPhone yet.

### Rollback
In `/opt/glosso/deploy`, restore `Caddyfile.bak-20261112-155428` with a copy and an `mv` (the
single-file mount needs a new inode), restore `docker-compose.yml.bak-20261112-155428`, then run
`docker compose --env-file .env up -d --no-deps --force-recreate caddy`.
