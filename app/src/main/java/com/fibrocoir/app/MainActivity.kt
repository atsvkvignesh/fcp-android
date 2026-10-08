package com.fibrocoir.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import java.io.File
import java.net.URISyntaxException

class MainActivity : AppCompatActivity() {

    companion object {
        /** The Apps Script web app this app opens. Change it here if the /exec URL ever changes. */
        const val HOME_URL =
            "https://script.google.com/macros/s/AKfycbwds8wFxInvCWz2AHfayVI6oNRXxMzx9wcT_t85n2CrLkFGoXSS-LC7OUDygYH1_7v5ZQ/exec"

        /** Added to the browser user-agent so the web page can tell it is running inside the app. */
        private const val UA_SUFFIX = " FibroCoirApp"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offlineView: View
    private var mainFrameFailed = false
    private var lastFailedUrl: String? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val cb = pendingPermission
            pendingPermission = null
            cb?.invoke()
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val clip = data?.clipData
                val single = data?.data
                val photo = cameraPhotoUri
                uris = when {
                    clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    single != null -> arrayOf(single)
                    photo != null -> arrayOf(photo)
                    else -> null
                }
            }
            cameraPhotoUri = null
            cb.onReceiveValue(uris)
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        buildLayout()
        setupWebView()
        setupBackButton()

        Notifications.ensureChannel(this)
        Topics.resubscribeAll(this)
        cleanOldCameraFiles()
        askNotificationPermission()

        val start = intent?.getStringExtra(Notifications.EXTRA_URL)
            ?.let { Uri.parse(it) }
            ?.takeIf { isInternal(it) }
            ?.toString()
            ?: HOME_URL
        webView.loadUrl(start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tapped a notification that carries a link
        val url = intent.getStringExtra(Notifications.EXTRA_URL) ?: return
        val uri = Uri.parse(url)
        if (isInternal(uri)) webView.loadUrl(url) else openExternal(uri)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush() // keep logins saved
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun buildLayout() {
        val brand = ContextCompat.getColor(this, R.color.brand)
        root = FrameLayout(this).apply { setBackgroundColor(brand) }

        webView = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8BC34A"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        root.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, dp(3), Gravity.TOP))

        offlineView = buildOfflineView().apply { visibility = View.GONE }
        root.addView(offlineView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)

        // Keep content clear of the status bar, navigation bar and keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildOfflineView(): View {
        val brand = ContextCompat.getColor(this, R.color.brand)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true

            addView(TextView(context).apply {
                text = getString(R.string.offline_title)
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = getString(R.string.offline_body)
                textSize = 15f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(24))
            })
            addView(Button(context).apply {
                text = getString(R.string.retry)
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(brand)
                setOnClickListener { retry() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            userAgentString = userAgentString + UA_SUFFIX
        }

        // Apps Script pages run inside a Google sandbox frame on another domain,
        // so third-party cookies must be allowed for logins to stick.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(AppBridge(), "FCPApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleUrl(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                if (!mainFrameFailed) offlineView.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    lastFailedUrl = request.url.toString()
                    offlineView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            // target="_blank" and window.open(): open inside the app if it's our page, else outside
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val popup = WebView(this@MainActivity)
                var handled = false
                fun take(uri: Uri?, v: WebView) {
                    if (handled || uri == null || uri.toString() == "about:blank") return
                    handled = true
                    openFromPopup(uri)
                    v.post { v.destroy() }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        take(r.url, v); return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (url != null && url != "about:blank") {
                            v.stopLoading(); take(Uri.parse(url), v)
                        }
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val mimes = acceptedMimes(params)
                val wantsImages = mimes.isEmpty() || mimes.any { it == "*/*" || it.startsWith("image") }
                val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                if (wantsImages && hasCamera) {
                    withPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                        launchFileChooser(params, mimes, includeCamera = granted)
                    }
                } else {
                    launchFileChooser(params, mimes, includeCamera = false)
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                withPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    requireAll = false
                ) { ok -> callback.invoke(origin, ok, false) }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        request.deny(); return@runOnUiThread
                    }
                    withPermissions(arrayOf(Manifest.permission.CAMERA)) { ok ->
                        if (ok) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request.deny()
                    }
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("data:") -> saveDataUrl(url, contentDisposition, mimeType)
                url.startsWith("blob:") -> toast("This file can't be downloaded inside the app yet")
                else -> downloadWithManager(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    private fun retry() {
        offlineView.visibility = View.GONE
        val failed = lastFailedUrl
        lastFailedUrl = null
        if (failed != null) webView.loadUrl(failed) else webView.reload()
    }

    // ---------------------------------------------------------------- links

    /** Pages that should stay inside the app. Everything else opens in the right app or browser. */
    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "script.google.com" ||
            host.endsWith(".googleusercontent.com") ||
            host == "accounts.google.com"
    }

    /** Returns true if the link was handled outside the web view. */
    private fun handleUrl(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            null -> true
            "http", "https" -> if (isInternal(uri)) false else { openExternal(uri); true }
            "about", "javascript", "data", "blob" -> false
            "intent" -> { openIntentUri(uri.toString()); true }
            else -> { openExternal(uri); true } // tel:, mailto:, whatsapp:, upi:, sms:, geo: ...
        }
    }

    private fun openFromPopup(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (isInternal(uri)) webView.loadUrl(uri.toString()) else openExternal(uri)
        } else {
            handleUrl(uri)
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast("No app found to open this link")
        }
    }

    private fun openIntentUri(s: String) {
        try {
            val intent = Intent.parseUri(s, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                when {
                    fallback != null -> openFromPopup(Uri.parse(fallback))
                    intent.`package` != null ->
                        openExternal(Uri.parse("market://details?id=${intent.`package`}"))
                    else -> toast("No app found to open this link")
                }
            }
        } catch (_: URISyntaxException) {
        }
    }

    // ---------------------------------------------------------------- file upload / camera

    private fun acceptedMimes(params: FileChooserParams): List<String> =
        params.acceptTypes
            .flatMap { it.split(",") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map {
                if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.substring(1)) ?: "*/*"
                else it
            }
            .distinct()

    private fun launchFileChooser(params: FileChooserParams, mimes: List<String>, includeCamera: Boolean) {
        val cb = filePathCallback ?: return
        val cameraIntent = if (includeCamera) createCameraIntent() else null

        val content = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            when {
                mimes.isEmpty() || mimes.contains("*/*") -> type = "*/*"
                mimes.size == 1 -> type = mimes[0]
                else -> {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
            }
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val launch = if (params.isCaptureEnabled && cameraIntent != null) {
            cameraIntent
        } else {
            Intent.createChooser(content, "Choose file").apply {
                if (cameraIntent != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        try {
            fileChooserLauncher.launch(launch)
        } catch (_: Exception) {
            filePathCallback = null
            cameraPhotoUri = null
            cb.onReceiveValue(null)
            toast("Can't open the file picker")
        }
    }

    private fun createCameraIntent(): Intent? = try {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("photo_", ".jpg", dir)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraPhotoUri = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (_: Exception) {
        null
    }

    private fun cleanOldCameraFiles() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        File(cacheDir, "camera").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    // ---------------------------------------------------------------- downloads

    private fun downloadWithManager(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val go = {
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    setDescription(getString(R.string.app_name))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                getSystemService(DownloadManager::class.java).enqueue(req)
                toast("Downloading $name…")
            } catch (_: Exception) {
                openExternal(Uri.parse(url))
            }
        }
        if (Build.VERSION.SDK_INT < 29) {
            withPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { go() }
        } else {
            go()
        }
    }

    private fun saveDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val comma = url.indexOf(',')
            val meta = url.substring(5, comma)
            val mime = meta.substringBefore(';').ifBlank { mimeType ?: "application/octet-stream" }
            val payload = url.substring(comma + 1)
            val bytes = if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val fromHeader = contentDisposition
                ?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
                ?.let { Uri.decode(it).trim() }
                ?.takeIf { it.isNotBlank() }
            val name = fromHeader ?: "FibroCoir_${System.currentTimeMillis()}.$ext"
            saveBytes(name, mime, bytes)
        } catch (_: Exception) {
            toast("Download failed")
        }
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                toast("Saved to Downloads: $name")
            } catch (_: Exception) {
                toast("Download failed")
            }
        } else {
            withPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { ok ->
                if (!ok) { toast("Storage permission needed to save files"); return@withPermissions }
                try {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, name).writeBytes(bytes)
                    toast("Saved to Downloads: $name")
                } catch (_: Exception) {
                    toast("Download failed")
                }
            }
        }
    }

    // ---------------------------------------------------------------- back button & menu

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offlineView.visibility == View.VISIBLE) { showMenu(); return }
                val list = webView.copyBackForwardList()
                val i = list.currentIndex
                val prev = if (i > 0) list.getItemAtIndex(i - 1)?.url else null
                // Go back only to a genuinely different page; otherwise show the app menu
                if (prev != null && prev != list.currentItem?.url && prev != "about:blank") webView.goBack()
                else showMenu()
            }
        })
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setItems(arrayOf("Reload page", "Go to home page", "Notification groups", "Exit app")) { _, which ->
                when (which) {
                    0 -> retry()
                    1 -> { offlineView.visibility = View.GONE; webView.loadUrl(HOME_URL) }
                    2 -> showTopicsDialog()
                    3 -> finish()
                }
            }
            .show()
    }

    private fun showTopicsDialog() {
        val keys = Topics.OPTIONAL.keys.toTypedArray()
        val labels = Topics.OPTIONAL.values.toTypedArray<CharSequence>()
        val current = Topics.subscribed(this)
        val checked = BooleanArray(keys.size) { keys[it] in current }
        AlertDialog.Builder(this)
            .setTitle("Notification groups")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                keys.forEachIndexed { i, k -> Topics.set(this, k, checked[i]) }
                toast("Saved")
                askNotificationPermission()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- permissions

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            withPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
        }
    }

    private fun isGranted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun withPermissions(perms: Array<String>, requireAll: Boolean = true, block: (Boolean) -> Unit) {
        fun ok() = if (requireAll) perms.all { isGranted(it) } else perms.any { isGranted(it) }
        if (ok()) { block(true); return }
        pendingPermission?.invoke() // finish any earlier request first
        pendingPermission = { block(ok()) }
        try {
            permissionLauncher.launch(perms)
        } catch (_: Exception) {
            pendingPermission = null
            block(false)
        }
    }

    // ---------------------------------------------------------------- JS bridge

    /**
     * Available to the web page as window.FCPApp (only when opened inside the app):
     *   FCPApp.subscribe("tanker")    -> true/false
     *   FCPApp.unsubscribe("tanker")  -> true/false
     *   FCPApp.getTopics()            -> '["all","tanker"]'
     *   FCPApp.version()              -> "1.0.12"
     */
    inner class AppBridge {
        @JavascriptInterface
        fun subscribe(topic: String): Boolean = Topics.set(applicationContext, topic, true)

        @JavascriptInterface
        fun unsubscribe(topic: String): Boolean = Topics.set(applicationContext, topic, false)

        @JavascriptInterface
        fun getTopics(): String = JSONArray(Topics.subscribed(applicationContext).toList()).toString()

        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
