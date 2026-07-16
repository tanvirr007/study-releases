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
import androidx.core.view.WindowInsetsControllerCompat
import study.tanvir.info.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isWebViewFirstPageLoaded = false

    companion object {
        const val WEB_URL = "https://study-tanvirr007.vercel.app"

        private val jsBlobHook = """
            (function() {
                if (window.__blobHookInstalled) return;
                window.__blobHookInstalled = true;

                const orig = URL.createObjectURL;
                URL.createObjectURL = function(blob) {
                    window._lastBlob = blob;
                    try { BlobDownloader.onDownloadPreparing(); } catch(e) {}
                    return orig.call(URL, blob);
                };
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isWebViewFirstPageLoaded }

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
        }

        addJavascriptInterface(
            BlobDownloader(this@MainActivity),
            "BlobDownloader"
        )

        webViewClient = object : WebViewClient() {
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
                url?.let { updateSystemBarTheme(it) }

                if (!isWebViewFirstPageLoaded) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        isWebViewFirstPageLoaded = true
                    }, 500)
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                url?.let { updateSystemBarTheme(it) }
            }
        }

        loadUrl(WEB_URL)
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

    private fun setOnBackPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (binding.webView.canGoBack()) binding.webView.goBack()
                else finish()
            }
        } else {
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (binding.webView.canGoBack()) binding.webView.goBack()
                        else finish()
                    }
                }
            )
        }
    }

    private fun setupDownloadSupport() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                binding.webView.fetchBlob(url, mimeType, contentDisposition)
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
}
