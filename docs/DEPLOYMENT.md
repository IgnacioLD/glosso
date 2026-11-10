# Glosso Pro - Despliegue del VPS

Guia para el VPS de 4-8 GB que aloja el servicio y los ficheros de la app.

## 1. Que corre en el VPS

- **glosso-server** (Kotlin/Ktor): API de scoring, proxy LLM (F4), health.
- **Caddy**: reverse proxy con TLS automatico (Let's Encrypt).
- **Assets estaticos**: modelo ONNX, vocab, DBs de nivel, audio de referencia,
  diccionario CMUdict. Se sirven bajo `/assets/...`.

## 2. Estructura de ficheros en disco

```
/srv/glosso/assets/
  wav2vec2_phoneme_fp16.onnx       # modelo unico (~603 MB), compartido por idiomas
  phoneme_vocab.json
  content/
    en/
      content.db                   # todas las clases (SQLite) del ingles
      manifest.json
      cmudict.dict                 # G2P de ingles (BSD-2-Clause)
      audio/ images/
```

Hoy solo se despliega ingles (`content/en/`). El layout sigue soportando varios
idiomas en `content/<code>/`, pero el pack `fr` esta dormido en el engine hasta su
proxima actualizacion y no tiene banco en el VPS. El modelo es unico (se entreno en
~60 inventarios espeak). `GLOSSO_ASSETS_DIR` apunta a la raiz. Un despliegue antiguo
con el ingles plano en la raiz sigue funcionando (fallback de compatibilidad).

`GLOSSO_ASSETS_DIR` apunta a esa carpeta. El servidor sirve `/assets` solo si el
directorio existe.

## 3. Despliegue con Docker Compose (recomendado)

```sh
# En el VPS, con Docker y Docker Compose instalados:
git clone <repo> glosso-pro
cd glosso-pro/deploy
cp .env.example .env      # editar dominio y rutas
docker compose up -d --build
```

El `Dockerfile` construye el servidor con Gradle (`installDist`) y lo ejecuta en
un JRE 21. `Caddyfile` publica el dominio con TLS.

## 4. Despliegue con systemd (sin Docker)

```sh
# En el VPS:
# 1. Instalar JDK 21
# 2. Copiar el artefacto
scp server/build/install/server/* usuario@vps:/opt/glosso/
# 3. Instalar el servicio
sudo cp deploy/glosso-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now glosso-server
```

## 5. Variables de entorno

| Variable | Defecto | Descripcion |
|---|---|---|
| `PORT` | `8080` | Puerto de escucha |
| `GLOSSO_ASSETS_DIR` | `data/assets` | Carpeta de assets estaticos |
| `GLOSSO_BOOTSTRAP` | (vacio) | `1` descarga modelo/vocab/CMUdict en el primer arranque |
| `GLOSSO_MAX_CONCURRENT` | `1` | Reconocimientos simultaneos admitidos |
| `GLOSSO_QUEUE_WAIT_MS` | `30000` | Espera maxima en cola antes de devolver 503 |
| `GLOSSO_DEBUG` | (vacio) | `1` activa logs del engine (fonemas) |
| `GLOSSO_ADMIN_TOKEN` | (vacio) | Token que protege `/metrics`, `/stats` y las vistas de analitica. Sin el, cerrados |
| `GLOSSO_API_KEY` | (vacio) | Credencial de cliente (`X-Api-Key`) que protege `/v1/score`. Sin ella, scoring abierto (dev/emulador). **No definir en produccion hasta que la app envie la cabecera** |
| `GLOSSO_DB_URL` | (vacio) | JDBC URL de Postgres para analitica. Sin ella, analitica desactivada |
| `GLOSSO_DB_USER` | `postgres` | Usuario de la base de datos |
| `GLOSSO_DB_PASSWORD` | (vacio) | Password de la base de datos |
| `GLOSSO_ANALYTICS_RETENTION_DAYS` | `180` | Dias de retencion de eventos (se purga al arrancar) |
| `GLOSSO_CORS_ORIGINS` | (vacio) | Lista de origenes permitidos (web). Vacio = sin CORS (app nativa no lo necesita) |
| `GLOSSO_PLAY_SERVICE_ACCOUNT_JSON` | (vacio) | Ruta al JSON de la cuenta de servicio de Play. Sin ella, verificacion de compras desactivada |
| `GLOSSO_PLAY_SERVICE_ACCOUNT_JSON_BASE64` | (vacio) | Alternativa al fichero: el JSON en base64 |
| `GLOSSO_PLAY_PACKAGE` | `me.shirobyte42.glosso` | Paquete de Play que se valida |
| `GLOSSO_PLAY_PRODUCT_ID` | `com.glosso.app.pro` | Suscripcion Pro que concede entitlement |
| `GLOSSO_PLAY_TRIAL_OFFER_ID` | (vacio) | Id de la oferta de prueba para reportar `TRIAL` (si no, `ACTIVE`) |
| `GLOSSO_BILLING_CACHE_TTL_SECONDS` | `600` | TTL de la cache de verificaciones (0 = sin cache) |
| `GLOSSO_RTDN_SECRET` | (vacio) | Secreto compartido que Pub/Sub presenta en `/v1/billing/rtdn`. Sin el, webhook cerrado |
| `GLOSSO_RTDN_SUBSCRIPTION` | (vacio) | Subscription push esperada; si se define, se comprueba el sobre |

Detalle y pasos exactos de Play/Pub/Sub: `docs/BILLING-E2E.md`.

## 6. Endpoints

| Metodo | Ruta | Descripcion |
|---|---|---|
| GET | `/health` | Estado, version, recognition, assets y analitica |
| GET | `/ready` | `200` solo cuando el modelo esta cargado (para el proxy/uptime) |
| GET | `/metrics` | Metricas Prometheus (requiere `GLOSSO_ADMIN_TOKEN`) |
| POST | `/v1/score` | Scoring: `audioBase64` (reconocimiento en nube) o `actualIpa`, con `language` objetivo. Requiere `X-Api-Key` si `GLOSSO_API_KEY` esta definida |
| POST | `/v1/events` | Analitica first-party de la app (lote de eventos) |
| GET | `/v1/events/summary` | Resumen JSON de eventos (requiere token) |
| GET | `/v1/events/product` | Rollup de producto: notas, mastery, top errores (requiere token) |
| GET | `/v1/content/manifest?language=<code>` | Catalogo descargable del idioma (por defecto `en`) |
| GET | `/v1/content/{kind}?language=<code>` | Contenido del idioma (`DRILL`, `WORD`, `STORY`, `DIALOGUE`, `ARTICLE`, `EXERCISE`, `LESSON`) |
| POST | `/v1/billing/verify` | Valida un `purchaseToken` contra la Play Developer API; `503` si no hay cuenta de servicio |
| POST | `/v1/billing/rtdn` | Webhook de Real-time Developer Notifications (Pub/Sub), protegido por `GLOSSO_RTDN_SECRET` |
| GET | `/admin/events` | Pagina HTML de analitica (requiere token) |
| GET | `/stats` | Ocupacion del gate de inferencia (requiere token) |
| GET | `/assets/content/<code>/{file}` | Ficheros estaticos por idioma (si `GLOSSO_ASSETS_DIR` existe) |

Los eventos de analitica no deben contener datos personales; el cliente envia un
id anonimo por instalacion. Ver `docs/PRIVACY.md`.

## 7. Capacidad y escalado

- 4 GB: ~2-4 inferencias wav2vec2 concurrentes. 8 GB: el doble.
- El LLM de conversacion **no** corre aqui: se proxya a una API externa (F4).
- El coste real es el **ancho de banda** (descarga del modelo), no el disco.
- Un solo VPS es punto unico de fallo. Plan de escalado: segunda instancia +
  balanceador (Caddy o un LB del proveedor), y CDN para los assets.

### Modelo: fp16, no int8

El modelo **int8** usa `ConvInteger` sobre convoluciones 1D, que el execution
provider CPU de ONNX Runtime no implementa: falla tanto en ARM64 como en x86_64.
Se usa el modelo **fp16** (~603 MB), que carga en cualquier CPU. Detalle en
`docs/FINDINGS.md`.

## 7b. Assets

Descargar con `tools/fetch-assets.sh [dir]` (modelo fp16, vocab, CMUdict). Los
assets no se versionan en git; viven en el VPS y se sirven bajo `/assets`.

Alternativa sin SSH: el contenedor descarga los assets en el primer arranque si se
define `GLOSSO_BOOTSTRAP=1` (van al volumen persistente `/assets`).

### 7c. Contenido e imagenes

El servidor tambien sirve el contenido generado bajo `/assets`, un directorio por
idioma en `content/<code>/`:

```
content.db     # todas las clases (SQLite), incluye el campo `image` de cada item
manifest.json  # catalogo del idioma (temas + bancos)
<code>.lexicon # G2P runtime (no-ingles; generado por phonemize.py)
audio/         # audio de referencia (Kokoro)
images/        # una ilustracion por item: images/<id>.png o .svg
```

Tras regenerar el contenido, sincronizarlo al directorio de assets:

```sh
python tools/content/phonemize.py --language <code> --content ../glosso-data/content/<code>
./gradlew :server:publishContent -Planguage=<code>    # banks -> dist/ (copia images/)
python tools/content/pipeline.py --language <code> \
    --audio-kinds drills,dialogues,words,stories,articles    # dist/ -> content.db + audio
deploy/sync-content.sh --language <code>                        # -> data/assets/content/<code>
deploy/sync-content.sh --language <code> user@vps:/srv/glosso/assets
```

La app pide el contenido en `/v1/content/{kind}?language=<code>` y carga las
imagenes y el audio desde `/assets/content/<code>/...`. Para el detalle completo
de anadir un idioma, ver `docs/ADD_LANGUAGE.md`.

`--audio-kinds` ya incluye todos los tipos con audio (`drills,dialogues,words,
stories,articles`) y el pipeline **falla** si un tipo tiene clips en disco pero
su mapa queda vacio (o al reves), para no publicar audio pagado que la app no
puede reproducir. Fue el caso de historias y noticias: los clips existian pero
`paragraphAudio` salia vacio. Para un pase deliberado solo-datos, `--audio-kinds
"" --skip-audio-check`.

### Version de contenido e invalidacion de cache

Hay **dos caches** que pueden ocultar una actualizacion de assets:

1. **Cloudflare** (delante de Caddy) cachea por extension hasta 4 horas
   (`cache-control: max-age=14400`). Una actualizacion tardaria ese tiempo o
   exigiria purgar a mano.
2. **La app**, que resuelve primero el fichero descargado del pack offline; sin
   version, seguiria sirviendo la copia antigua para siempre.

La solucion es una **version de contenido** (`assetVersion`) que identifica el
estado del catalogo y cambia cuando cambia cualquier asset:

- El servidor la calcula al cargar el contenido (`assetVersionFor` en
  `Application.kt`): hash SHA-256 de los bytes de `content.db` mas la lista de
  cada fichero de `audio/` e `images/` con tamano y mtime. `sync-content.sh` usa
  `rsync -a`, que preserva mtimes, asi que el hash es estable entre reinicios y
  cambia exactamente cuando cambia un fichero (los mismos nombres incluidos).
- El servidor la entrega en `GET /v1/content/manifest` (`assetVersion`) y la usa
  como ETag. Es la version que la app pone en cada URL de asset: `?v=<version>`.
- La app versiona tanto imagenes (`.png` y `.svg`, mismo camino) como audio, y el
  pack offline guarda sus assets bajo `filesDir/offline/assets/<version>/`. Cuando
  la version cambia, `OfflineStore` deja de servir lo descargado para la version
  anterior y lo vuelve a pedir; no se redescarga nada si la version no cambia.

Al publicar contenido nuevo basta con reiniciar el servidor (recalcula el hash):
la nueva version genera URLs nuevas y Cloudflare y la app fallan a MISS a la
vez, sin purgas manuales.

## 8. Operacion

- Health check: `GET /health`.
- Logs: `docker compose logs -f glosso-server` o `journalctl -u glosso-server`.
- Cambios del `deploy/Caddyfile`: vive en el repo (bind mount en el contenedor
  `caddy`), asi que se edita, se sincroniza al VPS y se recarga sin reiniciar:
  `docker compose -f deploy/docker-compose.yml exec caddy caddy reload --config /etc/caddy/Caddyfile`
  (o `docker compose -f deploy/docker-compose.yml restart caddy`).
- Backups: de `GLOSSO_ASSETS_DIR` (assets re-generables) y, cuando existan, de la
  base de datos de cuentas/entitlement.
- Monitorizacion de CPU/RAM/ancho de banda desde el panel del proveedor.

## 9. Pendiente (fases siguientes)

- Subida y reconocimiento de audio en el servidor (ONNX JVM).
- Cuentas, entitlement firmado y verificacion de Play Billing.
- Rate limiting, Play Integrity, cola del LLM gratuito.
- CDN para assets cuando crezca el trafico.
