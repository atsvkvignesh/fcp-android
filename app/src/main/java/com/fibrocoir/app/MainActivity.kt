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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import android.webkit.WebResourceResponse
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
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URISyntaxException

class MainActivity : AppCompatActivity() {

    companion object {
        /** The Apps Script web app this app opens. Change it here if the /exec URL ever changes. */
        const val HOME_URL =
            "https://script.google.com/macros/s/AKfycbwds8wFxInvCWz2AHfayVI6oNRXxMzx9wcT_t85n2CrLkFGoXSS-LC7OUDygYH1_7v5ZQ/exec"

        /** Added to the browser user-agent so the web page can tell it is running inside the app. */
        private const val UA_SUFFIX = " FibroCoirApp"

        /**
         * Pages bundled in the APK (app/src/main/assets/<name>/index.html) are served from this
         * address, so they open instantly and only fetch their data from Apps Script.
         */
        const val LOCAL_HOST = "appassets.androidplatform.net"
        const val LOCAL_BASE = "https://$LOCAL_HOST/assets/"
        private val LOCAL_NAME = Regex("[a-z0-9][a-z0-9-]*")
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    /** Bundled pages open as screens stacked over the hub, so going back never reloads the hub. */
    private val screens = ArrayList<WebView>()

    /** Background threads for data requests made by bundled pages (FCPApp.request). */
    private val io = java.util.concurrent.Executors.newCachedThreadPool()
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

        webView.loadUrl(homeUrl())
        // Opened by tapping a notification that carries a link: show it over the hub
        val link = intent?.getStringExtra(Notifications.EXTRA_URL)?.let { Uri.parse(it) }
        if (link != null && isInternal(link)) openScreen(link.toString(), animate = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tapped a notification that carries a link
        val url = intent.getStringExtra(Notifications.EXTRA_URL) ?: return
        val uri = Uri.parse(url)
        if (isInternal(uri)) openScreen(url) else openExternal(uri)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        screens.forEach { it.onResume() }
    }

    override fun onPause() {
        CookieManager.getInstance().flush() // keep logins saved
        webView.onPause()
        screens.forEach { it.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        screens.forEach { it.destroy() }
        screens.clear()
        webView.destroy()
        io.shutdown()
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
        // Same dark colour as the hub, so nothing flashes white while it opens
        val husk = ContextCompat.getColor(this, R.color.husk)
        root = FrameLayout(this).apply { setBackgroundColor(husk) }

        webView = WebView(this).apply { setBackgroundColor(husk) }
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

    private fun setupWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(LOCAL_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        CookieManager.getInstance().setAcceptCookie(true)
        configureWebView(webView, main = true)
    }

    /**
     * Same setup for the hub (main = true) and for bundled screens (main = false).
     * Only the hub drives the progress bar and the no-internet screen.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView, main: Boolean) {
        with(view.settings) {
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
            builtInZoomControls = false // app screens: no pinch zoom
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            userAgentString = userAgentString + UA_SUFFIX
        }

        // Apps Script pages run inside a Google sandbox frame on another domain,
        // so third-party cookies must be allowed for logins to stick.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.addJavascriptInterface(AppBridge(view), "FCPApp")

        view.webViewClient = object : WebViewClient() {
            // Serves https://appassets.androidplatform.net/assets/... from the APK, and /proxy for data.
            // Runs on a background thread, so the proxy may block while it fetches.
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == LOCAL_HOST && url.path == "/proxy") return DataProxy.handle(request)
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return if (main) handleUrl(request.url) else handleScreenUrl(request.url)
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (main) mainFrameFailed = false
            }

            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (!main) showScreen(v)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (main) {
                    progress.visibility = View.GONE
                    if (!mainFrameFailed) offlineView.visibility = View.GONE
                } else {
                    showScreen(v)
                }
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (main && request.isForMainFrame) {
                    mainFrameFailed = true
                    lastFailedUrl = request.url.toString()
                    offlineView.visibility = View.VISIBLE
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (!main && v != screens.lastOrNull()) return
                if (!main) progress.bringToFront()
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            // target="_blank" and window.open(): open inside the app if it's our page, else outside
            override fun onCreateWindow(
                v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val popup = WebView(this@MainActivity)
                var handled = false
                fun take(uri: Uri?, w: WebView) {
                    if (handled || uri == null || uri.toString() == "about:blank") return
                    handled = true
                    openFromPopup(uri)
                    w.post { w.destroy() }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(w: WebView, r: WebResourceRequest): Boolean {
                        take(r.url, w); return true
                    }

                    override fun onPageStarted(w: WebView, url: String?, favicon: Bitmap?) {
                        if (url != null && url != "about:blank") {
                            w.stopLoading(); take(Uri.parse(url), w)
                        }
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
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

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("data:") -> saveDataUrl(url, contentDisposition, mimeType)
                url.startsWith("blob:") -> toast("This file can't be downloaded inside the app yet")
                else -> downloadWithManager(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    // ---------------------------------------------------------------- bundled screens

    /** The hub bundled in the APK; the Apps Script hub only if this APK has no bundled one. */
    private fun homeUrl(): String = localUrl("hub") ?: HOME_URL

    /** "ec-viewer" or "ec-viewer?range=week" -> its bundled URL, or null if the APK has no such page. */
    private fun localUrl(target: String): String? {
        val cut = target.indexOfAny(charArrayOf('?', '#'))
        val name = if (cut >= 0) target.substring(0, cut) else target
        val rest = if (cut >= 0) target.substring(cut) else ""
        if (!LOCAL_NAME.matches(name)) return null
        return try {
            assets.open("$name/index.html").close()
            "$LOCAL_BASE$name/index.html$rest"
        } catch (_: Exception) {
            null
        }
    }

    /** Opens a bundled page as a new screen that slides in over the current one. */
    private fun openScreen(url: String, animate: Boolean = true) {
        val view = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            overScrollMode = View.OVER_SCROLL_NEVER // no stretch/glow at the ends, like a native list
        }
        configureWebView(view, main = false)
        val shown = !animate
        view.tag = shown
        view.translationX = if (shown) 0f else screenWidth()
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        screens.add(view)
        view.loadUrl(url)
        // Slide in once the page has painted (normally ~100 ms); never wait longer than this
        if (!shown) view.postDelayed({ showScreen(view) }, 400)
    }

    private fun showScreen(view: WebView) {
        if (view.tag == true || !screens.contains(view)) return
        view.tag = true
        view.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun closeTopScreen() {
        if (screens.isEmpty()) return
        val view = screens.removeAt(screens.size - 1)
        view.animate().translationX(screenWidth()).setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { root.removeView(view); view.destroy() }
            .start()
    }

    private fun closeAllScreens() {
        screens.forEach { root.removeView(it); it.destroy() }
        screens.clear()
    }

    private fun screenWidth(): Float =
        (if (root.width > 0) root.width else resources.displayMetrics.widthPixels).toFloat()

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
            host == "accounts.google.com" ||
            host == LOCAL_HOST
    }

    /** A page bundled in the APK. */
    private fun isLocal(uri: Uri): Boolean =
        uri.scheme?.lowercase() == "https" && uri.host?.lowercase() == LOCAL_HOST

    /** Links in the hub. Returns true if the link was handled outside the hub's web view. */
    private fun handleUrl(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            null -> true
            "http", "https" -> when {
                isLocal(uri) -> { openScreen(uri.toString()); true }
                isInternal(uri) -> false
                else -> { openExternal(uri); true }
            }
            "about", "javascript", "data", "blob" -> false
            "intent" -> { openIntentUri(uri.toString()); true }
            else -> { openExternal(uri); true } // tel:, mailto:, whatsapp:, upi:, sms:, geo: ...
        }
    }

    /** Links inside a screen: our own pages stay in that screen, anything else opens outside. */
    private fun handleScreenUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && isInternal(uri)) return false
        return handleUrl(uri)
    }

    private fun openFromPopup(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (isInternal(uri)) openScreen(uri.toString()) else openExternal(uri)
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
                val top = screens.lastOrNull()
                if (top != null) {
                    // Let the page close its own dialog first (window.onAppBack returns true if it did)
                    top.evaluateJavascript(
                        "(function(){try{return !!(window.onAppBack&&window.onAppBack())}catch(e){return false}})()"
                    ) { handled ->
                        if (handled == "true" || !screens.contains(top)) return@evaluateJavascript
                        if (top.canGoBack()) top.goBack() else closeTopScreen()
                    }
                    return
                }
                if (offlineView.visibility == View.VISIBLE) { showMenu(); return }
                webView.evaluateJavascript(
                    "(function(){try{return !!(window.onAppBack&&window.onAppBack())}catch(e){return false}})()"
                ) { handled -> if (handled != "true") hubBack() }
            }
        })
    }

    private fun hubBack() {
        val list = webView.copyBackForwardList()
        val i = list.currentIndex
        val prev = if (i > 0) list.getItemAtIndex(i - 1)?.url else null
        // Go back only to a genuinely different page; otherwise show the app menu
        if (prev != null && prev != list.currentItem?.url && prev != "about:blank") webView.goBack()
        else showMenu()
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setItems(arrayOf("Reload page", "Go to home page", "Notification groups", "Exit app")) { _, which ->
                when (which) {
                    0 -> retry()
                    1 -> { closeAllScreens(); offlineView.visibility = View.GONE; webView.loadUrl(homeUrl()) }
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
     *   FCPApp.hasLocal("ec-viewer")  -> true if this APK has that page bundled
     *   FCPApp.openLocal("ec-viewer") -> opens it as a screen over the current one (true/false)
     *   FCPApp.close()                -> closes the top bundled screen
     *   FCPApp.openUrl(url)           -> opens an Apps Script page as a screen (other links outside)
     *   FCPApp.request(id, url, body) -> fetches an Apps Script URL natively (POST if body is given);
     *                                    the answer arrives as FCP._done(id, status, text)
     */
    inner class AppBridge(private val view: WebView) {
        @JavascriptInterface
        fun request(id: String, url: String, body: String?) {
            io.execute {
                var code: Int
                var text: String
                try {
                    if (!Http.allowed(Uri.parse(url))) {
                        code = 400
                        text = String(Http.err("This address can't be fetched from the app."), Charsets.UTF_8)
                    } else {
                        val r = Http.fetch(if (body == null) "GET" else "POST", url, body)
                        code = r.code
                        text = String(r.body, Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    code = 0
                    text = String(Http.err("No connection to the server.", offline = true), Charsets.UTF_8)
                }
                val js = "window.FCP&&FCP._done(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")"
                view.post {
                    try { view.evaluateJavascript(js, null) } catch (_: Exception) { /* screen already closed */ }
                }
            }
        }

        @JavascriptInterface
        fun hasLocal(name: String): Boolean = localUrl(name) != null

        @JavascriptInterface
        fun openLocal(name: String): Boolean {
            val url = localUrl(name) ?: return false
            runOnUiThread { openScreen(url) }
            return true
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { closeTopScreen() }
        }

        /** Opens one of our web pages (an Apps Script /exec link) as a screen; anything else outside the app. */
        @JavascriptInterface
        fun openUrl(url: String) {
            val uri = Uri.parse(url)
            runOnUiThread { if (isInternal(uri)) openScreen(url) else openExternal(uri) }
        }

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
