# CodeHub APK — App Independiente

CodeHub se puede compilar como un **APK nativo de Android** usando
[Bubblewrap](https://github.com/nicepkg/bubblewrap) (Trusted Web Activity).
Esto genera una app que:

- **No depende de Chrome** ni de ningún navegador
- Se ejecuta como una **app nativa** en Android
- Tiene su propio **ícono en el launcher**
- Soporta **push notifications**, **geolocation**, **camera** (si se configura)
- Se puede publicar en **Google Play Store**

## Prerrequisitos

```bash
# Node.js >= 16
node --version

# Java JDK >= 11
java -version

# Android SDK
echo $ANDROID_HOME

# Bubblewrap
npm install -g @nicepkg/bubblewrap
```

## Pasos

### 1. Generar keystore (solo primera vez)

```bash
keytool -genkey -v \
  -keystore codehub-release-key.jks \
  -alias codehub \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### 2. Editar `twa-manifest.json`

Cambia las contraseñas del keystore en `signingKey`:
```json
"signingKey": {
  "path": "./codehub-release-key.jks",
  "alias": "codehub",
  "keystorePassword": "TU_CONTRASEÑA",
  "keyPassword": "TU_CONTRASEÑA"
}
```

### 3. Generar APK

**Windows (PowerShell):**
```powershell
.\build-apk.ps1
```

**Linux/Mac:**
```bash
bubblewrap rebuild --manifest=twa-manifest.json
```

### 4. Instalar en dispositivo

```bash
adb install app-release-signed.apk
```

### 5. Publicar en Play Store

1. Crea una cuenta en [Google Play Console](https://play.google.com/console)
2. Crea una nueva app
3. Sube el APK firmado
4. Completa la información (descripción, screenshots, etc.)
5. Publica

## Archivos

| Archivo | Descripción |
|---|---|
| `twa-manifest.json` | Configuración de Bubblewrap/TWA |
| `build-apk.ps1` | Script de build para Windows |
| `icon-512.png` | Ícono 512x512 para el APK |
| `icon-maskable-512.png` | Ícono maskable para el APK |
| `codehub-release-key.jks` | Keystore de firma (NO subir a git) |

## Notas

- El APK usa **Trusted Web Activity (TWA)**: renderiza la PWA en un WebView nativo
- Las **push notifications** funcionan igual que en la PWA
- El **offline mode** funciona igual que en la PWA
- La app se **auto-actualiza** cuando se actualiza la PWA en el servidor
- El APK es **~3MB** (solo el contenedor, el contenido se carga de la web)
