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

## Nota sobre el 403

Si el 403 persiste incluso autenticado, es que NewPipeExtractor necesita un
PO token explicito. Alternativas: subir `newpipe` en `gradle/libs.versions.toml`
del fork a una version mas nueva que maneje PO tokens, o esperar el fix upstream.
