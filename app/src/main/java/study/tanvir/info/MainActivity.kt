package study.tanvir.info

import android.annotation.SuppressLint

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle

import android.os.Handler
import android.os.Looper

import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt

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

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {

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
}
