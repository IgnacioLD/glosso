# Capa de decision del scoring (shared-inventory quality head)

Estado 2026-09-23. Portada a produccion desde `benchmark/phon-multilingual`
(PR #52). El hallazgo que la manda: el fine-tune acustico no arregla la
separacion nativos vs no nativos (ft_v1 0,4890 vs produccion 0,4637, los dos por
debajo de 0,5). El cuello de botella es la **decision**, no la acustica.

## Que se porta

Tres cosas, y las tres importan:

1. **Normalizacion dentro de la locucion.** Los features absolutos (GOP bruto,
   posterior, maxpost) llevan sesgo de hablante, canal, volumen y duracion y
   **invierten** al transferir entre corpus (AUC 0,49). Normalizar cada feature
   respecto a la propia locucion del aprendiz los elimina: es lo que separa.
   Features del fonema: GOP normalizado, rango de GOP, margen, entropia
   normalizada, log de frames y `producido == objetivo`.
2. **Agregacion por peor-N fonemas.** El score de la locucion es la media de las
   3 peores probabilidades por fonema. La media global diluye el acento (AUC
   0,44); el peor-3 lo concentra.
3. **Cabeza logistica congelada** con sus coeficientes y con provenance, no
   numeros sueltos. Ver `QualityHead.kt`.

Vive en `engine/src/commonMain/kotlin/com/glosso/engine/phonetics/quality/`:
`PhonemeClass` (clases articulatorias), `QualityFeatures` (features por fonema,
port de `align.py`), `QualityHead` (cabeza congelada) y `PronunciationQuality`
(orquestacion + peor-3). Es `commonMain`, asi que el **mismo motor** sirve al
servidor JVM y a la app Android.

## Provenance de la cabeza congelada

- Fuente: `benchmark/phon-multilingual/finalize.py`, modelo `logreg`, PR #52.
- Entrenada sobre speechocean762 (CC BY 4.0), split `l2_calib` (2.500
  locuciones, 125 hablantes Mandarin-L1): Adam full-batch, lr 0,05, l2 3e-3,
  500 epocas, seed 7, pesos balanceados por clase.
- Entradas: 6 features normalizadas + one-hot de 8 clases + one-hot del
  vocabulario de produccion (392 tokens) + sesgo = 407 coeficientes.
- Reproducibilidad: reconstruir la cabeza desde los features congelados con
  `finalize.fit_logreg` reproduce **todos** los coeficientes con error 0,0; la
  cabeza que se porta es exactamente la medida.

## Validacion honesta (sin fuga)

La review de #52 marco **bloqueante** por **fuga de seleccion**: la agregacion
(worst3 0,6092, worst5 0,6036, worst8 0,5881) y la familia de features se
eligieron mirando el AUC sobre el mismo test de Common Voice donde se reporta.
Es el maximo de varias configuraciones sobre los mismos datos, no un numero
honesto. **No hay fuga de etiquetas**: la cabeza entrena en so762 y nunca ve
Common Voice; el conjunto de test so762 es disjunto por hablante (125/125
hablantes, solape 0).

Lo que si se ha hecho aqui es medir fuera de muestra con un split **disjunto por
hablante**: se elige la configuracion en una mitad de hablantes y se mide en la
otra, repetido con 60 semillas. El numero reportado es el AUC fuera de muestra
de la configuracion **elegida**, no el maximo.

Scripts: `benchmark/phon-multilingual/honest_eval.py` (seleccion de agregacion)
y `honest_select.py` (seleccion conjunta familia x agregacion). Features
congelados: `features_{so762_l2_calib,so762_l2_test,cv_test}.jsonl` en la caja.

Salidas y checksums comiteados (antes no, y por eso el numero no era auditable):

- `out/honest_eval.json` (200 semillas): `selected_config` **0,5989**,
  `production_holdout` 0,4662, `ftv1_holdout` 0,4932.
- `out/honest_select.json` (60 semillas): `selected_holdout` **0,5911**,
  `production_holdout` 0,4682, `ftv1_holdout` 0,4946.

Los baselines de produccion/ft_v1 cambian segun la particion: aqui son 0,4662 /
0,4682 y 0,4932 / 0,4946, mientras la ruta de `honest_split.py` / `REPORT.md`
reporta 0,4581 / 0,4854 para el **mismo** baseline sobre su split con reserva.
Cita el baseline que acompane a la cabeza que cites; no los cruces
(`docs/PHON-MULTILINGUAL.md` seccion 5, auditoria de motor 2026-09-24, seccion 2).
- `out/eval_production_scores.json` y `out/eval_ftv1_scores.json`: los scores
  E2E por clip que alimentan `--baseline`/`--candidate`, para que los holdouts
  de produccion y ft_v1 se puedan recalcular.
- `out/feature-checksums.sha256`: sha256 de los features, el vocab y los evals
  que no se comitean (~40 MB). `REPRODUCE.md` da los comandos exactos y el
  entorno (revision del modelo `ae45363b`, torch 2.11, transformers 5.17).

| configuracion | momento | AUC separacion CV |
| --- | --- | ---: |
| 0,6092 del informe | max sobre los mismos datos | **optimista** |
| normalized + worst3 (fijo), head portado | fuera de muestra (200 semillas) | **0,598** |
| familia x agregacion (elegida en el split, medida fuera) | fuera de muestra (60 semillas) | **0,591** (mediana 0,595; p5 0,544) |
| produccion baseline (mismos holdouts) | fuera de muestra | 0,468 |
| ft_v1 baseline (mismos holdouts) | fuera de muestra | 0,495 |

El titular honesto del **head portado** es 0,598 (`honest_eval.py`); el 0,591 es
la seleccion conjunta de familia x agregacion (`honest_select.py`), que mide un
diseno distinto del artefacto que se envia. No confundirlos.

**Esto es un paso intermedio, no un arreglo.** La separacion sube de 0,468
(produccion) a ~0,60, pero el objetivo declarado (>0,65) **no se cumple**. Los
L1 casi nativos (german, malaysian, singaporean, slavic) siguen cerca de 0,5: la
senal fonetica por si sola no los separa y falta la palanca de
**prosodia/fluidez**. La fuga era de ~0,018 AUC; el mecanismo se sostiene fuera
de muestra, lo que cambia es la magnitud, no la conclusion.

## Tokenizacion de la referencia (bug corregido)

El benchmark entrena y mide con el diftongo como **un** token (`aɪ`, `eɪ`, `oʊ`,
`aʊ`, `ɔɪ`), la convencion del vocabulario del modelo (`common.py:arpabet_to_ipa`).
El port lo alimentaba con `PhoneticComparator.phonesOf`, que divide el diftongo en
sus dos vocales para el scorer de fonemas: el Viterbi y la cabeza recibian dos
tokens donde el benchmark usaba uno, con otro `token_id` en el one-hot, otro
`match` y otra normalizacion de GOP/entropia. El numero honesto no describia lo
que corria.

Arreglo: `PhoneticComparator.modelPhones` construye la referencia en la
convencion del modelo (diftongo y africada como un token) y el reconocedor alinea
**su propia** referencia sobre los mismos frames para la cabeza, sin tocar los
timings de prosodia (que siguen con la convencion partida del scorer).
`ModelInventory` unifica la resolucion al vocabulario (`common.py:token_candidates`)
y la comparten el reconocedor y el test.

La divergencia era la misma en las tres convenciones de referencia: ARPAbet->IPA
(`Arpabet.kt`, `AY -> aɪ`), IPA autoral y la salida de espeak-ng en ingles
(verificado: `time -> taɪm`, `day -> deɪ`, `go -> ɡoʊ`). Las tres producen el
diftongo como un simbolo y `phonesOf` lo partia igual.

`QualityParityTest` ya no recibe la referencia prefabricada: la reconstruye desde
`refIpa` con `modelPhones` -> `ModelInventory.resolveAll` -> `ForcedAligner.align`
y la compara con el golden, que ahora lleva diftongos y africadas. Si la
tokenizacion vuelve a divergir, el test falla (verificado por mutacion: con
`keepDiphthongs=false` fallan 3 de 4 tests).

## Paridad con el Python

`engine/src/jvmTest/kotlin/.../quality/QualityParityTest.kt` reproduce sobre una
matriz de probabilidades determinista y el vocabulario real de 392 tokens:
features crudos por fonema, GOP normalizado, rango, margen, entropia, frames,
token producido, probabilidad por fonema y score peor-3. El golden lo genera
`benchmark/phon-multilingual/gen_quality_fixtures.py` con el propio `align.py` y
`finalize.py`. Tolerancias explicitas: 1e-4 en GOP/gopt/entropia, 1e-5 en
margen/posterior/maxpost/calidad, 1e-9 en rango, exacto en frames y token. Los
coeficientes congelados se comprueban aparte con 1e-9.

## Contrato aditivo en `/v1/score`

No cambia el contrato ni la escala actual: se anade
`PronunciationQualityModel?` a `PronunciationFeedback`, con `score` (media del
peor-3, 0..1), `worst` (hasta 3 fonemas, peor primero), `phones` (todos con su
desglose) y `head` (id de la cabeza congelada). Solo se rellena en la ruta de
audio con referencia alineable; en la ruta por IPA o cuando no hay alineacion
queda `null`. La senal `fluency`/`acoustics` existente no se toca.

## Clip corto, silencioso o con poco habla

La capa no inventa senal:

- El reconocedor ya rechaza silencio, clips demasiado cortos y demasiado
  silenciosos con `evaluated = false`; no se llega a la capa.
- Si el audio no se puede alinear (referencia sin tokens, o menos frames que
  fonemas), la capa devuelve `null` y el score normal sigue su curso.
- Con 1 o 2 fonemas, el peor-3 usa los que hay; `gopRank` es 0 si hay uno solo;
  con desviacion cero se usa 1,0 (mismo `x.std() or 1.0` del Python).
- Un vocabulario distinto de 392 tokens (modelo no soportado) se rechaza con
  `null`, no se adivina.
- Cualquier excepcion en la capa se captura en el reconocedor y solo anula la
  senal.

Tests: `PronunciationQualityTest` (commonTest) cubre vacio, vocabulario
desconocido, span colapsado, un solo fonema y frames degenerados;
`QualityScoringRouteTest` (server) cubre la ruta de audio, la de IPA y la
ausencia de alineacion.

## Lo que no se toca

Identity (`me.shirobyte42.glosso`) and the acoustic model. No new dependencies.
