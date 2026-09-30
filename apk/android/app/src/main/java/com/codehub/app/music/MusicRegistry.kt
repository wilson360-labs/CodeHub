package com.codehub.app.music

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.codehub.app.music.parser.ExtensionParser
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.helpers.WebViewClient
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider
import dev.brahmkshatriya.echo.common.providers.MessageFlowProvider
import dev.brahmkshatriya.echo.common.providers.MetadataProvider
import dev.brahmkshatriya.echo.common.providers.NetworkConnectionProvider
import dev.brahmkshatriya.echo.common.providers.SettingsProvider
import dev.brahmkshatriya.echo.common.providers.WebViewClientProvider
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap

// Registro de extensiones de música Echo del reproductor de CodeHub.
//
// Escanea:
//  - paquetes instalados con el feature `dev.brahmkshatriya.echo.MUSIC`
//    (como hace la app de Echo con `AppRepository`), y
//  - APKs en `filesDir/extensions/*.apk` (`FileRepository`).
//
// Construye cada MusicExtension inyectando proveedores de CodeHub
// (metadata, settings por extensión en SharedPreferences, settings
// globales, mensajes, red y webview). La selección de extensión actual
// se guarda en `music_registry`.
class MusicRegistry(private val context: Context) {

    private val parser = ExtensionParser(context)
    private val registryPrefs = context.getSharedPreferences("music_registry", Context.MODE_PRIVATE)

    // Flujo compartido de mensajes que las extensiones envían (login, etc.).
    val messageFlow = MutableSharedFlow<Message>()

    // Cliente webview de las extensiones (login vía dialog).
    val webViewClient: WebViewClient = MusicWebViewClient(context)

    private val appMap = WeakHashMap<String, Pair<String, Result<Pair<Metadata, Lazy<ExtensionClient>>>>>()
    private val fileMap = WeakHashMap<String, Pair<String, Result<Pair<Metadata, Lazy<ExtensionClient>>>>>()
    private var cache: List<MusicExtension>? = null

    suspend fun music(): List<MusicExtension> = cache ?: reload()

    /** Errores de carga de la última recarga (APKs de extensión inválidos). */
    var lastErrors: List<String> = emptyList()
        private set

    suspend fun reload(): List<MusicExtension> = withContext(Dispatchers.IO) {
        val apps = parser.getAllDynamically(ImportType.App, appMap, installedPackages())
        val files = parser.getAllDynamically(ImportType.File, fileMap, extensionFiles())
        val all = apps + files
        lastErrors = all.mapNotNull { r ->
            r.exceptionOrNull()?.let { (it.cause ?: it).message ?: it.toString() }
        }
        all.mapNotNull { result ->
            val pair = result.getOrNull() ?: return@mapNotNull null
            if (pair.first.type != ExtensionType.MUSIC || !pair.first.isEnabled) return@mapNotNull null
            injected(pair.first, pair.second)
        }.also { cache = it }
    }

    fun invalidate() {
        cache = null
    }

    private fun injected(
        metadata: Metadata, lazy: Lazy<ExtensionClient>
    ): MusicExtension = MusicExtension(
        metadata,
        Injectable<ExtensionClient>(
            { lazy.value },
            mutableListOf<suspend ExtensionClient.() -> Unit>({
                if (this is MetadataProvider) setMetadata(metadata)
                if (this is MessageFlowProvider) setMessageFlow(messageFlow)
                if (this is GlobalSettingsProvider)
                    setGlobalSettings(toSettings(globalPrefs))
                if (this is SettingsProvider) setSettings(toSettings(metadata.prefs()))
                if (this is NetworkConnectionProvider)
                    setNetworkConnection(currentNetwork())
                if (this is WebViewClientProvider) setWebViewClient(webViewClient)
                onInitialize()
                onExtensionSelected()
            })
        )
    )

    // ------------------------------------------------------------------
    // Selección actual
    // ------------------------------------------------------------------

    fun currentId(): String? = registryPrefs.getString("current", null)

    fun select(id: String?) {
        if (id == null) registryPrefs.edit().remove("current").apply()
        else registryPrefs.edit().putString("current", id).apply()
    }

    // ------------------------------------------------------------------
    // Escaneo
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<File> {
        val pm = context.packageManager
        val infos = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_CONFIGURATIONS.toLong())
            )
        else pm.getInstalledPackages(PackageManager.GET_CONFIGURATIONS)
        return infos.mapNotNull { pi ->
            val feats = pi.reqFeatures ?: return@mapNotNull null
            if (feats.none { it.name?.startsWith(ExtensionParser.FEATURE) == true })
                return@mapNotNull null
            val src = pi.applicationInfo?.sourceDir ?: return@mapNotNull null
            File(src).takeIf { it.isFile }
        }
    }

    private fun extensionFiles(): List<File> {
        val dir = File(context.filesDir, "extensions")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            ?.toList() ?: emptyList()
    }

    // ------------------------------------------------------------------
    // Gestión de extensiones (importar / quitar)
    // ------------------------------------------------------------------

    /** Copia un APK de extensión a `filesDir/extensions`, lo valida y lo deja de solo lectura
     *  (Android 14+ rechaza DexClassLoader sobre ficheros escribibles). */
    suspend fun importStream(input: InputStream): Result<Metadata> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "ext_import_${System.nanoTime()}.apk")
            try {
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val meta = parser.parseManifest(tmp, ImportType.File)
                require(meta.type == ExtensionType.MUSIC) {
                    "La extensión es de tipo ${meta.type}, se requiere MUSIC"
                }
                val dir = File(context.filesDir, "extensions").apply { mkdirs() }
                val target = File(dir, "${meta.id}.apk")
                if (target.exists()) { target.setWritable(true); target.delete() }
                tmp.copyTo(target, overwrite = true)
                target.setReadOnly()
                invalidate()
                meta
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun importUri(uri: Uri): Result<Metadata> {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return Result.failure(IllegalStateException("No se pudo abrir el archivo"))
        return importStream(stream)
    }

    suspend fun importUrl(url: String): Result<Metadata> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Solo se admiten URLs https://" }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CodeHub-Music")
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            importStream(conn.inputStream).getOrThrow()
        }
    }

    /** Quita una extensión importada (los paquetes instalados se desinstalan desde Android). */
    fun remove(ext: MusicExtension): Boolean {
        if (ext.metadata.importType != ImportType.File) return false
        val f = File(ext.metadata.path)
        f.setWritable(true)
        val ok = f.delete()
        if (currentId() == ext.id) select(null)
        invalidate()
        return ok
    }

    // ------------------------------------------------------------------
    // Settings / red
    // ------------------------------------------------------------------

    val globalPrefs: SharedPreferences
        get() = context.getSharedPreferences("music_global", Context.MODE_PRIVATE)

    fun Metadata.prefs(): SharedPreferences =
        context.getSharedPreferences("${type.name}-$id", Context.MODE_PRIVATE)

    fun toSettings(prefs: SharedPreferences) = object : Settings {
        override fun getString(key: String) = prefs.getString(key, null)
        override fun putString(key: String, value: String?) {
            val e = prefs.edit()
            if (value != null) e.putString(key, value) else e.remove(key)
            e.apply()
        }

        override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)
        override fun putStringSet(key: String, value: Set<String>?) {
            val e = prefs.edit()
            if (value != null) e.putStringSet(key, value) else e.remove(key)
            e.apply()
        }

        override fun getInt(key: String): Int? =
            if (prefs.contains(key)) prefs.getInt(key, 0) else null

        override fun putInt(key: String, value: Int?) {
            val e = prefs.edit()
            if (value != null) e.putInt(key, value) else e.remove(key)
            e.apply()
        }

        override fun getBoolean(key: String): Boolean? =
            if (prefs.contains(key)) prefs.getBoolean(key, false) else null

        override fun putBoolean(key: String, value: Boolean?) {
            val e = prefs.edit()
            if (value != null) e.putBoolean(key, value) else e.remove(key)
            e.apply()
        }
    }

    private fun currentNetwork(): NetworkConnection {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } else null
        return when {
            capabilities == null -> NetworkConnection.NotConnected
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ->
                NetworkConnection.Unmetered
            else -> NetworkConnection.Metered
        }
    }
}