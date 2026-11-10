# Entrar con Google (Credential Manager)

Como en Niagara Launcher: el usuario pulsa **Entrar con Google**, Android le
muestra las cuentas de Google que ya tiene en el movil, elige una y entra. Sin
usuario, sin contrasena y sin formularios. Es **la unica forma** de que el
**Beta Founder y su PRO de por vida sigan a la persona, no al movil**: en el
movil nuevo entra con la misma cuenta y la cuenta se mueve alli (ver
`docs/BETA-RUNBOOK.md`, 6.1). Ya no hay recuperacion por codigo.

Estado: **la implementacion esta lista; falta la configuracion de Google**
(client id y huellas SHA-1). Sin ella la app oculta "Cambiar de movil" y el
servidor responde `503` en `/v1/auth/google*`; todo lo demas funciona igual.

## Flujo

1. App -> `POST /v1/auth/google/nonce` (con `X-Device-Id`). El servidor devuelve
   un nonce firmado con HMAC (`GLOSSO_ACCOUNT_SECRET`), ligado a ese dispositivo
   y valido 10 minutos. No guarda estado.
2. App -> Credential Manager con `GetGoogleIdOption`
   (`setServerClientId(<client id Web>)`, `setFilterByAuthorizedAccounts(false)`,
   `setAutoSelectEnabled(false)`, `setNonce(nonce)`). Android muestra la hoja de
   cuentas; el usuario elige una. Codigo: `androidApp/.../GoogleSignIn.kt`.
3. App -> `POST /v1/auth/google` con `{"idToken": ...}`. El servidor
   (`NimbusGoogleIdTokenVerifier`) comprueba:
   - firma RS256 con las claves publicas de Google
     (`https://www.googleapis.com/oauth2/v3/certs`, cacheadas y rotadas);
   - `aud` = `GLOSSO_GOOGLE_CLIENT_ID`;
   - `iss` = `accounts.google.com` o `https://accounts.google.com`;
   - `exp` no vencido;
   - `nonce` emitido para **este** `X-Device-Id` y aun fresco: un token filtrado
     solo puede mover la cuenta al dispositivo del nonce, nunca a otro. Conocer
     ese `X-Device-Id` ya equivale a ser ese dispositivo (modelo de amenaza en
     `docs/BETA-RUNBOOK.md`).
4. Primera vez: vincula el `sub` de Google a la cuenta de este movil
   (`glosso.linked_accounts`). En otro movil: la cuenta se mueve alli y lo que
   ese movil tuviera se fusiona. Respuesta: el mismo `MeState` que `/v1/me`.
   `401` = token rechazado; `409` = este movil ya esta vinculado a otra cuenta
   de Google.

## Privacidad

- Solo se pide la **identidad** (ID token OpenID: `sub`). **Ningun** scope de
  Gmail, Drive ni otro servicio; no hay pantalla de consentimiento de permisos.
- El servidor guarda **solo** el `sub` (identificador opaco de Google). No lee,
  registra ni guarda el email, el nombre, la foto ni el token.
- El email se guarda **solo en el propio movil** (`glosso_google_account`) para
  mostrar "Vinculada a ...". Nunca viaja al servidor. Cuando `/v1/me` deja de
  incluir `google` en `linkedAccounts` (la cuenta se fue a otro movil), la app
  lo borra.
- Declarado en la politica de privacidad (4 idiomas) y en Data safety como
  "Personal info / User IDs", opcional.

## Configuracion pendiente (hacerlo una vez)

No hay ningun client id en el repo, a proposito. Se crea en la consola de
Google Cloud del proyecto de Play (o uno nuevo del mismo propietario):

1. **Pantalla de consentimiento OAuth** (Google Auth Platform > Branding):
   tipo *External*, nombre "Glosso", email de soporte, dominio
   `glossostudio.com`, enlace a la politica de privacidad. Scopes: ninguno
   extra (solo `openid`, `email`, `profile`, que no requieren verificacion).
   Publicar en *Production* para que cualquier cuenta pueda entrar.
2. **Client id Web** (Clients > Create client > *Web application*), nombre
   "Glosso server". Es el valor de:
   - servidor: `GLOSSO_GOOGLE_CLIENT_ID` (admite varios separados por coma para
     rotar);
   - app: `glossoGoogleServerClientId` (propiedad Gradle
     `-PglossoGoogleServerClientId=...` o `glosso.googleServerClientId` en
     `local.properties`).
   No es un secreto (va dentro del APK), pero es configuracion del proyecto; no
   hace falta el *client secret* para nada.
3. **Client id Android** (Clients > Create client > *Android*), uno por cada
   firma que tenga que funcionar, con paquete `me.shirobyte42.glosso` y su
   **SHA-1**. No se usa en el codigo; Google lo exige para aceptar la peticion
   desde una app firmada con esa clave:
   - **Play App Signing** (la que firma lo que instala Play): Play Console >
     Test and release > App integrity > App signing key certificate > SHA-1.
   - **Clave de subida / release local** (`keystores/release.jks`), para builds
     firmadas en local y el debug con `-PglossoDebugReleaseSigning=true`:
     `keytool -list -v -keystore keystores/release.jks -alias <alias>` (pide la
     contrasena por teclado; no pasarla con `-storepass`).
   - **Debug** (opcional, solo desarrollo): `./gradlew :androidApp:signingReport`
     y copiar el SHA-1 de `debug`.
4. **Servidor**: anadir `GLOSSO_GOOGLE_CLIENT_ID` y `GLOSSO_ACCOUNT_SECRET` al
   entorno del VPS (ya cableados en `deploy/docker-compose.yml`). El secreto se
   genera con `openssl rand -base64 32` y se guarda con
   `tools/keys/glosso-secret set GLOSSO_ACCOUNT_SECRET` (por stdin); nunca en la
   linea de comandos ni en git. Recrear el contenedor y comprobar en el log:
   `Google sign-in: on`.
5. **App**: compilar con el client id Web. Sin el, el boton no aparece.

## Verificar

- Tests: `./gradlew :server:test` (`GoogleSignInRouteTest`,
  `EntitlementServiceGoogleTest`, `NimbusGoogleIdTokenVerifierTest`,
  `JdbcEntitlementStorePostgresTest`) y `./gradlew :androidApp:testDebugUnitTest`
  (`GlossoApiGoogleSignInTest`, `FounderRecoveryCopyTest`).
- E2E real (solo con la configuracion hecha): en un emulador con una cuenta de
  Google anadida, Ajustes > Cuenta > Cambiar de movil > Entrar con Google, elegir
  la cuenta -> "Listo...". En otro emulador, lo mismo con la misma cuenta ->
  `GET /v1/me` del segundo muestra `betaFounder=true` y el primero pasa a ser un
  usuario anonimo nuevo.
- Errores tipicos: `NoCredentialException` / "No hay ninguna cuenta de Google"
  sin cuentas en el movil; `[28444] Developer console is not set up correctly`
  si falta el client id Android con el SHA-1 de la firma instalada, o el
  `serverClientId` no es el de tipo Web.
