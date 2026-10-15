package com.fibrocoir.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.fibrocoir.app.core.PickedFile
import com.fibrocoir.app.core.Uploads
import kotlinx.coroutines.launch
import java.io.File

/** Opens the camera, the photo picker or the file picker and hands back a ready-to-send file. */
class FilePicker(val camera: () -> Unit, val gallery: () -> Unit, val document: () -> Unit)

/**
 * name      file name to send with (e.g. "ITM-0007-main.jpg")
 * photoOnly a picked image is shrunk like the web pages did; other files go as they are
 */
@Composable
fun rememberFilePicker(name: String, onPicked: (PickedFile, Uri) -> Unit, onError: (String) -> Unit): FilePicker {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }

    fun handle(uri: Uri) {
        scope.launch {
            try {
                val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                val f = if (mime.startsWith("image/")) Uploads.photo(ctx, uri, name) else Uploads.file(ctx, uri, name)
                onPicked(f, uri)
            } catch (e: Exception) { onError(e.message ?: "Couldn't read that file.") }
        }
    }

    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pending?.let { handle(it) } }
    fun shoot() {
        try {
            val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            pending = u
            take.launch(u)
        } catch (e: Exception) { onError("Couldn't open the camera.") }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shoot() else onError("Allow the camera to take a photo.")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { handle(it) } }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { handle(it) } }

    return remember(name) {
        FilePicker(
            camera = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot()
                else perm.launch(Manifest.permission.CAMERA)
            },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            document = { doc.launch(arrayOf("image/*", "application/pdf")) },
        )
    }
}

/** A photo box with "Take photo" and "Choose" under it. */
@Composable
fun PhotoSlot(
    label: String, name: String, current: Uri?, existingUrl: String = "", required: Boolean = false,
    modifier: Modifier = Modifier, files: Boolean = false, onPicked: (PickedFile, Uri) -> Unit,
) {
    val say = LocalSay.current
    val picker = rememberFilePicker(name, onPicked) { say(it) }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(shape).background(Fcp.husk3).border(1.dp, if (required && current == null && existingUrl.isEmpty()) Fcp.fibre else Fcp.edge, shape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                current != null -> AsyncImage(current, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                existingUrl.isNotEmpty() -> AsyncImage(existingUrl, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Text("📷\n$label" + if (required) "\nrequired" else "", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = Fcp.muted, modifier = Modifier.padding(8.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GhostButton("Take photo", Modifier.weight(1f)) { picker.camera() }
            GhostButton(if (files) "Choose file" else "Choose", Modifier.weight(1f)) { if (files) picker.document() else picker.gallery() }
        }
    }
}
