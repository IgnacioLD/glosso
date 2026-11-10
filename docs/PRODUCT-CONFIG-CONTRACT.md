# Contrato de producto: config, planes, entitlements y flags

Documento CONGELADO. Los tres streams implementan ESTE contrato y no lo cambian por su cuenta.
Si algo no encaja, se avisa al orquestador en vez de improvisar.

## 1. Autoridad

El backend es la autoridad de: fase del producto, fecha de beta, plan, entitlements, Beta Founder,
feature flags y version minima. El cliente NO decide nada de esto y nunca lo escribe.

## 2. Identidad del usuario (decision congelada)

Hoy no hay cuentas. La identidad es el DISPOSITIVO:

- La app genera un UUID en el primer arranque y lo persiste en SharedPreferences (`glosso_device_id`).
- La app lo manda en TODAS las llamadas relevantes como cabecera `X-Device-Id`.
- El servidor ya tiene `glosso.users (device_id unique, created_at)`. La primera vez = alta de usuario.
- Cuentas de verdad (email/OAuth) llegan despues; cuando lleguen, se VINCULAN al usuario existente
  y el `beta_founder` se hereda. Disenar la tabla para que ese vinculo sea posible (columna `account_id` nullable).

## 3. GET /v1/config (respuesta exacta)

```json
{
  "schema": 1,
  "mode": "BETA",
  "beta": {
    "active": true,
    "endDate": "2027-12-31T23:59:59Z",
    "founderEnabled": true,
    "founderBenefit": { "kind": "PRO_LIFETIME", "value": null, "until": null, "label": "Glosso Pro gratis para siempre" },
    "founderUntil": "2027-12-31T23:59:59Z"
  },
  "plans": {
    "FREE":         { "pronunciation_analysis": "LIMITED", "exercises": "LIMITED", "analytics": "BASIC",   "personalization": "NONE", "personalized_learning": false },
    "PRO":          { "pronunciation_analysis": "UNLIMITED", "exercises": "EXTENDED", "analytics": "ADVANCED", "personalization": "NONE", "personalized_learning": false },
    "PERSONALIZED": { "pronunciation_analysis": "UNLIMITED", "exercises": "UNLIMITED", "analytics": "ADVANCED", "personalization": "FULL", "personalized_learning": true }
  },
  "limits": {
    "FREE": { "pronunciation_analysis_per_day": 20, "history_days": 30 }
  },
  "pricing": {
    "PRO":          { "monthly": null, "yearly": null, "currency": null },
    "PERSONALIZED": { "monthly": null, "yearly": null, "currency": null }
  },
  "flags": {
    "beta_banner": true, "pro": false, "personalized": false, "accounts": false,
    "adaptive_exercises": false, "advanced_analytics": false
  },
  "client": { "minimumSupportedVersion": 2210, "recommendedVersion": 2219, "forceUpdate": false }
}
```

Reglas del contrato:
- `mode` es la fase activa: `BETA` | `FREE`. `PRO`/`PERSONALIZED` son planes, no fases; un usuario puede ser
  PRO estando el producto en fase FREE.
- `pricing` con `null` = precio no decidido: la UI NO muestra precio y NO vende. Nunca inventar cifras.
- `flags` sin clave = `false`. Un flag nuevo no requiere release de app.
- `limits` es opcional por plan; lo que no este, no se limita.
- `endDate` es la UNICA fuente de la fecha. En la app solo puede existir como texto formateado desde este valor.
- Toda la config se sirve con `Cache-Control: no-cache` + `ETag`, patron identico a `/v1/content/manifest`.

## 4. Entitlements y validacion server-side

- Tabla nueva `glosso.entitlements`: `user_id`, `plan` (FREE/PRO/PERSONALIZED), `status`
  (NONE/ACTIVE/GRACE/CANCELED/PENDING/TRIAL), `source` (beta/pro/play/manual), `expires_at`, `started_at`,
  `auto_renew`, `provider`, `provider_token_hash`, `updated_at`.
- El plan efectivo de un usuario lo calcula SIEMPRE el servidor y lo expone en `GET /v1/me` (autenticado por
  `X-Device-Id`): `{ "plan", "status", "betaFounder", "betaFounderSince", "betaFounderBenefit", "expiresAt" }`.
- El cliente cachea esa respuesta y la usa para la UI, pero la decision final de acceso a contenido
  premium es del servidor. Un APK viejo con `isPro=true` local no habilita nada.
- Beta Founder: se marca en `glosso.users` si la fecha de alta es <= `beta.founderUntil` (enmienda 2,
  seccion 8; antes era `beta_end_date`), y es PERMANENTE (columna `beta_founder boolean`, `beta_founder_since`, `beta_founder_until`, `beta_founder_benefit`).
  Nunca se borra ni se recalcula a la baja.

## 5. Transicion beta -> produccion (reglas)

- Al pasar `beta_end_date`, `mode` cambia a `FREE` por configuracion. NO se bloquea a nadie, NO se cobra solo.
- Usuario nuevo despues de la beta: `FREE`.
- Beta Founder: `PRO` (contenido) gratis, **sin `PERSONALIZED`**. El estatus se mantiene aunque cambie
  de plan; nunca concede el plan superior.
- Usuario con suscripcion Play valida: `PRO`. Con personalizacion contratada: `PERSONALIZED`.
- Nunca se borran cuentas, progreso ni historial de pronunciacion. La migracion es solo de plan.

## 6. Config centralizada (una sola fuente)

Variables de entorno del servidor (nombres congelados), leidas UNA vez en `ProductConfig.fromEnv()`:
`GLOSSO_PRODUCT_MODE`, `GLOSSO_BETA_END_DATE`, `GLOSSO_BETA_FOUNDER_ENABLED`,
`GLOSSO_BETA_FOUNDER_BENEFIT_KIND`, `GLOSSO_BETA_FOUNDER_BENEFIT_VALUE`,
`GLOSSO_MIN_CLIENT_VERSION`, `GLOSSO_RECOMMENDED_CLIENT_VERSION`,
`GLOSSO_LIMITS_JSON`, `GLOSSO_PRICING_JSON`
y `GLOSSO_FLAG_<NOMBRE>` para cada feature flag (por ejemplo `GLOSSO_FLAG_PRO=false`).
`endDate` en ISO-8601 UTC. `GLOSSO_LIMITS_JSON` y `GLOSSO_PRICING_JSON` llevan el JSON crudo de
`limits` y `pricing` (null si faltan o no parsean). Valores por defecto seguros en codigo si falta
la variable.

## 7. Enmienda 1: tiers, estatus y lo que NO se decide hoy

- Son TRES planes: `FREE`, `PRO`, `PERSONALIZED`. El plan superior de suscripcion es `PERSONALIZED`
  (la app completa, que se adapta al usuario). No hay cuarto plan.
- `Beta Founder` NO es un plan: es un ESTATUS ortogonal al plan. En `/v1/me` son DOS campos
  independientes (`plan`, `betaFounder`); nunca se anidan ni se mezclan. El estatus se conserva aunque
  cambie de plan.
- A efectos de acceso, un Beta Founder recibe el plan `PRO` (contenido) gratis: el servidor reporta
  `plan=PRO` mientras no tenga un plan superior contratado. El estatus de founder NUNCA concede
  `PERSONALIZED`, ni siquiera cuando ese plan exista.
- Que funcionalidad concreta entra en cada plan NO se decide ahora. `limits`, `pricing` y
  `beta.founderBenefit.value` van a `null` (= por decidir) hasta que la beta diga que se usa de verdad.
  La arquitectura debe permitir fijarlos desde configuracion, sin release de app.
- El plan `PERSONALIZED` se construira sobre datos reales de uso de la beta, no sobre una lista de
  funcionalidades inventada. No implementar entrenamiento adaptativo ficticio para "llenar" el plan.

## 8. Enmienda 2: beta hasta 2027, Beta Founder = PRO de por vida, fase por fecha

Decisiones del dueno (2026-09-25). Sustituyen lo que contradigan de las secciones anteriores (fechas 2026,
`discount_percent` como beneficio, founder marcado por `beta_end_date`). Todo lo servido es ADITIVO: ningun
campo existente cambia de nombre ni de tipo, `schema` sigue en `1` y una app que ignora claves desconocidas
no se rompe.

### 8.1 Dos fechas, dos claves independientes

- `beta.endDate` (`GLOSSO_BETA_END_DATE`, defecto `2027-12-31T23:59:59Z`): fin de la beta GRATUITA. Hasta
  entonces TODOS los usuarios, fundadores o no, tienen acceso completo y gratis (incluido lo que luego sera
  `PERSONALIZED`), sin anuncios. Copy: "Gratis hasta el 31 de diciembre de 2027."
- `beta.founderUntil` (`GLOSSO_BETA_FOUNDER_UNTIL`, defecto `2027-12-31T23:59:59Z`): fin de la ventana de
  Beta Founder. Toda alta <= `founderUntil` (con `founderEnabled`) es fundador, para siempre. Beta Founder =
  todo el que use la app durante la beta.
- Comparten valor por defecto pero son DOS claves: ninguna controla a la otra y el codigo no las fusiona.
  Solo hay defaults seguros en `ProductConfig`; el valor real se lee de config.

### 8.2 La fase sigue el calendario

- `mode` servido = `BETA` solo si `GLOSSO_PRODUCT_MODE=BETA` y ahora <= `endDate`; si no, `FREE`.
  `beta.active` igual. `flags.beta_banner`, si no se fija explicitamente, sigue a `beta.active`.
- La beta se cierra sola al pasar la fecha, sin reiniciar (el ETag cambia). `GLOSSO_PRODUCT_MODE=FREE` la
  cierra antes. `mode` sigue siendo solo `BETA` | `FREE`: `PRO` y `PERSONALIZED` son planes (seccion 3).

### 8.3 Beta Founder = PRO gratis de por vida, como pago unico (no suscripcion)

- El beneficio es `beta.founderBenefit.kind = "PRO_LIFETIME"` (`GLOSSO_BETA_FOUNDER_BENEFIT_KIND`, defecto):
  acceso GRATUITO y PERMANENTE al plan `PRO`. NUNCA incluye `PERSONALIZED` ("Pro+"): ese se paga. Copy:
  "Como Beta Founder tendras Glosso Pro gratis para siempre. El plan Personalizado no esta incluido."
- Se modela como **lifetime grant** (compra unica), no como suscripcion: tabla `glosso.lifetime_grants`
  (`user_id`, `plan`, `source`, `provider`, `provider_ref_hash`, `granted_at`, `revoked_at`,
  `revoke_reason`), sin expiracion, sin renovacion, sin periodo de gracia y sin RTDN. Solo se revoca a mano
  por fraude explicito (`revoked_at`), y una revocacion no se deshace sola.
- El del fundador es un grant con `source = "beta_founder"`. La misma abstraccion sirve para un futuro
  producto de pago unico (p.ej. "Pro de por vida" comprado: `source = "purchase"` + su proveedor).
- Se persiste al marcar al fundador (y la migracion V5 lo crea para los fundadores ya marcados). Cambiar
  despues la config no se lo quita a nadie. Otro `kind` de beneficio no concede plan.
- `glosso.users.beta_founder_benefit` guarda el `kind` concedido (`PRO_LIFETIME`), no la etiqueta.
- `founderBenefit.value` y `founderBenefit.until` quedan en `null`: no hay porcentaje y no caduca.
  `GLOSSO_BETA_FOUNDER_BENEFIT_LABEL` fija la etiqueta.
- Esto sustituye a la regla de la enmienda 1 "el servidor reporta `plan=PRO` para un founder": ahora ese
  PRO sale del grant de por vida.

### 8.4 Plan efectivo: suscripcion y pago unico son ortogonales

- Plan efectivo = el mayor entre la suscripcion en vigor (`glosso.entitlements`, Play Billing) y los grants
  de por vida no revocados. `FREE` < `PRO` < `PERSONALIZED`.
- Founder que ademas se suscribe a `PERSONALIZED` -> `PERSONALIZED`; si la suscripcion caduca vuelve a su
  `PRO` de por vida, nunca a `FREE`. Founder que se suscribe a `PRO` sigue `PRO` (gana el de por vida).
  Nunca pierde la condicion de fundador.
- Transicion post-beta: usuario nuevo -> `FREE`; Beta Founder -> `PRO` gratis de por vida (sin
  `PERSONALIZED`); suscriptor Pro -> `PRO`; suscriptor Personalized -> `PERSONALIZED`. Nunca se borran
  datos, historial ni estado.

### 8.5 `GET /v1/me` anade (todos opcionales para el cliente)

- `planSource`: `LIFETIME` | `SUBSCRIPTION` | `FREE`. Con `LIFETIME` la UI muestra "acceso permanente" y
  NUNCA renovacion ni cancelacion. `status` y `expiresAt` describen el plan solo si es `SUBSCRIPTION`; con
  `LIFETIME` van `NONE` y `null`.
- `lifetimeGrants`: `[{ "plan", "source", "grantedAt" }]`.
- `subscription`: `{ status, provider, source, startedAt, expiresAt, autoRenew }` de la suscripcion en
  vigor, o `null`. `CANCELED` + `autoRenew=false` = cancelada pero pagada hasta `expiresAt`; `GRACE` =
  renovacion fallida aun con acceso. Restaurar en otro dispositivo es un nuevo `POST /v1/billing/verify`.
- `betaAccess`: `true` mientras la beta gratuita esta abierta.
- `entitlements`: lo que el servidor concede AHORA. En beta, la fila de `PERSONALIZED` (acceso completo);
  despues, la fila de `plan`. Los `flags` siguen decidiendo que funcionalidades existen.
- `betaFounderUntil`: reservado; hoy `null` (la ventaja no caduca).
- `linkedAccounts`: proveedores externos vinculados a la cuenta (hoy solo `google`), nunca el identificador.
  Si la cuenta se fue a otro movil, este dispositivo es un usuario anonimo nuevo y llega `[]`: la app olvida
  su estado local de "vinculada a ...".

### 8.6 Entitlements por plan, flags y version

- `GLOSSO_PLANS_JSON` sobrescribe la matriz de `plans` clave a clave sobre los defaults (cambiar valores,
  anadir claves; ninguna clave por defecto desaparece; solo existen los tres planes). Clave nueva
  `personalized_learning` (bool; `true` solo en `PERSONALIZED` por defecto).
- `GLOSSO_FORCE_UPDATE` (defecto `false`); `recommendedVersion` nunca queda por debajo de
  `minimumSupportedVersion`. Durante la beta no se fuerza a nadie.
- Una variable vacia cuenta como no definida (`docker compose` inyecta `""`).
- Variables nuevas (se suman a la seccion 6): `GLOSSO_BETA_FOUNDER_UNTIL`,
  `GLOSSO_BETA_FOUNDER_BENEFIT_LABEL`, `GLOSSO_FORCE_UPDATE`, `GLOSSO_PLANS_JSON`.
