# App iOS: compilar y ejecutar en el simulador

Fase 4 de `docs/IOS-KMP-PLAN.md`. `iosApp/` es un cascaron SwiftUI que muestra
`MainViewController()` (`shared/src/iosMain/.../MainViewController.kt`): toda la UI es la de
`:shared`, como en Android. Solo simulador hasta que exista la cuenta de Apple Developer (sin firma
para dispositivos ni TestFlight). Bundle id provisional `com.glossostudio.glosso`.

## Requisitos

- Xcode (probado con 27.0) y un runtime de simulador iOS.
- JDK 21 (el mismo que el resto del build). La fase de Gradle lo busca en `JAVA_HOME`,
  `/usr/libexec/java_home -v 21` o Homebrew (`openjdk@21`), porque Xcode no hereda el entorno
  del shell.
- `xcodegen` solo para regenerar el proyecto (`brew install xcodegen`).

## Compilar y ejecutar

```sh
cd iosApp
xcodebuild -project Glosso.xcodeproj -scheme Glosso \
  -destination 'platform=iOS Simulator,name=<simulador>' -derivedDataPath /tmp/glosso-ios build
xcrun simctl install <udid> /tmp/glosso-ios/Build/Products/Debug-iphonesimulator/Glosso.app
SIMCTL_CHILD_GLOSSO_MUTE=1 xcrun simctl launch <udid> com.glossostudio.glosso
```

O abrir `iosApp/Glosso.xcodeproj` en Xcode y ejecutar el esquema `Glosso`. Objetivo de despliegue
iOS 16.0: cubre el iPhone XS Max de pruebas (iOS 18) y el minimo de ONNX Runtime (15.1).

## Como se construye el framework

- La fase "Build Shared.framework" (`iosApp/scripts/build-shared.sh`) ejecuta
  `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, que compila `Shared.framework` (estatico)
  para el SDK, la arquitectura y la configuracion que pide Xcode, lo deja en
  `shared/build/xcode-frameworks/<Configuracion>/<SDK>` (donde apunta `FRAMEWORK_SEARCH_PATHS`) y
  copia los recursos de Compose al bundle. La primera vez en un Mac, Gradle descarga ONNX Runtime
  para iOS (`tools/fetch-onnxruntime-ios.sh`, ver `docs/IOS-ENGINE.md`).
- El proyecto se genera con XcodeGen desde `iosApp/project.yml`; `Glosso.xcodeproj` se versiona
  para que un checkout limpio compile solo con `xcodebuild`, pero **no se edita a mano**: se cambia
  `project.yml` y se regenera con `cd iosApp && xcodegen generate`.
- El icono y la pantalla de arranque salen de los vectores de Android:
  `python3 iosApp/scripts/make-brand-assets.py`.
- Los sonidos de acierto y fallo se toman de `shared/src/androidMain/res/raw/` (una sola copia).
- Aviso conocido del enlazador: `libicu.icudtl_dat.o ... built for newer iOS-simulator version
  (18.5)`. Es el bloque de datos ICU de Skiko (sin codigo); el binario de la app declara iOS 16.0.

## API y clave

`iosApp/Configuration/Glosso.xcconfig` fija `GLOSSO_API_BASE` (produccion) y `GLOSSO_API_KEY`
vacia, y por ultimo incluye `Secrets.xcconfig` si existe (gitignorado, modo 600). Info.plist pasa
los dos valores a `IosBuild`. Como en Android (`glosso.apiKey`), la clave es un filtro de abuso y no
autenticacion: con la clave vacia la app no envia cabecera, y hoy `/v1/score` de produccion acepta
peticiones sin clave. Para fijarla sin que el valor pase por la linea de comandos:

```sh
tools/keys/glosso-secret run GLOSSO_API_KEY -- iosApp/scripts/write-secrets.sh
```

(`GLOSSO_API_KEY` todavia no esta en el llavero; hay que guardarla antes con `glosso-secret set`.)
Para apuntar a un servidor local: `GLOSSO_API_BASE=http://localhost:8080 iosApp/scripts/write-secrets.sh`
(Info.plist permite http solo a direcciones locales).

## Interruptores de prueba (solo Debug)

Se pasan en el entorno de lanzamiento con el prefijo `SIMCTL_CHILD_`; una build Release los ignora
(`IosDebugEnv.kt`):

- `GLOSSO_MUTE=1`: todos los reproductores a volumen 0. Usarlo siempre en el simulador: el audio
  del simulador sale por el Mac.
- `GLOSSO_FAKE_WAV=/ruta/toma.wav`: el grabador devuelve ese WAV (16 kHz mono) en lugar del
  microfono, asi una toma real llega a la puntuacion en la nube sin voz.
- `GLOSSO_OFFLINE=1`: la conectividad informa de que no hay red (el simulador no tiene modo avion).

## Que hace y que no hace la app iOS hoy

- Analitica y crash reporter **apagados** (fase 6): `Analytics` nunca se inicializa, Ajustes no
  muestra la seccion de estadisticas y los ganchos de primer/segundo plano no registran ni envian
  nada. No sign-in yet (phase 5).
- On-device scoring as on Android (`IosOnDeviceScorer`, see `docs/IOS-ENGINE.md`): the screens offer
  the model pack, download it or import it from Files, and score offline with the same result as
  the server. `GLOSSO_OFFLINE=1` only fakes connectivity: a reachable server still scores the take
  when the pack is missing.
- Descargas de lecciones y del modelo en primer plano (`ForegroundDownloader`); faltan las de
  `URLSession` en segundo plano.
- El audio del contenido es Ogg Vorbis, que AVFoundation no reproduce: `AudioDevice.ios.kt` lo
  decodifica con stb_vorbis (vendorizado en `shared/src/nativeInterop/stb_vorbis/`, ver su README) a
  WAV de 16 bits con la frecuencia y los canales del clip, fuera del hilo principal, y lo reproduce
  con `AVAudioPlayer` (la escucha lenta cambia el tempo sin cambiar el tono, como en Android). Los
  clips recientes quedan en una cache LRU de 6 MB; un audio de mas de 4 MB de PCM se escribe a un WAV
  temporal en lugar de memoria. Un Ogg truncado o corrupto falla limpio (`audio_failed`).
- En Debug, cada clip deja una traza `I/Audio: ready ...` (decodificado o de cache, Hz, canales,
  duracion) y `I/Audio: done ... after N s at 0.8x (expected M s)`. Con la app silenciada:
  `xcrun simctl spawn <udid> log stream --predicate 'process == "Glosso" AND eventMessage BEGINSWITH "I/Audio"'`.
