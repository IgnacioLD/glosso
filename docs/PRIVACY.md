# Privacidad y analitica - Glosso Pro

Resumen de que datos trata el producto y como declararlo en Google Play. No es
asesoramiento legal.

## Audio

- El audio se envia al servidor **solo** para reconocer y puntuar.
- Se procesa en memoria y **se descarta al terminar la peticion**. No se guarda
  ni se registra ni se reutiliza.
- En modo offline (fase posterior) el audio no sale del dispositivo.

## Analitica first-party (`/v1/events`)

Recogemos eventos de producto en nuestra propia base de datos (Postgres,
`glosso.analytics_event`). **No se comparte con terceros.**

| Dato | Ejemplo | Por que |
|---|---|---|
| Identificador aleatorio de instalacion | UUID aleatorio creado al primer arranque | Contar dispositivos unicos |
| Id de sesion | UUID aleatorio | Agrupar eventos de una sesion |
| Version de app / plataforma | `1.0.0`, `android` | Compatibilidad |
| Idioma practicado | `en_GB` | Mejora del curso |
| Nombre y propiedades del evento | `score_attempt`, `score`, `level` | Analizar uso y dificultad |

**No recogemos**: Advertising ID, IMEI, MAC, `ANDROID_ID` como identificador
estable, modelo ni marca del dispositivo, ubicacion precisa, contactos, SMS,
texto libre del usuario, mensajes de error, ni audio.

El servidor **no almacena la IP** en la tabla de eventos. Los eventos de servidor
(`score_attempt`, `503`, errores) se registran igual, sin audio.

## Cuenta: entrar con Google (opcional)

Sirve para llevarse la cuenta (y el estatus Beta Founder) a un movil nuevo. Es
opcional; sin ello la app funciona igual.

- **Entrar con Google**: el usuario elige una cuenta de Google del movil (Android
  Credential Manager). Solo se pide la identidad: el servidor verifica el ID
  token y guarda **unicamente** el identificador opaco que Google asigna a la
  cuenta (`sub`). **No** guarda el correo, el nombre, la foto ni el token, y no
  pide acceso a Gmail, Drive ni a ningun otro servicio. El correo solo se
  muestra en el propio movil.

## Retencion

- Configurable con `GLOSSO_ANALYTICS_RETENTION_DAYS` (por defecto **180 dias**).
- Se purga al arrancar el servidor.

## Seguridad

- **TLS obligatorio**: la app solo debe hablar con el endpoint por HTTPS.
- `/metrics`, `/stats`, `/admin/events` y `/v1/events/summary` estan protegidos
  con token (`GLOSSO_ADMIN_TOKEN`); sin token quedan cerrados.
- Limite de tamano de eventos y de audio; headers de seguridad (HSTS,
  `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`).

## Declaracion en Play Console (Data safety)

- **Data collected / Personal info / User IDs**: si, solo si el usuario entra
  con Google (opcional): el identificador de la cuenta de Google.
- **Data collected / App activity**: "Other actions" -> si (eventos de uso).
- **Data collected / Device or other IDs**: "Device or other IDs" -> si
  (identificador aleatorio de instalacion).
- **Data collected / App info and performance**: "Crash logs" y "Diagnostics"
  -> si, desde la app 2.5.4 (tipo de error, lugar del codigo y pila tecnica de llamadas
  sin mensajes ni datos del usuario; velocidad de la puntuacion, version de Android, movil o tableta).
  No compartido, opcional, para analitica y funcionamiento de la app.
- **Shared with third parties**: no.
- **Encrypted in transit**: si.
- **Data deletion**: ofrecer un canal (soporte) y, si aplica, boton in-app para
  resetear el id y borrar los datos asociados.

## Tu decision

- Las estadisticas de uso estan activadas por defecto y el onboarding lo dice.
  Se pueden desactivar en cualquier momento en Ajustes > Estadisticas de uso; la
  app funciona exactamente igual sin ellas y el servidor deja de registrar
  tambien el evento de puntuacion de ese dispositivo.
- "Borrar mis datos de uso", en el mismo sitio, borra todos los eventos de ese
  dispositivo en nuestro servidor.
- Los eventos que manda la app: app abierta, duracion de la sesion, onboarding
  empezado y terminado, leccion empezada, terminada o abandonada, ejercicio
  terminado (tipo, franja de nota de 10 puntos, dominado o no), intento de
  pronunciacion puntuado en el movil (franja de nota, dominado o no, ids de las
  reglas de error), permiso de microfono denegado, muro de pago visto, boton de
  Pro pulsado y compra empezada o fallida.

Desde la app 2.5.4, la app manda ademas:

- Al abrir la app, el contexto: version de Android, movil o tableta, modo
  oscuro si o no, si esta instalado el modelo opcional en el movil, idioma de
  la interfaz y acento elegido. Nunca el modelo, la marca ni un identificador
  de hardware.
- La velocidad de la puntuacion (milisegundos) y si se puntuo en el movil.
- Pantallas vistas (de una lista cerrada), pasos de leccion alcanzados, audio
  reproducido (que tipo de audio, normal o lento, y escuchar tu propia
  grabacion, nunca la grabacion), resultados de "Tu turno" y del repaso
  (recordada, parcial, no es esa frase, mostrada), historias abiertas y cuanto
  se leyo (porcentaje), y la duracion de la racha diaria.
- Ajustes cambiados (cual y su nuevo valor: idioma que aprendes, acento,
  idioma de la interfaz, transcripcion fonetica, traducciones, recordatorio
  diario, descargas solo con wifi).
- Descargas de lecciones o del modelo (empezada, terminada o fallida, tamano
  en MB, codigo de error corto) y modelo borrado.
- Errores visibles por tipo (puntuacion fallida, sin conexion, no evaluado,
  microfono, contenido, audio) e informes de cierres: el tipo de error, el lugar
  del codigo de la app donde ocurrio y la pila tecnica de llamadas (clase,
  metodo, fichero y linea), enviados en el siguiente arranque en caso de cierre fatal;
  nunca el mensaje del error, el contenido ni nada que escribas.
- Permiso de notificaciones concedido o no y resultado de entrar con Google
  (correcto, cancelado, sin cuenta, fallido).

Todo con las mismas reglas: solo valores si/no, numeros acotados e ids, nunca
texto libre ni audio; se conserva 180 dias y "Borrar mis datos de uso" lo
borra todo, informes de cierres incluidos. El servidor descarta cualquier
otro evento.
