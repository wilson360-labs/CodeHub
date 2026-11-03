package com.codehub.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.TimeUnit

internal fun interface NativeTokenCallback {
    fun onToken(token: String?)
}

/** Encrypts the Supabase session with an AES key held by Android Keystore. */
internal object NativeAuthSessionStore {
    private const val PREFS = "codehub_native_auth"
    private const val SESSION = "encrypted_session"
    private const val KEY_ALIAS = "codehub_native_session_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun save(context: Context, session: JSONObject, user: JSONObject? = null) {
        val stored = JSONObject(session.toString())
        val previous = read(context)
        val profile = user ?: previous?.optJSONObject("user")
        if (profile != null) stored.put("user", profile)
        val id = user?.optString("id")?.takeIf { it.isNotBlank() } ?: previous?.optString("id").orEmpty()
        if (id.isNotBlank()) stored.put("id", id)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(stored.toString().toByteArray(StandardCharsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SESSION, value).apply()
    }

    @JvmStatic
    fun saveWebSession(context: Context, webSession: JSONObject) {
        val access = webSession.optString("token")
        if (access.isBlank()) { clear(context); return }
        val session = JSONObject()
            .put("access_token", access)
            .put("refresh_token", webSession.optString("refresh_token"))
            .put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        save(context, session, webSession.optJSONObject("user")?.let { user ->
            JSONObject(user.toString()).put("id", webSession.optString("id"))
        })
    }

    fun read(context: Context): JSONObject? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SESSION, null) ?: return null
        return runCatching {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
        }.getOrElse {
            clear(context)
            null
        }
    }

    fun accessToken(context: Context): String = read(context)?.optString("access_token").orEmpty()

    @JvmStatic
    fun webSession(context: Context): String? {
        val stored = read(context) ?: return null
        val access = stored.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val user = stored.optJSONObject("user") ?: JSONObject()
        return JSONObject()
            .put("id", stored.optString("id"))
            .put("user", user)
            .put("token", access)
            .put("refresh_token", stored.optString("refresh_token"))
            .toString()
    }

    @JvmStatic
    fun refreshIfNeeded(context: Context, callback: NativeTokenCallback) {
        val session = read(context)
        val access = session?.optString("access_token").orEmpty()
        if (access.isBlank()) { callback.onToken(null); return }
        val expiresAt = session?.optLong("expires_at") ?: 0L
        if (expiresAt == 0L || expiresAt > System.currentTimeMillis() / 1000 + 90) {
            callback.onToken(access)
            return
        }
        val refresh = session?.optString("refresh_token").orEmpty()
        if (refresh.isBlank()) { clear(context); callback.onToken(null); return }
        val body = JSONObject().put("refresh_token", refresh).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("${WileChatRepository.BASE_URL}/api/auth/refresh").post(body).build()
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            .newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = callback.onToken(access)

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    var unauthorized = false
                    val result = response.use { res ->
                        unauthorized = res.code == 401
                        if (!res.isSuccessful) null else runCatching {
                            JSONObject(res.body?.string().orEmpty()).optJSONObject("session")
                        }.getOrNull()
                    }
                    if (result == null && !unauthorized) {
                        callback.onToken(access)
                    } else if (result == null || result.optString("access_token").isBlank()) {
                        clear(context)
                        callback.onToken(null)
                    } else {
                        save(context, result)
                        callback.onToken(result.optString("access_token"))
                    }
                }
            })
    }

    @JvmStatic
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SESSION).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
