# Declaracion en Play Console (Data safety)

Referencia para rellenar Play Console. La politica que ven los usuarios esta en `docs/PRIVACY.md`.

- **Data collected / Personal info / User IDs**: si, solo si el usuario entra
  con Google (opcional): el identificador de la cuenta de Google.
- **Data collected / Audio**: procesado de forma efimera para puntuar, no se guarda.
- **Data collected / App activity**: "Other actions" -> si, opcional (eventos de
  uso, solo tras aceptar las estadisticas).
- **Data collected / Device or other IDs**: si, identificador aleatorio de
  instalacion.
- **Data collected / App info and performance**: "Crash logs" y "Diagnostics"
  -> si, opcional, solo tras aceptar las estadisticas (tipo de error, lugar del
  codigo y pila tecnica de llamadas sin mensajes ni datos del usuario; velocidad
  de la puntuacion, version de Android, movil o tableta).
- **Shared with third parties**: no. Cloudflare y Hostinger actuan como
  encargados del tratamiento, lo que Play no considera compartir.
- **Encrypted in transit**: si.
- **Data deletion**: boton en Ajustes para borrar la cuenta y los datos, y
  solicitud por email a privacy@glossostudio.com.
