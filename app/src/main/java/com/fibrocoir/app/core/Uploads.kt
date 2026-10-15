package com.fibrocoir.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.fibrocoir.app.BuildConfig
import com.fibrocoir.app.core.google.OfflineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

/** A file picked on the phone, ready to send. */
class PickedFile(val bytes: ByteArray, val mime: String, val name: String)

/**
 * Sends photos and bills to the "FCP Uploads" Apps Script (apps-script/uploads), which saves
 * them in Drive and returns a link. Address and key are set at build time from GitHub secrets.
 */
class Uploads(private val http: OkHttpClient) {
    data class Result(val id: String, val url: String, val createdFolderId: String)

    val configured: Boolean get() = BuildConfig.UPLOAD_URL.isNotBlank() && BuildConfig.UPLOAD_TOKEN.isNotBlank()

    suspend fun upload(
        file: PickedFile, folderId: String?, subfolder: String? = null, newFolderName: String? = null, name: String = file.name,
    ): Result = withContext(Dispatchers.IO) {
        if (!configured) throw IllegalStateException("Photo upload isn't set up in this build yet (FCP_UPLOAD_URL).")
        val body = buildJsonObject {
            put("token", BuildConfig.UPLOAD_TOKEN)
            put("data", Base64.encodeToString(file.bytes, Base64.NO_WRAP))
            put("mime", file.mime)
            put("name", name)
            if (!folderId.isNullOrBlank()) put("folderId", folderId)
            if (!subfolder.isNullOrBlank()) put("subfolder", subfolder)
            if (!newFolderName.isNullOrBlank()) put("newFolderName", newFolderName)
        }.toString()
        val req = Request.Builder().url(BuildConfig.UPLOAD_URL)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val text = try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Upload failed (${r.code}).")
                r.body?.string().orEmpty()
            }
        } catch (e: IOException) { throw OfflineException("No connection, the photo wasn't sent.") }
        val o = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            throw IllegalStateException("The upload script didn't answer properly. Is it deployed for Anyone?")
        }
        if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") throw IllegalStateException(o["error"]?.jsonPrimitive?.contentOrNull ?: "Upload failed.")
        Result(
            o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["createdFolderId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    companion object {
        /** A photo shrunk to `maxSide` px and saved as JPEG, turned the right way up. */
        suspend fun photo(context: Context, uri: Uri, name: String, maxSide: Int = 1200, quality: Int = 82): PickedFile = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalStateException("Couldn't read that picture.")
            val turn = try {
                cr.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            val scale = minOf(1f, maxSide.toFloat() / max(raw.width, raw.height))
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (turn != 0f) postRotate(turn) }
            val bmp = if (scale < 1f || turn != 0f) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true) else raw
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            PickedFile(out.toByteArray(), "image/jpeg", name)
        }

        /** Any file (a PDF bill...), as it is. */
        suspend fun file(context: Context, uri: Uri, fallbackName: String): PickedFile = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            var name = fallbackName
            try {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
            } catch (_: Exception) { }
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read that file.")
            if (bytes.size > 15 * 1024 * 1024) throw IllegalStateException("That file is over 15 MB.")
            PickedFile(bytes, mime, name)
        }

        /** Drive link to a picture Coil can show (same idea as the web pages' driveThumb). */
        fun thumb(url: String?, size: Int = 400): String {
            val s = url?.trim().orEmpty()
            if (s.isEmpty()) return ""
            if (!Regex("drive\\.google\\.com|docs\\.google\\.com").containsMatchIn(s)) return s
            val m = Regex("/d/([a-zA-Z0-9_-]{20,})").find(s) ?: Regex("[?&]id=([a-zA-Z0-9_-]{20,})").find(s) ?: return s
            return "https://drive.google.com/thumbnail?id=${m.groupValues[1]}&sz=w$size"
        }
    }
}
