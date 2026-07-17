package study.tanvir.info

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.BufferedReader
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours

    private const val GITHUB_API_URL = "https://api.github.com/repos/tanvirr007/study-releases/releases/latest"
    private const val FALLBACK_APK_URL = "https://github.com/tanvirr007/study-releases/releases/latest/download/CQ.apk"

    fun checkForUpdates(activity: Activity) {
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
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "CQ-WebView-App")
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    // Update last checked time
                    prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()

                    parseAndProcessResponse(activity, response.toString(), localVersionCode)
                } else {
                    Log.e(TAG, "Server returned response code: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
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

    private fun parseAndProcessResponse(activity: Activity, jsonResponse: String, localVersionCode: Long) {
        try {
            val jsonObject = JSONObject(jsonResponse)
            val tagName = jsonObject.optString("tag_name", "")
            val rawBody = jsonObject.optString("body", "No changelog provided.")
            val htmlUrl = jsonObject.optString("html_url", "https://github.com/tanvirr007/study-releases/releases/latest")
            
            // Extract remote version code (last digit/number segment from tag, e.g. "v2.1.15" -> 15)
            val remoteVersionCode = tagName.substringAfterLast(".").toLongOrNull() ?: 0L
            
            // Extract and clean up only the Changelog section
            val changelog = extractAndCleanChangelog(rawBody)

            // If a newer version is available
            if (remoteVersionCode > localVersionCode) {
                showUpdateNotification(activity.applicationContext, tagName, htmlUrl)
                Handler(Looper.getMainLooper()).post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showUpdateDialog(activity, tagName, changelog, htmlUrl)
                    }
                }
            } else {
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
            .replace(Regex("\\*\\s+"), "• ") // Convert * bullets to standard round bullets
            .replace(Regex("(?m)^\\s*\\*\\s+"), "  - ") // Convert sub-bullets (nested asterisks)
            .replace(Regex("`"), "") // Remove code block ticks
            .trim()
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        changelog: String,
        downloadUrl: String
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available")
            .setMessage("A new version ($versionName) of the app is available.\n\nWhat's New:\n$changelog")
            .setCancelable(false)
            .setPositiveButton("Update Now") { dialog, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open download link", e)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("New Update Available")
            .setContentText("Version $versionName is available to download.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(999, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission not granted", e)
        }
    }
}
