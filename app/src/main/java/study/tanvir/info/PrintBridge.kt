package study.tanvir.info

import android.app.Activity
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

class PrintBridge(private val activity: Activity, private val webView: WebView) {

    @JavascriptInterface
    fun print() {
        print(null)
    }

    @JavascriptInterface
    fun print(customJobName: String?) {
        activity.runOnUiThread {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val resolvedName = customJobName?.trim()?.takeIf { it.isNotEmpty() }
                ?: webView.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${activity.getString(R.string.app_name)} Document"
            
            // Create a print adapter from the WebView content
            val printAdapter = webView.createPrintDocumentAdapter(resolvedName)
            
            // Trigger system print dialog
            printManager.print(
                resolvedName,
                printAdapter,
                PrintAttributes.Builder().build()
            )
        }
    }
}
