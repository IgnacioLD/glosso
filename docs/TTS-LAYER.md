# Capa de TTS desacoplada de proveedores

**Contrato:** `docs/TTS-LAYER-BRIEF.md` manda. Este documento describe lo
implementado en la rama `feat/tts-layer` y lo que queda pendiente.

**Estado:** la capa esta implementada y testeada como componente desacoplado.
**Fase 2 conectada:** la rama `feat/content-pool-queue` anade el Content Pool y
la cola Redis (`glosso:gen:tts`) y el worker llama a `TtsJobProcessor`; ver
`docs/CONTENT_POOL.md`. La generacion de **texto** (fase 3) sigue pendiente.

---

## 0. Inspeccion previa (PASO 0 del encargo)

Revisado el repo antes de escribir codigo. Lo que hay hoy:

| Pieza | Estado real |
|---|---|
| Backend | `:server` Ktor 3.0.3, un `Application.kt`. Rutas de scoring, contenido, billing, admin. |
| Content Pool | **No existe** como concepto del brief. El contenido publicado vive en SQLite `content.db` por idioma (`ContentDb.kt`), solo lectura, escrito por `tools/content/pipeline.py`. |
| Generacion de contenido | Fase 1 (`feat/need-engine`, PR #61) cubre perfil -> necesidad -> need class -> demanda y ya esta **mergeada** en `main`. Fases 2/3 (Content Pool, cola Redis, worker) **no existen todavia**. |
| Almacenamiento | **No hay R2/S3.** Solo el volumen de assets `/srv/glosso/assets` y la cache en disco del pipeline. |
| Configuracion | Variables de entorno leidas sueltas en `AnalyticsStore`, `BillingService`, etc. No habia `.env.example`. |
| Worker / queue | **No existen.** Ni Redis, ni scheduler, ni streams. |
| Redis | **No existe** (ni dependencia, ni compose). |
| Abstraccion TTS previa | **No existe.** `elevenlabs`/`cartesia` solo aparecian en documentacion. El audio offline lo hace Kokoro/Chatterbox en `tools/content/pipeline.py`. |

Conclusion: no habia una abstraccion que reutilizar. Se implementa una nueva,
aislada en `com.glosso.server.tts`, sin tocar el plano de senal
(`/v1/score`, `/v1/content/*`, `/assets`), billing ni `:engine`.

---

## 1. Archivos

Todo el codigo nuevo vive en `server/src/main/kotlin/com/glosso/server/tts/`:

| Archivo | Rol |
|---|---|
| `TtsModels.kt` | Tipos del dominio: `TtsRequest`, `TtsResult`, `AudioFormat`, `TtsSpeed`, `TtsJob`, `TtsAssetRecord`, `TtsConfig`, `VoicePalette`, `TtsFailureKind`/`TtsException`. |
| `TtsProvider.kt` | La interfaz `TtsProvider` (la abstraccion que pide el brief). |
| `CartesiaTtsProvider.kt` | Proveedor Cartesia (Sonic). Primario. |
| `ElevenLabsTtsProvider.kt` | Proveedor ElevenLabs. Secundario, apagado por defecto. |
| `TtsProviderRouter.kt` | Seleccion de proveedor, retries con backoff, limite de concurrencia y fallback. |
| `TtsFingerprint.kt` | Clave de cache determinista y `object_key`. |
| `AudioValidator.kt` | Validacion del audio antes de publicar. |
| `TtsPricing.kt` | Calculo de `estimated_cost`. |
| `HttpTransport.kt` | Cliente HTTP JDK detras de una interfaz testeable. |
| `HttpErrors.kt` | Traduccion de respuestas HTTP a errores transitorios/permanentes. |
| `ObjectStore.kt` | Interfaz de object storage + in-memory + ficheros locales. |
| `S3ObjectStore.kt` | Cliente S3-compatible real (R2/S3) via AWS SDK v2. |
| `TtsAssetStore.kt` | Interfaz de metadata + implementacion in-memory. |
| `JdbcTtsAssetStore.kt` | Metadata en PostgreSQL (mismo esquema `glosso`). |
| `TtsObservability.kt` | Eventos por `job_id` con redaccion de secretos. |
| `TtsService.kt` | Orquestacion end to end (cache -> generar -> validar -> subir -> metadata). |
| `TtsJobProcessor.kt` | Punto de conexion del futuro worker (firma explicita). |
| `TtsAssembly.kt` | Composition root desde entorno. |

Recursos y tests:

- `server/src/main/resources/db/migration/V2__tts_assets.sql` - DDL de
  `glosso.tts_assets` y `glosso.tts_costs` (idempotente).
- `server/src/test/kotlin/com/glosso/server/tts/` - 45 tests.
- `deploy/.env.example` - todas las variables, sin secretos.
- `gradle/libs.versions.toml`, `server/build.gradle.kts` - AWS SDK v2 `s3` y
  `url-connection-client`.

---

## 2. Arquitectura resultante

```
  (futuro) Worker TTS  -->  TtsJobProcessor.process(TtsJob)
                                   |
                                   v
                             TtsService.synthesize
       fingerprint -> cache lookup (PostgreSQL metadata + R2 object)
                                   |
                       hit? --> reutilizar, coste 0
                                   |
                                  miss
                                   v
                        TtsProviderRouter.synthesize
              (retries + backoff + concurrencia + fallback controlado)
                          |                    |
                   CartesiaTtsProvider   ElevenLabsTtsProvider
                          |                    |
                          +---------+----------+
                                    v
                        AudioValidator (formato, tamano, duracion, checksum)
                          |  invalido -> FAILED, no se publica
                          v
                   ObjectStore.put -> S3/R2 (o local/in-memory en dev)
                                    |
                          TtsAssetStore.insertIfAbsent + recordCost
```

Nada fuera de `com.glosso.server.tts` conoce a Cartesia ni a ElevenLabs. El
router devuelve el proveedor que finalmente genero, y queda registrado.

---

## 3. Como configurar Cartesia

En el entorno (nunca en Git, nunca en el cliente Android):

```
TTS_PROVIDER=cartesia
CARTESIA_API_KEY=sk_car_...
CARTESIA_MODEL=sonic-3.6
CARTESIA_DEFAULT_VOICE=<voice-id>
CARTESIA_VERSION=2026-08-14      # opcional, default
CARTESIA_BASE_URL=https://api.cartesia.ai   # opcional
CARTESIA_TOKEN_GRANTS=tts                    # opcional, default `tts`
CARTESIA_TOKEN_EXPIRES_IN=120                # opcional, segundos
CARTESIA_TOKEN_REFRESH_SKEW_SECONDS=30       # opcional
CARTESIA_TOKEN_VERSION=2026-08-14            # opcional, default CARTESIA_VERSION
```

**Autenticacion en dos pasos (verificado con llamadas reales).** La API key
**sola no vale** para `/tts/bytes`: con `X-API-Key` o con
`Authorization: Bearer sk_car_...` devuelve **401 "Invalid API key"** aunque la
key sea correcta. `/voices` si acepta la API key (200); el endpoint de sintesis
espera un **access token**. Flujo correcto:

1. `POST {base}/access-token` con `Authorization: Bearer <API_KEY>`,
   `Cartesia-Version` y cuerpo
   `{"grants":{"tts":true},"expires_in":120}` -> 200 `{"token":"eyJ..."}` (JWT).
2. `POST {base}/tts/bytes` con `Authorization: Bearer <JWT>` **y**
   `Cartesia-Version` (obligatoria, sin ella 400) -> 200 + audio.

El provider pide un token por cada canje y lo **cachea** hasta poco antes de
expirar (`expires_in - skew`), asi que una sola llamada cubre muchas frases. Si
`/tts/bytes` responde 401, el token se **re-canjea una vez** y se reintenta; un
segundo 401 es error permanente (no dispara fallback). El 401 del propio
`/access-token` tambien es permanente y no se reintenta.

Contrato de sintesis: `POST {base}/tts/bytes`, cabecera `Cartesia-Version`,
credencial `Authorization: Bearer <JWT>`, cuerpo `{model_id, transcript,
voice{id}, language, output_format, generation_config.speed}`, audio binario en
200. La velocidad va en `generation_config.speed` (normal 1.0, lenta
`TTS_SLOW_SPEED`, 0.7 por defecto).

**Duracion del WAV.** Cartesia devuelve el WAV en streaming con las longitudes
RIFF/`data` a placeholder (`0x7FFFFFFF`), asi que la duracion no se lee de la
cabecera: se calcula del tamano real del payload
(`bytes / (sample_rate * channels * bits / 8)`). Medido: un WAV de 247.038 bytes
a 44.1 kHz mono 16 bits da 2.800 ms correctos, mientras que la cabecera habria
dado unas 48.000.000 ms.

---

## 4. Como configurar ElevenLabs

```
ELEVENLABS_API_KEY=...
ELEVENLABS_MODEL=eleven_multilingual_v2
ELEVENLABS_DEFAULT_VOICE=<voice-id>
ELEVENLABS_BASE_URL=https://api.elevenlabs.io   # opcional
```

Contrato: `POST {base}/v1/text-to-speech/{voice_id}?output_format=...`,
cabecera `xi-api-key`, cuerpo `{text, model_id, voice_settings{speed}}`.

**Apagado por defecto.** Solo se activa con `TTS_PROVIDER=elevenlabs` o
anadiendolo a `TTS_FALLBACK_PROVIDERS`. Si no hay `ELEVENLABS_API_KEY`, el
proveedor se omite limpiamente.

---

## 5. Como cambiar de proveedor

- Cambiar el primario: `TTS_PROVIDER=elevenlabs` (nada mas; no se toca codigo ni
  el Content Generator).
- Activar fallback: `TTS_FALLBACK_PROVIDERS=elevenlabs`.
- Anadir un proveedor nuevo (OpenAI, Google, Azure, local): implementar
  `TtsProvider` y anadirlo al `when` de `TtsAssembly`. El resto del sistema no
  cambia (brief sec. 17).

---

## 6. Como funciona el fallback

- Se recorre la cadena: primario, luego cada fallback.
- **Solo se hace fallback con errores transitorios**: timeout, HTTP 408, HTTP
  429, HTTP 5xx y fallos de conexion (`TtsFailureKind.isTransient = true`).
- Reintentos por proveedor con backoff exponencial (500 ms, 1 s, 2 s..., tope
  8 s) y respeto de `Retry-After` en 429. `TTS_MAX_RETRIES` controla los
  reintentos (total de intentos = retries + 1).
- **Nunca hay fallback con errores permanentes**: texto invalido, idioma no
  soportado, voz/modelo inexistente, formato no soportado, 400/422 y 401/403.
  Se devuelve FAILED con el motivo y no se enmascara.
- El resultado registra `provider`, `metadata.fallback` y `metadata.attempts`.

---

## 7. Como funciona el cache

- Clave determinista SHA-256 de
  `text | language | provider | model | voice | speed | format | providerOptions`
  (`TtsFingerprint.VERSION = v2`).
- **La velocidad forma parte de la clave**: `normal` != `slow` aunque el texto
  sea el mismo (brief sec. 6). Tambien cambian la clave el proveedor, el modelo,
  la voz, el formato y **cualquier `providerOptions`** (emocion, estabilidad,
  etc.), que se serializan ordenados por clave para que el hash no dependa del
  orden del mapa. Un ajuste del proveedor que cambia el audio nunca reutiliza un
  clip ajeno.
- `object_key = <prefijo>/<hash>.<ext>` (`tts/<hash>.mp3` por defecto).
- Flujo: calcular hash -> mirar metadata en PostgreSQL -> comprobar el objeto en
  R2 -> si existe, reutilizar (coste 0) -> si no, generar -> subir -> registrar.
- Si el objeto existe pero falta la metadata (el worker murio despues de subir y
  antes de marcar), la siguiente ejecucion **recupera el asset** desde el
  object store y reinserta la metadata: no regenera ni paga dos veces.
- La cache se comprueba para toda la cadena de proveedores antes de generar, de
  modo que un asset generado por el fallback se reutiliza sin volver a intentar
  el primario.

---

## 7.1. Como se elige la voz (paleta pequena, determinista)

Principio del dueno: "no generes voces a saco; pon voces donde tenga sentido, de
diferentes personas donde tenga sentido". Traducido a esta capa:

- **No** hay una voz por item ni asignacion aleatoria. `VoicePalette` es una
  paleta pequena: una voz por defecto + una lista corta ordenada para dialogos.
- **Prioridad de resolucion** (`TtsProviderRouter`): voz explicita del request >
  voz de la paleta para el hablante (`speakerIndex`) > voz por defecto de la
  paleta (`TTS_VOICE_DEFAULT`) > voz por defecto del proveedor
  (`CARTESIA_DEFAULT_VOICE`).
- **Vocabulario y drills**: una sola voz (la por defecto).
- **Dialogos**: el contenido pide `speakerIndex` por turno
  (`TtsRequest.asSpeaker(i)`). La paleta devuelve voces distintas para
  hablantes distintos (`TTS_DIALOGUE_VOICES`), asi un dialogo suena a personas
  diferentes. Si hay mas hablantes que voces, cicla de forma determinista.
- **Voz concreta cuando tenga sentido**: el contenido puede fijar
  `TtsRequest.voice`, que gana siempre.
- **Estable**: el mismo contenido resuelve siempre la misma voz. Regenerar no
  cambia la voz.
- **`normal` y `slow` comparten voz**: ambas variantes se construyen del mismo
  request base, solo cambia la velocidad.
- La voz forma parte del **hash de cache**, asi que **cambiar de voz invalida el
  audio cacheado de ese texto** (correcto: es otro audio).

Configuracion (ver `deploy/.env.example`): `TTS_VOICE_DEFAULT`,
`TTS_DIALOGUE_VOICES` y `CARTESIA_DEFAULT_VOICE`. Los ids de voz son publicos,
no secretos. Los 4 ids GB aportados por el dueno (2 masculinos, 2 femeninos)
estan documentados en `.env.example`; la paleta de dialogo por defecto usa uno
masculino y uno femenino.

---

## 8. Idempotencia

- `glosso.tts_assets.cache_key` es `unique`; `insertIfAbsent` usa
  `insert ... on conflict (cache_key) do nothing`. Reejecutar un job no duplica
  filas.
- Subir el mismo objeto dos veces sobreescribe el mismo contenido (misma clave,
  mismos bytes), asi que no hay assets huerfanos.
- Recuperacion tras muerte del worker: cubierta por la comprobacion de objeto
  sin metadata descrita en la sec. 7 (test
  `re-running a job after upload but before marking recovers the asset`).

---

## 9. Validacion de audio y coste

**Validacion** (`AudioValidator`): no vacio, tamano minimo, no es un cuerpo de
error JSON/HTML, el contenedor coincide con el magic (MP3/OGG/WAV), duracion
declarada o estimada no negativa ni absurda, y checksum SHA-256. Si falla, el
job es **FAILED** con el motivo, no se sube a R2 y no se registra coste.

**Coste** (`TtsPricing`): `estimated_cost = characters / 1_000_000 *
TTS_COST_PER_1M_CHARS_<PROVIDER>` (acepta tambien clave `provider:model`). Sin
tarifa configurada estima 0 pero **siempre guarda los caracteres**, asi que el
coste real se puede recalcular despues. Se registra un evento append-only por
generacion en `glosso.tts_costs` (job_id, provider, model, chars, credits,
estimated_cost) y el coste en el propio asset. Los cache hits no registran
coste. Consultas tipo "cuanto cuesta un curso A1" son `sum()` sobre
`glosso.tts_costs`.

---

## 10. Observabilidad y privacidad

- Cada paso emite un `TtsEvent` correlacionado por `jobId`:
  `job_start`, `cache_hit`, `cache_recovered`, `provider_retry`,
  `provider_fallback`, `published`, `failed`.
- `LoggingTtsObservability` redacta cualquier cosa con pinta de clave
  (`sk_...`, `Bearer ...`, `xi-api-key`, `api_key`). **Nunca** se loguean claves
  ni tokens.
- **Privacidad:** a Cartesia y ElevenLabs solo va texto generado por Glosso.
  El audio del alumno no sale nunca del dispositivo (brief sec. 16). `:engine`
  y `/v1/score` no se tocan.
- **PostgreSQL solo guarda metadata** (`tts_assets`, `tts_costs`). Los audios
  van al object store.

---

## 11. Punto de conexion del worker (fase 2 conectada)

La firma que usa la cola ya existe y esta cableada:

```kotlin
val runtime = TtsAssembly.fromEnv(dataSource = controlPlaneDataSource) // o null si no hay proveedor
val results = runtime.processor.processPair(
    TtsJob(jobId = job.id, request = requestForVariant(...), contentId = variant.contentId, variantId = variant.id),
)
// el worker escribe luego:
//   content_variants.audio_key / slow_audio_key = result.asset?.objectKey
//   generation_jobs.status = DONE/FAILED/DEAD, error = result.errorMessage
```

El worker de la fase 2 (`com.glosso.server.generation.TtsWorker`) consume
`glosso:gen:tts` (Redis Streams, consumer group `workers-tts`) y llama a
`TtsJobProcessor`. Detalles en `docs/CONTENT_POOL.md`.

**Fase 1 ya mergeada.** El PR #61 (`feat/need-engine`, fase 1) esta en `main`, y
esta capa comparte su PostgreSQL y su esquema `glosso`. El `SchemaMigrator` de
`com.glosso.server.generation` **descubre** las migraciones `/db/migration/V*__*.sql`
ordenadas por version, asi que `V2__tts_assets.sql` se aplica solo, sin registrar
nada a mano. La ruta paralela `JdbcTtsAssetStore.ensureSchema()` se mantiene para
uso aislado (es idempotente y aplica el mismo DDL).

---

## 12. Tests ejecutados

```
./gradlew :server:test --tests "com.glosso.server.tts.*"
./gradlew :engine:jvmTest :server:test                     # todo verde
```

Cubren: fingerprint (normal != slow, todos los parametros, `providerOptions`
distinto cambia la clave y el orden del mapa no la cambia), validacion de audio
(vacio, pequeno, corrupto, formato, duracion, checksum), Cartesia (request
documentado, 429/5xx/408 transitorios, 400 de idioma permanente, 401, texto
invalido, idioma no soportado, formato OGG, key ausente), ElevenLabs (request,
clamp de velocidad, 401, 500, formato), router (retries, backoff, Retry-After,
sin fallback en permanente, fallback en transitorio, `IOException` transitoria,
excepcion inesperada envuelta y marcada con el proveedor, proveedor que genero,
resolucion de voz por prioridad), voces (`VoicePalette` y config), config
(`TtsConfig.fromEnv`), redaccion de secretos, clasificacion
transitorio/permanente, y servicio (cache miss/hit, idempotencia, normal vs
slow, `providerOptions` produce un asset distinto, audio invalido no publica,
fallback cacheado, job fallido registra el proveedor, coste solo por generacion).

`SchemaMigratorPostgresTest` (paquete `generation`) arranca un PostgreSQL real
embebido (zonky), migra una base vacia y comprueba que `tts_assets` y `tts_costs`
existen; `SchemaMigrationTest` comprueba ademas que el runner descubre la V2 sin
lista hardcoded.

**Test opcional contra la API real:** `CartesiaLiveTest` no corre por defecto;
retorna sin hacer nada salvo que existan `CARTESIA_API_KEY` y
`CARTESIA_DEFAULT_VOICE` en el entorno.

---

## 13. Problemas pendientes (honesto)

1. **Fase 3 no existe.** La fase 2 (Content Pool -> Content Gap -> Generation
   Queue) esta en `docs/CONTENT_POOL.md`. La generacion de **texto** (LLM,
   validacion de contenido, worker text) sigue pendiente: la fase 2 usa una
   fuente de candidatos determinista (plantillas), no un modelo.
2. **Migracion registrada.** El PR #61 esta mergeado y su `SchemaMigrator`
   descubre `V2__tts_assets.sql` por convencion de nombre; un test con
   PostgreSQL real (`SchemaMigratorPostgresTest`) parte de una base vacia y
   comprueba que `glosso.tts_assets` y `glosso.tts_costs` existen tras migrar.
3. **R2 real pendiente de credenciales.** El cliente S3-compatible esta
   implementado y configurable por entorno, pero **no se ha subido nada a R2 de
   verdad** porque no hay credenciales. Los tests usan in-memory y ficheros.
4. **Validacion manual contra Cartesia resuelta.** El 401 de `/tts/bytes` no era
   una key con permisos limitados: el endpoint de sintesis espera un **access
   token**, no la API key (ver sec. 3). El provider canjea la key en
   `/access-token`, cachea el JWT y reintenta una vez tras un 401. Verificado con
   llamadas reales (`CartesiaLiveTest` con `CARTESIA_API_KEY` y
   `CARTESIA_DEFAULT_VOICE`, resultado 200 y audio real).
5. **Politica de voces y formato.** El brief usa MP3 (`tts/{hash}.mp3`); el
   pipeline offline existente usa OGG 24 kHz. La capa runtime queda en MP3 por
   defecto y configurable. Unificar el formato con la app (que hoy reproduce OGG)
   es una decision de producto pendiente.
6. **Sin endpoint HTTP.** No se expone ruta publica de TTS a proposito: la
   generacion es capacidad de fondo. Si se quiere probar a mano antes de la fase
   2, se puede invocar `TtsAssembly.fromEnv()` + `processor.process(...)` desde
   un test o un one-shot.
7. **`deploy/docker-compose.yml` y `.env.example`**: se creo `deploy/.env.example`;
   el compose sigue con dos servicios (API + Caddy) porque el stack completo
   (Postgres/Redis/worker) es de la infraestructura de las fases 2/3.
8. **Asignacion de voces al contenido.** Esta capa aporta el mecanismo
   (paleta + `speakerIndex` + voz explicita) y su configuracion, pero **quien
   decide que contenido es dialogo y con cuantos hablantes es la fase 3**
   (generador de contenido), que aun no existe. La politica de producto
   recomendada esta en la sec. 7.1.
