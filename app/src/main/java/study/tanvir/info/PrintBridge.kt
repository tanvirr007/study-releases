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
        activity.runOnUiThread {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "${activity.getString(R.string.app_name)} Document"
            
            // Create a print adapter from the WebView content
            val printAdapter = webView.createPrintDocumentAdapter(jobName)
            
            // Trigger system print dialog
            printManager.print(
                jobName,
                printAdapter,
                PrintAttributes.Builder().build()
            )
        }
    }
}
