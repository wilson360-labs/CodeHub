# CodeHub para iOS

Este módulo inicia la app de iPhone en SwiftUI. No modifica ni reemplaza la app Android.

## Compilar un IPA de CI

El workflow `Build CodeHub iOS IPA` usa Xcode en un runner macOS y genera un IPA **sin firmar** para descargar como artefacto de GitHub Actions. Puede compilarse y revisarse, pero para instalarlo en un iPhone hay que firmarlo con un certificado y un perfil de aprovisionamiento válidos.

El proyecto Xcode se genera desde `project.yml` con XcodeGen. Para compilar localmente en macOS:

```sh
brew install xcodegen
xcodegen generate --spec ios/project.yml --project ios/CodeHub.xcodeproj
xcodebuild -project ios/CodeHub.xcodeproj -scheme CodeHub -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

La pantalla inicial enlaza con CodeHub web en Safari. Las funciones nativas de iOS y la firma/distribución se integrarán en fases posteriores.
