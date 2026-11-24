# Recursos graficos de la ficha de Play

Los graficos que la ficha de Glosso necesita en Play Console, generados de
forma reproducible desde el emulador: capturas de telefono y tablet (en, es, fr
y de), el icono de la ficha y el grafico destacado. El crudo y lo compuesto
viven bajo `docs/store/`.

El **texto** de la ficha (titulo, descripciones por locale) es otra cosa y vive en
`docs/store/README.md` + `docs/store/store-listing.csv`; este documento es solo de
los graficos.

## Que hay en `docs/store/`

| ruta | que es |
|---|---|
| `screenshots/raw/<idioma>/` | crudo del emulador, 1080x2340, sin UI de debug |
| `screenshots/raw-tablets/<dispositivo>/<idioma>/` | crudo de tablet (7" y 10") |
| `screenshots/framed/<idioma>/` | capturas disenadas del telefono, 1080x1920 |
| `screenshots/framed-7in/<idioma>/` | capturas disenadas 7", 1200x1920 |
| `screenshots/framed-10in/<idioma>/` | capturas disenadas 10", 1600x2560 |
| `icon-512.png` | icono de la ficha, 512x512 |
| `feature-1024x500.png` | grafico destacado (en) |
| `feature-1024x500.es.png` | grafico destacado (es) |
| `feature-1024x500.fr.png` | grafico destacado (fr) |
| `feature-1024x500.de.png` | grafico destacado (de) |

Las capturas disenadas son la pantalla real de la app dentro de un marco de movil
dibujado a mano, sobre el fondo de marca, con un titular en Bricolage Grotesque y
Plus Jakarta Sans (las fuentes de la app). El titular cambia por idioma.

## Como se regenera

### 1. Build de captura

El modo de captura oculta la UI de debug (barra de fixtures, atajo de seed del
onboarding, comparador de modelos) y anade un unico objetivo invisible en la
cabecera de practica para inyectar una toma puntuada, porque el emulador no tiene
microfono. Solo debug, apagado por defecto:

```sh
./gradlew :androidApp:assembleDebug -PglossoStoreScreenshots=true -PglossoDebugReleaseSigning=true
adb -s emulator-5568 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

### 2. Capturar el crudo

```sh
python3 tools/store/capture.py --serial emulator-5568 --locales en,es,fr,de --device phone
python3 tools/store/capture.py --serial emulator-5568 --locales en,es,fr,de --device tablet7
python3 tools/store/capture.py --serial emulator-5568 --locales en,es,fr,de --device tablet10
```

El script configura el emulador (resolucion, densidad, tema claro, bateria al
100%, animaciones a 0, volumen de media a 0), limpia los datos de la
app, fija el idioma de la app (`cmd locale set-app-locales`), pone el acento
americano, siembra el perfil demo (`--ez seed_profile_demo true`) y recorre las
pantallas. En telefono abre ademas la leccion del dia: captura su dialogo y, tras
fijar el paso guardado de la leccion (`run-as`, solo debug), el paso que sigue a
los ejercicios. Ese paso es "Tu turno" solo con la interfaz en espanol (los
dialogos solo estan traducidos al espanol); en los otros idiomas es el ejercicio
de pronunciacion, y el titular de `compose.py` lo refleja. En todos los
dispositivos termina en el curso de ruso: cambia el idioma objetivo, guarda una
leccion como la que esta en curso y la abre desde Hoy, para capturar el paso
Contexto (las notas de cultura de "Cinco rosas para Masha", con el ejemplo que
no se dice tachado) y el dialogo de "Entre amigas: Привет y Пока" con la
traduccion visible. Los dialogos se desplazan hasta dejar el boton de
traduccion bajo la etiqueta del paso, para que el encuadre sea igual en los
cuatro idiomas aunque el titulo ocupe una o dos lineas. Requiere la red: la
app es cloud-first, y sin servidor las pantallas de contenido salen con "Couldn't
load".

### 3. Componer

```sh
python3 tools/store/compose.py            # todo
python3 tools/store/compose.py --only phones,icon,feature
```

Usa `rsvg-convert` con un `fontconfig` temporal que expone solo las fuentes de la
app, de modo que los pesos de las fuentes variables salen como en la app. No hay
arte de terceros: el marco es un rectangulo redondeado con la pantalla recortada
dentro.


## Requisitos

- `adb` en el PATH y un emulador permitido (`5558`, `5560`, `5562`, `5568`,
  `5570`), por ejemplo `emulator -avd <avd> -port 5568`. Nunca el movil del dueno.
- `rsvg-convert`, `magick` y `python3`.
- Build de captura instalada (paso 1).

## Checklist de Play

Formato y limites de Play Console (revisar contra la ayuda vigente antes de
subir):

- [ ] **Icono**: `icon-512.png`, 512x512, PNG de 32 bits, sin esquinas
      redondeadas ni insignias (Play aplica su propia mascara).
- [ ] **Grafico destacado**: `feature-1024x500.png` (y `.es.png`, `.fr.png`,
      `.de.png`), 1024x500, opaco (sin transparencia), sin insignias ni botones de
      "instalar".
- [ ] **Telefono**: 2 a 8 capturas de `screenshots/framed/<idioma>/`, 1080x1920
      (9:16).
- [ ] **Tablet 7"**: 2 a 8 capturas de `screenshots/framed-7in/<idioma>/`,
      1200x1920.
- [ ] **Tablet 10"**: 2 a 8 capturas de `screenshots/framed-10in/<idioma>/`,
      1600x2560.
- [ ] **Idiomas**: subir el juego `en`, el `es`, el `fr` y el `de` en su ficha de
      idioma.
- [ ] **Contenido**: sin promesas ajenas a la app, sin precios ni "gratis para
      siempre" en el grafico, sin capturas de dispositivos de terceros.
- [ ] **Textos de la ficha**: titulo, descripcion corta y larga alineados con
      `docs/PRODUCT_LIFECYCLE.md` y `docs/PRODUCT-CONFIG.md` (fase beta).

## Notas

- Las capturas se toman en tema claro (dia). El tema tinta es la misma app; si
  hace falta un juego oscuro, repetir la captura forzando `ui_night_mode=2`.
- El crudo incluye la barra de estado y, en tablet, la taskbar del emulador; la
  composicion recorta ambas para que no aparezca nada que no sea la app.

## Texto de Play

`fastlane/metadata/android/` es la ficha de F-Droid. La de Play tiene textos
propios, copiados tal como estan publicados en `docs/store/play-listing/<locale>/`.
Para subir: una carpeta de `supply` con esos textos, `changelogs/<versionCode>.txt`
de fastlane y `images/phoneScreenshots/` desde `framed/<idioma>/`.
