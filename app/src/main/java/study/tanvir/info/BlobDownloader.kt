package study.tanvir.info

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream

class BlobDownloader(private val context: Context) {

    companion object {
        @JvmStatic
        private var isActive = true

        @JvmStatic
        fun setIsActive(value: Boolean) {
            isActive = value
        }
    }

    @JavascriptInterface
    fun onDownloadPreparing() {
        (context as? Activity)?.runOnUiThread {
            // Toast.makeText(context, "Preparing file…", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun error(message: String?) {
        showError(message ?: "Unknown error")
    }

    @JavascriptInterface
    fun download(base64: String?, mimeType: String?, cd: String?) {
        if (!isActive || base64 == null) return

        try {
            val fileName = extractFileName(cd).takeIf { !it.isNullOrEmpty() }
                ?: "report_${System.currentTimeMillis()}${getExt(mimeType)}"

            val cleanBase64 = base64.substringAfter(',')
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)

            saveFile(fileName, bytes, mimeType)
        } catch (e: Exception) {
            showError("Failed: ${e.message}")
        }
    }

    private fun extractFileName(cd: String?): String? =
        cd?.substringAfter("filename=")
            ?.replace("\"", "")
            ?.trim()

    private fun getExt(mime: String?) = when (mime) {
        "application/pdf" -> ".pdf"
        "image/png" -> ".png"
        "image/jpeg" -> ".jpg"
        "application/json" -> ".json"
        else -> ".bin"
    }

    private fun saveFile(name: String, bytes: ByteArray, mime: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(name, bytes, mime)
        else saveLegacy(name, bytes, mime)
    }

    private fun saveQ(name: String, bytes: ByteArray, mime: String?) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else MediaStore.Files.getContentUri("external"),
            values
        ) ?: return showError("Failed to create file")

        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        showToast(name)
        confirmOpen(uri, mime, name)
    }

    private fun saveLegacy(name: String, bytes: ByteArray, mime: String?) {
        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloads, name)
        FileOutputStream(file).use { it.write(bytes) }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )

        showToast(name)
        confirmOpen(uri, mime, name)
    }

    private fun confirmOpen(uri: Uri, mime: String?, name: String) {
        val activity = context as? Activity ?: return

        activity.runOnUiThread {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Open file?")
                .setMessage("File \"$name\" has been downloaded. Do you want to open it now?")
                .setPositiveButton("Open") { _, _ ->
                    openFile(uri, mime)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun openFile(uri: Uri, mime: String?) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            showError("No app found to open this file")
        }
    }

    private fun showError(msg: String) = showToast(msg)

    private fun showToast(msg: String) {
        (context as? Activity)?.runOnUiThread {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
