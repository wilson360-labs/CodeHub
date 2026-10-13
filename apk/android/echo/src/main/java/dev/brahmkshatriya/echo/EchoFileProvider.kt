package dev.brahmkshatriya.echo

import androidx.core.content.FileProvider

/**
 * Subclase de [FileProvider] solo para el injerto en CodeHub.
 *
 * CodeHub (`:app`) ya declara `androidx.core.content.FileProvider` con la
 * authority `${applicationId}.fileprovider`. El manifest merger fusiona los
 * `<provider>` por `android:name`, así que dos entradas con la misma clase pero
 * distinta authority (y distinto `FILE_PROVIDER_PATHS`) son un conflicto duro
 * ("Manifest merger failed with multiple errors").
 *
 * Al declarar aquí una clase propia, el provider de Echo pasa a ser un nodo
 * distinto y ambos conviven. La authority sigue siendo `${applicationId}.provider`
 * (la que usa [dev.brahmkshatriya.echo.extensions.InstallationUtils] vía
 * `FileProvider.getUriForFile`), que resuelve por authority, no por clase.
 */
class EchoFileProvider : FileProvider()
