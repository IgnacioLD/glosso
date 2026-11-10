# Engine en iOS: reconocedor ONNX con la misma nota que el servidor

Fase 2 de `docs/IOS-KMP-PLAN.md` (decision 3). El reconocedor acustico corre en iOS con el mismo
modelo (`wav2vec2_phoneme_int8_dyn_pertensor.onnx`) y el mismo codigo de puntuacion que el servidor y
Android.

## Que es comun y que es de cada plataforma

| Pieza | Donde | Plataformas |
| --- | --- | --- |
| WAV, recorte de silencio, puertas de `SpeechGate`, normalizacion | `commonMain` `Wav2Vec2Audio` | todas |
| CTC voraz, confianzas por token, alineado forzado, prosodia, capa de calidad | `commonMain` `Wav2Vec2Decoder` | todas |
| Parser RIFF (`WavParser.kt`) | `commonMain` | todas |
| Cargar el modelo y ejecutarlo | `onnxMain` `Wav2Vec2PhonemeRecognizer` (API Java de ONNX Runtime) | JVM, Android |
| Cargar el modelo y ejecutarlo | `iosMain` `Wav2Vec2PhonemeRecognizer` (API C por cinterop) | iosArm64, iosSimulatorArm64 |

Las dos clases de plataforma solo crean la sesion (opciones por defecto en los dos lados), pasan
`input_values [1, n]` y copian los logits `[1, T, V]`. Todo lo demas es el mismo codigo Kotlin, asi que
con los mismos logits la nota es la misma. La version iOS se construye con rutas (`String`), no
`java.io.File`: `Wav2Vec2PhonemeRecognizer(modelPath, vocabPath)` o `fromPack(dir)`.

## ONNX Runtime para iOS

- **Version 1.30.0**, la misma que Android (`onnxruntime-android`). El servidor sigue en 1.20.0
  (`onnxruntime-jvm`; la 1.30.0 de la JVM da SIGSEGV en el VPS, ver
  `docs/DEPLOY-AMERICAN-RECALL-2026-10-01.md`).
- **Origen:** el paquete oficial de Microsoft `onnxruntime-c` (el que publica para CocoaPods),
  `https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.30.0.zip`, fijado por SHA-256 en
  `tools/fetch-onnxruntime-ios.sh`. Licencia MIT (Microsoft Corporation), catalogada como
  `native:onnxruntime-ios` en `tools/licensing/collect.py`.
- **Nunca se versiona.** El script lo deja en `data/onnxruntime-ios/1.30.0/` (gitignored): cabeceras,
  `ios-arm64/libonnxruntime.a` y `ios-simulator-arm64/libonnxruntime.a` (corte arm64 del binario
  universal del simulador). `:engine` ejecuta el script antes del cinterop (`fetchOnnxRuntimeIos`), asi
  que un build en Mac no necesita ningun paso a mano. Otra ruta: `-Pglosso.onnxruntimeIosDir=...`.
- **Enlazado:** el cinterop (`engine/src/nativeInterop/cinterop/onnxruntime.def`) mete la libreria
  estatica en el klib y declara `-lc++` y los frameworks `Foundation`, `CoreML` y `Network`, asi que
  `:shared` y la app la enlazan sin flags propios. El `.def` lleva un shim C minimo (abrir sesion,
  ejecutar, liberar) para no recorrer la tabla de punteros de `OrtApi` desde Kotlin.
- **iOS minimo:** el binario de ONNX Runtime pide **iOS 15.1** (`MinimumOSVersion` del framework,
  guardado tambien en `data/onnxruntime-ios/1.30.0/MINIMUM_IOS_VERSION`); Kotlin/Native compila para
  15.0. El minimo efectivo es 15.1, asi que un iPhone XS Max con iOS 18 entra de sobra. El target de
  la app en Xcode debe ser >= 15.1.
- **Linux/Docker:** los targets iOS solo existen en macOS (`glosso.ios`), asi que la imagen del
  servidor no ve ni el cinterop ni la descarga.

## Paridad con el servidor

```sh
tools/fetch-assets.sh                                   # modelo + vocab + cmudict
tools/score-parity/run-ios-parity.sh --simulator <nombre-o-udid>
```

Ejecuta los mismos clips por el reconocedor JVM (el camino del servidor) y por el de iOS en el
simulador (`RecognizerParityHarness`, en `commonTest`), con la cadena entera de `/v1/score`
(`calculateScoringResult` con variantes, `StressAnalyzer`, `CheapScorer`, `FeedbackAssembler`), y
compara con `tools/score-parity/compare-ios.py`: nota, veredicto, transcripcion, confianzas y alineado
(bits exactos), el JSON entero de la respuesta y los logits. Falla si cambia cualquier nota, veredicto
o transcripcion. Clips: los del banco (`golden-ids.txt` y `native-ids.txt`, 49 unicos) mas las cuatro
fixtures de `androidApp/src/androidTest/assets/score-parity/`, que ademas se comparan con el golden
sacado del servidor real.

Resultado (2026-10-06, simulador iOS 27, Mac Apple Silicon):

| Comparacion | Notas iguales | Transcripciones | Confianzas y logits |
| --- | --- | --- | --- |
| iOS (ORT 1.30.0) vs JVM del servidor (ORT 1.20.0) | 49/49, diferencia max 0 | 46/46 | difieren: logits hasta 8,46, confianza de token hasta 0,208 |
| iOS (ORT 1.30.0) vs JVM con ORT 1.30.0 (prueba local, sin commitear) | 49/49 | 46/46 | identicos bit a bit en 47/47 logits, confianzas y alineado |
| Fixtures vs golden del servidor real | 4/4 (good 100 PERFECT, silencio, ruido y bajo "no evaluable") | igual | - |

Lectura: **el codigo iOS es exacto**; la unica fuente de diferencia en los numeros intermedios es la
version de ONNX Runtime (1.20.0 en el servidor, 1.30.0 en Android e iOS), que ya existia entre el
servidor y Android. Con la misma version, las unicas diferencias del JSON completo son un
`quality.margin` de 3,5e-18 (un ulp de `exp`/`ln` entre la libm de Apple y la JVM; la capa de calidad es
informativa y no toca la nota) y el formato decimal con el que Kotlin/Native imprime algunos `Float`
(mismos bits).

## Rendimiento en el simulador

Simulador iOS 27 en Mac Apple Silicon (arm64 nativo, sin limites de memoria de un iPhone; los numeros
de dispositivo real vendran despues):

| Medida | Valor |
| --- | --- |
| Carga del modelo (proceso nuevo) | ~0,67-0,78 s |
| Inferencia + decodificacion, una toma de 2,2 s | ~0,13-0,16 s |
| Inferencia, mediana de los 77 clips del banco | ~0,16-0,21 s (max ~0,9 s, frases largas) |
| `phys_footprint` antes de cargar / cargado / tras una toma | 8,5 MB / 281 MB / 307 MB |
| Pico de `phys_footprint` (cargar + una toma) | 419 MB |
| `phys_footprint` estable, 120 tomas seguidas de 5,2 s | ~547 MB, plano desde la toma ~30 (sin fugas) |
| `phys_footprint` tras los 77 clips del banco (varias longitudes) | ~780 MB |

La memoria crece por escalones hasta el tamano que pide la toma mas larga (arena de ONNX Runtime y
asignador de Kotlin/Native) y luego se queda plana; no crece toma a toma.

`phys_footprint` es la cifra con la que iOS decide matar una app por memoria. Con 4 GB (iPhone XS Max)
el limite de una app en primer plano ronda la mitad de la RAM, asi que 420-780 MB solo para el modelo
deja margen, pero no mucho junto a la UI: hay que confirmarlo en el dispositivo y liberar el modelo
cuando no se use.

## Lo que falta para enchufarlo en la app

1. En `:shared` `iosMain`, una implementacion de `OnDeviceScoring` que sustituya a `NoOnDeviceScoring`:
   crea `Wav2Vec2PhonemeRecognizer.fromPack(<Application Support>/scoring)` y repite lo que hace
   `OnDeviceScorer` en Android (variantes, `StressAnalyzer`, `CheapScorer`, `FeedbackAssembler`).
2. Descarga del paquete del modelo a Application Support (excluido de la copia de iCloud con
   `NSURLIsExcludedFromBackupKey`), con la misma comprobacion de sha256 que Android.
3. Cargar el modelo fuera del hilo principal (`Dispatchers.Default`) y liberarlo con `close()` cuando la
   app pase a segundo plano si la memoria aprieta.
4. Proyecto Xcode con deployment target >= 15.1.
5. Medir carga, inferencia y pico de memoria en el iPhone XS Max real.
