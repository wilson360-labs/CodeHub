# ═══════════════════════════════════════════════════════════════
# CodeHub APK Builder — Genera APK independiente
# Wilson.E 2026
#
# Este script genera un APK de CodeHub usando Bubblewrap/TWA
# para que funcione como una app nativa en Android sin depender
# de Chrome ni de ningún navegador.
#
# REQUISITOS:
#   - Node.js >= 16
#   - Java JDK >= 11
#   - Android SDK (ANDROID_HOME configurado)
#   - npm install -g @nicepkg/bubblewrap
#
# USO:
#   1. Generar keystore (solo primera vez):
#      keytool -genkey -v -keystore codehub-release-key.jks -alias codehub -keyalg RSA -keysize 2048 -validity 10000
#
#   2. Editar twa-manifest.json con la contraseña del keystore
#
#   3. Ejecutar:
#      .\build-apk.ps1
# ═══════════════════════════════════════════════════════════════

Write-Host "═══ CodeHub APK Builder ═══" -ForegroundColor Cyan
Write-Host ""

# Verificar prerrequisitos
$bubblewrap = Get-Command bubblewrap -ErrorAction SilentlyContinue
if (-not $bubblewrap) {
    Write-Host "❌ Bubblewrap no encontrado. Instala con:" -ForegroundColor Red
    Write-Host "   npm install -g @nicepkg/bubblewrap" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "O alternativamente:" -ForegroundColor Yellow
    Write-Host "   npm install -g @nickvdh/nickvdh" -ForegroundColor Yellow
    exit 1
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host "❌ Java no encontrado. Instala JDK 11+" -ForegroundColor Red
    exit 1
}

# Verificar keystore
$keystore = Join-Path $PSScriptRoot "codehub-release-key.jks"
if (-not (Test-Path $keystore)) {
    Write-Host "⚠️  Keystore no encontrado. Generando uno nuevo..." -ForegroundColor Yellow
    Write-Host ""
    $password = Read-Host "Contraseña para el keystore"
    keytool -genkey -v -keystore $keystore -alias codehub -keyalg RSA -keysize 2048 -validity 10000 -storepass $password -keypass $password -dname "CN=Wilson.E, OU=CodeHub, O=CodeHub, L=Guatemala, ST=Guatemala, C=GT"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Error generando keystore" -ForegroundColor Red
        exit 1
    }
    Write-Host "✅ Keystore generado: $keystore" -ForegroundColor Green
    Write-Host ""
}

# Ejecutar bubblewrap
Write-Host "🔧 Generando APK con Bubblewrap..." -ForegroundColor Cyan

# Detectar manifest
$manifest = Join-Path $PSScriptRoot "twa-manifest.json"
if (-not (Test-Path $manifest)) {
    Write-Host "❌ twa-manifest.json no encontrado en $PSScriptRoot" -ForegroundColor Red
    exit 1
}

# Bubblewrap rebuild
bubblewrap rebuild --manifest=$manifest --签名=$keystore

if ($LASTEXITCODE -eq 0) {
    $apkPath = Join-Path $PSScriptRoot "app-release-signed.apk"
    if (Test-Path $apkPath) {
        Write-Host ""
        Write-Host "✅ APK generado exitosamente!" -ForegroundColor Green
        Write-Host "📱 Archivo: $apkPath" -ForegroundColor Green
        Write-Host ""
        Write-Host "Para instalar en tu dispositivo Android:" -ForegroundColor Cyan
        Write-Host "   adb install app-release-signed.apk" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "O sube el APK a Google Play Store para distribución." -ForegroundColor Cyan
    }
} else {
    Write-Host "❌ Error generando APK" -ForegroundColor Red
    Write-Host ""
    Write-Host "Si el error es sobre Android SDK:" -ForegroundColor Yellow
    Write-Host "   1. Instala Android Studio" -ForegroundColor Yellow
    Write-Host "   2. Configura ANDROID_HOME" -ForegroundColor Yellow
    Write-Host "   3. Ejecuta: sdkmanager 'platform-tools' 'build-tools;33.0.0' 'platforms;android-33'" -ForegroundColor Yellow
}
