package com.fibrocoir.app

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native HTTP for bundled pages, so they never depend on browser CORS rules.
 * Only Apps Script web apps can be reached (and the googleusercontent.com address they
 * redirect to with the answer).
 */
object Http {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000 // uploads of bill photos / PDFs can take a while

    class Result(val code: Int, val type: String, val body: ByteArray)

    /** An Apps Script web app address: https://script.google.com/macros/... (or /a/macros/...). */
    fun allowed(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == "script.google.com" &&
            (uri.path?.let { it.startsWith("/macros/") || it.startsWith("/a/macros/") } == true)

    private fun redirectAllowed(url: URL): Boolean =
        url.protocol == "https" &&
            (url.host == "script.google.com" || url.host.endsWith(".googleusercontent.com"))

    /**
     * GET, or POST with a text/plain body. Redirects are followed by hand: Apps Script answers a
     * POST with 302 to googleusercontent.com, which must be fetched with GET.
     */
    fun fetch(method: String, url: String, body: String?): Result {
        var target = URL(url)
        var verb = method
        var payload = body
        for (hop in 0 until 6) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                requestMethod = verb
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (verb == "POST" && payload != null) {
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: return Result(502, "application/json", err("The script sent a redirect without an address."))
                    target = URL(target, location)
                    if (!redirectAllowed(target)) {
                        return Result(502, "application/json", err("The script redirected somewhere unexpected."))
                    }
                    if (code != 307 && code != 308) { verb = "GET"; payload = null }
                    continue
                }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val data = stream?.use { it.readBytes() } ?: ByteArray(0)
                val type = conn.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/json"
                return Result(code, type, data)
            } finally {
                conn.disconnect()
            }
        }
        return Result(502, "application/json", err("Too many redirects from the script."))
    }

    fun err(message: String, offline: Boolean = false): ByteArray =
        ("{\"ok\":false,\"error\":" + org.json.JSONObject.quote(message) +
            (if (offline) ",\"offline\":true" else "") + "}").toByteArray(Charsets.UTF_8)
}

/**
 * GET https://appassets.androidplatform.net/proxy?url=<Apps Script URL> from a bundled page:
 * the app fetches it and hands back the answer (same origin as the page, so no CORS).
 */
object DataProxy {
    fun handle(request: WebResourceRequest): WebResourceResponse {
        val target = request.url.getQueryParameter("url")?.let { Uri.parse(it) }
        if (request.method != "GET" || target == null || !Http.allowed(target)) {
            return response(Http.Result(400, "application/json", Http.err("This address can't be fetched from the app.")))
        }
        return try {
            response(Http.fetch("GET", target.toString(), null))
        } catch (_: Exception) {
            response(Http.Result(502, "application/json", Http.err("No connection to the server.", offline = true)))
        }
    }

    private fun response(r: Http.Result): WebResourceResponse {
        val reason = when {
            r.code in 200..299 -> "OK"
            r.code == 400 -> "Bad Request"
            r.code == 502 -> "Bad Gateway"
            else -> "Error"
        }
        return WebResourceResponse(
            r.type, "utf-8", r.code, reason,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(r.body)
        )
    }
}
