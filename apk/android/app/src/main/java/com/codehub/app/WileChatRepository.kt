package com.codehub.app

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Owns authenticated WIL.E HTTP calls and SSE parsing, separate from Android view lifecycle. */
internal class WileChatRepository(private val accessToken: String) {
    companion object {
        const val BASE_URL = "https://codehub-98s6.onrender.com"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun streamChat(
        url: String,
        payload: JSONObject,
        onEvent: (String, JSONObject) -> Unit,
        onFinished: (String?) -> Unit,
    ): Call {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = client.newCall(apiRequest(url).post(body).build())
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onFinished("No pude conectar con WIL.E. Revisa tu conexión e inténtalo de nuevo.")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                var failure: String? = null
                response.use { res ->
                    if (!res.isSuccessful) {
                        val detail = res.body?.string()?.let {
                            runCatching { JSONObject(it).optString("error") }.getOrNull()
                        }.orEmpty()
                        failure = detail.ifBlank { "WIL.E respondió con un error HTTP ${res.code}." }
                    } else {
                        val source = res.body?.source()
                        if (source == null) failure = "El servidor respondió sin contenido."
                        else {
                            var event = ""
                            val data = StringBuilder()
                            try {
                                while (!source.exhausted()) {
                                    val line = source.readUtf8Line() ?: break
                                    when {
                                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                                        line.startsWith("data:") -> {
                                            if (data.isNotEmpty()) data.append('\n')
                                            data.append(line.substringAfter(':').trimStart())
                                        }
                                        line.isBlank() -> {
                                            if (data.isNotEmpty()) {
                                                val parsed = runCatching { JSONObject(data.toString()) }.getOrNull()
                                                if (parsed != null) onEvent(event, parsed)
                                            }
                                            event = ""
                                            data.setLength(0)
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                if (!call.isCanceled()) failure = "La conexión se interrumpió antes de completar la respuesta."
                            }
                        }
                    }
                }
                if (!call.isCanceled()) onFinished(failure)
            }
        })
        return call
    }

    fun get(url: String, callback: (JSONObject?, String?) -> Unit): Call = request(url, null, callback)

    fun post(url: String, payload: JSONObject, callback: (JSONObject?, String?) -> Unit): Call =
        request(url, payload, callback)

    private fun request(url: String, payload: JSONObject?, callback: (JSONObject?, String?) -> Unit): Call {
        val builder = apiRequest(url)
        if (payload != null) builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(builder.build())
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback(null, "No se pudo completar la solicitud. Revisa tu conexión e inténtalo otra vez.")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val parsed = response.use { res ->
                    val value = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrElse {
                        JSONObject().put("error", "Error HTTP ${res.code}")
                    }
                    if (res.isSuccessful) value to null
                    else null to value.optString("error").ifBlank { "Error HTTP ${res.code}" }
                }
                if (!call.isCanceled()) callback(parsed.first, parsed.second)
            }
        })
        return call
    }

    private fun apiRequest(url: String) = Request.Builder().url(url).apply {
        if (accessToken.isNotBlank()) header("Authorization", "Bearer $accessToken")
    }
}
