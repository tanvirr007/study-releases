package study.tanvir.info

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import study.tanvir.info.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isWebViewFirstPageLoaded = false
    private var backPressedTime: Long = 0
    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private var backgroundedAt: Long = 0L
    private var isAuthenticated = false
    private var shouldKeepSplashScreen = true
    private var isInitialLoadStarted = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            uploadMessage?.onReceiveValue(results)
        } else {
            uploadMessage?.onReceiveValue(null)
        }
        uploadMessage = null
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            val message = "Notification permission denied\nDownload status won't be shown"
            val spannable = android.text.SpannableString(message).apply {
                setSpan(
                    android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER),
                    0,
                    length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            Toast.makeText(this, spannable, Toast.LENGTH_LONG).show()
        }
        // Chain into OTA permission prompt after notification permission is handled
        showOtaPermissionPopup()
    }

    companion object {
        const val WEB_URL = "https://study-tanvirr007.vercel.app"

        private val jsBlobHook = """
            (function() {
                if (!window.__blobHookInstalled) {
                    window.__blobHookInstalled = true;
                    const orig = URL.createObjectURL;
                    URL.createObjectURL = function(blob) {
                        window._lastBlob = blob;
                        try { BlobDownloader.onDownloadPreparing(); } catch(e) {}
                        return orig.call(URL, blob);
                    };
                }

                if (!window.__printHookInstalled) {
                    window.__printHookInstalled = true;
                    window.print = function() {
                        try { PrintBridge.print(document.title); } catch(e) {}
                    };
                }
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { shouldKeepSplashScreen }

        // Apply FLAG_SECURE window flag based on user preferences
        val prefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        val flagSecureDisabled = prefs.getBoolean("flag_secure_disabled", false)
        if (flagSecureDisabled) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupWebView()
        setupDownloadSupport()
        setOnBackPressed()
        setupOfflineRetry()
        setupSwipeToRefresh()
        setupLockScreen()
        checkPostUpdateToast()
        checkFirstLaunchPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkBiometricLock()
        if (isWebViewFirstPageLoaded) {
            binding.webView.evaluateJavascript("window.dispatchEvent(new Event('focus'));", null)
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = System.currentTimeMillis()
        android.webkit.CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null) {
            val scheme = data.scheme
            val host = data.host
            val path = data.path ?: ""
            val query = data.query?.let { "?$it" } ?: ""
            val fragment = data.fragment?.let { "#$it" } ?: ""

            if (scheme == "cq") {
                val webUrl = "$WEB_URL$path$query$fragment"
                binding.webView.loadUrl(webUrl)
            } else if (scheme == "http" || scheme == "https") {
                if (host == "study-tanvirr007.vercel.app" || host == "www.study-tanvirr007.vercel.app") {
                    binding.webView.loadUrl(data.toString())
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() = with(binding.webView) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        // Enable cookies and accept third-party cookies
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@with, true)
        }

        addJavascriptInterface(
            BlobDownloader(this@MainActivity),
            "BlobDownloader"
        )

        addJavascriptInterface(
            SecurityBridge(this@MainActivity),
            "SecurityBridge"
        )

        addJavascriptInterface(
            PrintBridge(this@MainActivity, this@with),
            "PrintBridge"
        )

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // If it's an internal URL (matching our base web app, local files, or content), load inside WebView
                if (url.startsWith(WEB_URL) || url.startsWith("file://") || url.startsWith("content://")) {
                    return false
                }
                
                // For all other links (external websites or custom schemes like tel, mailto, tg, etc.)
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    view?.context?.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    // Fallback: If it's an external web link but no browser is found, load it in the WebView
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return false
                    }
                    return true // Consume custom protocols to prevent unsupported scheme crashes
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                view?.evaluateJavascript(jsBlobHook, null)

                if (url?.endsWith("/dashboard") == true
                    || url?.endsWith("/dashboard/") == true
                    || url?.endsWith(WEB_URL) == true
                    || url?.endsWith("$WEB_URL/") == true
                ) {
                    view?.clearHistory()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(jsBlobHook, null)
                url?.let { updateSystemBarTheme(it) }
                android.webkit.CookieManager.getInstance().flush()
                binding.swipeRefreshLayout.isRefreshing = false

                if (!isWebViewFirstPageLoaded) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        isWebViewFirstPageLoaded = true
                        shouldKeepSplashScreen = false
                    }, 500)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.webView.visibility = View.GONE
                    binding.offlineLayout.visibility = View.VISIBLE
                    binding.swipeRefreshLayout.isRefreshing = false
                    // Hide splash screen immediately if first page load fails
                    isWebViewFirstPageLoaded = true
                    shouldKeepSplashScreen = false
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                url?.let { updateSystemBarTheme(it) }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadMessage?.onReceiveValue(null)
                val intent = fileChooserParams?.createIntent()
                if (intent == null) {
                    return false
                }
                uploadMessage = filePathCallback
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    return false
                }
                return true
            }
        }
    }

    private fun startInitialLoadIfNeeded() {
        if (!isInitialLoadStarted) {
            isInitialLoadStarted = true
            val deepLinkUrl = intent?.data?.toString()
            val host = intent?.data?.host
            val scheme = intent?.data?.scheme
            
            if (scheme == "cq") {
                val path = intent?.data?.path ?: ""
                val query = intent?.data?.query?.let { "?$it" } ?: ""
                val fragment = intent?.data?.fragment?.let { "#$it" } ?: ""
                binding.webView.loadUrl("$WEB_URL$path$query$fragment")
            } else if (deepLinkUrl != null && (host == "study-tanvirr007.vercel.app" || host == "www.study-tanvirr007.vercel.app")) {
                binding.webView.loadUrl(deepLinkUrl)
            } else {
                binding.webView.loadUrl(WEB_URL)
            }
        }
    }

    private fun updateSystemBarTheme(url: String) {
        val isHomePage = url == WEB_URL || url == "$WEB_URL/"

        val controller = WindowInsetsControllerCompat(window, window.decorView)

        if (isHomePage) {
            // LIGHT system bars (dark icons)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
            binding.root.setBackgroundColor(Color.WHITE)
        } else {
            // DARK system bars (light icons)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            binding.root.setBackgroundColor("#112240".toColorInt())
        }
    }

    private fun handleBackPress() {
        // If locked, back press exits the app (no peeking)
        if (binding.lockOverlay.visibility == View.VISIBLE) {
            finish()
            return
        }

        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                finish()
            } else {
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                backPressedTime = System.currentTimeMillis()
            }
        }
    }

    private fun setOnBackPressed() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            }
        )
    }

    private fun setupDownloadSupport() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                binding.webView.fetchBlob(url, mimeType, contentDisposition)
                return@setDownloadListener
            }

            if (url.startsWith("data:")) {
                handleDataUri(url, mimeType, contentDisposition)
                return@setDownloadListener
            }

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            val request = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Downloading file…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }
    }

    private fun handleDataUri(url: String, mimeType: String?, contentDisposition: String?) {
        try {
            val resolvedMime = url.substringAfter("data:").substringBefore(";").takeIf { it.isNotEmpty() && !it.contains(",") } ?: mimeType
            val fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
            BlobDownloader(this).download(url, resolvedMime, "filename=\"$fileName\"")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to download data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupOfflineRetry() {
        binding.btnRetry.setOnClickListener {
            if (isNetworkAvailable()) {
                binding.offlineLayout.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                val url = binding.webView.url
                if (url.isNullOrEmpty()) {
                    binding.webView.loadUrl(WEB_URL)
                } else {
                    binding.webView.reload()
                }
            } else {
                Toast.makeText(this, "No internet connection detected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun setupSwipeToRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }

        // Disable swipe to refresh when webview is scrolled down
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            binding.webView.canScrollVertically(-1)
        }
    }

    private fun setupLockScreen() {
        binding.btnUnlock.setOnClickListener {
            showBiometricPrompt()
        }
    }

    private fun checkBiometricLock() {
        val prefs = getSharedPreferences("security_prefs", MODE_PRIVATE)
        val biometricEnabled = prefs.getBoolean("biometric_enabled", false)

        if (!biometricEnabled) {
            isAuthenticated = false
            startInitialLoadIfNeeded()
            return
        }

        // Grace period: skip lock if away less than 30 seconds and already authenticated
        val timeAway = System.currentTimeMillis() - backgroundedAt
        if (isAuthenticated && backgroundedAt != 0L && timeAway < 30_000) {
            startInitialLoadIfNeeded()
            return
        }

        // Show lock
        isAuthenticated = false
        binding.lockOverlay.visibility = View.VISIBLE

        // Apply dark system bar theme for lock screen so icons are light/visible against dark background
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        binding.root.setBackgroundColor("#112240".toColorInt())

        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isAuthenticated = true
                binding.lockOverlay.visibility = View.GONE
                // Restore system bar theme based on current web page URL
                updateSystemBarTheme(binding.webView.url ?: WEB_URL)
                startInitialLoadIfNeeded()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Stay on lock screen, user can tap "Unlock" to retry
                shouldKeepSplashScreen = false
            }

            override fun onAuthenticationFailed() {
                // Wait for final error or success
            }
        }

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        try {
            val prompt = BiometricPrompt(this, executor, callback)

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock App")
                .setSubtitle("Authenticate to continue")
                .setAllowedAuthenticators(authenticators)
                .build()

            prompt.authenticate(info)
        } catch (_: Exception) {
            // If biometric is unavailable, unlock anyway
            isAuthenticated = true
            binding.lockOverlay.visibility = View.GONE
            updateSystemBarTheme(binding.webView.url ?: WEB_URL)
            startInitialLoadIfNeeded()
            shouldKeepSplashScreen = false
        }
    }

    private fun checkFirstLaunchPermissions() {
        val prefs = getSharedPreferences("update_prefs", MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean("ota_permission_prompted", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Notification result callback will chain into showOtaPermissionPopup
                requestNotificationPermissionLauncher.launch(permission)
                return
            }
        }

        // If notification permission already granted or below Android 13
        if (!alreadyPrompted) {
            showOtaPermissionPopup()
        } else {
            // Already prompted before, check for updates directly
            checkForUpdatesIfNeeded()
        }
    }

    private fun showOtaPermissionPopup() {
        val prefs = getSharedPreferences("update_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("ota_permission_prompted", false)) {
            checkForUpdatesIfNeeded()
            return
        }

        // Mark as prompted so we never show this again
        prefs.edit().putBoolean("ota_permission_prompted", true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                checkForUpdatesIfNeeded()
                return
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Enable OTA Updates")
                .setMessage("This app supports OTA updates. Please allow permission to install unknown apps to safely apply updates")
                .setCancelable(false)
                .setPositiveButton("Allow") { d, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) { }
                    d.dismiss()
                    checkForUpdatesIfNeeded()
                }
                .setNegativeButton("Later") { d, _ ->
                    d.dismiss()
                    checkForUpdatesIfNeeded()
                }
                .show()
        } else {
            checkForUpdatesIfNeeded()
        }
    }

    private fun checkForUpdatesIfNeeded() {
        if (isNetworkAvailable()) {
            UpdateChecker.checkForUpdates(this)
        }
    }

    private fun checkPostUpdateToast() {
        val prefs = getSharedPreferences("update_prefs", MODE_PRIVATE)
        val pendingVersion = prefs.getString("pending_update_version", null) ?: return

        // Clear the flag immediately
        prefs.edit().remove("pending_update_version").apply()

        Toast.makeText(this, "Successfully updated to $pendingVersion", Toast.LENGTH_LONG).show()
    }
}

