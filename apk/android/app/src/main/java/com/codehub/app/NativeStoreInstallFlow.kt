package com.codehub.app

import android.app.Activity
import android.app.AlertDialog

/** One explicit Shizuku request unlocks silent installs for every store entry point. */
object NativeStoreInstallFlow {
    fun request(activity: Activity, onContinue: (preferSilent: Boolean) -> Unit) {
        if (ShizukuInstaller.isReady()) {
            onContinue(true)
            return
        }
        if (!ShizukuInstaller.isBinderAlive()) {
            onContinue(false)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Instalación automática")
            .setMessage("Shizuku está activo. Puedes autorizar CodeHub para instalar y actualizar apps sin repetir la confirmación de Android. También puedes continuar con el instalador normal.")
            .setPositiveButton("Solicitar permiso") { _, _ ->
                ShizukuInstaller.requestPermission { granted ->
                    activity.runOnUiThread {
                        if (granted) onContinue(true)
                        else AlertDialog.Builder(activity)
                            .setMessage("No se concedió el permiso de Shizuku. ¿Quieres continuar con el instalador de Android?")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Instalar con Android") { _, _ -> onContinue(false) }
                            .show()
                    }
                }
            }
            .setNeutralButton("Instalador de Android") { _, _ -> onContinue(false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
