# Glosso Content Engine - arquitectura del corpus adaptativo

**Estado:** diseno aprobado por el dueno. **Manana se implementa.** Este documento
es el contrato: fija el modelo mental, el esquema, el pipeline, la economia y el
orden de construccion.

## 0. Principio

No se genera contenido **individual** para cada usuario por defecto. Se construye
**una biblioteca reutilizable** que crece con las necesidades reales de los
usuarios, y se le sirve a cada alumno la mejor pieza disponible para su estado.

El alumno debe **sentir** que el contenido es suyo. Por debajo, el sistema
reutiliza.

Frase que resume el modelo: *Glosso construye continuamente un corpus adaptativo
a partir de las necesidades reales de sus usuarios.*

## 1. El flujo

```
                    CEFR
                      |
              Contenido base (curriculo)
                      |
             Perfil del usuario
                      |
          ¿Que necesita practicar?
                      |
          +-----------+-----------+
          |                       |
      Ya existe                No existe
          |                       |
     seleccionar          generar bajo demanda
          |                       |
       entregar <---- validar ----+
          |
        cachear
```

## 2. Tres tipos de contenido

1. **Curricular (fijo).** A1 / Asking for directions, con su objetivo CEFR.
   Existe independentemente de los usuarios, se genera, se revisa y se versiona.
2. **Adaptativo (frecuente).** Necesidades repetidas de la masa de usuarios:
   `A1 + /θ/ + sustitucion por /t/ + posicion inicial` -> 20-50 ejercicios, que
   despues se reutilizan para todos.
3. **Experimental (dinamico).** Para un caso concreto (p.ej. `/θ/` + pedir
   indicaciones). Se valida y, **si funciona, entra al pool global**:

   > El contenido generado para un usuario puede convertirse en contenido para
   > todos.

## 3. Content Intelligence (la demanda manda)

Los usuarios no solo consumen: **producen la lista de la compra** del contenido.
El sistema agrega errores reales y los convierte en demanda:

```
/θ/ -> 38.421 dificultades
/ɪ/ -> 31.204
/r/ -> 26.819
```

Con eso sabe que necesita mas contenido de `/θ/` sin que nadie se siente a
decidirlo a mano. Panel interno propuesto:

- **Demanda actual**: patron x nivel, ordenado por volumen.
- **Cobertura**: que porcentaje de la demanda esta cubierta por el pool, por nivel.
- **Proxima generacion**: los N ejercicios que faltan, por familia.

Y el bucle de calidad: medir que ejercicios funcionan y subir o bajar su
prioridad (ver seccion 9).

## 4. Content schema (el contrato del generador)

**Regla dura:** el generador **nunca** trabaja libre. Todo encargo es un esquema
explicito:

```yaml
target:        /θ/
error_pattern: TH_TO_T
cefr:          A1
position:      initial
objective:     production
vocabulary:    A1 frequency list
grammar:       present_simple
max_words:     8
required_words: [think, three]
forbidden:     [advanced vocabulary]
```

Sin esquema no hay generacion. Esto es lo que evita la fabrica de contenido
basura.

## 5. Pipeline de generacion

```
generar -> validar -> TTS -> validar audio -> publicar -> cachear
```

1. **Generar** con el esquema de la seccion 4.
2. **Validar texto** con el gate real del proyecto (`:server:publishContent`,
   criterio **0 invalid**): nivel, IPA bien formado (CMUdict), duracion,
   duplicados, inventario de fonemas, y que las `focus words` aparezcan
   literalmente.
3. **TTS** con la politica de audio (seccion 6).
4. **Validar audio**: formato, duracion, no vacio, lento mas largo que normal.
5. **Publicar** (dist + `content.db`) y **cachear**.

## 6. Politica de audio (donde esta el dinero)

El texto y las imagenes son baratos; **el audio es el recurso a controlar**:

- **Una voz principal** por pieza. Las demas voces son **opcionales** y se
  generan solo si aportan variedad real.
- **Variante lenta solo cuando tiene sentido pedagogico** (no por defecto en todo).
- Los clips se **comparten entre usuarios**: nadie "posee" su audio.

## 7. Cadencia y cache

Lo que importa no es la hora, es **la estrategia de cache**:

| caso | accion |
|---|---|
| Existe en el pool | **entrega inmediata** (coste marginal ~0) |
| No existe, pero hay una variante valida | servir otra variante existente |
| No existe y merece la pena | **poner en cola** la generacion (se cachea para todos) |
| Es urgente (el alumno espera) | generacion inmediata, coste de excepcion |

- **Batch diario**: rellenar huecos de lo que la gente pidio ese dia.
- **Batch semanal**: analizar demanda y decidir que **familias nuevas** crear.

## 8. Modelo mental de la API

No:

```python
generate_exercise(user_id)
```

Sino:

```python
get_best_exercise(skill_state, cefr_context)
```

El usuario no posee el ejercicio: el sistema **busca el mejor recurso disponible**
para su estado.

## 9. Aprendizaje del sistema (que ejercicio funciona)

Por cada pieza del pool, medir:

- usuarios que la recibieron,
- completados,
- **repetidos voluntariamente**,
- **mejora media** del fonema objetivo (puntos),
- retencion a 7 dias.

El ejercicio que funciona **sube**; el que no mejora al alumno, **baja o se
descarta**.

## 10. Cost control

Glosso is free for everyone, so there is no tier to price against. Cost is
controlled by demand instead: the existing pool and cached content first, and new
generation only when the measured gap for a Need Class justifies it.

## 11. Economia (numeros medidos en el proyecto)

| pieza | coste medido |
|---|---|
| Imagen (FLUX.2 klein-base-4B + duotone, 20 s en L40S) | ~$0,008 |
| Clip de audio corto (~5 s, Chatterbox) | ~$0,001 |
| Clip largo (40 palabras) | ~$0,005 |
| Drill de una frase (1 voz + lenta) | ~$0,007 |
| Leccion completa (20 frases + imagen + texto) | ~$0,30 |

- **Pool por necesidad** (ejemplo: 44 fonemas x 3 patrones x 20 frases = 2.640
  frases): texto ~$5 + audio ~$26 + imagenes ~$20 = **~$50 una vez**. Coste
  marginal por alumno: **~$0** (solo ancho de banda).
- **Generacion por usuario y dia** (3 drills x 10.000 usuarios): **~$210/dia** e
  inviable. El pool cuesta lo mismo con 100 usuarios que con 10.000.
- Pendiente de medir: el **coste del LLM por leccion** (unico numero que no
  tenemos).

## 12. Que existe ya y que falta

**Existe**: el curriculo A1-B2 (2.879 items), los drills con `focus` fonetico y
pares minimos, el gate de validacion (`publishContent`, 0 invalid), el pipeline de
audio y de imagenes, el `content.db` + `assets`, y en construccion el
**perfil fonetico con tendencias** (directrices 7/8/16).

**Falta**: el `content schema` formalizado, el registro de necesidades (Content
Intelligence), el pool indexado por (fonema, patron, nivel), el selector
`get_best_exercise`, la generacion bajo demanda con validacion, y las metricas de
eficacia por ejercicio.

## 13. Orden de implementacion (manana)

El dueno lo fijo y es el orden correcto: **antes de generar nada, las tres piezas
que evitan la fabrica de basura**.

1. **Content schema** (`docs` + codigo): el contrato de la seccion 4, con test de
   validacion del propio esquema.
2. **Sistema de validacion**: reforzar el gate actual para que sea el juez del
   contenido generado (esquema + CEFR + IPA + focus + duplicados).
3. **Perfil fonetico** (en vuelo): sin estado del alumno no hay demanda que
   agregar.
4. **Registro de necesidades + pool indexado** por (fonema, patron, nivel), con
   el selector `get_best_exercise`.
5. **Generacion bajo demanda** para huecos, con cache y publicacion al pool.
6. **Panel de Content Intelligence** y metricas de eficacia por ejercicio.

**Nada del paso 5 sin los pasos 1-3 cerrados.**

## 14. Guardarrailes (anti basura)

- El generador solo trabaja con esquema explicito (seccion 4).
- Todo pasa el **gate de validacion** antes de existir; nada se publica sin 0
  invalid.
- El pool se **cura**: lo que no mejora al alumno se descarta (seccion 9).
- Nada de contenido generado sin `target` fonetico ni objetivo CEFR.
- La personalizacion no cambia el objetivo CEFR: cambia **como** se practica
  (directriz 15).

## 15. Riesgos

- **Pool que no cubre**: vigilar la cobertura por nivel; si baja, priorizar esa
  familia.
- **Variedad percibida**: si el alumno repite frases, la sensacion de
  personalizacion cae. Mitigacion: variaciones parametrizadas + rotacion y
  batch semanal.
- **Deriva de calidad**: el generador sin esquema produce frases raras; el gate y
  la curacion son la defensa.
- **Coste de audio a escala**: la politica de la seccion 6 es la palanca; hay que
  medirla por familia antes de abrir generacion nueva.

## 16. Provenance y licencia comercial de cada clip (requisito de pipeline)

Todo clip guarda **proveedor, modelo, voz, version/configuracion y fecha** de
generacion, para saber exactamente que regenerar si un modelo o una voz cambian.

Y el pipeline lleva un **gate de licencia**: un clip sin licencia comercial
verificable **no entra en `content.db`** (campo `provider_license` /
`commercial_license` por proveedor). Ver `docs/LICENSING.md` (TTS de terceros),
donde ademas esta el criterio de seleccion de proveedor y el corpus de prueba de
**consistencia fonetica**.

La **cache de audio** (fingerprint por clip y su migracion) se documenta en
`docs/TTS-CACHE.md`: que identidad cubre, por que el flag de huella hashed esta
apagado hoy y los pasos exactos para activarlo cuando termine el render en curso.

Las **imagenes** siguen la misma regla en `content/<lang>/images/provenance.json`:
por cada PNG se registra proveedor, modelo + revision, seed, steps, guidance,
dtype, hash del prompt, version del duotone y fecha. El backend por defecto es
siempre el modelo comercial (FLUX.2 [klein] base 4B, Apache-2.0); un modelo no
comercial exige `--model` explicito, avisa por consola y se registra como
`commercial_license: false`. Las imagenes ya generadas se rellenan con
`images.py --backfill-provenance` y quedan marcadas como `inferred`.


---

## 17. Capa de inferencia y orquestacion (Cloudflare)

**Decision de arquitectura:** no casar Glosso con un unico modelo. Se usa
**Cloudflare como capa de inferencia/orquestacion**, con modelos open-weight
donde tengan sentido y proveedores externos donde aporten calidad real.

```
                    GLOSSO
                       |
                Cloudflare Worker
                       |
                  AI Gateway
                       |
          +------------+------------+
          |            |            |
     Workers AI      OpenAI      Otro proveedor
     open-weight     fallback    (TTS: Cartesia, etc.)
          |
     +----+-----+
     |          |
   Qwen      Gemma / GLM / ...
```

La ventaja clave: **la aplicacion no necesita saber donde se esta ejecutando el
modelo**. El gateway resuelve routing, analitica, cache, rate limiting, retries,
fallback y coste.

### 17.1 Politica de routing (ejemplo)

- Primario: modelo open-weight barato y rapido (**GLM / Gemma**).
- Si falla: **OpenAI** como fallback.
- Si el contenido ya existe: **CACHE** (no se llama a ningun modelo).
- Si es generacion **batch**: **Workers AI**.
- Si es contenido **critico**: modelo premium.

### 17.2 Coste

- Workers AI: asignacion gratuita de **10.000 Neurons/dia**; despues **$0,011 por
  1.000 Neurons** en Workers Paid (desde **$5/mes**).
- Modelos pequenos muy baratos (ejemplo publicado: `llama-3.2-1b-instruct` a
  **$0,027 por millon de tokens de entrada**).
- Para contenido que **se genera una vez y se cachea para miles de usuarios**,
  esto es marginal. Encaja con el modelo economico de la seccion 11.

Modelos a probar (por orden de coste/velocidad, no de potencia): GLM-4.7 Flash
(rapido y multilingue), Gemma 4 26B A4B (open eficiente), Kimi (tareas
complejas). Para generar ejercicios A1/B2 **con restricciones**, empezar por un
modelo pequeno y rapido: no hace falta un modelo enorme.

### 17.3 Lo que NO se hace

Generar en el camino caliente del usuario:

```
Usuario -> Worker -> LLM -> generar -> responder      (mal)
```

Es caro e impredecible. Se hace:

```
Usuario -> Perfil -> Content Engine -> ¿existe ejercicio adecuado?
   Si -> entregar
   No -> generar -> validar -> cachear -> entregar
```

Y si el ejercicio generado funciona bien, **entra al pool global**.

### 17.4 Inferencia en tiempo real de pronunciacion: benchmark primero

**No se asume** que Workers AI sea la mejor opcion para el diagnostico de
pronunciacion (audio -> ASR/alineacion fonetica -> analisis -> diagnostico):
necesita latencia, precision y muy probablemente modelos especializados. Si algun
dia se considera, **benchmark real antes de cambiar arquitectura** por ese
camino. El motor on-device + servidor actual (mismo engine Kotlin) sigue siendo la
base.

### 17.5 Datos y licencias

Cloudflare declara que los inputs/outputs de Workers AI son *Customer Content* y
que no los usa para entrenar sus modelos salvo consentimiento explicito. Aun asi,
**cada modelo puede tener sus propias condiciones/licencia**: revisarlas antes de
meter un modelo en el pipeline del catalogo (misma regla que en
`docs/LICENSING.md` para TTS).
