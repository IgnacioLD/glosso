# Multi-acento

Estado: **implementado detras de flag, apagado** (`GLOSSO_MULTI_ACCENT`). Las
fonologias son semillas sin calibrar (`calibrated = false`) hasta medir cada
acento con su propio audio nativo. Ver [Estado](#estado-2026-09-26).

## Problema

El scoring de ingles se cierra a una referencia: la lectura primaria de CMUdict
(americano general, rotico) mas lecturas por palabra y una lectura no-rotica de
frase entera. Un hablante con otro acento legitimo (un tejano, un irlandes) paga
como error lo que es su acento. Y el ensanchado por palabra permite mezclar: una
palabra en una lectura, la siguiente en otra.

Lo que se quiere: comparar contra **varios sistemas de acento** y quedarse con
el que mejor encaja, **siempre entero**. Nunca mitad de un acento y mitad de otro.

## Diseno

### Sistema de acento

Codigo: `engine/src/commonMain/kotlin/com/glosso/engine/accent/`.

- `AccentPhonology`: un acento como **reescritura coherente** de la referencia
  canonica (CMUdict, rotica). `rhotic` y una lista ordenada de `AccentRule`
  (regex -> realizacion). Cada regla se aplica a **todas** las palabras de la
  frase. Un acento no-rotico tiene dos variantes libres internas (NEAR como `ɪə`
  o `iə`), cada una tambien de frase entera.
- `ReferenceVoice`: voz del proveedor del dueno (UUID -> acento, genero).
- `AccentSystem`: acento + genero + fonologia + sus voces de referencia. Id
  `"<acento>-<genero>"` (`en-US-South-female`), la misma convencion de las claves
  de audio del contenido (`en-GB-female`). Solo existe un sistema si hay al menos
  una voz para ese par.
- `AccentCatalog`: los sistemas de ingles con las voces de
  `design-reference/voces-acentos.txt` mas las de la seleccion de acentos del dueno
  (en-GB conserva ademas las voces Kokoro que ya se publican).

| Acento | Rotico | Reglas semilla | Voces |
| --- | --- | --- | --- |
| en-GB | no | (solo no-rotico) | Kokoro 2f + 1m, proveedor 2f + 2m |
| en-US | si | ninguna (CMUdict ya es americano general) | 2f + 3m |
| en-US-CA | si | `cot-caught` (ɔ -> ɑ) | 2f |
| en-US-South | si | `price-monophthong` (aɪ -> aː), `pin-pen` (ɛ+nasal -> ɪ) | 2f + 2m |
| en-US-NY | no | `thought-raising` (ɔ -> ʊə) | 3m |
| en-IE | si | `th-stopping` (θ -> t), `dh-stopping` (ð -> d), `face-monophthong` (eɪ -> eː), `goat-monophthong` (oʊ -> oː) | 1f + 2m |
| en-AU | no | `face-wide` (eɪ -> æɪ), `price-back` (aɪ -> ɑɪ), `goat-front` (oʊ -> əʉ) | 2f + 3m |
| midwestern-american | si | `cot-caught` (ɔ -> ɑ), `bag-raising` (æ -> eə ante velar) | 1f + 2m |
| canadian-english | si | `canadian-raising-price` (aɪ -> əɪ ante sorda), `canadian-raising-mouth` (aʊ -> ʌʊ ante sorda) | 1f + 1m |
| new-zealand | no | `nz-dress-raising` (ɛ -> ɪ) | 2f |

Los tres acentos nuevos (`midwestern-american`, `canadian-english`, `new-zealand`)
mas las voces que faltaban por genero (`en-US-South-male`, `en-US`/`en-US-NY`/`en-IE`/
`en-AU` masculinas) entran aqui para que **todas** las voces registradas en
`/v1/admin/voices` tengan sistema. El acento `british` de la seleccion del dueno es
el `en-GB` del catalogo: sus voces se registran con `en-GB` y no con `british`.

Las reglas solo usan simbolos que el vocabulario del modelo puede emitir
(`aː`, `eː`, `oː`, `ʊə`, `ʉ` estan en `phoneme_vocab.json`). Son descripciones
estandar de cada acento, **no** medidas: por eso `calibrated = false`.

### Scoring por sistema entero

`MultiAccentScorer.score(...)`:

1. Para cada sistema, su referencia es su fonologia aplicada a la frase canonica
   entera. Si llega una referencia propia del acento (`accentReferences`, p. ej.
   el clip de esa voz fonemizado), se puntua **ademas** y se reporta en empate.
   La clave por sistema (`en-IE-male`) gana a la de acento (`en-IE`).
2. Dentro de un sistema se **apaga** el ensanchado legado (la lectura no-rotica
   de frase: la rotacidad la decide el sistema). Las alternativas lexicas del
   diccionario (CMUdict lista "thirty" de dos formas) se aceptan, pero
   realizadas en el acento del mismo sistema. La elision de palabras funcionales
   (neutral al acento) se mantiene.
3. Gana el **mejor sistema completo**. Desempates, en orden: nota, menos sonidos
   MISSED, el acento del modo, orden del catalogo.
4. Se reporta en `PronunciationFeedback.accentMatch`: sistema elegido, su nota,
   el modo, `calibrated` y la nota de cada sistema.

Si una toma mezcla acentos (PRICE del sur y TH irlandes, `r` en una palabra y no
en la siguiente), ningun sistema entero encaja y la nota lo refleja. Los tests
`MultiAccentScorerTest` lo fijan, y comparan con la union por palabra (que si lo
regalaria).

Sistemas del mismo acento sin referencia propia puntuan igual (misma fonologia):
se calcula una vez.

### El modo

El acento que el alumno quiere dominar (`ScoreRequest.accent`) manda en
contenido y objetivo, **no** en discriminar: solo rompe empates exactos. Una
toma irlandesa con modo en-US sale en-IE con su nota, no penalizada. El modo es
un acento, no un genero: los dos generos de un acento comparten fonologia,
puntuan igual y el orden del catalogo decide cual se reporta.

### Flag y API

- `GLOSSO_MULTI_ACCENT=1` activa el scoring multi-acento en `/v1/score` (solo
  ingles). Apagado, `/v1/score` es exactamente el de antes y `accentMatch` es
  `null`.
- `ScoreRequest.accent`: el modo. `ScoreRequest.accentIpa`: referencias por
  sistema o acento (max 32). `expectedIpa` sigue siendo la lectura canonica
  CMUdict que publica el contenido: cada sistema la realiza en su acento.
  **Confianza:** `accentIpa` lo manda el cliente y se puntua como referencia
  aceptada, igual que `expectedIpa` (que ya venia del cliente): no es un hueco
  nuevo, pero quien tenga el control del cliente puede enviar su propia
  transcripcion como referencia y sacar 100. No se valida contra un mapeo
  conocido por el servidor; si eso importa, la referencia de cada voz debe
  publicarse con el contenido o restringirse a un mapeo de voz del servidor.
- `GET /v1/accents` (publico): sistemas, reglas, si estan calibrados y si el
  flag esta activo.
- `GET /v1/admin/voices` y `POST /v1/admin/voices` (admin): voces del catalogo
  con su estado de registro, e ingesta de una voz.

La app no cambia: el campo nuevo es opcional y el flag esta apagado. Mostrar el
acento elegido es trabajo de app cuando el flag se encienda.

## Subir una voz

`POST /v1/admin/voices` con `Authorization: Bearer <GLOSSO_ADMIN_TOKEN>`:

```json
{ "voiceId": "d1d9c946-7cfc-4378-85a4-07d09827cb7e", "accent": "en-US-South", "gender": "female",
  "label": "opcional", "audioBase64": "opcional" }
```

- `voiceId` debe ser un UUID; `accent`, uno del catalogo; `gender`, female/male.
- Si la voz esta en el catalogo, acento y genero deben coincidir (409 si no): el
  catalogo revisado es la fuente de verdad. Una voz fuera del catalogo se guarda
  pero no entra en ningun sistema hasta anadirla a `AccentCatalog`.
- Audio: inline (`audioBase64`, max 5 MB) o, si no viene, lo pide el fetcher.
  Se valida el contenedor por bytes magicos (WAV, OGG, MP3, FLAC) y el tamano.
- Se guarda en `<assets>/.voices/` (o `GLOSSO_VOICES_DIR`): `registry.json` +
  `<uuid>.<ext>`, escrito atomico. `/assets` no sirve rutas ocultas, asi que el
  audio del proveedor no se publica.
- En produccion `/assets` se monta **de solo lectura**, asi que el stack le da a
  las voces su **propio volumen escribible y persistente**: el compose fija
  `GLOSSO_VOICES_DIR=/voices` y lo respalda con el directorio del host
  `${GLOSSO_VOICES_HOST_DIR:-/srv/glosso/voices}` (ver `deploy/docker-compose.yml`
  y `deploy/.env.example`). Cualquier ruta que se use debe ser escribible por el
  uid del contenedor (`10001:10001`); `tools/infra/deploy.sh` crea el directorio
  por defecto con ese dueno. Para **cambiarlo**, apunta `GLOSSO_VOICES_HOST_DIR`
  a otra ruta en `deploy/.env` y vuelve a desplegar; el contenido no se mueve
  solo, hay que copiar `registry.json` y los audios al directorio nuevo. El
  runbook de permisos del bind mount esta en `docs/SERVIDOR-ESCRITURA-MINIMA.md`.

Ejemplo sin poner el token en la linea de comandos (regla de secretos):

```sh
tools/keys/glosso-secret run GLOSSO_ADMIN_TOKEN -- sh -c '
  printf "header = \"Authorization: Bearer %s\"\n" "$GLOSSO_ADMIN_TOKEN" |
  curl -sS -K - -H "Content-Type: application/json" \
    -d "{\"voiceId\":\"d1d9c946-7cfc-4378-85a4-07d09827cb7e\",\"accent\":\"en-US-South\",\"gender\":\"female\"}" \
    https://<host>/v1/admin/voices'
```

### Modo fichero local (para probar ya)

```sh
GLOSSO_VOICE_FETCHER=local GLOSSO_VOICE_LOCAL_DIR=/ruta/voces ./gradlew :server:run
```

Busca `/ruta/voces/<uuid>.wav|ogg|mp3|flac`.

### El enchufe del proveedor (pendiente)

Aun **no** sabemos la API del proveedor de voces del dueno, asi que no hay
fetcher de red. Observacion sin confirmar: los ids tienen el formato UUID de las
voces de Cartesia, que el servidor ya usa como TTS (`CartesiaTtsProvider`,
`docs/TTS-CARTESIA-EN.md`); si el dueno lo confirma, el fetcher natural es
renderizar una frase con esa voz. No se ha cableado nada de eso. Para enchufarlo:

1. Implementar `VoiceAudioFetcher` (`server/.../accent/VoiceAudioFetcher.kt`):
   `name` y `suspend fun fetch(voiceId): FetchedVoiceAudio`. La clave se lee del
   entorno (via `glosso-secret run`), nunca se registra en log.
2. Anadir su rama en `VoiceAudioFetchers.fromEnv` y darle nombre en
   `GLOSSO_VOICE_FETCHER`.
3. Nada mas cambia: validacion, registro, API y catalogo ya son independientes
   del proveedor. Un valor desconocido de `GLOSSO_VOICE_FETCHER` deja la ingesta
   en modo "sin fetcher" (503 con motivo claro), nunca adivina un proveedor.

## Anadir un acento

1. Fonologia en `AccentCatalog`: `AccentPhonology(accent, rhotic, rules)`, con
   reglas que el vocabulario del modelo pueda emitir. Entrada en `accents` con su
   etiqueta (el orden es el ultimo desempate).
2. Sus voces en `PROVIDER_VOICES` (UUID, acento, genero, etiqueta).
3. Tests: su realizacion en `MultiAccentScorerTest` (el test de "nativo de cada
   acento saca 100 contra su sistema" lo cubre solo) y el recuento de sistemas.
4. Medir con audio nativo (abajo) y, cuando pase, `calibrated = true`.

Un genero de un acento sin voz no es sistema: basta con anadir la voz.

## Como se mide

Una metrica, un comando: **nativo por acento**, cuantos clips nativos de cada
acento llegan a 98 contra **su propio** sistema. Mismo conjunto de ids que el
bench del engine (`tools/engine-bench/native-ids.txt`), un conjunto por voz:

```sh
tools/multi-accent-bench/run.sh                               # report-only
GLOSSO_ACCENT_VOICES="en-US-South-female en-IE-male" tools/multi-accent-bench/run.sh
GLOSSO_ACCENT_MIN_RATE=0.9 tools/multi-accent-bench/run.sh   # muro
```

`make_samples.py --voice <clave>` construye cada conjunto; si la voz no esta en
el mapa de audio pero el fichero existe con la misma convencion de nombre (los
clips en-US), lo usa.

**Sin `GLOSSO_ACCENT_MIN_RATE` el bench no imprime `PASS`**, solo `OK`
(report-only): no es evidencia para encender el flag. Un conjunto con **0 clips**
(falta esa voz en `content.db`, o ninguno de sus clips esta en disco) **falla
siempre**, con o sin muro: un manifiesto vacio imprimia `clips=0 own_rate=0.000`
y un `PASS` indistinguible de evidencia real, y esa es justo la metrica que
gobierna el flag. El mismo corte lo hace `MultiAccentNativeTest`: un conjunto sin
ningun clip puntuado (vacio o con todos rechazados) es un fallo.

`MultiAccentNativeTest` imprime por conjunto:

```
ACCENT98|set=<voz>|accent=<acento>|clips|rejected|own98|best98|legacy98|own_rate|chosen|calibrated
```

`own98` es la metrica; `best98` es la nota que daria el flag; `legacy98` es la
de produccion hoy (no comparable como objetivo: el legado mezcla lecturas).

## Estado (2026-09-26)

Medido en local el 2026-09-26 con el modelo int8 selectivo
(`wav2vec2_phoneme_int8_dyn_pertensor.onnx`), `glosso-data` actual y
`tools/engine-bench/native-ids.txt`. Comando:

```sh
GLOSSO_MODEL=.../wav2vec2_phoneme_int8_dyn_pertensor.onnx GLOSSO_VOCAB=.../phoneme_vocab.json \
GLOSSO_CMUDICT=.../cmudict.dict GLOSSO_DATA=.../glosso-data tools/multi-accent-bench/run.sh
```

| Conjunto | Clips | own98 (su sistema) | best98 (flag) | legacy98 (hoy) | Sistema elegido |
| --- | ---: | ---: | ---: | ---: | --- |
| en-GB-female | 42 | 30 (71%) | 30 | 29 | en-GB 42 |
| en-GB-male | 38 | 29 (76%) | 29 | 28 | en-GB 38 |
| en-US-female | 30 | 6 (20%) | 14 | 13 | en-US 18, en-GB 11, en-US-South 1 |
| en-US-male | 23 | 7 (30%) | 14 | 13 | en-US 13, en-GB 10 |

Lectura:

- **en-GB**: cada clip britanico elige en-GB y su propio sistema no empeora al
  legado (30 vs 29, 29 vs 28). Sigue lejos del muro (90%): es el mismo techo del
  bench del engine, no algo del multi-acento.
- **en-US no es evidencia nativa valida todavia.** Los clips `*.en-US-*.ogg` de
  `glosso-data` son restos sin verificar de pases anteriores (Cartesia +
  Kokoro/Chatterbox, ver `docs/TTS-CARTESIA-EN.md`), y el reconocedor oye rasgos
  britanicos en ellos (`nəʊ`, `wɔːm`, `tʃiə`, `ɒn`): por eso eligen en-GB en 21
  de 53. El numero en-US real sale con las voces en-US del dueno.
- **en-US-CA, en-US-South, en-US-NY, en-IE, en-AU: sin numero.** No hay audio
  de esas voces en el contenido. El comando esta preparado
  (`GLOSSO_ACCENT_VOICES=...`).

Guardia de separacion (el mismo probe fijo de 810 filas del bench del engine):

```sh
GLOSSO_CMUDICT=.../cmudict.dict ./gradlew :engine:jvmTest \
  --tests 'com.glosso.engine.accent.MultiAccentAucProbeTest' -i
```

| Puntuacion | AUC nativo > no nativo |
| --- | ---: |
| `current` (produccion hoy) | 0.4718 |
| `multi` (todos los sistemas) | 0.4746 |
| `multi_gb_us` (solo en-GB + en-US) | 0.4720 |

Los sistemas extra no reducen la separacion en este conjunto: no nativos eligen
en-IE en 21/594 y nativos en 16/216. El probe es Common Voice con IPA -> IPA y
su AUC ya esta por debajo de 0.5 hoy; es guardia, no objetivo.

**Recomendacion:** mantener el flag apagado hasta tener el audio de las voces
del dueno, medir `own98` por acento y calibrar. Encenderlo solo con cada acento
ofrecido por encima del muro y `multi >= current` en la guardia
(`GLOSSO_MULTI_ACCENT_AUC_STRICT=1`).

### Auto-consistencia sintetica (2026-09-26)

Antes de tener audio humano, se midio la **auto-consistencia sintetica**: las 30
voces registradas, re-sintetizadas con Cartesia sobre los 45 ids de
`native-ids.txt` y puntuadas contra su propio sistema. No es el `own98` nativo
(TTS, no nativos), pero dice si la fonologia de un acento encaja con su voz.

Resultado completo en `tools/multi-accent-bench/RESULTS-2026-09-26.md`. Resumen:
solo **en-GB** sale consistente (0.689, su sistema gana en 179/180); los otros 9
puntuan menos contra su propio acento que contra en-GB (peor en-IE 0.193,
en-US-South 0.300, en-AU 0.344), porque las reglas semilla (th-stopping,
monoptongos, desplazamientos) reescriben la IPA a realizaciones que la voz no
produce. `multi >= current` en el guard AUC. Reproducir con
`GLOSSO_ACCENT_SYNTH=1 tools/keys/glosso-secret run CARTESIA_API_KEY -- tools/multi-accent-bench/run.sh`.

Aviso de metodo: el arnes manda el payload de Cartesia del server
(`voice: {"id": ...}`). El cliente compartido `tools/content/cartesia_a1_audio.py`
manda `{"mode":"id",...}`, que en 2026-09 renderiza la misma voz distinto (mas
lenta y mal reconocida en voces masculinas). Ver el informe.

### Referencia en la convencion del modelo (2026-10-01)

Hallazgo: **el reconocedor transcribe cualquier acento ingles en la convencion
de espeak en-GB** (no rotico, `əʊ`, `a`, `ɒ`), que es con la que se afino. Una
voz americana de Cartesia sale `nəʊ ðats ɔːl`, `kɔːs`, `nʌmbə`, igual que una
britanica. Por eso el audio de referencia sacaba ~89 contra la lectura de
CMUdict (americana, rotica): no era el acento de la voz, era la convencion de la
referencia. Y por eso un sistema por acento con reglas no puede funcionar con
este modelo: no distingue la roticidad.

Medido con el modelo int8 selectivo sobre `tools/engine-bench/native-ids.txt`,
pasando por clip la frase fonemizada con espeak (`accentIpa`, la misma via que
`ScoreRequest.accentIpa` en `/v1/score` con el flag encendido):

```sh
python3 tools/reference-selfscore/make_samples.py --db $DATA/content/en/content.db \
    --audio-root $DATA/content/en/audio --ids-file tools/engine-bench/native-ids.txt \
    --voice en-GB-female --out $PWD/build/accent-ref/en-GB-female      # y el resto de voces
uv run --python 3.11 --with espeakng-loader --with phonemizer-fork \
    python tools/multi-accent-bench/add_accent_ipa.py build/accent-ref/*/manifest.json
GLOSSO_ACCENT_MANIFESTS="en-GB-female=$PWD/build/accent-ref/en-GB-female/manifest.json;..." \
GLOSSO_MODEL=.../wav2vec2_phoneme_int8_dyn_pertensor.onnx GLOSSO_VOCAB=... GLOSSO_CMUDICT=... \
    ./gradlew :engine:jvmTest --tests 'com.glosso.engine.accent.MultiAccentNativeTest' --rerun-tasks -i
```

| Conjunto (voces Cartesia) | Clips | Hoy (`legacy98`) | Mejor sistema con referencia espeak (`best98`) | Contra la referencia de su propio acento (`own98`) |
| --- | ---: | ---: | ---: | ---: |
| en-GB-female | 42 | 29 (69%) | 39 (93%) | 39 (93%) |
| en-GB-male | 38 | 28 (74%) | 36 (95%) | 36 (95%) |
| en-US-female | 37 | 28 (76%) | 36 (97%) | 19 (51%) |
| en-US-male | 34 | 26 (76%) | 33 (97%) | 18 (53%) |

Lectura:

- Con la referencia en la convencion del modelo, **144 de 151 clips nativos
  llegan a 98 (95%)**, frente a 111 de 151 (74%) hoy. Es la primera vez que la
  metrica que manda supera el muro del 90%.
- Las voces americanas eligen el sistema en-GB en la mitad de los clips y su
  `own98` contra la referencia espeak en-US es 52%: el modelo no oye la `r`. La
  referencia que sirve para todos los acentos es la en-GB de espeak.
- Los 7 que no llegan son errores reales del modelo (`very` -> `bɛɹi`,
  `course` -> `ɔːs`, `niece` -> `nɛs`): contrastes que no se pueden perdonar sin
  perdonarselos al alumno. Uno (`en.word.opinions.007`) es un desfase entre
  `content.db` y el JSON fuente, no del motor.
- **Guardia de separacion** (`EnGbReferenceAucProbeTest`, probe fijo de 810):
  aceptar la referencia britanica ademas de la americana da `gb_accept_both =
  0.4232` frente a `us_variants = 0.4718` de produccion. Baja. Las dos estan por
  debajo de 0.5: la nota no separa nativo de no nativo en ese probe con ninguna
  de las dos referencias. Es la decision de producto pendiente de ese muro.
- Consecuencia: `accentMatch` no dice que acento tiene el alumno (el modelo no lo
  distingue). No se debe mostrar como "suenas americano".

### Pendiente

- **Audio de las voces del dueno**: en cuanto el proveedor este enchufado (o con
  ficheros locales), renderizar los clips de `native-ids.txt` con cada voz bajo
  su clave (`en-US-South-female`, ...) y correr el bench con
  `GLOSSO_ACCENT_VOICES`. Hasta entonces esos acentos no tienen numero.
- **Calibrar** cada fonologia con ese audio (reglas que sobran o faltan) y marcar
  `calibrated = true` solo cuando su `own98` supere el muro.
- **Referencias por voz**: fonemizar el clip de cada voz (`/v1/phonemize`) y
  enviarlo en `accentIpa`, o publicarlo con el contenido.
- **App**: mostrar `accentMatch` cuando el flag se encienda.
- Limitacion conocida: las alternativas lexicas de CMUdict ("either",
  "tomato") no estan etiquetadas por acento; se aceptan en cualquier sistema,
  realizadas en su fonologia.
