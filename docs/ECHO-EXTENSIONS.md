# Extensiones de Echo — catálogo privado y proxy

Reproductor Echo integrado en CodeHub (`apk/android/echo`). Las extensiones se
instalan/actualizan **sin exponer nunca el enlace ni el repositorio de origen**:
el dispositivo solo habla con el backend de CodeHub.

## Cómo funciona

```
App (Echo)                    Backend CodeHub                    Origen (privado)
   │                               │                                   │
   │ GET /api/extensions           │                                   │
   │──────────────────────────────►│  fetch                 ──────────►│
   │◄──── [{id,name,updateUrl:…}]  │  (caché 6 h)                       │
   │         updateUrl = proxy     │                                   │
   │                               │                                   │
   │ GET /api/extensions/pkg/:id?check=1&from=<v>                      │
   │──────────────────────────────►│  resuelve /releases/latest ───────►│
   │◄──── 200 (hay update) / 204   │  (caché 30 min)                    │
   │                               │                                   │
   │ GET /api/extensions/pkg/:id   │  descarga el .eapk  ──────────────►│
   │──────────────────────────────►│  lo reenvía al cliente            │
   │◄──── .eapk (bytes)            │                                   │
```

- El JSON que consume Echo es el mismo formato de siempre
  (`id, name, subtitle, iconUrl, updateUrl`), así que no hace falta tocar el
  parser ni la UI de "Añadir extensiones".
- `updateUrl` lo reescribe el backend hacia su propio proxy. La URL real
  (p. ej. `api.github.com/repos/…/releases`) nunca sale del servidor.
- `iconUrl` también se reescribe hacia `/api/extensions/icon/:id`: si el icono
  del origen vive en un repo (`raw.githubusercontent.com/<autor>/<ext>/…`), el
  dispositivo no ve ni el repo ni el usuario.
- El servidor solo acepta `id` presentes en el catálogo → no hay SSRF.

## Homologación con Echo

Echo considera que hay actualización si la etiqueta de la última release cambia
(`tag_name != currentVersion`) y elige el asset cuyo nombre termina en `apk`
(prefiriendo el ABI `arm64-v8a`). El backend replica exactamente ese criterio.

## Endpoints (`backend/modules/extensions`)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/extensions` | Lista para instalar (JSON de Echo, sin origen). |
| `GET` | `/api/extensions/pkg/:id` | Descarga el `.eapk` (proxy del origen). |
| `GET` | `/api/extensions/pkg/:id?check=1&from=<v>` | `200` = hay update, `204` = no hay. |
| `GET` | `/api/extensions/icon/:id` | Icono reenviado por CodeHub (sin URL de origen). |
| `POST` | `/api/extensions/refresh` | Invalida la caché (header `x-admin-key`). |

## Variables de entorno (todas opcionales)

| Variable | Defecto | Uso |
|---|---|---|
| `ECHO_EXTENSIONS_SOURCE_URL` | lista pública de Echo | JSON con las extensiones. |
| `ECHO_EXTENSIONS_ALLOWLIST` | *(vacío = todas)* | ids permitidos (coma). |
| `ECHO_EXTENSIONS_BLOCKLIST` | *(vacío)* | ids vetados (coma). |
| `ECHO_EXTENSIONS_CACHE_TTL_MS` | `6h` | TTL del catálogo. |
| `ECHO_EXTENSIONS_ASSET_TTL_MS` | `30min` | TTL de la resolución del `.eapk`. |
| `ECHO_EXTENSIONS_MAX_BYTES` | `80 MB` | Tamaño máximo a reenviar. |
| `ECHO_EXTENSIONS_MAX_ICON_BYTES` | `512 KB` | Tamaño máximo de icono reenviado. |
| `ECHO_EXTENSIONS_FETCH_TIMEOUT_MS` | `20s` | Timeout saliente. |
| `BACKEND_URL` | `https://codehub-98s6.onrender.com` | Base pública del proxy. |
| `GITHUB_TOKEN` / `GH_TOKEN` | *(opcional)* | Sube el límite de la API de GitHub. |

> Para ofrecer **solo** ciertas extensiones usa `ECHO_EXTENSIONS_ALLOWLIST`
> (p. ej. `deezer,spotify,youtube-music`). Sin ella se ofrecen todas las de la
> fuente.

## Cliente Android

- `extensions/CodeHubExtensions.kt` — base del backend, URL del catálogo y
  caché en memoria `id → updateUrl`.
- `ExtensionsAddBottomSheet` — carga el catálogo solo al abrir el diálogo
  (no hay que pegar ningún enlace).
- `AppUpdater.getUpdateFileUrl` — acepta las URLs del proxy además de las de
  GitHub.
- `ExtensionsViewModel.getExtensionUpdate` — si el `id` está en el catálogo,
  la actualización pasa por el proxy; si no, se mantiene la URL del manifiesto.

La base del backend se inyecta con `buildConfigField("...", "CODEHUB_BACKEND", …)`
en `apk/android/echo/build.gradle.kts` y se puede sobreescribir con
`-PCODEHUB_BACKEND=https://…`.
