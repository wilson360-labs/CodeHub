# CodeBeat YouTube Music Extension — Mejorado

## Que se corrigio

1. **Reorden del fallback**: cuando hay sesion autenticada (Google login), se prueba
   YtmKt primero (usa la sesion) en vez de RealNewPipe (anonimo, siempre 403).
2. **NewPipeExtractor actualizado** a la ultima version (soporte de PO tokens).
3. **Aviso al usuario**: si no esta autenticado, se muestra un mensaje claro
   pidiendo iniciar sesion con Google.

## Archivos modificados

- `YouTubeStreamResolver.kt` — reorden del fallback + check de autenticacion.
- `build.gradle.kts` — newpipe version bump.
- `libs.versions.toml` — newpipe version bump.

## Como compilar

1. Forkea `abhishekkchoudharyy/Eco-Youtube_Music`.
2. Copia los archivos modificados a `ext/src/main/java/...` y `ext/`.
3. Actualiza `gradle.properties` con tus datos (extName, extId, etc.).
4. Configura secrets `KEYSTORE_B64` y `PASSWORD` en GitHub Actions.
5. Ejecuta el workflow `build-ytm-extension.yml` en CodeHub (pon tu fork).
6. Hospeda el APK resultante y actualiza el catalogo CodeBeat.

## Nota importante

YouTube cambia su anti-bot frecuentemente. Este fix mejora las probabilidades pero no
garantiza 100% de exito. Si YouTube vuelve a bloquear, se necesitara actualizar
NewPipeExtractor de nuevo.
