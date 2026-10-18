# YouTube Music Extension — build propio de CodeBeat

## Tu fork
https://github.com/wilson360-labs/Eco-Youtube_Music

## Como funciona ahora (automatico)

El parche ya **no** se aplica a mano en el fork. El workflow de CodeHub
(`.github/workflows/build-ytm-extension.yml`) lo aplica solo durante el build:

1. Hace checkout de este repo (CodeHub) en `codehub/`.
2. Hace checkout de tu fork en `ext/`.
3. Copia `extensions/youtube-music/YouTubeStreamResolver.kt` (version parcheada
   de CodeBeat) sobre el archivo del fork.
4. Compila con `./gradlew assembleDebug --stacktrace`.
5. Publica el APK como release `ytm-<run_number>` en CodeHub.
6. Actualiza `backend/data/codebeat-extensions.json` con la URL del APK
   (`updateUrl`) y hace push (Render hace deploy solo).

## Compilar

- CodeHub → Actions → "Build YouTube Music Extension" → Run workflow
- Repo: `wilson360-labs/Eco-Youtube_Music`, branch: `main`

No se necesitan secrets extra: el build es `assembleDebug` (firma debug),
suficiente para instalar el extension desde CodeBeat.

## El parche que se aplica

`extensions/youtube-music/YouTubeStreamResolver.kt` sobreescribe
`ext/src/main/java/dev/brahmkshatriya/echo/extension/streaming/YouTubeStreamResolver.kt`.

Cambios clave:

1. **Orden por autenticacion** en `resolveStreamableInternal`: si
   `api.userAuthState != null` (sesion de Google iniciada), se prueban primero
   las rutas autenticadas (`tryYtmKtEndpoint` → `tryYouTubeMusicApi`) y luego
   se conserva el orden original (`RealNewPipe` → `YtmKt` → `YouTube Music API`).

2. **Cabeceras en la reproduccion**: cada `Streamable.Source.Http` lleva
   `User-Agent`, `Referer`, `Origin` y, si hay sesion, la `Cookie` y
   `Authorization` de `api.userAuthState`. Sin cabeceras YouTube responde 403.

3. **Pre-flight (verificacion previa)**: antes de entregar las fuentes al
   reproductor se hace un `GET` con `Range: bytes=0-1` a cada URL. Las que
   responden 401/403 se descartan; si una ruta entera queda sin URLs validas,
   se pasa a la siguiente ruta. Si todas fallan, el error del reproductor
   indica exactamente que ruta y que codigo HTTP fallo.

Para cambiar el parche: edita `extensions/youtube-music/YouTubeStreamResolver.kt`
en CodeHub, commit y vuelve a correr el workflow.

## Configuracion opcional del fork (no obligatoria)

`gradle.properties` del fork (si quieres que el APK se identifique aparte):
```properties
extType=music
extId=Youtube_music
extClass=YoutubeExtension
extName=YouTube Music
extDescription=YouTube Music para CodeBeat
extAuthor=Wilson.E
extAuthorUrl=https://github.com/wilson360-labs
extRepoUrl=https://github.com/wilson360-labs/Eco-Youtube_Music
extUpdateUrl=https://codehub-98s6.onrender.com/api/extensions/pkg/Youtube_music
extIconUrl=https://music.youtube.com/img/favicon_144.png
```

> El workflow **sobreescribe** `extUpdateUrl` en cada build para que el
> manifiesto apunte al proxy de CodeHub. Si apuntara al repo upstream de
> Abhishek, un fallo del catalogo reinstalaria la extension **sin** el parche.

## Version por build (importante)

El fork deriva `version`/`version_code` del git del checkout
(`v<hash7>` / commit count). Como el parche se aplica sin commitear, **todos
los builds salian identicos** (`vba0fa73`), y por eso:

- la app no podia distinguir que habia una version nueva,
- CodeBeat mostraba siempre la misma version, asi que era imposible saber
  que build tenias instalado.

Por eso el workflow inyecta en `app/build.gradle.kts`:

```properties
verCode = System.getenv("YTM_VERSION_CODE")  ->  numero de run de Actions
verName = System.getenv("YTM_VERSION_NAME")  ->  "cb-<run>"
```

y anade `"version": "cb-<run>"` al catalogo para que el backend pueda
comprobar correctamente si hay actualizacion (`?check=1&from=...`).

## Instalar / actualizar en el dispositivo

CodeBeat **no** tiene auto-actualizacion de extensiones: hay que instalarla a
mano. Usa en CodeBeat **Extensiones → Importar desde URL** con:

```
https://codehub-98s6.onrender.com/api/extensions/pkg/Youtube_music
```

Esa URL siempre sirve el build mas reciente (el proxy resuelve el asset),
asi que no hay que andar copiando la release `ytm-<n>` a mano.

**Verifica el build instalado**: en la lista de extensiones debe aparecer
`YouTube Music · vcb-<n>`. Si sigue diciendo `vba0fa73`, la instalacion no se
llevó a cabo (borra la extension e importa de nuevo).

> Espera ~30 min tras publicar una release: el backend cachea la resolucion
> del `.eapk` (`ECHO_EXTENSIONS_ASSET_TTL_MS`).

## Nota sobre el 403 — causa raiz (build `cb-9+`)

**El fallo no era ni la URL ni las cabeceras: era un PO token.**

Desde 2026 YouTube exige un **PO token GVS** (Proof of Origin) para los clientes
de InnerTube `web_music`, `ios`, `mweb`, `tv_simply`, etc. Sin ese token, las
URLs que devuelve `/player` dan **HTTP 403** al reproducir, y la propia llamada
`/player` del cliente `ios` devuelve **400 `Precondition check failed`**. Es
exactamente lo que se veia en el error agregado:

```
YtmKt (auth): URL bloqueada (HTTP 403)
YouTube Music API (auth): 400 "Precondition check failed"
```

### La solucion: cliente InnerTube `ANDROID`

El cliente `ANDROID` **no exige PO token** y devuelve `streamingData` con URLs
**sin `signatureCipher`** (sin necesidad de descifrar firmas). Verificado en vivo
contra `youtubei/v1/player` con `clientName=ANDROID` (`clientVersion=20.10.38`)
en dos videos distintos:

```
ANDROID              status=OK   audioUrl=6  audioCipher=0  GET Range -> 206
WEB / WEB_REMIX / ANDROID_VR / WEB_EMBEDDED / TVHTML5   -> todos bloqueados
```

Ademas las URLs aguantan las cabeceras exactas del reproductor (`Origin` y
`Referer` de music, `Range`, User-Agent): todas las combinaciones devolvieron
**206**.

`YouTubeStreamResolver.kt` ahora arranca con `tryAndroidClient()`:

1. `POST /youtubei/v1/player` con `clientName=ANDROID` (via `HttpURLConnection`,
   sin dependencias nuevas) con `contentCheckOk`/`racyCheckOk`.
2. `preferVideos=false` → `adaptiveFormats` de **audio**; `preferVideos=true` →
   `formats` **muxed** (itag 18), con audio y video sincronizados.
3. `playabilityStatus != OK` → el error reporta `status` + `reason`.
4. Despues siguen todas las demas rutas como respaldo (cero regresion): sesion
   Google → YtmKt → YouTube Music API → RealNewPipe → YtmKt → YouTube Music API.

### Aportes del fix `Eco-403-fix` (cb-9)

Combinados sobre la base anterior sin perder nada de lo ya verificado:

- **`headersFor(url)`** — googlevideo devuelve **403 si la User-Agent no coincide
  con el cliente que generó la URL**, que viaja en el parámetro `c=` de la propia
  URL (verificado en vivo: `&c=ANDROID`). Ahora cada fuente lleva la UA de su
  cliente: `IOS` → `com.google.ios.youtube/19.34.2`, `ANDROID`/`ANDROID_MUSIC` →
  `com.google.android.youtube/19.44.38`, `ANDROID_VR` → UA de Oculus, resto →
  UA de Chrome + `Origin`/`Referer` de youtube.com. Los sondeos ahora usan las
  cabeceras de **cada fuente** en vez de un mapa global.
- **`isPlayable(media)`** — guardia de Eco-403-fix: si el CDN rechaza la primera
  fuente, la ruta se descarta y se pasa a la siguiente (aplicado a RealNewPipe,
  YtmKt y YouTube Music API). `finalize()` sigue sondeando **todas** las fuentes
  y filtrando, que es la versión fuerte de esta misma idea.
- **Orden final**: InnerTube `ANDROID` → (sesión Google) YtmKt auth → YouTube
  Music API auth → RealNewPipe → YtmKt → YouTube Music API.
- Cookies de la sesión Google solo se adjuntan a clientes **web**; los clientes
  nativos (IOS/ANDROID) no soportan cookies de cuenta.
- **`ClientException.LoginRequired()`** (v2) — si **todas** las rutas fallan con
  403/bot y **no hay sesión**, se lanza en vez del error crudo. Echo lo traduce a
  `AppException.LoginRequired` y muestra el botón **"Login"**
  (`ExceptionUtils.kt` → `openLoginException`), que abre el flujo de inicio de
  sesión para que el usuario se loguee. Con sesión activa no se intercepta: los
  motivos reales se reportan tal cual.
- **JSON con `kotlinx.serialization`, nunca `org.json`** — el módulo `ext` del
  fork es **JVM puro** (`java-library` + `kotlin.jvm` + `jvmToolchain(17)`) y
  `org.json` es una librería de **Android**, no de la JDK: no está en el
  classpath (tampoco llega de `echo-common` ni de NewPipeExtractor, que usa
  `nanojson`). Usar `org.json` rompe Gradle con **`Unresolved reference`**. El
  POST a `/player` se construye y parsea con `kotlinx.serialization.json`
  (`buildJsonObject`, `Json.parseToJsonElement`, `JsonArray`), que ya usa el
  propio fork en `YouTubeAuthManager.kt`. **No reintroducir `org.json`.**

### Cambios de apoyo (cb-8)

- **`Range: bytes=0-`** en `mediaHeaders()`. `DefaultHttpDataSource` calcula su
  propio `Range` con `HttpUtil.buildRangeRequestHeader(position, length)`, que
  devuelve `null` si `position==0 && length==UNSET`, asi que la primera carga se
  hacia **sin Range**. Media3 solo lo pisa en los seeks, no rompe el avance.
- El pre-flight es una **replica exacta** del reproductor (`HttpURLConnection`,
  mismas cabeceras) en vez de OkHttp, y anade `[okhttp=...]` al error si OkHttp
  hubiera aceptado la misma URL (diagnostico de pila HTTP).
- `RealNewPipe` ya no se traga en silencio: antes devolvia
  `ExtractionResult.Failed`, que no se reportaba y desaparecia del error
  agregado; ahora devuelve `Error` con el motivo real.

### Alternativas pendientes

Si en algun momento `ANDROID` pidiera PO token: implementar un proveedor de PO
tokens (BgUtils / botguard). La tabla vigente de yt-dlp (jul-2026) marca
`web_embedded` y `tv` como "no requerido", pero con limitaciones: `web_embedded`
solo sirve para videos incrustables y `tv` entrega formatos DRM si no se pasan
cookies. Subir `newpipe` no sirve: el fork ya esta en v0.26.5, la ultima
version publicada (ago-2026).
