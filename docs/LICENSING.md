# Glosso Pro - Limpieza legal y provenance

Desde el 2026-11-10 Glosso se publica como software libre bajo la AGPL-3.0-or-later
(`LICENSE`, con el permiso adicional para las librerias de Google Play en `NOTICE`).
Las secciones que hablan de producto propietario describen la etapa anterior; las
obligaciones de terceros (atribuciones, espeak-ng solo en build, TTS) siguen vigentes.
No es asesoramiento legal: confirmar con abogado de PI antes de publicar.

Estado: **parcialmente resuelto**. Cerrado: provenance de `PhoneticComparator`
(obra propia), NOTICE/LICENSE y corpus (se regenera), atribucion dentro del
artefacto (`shared/src/commonMain/composeResources/files/licenses.json`, seccion 4.c) y espeak-ng
acotado como herramienta de build (seccion 4.b). Pendiente antes de monetizar
(F4): confirmar autoria de las dos identidades git y cubrir ru/zh antes de F5.

---

## 1. Regla base

`glosso-studio` es AGPL-3.0-or-later, pero el autor es el titular del copyright
(identidades `shirobyte42` + `IgnacioLD`). Se puede relicenciar el codigo propio.
`glosso-pro` es una **repo nueva y privada**; no copiar `LICENSE` AGPL, avisos
AGPL, README ni metadata F-Droid.

---

## 2. Reutilizable (propio)

- Codigo Kotlin propio de `shared/` y `androidApp/`.
- DBs `sentences_v11_*.db` (generadas por IA, uso propio).
- Modelo wav2vec2 ONNX + vocab (Apache-2.0).
- Dependencias Kotlin/Android (Apache-2.0 / MIT).
- `data/fix_ipa_word_groups.py` (usa `eng_to_ipa`, MIT).
- `data/espeak_phoneme_whitelists.json` (IDs enteros en el vocab del modelo).

---

## 3. Obligaciones a mantener (permisivas)

- **Apache-2.0:** conservar NOTICE y atribucion; declarar cambios. Aplica a
  Kotlin, kotlinx, Compose/Material3/Navigation, AndroidX, Room, Koin, Ktor,
  Lottie, Google Play In-App Review, Qwen3-TTS, **Kokoro** (solo build-time:
  genera las refs de voz, ver 3.b).
- **MIT:** conservar texto de copyright/licencia. ONNX Runtime, **Chatterbox**
  (build-time, renderiza el audio) y **Perth** (watermarker de Resemble AI que
  Chatterbox incrusta en cada clip).
- **BSD-2-Clause:** CMUdict.
- **Atribucion obligatoria del modelo wav2vec2** (`facebook/wav2vec2-lv-60-espeak-cv-ft`
  / `onnx-community`), Apache-2.0.

---

## 3.c Corpus de fine-tune (investigacion, no entrenado)

Fuentes descargadas para la investigacion del modelo acustico
(`benchmark/finetune/`). Todas permisivas y compatibles con producto propietario;
no se versionan los datos (gitignore), solo la receta y las estadisticas.

| corpus | origen | licencia | uso |
|---|---|---|---|
| Common Voice 27 EN | Mozilla Data Collective, id `cmu5jplf300nwmh07iqvk9leo` | **CC-0** | train (nativos GB+US) y eval no nativa |
| LibriSpeech clean | OpenSLR SLR12 / `openslr/librispeech_asr` | **CC-BY-4.0** | train (nativos US, preparado) |
| speechocean762 | OpenSLR SLR101 / `mispeech/speechocean762` | **CC BY 4.0** | eval/calibracion de no nativos con puntuacion humana a nivel de fonema |

Obligaciones: CC-0 (sin atribucion obligatoria, documentamos provenance igual),
CC-BY-4.0 (atribuir a LibriSpeech/OpenSLR si se distribuye el derivado; aqui solo
se usa en research) y CC BY 4.0 (atribucion de speechocean762). Provenance por
clip en el `provenance.jsonl` de cada manifiesto (provider, dataset id, `path`
original, licencia, fecha).

Correccion: speechocean762 figuraba como Apache-2.0 en el brief y en informes
previos; su pagina canonica (OpenSLR SLR101) lo publica bajo **CC BY 4.0**. Sigue
siendo permisiva y comercial; solo cambia el texto de atribucion. Detalle en 3.d.

---

## 3.d Datos de fonetica multilingue (licencias verificadas 2026-09-23)

Fuentes usadas o planeadas para el scoring multilingue y la calibracion humana
(detalle en `docs/PHON-MULTILINGUAL.md`):

| fuente | licencia | comercial | uso |
| --- | --- | --- | --- |
| `facebook/wav2vec2-lv-60-espeak-cv-ft` | Apache-2.0 | si | modelo base (eSpeak, ~60 idiomas) |
| speechocean762 (OpenSLR SLR101) | **CC BY 4.0** | si, con atribucion | etiquetas humanas por fonema |
| Common Voice 27.0 (Mozilla Data Collective) | CC-0 | si | cobertura de acento/L1 |
| LibriSpeech (OpenSLR SLR12) | CC-BY-4.0 | si | entrenamiento nativo opcional |
| `facebook/mms-1b-all` | CC-BY-NC-4.0 | **no** | **descartado** |
| L2-ARCTIC (TAMU) | CC BY-NC 4.0 | **no** | **descartado** |

L2-ARCTIC es **CC BY-NC 4.0**: sus etiquetas de error por L1 serian utiles, pero
la licencia no comercial lo excluye del producto propietario y no se ha
descargado.

---

## 3.b Cadena de audio (Kokoro -> refs sinteticas -> Chatterbox)

Los clips que reproduce la app se renderizan offline; **ningun motor TTS ni
modelo se empaqueta**. La cadena es:

1. **Kokoro** (Apache-2.0) genera las referencias de voz con
   `tools/content/make_voice_refs.py` (`tools/content/voices/<id>.wav`). Son
   **sinteticas a proposito**: se parte de voces del modelo, no de grabaciones
   de personas reales, para no clonar la voz de nadie.
2. **Chatterbox** (MIT, Resemble AI) clona cada referencia y renderiza el audio
   (`pipeline.py --tts chatterbox`). Cada clip lleva un **watermark implicito de
   Perth** (MIT, Resemble AI) incrustado por Chatterbox: no hay que hacer nada
   para cumplirlo, pero conviene saber que esta ahi (auditable con la libreria
   `perth`).
3. Las muestras de audio del repo (`docs/audio-samples/`) y las refs
   (`tools/content/voices/`) se **pinean** para reproducir exactamente la misma
   voz; se regeneran con `make_voice_refs.py` y se re-renderizan con
   `render_samples.py`. Misma disciplina de atribucion que `NOTICE`.

Enlaces: Kokoro <https://github.com/hexgrad/kokoro> (Apache-2.0), Chatterbox
<https://github.com/resemble-ai/chatterbox> (MIT), Perth
<https://github.com/resemble-ai/Perth> (MIT).

---

## 4. Prohibido / a eliminar

| Componente | Licencia | Accion |
|---|---|---|
| Allosaurus | GPL-3.0 | No incluir codigo ni modelos; eliminar credito en About y referencias en CI/docs |
| ai-pronunciation-trainer | AGPL-3.0 | No copiar/adaptar codigo; auditar `PhoneticComparator`; si hay duda, reimplementar clean-room |
| espeak-ng (si se enlazara) | GPL-3.0 | No enlazar, no empaquetar, no shell out desde codigo distribuible. Ver 4.b |
| L2-ARCTIC | CC BY-NC 4.0 | No descargar ni usar (no comercial). Ver 3.d |
| facebook/mms-1b-all | CC-BY-NC-4.0 | No usar como modelo base (no comercial). Ver 3.d |

---

## 4.b espeak-ng (GPL-3.0): herramienta de build, nunca en el artefacto

Estado: **verificado y acotado**. espeak-ng es una **herramienta de build**; no
se distribuye ni se enlaza, y su salida no es obra derivada.

**Frontera real (comprobada):**

- La app y el server **no llevan la libreria ni el binario**. La app usa el
  **modelo acustico ONNX** (`facebook/wav2vec2-lv-60-espeak-cv-ft`, Apache-2.0)
  afinado con el inventario de fonemas de eSpeak, y **CMUdict** (BSD-2-Clause)
  para el G2P de ingles. Ninguno de los dos es eSpeak.
- El unico consumidor es `tools/content/phonemize.py`, que en **tiempo de build**
  ejecuta `uv run --with espeakng-loader --with phonemizer-fork ...` para
  rellenar el IPA de referencia y construir los `<lang>.lexicon`. Nunca se
  invoca desde codigo que viaje en el APK/AAB, en un jar/aar o en la imagen del
  server.
- El endpoint `/v1/phonemize` del server **no invoca eSpeak en runtime**: recibe
  audio y devuelve el IPA del **reconocedor ONNX**. El camino texto -> IPA usa
  `CmuDictG2P` / `LexiconG2P`, que leen ficheros de datos (CMUdict, lexicon); no
  ejecutan ni enlazan codigo GPL. (GPL-3.0 no tiene clausula de red y el server
  no se distribuye, asi que aunque lo hiciera seria aceptable; el hecho es que
  **no ocurre**.)
- Un guard automatico lo comprueba: `tools/licensing/espeak_boundary_test.py`
  falla si espeak-ng aparece como dependencia de runtime, en el Dockerfile o en
  los assets de un modulo distribuible.

**Por que no es un problema de licencia:**

- eSpeak-ng (GPL-3.0) es un **programa de build**. El IPA y el lexicon que
  produce son **datos** calculados a partir del texto de entrada; no incorporan
  una porcion sustancial del programa GPL, luego no quedan cubiertos por su
  copyleft (posicion habitual de la FSF para la salida de herramientas).
- Se usa porque **el modelo acustico esta afinado en el dialecto de fonemas de
  eSpeak**: la referencia y la transcripcion del modelo comparten inventario, que
  es justo lo que el scorer necesita. Es una decision tecnica, no una dependencia
  de producto.

**Idiomas futuros:** el camino es un **G2P permisivo** en build (Epitran MIT,
Gruut MIT, g2p_en Apache-2.0; ver seccion 6). Mientras solo se genere con
espeak-ng, la acotacion de arriba se mantiene.


---

## 4.c Atribucion dentro del artefacto (pantalla legal)

La pantalla About no puede enlazar al repositorio (es privado), asi que la lista
completa viaja en el APK:

- `shared/src/commonMain/composeResources/files/licenses.json`: cada componente con nombre, autor,
  licencia y texto. Cubre las dependencias del **runtime classpath resuelto**
  (no solo lo declarado a mano): Play Services arrives transitively through
  Google sign-in and ships, so it is attributed. Las 4 fuentes llevan **su propia
  OFL** (cada una con su copyright).
- `shared/src/commonMain/composeResources/files/privacy/privacy-<lang>.md`: politica de privacidad
  por idioma de interfaz (en/es/fr/de); `PrivacyScreen` elige la activa.
- `tools/licensing/collect.py` genera ambos; `collect_test.py` falla si un
  artefacto del runtime no tiene entrada o si el asset se desincroniza.
- El classpath se resuelve con `:androidApp:licensesRuntimeClasspath` (lock
  versionado en `tools/licensing/runtime-classpath.txt`); `preBuild` depende de
  `:androidApp:licensesRuntimeClasspathCheck`, asi que un lock desactualizado
  rompe el build.

---

## 5. Auditoria de provenance

- [x] `PhoneticComparator.kt` **confirmado como obra propia** del titular, asistido
      por IA. Las matrices de similitud y las descripciones de pares minimos son
      heuristicas escritas a mano a partir de hechos foneticos estandar (no
      copyrightables: sonoridad, punto y modo de articulacion). No se copio codigo
      de Allosaurus ni de ai-pronunciation-trainer; esos nombres solo aparecen en
      comentarios que documentan convenciones de simbolos. La declaracion consta
      tambien en la cabecera del propio fichero.
- [x] **Corpus y DBs:** se van a regenerar con contenido nuevo y propio, asi que
      la provenance del corpus antiguo queda descartada para este producto.
- [x] `NOTICE` y `LICENSE` en la raiz: AGPL-3.0-or-later + atribuciones
      Apache-2.0 / MIT / BSD-2-Clause / EPL-1.0 y del modelo wav2vec2
      (`facebook` via `onnx-community`); textos completos en `licenses/`.
- [ ] Confirmar autoria unica del copyright (o cesion firmada) para las dos
      identidades de git de `glosso-studio`.
- [ ] Confirmar cobertura del modelo en ruso y mandarin antes de F5.

---

## 5.b speechocean762 (calibracion, no distribuido)

Corpus usado **solo para calibrar y evaluar la logica de scoring** (banco
`benchmark/arbiter/`): 5000 frases de 250 hablantes no nativos (L1 mandarin) con
puntuacion humana por fonema y palabra de cinco anotadores. **No se usa para
entrenar** (sus etiquetas son de calidad 0-2, no transcripciones) y **no se
distribuye** en el APK, el server ni los assets.

- Origen: OpenSLR SLR101 (`https://www.openslr.org/101/`, tar de 520 MB, 644 MB
  con audio) y el espejo HuggingFace `mispeech/speechocean762` (de donde salen
  los bloques `mispronunciations`). Guardado fuera del repo en
  la A100 (`/opt/ft/work/arbiter/so762/`), no versionado.
- Licencia: la ficha de HuggingFace `mispeech/speechocean762` declara
  **Apache-2.0**; la pagina de OpenSLR SLR101 declara **CC BY 4.0**; el README
  del corpus dice que es libre para uso comercial y no comercial. Ambas son
  permisivas y compatibles con producto propietario. Se anota la discrepancia y
  se conserva la atribucion (Zhang et al., Interspeech 2021).
- Limite: una sola familia de acento (mandarin). Sirve para la **logica** de la
  decision (umbrales, arbitraje), no como cobertura de acentos; para diversidad
  se siguen usando los grupos no nativos de Common Voice.

---

## 6. G2P permisivo (para generar IPA de referencia)

Runtime recognition no necesita espeak-ng. Solo la generacion de IPA de referencia
en tiempo de build.

| Herramienta | Licencia | Idiomas |
|---|---|---|
| g2p_en (Kyubyong) | Apache-2.0 | Ingles (usar en F0) |
| CMUdict | BSD-2-Clause | Ingles (datos) |
| Epitran | MIT | 60+ inc. eng, deu, fra, spa, ita |
| Gruut / gruut-ipa | MIT | en, de, es, fr, it, nl, pt, ru, sv, ar, cs, fa, lb, sw |
| DeepPhonemizer | MIT | multilingue, latin |
| OpenPhonemizer | BSD-3-Clause-Clear | Ingles (espeak-compatible) |

Evitar **en cualquier artefacto distribuible**: `phonemizer` (bootphon), la ruta
espeak de Piper/Kokoro y, en general, cualquier dependencia GPL. En build, el
unico uso es `tools/content/phonemize.py` (ver 4.b); ahi la tabla anterior marca
la sustitucion si algun dia se quiere un pipeline sin GPL ni como herramienta.


Latin/ruso/mandarin tienen huecos: ruso necesita acento lexico (diccionario o
modelo de estres); mandarin necesita pinyin + tonos (diccionario CC-CEDICT revisar
licencia, o G2P propio). Normalizar la salida al inventario del modelo con
`gruut-ipa` o las tablas propias de `PhoneticComparator`.

---

## 7. Renombrado cosmetico "eSpeak" (recomendado)

- `espeak_vocab.json` -> `phoneme_vocab.json`
- `EspeakModelConfig` -> `PhonemeModelConfig`
- `EspeakWav2Vec2Recognizer` -> `Wav2Vec2PhonemeRecognizer`
- `espeakPhonemizer` -> `phonemizerLocale`
- Quitar "eSpeak" de `strings_about.xml` y creditos About
- Mantener la atribucion del modelo (facebook / onnx-community)

---

## 8. Privacidad (nuevo respecto a glosso-studio)

Al usar nube:
- Consentimiento explicito antes de enviar audio.
- **Retencion cero**: procesar y descartar.
- Politica de privacidad y terminos propios (URLs reales, no placeholder).
- La voz es dato sensible: revisar GDPR / consentimiento / transferencias.

---

## 9. Marca y assets (no reutilizar)

Nombre "Glosso Studio"/"Glosso", iconos y logo, `store_assets/`, dominio
`glossostudio.com`, listing de Play, `fastlane/`, `apks/`. Crear branding nuevo
para el producto propietario.

---

## 10. Checklist de lanzamiento propietario

- [ ] Confirmar autoria o cesion de copyright.
- [ ] No copiar LICENSE/README/avisos AGPL.
- [ ] No empaquetar Allosaurus ni creditos GPL.
- [x] No copiar codigo de ai-pronunciation-trainer; `PhoneticComparator` confirmado como propio (ver seccion 5).
- [x] espeak-ng acotado como herramienta de build: no enlazado ni empaquetado, con guard (`tools/licensing/espeak_boundary_test.py`). Ver 4.b.
- [x] Mantener NOTICE Apache-2.0 / MIT y atribucion del modelo (NOTICE + licenses/).
- [x] Verificar provenance del corpus (corpus nuevo, se regenera).
- [ ] Renombrar "eSpeak".
- [ ] Branding nuevo; sin nombre/logo/dominio/assets antiguos.
- [ ] Politica de privacidad, terminos y consentimiento de audio.
- [ ] Revision de abogado de PI antes de publicar.

---

# TTS de terceros: requisito de licencia comercial

**Regla dura:** si Glosso es una app comercial, **los proveedores se filtran por
licencia comercial desde el principio**. Que una API permita generar audio **no**
significa automaticamente que puedas usar ese audio comercialmente.

## Candidatos

| proveedor | uso comercial | notas |
|---|---|---|
| **Cartesia** | **si** (plan Pro incluye licencia de uso comercial explicita) | encaja con la arquitectura de generar -> validar -> guardar -> servir a miles |
| **ElevenLabs** | **si en planes de pago** (conservas derechos sobre el output, sujeto a que tengas derechos sobre el contenido de entrada; su API admite uso comercial) | **no** generar con el plan gratuito el contenido que va dentro de la app |
| **Google Cloud TTS** | si, **sujeto a sus terminos de servicio** | muchas voces, acentos, `speed`, `pitch` y SSML |

Benchmark a hacer antes de decidir (no elegir solo por precio): uso comercial,
calidad, voces britanicas y US, velocidad lenta, control SSML, API, y generacion
masiva. Los tres son candidatos.

## Lo que importa mas que la naturalidad: la consistencia fonetica

En una app que **ensena pronunciacion**, un TTS que pronuncie mal el objetivo es
peor que un TTS poco natural:

> Si generas "three" y el motor produce un /θ/ raro, reducido o confuso, estas
> metiendo mal input en el producto que precisamente ensena ese sonido.

Corpus de prueba antes de adoptar un proveedor (palabras):

`think`, `three`, `this`, `that`, `ship`, `sheep`, `world`, `very`, `water`,
`right`, `light`, `worked`, `asked`

Y frases que contengan esos sonidos. De cada API se mide: naturalidad, claridad,
**pronunciacion correcta del fonema objetivo**, ritmo y **consistencia** (misma
palabra, mismo sonido, siempre).

## Recomendacion

- **Cartesia** como candidato principal.
- **ElevenLabs** como benchmark de calidad.
- **Google Cloud TTS** como tercera alternativa.

## Provenance por clip (obligatorio)

Cada clip del catalogo debe guardar **que proveedor, modelo, voz, version/config
y fecha** lo genero. Asi, si dentro de un ano cambian un modelo o una voz, se sabe
exactamente que contenido hay que regenerar.

Y en el pipeline, un requisito de publicacion:

> **Un clip sin licencia comercial verificable no entra en `content.db`.**

Puede materializarse como `provider_license` / `commercial_license=true` por
proveedor, comprobado por el gate antes de publicar. Esto evita un problema serio
en cuanto Glosso empiece a cobrar.

## Estado actual (contexto)

El audio del catalogo hoy es **Chatterbox (MIT) autohospedado, clonando
referencias generadas con Kokoro (Apache-2.0)**: la cadena mas limpia posible
(no depende de la licencia del output de una API de terceros). Si en el futuro se
migra a un proveedor cloud (por calidad de voces, idiomas o velocidad), aplica
esta regla **antes** de generar nada destinado al catalogo.
