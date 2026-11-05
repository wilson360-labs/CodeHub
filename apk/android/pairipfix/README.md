# PairIP Fix — módulo LSPosed

Permite instalar como APK apps protegidas con **PairIP** (`libpairpcore.so` +
clases `com.pairip.*`): la app deja de exigir "instalar desde Google Play" y de
rechazar el APK por firma.

Uso personal. Detalle completo del flujo (bot + analizador) en
[`docs/PAIRIP-TOOL.md`](../../docs/PAIRIP-TOOL.md).

## Qué hace

Intercepta en runtime las comprobaciones de PairIP. **No** parchea el APK: la
firma y los bytes del APK quedan intactos.

| Clase | Métodos | Efecto |
|---|---|---|
| `com.pairip.SignatureCheck` | `verifyIntegrity`, `verifySignatureMatches` | verificación de firma anulada |
| `com.pairip.licensecheck.LicenseClient` | `initializeLicenseCheck`, `performLocalInstallerCheck` | chequeo de instalador anulado + `licenseCheckState = FULL_CHECK_OK` |
| `com.pairip.licensecheck3.LicenseClientV3` | `initializeLicenseCheck`, `processResponse` | respuesta de licencia ignorada |
| `com.pairip.licensecheck.LicenseActivity` | `closeApp`, `exitApp`, `showErrorDialog`, `showPaywallAndCloseApp`, `logAndShowErrorDialog` | no cierra la app ni muestra el aviso |
| `com.pairip.licensecheck.LicenseResponseHelper` | `validateResponse`, `verifySignature`, `getRepeatedCheckMetadata` | respuesta aceptada |
| `com.pairip.licensecheck.ResponseValidator` | `validateResponse`, `verifySignature` | respuesta aceptada |
| `com.pairip.licensecheck3.ResponseValidator` | `validateResponse`, `verifySignature` | respuesta aceptada |

Solo se hookea si la app tiene `com.pairip.VMRunner` (detector de PairIP).

## Compilar

Proyecto Gradle **standalone** (no entra en `apk/android/settings.gradle`, así
que el build de la app no se ve afectado):

```bash
cd apk/android/pairipfix
gradle assembleRelease
# salida: build/outputs/apk/release/pairipfix-release.apk
```

Requisitos: JDK 17, Android SDK (API 35). La API de Xposed entra como
`compileOnly` (`de.robv.android.xposed:api:82`, maven `api.xposed.info`); el
runtime lo aporta LSPosed.

CI: `.github/workflows/build-pairip-module.yml` → release `pairip-module`
(asset `pairipfix-module.apk`).

## Instalar

1. LSPosed instalado (root: Magisk / KernelSU).
2. Instalar el APK del módulo y reiniciar el dispositivo.
3. LSPosed → PairIP Fix → activar el módulo → **marcar la app protegida** en el
   scope.
4. Forzar el cierre de la app protegida y abrirla otra vez.

## Créditos

La idea de qué clases hookear viene del módulo público de
[`ahmedmani/pairipfix`](https://github.com/ahmedmani/pairipfix) (sin licencia:
aquí no se copia su código, la implementación es propia).
