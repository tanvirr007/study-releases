package study.tanvir.info

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

class ThemeBridge(private val activity: MainActivity) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onThemeChanged(isDark: Boolean, colorHex: String?) {
        mainHandler.post {
            activity.applyTheme(isDark, colorHex)
        }
    }

    @JavascriptInterface
    fun setTheme(isDark: Boolean, colorHex: String?) {
        mainHandler.post {
            activity.applyTheme(isDark, colorHex)
        }
    }
}
