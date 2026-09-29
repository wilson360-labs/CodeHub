package com.codehub.app.music

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.brahmkshatriya.echo.common.helpers.WebViewClient as EchoWebViewClient
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cliente webview inyectado a las extensiones (login OAuth, captura de
 * tokens...) mediante un Dialog con WebView. Soporta los tres tipos de
 * [WebViewRequest]: Headers, Cookie y Evaluate, con timeout.
 *
 * Port compacto de `dev.brahmkshatriya.echo.ui.extensions.WebViewUtils`
 * + `WebViewClientFactory` (sin Fragmentos ni la lib de inspección).
 */
@SuppressLint("SetJavaScriptEnabled")
class MusicWebViewClient(private val appContext: Context) : EchoWebViewClient {

    private var activityRef: WeakReference<Activity> = WeakReference(null)

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef.get() === activity) activityRef.clear()
    }

    override suspend fun await(
        showWebView: Boolean,
        reason: String,
        request: WebViewRequest<String>,
    ): Result<String?> = runCatching {
        val result = run(showWebView, reason, request)
        result.getOrNull()?.toString()
    }

    /**
     * Ejecuta un [WebViewRequest] genérico y devuelve el resultado tipado
     * de la cadena Headers -> Cookie -> Evaluate.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun run(
        showWebView: Boolean,
        reason: String,
        request: WebViewRequest<*>,
    ): Result<Any?> {
        val activity = withContext(Dispatchers.Main) { activityRef.get() }
        if (activity == null || activity.isFinishing) {
            return Result.failure(IllegalStateException("Sin actividad para el webview"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val deferred = kotlinx.coroutines.CompletableDeferred<Result<Any?>>()
            withContext(Dispatchers.Main) { setup(activity, reason, request, scope, deferred) }
            val result = withTimeout(request.maxTimeout) { deferred.await() }
            return result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return Result.failure(Exception("WebView request timed out"))
        } finally {
            scope.cancel()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setup(
        activity: Activity,
        reason: String,
        target: WebViewRequest<*>,
        scope: CoroutineScope,
        deferred: kotlinx.coroutines.CompletableDeferred<Result<Any?>>,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val dialog = Dialog(activity)
        dialog.setCancelable(false)

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#101018"))
        root.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))

        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(0, 0, 0, dp(4f))

        val title = TextView(activity)
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        title.text = reason
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val progress = ProgressBar(activity)
        header.addView(progress, LinearLayout.LayoutParams(dp(26f), dp(26f)))
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val webView = WebView(activity)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
        )
        dialog.addContentView(
            root,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val requests = Collections.synchronizedList(mutableListOf<NetworkRequest>())
        val bridge = Bridge()
        var done = false

        val stopRegex = target.stopUrlRegex
        val interceptRegex = if (target is WebViewRequest.Headers<*>) target.interceptUrlRegex else null

        fun intercept(request: NetworkRequest, url: String) {
            if (target is WebViewRequest.Headers<*>) {
                if (interceptRegex == null || interceptRegex.matches(url)) {
                    requests.add(request)
                }
            }
            if (done || stopRegex.find(url) == null) return
            synchronized(this) {
                if (done) return
                done = true
            }
            scope.launch {
                progress.visibility = View.GONE
                val result = runCatching {
                    var headerResult: Any? = null
                    var cookieResult: Any? = null
                    var evalResult: Any? = null
                    if (target is WebViewRequest.Headers<*>) {
                        headerResult = target.onStop(requests.toList())
                    }
                    if (target is WebViewRequest.Cookie<*>) {
                        val cookie = CookieManager.getInstance().getCookie(request.url) ?: ""
                        cookieResult = target.onStop(NetworkRequest(request.url), cookie)
                    }
                    if (target is WebViewRequest.Evaluate<*>) {
                        val data = evalJSAsync(webView, bridge, target.javascriptToEvaluate)
                        evalResult = target.onStop(NetworkRequest(request.url), data)
                    }
                    evalResult ?: cookieResult ?: headerResult
                }
                dialog.dismiss()
                deferred.complete(result)
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
                if (done) return
                val ev = target as? WebViewRequest.Evaluate<*>
                val js = ev?.javascriptToEvaluateOnPageStart ?: return
                scope.launch {
                    runCatching { evalJSAsync(webView, bridge, js) }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                request?.let { r ->
                    val url = r.url.toString()
                    val headers = HashMap<String, String>()
                    r.requestHeaders?.let { headers.putAll(it) }
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (cookie != null) headers["Cookie"] = cookie
                    intercept(NetworkRequest(url, headers), url)
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                val headers = HashMap<String, String>()
                request.requestHeaders?.let { headers.putAll(it) }
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie != null) headers["Cookie"] = cookie
                intercept(NetworkRequest(url, headers), url)
                return null
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = WebSettings.getDefaultUserAgent(activity)
            cacheMode = if (target.dontCache) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        webView.addJavascriptInterface(bridge, "bridge")
        val initial = target.initialUrl
        initial.lowerCaseHeaders["user-agent"]?.let { webView.settings.userAgentString = it }

        dialog.setOnShowListener { _ ->
            try {
                webView.loadUrl(initial.url, initial.headers)
            } catch (e: Throwable) {
                deferred.complete(Result.failure(e))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private suspend fun evalJSAsync(
        webView: WebView,
        bridge: Bridge,
        js: String,
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val asyncFunction = if (js.startsWith("async function")) js
            else if (js.startsWith("function")) "async $js"
            else {
                continuation.resumeWithException(Exception("Invalid JS function, must start with async or function"))
                return@suspendCancellableCoroutine
            }
            bridge.onResult = continuation::resume
            bridge.onError = continuation::resumeWithException
            val newJs = """
                (function() {
                    try {
                        const fun = $asyncFunction;
                        fun().then((result) => {
                            bridge.putJsResult(result);
                        }).catch((error) => {
                            bridge.putJsError(error.message || error.toString());
                        });
                    } catch (error) {
                        bridge.putJsError(error.message || error.toString());
                    }
                })()
                """.trimIndent()
            try {
                webView.evaluateJavascript(newJs, null)
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
            continuation.invokeOnCancellation {
                runCatching { webView.evaluateJavascript("javascript:window.stop();", null) }
            }
        }
    }

    @Suppress("unused")
    class Bridge {
        var onError: ((Throwable) -> Unit)? = null
        var onResult: ((String?) -> Unit)? = null

        @JavascriptInterface
        fun putJsResult(result: String?) {
            onResult?.invoke(result)
        }

        @JavascriptInterface
        fun putJsError(error: String?) {
            onError?.invoke(Exception(error ?: "Unknown JavaScript error"))
        }
    }
}