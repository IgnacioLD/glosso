# Glosso Pro - cache de audio TTS (fingerprint y migracion)

El pipeline cachea cada clip en `content/<lang>/audio/.cache.json`. La clave es un
**fingerprint**: si cambia cualquier entrada del render, el clip se vuelve a
generar en vez de reutilizarse. Un fingerprint incompleto reutiliza audio viejo en
silencio, que es la misma clase de bug que el bloqueante de PR #22 (un "miss" de
cache tratado como "hit").

Contrato de ficheros que esta cache **no** cambia: `<name>.<voicekey>[.slow]`,
`<id>.p<i>.<voicekey>`, OGG Vorbis 24 kHz mono.

## Las dos huellas

### Legacy (la que se usa hoy)

```
sha1("{text}|{voice}|{speed}{tag}")
  tag = ""                                       # kokoro
  tag = "|{backend}|{LEGACY_PROFILE_VERSION}"    # chatterbox y demas
  LEGACY_PROFILE_VERSION = "glosso-intonation-v1"
```

No cubre el checkpoint ni la referencia de voz: cambiar `--model-dir` o regenerar
`voices/*.wav` **no** invalida la cache.

### Hashed (lista, apagada)

```
sha1("|".join([text, voice, speed, backend, PROFILE_VERSION,
               model_revision, ref_revision]))
  PROFILE_VERSION = PROFILE_HASH     # derivado de los knobs (intonation.py)
  model_revision  = identidad del checkpoint: commit HF del arbol local si esta,
                    o hash nombre:tamano de sus ficheros. Sale de --model-dir.
  ref_revision    = sha1 del contenido de refs/<voice>.wav (12 hex) o "no-ref".
```

Cubre todo lo que cambia el audio. Se usa **solo** cuando el flag esta activo.

## El flag

`tools/content/intonation.py`:

```python
USE_HASHED_PROFILE_VERSION = False
PROFILE_VERSION = PROFILE_HASH if USE_HASHED_PROFILE_VERSION else LEGACY_PROFILE_VERSION
```

Esta **apagado a proposito**: hay un render de ~6.000 clips en vuelo, lanzado con
la huella legacy. Activarlo antes de que termine invalidaria la cache y habria que
re-renderizar todo. El flag se lee por instancia (`Tts.hashed_profile`), asi que un
proceso en curso no se ve afectado aunque se edite el fichero.

Verificado en `naming_test`:

- con el flag en `False`, `_fingerprint` es `_legacy_fingerprint` y sale
  **byte-identico** al de antes de esta rama;
- con el flag en `True`, cambiar el checkpoint o regenerar la referencia cambia la
  huella (y sigue siendo determinista).

## Activacion (solo cuando el render haya terminado)

El orden importa: **primero el flag, luego migrar**. Al reves no sirve: migrar con
el flag apagado reescribe la huella legacy (no-op) y activar despues invalidaria
toda la cache.

0. Confirma que no queda ningun render en marcha y que el proceso hizo `flush()`.
1. Copia de seguridad de `content/en/audio/.cache.json` y `.provenance.json`.
2. Pon `USE_HASHED_PROFILE_VERSION = True` en `tools/content/intonation.py`.
3. Migra con **exactamente** los mismos flags del render (sobre todo
   `--model-dir` y `--refs-dir`):

   ```sh
   /opt/gen/venv-tts/bin/python tools/content/pipeline.py --language en \
       --tts chatterbox --device cuda \
       --model-dir /opt/gen/models/chatterbox \
       --refs-dir tools/content/voices \
       --source <dist> --out <content/en> \
       --audio-kinds drills,dialogues,words,stories,articles \
       --voices en-GB-female,en-GB-male \
       --migrate-cache
   ```

   No carga torch ni toca la GPU: solo reetiqueta la cache y reconstruye
   `content.db` y los mapas de audio.
4. Mira el resumen final: `skipped 0` es lo esperado. Si hay skips, revisa la
   seccion siguiente antes de dar la migracion por buena.
5. Commit del flag (junto con este documento si no lo estaba).

## Migracion segura (`--migrate-cache`)

Antes reetiquetaba a ciegas cualquier clip existente. Ahora `Tts._can_migrate`
solo reetiqueta un clip si:

- su huella guardada es exactamente la legacy (o ya la hashed) para ese
  texto/voz/velocidad, **y**
- el sidecar `.provenance.json` coincide en `provider`, `model` (checkpoint),
  `voice`, `profile`, y en `ref` si el registro la trae.

Si algo no cuadra, el clip **no** se reetiqueta, **no** se mapea en `content.db`
y se cuenta como `skipped`: se re-renderiza en el siguiente render, nunca se sirve
audio dudoso. Es **idempotente**: repetirlo sobre una cache ya migrada no hace
nada.

Se saltan, por ejemplo: cache ausente o de otro motor/perfil, clip sin sidecar,
provenance de otro checkpoint, o de otra referencia de voz.

### Limites a tener en cuenta

- **Mismos flags que el render.** El `model_revision` que se compara es el que
  paso `--model-dir`; si el render lo paso y la migracion no (o al reves), todos
  los clips se saltan.
- **No regeneres `voices/*.wav` entre el render y la migracion.** Migrar solo es
  seguro si las referencias no cambiaron; si cambiaron, los clips quedan
  obsoletos y toca re-renderizar.
- **Pasa siempre `--model-dir`.** Si se omite, `model_revision` es `"unknown"` y
  la huella no distingue un checkpoint de otro. El comando de render ya lo pasa.

## Rollback

No hay vuelta automatica: tras migrar, la cache guarda huellas hashed y poner el
flag en `False` haria miss en todo. Para volver atras: restaura los backups del
paso 1 y deja el flag en `False`. Sin backup, toca re-render.

## Cross-machine

Ambas maquinas usan el mismo checkpoint y las referencias pinned, asi que la
huella hashed no depende de la maquina: unir los `.cache.json` de dos GPUs sigue
siendo seguro.

## Tests

- `tools/content/naming_test.py`:
  - `test_hashed_fingerprint`: flag off => byte-identico al legacy e insensible al
    checkpoint/referencia; flag on => cambia con checkpoint y con referencia.
  - `test_migrate_cache`: solo migra lo verificable, salta cache/provenance que no
    cuadran, y es idempotente.
- `tools/content/intonation_test.py`: el tag hashed es distinto del legacy,
  determinista, depende del perfil, y el flag sigue apagado.
