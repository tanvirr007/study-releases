package study.tanvir.info

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours

    private const val GITHUB_API_URL = "https://api.github.com/repos/tanvirr007/study-releases/releases/latest"

    // Single static executor to reuse background threads
    private val updateExecutor = Executors.newSingleThreadExecutor()

    fun checkForUpdates(activity: Activity) {
        val activityRef = WeakReference(activity)
        val context = activity.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Get local version code
        val localVersionCode = getLocalVersionCode(context)

        // If not a local debug build (where versionCode is 1), enforce the 4-hour cache limit
        if (localVersionCode != 1L) {
            val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCheckTime < CHECK_INTERVAL_MS) {
                Log.d(TAG, "Update check skipped: 4h caching interval active")
                return
            }
        }

        // Fetch latest release details asynchronously
        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(GITHUB_API_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "CQ-WebView-App")
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = StringBuilder()
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                    }
                    parseAndProcessResponse(activityRef, context, response.toString(), localVersionCode)
                } else {
                    Log.e(TAG, "Server returned response code: $responseCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun getLocalVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local version code", e)
            1L
        }
    }

    private fun parseAndProcessResponse(
        activityRef: WeakReference<Activity>,
        context: Context,
        jsonResponse: String,
        localVersionCode: Long
    ) {
        try {
            val jsonObject = JSONObject(jsonResponse)
            val tagName = jsonObject.optString("tag_name", "")
            val rawBody = jsonObject.optString("body", "No changelog provided.")
            val htmlUrl = jsonObject.optString("html_url", "https://github.com/tanvirr007/study-releases/releases/latest")

            // Extract remote version code (last digit/number segment from tag, e.g. "v2.1.15" -> 15)
            val remoteVersionCode = tagName.substringAfterLast(".").toLongOrNull() ?: 0L

            // Extract and clean up only the Changelog section
            val changelog = extractAndCleanChangelog(rawBody)

            // Try to find the direct APK download url from release assets
            val assets = jsonObject.optJSONArray("assets")
            var downloadUrl = htmlUrl // Default fallback to browser release page
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i)
                    val name = asset?.optString("name", "") ?: ""
                    if (name == "CQ.apk" || name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", downloadUrl)
                        break
                    }
                }
            }

            // If a newer version is available
            if (remoteVersionCode > localVersionCode) {
                showUpdateNotification(context, tagName, downloadUrl)
                Handler(Looper.getMainLooper()).post {
                    val activity = activityRef.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        showUpdateDialog(activity, tagName, changelog, downloadUrl, remoteVersionCode)
                    }
                }
            } else {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                Log.d(TAG, "App is up-to-date (Local: $localVersionCode, Remote: $remoteVersionCode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing release JSON", e)
        }
    }

    private fun extractAndCleanChangelog(body: String): String {
        val header = "### 📝 Changelog"
        val startIndex = body.indexOf(header)
        val rawText = if (startIndex != -1) {
            body.substring(startIndex + header.length).trim()
        } else {
            body
        }

        // Clean up Markdown formatting to make it clean text for standard Android TextViews
        return rawText
            .replace(Regex("\\*\\*"), "") // Remove bold indicators
            .replace(Regex("(?m)^\\s+\\*\\s+"), "  - ") // Convert sub-bullets (nested asterisks) to dashes
            .replace(Regex("\\*\\s+"), "• ") // Convert main bullets to standard round bullets
            .replace(Regex("`"), "") // Remove code block ticks
            .trim()
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        changelog: String,
        downloadUrl: String,
        remoteVersionCode: Long
    ) {
        val messageText = "A new version ($versionName) of the app is available\n\nWhat's New:\n$changelog"
        val spannableMessage = SpannableStringBuilder(messageText)
        val boldStart = messageText.indexOf("What's New:")
        if (boldStart != -1) {
            spannableMessage.setSpan(
                StyleSpan(Typeface.BOLD),
                boldStart,
                boldStart + "What's New:".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()

        // Container view constructed programmatically for styling flexibility
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // Message text view showing changelog details
        val messageView = TextView(activity).apply {
            text = spannableMessage
            textSize = 15f
            val typedArray = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val color = typedArray.getColor(0, android.graphics.Color.BLACK)
            typedArray.recycle()
            setTextColor(color)
        }
        container.addView(messageView)

        // Spacer element
        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (12 * density).toInt()
            )
        }
        container.addView(spacer)

        // Progress bar (initially invisible)
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(progressBar)

        // Progress description text (initially invisible)
        val progressTextView = TextView(activity).apply {
            visibility = View.GONE
            textSize = 14f
            val typedArray = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            val color = typedArray.getColor(0, android.graphics.Color.GRAY)
            typedArray.recycle()
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
        }
        container.addView(progressTextView)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Update Now", null) // Set null to prevent auto-dismissing
            .setNegativeButton("Later") { d, _ ->
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                d.dismiss()
            }
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        positiveButton.setOnClickListener {
            // Android 8.0+ request installation permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                        Toast.makeText(
                            activity.applicationContext,
                            "Please enable permission to install updates",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to navigate to install permission settings", e)
                    }
                    return@setOnClickListener
                }
            }

            // If the URL is fallback HTML release page, trigger browser open and dismiss
            if (!downloadUrl.endsWith(".apk") && !downloadUrl.contains("/download/")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open HTML download URL", e)
                }
                dialog.dismiss()
                return@setOnClickListener
            }

            // Start background downloading with progress update
            downloadApk(
                activity = activity,
                downloadUrl = downloadUrl,
                progressBar = progressBar,
                progressTextView = progressTextView,
                messageView = messageView,
                positiveButton = positiveButton,
                negativeButton = negativeButton,
                dialog = dialog,
                remoteVersionCode = remoteVersionCode,
                versionName = versionName
            )
        }
    }

    private fun downloadApk(
        activity: Activity,
        downloadUrl: String,
        progressBar: ProgressBar,
        progressTextView: TextView,
        messageView: TextView,
        positiveButton: Button,
        negativeButton: Button,
        dialog: AlertDialog,
        remoteVersionCode: Long,
        versionName: String
    ) {
        val activityRef = WeakReference(activity)

        // State 1: Downloading — hide changelog, hide buttons, show progress
        dialog.setTitle("Downloading")
        messageView.visibility = View.GONE
        positiveButton.visibility = View.GONE
        negativeButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressTextView.visibility = View.VISIBLE
        progressTextView.text = "Connecting..."

        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            try {
                val url = URL(downloadUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                val fileLength = connection.contentLength
                inputStream = connection.inputStream

                val currentAct = activityRef.get()
                if (currentAct == null || currentAct.isFinishing || currentAct.isDestroyed) {
                    return@execute
                }

                val apkFile = File(currentAct.cacheDir, "update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                outputStream = FileOutputStream(apkFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    // Abort download if activity is gone
                    val loopAct = activityRef.get()
                    if (loopAct == null || loopAct.isFinishing || loopAct.isDestroyed) {
                        break
                    }

                    total += count
                    outputStream.write(data, 0, count)

                    Handler(Looper.getMainLooper()).post {
                        val viewAct = activityRef.get()
                        if (viewAct == null || viewAct.isFinishing || viewAct.isDestroyed) return@post

                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            progressBar.isIndeterminate = false
                            progressBar.progress = progress
                            val downloadedStr = formatBytes(total)
                            val totalStr = formatBytes(fileLength.toLong())
                            progressTextView.text = "Downloading: $progress% ($downloadedStr / $totalStr)"
                        } else {
                            progressBar.isIndeterminate = true
                            val downloadedStr = formatBytes(total)
                            progressTextView.text = "Downloading: $downloadedStr"
                        }
                    }
                }
                outputStream.flush()

                // State 3: Download complete — show Ready to install
                Handler(Looper.getMainLooper()).post {
                    val finalAct = activityRef.get()
                    if (finalAct == null || finalAct.isFinishing || finalAct.isDestroyed) return@post

                    dialog.setTitle("Ready to install")
                    progressBar.visibility = View.GONE
                    progressTextView.visibility = View.GONE
                    messageView.text = "• Tap restart to install the update"
                    messageView.visibility = View.VISIBLE

                    positiveButton.text = "Restart"
                    positiveButton.visibility = View.VISIBLE

                    positiveButton.setOnClickListener {
                        // Save version name so MainActivity can show success toast on relaunch
                        val prefs = finalAct.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putString("pending_update_version", versionName).apply()

                        installApk(finalAct, apkFile)
                        dialog.dismiss()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading update APK", e)

                // State 2: Download failed — show error and Retry
                Handler(Looper.getMainLooper()).post {
                    val errorAct = activityRef.get()
                    if (errorAct == null || errorAct.isFinishing || errorAct.isDestroyed) return@post

                    dialog.setTitle("Download Failed")
                    progressBar.visibility = View.GONE
                    progressTextView.visibility = View.GONE
                    messageView.text = "The connection was lost or the download failed"
                    messageView.visibility = View.VISIBLE

                    positiveButton.text = "Retry"
                    positiveButton.visibility = View.VISIBLE

                    positiveButton.setOnClickListener {
                        downloadApk(
                            activity = errorAct,
                            downloadUrl = downloadUrl,
                            progressBar = progressBar,
                            progressTextView = progressTextView,
                            messageView = messageView,
                            positiveButton = positiveButton,
                            negativeButton = negativeButton,
                            dialog = dialog,
                            remoteVersionCode = remoteVersionCode,
                            versionName = versionName
                        )
                    }
                }
            } finally {
                try {
                    outputStream?.close()
                    inputStream?.close()
                } catch (_: Exception) {}
                connection?.disconnect()
            }
        }
    }

    private fun installApk(activity: Activity, file: File) {
        try {
            val authority = "${activity.packageName}.provider"
            val apkUri = FileProvider.getUriForFile(activity, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start package installation", e)
            Toast.makeText(
                activity,
                "Failed to trigger package installer: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showUpdateNotification(
        context: Context,
        versionName: String,
        downloadUrl: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "app_updates"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new application updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Update Available")
            .setContentText("Version $versionName is available to download")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(999, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission not granted", e)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }
}
