# Cabeceras de seguridad del frontend (glossostudio.com)

El sitio vive en el repo **`glosso-studio-website`** (Hugo). Esta carpeta deja la
configuracion lista para no tocar la rama del rediseno en vuelo; se copia al repo
de la web cuando toque.

## Que aplicar

1. Copiar `_headers` a `static/_headers` en `glosso-studio-website`. Hugo lo
   publica en la raiz y Cloudflare Pages lo aplica. Si el sitio no esta en Pages,
   poner los mismos valores en una regla de Cloudflare (**Transform Rules >
   Modify Response Header**) o en el servidor que lo sirva.
2. La API debe permitir CORS **solo** desde el origen del sitio. En
   `deploy/.env.example` ya esta el valor de produccion:
   `GLOSSO_CORS_ORIGINS=https://glossostudio.com`. Si `www` tambien llama a la
   API, anadir `,https://www.glossostudio.com`. **Nunca `*`.**
3. `www.glossostudio.com` (525): es mapeo del panel de Cloudflare, no del repo.
   Ver `glosso-studio-website/docs/www-503-fix.md`.

## Cabeceras

| Cabecera | Valor | Nota |
|---|---|---|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Sin `preload` (requiere envio manual a la lista). |
| `X-Content-Type-Options` | `nosniff` | |
| `X-Frame-Options` | `DENY` | + `frame-ancestors 'none'` en CSP. |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=()` | El microfono **solo** en `/demo/*`. |
| `Content-Security-Policy` | ver `_headers` | `style-src 'unsafe-inline'` por los `style="..."` inline del tema. |

## Cuidado con el CSP

No romper el sitio ni la demo. El CSP de `_headers` cubre lo que hay hoy:
fuentes y CSS propios (con SRI), el script de Umami autoalojado
(`umami.glossostudio.com`) y la demo llamando a `api.glossostudio.com`.

- Si el rediseno anade un recurso externo, hay que anadirlo a la directiva
  correspondiente o el navegador lo bloqueara.
- Primera puesta en produccion: se puede lanzar como
  `Content-Security-Policy-Report-Only` un dia y mirar la consola del navegador.
- El `_headers` da el microfono solo a `/demo/*`. Ajustar la ruta si la demo
  final usa otro prefijo.

## Prueba

```sh
curl -sSI https://glossostudio.com/ | grep -Ei 'strict-transport|x-content-type|referrer|permissions-policy|content-security'
```
