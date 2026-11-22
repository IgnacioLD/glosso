# Anadir un idioma (sin tocar la logica)

Guia para que **otro agente** anada un idioma de punta a punta: contenido, IPA,
audio, imagenes, publicacion y verificacion. El scoring, el servidor y la app ya
son multi-idioma; anadir un idioma es **datos + un language pack declarativo**.

- [1. Como funciona](#1-como-funciona)
- [2. El language pack (unico bloque de "codigo")](#2-el-language-pack)
- [3. Contenido en glosso-data](#3-contenido-en-glosso-data)
- [4. G2P: IPA de referencia](#4-g2p)
- [5. Audio: TTS](#5-audio-tts)
- [6. Imagenes](#6-imagenes)
- [7. Publicar y sincronizar](#7-publicar-y-sincronizar)
- [8. Verificar](#8-verificar)
- [9. Estado por idioma objetivo](#9-estado-por-idioma-objetivo)
- [10. Benchmark de modelos por idioma](#10-benchmark-de-modelos-por-idioma)

---

## 1. Como funciona

Un `LanguagePack` (en `engine/.../model/LanguagePack.kt`) declara, por idioma:

| Campo | Que es |
|---|---|
| `code` | Clave de contenido y de API: `content/<code>`, `?language=<code>` |
| `displayName` / `nativeName` / `flag` | Presentacion en el selector de la app |
| `g2pKind` | `CMU_DICT` (lectura de un diccionario), `LEXICON` (lexicon propio), `PRECOMPUTED` (sin G2P en runtime) |
| `g2pAsset` | Fichero del lexicon dentro de `content/<code>/` (p. ej. `fr.lexicon`) |
| `phonemizerLocale` | Locale para el pipeline (p. ej. `fr-fr`) |
| `phonemeInventory` | Simbolos IPA esperados (documentacion/validacion) |
| `similarityKey` | Bucket de la matriz en `PhoneticComparator` (`en`, `fr`, `de`, `ru`, `es`, `la`) |
| `errorRulesKey` | Bucket de reglas en `ErrorRules` (`en`, `fr`, `de`, `ru`) |
| `accents` / `defaultAccent` / `voices` | Voces offline y acentos disponibles |

**El modelo desplegado NO sirve para otros idiomas** (medido el 2026-10-01 con
las voces rusas de Cartesia). `wav2vec2_phoneme_int8_dyn_pertensor.onnx` esta
ajustado a ingles y escribe cualquier audio con fonos ingleses: "Это наш друг
Миша" sale `ɛtənəzdɹkmiəʃə`. El modelo base multilingue
(`wav2vec2-lv-60-espeak-cv-ft`, el fp16 sin ajustar) lo escribe en la notacion de
espeak-ng ruso: `etʌnaʒdrukmʲiʃa`. Por eso:

- El servidor puntua cada idioma con el modelo que diga `GLOSSO_MODEL_FILES`
  (`ru=wav2vec2_phoneme_multilingual_int8_dyn_pertensor.onnx,de=...`, ficheros
  dentro de assets). Sin la variable usa un solo modelo, como antes.
- **El fp16 no cabe en el VPS.** Con `ru=wav2vec2_phoneme_fp16.onnx` el servidor
  pasa de 4 GiB de memoria anonima en cuanto puntua (medido en un canario el
  2026-10-02: muere por OOM con el limite de 2304M y llena uno de 4096M). El
  modelo de produccion para ruso es el base multilingue cuantizado con la misma
  receta selectiva que el de ingles
  (`benchmark/finetune/quantize_selective.py quantize --method dynamic` sobre el
  fp32 exportado de `facebook/wav2vec2-lv-60-espeak-cv-ft`): 358 MB, pico de
  2,14 GiB con los dos modelos cargados. Por eso el servicio necesita
  `GLOSSO_SERVER_MEMORY=3072M` en `deploy/.env` cuando hay un segundo modelo.
  Las notas no cambian: sobre los 804 clips de referencia de los drills rusos,
  media 87,2 con fp16 y 87,2 con int8 (diferencia media 0,05; 32 y 28 clips
  llegan a 98).
- En el movil solo se puntua ingles (`LanguagePack.onDeviceScoring`); los demas
  idiomas van siempre al servidor.
- Un idioma se prueba en un build sin publicarlo con
  `-PglossoExtraLanguages=de` (`OfferedLanguages` en la app).

Medicion rusa, "un nativo saca >= 98", sobre 112 frases de referencia (drills y
dialogos, voces Cartesia):

| modelo | media | >= 98 |
| --- | --- | --- |
| desplegado (ajustado a ingles) | 57 (drills) | 0 de 50 |
| base multilingue, sin calibrar | 83 (drills), 89 (dialogos) | 13 de 112 (11,6 %) |

Las palabras sueltas son peores (media 76, 27 de 120 clips sin evaluar por
cortos). El residuo son fonos que el modelo base se come o confunde en ruso, no
diferencias de notacion: plegar los alofonos vocalicos solo sube a 14 de 112.
Para que un nativo ruso saque 98 hace falta lo mismo que en ingles: un modelo
ajustado al idioma. Hasta entonces la nota rusa es orientativa.

Comando reproducible (reputua las transcripciones guardadas, sin modelo):

```sh
GLOSSO_NATIVE_TRANSCRIPTS=<filas.json> GLOSSO_NATIVE_LANGUAGE=ru \
  ./gradlew :engine:jvmTest --rerun --tests 'com.glosso.engine.phonetics.NativeTranscriptBenchTest' -i | grep NATIVE98
```

Las filas (`[id, voz, nota, evaluado, texto, referencia, oido]`) salen de pasar
los clips de referencia por `POST /v1/score` del servidor con el modelo del idioma.

Otros hallazgos del ruso:

- El contenido escribe el acento con U+0301. espeak-ng no lo entiende y mueve el
  acento, asi que `phonemize.py` lo quita, avisa de las palabras que espeak
  acentua distinto y las corrige con `content/ru/ru.overrides`.
- Las frases se fonemizan enteras (las atonas se reducen y las preposiciones
  asimilan); el lexicon guarda cada palabra leida sola.
- espeak-ng no marca la blandura de ль y нь a final de palabra (уголь = угол):
  ese contraste no se puede comprobar ni puntuar.
- Los drills nombran sus sonidos en la notacion del engine (`ʃ`, `ʒ`, `y`, `ʌ`,
  `ɭ`), no en IPA estandar; la tabla esta en `curriculum/ru/phonology.yaml` de
  glosso-data.

El contenido vive en `glosso-data/content/<code>/`:

```
content/<code>/
  manifest.json        temas (ids de themes.md) + idioma
  drills/ words/ dialogues/ exercises/ stories/ articles/ lessons/   # JSON autoral
  <code>.lexicon       word ipa (G2P runtime, generado)
  dist/                salida de publishContent (bancos con IPA)
  content.db           salida del pipeline (SQLite) - generado
  audio/ images/       salida del pipeline - generado
```

---

## 2. El language pack

Es un **bloque de datos**. Copia el de frances y cambia los valores; no hay
logica que tocar:

```kotlin
val ITALIAN = LanguagePack(
    code = "it",
    displayName = "Italian",
    nativeName = "Italiano",
    flag = "\uD83C\uDDEE\uD83C\uDDF9",
    experimental = true,
    g2pKind = G2PKind.LEXICON,      // o CMU_DICT solo para ingles
    g2pAsset = "it.lexicon",
    phonemizerLocale = "it",
    phonemeInventory = setOf("a", "e", "i", "o", "u", "t", "d", "k", "ɡ", "p",
        "b", "f", "v", "s", "z", "ʃ", "tʃ", "dʒ", "m", "n", "ɲ", "l", "ʎ", "r", "j", "w"),
    similarityKey = "en",           // reutiliza una matriz; anade una si hace falta
    errorRulesKey = "en",           // reutiliza reglas; anade un set si las hay
    accents = listOf(AccentOption("it-IT", "Italian")),
    defaultAccent = "it-IT",
    voices = listOf(VoiceModel("if_sara", "it", "it-IT", VoiceGender.FEMALE, "Sara")),
)
```

Y anadelo a `LanguagePacks.all`. **Por que no un JSON en runtime:** el engine
necesita las voces, acentos, matriz y reglas en compilacion, y cargar un fichero
en `commonMain` exigiria una dependencia de recursos multiplataforma; el pack es
puro dato y se versiona con el engine. Si en el futuro se quieren reglas de error
nuevas sin recompilar, ese es el punto a cambiar.

Si el idioma necesita una matriz de similitud propia, anade una en
`PhoneticComparator` y apunta `similarityKey` a ella. Si necesita reglas de error
curadas, anade un `RuleSet` en `ErrorRules` y apunta `errorRulesKey`.

**Regla dura del inventario:** todo simbolo que pongas en el inventario, en la
matriz o en una regla tiene que ser un token que el modelo puede emitir. Un
simbolo fuera del vocabulario no se puede oir nunca: la referencia que lo use no
puede sacar 100 y la regla que dependa de el no dispara. Anade el pack a
`LanguageInventoryTest` (`engine/src/jvmTest`), que lo comprueba contra el
vocabulario de 392 tokens. Antes de escribir el inventario, mira que emite
espeak-ng de verdad para ese locale (seccion 4): no coincide con el IPA de
manual (el ruso escribe la Ы como `y`, el aleman la R como `r`/`ɾ`).

---

## 3. Contenido en glosso-data

Crea `content/<code>/` con un banco minimo. Sigue `glosso-data/AGENTS.md` y
`prompts/*.md` (temas en `themes.md`, sin inventar). Para una v1 minima basta:

- 4-8 `drills` (contrastes propios del idioma; son los que la app puntua).
- 1 `dialogue` o `story` como base de leccion.
- 2 lecciones minimas (`exerciseIds` de 2 a 6, `drillTargets` con los sonidos).
- 4-8 `exercises` (tipos: `multiple-choice`, `true-false`, `fill-blank`,
  `order`, `match`; mira `ContentValidator`).
- 4-6 `words` (`lemma` en minusculas, `translations.es`, `examples`).
- `manifest.json` con `language` y los temas usados (ids de `themes.md`).

Convencion de `id`: `<code>.<target>.<contrast>.<theme>.<nnn>` para drills,
`<code>.<kind>.<theme>.<nnn>` para el resto.

**IPA:** deja `ipa` vacio; lo rellena `phonemize.py` (seccion 4). **No** lo
escribas a mano salvo que no haya G2P.

**Validacion de drills:** el publicador exige que el sonido objetivo aparezca
>= 2 veces y que `focus` sea un par minimo real (difieren en 1 sonido). Si el
G2P del idioma no produce el simbolo que declaras (p. ej. espeak puede escribir
`nj` en vez de `ɲ` en alguna palabra), ajusta el drill o el simbolo objetivo.

---

## 4. G2P

Recomendacion general: **precomputar el IPA con espeak-ng en publish-time** y
guardarlo en cada item. El servidor y la app no necesitan espeak en runtime: la
app envia `expectedIpa` (el IPA del drill) al puntuar, y el servidor puede
fonemizar con el lexicon publicado. espeak-ng es ademas el fonemizador con el
que se entreno el modelo, asi que su IPA es la que mejor encaja con el inventario
del reconocedor.

```sh
# 1) rellena ipa en el JSON autoral y escribe content/<code>/<code>.lexicon
uv run --python 3.11 \
    --with espeakng-loader --with phonemizer-fork \
    python tools/content/phonemize.py --language <code> \
    --content ../../../glosso-data/content/<code>
```

Notas:

- espeak-ng se usa como **herramienta externa de authoring** (subproceso), no se
  enlaza ni se empaqueta en el producto; solo viajan los datos (IPA + lexicon).
  Ver la excepcion y su justificacion en `docs/LICENSING.md`.
- Se fonemiza **palabra a palabra** (un token IPA por palabra) para conservar el
  alineamiento por palabra del scorer. Esto pierde la liaison/enlace entre
  palabras: escribe frases cortas donde no importe.
- `phonemize.py` quita las banderas de cambio de idioma que espeak-ng pone a
  los prestamos (`(en)bˈeɪbi(de)`) en todo idioma que no sea ingles, y reescribe
  las grafias de espeak que son un solo sonido en varios caracteres
  (`SYMBOL_FOLDS`, hoy solo ruso). El scorer aplica los mismos folds a lo que oye
  el modelo (`RU_SYMBOL_FOLDS` en `PhoneticComparator`): las dos tablas van juntas.
- Alternativas permisivas si no quieres espeak: **Epitran** (MIT) o **Gruut**
  (MIT). Genera el mismo `<code>.lexicon`. Si no hay G2P, usa
  `g2pKind = PRECOMPUTED` y escribe el IPA a mano, enviando `expectedIpa`.

---

## 5. Audio TTS

El pipeline usa **Kokoro** por defecto y **Chatterbox** con `--tts chatterbox`,
parametrizado por idioma en `tools/content/pipeline.py` (`LANGUAGES`). Anade ahi
las voces del idioma (clave = `<accent>-<gender>`, valor = id de Kokoro) y sus
acentos. El `lang_code` de Kokoro es la primera letra del id de voz.

```sh
uv run --python 3.11 --with kokoro --with soundfile --with numpy \
    python tools/content/pipeline.py --language <code> \
    --audio-kinds drills,dialogues,words,stories,articles
```

```sh
# Chatterbox: clona las refs Kokoro de tools/content/voices/<id>.wav
/opt/gen/venv-tts/bin/python tools/content/pipeline.py --tts chatterbox \
    --device cuda --model-dir /opt/gen/models/chatterbox \
    --refs-dir tools/content/voices --language <code> ...
```

Ambos backends escriben los mismos nombres (`<name>.<voicekey>[.slow]`, OGG
Vorbis 24 kHz mono), asi que la app no cambia. Las refs `tools/content/voices/`
se generan con `tools/content/make_voice_refs.py` (Kokoro, Apache-2.0), **con el
texto del idioma de cada voz** (ver `TEXTS`), y son sinteticas a proposito: no se
clona la voz de ninguna persona real. Chatterbox (MIT) va por familia de modelo
(ingles / multilingue con `language_id`; la ruta `f -> fr` esta lista pero hoy no
se ofrece frances); la entonacion sale del perfil puro de
`tools/content/intonation.py` (tests `intonation_test.py` y `naming_test.py`,
A/B de calibracion en `intonation_ab.py`).

Kokoro trae packs para `en`, `es`, `fr`, `it`, `pt`, `hi`, `ja`, `zh` (voces
`a/b/e/f/i/p/h/j/z`). **Aleman y ruso no tienen voces Kokoro**, y la de frances no
vale (ver seccion 9).

**Frances, aleman y ruso: audio con Cartesia.** El audio de referencia de estos
idiomas ya no sale de este pipeline ni de Piper: se renderiza con Cartesia, con
las voces de la cuenta del dueno (dos por genero, listadas en el `LanguagePack`
de cada idioma). Los clips se llaman igual que los ingleses,
`<id>.<accent>-<gender>.ogg`, con `fr-FR`, `de-DE` y `ru-RU` como acento.
En `LANGUAGES` estos idiomas llevan `"renderer": "cartesia"` y sus narradores
(`fr-FR-female`, `fr-FR-male`, ...), asi que:

```sh
# construye content.db y cablea en los mapas de audio los clips que ya esten en disco
python tools/content/pipeline.py --language de --no-render
```

Sin `--no-render` el pipeline se niega (no le pasa un id de voz de Cartesia a
Kokoro) y no escribe provenance propia de esos clips: la lleva quien los
renderiza. El script de render de Cartesia (`tools/content/cartesia_a1_audio.py`)
hoy solo nombra clips `en-GB` con las voces inglesas; generalizarlo a estos
idiomas es trabajo de su stream. No uses MMS-TTS (CC-BY-NC).

---

## 6. Imagenes

El arte de marca (linea oro sobre negro) es **agnostico al idioma**: reutiliza
las imagenes ya generadas en vez de volver a pagar la API. `images.py` prefiere
imagenes del mismo tema y las copia con el id del item.

```sh
# reutilizar (sin API key)
python tools/content/images.py --content ../../../glosso-data/content/<code> \
    --kind exercises --reuse-from ../../../glosso-data/content/en/images
# idem para dialogues y stories

# o generar arte nuevo por item (necesita DEEPINFRA_API_KEY)
python tools/content/images.py --language <code> --kind exercises
```

Cada item lleva su `imagePrompt` (agnostico al idioma), asi que generar de cero
tambien "sale gratis" en esfuerzo.

---

## 7. Publicar y sincronizar

```sh
# 1) IPA + lexicon
python tools/content/phonemize.py --language <code> --content ../glosso-data/content/<code>
#    (el publish tambien autodetecta content/<code>/<code>.lexicon)

# 2) valida y compila los bancos -> dist/ (rellena IPA que falte, valida drills)
./gradlew :server:publishContent -Planguage=<code>
#    -Plevels=A1 (o A1,A2) publica solo esos niveles: deja fuera los items de
#    otros niveles y las lecciones que usen alguno de ellos.

# 3) audio + SQLite + manifest + (copia de) imagenes
python tools/content/pipeline.py --language <code> \
    --audio-kinds drills,dialogues,words,stories,articles

# 4) sube a los assets del servidor: <assets>/content/<code>/
deploy/sync-content.sh --language <code> user@vps:/srv/glosso/assets
```

En el VPS, los assets quedan:

```
/srv/glosso/assets/
  wav2vec2_phoneme_fp16.onnx     # modelo unico, compartido
  phoneme_vocab.json
  content/
    en/  {content.db, manifest.json, cmudict.dict, audio/, images/}
    <code>/ {content.db, manifest.json, <code>.lexicon, audio/, images/}
```

Si no se usa `publishContent` (p. ej. solo datos), recuerda que el servidor lee
`content/<code>/content.db` y `content/<code>/manifest.json`.

---

## 8. Verificar

Tests y build:

```sh
./gradlew :engine:jvmTest :server:test :androidApp:testFdroidDebugUnitTest :androidApp:testPlaystoreDebugUnitTest
./gradlew build
```

Servidor local con el contenido nuevo:

```sh
GLOSSO_ASSETS_DIR=$PWD/data/assets PORT=8080 ./gradlew :server:run
curl "http://localhost:8080/v1/content/LESSON?language=<code>"
curl "http://localhost:8080/v1/content/manifest?language=<code>"
curl -X POST http://localhost:8080/v1/score -H 'Content-Type: application/json' \
  -d '{"text":"...","expectedIpa":"...","actualIpa":"...","language":"<code>"}'
```

En la app: Library -> Settings -> Target language -> elige el idioma; el
contenido, los acentos y las voces cambian. Abre una leccion y comprueba que
suena el audio (`/assets/content/<code>/audio/...`).

**Validacion de reconocimiento (importante):** puntua una grabacion real del
idioma y mira la nota y los fonemas. Si la precision es pobre, anotalo en
`docs/FINDINGS.md`: puede hacer falta un modelo por idioma o ajustar el
inventario. `experimental = true` en el pack hasta validarlo.

---

## 9. Estado por idioma objetivo

### Ingles (`en`) - soportado

- G2P CMUdict (BSD-2-Clause), 8 voces Kokoro (`a`/`b`), matrices y reglas curadas.
- Referencia: implementacion original; sin regresiones.

### Frances (`fr`) - motor listo, no se ofrece todavia

- Motor listo: G2P espeak-ng `fr-fr` en publish-time + `fr.lexicon` para runtime,
  matriz `fr` y reglas de error francesas (`fr.an.on`, `fr.u.ou`, `fr.r.rolled`...).
- **Voces:** Cartesia `fr-FR`, dos por genero (Inès, Léonie, Étienne, Mathieu) en
  el pack; `LANGUAGES["fr"]` tiene `fr-FR-female`/`fr-FR-male` para `--no-render`.
  Sustituyen a la voz Kokoro `ff_siwis`, cuya referencia se genero con texto en
  ingles. La ruta Chatterbox (`f -> fr`, `make_voice_refs.py`) sigue en el repo
  pero no es la que se usa.
- **Inventario contra el modelo:** espeak-ng 1.52 escribe la semiconsonante de
  "nuit" como `y` (`nyˈi`) y el modelo no tiene token `ɥ`, asi que `ɥ` salio del
  inventario. Las reglas `fr.ui.oui`/`fr.oui.ui` y las entradas `ɥ` de la matriz
  siguen ahi pero no pueden disparar con una referencia de espeak. espeak tambien
  escribe `anjˈo` para "agneau" (y `ɲ` solo a final de palabra) y deja un guion
  final en los cliticos (`ʒə-`), que el scorer ignora.
- **No se ofrece en la app:** `LanguagePacks.offered` solo incluye ingles y ruso hasta que
  el banco `content/fr` este listo. El pack sigue en `LanguagePacks.all` y
  `LanguagePreferences` no lo acepta aunque quede guardado de una build anterior.
- **Sin validar:** reconocimiento con audio real en frances. Antes de ofrecerlo:
  medirlo, ampliar el banco, revisar liaison (hoy se fonemiza por palabra),
  publicar `content/fr` y anadirlo a `LanguagePacks.offered`.

### Aleman (`de`) - motor y tooling listos, sin validar con voz real

Que esta cableado:

- Pack `GERMAN` (`experimental`, `de.lexicon`, acento `de-DE`, voces Cartesia
  Lena, Lorelei, Clemens, Jan). En `LanguagePacks.all`, **no** en `offered`.
- Matriz `de` revisada contra lo que escribe espeak-ng `de`, reglas de error
  `de.*` pensadas para un hispanohablante (Ü/Ö, longitud vocalica, ICH/ACH, S
  sonora, W dicha como B, terminaciones atonas, H muda) y pistas en los cuatro
  idiomas de UI.
- Tooling: `phonemize.py --language de`, `publishContent -Planguage=de`,
  `pipeline.py --language de --no-render`.

Donde espeak-ng y el modelo no coinciden, y que se eligio:

| espeak-ng 1.52 | Modelo | Decision |
|---|---|---|
| `ʏ` (Ü corta: `fˈʏnf`) | no tiene `ʏ`, solo `y` | El lexicon conserva `ʏ`; `ModelInventory` lo resuelve a `y` y la matriz trata `ʏ`/`y` como el mismo sonido (0,98). |
| `r` ante vocal, `ɾ` en el resto; nunca `ʁ` | tiene `r`, `ɾ` y `ʁ` | `r`, `ɾ` y `ʁ` son un solo fonema (>= 0,9): la R uvular, la vibrante y la simple son todas nativas. No hay regla de error para la R. |
| `ɜ` para "-er" (`fˈɑːtɜ`) | tiene `ɜ`, `ɐ`, `ə` | `ɜ`/`ə` a 0,85; el scorer ya pliega `ɐ` en `ə` para todos los idiomas, asi que la `ɐ` del modelo cuenta como correcta. |
| `ʊɐ` (R vocalizada: `dˈʊɐç`) | no como token unico; si `ʊ` y `ɐ` | Se parte en `ʊ` + `ɐ`. Una `ɾ` de coda dicha vocalizada (`ɾ`/`ə`) vale 0,85. |
| `ɑː` (A larga), `a` (corta) | tiene `ɑː` y `aː` | `ɑː`/`aː` es el mismo sonido (0,98); `ɑː`/`a` es el contraste Staat/Stadt (0,8). |
| `ɔø` ("eu"/"äu") | token `ɔø` | El scorer lo parte en `ɔ` + `ø`; `ø` contra `ɪ`/`ʏ`/`y` vale 0,9 por si el modelo oye `ɔɪ`. En validacion de contenido es un solo fono. |
| `ts`, `pf` | tokens `ts`, `pf` | El scorer y la validacion los tratan como dos fonos (`t`+`s`), igual que en ingles: un drill no puede declarar `ts` como objetivo. |

Que NO esta validado:

- **La precision del reconocimiento con voz alemana real no se ha medido.** La
  matriz y las reglas son fonologia y lo que escribe espeak; ningun numero sale
  de audio. No se sabe que emite el modelo para una Ü corta, una R uvular o un
  "-er" reales: la tabla de arriba es la hipotesis, no una medicion.
- Alineamiento forzado, cabeza de calidad y prosodia: entrenados y medidos solo
  en ingles.
- La lista de palabras prohibidas de `DrillValidator` ahora solo se aplica a
  ingles (en aleman "dick" es vocabulario A1); no hay lista alemana curada.

### Ruso (`ru`) - publicado el 2026-10-02, sin validar con voz real

Que esta cableado:

- Pack `RUSSIAN` (`experimental`, `ru.lexicon`, acento `ru-RU`, voces Cartesia
  Tatiana, Irina, Sergei, Dmitri). En `LanguagePacks.offered` desde el
  2026-10-02 por decision del dueno: el curso (188 lecciones autoradas, A1 a B2)
  se ofrece en todas las builds. Sigue sin revision de un hablante nativo y lejos de la
  regla "un nativo saca >= 98" (4 % de los clips de referencia de los drills,
  media 87).
- Los textos que el curso escribe para el alumno y no en ruso (titulo y objetivo
  de la leccion, significados de los ejercicios de emparejar) van en `l10n` por
  idioma de interfaz (`LessonModel.localized`, `ExerciseModel.localized`); las
  traducciones de frases, palabras y pistas, en sus mapas `translations` y `cue`.
  Hoy en es, en, fr y de.
- Matriz `ru` nueva (antes caia en la inglesa), reglas `ru.*` para un
  hispanohablante (consonante blanda dicha dura, Ы, vocales atonas sin reducir,
  Ш/Ж/Щ, З y В sonoras), pistas en los cuatro idiomas de UI y tabla
  letra-fonema para el cirilico.
- Tooling igual que en aleman, con `--language ru`.
- **Hoy solo A1 publicado**; A2-B2 ocultos hasta reescribirlos con criterio
  cultural (notas de leccion `notes`: cultura, etiqueta, registro y lengua, que
  la app ensena en el paso "Contexto"). Se publica con:

  ```sh
  ./gradlew :server:publishContent -Planguage=ru -Plevels=A1
  python tools/content/pipeline.py --language ru --no-render
  ```

  Sin `-Plevels=A1` vuelven a salir los cuatro niveles. Con un solo nivel la app
  no ofrece selector de nivel.

Notacion: espeak-ng `ru` y el modelo coinciden (el modelo se entreno con esa
notacion; todos los simbolos que espeak-ng 1.52 emitio para una muestra de unas
500 palabras estan en el vocabulario), pero **no es el IPA de manual**:

| Sonido | espeak-ng / modelo | IPA de manual |
|---|---|---|
| Ы | `y` | `ɨ` (la matriz acepta `ɨ` como el mismo sonido) |
| Л dura / ЛЬ | `ɭ` / `ɭʲ` | `ɫ` / `lʲ` |
| ЗЬ | `ʑ` | `zʲ` |
| Щ, Ш, Ж | `ɕ`, `ʃ`, `ʒ` | `ɕː`, `ʂ`, `ʐ` |
| О/А atonas | `ʌ` o `a` | `ɐ`, `ə` |
| Е/Я atonas | `i` o `ɪ` | `ɪ` |
| А tonica, Ё | `ɑ`, `ɵ` | `a`, `ɵ` |

Folds (`RU_SYMBOL_FOLDS` en el engine, `SYMBOL_FOLDS` en `phonemize.py`): tres
grafias de espeak son un sonido en varios caracteres y, sin plegar, el caracter
extra se puntuaria como un segmento propio.

| espeak-ng | Se pliega a | Por que |
|---|---|---|
| `tʃʲ` (Ч) | `tʃ` | Ч siempre es blanda: la marca no contrasta y una CH espanola es el mismo fonema. |
| `ɪ^` (РЬ final: `dvʲˈerɪ^`) | `ʲ` | Es la blandura de la R, no una vocal. |
| `u"` (У tras blanda) | `u` | Alofono. |

**Blandura:** una consonante blanda es un token del modelo (`tʲ`) y un fono en la
validacion de contenido (мать/мат es un par minimo `tʲ`/`t`), pero el scorer la
compara como segmento propio `ʲ`: decirla dura es una `ʲ` omitida y dispara
`ru.soft.hard`. Es asi porque el parser de fonos es comun a todos los idiomas y
no se ha tocado para no mover el scoring de ingles.

Que NO esta validado, y limites conocidos:

- **La precision del reconocimiento con voz rusa real no se ha medido.** Como en
  aleman, todo sale de la fonologia y de espeak, no de audio.
- espeak-ng pierde la blandura final de НЬ y ЛЬ (`dʲˈen` para "день", `jˈeɭ`
  tanto para "ел" como para "ель"): esos pares minimos no validan y esa
  blandura no se puntua. Corrige ese IPA a mano o evita esos drills.
- Ы/И no es un par minimo de un solo fono (быть/бить tambien cambia la blandura
  de la consonante): esos drills van sin `contrast`.
- El acento lexico esta en el IPA de referencia (`ˈ`) pero el scorer no lo
  puntua; la reduccion vocalica si, porque espeak la escribe.
- La confianza por fono se descarta en las frases cuyo audio trae `tʃʲ`, `ɪ^` o
  `u"` (los folds cambian el numero de fonos); solo afecta al descuento de las
  inserciones.
- Alineamiento forzado, cabeza de calidad y prosodia: solo ingles.

### Mandarin (`zh`) - fase 2 (trabajo mayor)

- TTS Kokoro `z` disponible; espeak `cmn`.
- Decisiones necesarias antes de empezar:
  1. **Segmentacion**: no hay espacios; hay que segmentar palabras (p. ej. jieba,
     revisar licencia) o puntuar por caracter/silaba.
  2. **Tonos**: el reconocedor **descarta tokens de tono**; hay que
     interpretarlos desde el vocab y puntuar el tono como parte del scoring.
  3. Inventario de iniciales/finales y reglas de error propias (p. ej. zh/z,
     sh/s, tonos confundidos).
- Marcar como `experimental` y no ofrecerlo hasta validar el modelo.

### Regla comun

Para cualquier idioma nuevo: `experimental = true` hasta validar el
reconocimiento con voz real, y anotar el resultado en `docs/FINDINGS.md`.

---

## 10. Benchmark de modelos por idioma

Medido el 2026-11-22 con `tools/language-bench/` (como reproducirlo, fuentes,
normalizacion y metricas en su `README.md`; numeros en `results/`). Pregunta
doble: para que idiomas podemos crear contenido ya y con que modelo, sabiendo
que el modelo tiene que caber en el VPS (CPU, ~3 GB para todo el servidor) y en
el movil (int8 ONNX, idealmente muy por debajo de 400 MB).

**Audio.** Voz nativa real: los 100 primeros clips del test de Google FLEURS
(CC-BY-4.0) por idioma, frases largas leidas de Wikipedia (10-20 s), mucho mas
dificiles que un drill. Voz TTS: 18 frases cortas de FLEURS x 2 voces Cartesia
(mujer y hombre) por idioma, 36 clips, 23.644 caracteres en total (30 frases x 2
voces se iba a ~43k, por encima del presupuesto). La TTS se parece mas a un drill
de la app, pero no es un humano.

**Metricas.** PER (tasa de error de fonos) contra la IPA de espeak-ng de la frase:
*raw* (solo sin acento ni espacios) y *plegado* (sin diacriticos, longitud ni
modificadores, y con las variantes de notacion unificadas: `g/ɡ`, las R, `ɫ/ɭ/l`,
`ɐ/ə`, `ʂ ʐ ʈ/ʃ ʒ t`, `ɨ/y`...), para no castigar a ZIPA por escribir en otra
notacion que la de espeak, con la que se entreno wav2vec2. *Basura*: clips con
transcripcion vacia o PER plegado > 0,5. *Motor*: la nota real de
`PhoneticComparator` via `NativeTranscriptBenchTest` (media y % de clips >= 98,
contando como fallo los vacios), solo en `en ru es fr de`, los idiomas con matriz
propia; `it pt nl pl uk` caen en la matriz inglesa y su nota no significaria nada.
A ZIPA y whisper se les aplica antes del motor un mapeo minimo a la notacion de
espeak (`TO_ESPEAK` en `bench.py`: tilde de velarizacion fuera, `g`->`ɡ`,
`ɜ˞`->`ɚ`, `ʀ`->`ʁ`, `ʈʂ ʂ ʐ`->`tʃ ʃ ʒ`), el primer esbozo del mapeo que habria
que mantener si se cambia de modelo.

### Modelos

| clave | modelo | params | int8 en disco | licencia |
| --- | --- | ---: | ---: | --- |
| w2v2-lv60 | `facebook/wav2vec2-lv-60-espeak-cv-ft` (el multilingue del servidor hoy) | 315M | 358 MB | Apache-2.0 |
| w2v2-en-int8 | el ingles desplegado (solo `en`, ancla) | 315M | 358 MB | Apache-2.0 (base) |
| zipa-cr-small | `anyspeech/zipa-small-crctc-500k` (CTC) | 64M | 71 MB | CC-BY-4.0 |
| zipa-t-small | `anyspeech/zipa-small-noncausal-500k` (transducer) | 65M | 71 MB | CC-BY-4.0 |
| zipa-cr-large | `anyspeech/zipa-large-crctc-500k` (CTC) | 300M | 310 MB | CC-BY-4.0 |
| zipa-ns | `anyspeech/zipa-small-crctc-ns-700k`, solo referencia | 64M | 71 MB | CC-BY-NC-SA-4.0: **no usable** (app de pago) |
| whisper | `neurlang/ipa-whisper-base` (whisper-base ajustado a IPA de goruut) | 74M | 182 MB | Apache-2.0 |

Descartados sin medir: Allosaurus (codigo GPL-3.0), PhoneticXeus (CC-BY-NC-SA),
Phonon-2.5 (es ASR de texto, no de fonos). wav2vec2 se evaluo en fp32 con
transformers (en ruso int8 y fp16 dieron la misma media, seccion 1); ZIPA en su
int8 ONNX, que es lo que se desplegaria; whisper en fp32.

### Velocidad y memoria (CPU, Apple M1 Max, 20 clips FLEURS, 189 s de audio)

| modelo | ms por s de audio, 1 hilo | ms por s, hilos por defecto | RSS del modelo cargado | pico del proceso |
| --- | ---: | ---: | ---: | ---: |
| w2v2 (int8 ONNX del servidor) | 218 | 58 | 505-528 MB | 1,16 GB |
| zipa-cr-small (int8) | 19 | 8 | 160-176 MB | 0,66 GB |
| zipa-t-small (int8) | 18 | 9 | 139-179 MB | 0,68 GB |
| zipa-cr-large (int8) | 51 | 18 | 606-610 MB | 1,10 GB |
| whisper (fp32 torch) | 213 | 182 | 142 MB | 0,79 GB |

El pico incluye ~0,5 GB de torch/transformers que el script importa para todos
los modelos y que el producto no carga: para comparar cuenta el RSS del modelo.
wav2vec2 se cronometro con el int8 ingles desplegado, que tiene el mismo grafo y
la misma cabeza de 392 tokens que el multilingue int8. ZIPA small es ~12x mas
rapido que wav2vec2 con un hilo y ocupa 1/5 en disco.

### Nota del motor (media / % de clips >= 98)

| idioma, audio | w2v2-en-int8 | w2v2-lv60 | zipa-cr-small | zipa-t-small | zipa-cr-large | whisper | zipa-ns (NC) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| en TTS | 92,9 / 11 % | 96,5 / 44 % | 91,9 / 14 % | 92,2 / 11 % | 92,3 / 14 % | 61,3 / 0 % | 92,1 / 17 % |
| en FLEURS | 91,0 / 0 % | 92,2 / 6 % | 86,7 / 0 % | 88,2 / 0 % | 87,8 / 0 % | 58,9 / 0 % | 88,1 / 0 % |
| ru TTS | - | 88,1 / 6 % | 82,2 / 0 % | 83,2 / 0 % | 84,4 / 0 % | 55,0 / 0 % | 83,4 / 0 % |
| ru FLEURS | - | 84,1 / 0 % | 79,2 / 0 % | 82,1 / 0 % | 76,9 / 0 % | 60,2 / 0 % | 85,0 / 0 % |
| es TTS | - | 92,1 / 11 % | 96,8 / 56 % | 97,6 / 58 % | 97,4 / 67 % | 61,8 / 0 % | 97,2 / 61 % |
| es FLEURS | - | 86,7 / 0 % | 95,7 / 42 % | 95,8 / 49 % | 94,8 / 38 % | 75,0 / 0 % | 95,7 / 46 % |
| fr TTS | - | 87,4 / 3 % | 91,6 / 8 % | 92,4 / 14 % | 89,4 / 3 % | 46,4 / 0 % | 90,0 / 8 % |
| fr FLEURS | - | 82,4 / 0 % | 86,4 / 1 % | 88,8 / 5 % | 85,3 / 2 % | 44,8 / 0 % | 86,3 / 3 % |
| de TTS | - | 86,9 / 0 % | 90,9 / 0 % | 90,2 / 0 % | 89,4 / 0 % | 53,9 / 0 % | 89,6 / 0 % |
| de FLEURS | - | 82,5 / 0 % | 86,4 / 0 % | 87,3 / 0 % | 85,9 / 0 % | 53,0 / 0 % | 86,4 / 0 % |

El ruso con wav2vec2 cuadra con la medicion de la seccion 1 (media 83-89). La
fila inglesa es orientativa: en produccion la referencia inglesa sale de CMUdict,
no de espeak `en-us`, y el ancla real es `tools/engine-bench` (62 % de los clips
nativos del catalogo llegan a 98). Que `w2v2-lv60` saque mas que el ingles
desplegado aqui solo dice que escribe en la misma notacion `en-us` que la
referencia; el desplegado escribe en la britanica.

### PER plegado (raw), TTS / FLEURS

| idioma | w2v2-lv60 | zipa-cr-small | zipa-t-small | zipa-cr-large | whisper | zipa-ns (NC) |
| --- | --- | --- | --- | --- | --- | --- |
| en | 0,05 (0,06) / 0,10 (0,12) | 0,11 (0,20) / 0,20 (0,28) | 0,13 (0,22) / 0,18 (0,27) | 0,10 (0,19) / 0,19 (0,28) | 0,56 / 0,49 | 0,12 / 0,18 |
| ru | 0,22 (0,26) / 0,26 (0,29) | 0,36 (0,53) / 0,38 (0,55) | 0,28 (0,49) / 0,31 (0,50) | 0,30 (0,50) / 0,31 (0,52) | 0,80 / 0,58 | 0,30 / 0,32 |
| es | 0,10 (0,11) / 0,18 (0,18) | 0,23 (0,29) / 0,19 (0,25) | 0,21 (0,26) / 0,18 (0,20) | 0,22 (0,27) / 0,25 (0,29) | 0,62 / 0,32 | 0,23 / 0,25 |
| fr | 0,18 (0,18) / 0,22 (0,23) | 0,13 (0,14) / 0,24 (0,29) | 0,11 (0,13) / 0,17 (0,20) | 0,17 (0,21) / 0,24 (0,30) | 1,14 / 0,72 | 0,16 / 0,24 |
| de | 0,17 (0,19) / 0,23 (0,26) | 0,16 (0,24) / 0,26 (0,35) | 0,17 (0,27) / 0,23 (0,32) | 0,19 (0,29) / 0,24 (0,34) | 0,88 / 0,60 | 0,18 / 0,25 |
| it | 0,21 (0,27) / 0,20 (0,23) | 0,18 (0,21) / 0,18 (0,21) | 0,15 (0,19) / 0,15 (0,18) | 0,17 (0,20) / 0,16 (0,18) | 1,46 / 0,46 | 0,17 / 0,16 |
| pt | 0,31 (0,36) / 0,38 (0,43) | 0,38 (0,43) / 0,40 (0,44) | 0,36 (0,41) / 0,38 (0,42) | 0,42 (0,46) / 0,41 (0,45) | 1,26 / 1,06 | 0,39 / 0,43 |
| nl | 0,38 (0,49) / 0,39 (0,48) | 0,23 (0,30) / 0,28 (0,34) | 0,26 (0,30) / 0,29 (0,33) | 0,26 (0,31) / 0,30 (0,33) | 0,57 / 0,59 | 0,26 / 0,29 |
| pl | 0,34 (0,39) / 0,43 (0,51) | 0,24 (0,37) / 0,37 (0,46) | 0,18 (0,26) / 0,33 (0,38) | 0,08 (0,17) / 0,32 (0,36) | 0,65 / 0,52 | 0,08 / 0,30 |
| uk | 0,36 (0,57) / 0,44 (0,63) | 0,38 (0,59) / 0,42 (0,60) | 0,31 (0,48) / 0,38 (0,50) | 0,34 (0,53) / 0,36 (0,44) | 0,69 / 0,63 | 0,31 / 0,34 |

Clips vacios o basura: 0 % en casi todo para wav2vec2 y ZIPA; 1-9 % en `pt`,
`pl` y `uk` (FLEURS) y 6-8 % en `ru` con zipa-cr-small; whisper entre 10 % y 93 %.
whisper se midio con 30 clips FLEURS por idioma (100 en `en`/`ru`) mas toda la TTS.
Una diferencia grande entre raw y plegado (ZIPA en `ru`/`uk`) es sobre todo la
blandura `ʲ` y la longitud: notacion que el plegado quita y el motor si mira.

### Lo que se ve en las transcripciones

- ZIPA no marca la blandura rusa: "Парижане слывут..." (ref.
  `pʌrʲiʒˈɑnʲi sɭyvˈut`) sale `pəriʐɑnisl̴ɛvut` en zipa-t-small y
  `pʌrʲiʒɑnʲisɭʌvut` en wav2vec2. Por eso en ruso gana wav2vec2 (entrenado con la
  notacion de espeak, que escribe `ʲ` en cada blanda).
- wav2vec2 habla castellano peninsular: "Aerosmith canceló los conciertos" con
  voz latina sale `aɾosmiθ ... konθjeɾtos` (la referencia `es-419` y la voz
  sesean). ZIPA escribe `konsiɛrtos`: `e` como `ɛ` de forma sistematica, que la
  matriz `es` tolera.
- Polaco con zipa-cr-large: "Także zapaśnicy złożyli hołd Lunie" ->
  `tagʐɛzapaɕɲitsɨzwuʐɨlixɔwtluɲɛ` (ref. `tˈaɡʒɛ zˌapaɕɲˈitsɨ zwɔʒˈɨli xˈɔwt lˈuɲʲɛ`),
  casi perfecto; wav2vec2 `tʌɡʒʌzʌpʌɕnʲitsy...`.
- whisper entra en bucles (`ʌnʌnʌnʌn...`, `ˈpɑnəsəl ˈpɑnəsəl...`) y, como es
  seq2seq con modelo de lenguaje, "corrige" hacia la palabra probable: ocultaria
  justo los errores que queremos puntuar. Descartado.
- `pt` y `uk` tienen un PER alto con todos los modelos, en parte por la
  referencia: espeak `pt-br` escribe la -e final atona como `y`, la -a como `æ` y
  la RR como `x`; espeak `uk` escribe la В como `β` y alarga la І (`iː`). Antes de
  medir en serio esos idiomas hay que revisar su referencia (folds tipo
  `SYMBOL_FOLDS`).

### Recomendacion

**Ningun idioma nuevo cumple hoy la regla "un nativo saca >= 98" con un modelo sin
ajustar.** El mejor caso es el espanol con ZIPA: media 96-98 y 56-67 % de los
clips TTS a 98 (38-49 % en FLEURS), lejos del >= 90 %. Para llegar hace falta lo
mismo que en ingles: ajustar el modelo al idioma (y calibrar la matriz).

Idiomas para crear contenido ya, como `experimental` y con nota orientativa (igual
que el ruso hoy), por orden:

1. **es**: el mas cerca con diferencia (con ZIPA). Matriz y motor existen.
2. **fr** y **de**: media 87-92 con ZIPA, casi nada llega a 98; motor listo.
3. **it**, **pl**, **nl**: PER plegado de ZIPA igual o mejor que fr/de
   (0,08-0,33), pero no tienen matriz propia: primero la matriz y las reglas,
   luego medir la nota.
4. **pt** y **uk**: no empezar hasta arreglar su referencia de espeak y volver a
   medir.
5. **ru**: sigue con wav2vec2 multilingue, que en ruso gana a ZIPA.

**Modelo.** Para los idiomas nuevos, **zipa-cr-small** (CTC, 64M, 71 MB int8,
CC-BY-4.0). zipa-t-small (transducer) gana hasta 2,5 puntos en frances y empata
en espanol y aleman, y el large no mejora siendo 4x mas grande y lento. El CTC
pesa mas que esos puntos porque reutiliza lo que el motor ya tiene para wav2vec2
(decodificador greedy, confianza por frame, alineacion forzada); el transducer
obliga a un bucle decoder/joiner nuevo y no da posteriores por frame para alinear.
**Ingles se queda con su modelo ajustado y ruso con wav2vec2.**

**Coste del cambio a ZIPA (solo para los idiomas nuevos):**

- Tamano: 71 MB frente a 358 MB. Cabe en el movil, asi que esos idiomas podrian
  puntuarse en el dispositivo y no solo en el servidor.
- Latencia: ~19 ms por segundo de audio con un hilo (8 con todos) frente a 218 (58).
- RAM: ~160-180 MB del modelo cargado frente a ~510 MB. En el VPS cabe junto a
  los dos wav2vec2 actuales (2,14 GiB de pico, seccion 1) dentro de
  `GLOSSO_SERVER_MEMORY=3072M`.
- Licencia: pesos CC-BY-4.0 (atribucion obligatoria en los creditos y en
  `docs/LICENSING.md`), codigo MIT. Los modelos `-ns` de ZIPA son CC-BY-NC-SA y
  no se pueden usar; en la tabla, zipa-ns apenas mejora a zipa-cr-small salvo en
  polaco y ucraniano.
- Frontend: ZIPA no toma la onda normalizada sino 80 filtros fbank tipo Kaldi;
  hay que portarlo a Kotlin (servidor, Android) y a iOS, con un test de paridad
  contra lhotse. La tasa de frames cambia, lo que afecta a los tiempos por fono.
- Notacion: el vocabulario de ZIPA (`ipa_simplified`, 127 tokens de un simbolo;
  longitud `ː`, blandura `ʲ` y diacriticos como tokens sueltos) no es
  `phoneme_vocab.json` (392 tokens de espeak, con `tʃ`, `aɪ`, `tʲ` como uno solo).
  Hace falta un vocabulario por modelo, que `ModelInventory` resuelva cada fono de
  referencia a una secuencia de tokens ZIPA (no a uno), y un mapeo de lo oido a la
  notacion de espeak que usan matrices y reglas (`TO_ESPEAK` es el punto de
  partida). El cabezal de calidad y la prosodia estan entrenados sobre los tokens
  de wav2vec2 ingles y no se trasladan (hoy ya solo son ingleses).
- Ajuste fino: si se ajusta un modelo por idioma para llegar a 98, uno de 64M es
  mucho mas barato de ajustar y desplegar que uno de 315M.
