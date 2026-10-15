package com.fibrocoir.app.core.google

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Signs in to Google as the app's service account (assets/service-account.json, added at build
 * time from a GitHub secret) and hands out access tokens for the Sheets API.
 * Tokens last an hour; one is kept and reused until a minute before it runs out.
 */
class GoogleAuth(private val context: Context, private val http: OkHttpClient) {

    @Serializable
    private data class Key(
        val client_email: String,
        val private_key: String,
        val token_uri: String = "https://oauth2.googleapis.com/token",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val key: Key? by lazy {
        try {
            context.assets.open(KEY_FILE).bufferedReader().use { json.decodeFromString(Key.serializer(), it.readText()) }
        } catch (_: Exception) {
            null
        }
    }

    /** False when the APK was built without the service-account key. */
    val configured: Boolean get() = key != null

    /** The address sheets must be shared with. */
    val accountEmail: String? get() = key?.client_email

    private val lock = Mutex()
    private var token: String? = null
    private var expiresAt = 0L

    suspend fun accessToken(): String = lock.withLock {
        val now = System.currentTimeMillis()
        token?.let { if (now < expiresAt - 60_000) return it }
        val k = key ?: throw SheetsException(
            "This app was built without its Google key. Install the version from the office.", 0
        )
        val (t, ttl) = fetchToken(k)
        token = t
        expiresAt = now + ttl * 1000
        t
    }

    /** Forget the token, e.g. after Google rejected it. */
    fun invalidate() {
        token = null
        expiresAt = 0
    }

    private suspend fun fetchToken(k: Key): Pair<String, Long> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", signedJwt(k))
            .build()
        val req = Request.Builder().url(k.token_uri).post(body).build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw SheetsException("Google refused the app's key (${res.code}). $text".take(300), res.code)
                }
                val o = json.parseToJsonElement(text).jsonObject
                o.getValue("access_token").jsonPrimitive.content to (o["expires_in"]?.jsonPrimitive?.long ?: 3600L)
            }
        } catch (e: IOException) {
            throw OfflineException()
        }
    }

    private fun signedJwt(k: Key): String {
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = """{"iss":"${k.client_email}","scope":"$SCOPES","aud":"${k.token_uri}","iat":$now,"exp":${now + 3600}}"""
        val unsigned = b64(header.toByteArray()) + "." + b64(claims.toByteArray())
        val pem = k.private_key
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .trim()
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pem, Base64.DEFAULT)))
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(unsigned.toByteArray())
        }.sign()
        return unsigned + "." + b64(sig)
    }

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val KEY_FILE = "service-account.json"
        const val SCOPES = "https://www.googleapis.com/auth/spreadsheets"
    }
}

/** Something went wrong talking to Google. `code` is the HTTP status (0 if none). */
open class SheetsException(message: String, val code: Int) : Exception(message)

/** No internet connection (or Google unreachable). */
class OfflineException(message: String = "No internet connection.") : SheetsException(message, 0)
