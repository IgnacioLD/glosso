# Perfil fonetico: memoria por fonema, tendencias, patrones y repeticion

Implementa las directrices 7, 8, 9, 11 y 16 de
`docs/app-review/DIRECTRICES-DISENO.md`. El motor ya pliega un perfil agregado
(`PhoneticProfile.fold`) y `ErrorRules` ya nombra errores; lo que faltaba era la
**memoria por fonema a lo largo del tiempo**, que es la que hace posible un
entrenador adaptativo y no un curso con nota.

## Que se guarda

Dos cosas, con vidas distintas:

- **Agregado** (`PhoneticProfileModel`): sigue siendo el valor que viaja al
  servidor con cada intento, para que el scoring siga siendo stateless. No cambia.
- **Historial** (`PhonemeObservationModel`, solo en el dispositivo): una fila por
  sonido y toma, con fecha. No se envia a la nube, asi que la fecha es el dia
  local del alumno y no el del servidor.

```kotlin
PhonemeObservationModel(
    phoneme = "θ",
    day     = 19_700L,     // epoch day local
    total   = 2,           // veces que aparecio el sonido en la toma
    correct = 0,           // cuantas entraron perfectas
    error   = "t",         // el telefono que salio en su lugar ("(dropped)" si se cayo)
    pattern = "en.th.stopping.t", // nombre curado del error, via ErrorRules
)
```

`pattern` es el **id que expone `ErrorRules`** (`ErrorRules.ruleFor`), sin tocar
su logica de deteccion. Ejemplos: `en.th.stopping` (TH -> S),
`en.th.stopping.t` (TH -> T), `en.h.dropping` (omision). Dos errores distintos del
mismo fonema producen dos patrones distintos; si el contraste no esta curado,
`pattern` es null pero `error` sigue guardando el telefono producido. Esto es el
contrato de la seccion 4 de `docs/CONTENT-ENGINE.md`: el pool se indexa por
`(fonema, patron, nivel)`, y ese par `(phoneme, pattern)` ya sale del perfil. El
nivel CEFR no se conoce en el momento de plegar; se une despues con el contenido.

## Logica pura (engine)

Todo en `engine/src/commonMain/kotlin/com/glosso/engine/profile/`, sin Android y
con tests en `commonTest`:

| Modulo | Directriz | Que hace |
|---|---|---|
| `PhonemeMemory` | 7 | Anade las observaciones de una toma, acota el historial por sonido y deriva el patron. |
| `PhonemeTrends` | 7 | Serie diaria, primera/ultima/mejor nota, delta y direccion (ventanas, no puntos sueltos). |
| `ErrorPatternDetector` | 8 | Solo hay patron con el mismo error 3 veces seguidas; la confianza sube con evidencia y baja si el sonido mejora. |
| `PhonemeScheduler` | 16 | SRS ligero: mejora -> intervalo mas largo (3, 6, 12, 24, 30 dias); empeora o patron -> cada dia. |
| `PracticeRecommender` | 11 | "Tu practica de hoy": `/θ/ 2 min, /ð/ 2 min, frases 1 min`, priorizado por debilidad, patron y tendencia. |
| `SkillProfile` | 9 | Dimensiones con datos reales: pronunciacion y competencias de los ejercicios; no inventa las que no hay. |

El historial se escribe en `ProfileStore.record(profile, alignment)` justo despues
de puntuar (nube u on-device), en `PracticeEngine`. La migracion no pierde nada:
el agregado vive en la misma clave y version (`glosso_profile` / `phonetic_profile`,
v1), y el historial empieza vacio en su propia clave. Los perfiles antiguos siguen
cargando.

## Perfil multidimensional (directriz 9)

`SkillProfile` solo publica una dimension con `>= 5` respuestas o intentos.
- Pronunciacion: del perfil agregado.
- Comprension / vocabulario / gramatica: de `ProgressStore.skills`, alimentado por
  `recordAnswer(correct, skill)` segun el tipo de ejercicio
  (`multiple-choice`/`match` -> vocabulario, `fill-blank`/`order` -> gramatica,
  `true-false` -> comprension).
- Nunca se afirma equivalencia CEFR: son porcentajes de competencia, no niveles.

## Fixture de depuracion (solo debug)

`DemoProfile` fabrica una historia multi-dia documentada y su agregado, y
`MainActivity` la carga con un extra, solo bajo `BuildConfig.DEBUG`. Numeros:
`/θ/` 43, 51, 63, 71 (mejora); `/ð/` 58..60 estable con el mismo error (patron);
`/ɪ/` 64, 68, 66; `/ɹ/` 82, 85; `/æ/` 95, 92.

```sh
./gradlew :androidApp:assembleDebug
adb -s emulator-5570 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5570 shell cmd locale set-app-locales com.glosso.app --locales es
adb -s emulator-5570 shell am force-stop com.glosso.app
adb -s emulator-5570 shell am start -n com.glosso.app/.MainActivity --ez seed_profile_demo true
# toca "Tu" en la barra inferior
```

El camino real (grabar y puntuar) escribe las mismas filas via
`ProfileStore.record`; el fixture solo existe para ver la tendencia sin esperar
semanas de tomas.
