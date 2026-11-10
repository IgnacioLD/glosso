# Glosso - Suscripcion con Google Play Billing

Estado: **capa de suscripcion implementada y compilando; la compra real NO esta
verificada de extremo a extremo**. Ver la seccion de honestidad antes de dar nada
por bueno. El detalle de que se probo de verdad (fake en emulador + tests) y los
pasos exactos que quedan para el dueno estan en `docs/BILLING-E2E.md`.

## Que hay implementado

- **Play Billing Library** en `:androidApp` (`com.android.billingclient:billing`,
  version en `gradle/libs.versions.toml`).
- **Suscripcion gestionada** leida de Play: base plan mas oferta, con el
  `offerToken` de la oferta (no el base plan a secas) para que el mes de prueba se
  aplique de verdad. El precio y el periodo de prueba salen siempre de Play
  (`queryProductDetailsAsync`), nunca estan escritos a mano.
- **Capa separada**: `SubscriptionRepository` (interfaz) con
  `PlaySubscriptionRepository` (real) y `FakeSubscriptionRepository`
  (desarrollo/tests). Estado como enum:
  `NONE / TRIAL / ACTIVE / GRACE / CANCELED / PENDING`.
- **Ciclo de vida**: conexion y reconexion con backoff, `queryPurchasesAsync` al
  arrancar y en cada `ON_RESUME` (restaurar y revalidar), `launchBillingFlow` con
  el `offerToken`, `acknowledgePurchase` (obligatorio: sin reconocer, Play
  reembolsa a los 3 dias), compras `PENDING` (pago diferido) y errores de red como
  estado, sin romper la app.

### Reglas de resolucion de estado

- Una compra `PURCHASED` **gana siempre** a una `PENDING`: una compra pagada nunca
  se oculta bajo un pago pendiente, y se reconoce siempre que no lo este.
- `TRIAL` y `ACTIVE` solo se distinguen **cuando las fases de la oferta ya estan
  cargadas**. Si el estado se resuelve antes, se espera y se recalcula al llegar
  los detalles, para no etiquetar una prueba como `ACTIVE`.
- `PENDING` no da entitlement; con una compra pendiente el CTA del paywall queda
  deshabilitado y se pide completar el pago en Google Play.
- No se envian parametros de reemplazo o upgrade/downgrade: el flujo solo lleva el
  `offerToken`, asi que no puede cambiar prorrateos de una suscripcion existente
  por accidente.
- Un error se limpia en cuanto la operacion se recupera (conexion, detalles,
  consulta de compras) o cuando el usuario reintenta (`refresh`).
- **Paywall** (`PaywallSheet.kt`): plan con "1 mes gratis, luego X al mes" a partir
  de los datos de Play, CTA, restaurar compras, enlace para gestionar/cancelar en
  Google Play y aviso legal de renovacion automatica.
- **Gate real** en un unico sitio (`billing/AccessPolicy.kt` + `billing/AccessController.kt`):
  gratis = todo A1 (lecciones, historias, ejercicios y drills A1) mas el banco de
  practica libre general; Pro = A2/B1/B2, la practica personalizada y el perfil
  fonetico. La frontera es el nivel CEFR y nunca cae dentro de una leccion: si una
  leccion se puede empezar gratis, se puede terminar gratis, y una leccion ya
  empezada se puede continuar siempre. Las descargas offline espejan la misma
  frontera.
- **Por que la personalizacion es Pro.** El paywall ya promete *"Practice and
  phonetic profile included"*: si el plan personalizado y el perfil fonetico
  quedaran abiertos, la promesa no entregaria nada. Y justo eso es lo que un
  alumno no puede montar por su cuenta: su plan (derivado de su perfil) y su mapa
  fonetico. El banco de practica libre general (A1) si queda gratis, como gancho
  para probar sin limites.
- **Toda superficie pasa por el mismo gate.** Cada sitio que abre contenido
  (lecciones, historias, ejercicios, practica, perfil y Hoy) pregunta a
  `AccessController`, que decide por item (cualquier `ContentMeta` por su nivel) o
  por funcion (`ProFeature`). Ninguna pantalla re-deriva la frontera; un test de
  auditoria de fuentes (`ContentGateTest`) falla si aparece un CTA que abra
  contenido sin el gate.

### Ficheros

| Fichero | Papel |
| --- | --- |
| `androidApp/.../billing/SubscriptionStatus.kt` | El enum y la regla de entitlement |
| `androidApp/.../billing/SubscriptionPlan.kt` | La oferta tal como la devuelve Play |
| `androidApp/.../billing/BillingPeriods.kt` | ISO-8601 (`P1M`, `P7D`) a duracion |
| `androidApp/.../billing/BillingText.kt` | Duracion y oferta en el idioma de la interfaz |
| `androidApp/.../billing/SubscriptionRepository.kt` | La interfaz |
| `androidApp/.../billing/PlaySubscriptionRepository.kt` | Implementacion real |
| `androidApp/src/debug/.../billing/FakeSubscriptionRepository.kt` | Doble de desarrollo, **solo debug** (no se empaqueta en release) |
| `androidApp/.../billing/AccessPolicy.kt` | Que es gratis y que es de pago |
| `androidApp/.../billing/BillingProvider.kt` | Elige real o fake (`debugFakeBilling` por source set) |
| `androidApp/.../SubscriptionViewModel.kt` | Estado para la UI |
| `androidApp/.../PaywallSheet.kt` | La hoja de pago |

## Honestidad: que se puede y que no se puede probar

- **No se puede probar una compra real en local.** Google no ofrece dobles
  oficiales y `launchBillingFlow` solo funciona con la app instalada desde un
  track de pruebas de Play y una cuenta en *license testers*.
- Por eso existe el **fake**: en `debug` el flujo completo (paywall, CTA, gate de
  lecciones y de descargas, estados) se ejercita sin Play. Lo que el fake **no**
  prueba es la integracion con Google (conexion, compra, reconocimiento,
  restaurar en un dispositivo real). Eso queda **pendiente de validar en un track
  de pruebas**.
- La deteccion de `TRIAL` en el cliente se estima con la hora de compra mas la
  duracion de la prueba leida de Play. `GRACE` no se puede ver desde el cliente:
  la fija la verificacion en servidor (fase 2) o el fake.

## Configuracion de build

| Propiedad Gradle | Por defecto | Que hace |
| --- | --- | --- |
| `glossoBilling` | `fake` (debug) | `real` usa el cliente real de Play en debug |
| `glossoSubscriptionId` | `me.shirobyte42.glosso.pro` | El id de la suscripcion (debe coincidir con la identidad publicada y con el producto de Play) |

El id del producto **debe** pasar `-PglossoSubscriptionId=...` cuando no coincida
con el default, sobre todo para un build de license tester. En Play Console ese id
es practicamente inmutable, asi que conviene fijarlo antes de crear el producto.

`release` **siempre** usa el cliente real; nunca cae al fake. La decision no
depende solo del flag: `BillingProvider.useFake` exige `BILLING_FAKE && DEBUG`,
asi que un build no debuggable jamas entrega el fake aunque la propiedad este
mal. Ademas el propio `FakeSubscriptionRepository` vive en `src/debug`, de modo
que no se compila en release. Lo fijan `BillingProviderTest`,
`DebugBillingConfigTest` y `ReleaseBillingConfigTest` (ver `docs/BILLING-E2E.md`).

```sh
# Debug con el fake (flujo completo sin Play)
./gradlew :androidApp:assembleDebug

# Debug contra Play real, para un build de license tester
./gradlew :androidApp:assembleDebug -PglossoBilling=real \
  -PglossoSubscriptionId=me.shirobyte42.glosso.pro
```

## Pro desbloqueado en la build debug (y por que no puede pasar en release)

La build **debug** arranca con Glosso Pro ya desbloqueado: `debugFakeBilling()`
construye `FakeSubscriptionRepository(initialStatus = ACTIVE)`, asi que al abrir
la app las superficies Pro son accesibles sin recorrer el flujo de compra fake.

Es una **comodidad de desarrollo**, no un interruptor de producto. Para volver a
ver el muro y probar el paywall, quita el argumento `initialStatus` en
`androidApp/src/debug/.../billing/FakeBilling.kt` (el default de
`FakeSubscriptionRepository` es `NONE`) o llama a `reset()` desde los hooks de
test del fake.

### Por que es fisicamente imposible en release

- El desbloqueo vive **solo** en `src/debug`. No hay ningun `if (BuildConfig.DEBUG)`
  ni parametro de runtime en codigo compartido (`main`) que active Pro.
- En release, `debugFakeBilling()` es un stub que devuelve `null`, y
  `FakeSubscriptionRepository` **no se compila**. Un APK de release no contiene ni
  la clase ni la ruta que podria poner el estado en `ACTIVE`: no hay nada que
  cambiar para desbloquear.
- `BillingProvider.useFake` sigue exigiendo `BILLING_FAKE && BuildConfig.DEBUG`, y
  `BuildConfig.DEBUG` es `false` en release en tiempo de compilacion.

### Evidencia (tres capas)

| Capa | Que fija | Donde |
| --- | --- | --- |
| Auditoria de fuentes | Ningun source set de produccion referencia el fake ni un override de entitlement | `ProductionEntitlementGuardTest` (`:androidApp:test`) |
| Guard por variante | En release el seam devuelve `null` y el fake no esta en el classpath | `ReleaseEntitlementGuardTest` (`:androidApp:testReleaseUnitTest`) |
| Artefacto | Ni el APK ni el AAB de release contienen `FakeSubscriptionRepository` en ningun dex | `:androidApp:verifyReleaseApkEntitlement` (tras `assembleRelease`) y `:androidApp:verifyReleaseBundleEntitlement` (tras `bundleRelease`). El AAB es lo que Play publica de verdad; `:androidApp:verifyReleaseEntitlement` comprueba los dos |

Lo que este cambio **si** garantiza es estrecho y verificable sobre el binario: el
desbloqueo de debug esta confinado fisicamente al source set `debug`, no hay
ningun flag, booleano, extra ni constante que lo active en release, y el APK y el
AAB de release no contienen el fake en ningun dex. El guard se rompio a proposito
(se movio el fake a `main`) y falla, tanto para el APK como para el AAB.

Lo que **hoy no** existe: el cliente **no** consulta la verificacion en servidor.
`PlaySubscriptionRepository` decide el entitlement por si solo a partir de lo que
devuelve Play Billing, sin llamar a `POST /v1/billing/verify`, asi que **quien
controle el cliente controla el entitlement**. Es una **limitacion conocida y
pendiente**, no una defensa vigente; es preexistente y este cambio no la toca.

Conectarlo **no se puede hacer todavia**: `/v1/billing/verify` **falla cerrado**
(503 mientras no haya service account; nunca concede Pro) y el setup de Play
Console + Google Cloud esta pendiente. Conectar el cliente antes de ese setup
dejaria a todo el mundo sin Pro, porque la verificacion no podria resolver ningun
token. Va despues del setup; el contrato y los pasos estan en "Verificacion en
servidor (fase 2, contrato ya definido)". Ver `server/.../billing/BillingService.kt`.

## Pasos exactos en Play Console

1. **Crear o abrir la app.** Package `me.shirobyte42.glosso` (la identidad
   publicada). Necesita al menos un build subido para poder configurar productos.
2. **Subir un AAB a un track.** Monetizacion y productos no se pueden activar
   hasta que existe un artefacto. Sube primero (ver punto 6).
3. **Crear la suscripcion.** Monetizacion > Productos > Suscripciones > Crear
   suscripcion.
   - Id del producto: `me.shirobyte42.glosso.pro` (el mismo que
     `glossoSubscriptionId`). Si se pasa otro con `-PglossoSubscriptionId=...`,
     ese es el que hay que crear.
   - Nombre y descripcion visibles para el usuario.
4. **Crear el base plan.** Dentro de la suscripcion, anade un base plan:
   - Tipo: auto-renovable.
   - Periodo de facturacion: mensual (el que quieras; el texto del paywall se
     construye con lo que devuelva Play).
   - Precio.
5. **Anadir la oferta con 1 mes gratis.** En el base plan, "Anadir oferta":
   - Fase de elegibilidad: nuevo cliente.
   - Fase de precio: **1 mes gratis** (precio 0), duracion 1 mes.
   - El resto de fases: el precio mensual.
   - Activa el base plan y la oferta.
6. **Track de pruebas internas.**
   - Pruebas > Pruebas internas > Crear nueva version.
   - Sube el AAB firmado de release.
   - Anade los correos de prueba en la lista de testers.
   - Publica y copia el enlace de opt-in.
7. **License testers.**
   - Configuracion > Pruebas de licencia.
   - Anade los correos de las cuentas de prueba.
   - Respuesta de licencia: `RESPOND_NORMALLY` (o la que necesites).
   - En el dispositivo, instala desde el enlace de opt-in del track internos con
     una de esas cuentas.

Nota: un pedido de prueba se puede cancelar/renovar sin coste en
"Mis pedidos" del testeador; los reembolsos y cambios de estado se pueden forzar
desde el propio pedido.

## Como probarlo

### Sin Play (fake, debug)

1. `./gradlew :androidApp:assembleDebug` (usa el fake por defecto).
2. Abre la app, ve a **Biblioteca > Glosso Pro** o toca una leccion bloqueada o
   el icono de descarga offline.
3. El paywall muestra el plan de desarrollo y "1 mes gratis, luego 6,99 EUR al
   mes". El CTA pasa el estado a `TRIAL`, se abre el resto del curso (A2, B1 y B2;
   el A1 entero ya era gratis) y las descargas se habilitan.
   "Restaurar compras" es no-op. Los fallos se simulan con
   `FakeSubscriptionRepository.simulateError(...)`.
4. Esto valida UI, gate y estados, no la integracion con Google.

### Con Play (license testers)

1. Prepara el build con `-PglossoBilling=real` y el `glossoSubscriptionId`
   correcto.
2. Instalalo desde el enlace de opt-in del track de pruebas internas con una
   cuenta de license tester.
3. Comprueba: precios reales en el paywall, `launchBillingFlow` abre la hoja de
   Play con "1 mes gratis", la compra queda reconocida (no se reembolsa a los 3
   dias), `queryPurchasesAsync` restaura al reabrir, cancelar deja el estado
   `CANCELED`, y el modo avion no rompe la app (error y reintento).

## Verificacion en servidor (fase 2, contrato ya definido)

**Por que.** Un cliente se puede modificar: puede mentir sobre si tiene
suscripcion. El cliente **no puede** ser la fuente de verdad del entitlement. Por
eso la validacion real la hace el servidor contra la Google Play Developer API.

**Como debe ser.** El servidor valida el `purchaseToken` con la Android Publisher
API usando una service account, guarda el estado por dispositivo/cuenta y recibe
los cambios de ciclo de vida por Real-time Developer Notifications (RTDN) en un
Pub/Sub push. Devuelve un **token de entitlement firmado y con caducidad** que la
app cachea para funcionar offline (ver ventana de revalidacion en `PLAN.md`).

### Contrato propuesto

`POST /v1/billing/verify` (hoy responde `501`)

```json
// request
{ "productId": "me.shirobyte42.glosso.pro", "purchaseToken": "...",
  "packageName": "me.shirobyte42.glosso", "deviceId": "anon-install-id" }

// response
{ "entitled": true, "status": "TRIAL",
  "entitlementToken": "signed.jwt", "expiresAtMillis": 0 }
```

`POST /v1/billing/rtdn` (hoy responde `501`): recibe el sobre de Pub/Sub
(`PubSubPushEnvelope`), decodifica su `data` base64 a `DeveloperNotification` y
actualiza el entitlement (renovado, cancelado, en gracia, en espera, revocado,
cambio de precio).

Los tipos viven en `server/.../api/Dto.kt` (`BillingVerifyRequest`,
`BillingVerifyResponse`, `PubSubPushEnvelope`, `PubSubMessage`) y el hueco del
cliente en `GlossoApi.verifyPurchase(...)`.

### Que hace falta para activarlo

1. Cuenta de servicio en Google Cloud con acceso a la Play Developer API.
2. En Play Console: Usuarios y permisos > invitar la service account con permiso
   de finanzas/administracion de pedidos.
3. Habilitar la Android Publisher API en el proyecto de Google Cloud.
4. Topic de Pub/Sub + suscripcion push apuntando a `POST /v1/billing/rtdn`, y
   configurarla en Play Console (Monetizacion > Notificaciones).
5. Secretos en el VPS: clave de la service account, nombre del package, secreto
   del webhook. Nunca en git.

**TODO** en `server/.../Application.kt` (`/billing/verify` y `/billing/rtdn`): no
implementar hasta tener la service account; el contrato y el porque ya quedan
escritos.

## Checklist antes de considerar el billing listo

- [ ] App creada en Play Console con el package correcto.
- [ ] Suscripcion + base plan + oferta de 1 mes gratis activos.
- [ ] License testers y track de pruebas internas configurados.
- [ ] Compra real probada en dispositivo con build de track y cuenta tester.
- [ ] Reconciliacion y reembolso comprobados en un pedido de prueba.
- [ ] Verificacion en servidor (`/billing/verify`) y RTDN (`/billing/rtdn`)
      implementados con service account.
- [ ] Gate del cliente reemplazado por el entitlement firmado del servidor.
