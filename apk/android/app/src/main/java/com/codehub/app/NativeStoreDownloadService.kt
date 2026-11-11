package com.codehub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * True while any CodeHub UI (store / my apps) is in the foreground. The
 * download service uses it to avoid duplicating toasts with notifications
 * when the user is watching the sheet. Activities set it in onResume/onPause.
 */
object NativeStorePresence {
    @Volatile var visible: Boolean = false
}

/**
 * Foreground service that keeps APK transfers from the CodeHub Store alive
 * while the user is away from the app, mirroring the Orion DownloadService:
 * a per-app progress notification with a cancel action and a "Listo para
 * instalar" hand-off when the silent install is not available.
 *
 * Type dataSync (FOREGROUND_SERVICE_DATA_SYNC is declared in the manifest).
 * The Android 15+ 6-hour limit of that type suits short store downloads;
 * long-lived monitoring must use specialUse (QuakeDetectorService).
 */
class NativeStoreDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "codehub_store_downloads"
        private const val GROUP_KEY = "com.codehub.app.STORE_TRANSFERS"
        private const val ACTION_CANCEL = "com.codehub.app.STORE_CANCEL_TRANSFER"
        private const val EXTRA_OPEN_SELF_UPDATE = "com.codehub.app.EXTRA_OPEN_SELF_UPDATE_INSTALLER"
        private const val EXTRA_APP_ID = "com.codehub.app.EXTRA_APP_ID"
        private const val EXTRA_APP_NAME = "com.codehub.app.EXTRA_APP_NAME"
        private const val FOREGROUND_ID = 40321

        /** Name card extra: NativeStoreActivity may open the verified APK. */
        @JvmStatic val EXTRA_TAP_APP_ID = EXTRA_APP_ID
        @JvmStatic val EXTRA_TAP_APP_NAME = EXTRA_APP_NAME

        private fun intent(context: Context): Intent =
            Intent(context, NativeStoreDownloadService::class.java)

        /** Called by the installer engine before launching a transfer. */
        @JvmStatic
        fun ensureStarted(context: Context) {
            runCatching {
                context.startForegroundService(intent(context))
            }.onFailure { android.util.Log.w("StoreDownloadSvc", "ensureStarted", it) }
        }

        private fun cancelIntent(context: Context, appId: String): PendingIntent =
            PendingIntent.getService(
                context,
                (ACTION_CANCEL + appId).hashCode(),
                intent(context).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_APP_ID, appId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openStoreIntent(context: Context, appId: String, appName: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                ("storetap" + appId).hashCode(),
                Intent(context, NativeStoreActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_TAP_APP_ID, appId)
                    putExtra(EXTRA_TAP_APP_NAME, appName)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openSelfUpdateIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            "self-update-installer".hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_SELF_UPDATE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val appId = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
            if (appId.isNotBlank()) NativeStoreApkInstaller.cancel(appId)
            return START_NOT_STICKY
        }
        goForeground()
        if (!observing) {
            observing = true
            NativeStoreApkInstaller.transfers
                .onEach(::render)
                .launchIn(scope)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        runCatching {
            createChannel()
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        }.onFailure { android.util.Log.w("StoreDownloadSvc", "goForeground", it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Descargas de la tienda", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progreso de descargas de APK del CodeHub Store"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /* API 26+ uses channels by constructor; older builds keep the classic one. */
    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

    /** Diff the whole map; each transfer keeps one notification slot (appId hash). */
    private fun render(states: Map<String, ApkTransferState>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var hasActive = false
        states.forEach { (appId, state) ->
            when (state) {
                is ApkTransferState.Downloading -> {
                    hasActive = true
                    val percent = if (state.totalBytes > 0)
                        (state.downloadedBytes * 100 / state.totalBytes).toInt().coerceIn(0, 100) else 0
                    manager.notify(appId.hashCode(), builder()
                        .apply {
                            if (state.totalBytes > 0) setProgress(100, percent, false)
                            else setProgress(0, 0, true)
                            setOngoing(true)
                            setOnlyAlertOnce(true)
                            setSmallIcon(android.R.drawable.stat_sys_download)
                            setContentTitle("Descargando ${NativeStoreApkInstaller.displayName(appId)}")
                            setContentText(if (state.totalBytes > 0) "$percent% · APK oficial" else "Descargando APK oficial")
                            setGroup(GROUP_KEY)
                        }
                        .addAction(0, "Cancelar", cancelIntent(this, appId))
                        .build())
                }
                is ApkTransferState.Verifying -> {
                    hasActive = true
                    manager.notify(appId.hashCode(), builder()
                        .apply {
                            setOngoing(true)
                            setOnlyAlertOnce(true)
                            setSmallIcon(android.R.drawable.stat_sys_download)
                            setContentTitle("Verificando ${NativeStoreApkInstaller.displayName(appId)}")
                            setContentText("Comprobando el APK descargado")
                            setGroup(GROUP_KEY)
                        }
                        .build())
                }
                ApkTransferState.ReadyToInstall -> hasActive = true
                ApkTransferState.AwaitingUserConfirmation -> {
                    hasActive = true
                    if (NativeStoreApkInstaller.pendingApkFile(appId) != null) {
                        val name = NativeStoreApkInstaller.displayName(appId)
                        val selfUpdate = appId == NativeStoreApkInstaller.SELF_UPDATE_ID
                        val notification = builder()
                            .apply {
                                setOngoing(true)
                                setOnlyAlertOnce(true)
                                setSmallIcon(android.R.drawable.stat_sys_download_done)
                                setContentTitle(if (selfUpdate) "Actualización de CodeHub lista" else "Listo para instalar")
                                setContentText(if (selfUpdate) "Toca para continuar con el instalador de Android" else "Confirma la instalación de $name")
                                setGroup(GROUP_KEY)
                            }
                            .setContentIntent(if (selfUpdate) openSelfUpdateIntent(this) else openStoreIntent(this, appId, name))
                            .build()
                        manager.notify(appId.hashCode(), notification)
                    }
                }
                ApkTransferState.Installed -> {
                    if (appId == NativeStoreApkInstaller.SELF_UPDATE_ID) {
                        // BootReceiver confirma el reemplazo desde Package Manager
                        // y emite la notificación final una sola vez.
                        NativeStoreApkInstaller.clearUiStateOnly(appId)
                    } else {
                        maybeNotify(manager, appId, builder()
                            .apply {
                                setSmallIcon(android.R.drawable.stat_sys_download_done)
                                setContentTitle("Instalada")
                                setContentText("${NativeStoreApkInstaller.displayName(appId)} se instaló correctamente")
                                setGroup(GROUP_KEY)
                            }
                            .build())
                    }
                }
                is ApkTransferState.Failed -> {
                    maybeNotify(manager, appId, builder()
                        .apply {
                            setSmallIcon(android.R.drawable.stat_notify_error)
                            setContentTitle("Descarga fallida")
                            setContentText(state.message)
                            setGroup(GROUP_KEY)
                        }
                        .build())
                }
                ApkTransferState.Cancelled -> {
                    maybeNotify(manager, appId, builder()
                        .apply {
                            setSmallIcon(android.R.drawable.stat_sys_download_done)
                            setContentTitle("Cancelada")
                            setContentText("Descarga de ${NativeStoreApkInstaller.displayName(appId)} cancelada")
                            setGroup(GROUP_KEY)
                        }
                        .build())
                }
                else -> Unit
            }
        }
        if (!hasActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Terminal states: show a one-shot notification only when the sheet could
     * not present it (user left the UI); otherwise just clean the flow state.
     */
    private fun maybeNotify(manager: NotificationManager, appId: String, notification: Notification) {
        if (!NativeStorePresence.visible) {
            manager.notify(appId.hashCode(), notification)
        }
        NativeStoreApkInstaller.clearUiStateOnly(appId)
    }

    private fun buildForegroundNotification(): Notification =
        builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CodeHub Store")
            .setContentText("Descargas en curso")
            .setGroup(GROUP_KEY)
            .setOngoing(true)
            .build()
}
