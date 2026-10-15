package com.fibrocoir.app

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bundled pages fetch their data from https://appassets.androidplatform.net/proxy?url=<Apps Script URL>.
 * The app makes that request itself and hands back the answer, so the page never needs
 * Google to send browser CORS headers. Only Apps Script web apps can be reached this way.
 */
object DataProxy {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    fun handle(request: WebResourceRequest): WebResourceResponse {
        val target = request.url.getQueryParameter("url")?.let { Uri.parse(it) }
        if (request.method != "GET" || target == null || !allowed(target)) {
            return json(400, "Bad Request", """{"ok":false,"error":"This address can't be fetched from the app."}""")
        }
        return try {
            val conn = (URL(target.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true // script.google.com answers with a redirect to googleusercontent.com
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                conn.disconnect()
                return json(502, "Bad Gateway", """{"ok":false,"error":"The script sent an unexpected redirect."}""")
            }
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?: ByteArrayInputStream(ByteArray(0))
            val type = conn.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/json"
            val reason = conn.responseMessage?.ifBlank { null } ?: if (code < 400) "OK" else "Error"
            WebResourceResponse(type, "utf-8", code, reason, mapOf("Cache-Control" to "no-store"), body)
        } catch (_: Exception) {
            json(502, "Bad Gateway", """{"ok":false,"error":"No connection to the server.","offline":true}""")
        }
    }

    private fun allowed(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == "script.google.com" &&
            (uri.path?.let { it.startsWith("/macros/") || it.startsWith("/a/macros/") } == true)

    private fun json(code: Int, reason: String, body: String) = WebResourceResponse(
        "application/json", "utf-8", code, reason,
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    )
}
