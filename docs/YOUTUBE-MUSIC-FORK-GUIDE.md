# YouTube Music Extension — Fork de wilson360-labs

## Tu fork
https://github.com/wilson360-labs/Eco-Youtube_Music

## Pasos para aplicar el fix

### 1. Clona tu fork
```bash
git clone https://github.com/wilson360-labs/Eco-Youtube_Music.git
cd Eco-Youtube_Music
```

### 2. Actualiza NewPipeExtractor
Edita `gradle/libs.versions.toml`:
```toml
newpipe = "v0.26.1"   # o la ultima version disponible en JitPack
```
(Cambiar de `v0.26.5` a la ultima que maneje PO tokens)

### 3. Reordena el fallback en YouTubeStreamResolver.kt
Archivo: `ext/src/main/java/dev/brahmkshatriya/echo/extension/streaming/YouTubeStreamResolver.kt`

Reemplaza la funcion `resolveStreamableInternal` con la version reordenada.
El codigo completo esta en: docs/YOUTUBE-MUSIC-IMPROVED.md (seccion "Codigo modificado")

Cambio clave: si `api.userAuthState != null` (sesion autenticada), probar YtmKt primero.

### 4. Configura gradle.properties
```properties
extType=music
extId=Youtube_music
extClass=YoutubeExtension
extName=YouTube Music
extDescription=YouTube Music para CodeBeat (mejorado)
extAuthor=Wilson.E
extAuthorUrl=https://github.com/wilson360-labs
extRepoUrl=https://github.com/wilson360-labs/Eco-Youtube_Music
extUpdateUrl=https://api.github.com/repos/wilson360-labs/Eco-Youtube_Music/releases/latest
extIconUrl=https://music.youtube.com/img/favicon_144.png
```

### 5. Configura secrets en GitHub
En tu fork → Settings → Secrets → Actions:
- KEYSTORE_B64: base64 de un keystore JKS (puedes generar uno con el workflow keystore-generate.yml de CodeHub)
- PASSWORD: password del keystore

### 6. Compila
- Ve a CodeHub → Actions → "Build YouTube Music Extension" → Run workflow
- Repo: `wilson360-labs/Eco-Youtube_Music`, branch: `main`
- El workflow compila, publica el APK y actualiza el catalogo CodeBeat automaticamente

### 7. Prueba
- En CodeBeat, instala YouTube Music desde la lista de extensiones
- Inicia sesion con tu cuenta de Google (Configuracion del extension)
- Reproduce una cancion — deberia funcionar con la sesion autenticada

## Archivos clave a modificar

1. `gradle/libs.versions.toml` — subir newpipe version
2. `ext/src/main/java/.../streaming/YouTubeStreamResolver.kt` — reordenar fallback

## Nota

Si el 403 persiste incluso con sesion autenticada, el problema es que NewPipeExtractor
necesita un PO token explicito. En ese caso, la unica solucion es esperar a que el
autor original (Abhishek890) publique una version actualizada, o contribuir el fix
al repo de NewPipeExtractor.
