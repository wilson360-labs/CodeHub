# PairIP con el bot de Telegram (uso personal)

Wilson.E 2026 · **Uso personal: NO distribuir** (toca código de terceros).

PairIP ("pairipcore", `libpairpcore.so` + `com.pairip.*`) es el SDK de Google que
algunos APKs usan para exigir instalación desde Play ("Get this app from Play"),
validar la firma y detectar depuradores/Frida.

Hay **dos vías**, y la fiable es la primera:

| Vía | Qué hace | Requiere |
|---|---|---|
| **Módulo LSPosed** (recomendada) | hook en runtime: la app deja de exigir Play/firma y arranca normal | root + LSPosed |
| **Strip del APK** (experimental) | re-empaqueta sin `libpairpcore.so` ni firma v1 | re-firmar el APK a mano; puede crashear |

## 1) Cómo se usa desde el bot

```
Manda el APK como documento        · sin comando previo (detecta .apk solo)
/pairip <url>                      · por link directo (GitHub release, tope 120 MB)
/pairip <appId>                    · analiza el APK de una app del catálogo
/pairip strip <url|appId>          · además quita libpairpcore (experimental)
/pairip app.apk                    · el APK con pie de foto "/pairip app.apk"
```

* APK ≤ 20 MB: llega como documento (tope de la Bot API de Telegram).
* APK > 20 MB: usa la URL (ej. release de GitHub) — el servidor lo baja.

El análisis es `POST /api/admin/pairip/analyze` (sesión admin, guardas
anti-SSRF, redirects validados). El informe dice: `pairip` sí/no, nativo
(`libpairpcore.so` por ABI) o clases DEX (`com/pairip/*`), paquete, versión,
SHA-256 y tamaño. Con PairIP detectado manda el enlace del módulo LSPosed y
la guía.

## 2) Módulo LSPosed del repo (`apk/android/pairipfix`)

Proyecto Gradle **standalone** (no entra en `apk/android/settings.gradle`: el
build de la app no se toca). CI propio:
`.github/workflows/build-pairip-module.yml` (JDK 17, Gradle 8.11.1, AGP 8.7.3,
`de.robv.android.xposed:api:82` como `compileOnly` desde `api.xposed.info`).
Publica el asset en el release `pairip-module`:

```
https://github.com/wilson360-labs/CodeHub/releases/download/pairip-module/pairipfix-module.apk
```

Esa URL es la que devuelve el endpoint y la que manda el bot.

Hooks (solo si la app tiene `com.pairip.VMRunner`; si no, no se toca nada):

| Clase | Métodos | Efecto |
|---|---|---|
| `com.pairip.SignatureCheck` | `verifyIntegrity`, `verifySignatureMatches` | verificación de firma anulada |
| `com.pairip.licensecheck.LicenseClient` | `initializeLicenseCheck`, `performLocalInstallerCheck` | chequeo de instalador anulado + `licenseCheckState = FULL_CHECK_OK` |
| `com.pairip.licensecheck3.LicenseClientV3` | `initializeLicenseCheck`, `processResponse` | respuesta de licencia ignorada |
| `com.pairip.licensecheck.LicenseActivity` | `closeApp`, `exitApp`, `showErrorDialog`, `showPaywallAndCloseApp`, `logAndShowErrorDialog` | no cierra la app ni muestra el aviso |
| `com.pairip.licensecheck.LicenseResponseHelper` | `validateResponse`, `verifySignature`, `getRepeatedCheckMetadata` | respuesta aceptada |
| `com.pairip.licensecheck.ResponseValidator` | `validateResponse`, `verifySignature` | respuesta aceptada |
| `com.pairip.licensecheck3.ResponseValidator` | `validateResponse`, `verifySignature` | respuesta aceptada |

Instalación: LSPosed → PairIP Fix → activar el módulo → **marcar la app
protegida** en el scope → forzar el cierre de la app y abrirla otra vez.

Detalle del módulo: `apk/android/pairipfix/README.md`.

## 3) Strip del APK (experimental)

`POST /api/admin/pairip/strip` re-empaqueta y sube el resultado a Supabase:

1. fuera `lib/<abi>/libpairpcore.so` — el SDK nativo de Google;
2. fuera la firma JAR v1 (`META-INF/*.RSA|*.DSA|*.EC|*.MF|*.SF`): cualquier byte
   distinto invalida la firma y Android la rechazaría.

**Paso final obligatorio (en tu PC):** el APK sale sin firma → hay que
re-firmarlo con una clave propia antes de instalar:

```bash
zipalign -p 4 app-stripped.apk app-aligned.apk
apksigner sign --ks tu-key.jks app-aligned.apk
apksigner verify app-aligned.apk
```

Limitación honesta: las clases `com.pairip.*` quedan en el DEX y la app puede
seguir pidiendo Play; si codificó métodos en la VM de PairIP, el paquete
resultante puede crashear al abrir (sin el `.so` no puede ejecutar el VM).
Por eso la vía fiable es el módulo LSPosed.

## Alternativa sin root: `BetterKnownInstalled`

En Android 9+ el *verificador* respeta el archivo
`/data/system/package_cache/`-versión: con root (`adb` por Magisk/Shizuku)
se puede escribir el verificador que marca el APK como instalado desde su
instalador legítimo. Es la vía que PairIP no puede detectar tan fácil.

## Liberaciones / límites

- Técnica e implementación propias (el upstream `ahmedmani/pairipfix` no
  tiene LICENSE: no se copia su código; solo se toma la idea de qué clases
  hookear).
- Detección va y viene: algunos builds marcan LSPosed; si sucede, probar
  `BetterKnownInstalled` o la vía del strip.
- Tope del análisis: `PAIRIP_MAX_BYTES` (120 MB) para URL y 20 MB para
  documentos de Telegram (límite de la Bot API).
