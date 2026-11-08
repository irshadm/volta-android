package dev.irshadm.volta.core.maps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Shared transport for Google Maps Platform REST calls: adds the key and the Android identity headers, and turns
 * any failure into a [GoogleApiException]. Feature clients (routes, places, elevation) build on this, so the key
 * handling lives in one place.
 */
class GoogleApi(
    private val http: OkHttpClient,
    private val identity: AppIdentity,
    private val keys: MapsKeyProvider,
) {
    private val jsonType = "application/json".toMediaType()

    /** POSTs [body] with the key from [MapsKeyProvider]; throws [MissingMapsKeyException] when none is set. */
    suspend fun post(url: String, body: String, fieldMask: String): String = post(url, body, fieldMask, key = keys.currentKey()?.takeIf { it.isNotBlank() } ?: throw MissingMapsKeyException())

    /** POSTs with an explicit [key]; used to try a key before it is saved. */
    suspend fun post(url: String, body: String, fieldMask: String, key: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody(jsonType))
            .header("X-Goog-Api-Key", key)
            .header("X-Goog-FieldMask", fieldMask)
            .header("X-Android-Package", identity.packageName)
            .header("X-Android-Cert", identity.sha1Hex)
            .build()
        try {
            http.newCall(request).execute().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful) throw GoogleErrorParser.parse(res.code, text)
                text
            }
        } catch (e: IOException) {
            throw GoogleApiException(KeyProblem.Network, httpCode = 0, googleMessage = e.message ?: "No connection", cause = e)
        }
    }

    /**
     * GETs a legacy Maps endpoint (Elevation) with an explicit [key]. These answer HTTP 200 even when they refuse, with
     * a `status` other than OK in the body, so that case is turned into the same [GoogleApiException] as the newer APIs.
     */
    suspend fun getLegacy(url: String, key: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url.toHttpUrl().newBuilder().addQueryParameter("key", key).build())
            .header("X-Android-Package", identity.packageName)
            .header("X-Android-Cert", identity.sha1Hex)
            .build()
        try {
            http.newCall(request).execute().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful || legacyStatus(text) !in LEGACY_OK) throw GoogleErrorParser.parse(res.code, text)
                text
            }
        } catch (e: IOException) {
            throw GoogleApiException(KeyProblem.Network, httpCode = 0, googleMessage = e.message ?: "No connection", cause = e)
        }
    }

    private fun legacyStatus(body: String): String? = Regex("\"status\"\\s*:\\s*\"([A-Z_]+)\"").find(body)?.groupValues?.get(1)

    private companion object {
        val LEGACY_OK = setOf("OK", "ZERO_RESULTS")
    }
}
