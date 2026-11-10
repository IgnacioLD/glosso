# Glosso - Content Pool, Content Gap y Generation Queue (fase 2)

**Estado:** implementado y probado de punta a punta en local (PostgreSQL y Redis
embebidos). **Contrato:** `docs/CONTENT-GENERATION-BRIEF.md` manda;
`docs/CONTENT_GENERATION_ARCHITECTURE.md` fija nombres y esquema. Este documento
describe lo que anade la rama `feat/content-pool-queue`.

Cubre la fase 2 del brief: **Content Pool -> Content Gap -> Generation Queue**,
mas el cableado real del TTS de la fase 4 que #63 dejo listo
(`TtsJobProcessor`). La **generacion de texto (fase 3) NO entra aqui**.

---

## 1. Que llega hasta aqui y que no

```
need_classes (fase 1)                        user_needs -> Demand Aggregator
        |
        v
Content Gap  (min/target/max configurable)   GapCalculator + CompositeContentCatalog
        |
        v
GenerationService.enqueueGaps()              materializa candidatos + crea jobs
        |
        v
Redis Streams  glosso:gen:tts  (group workers-tts)
        |
        v
TtsWorker (un solo worker, stateless)  ->  TtsJobProcessor.processPair(job)
        |                                    (fase 4 de #63: router + cache + validacion)
        v
ObjectStore (R2/S3 o ficheros local)  +  glosso.content_variants.audio_key / slow_audio_key
        |
        v
Content ACTIVE en el pool  ->  la proxima agregacion lo cuenta y el gap se cierra
```

**Fuera de alcance (fase 3):** el generador de texto LLM, la validacion de
contenido (schema/CEFR/grammar/vocabulary/target/IPA/answer), el worker text y
la publicacion a `content.db`. La fase 2 usa una **fuente de candidatos
determinista** (`TemplateCandidateSource`, plantillas) que implementa el paso
"variacion parametrizada (plantillas)" del brief sin ningun modelo. La fase 3
sustituye esa fuente por el proveedor de texto; el resto del pipeline no cambia.

---

## 2. Esquema PostgreSQL (migracion V3)

`server/src/main/resources/db/migration/V3__content_pool.sql` (reversible en
`V3__content_pool.down.sql`). Se **descubre sola** por convencion de nombre
(`SchemaMigrator`), no hay listas a mano. Coexiste con V1 (fase 1) y V2 (TTS).

- **`glosso.content`**: la pieza logica. Campos del brief: `cefr`, `skill`,
  `objective`, `exercise_type`, `target_phoneme`, `error_pattern`, `position`,
  `difficulty`, `status`, `quality_score`, `created_by`, `provider`, `created_at`,
  mas `kind`, `replacement_phoneme`, `language` y `need_class_id` (el pool por
  Need Class). Estados: `DRAFT|GENERATING|VALIDATING|APPROVED|ACTIVE|DEPRECATED|REJECTED`.
- **`glosso.content_variants`**: las piezas servibles. Aqui viven los campos por
  pieza que el brief lista (`text`, `ipa`, `answer`, `audio_key`, `slow_audio_key`,
  `image_key`), mas `voice`/`provider`/`model`. `variant_hash` es `unique`: la
  unidad de idempotencia del pool. Separar pieza logica y variante es lo que hace
  el pool reutilizable (una pieza, N voces/regeneraciones).
- **`glosso.generation_jobs`**: el libro mayor de la cola. `job_hash` es `unique`
  (idempotencia), `status` es `QUEUED|RUNNING|DONE|FAILED|DEAD`, y
  `provider`/`error` registran **quien fallo**. No guarda binarios.
- **`glosso.v_gap`**: vista de admin (demanda vs pool efectivo). El scheduler NO
  la usa para decidir: usa la configuracion min/target/max.

**PostgreSQL guarda solo metadata.** El audio va al object store; la variante
solo guarda la clave del objeto.

---

## 3. Cola (Redis Streams)

Redis es **infraestructura de trabajo**, nunca la verdad. La verdad esta en
`generation_jobs` y `content_variants`: perder Redis solo significa reencolar.

| Clave | Tipo | Uso |
|---|---|---|
| `glosso:gen:tts` | Stream | Una entrada por variante a sintetizar (`jobId`, `variantId`, `contentId`). |
| `workers-tts` | Consumer group | Lo consume el unico worker. |
| `glosso:gen:retry` | ZSET | Reintentos, `score = ejecutar_en_ms`. |
| `glosso:gen:dlq` | Stream | Jobs que agotaron `max_attempts`. |
| `glosso:job:<hash>` | String `SET NX EX` | Lock de idempotencia del enqueue. |

El stream se recorta (`MAXLEN ~ 10000`) porque su contenido es transitorio.

La implementacion vive en `RedisStreamGenerationQueue` (Jedis). El worker
promueve los reintentos vencidos al principio de cada corrida. La interfaz
`GenerationQueue` tiene una implementacion `InMemoryGenerationQueue` con las
mismas semantica para tests.

---

## 4. Idempotencia (el mismo trabajo dos veces no duplica nada)

Cuatro capas, de arriba abajo:

1. **Pool**: `content_variants.variant_hash` es `unique`. `variant_hash =
   sha256(schema_version | text | language | kind | target | pattern | position |
   voice | candidate_source)`. El scheduler nunca crea dos variantes para el mismo
   texto.
2. **Job**: `generation_jobs.job_hash` es `unique`; `GenerationService` no
   reencola si la variante ya tiene audio o el job esta `DEAD`.
3. **Mensaje**: el lock `glosso:job:<hash>` (Redis `SET NX EX`) evita que dos
   ciclos empujen el mismo trabajo. Best-effort; PostgreSQL es la defensa final.
4. **Asset TTS**: el fingerprint `SHA256(text|language|provider|model|voice|
   speed|format|providerOptions)` de #63 y `tts_assets.cache_key unique` reutilizan
   el clip. Reescribir el mismo objeto es la misma clave y los mismos bytes.

Ademas el worker comprueba `variant.hasAudio` antes de sintetizar: un mensaje
redeliverado es un no-op. Un job terminado no se regenera.

---

## 5. Worker y errores

`TtsWorker` (uno solo; `runOnce` no deja que dos corridas drenen la misma cola a
la vez en un proceso):

- Consume `glosso:gen:tts`, carga la variante, llama a
  `TtsJobProcessor.processPair(job)`, escribe `audio_key`/`slow_audio_key` y
  marca la pieza `ACTIVE`.
- **No marca `ACTIVE` hasta que existen las dos variantes de audio validadas**:
  un job fallido no deja contenido a medias.
- **Registra el proveedor que fallo** en `generation_jobs.provider` y en `error`.
- **Reintenta** con backoff (`GLOSSO_GENERATION_RETRY_BACKOFF_MS` x intento) hasta
  `max_attempts`; despues lo manda a `glosso:gen:dlq` y el job queda `DEAD`.
- **Nunca lanza**: un fallo de Redis/PostgreSQL se registra y el mensaje queda
  sin ack (recuperable por pending entries), no tumba el worker. La recuperacion
  automatica de pending entries atascadas (`XAUTOCLAIM`) queda para mas adelante;
  hoy un mensaje asi se recupera reiniciando al worker, que lo reapunta desde el
  lock/estado de PostgreSQL.

`runOnce` es la entrada; en produccion un loop en el scope de generacion lo
invoca, y el scheduler lo comparte con el ciclo de enqueue.

---

## 6. Scheduler y estados

`GenerationScheduler` corre en su propio `CoroutineScope(Dispatchers.IO)`
(`generationScope`), espera un intervalo completo antes de la primera corrida
(`GLOSSO_SCHEDULE_HOURS`, default 6) y aisla cada ciclo. Un ciclo = agregar
demanda + `enqueueGaps()`. Cuando la generacion esta activa, este scheduler
sustituye al `DemandScheduler` de la fase 1 (hace lo mismo y ademas encola).

`GenerationService.enqueueGaps()` solo actua sobre clases `BELOW_MIN` y calcula
`want = min(target - effective, maximum - effective)`, asi **nunca se pasa de
maximum** ni se encola una clase cubierta. Si la fuente de candidatos no tiene
nada para una clase, no se encola (el gap queda abierto para la fase 3).

Estados de la pieza: `APPROVED` al materializar (candidatos deterministas),
`ACTIVE` cuando el audio esta. `DEPRECATED`/`REJECTED` son de fases posteriores.

---

## 7. Almacenamiento de objetos

El `ObjectStore` de la fase 4: `S3ObjectStore` (R2/S3 via `GLOSSO_R2_*`) o
`LocalFileObjectStore` (`GLOSSO_TTS_CACHE_DIR`) o in-memory (tests). El orden de
seleccion en `TtsAssembly`: R2/S3 -> ficheros local -> in-memory. **No se
necesitan credenciales de R2** para correr: los tests y un despliegue sin R2
usan ficheros local o memoria. Ver `docs/TTS-LAYER.md` sec. 9.

---

## 8. Configuracion (entorno)

| Variable | Default | Uso |
|---|---|---|
| `GLOSSO_INGEST_TOKEN` | (vacio) | Token de servicio de la ingesta (`POST /v1/profiles/sync`, cabecera `X-Ingest-Token`). **Fail closed:** sin el, la ingesta responde 401 y nadie puede inyectar demanda que dispare TTS. Lo pone el despliegue; nunca en git. |
| `GLOSSO_REDIS_URL` | (vacio) | Si falta, la generacion se desactiva limpiamente. |
| `GLOSSO_WORKER_ID` | `worker-1` | Nombre del consumidor en el grupo. |
| `GLOSSO_GENERATION_MAX_ATTEMPTS` | 3 | Intentos antes de `DEAD`. |
| `GLOSSO_GENERATION_RETRY_BACKOFF_MS` | 30000 | Backoff base del reintento. |
| `GLOSSO_GENERATION_MAX_MESSAGES` | 50 | Mensajes por corrida del worker. |
| `GLOSSO_GENERATION_MAX_CLASSES` | 50 | Clases por ciclo del scheduler. |
| `GLOSSO_GENERATION_WORKER_BLOCK_MS` | 1000 | Bloqueo del worker cuando la cola esta vacia. |
| `GLOSSO_SCHEDULE_HOURS` | 6 | Periodicidad del ciclo. |
| `GLOSSO_POOL_MIN|TARGET|MAX` | 20/50/100 | Tamano del pool. |

La generacion solo arranca si hay `GLOSSO_DB_URL`, `GLOSSO_REDIS_URL` y un
proveedor TTS con clave. Si falta cualquiera, la API sigue sirviendo el plano de
senal sin cambios.

---

## 9. Rutas de admin (token `GLOSSO_ADMIN_TOKEN`)

| Ruta | Efecto |
|---|---|
| `POST /v1/admin/generation/run` | Fuerza un ciclo (agrega + encola) y drena el worker una vez. |
| `GET /v1/admin/generation/status` | Profundidad de cola, pending y conteo de jobs por estado. |
| `GET /v1/admin/generation/jobs?status=&limit=` | Los jobs, con `provider` y `error`. |
| `GET /v1/admin/content-pool?language=&status=&limit=` | El pool generado, con texto y claves de audio. |

### 9.1 Ingesta autenticada y despliegue

`POST /v1/profiles/sync` es la unica via de entrada de demanda que puede terminar
en coste TTS. Se cierra con el token de servicio `GLOSSO_INGEST_TOKEN` (cabecera
`X-Ingest-Token`): sin token valido responde 401 y no se encola nada, asi que un
bucle anonimo no puede inflar la cola `glosso:gen:tts`. El token es unica y
exclusivamente de entorno (`deploy/.env`, permisos 600), **nunca** en git.

El despliegue al VPS lo hace el agente `deployall`. Este cambio de codigo **no**
toca produccion: hasta que el despliegue fije `GLOSSO_INGEST_TOKEN`, la ingesta
queda cerrada (401), que es el estado seguro por defecto.

---

## 10. Como se demuestra (test E2E real)

`GenerationQueueE2ETest` arranca **PostgreSQL embebido** y **Redis embebido** y
corre el flujo completo con el servicio real y un **proveedor TTS falso**
(lo unico que un test puede falsear):

```
perfil debil (θ) -> user_need -> need_class -> gap BELOW_MIN
  -> GenerationService.enqueueGaps() -> 3 jobs + 3 mensajes en glosso:gen:tts
  -> TtsWorker.runOnce() -> TtsJobProcessor -> AudioValidator -> ObjectStore
  -> Content ACTIVE (normal + slow)
  -> re-agregar: available_effective=3, gap=0, estado OK
  -> segundo ciclo + segundo drenado: 0 jobs nuevos, 0 mensajes, sin duplicados
```

Y el camino de fallo: un proveedor que falla siempre deja los 3 jobs `DEAD`
tras `max_attempts`, con `provider` y `error` grabados, y **ningun** contenido
`ACTIVE`. Tambien hay tests de la cola Redis real
(`RedisStreamGenerationQueueTest`) y de las rutas admin
(`GenerationAdminRouteTest`).

---

## 11. Que queda para la fase 3

1. **Text provider** (LLM/local) detras de `CandidateSource`: sustituir
   `TemplateCandidateSource` por la implementacion real, con banco curado y
   esquema de generacion por Need Class.
2. **Validacion de contenido** (schema, CEFR, grammar, vocabulary, target
   phoneme con G2P + `DrillValidator`, answer, length, duplicate) antes de
   `APPROVED`. La fase 2 solo valida el audio (reutiliza `AudioValidator`).
3. **Worker text** (`glosso:gen:text` -> `glosso:gen:validate` -> `glosso:gen:tts`),
   batching 20-50 por Need Class como pide el brief.
4. **Imagenes** (`glosso:gen:image`, FLUX, gate de licencia).
5. **Publicacion** a `content.db` + `manifest.json` (`PublishService`) sin romper
   `/v1/content/*`, y `ExerciseSelector`.
6. **Coste/eficacia**: `generation_costs`, `content_metrics`, `DEPRECATED` por
   baja mejora.

Nada de esto se toca aqui: la fase 2 deja el pool, la cola, el worker, el
scheduler y el TTS reales, listos para enchufarlos.
