# Bypass de PairIP (bot de Telegram) — uso personal

Wilson.E 2026 · **Uso personal: NO distribuir** (toca código de terceros).

PairIP ("pairipcore", `libpairpcore.so` + `com.pairip.*`) es el SDK de Google que
algunos APKs usan para exigir instalación desde Play ("Get this app from Play"),
validar la firma y detectar depuradores/Frida.

## Cómo se usa desde el bot

El bot **NO parchea APKs**: analiza y entrega el módulo LSPosed que quita la
pantalla en runtime (el APK queda intacto; la firma no cambia).

```
/pairip <url>      · analiza un APK por URL (https, tope 120 MB)
/pairip            · luego manda el APK como documento (file_id Telegram)
/pairip <appId>    · enlace listo para probar la tienda del catálogo
```

El comando llama a `POST /api/admin/pairip/analyze` (sesión admin, guardas
anti-SSRF con `_assertPublicHost`/`validateUrlSafety`, redirects validados).
El informe dice: `pairip` sí/no, nativo (`libpairpcore.so` array por ABI) o
clases DEX (`com/pairip/*`), versión, paquete, SHA-256.

## Por qué runtime y no re-empaquetado

PairIP reescribe métodos protegidos a bytecode de VM propia cargado de la
carpeta de la app: quitar esas llamadas rompe la app. El enfoque LSPosed
intercepta en memoria los *checks* y la app corre normal.

## Módulo LSPosed del repo (`apk/android/pairipfix`)

Hooks activos solo si existe `com.pairip.VMRunner` (app sin PairIP no toca nada):

| Clase hook | Métodos | Acción |
|---|---|---|
| `SignatureCheck` | `verifyIntegrity`, `verifySignatureMatches` | no-op / `true` |
| `licensecheck.LicenseClient` | `initializeLicenseCheck`, `performLocalInstallerCheck` | no-op / `true` |
| `licensecheck3.LicenseClientV3` | `processResponse` | no-op |
| `licensecheck.LicenseResponseHelper` | `validateResponse`, `verifySignature`, `getRepeatedCheckMetadata` | no-op / nulo |
| `licensecheck3.ResponseValidator` | `validateResponse`, `verifySignature` | no-op |
| `licensecheck.LicenseActivity` | `closeApp`, `exitApp`, `showErrorDialog`, `showPaywallAndCloseApp`, `logAndShowErrorDialog` | no-op |

Se activa con: LSPosed → módulo → scope de la app cerrada → la app se
detiene y se reabre.

## Alternativa sin root: `BetterKnownInstalled`

En Android 9+ el *verificador* respeta el archivo
`/data/system/package_cache/`-versión: con root (`adb` por Magisk/Shizuku)
se puede escribir el verificador que marca el APK como instalado desde su
instalador legítimo. Es la vía que PairIP no puede detectar tan fácil.

## Liberaciones / límites

- Técnica e implementación propias (el upstream `ahmedmani/pairipfix` no
  tiene LICENSE: no se copia su código).
- Detección va y viene: algunos builds marcan LSPosed; si sucede, probar
  `BetterKnownInstalled` o versión del módulo con firma distinta.
- Rate-limit del análisis: el tope es por descarga de `PAIRIP_MAX_BYTES`
  (120 MB) y el buffer se libera al final de la respuesta.
