package study.tanvir.info

import android.animation.ObjectAnimator
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import java.util.concurrent.atomic.AtomicBoolean

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val KEY_LAST_NOTIFIED_VERSION_CODE = "last_notified_version_code"
    const val EXTRA_AUTO_UPDATE_DIALOG = "extra_auto_update_dialog"
    private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours

    const val VERSION_JSON_URL = "https://raw.githubusercontent.com/tanvirr007/study-releases/master/version.json"

    // Single static executor to reuse background threads
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val isChecking = AtomicBoolean(false)
    private val isManualCheckRequested = AtomicBoolean(false)
    private var activeDialogRef: WeakReference<AlertDialog>? = null
    private var currentActivityRef: WeakReference<Activity>? = null

    fun checkForUpdates(activity: Activity, isManualCheck: Boolean = false) {
        currentActivityRef = WeakReference(activity)
        val context = activity.applicationContext

        // Get local version code
        val localVersionCode = getLocalVersionCode(context)

        if (isManualCheck) {
            isManualCheckRequested.set(true)
            Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
            // Dismiss stale or currently visible dialog to display a clean new update dialog
            Handler(Looper.getMainLooper()).post {
                activeDialogRef?.get()?.let {
                    if (it.isShowing) {
                        try { it.dismiss() } catch (_: Exception) {}
                    }
                }
            }
        }

        if (!isChecking.compareAndSet(false, true)) {
            Log.d(TAG, "Update check already in progress, registered manual request on active check.")
            return
        }

        // Fetch latest version manifest asynchronously from Raw GitHub CDN with cache-busting
        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val cacheBusterUrl = "$VERSION_JSON_URL?t=${System.currentTimeMillis()}"
                val url = URL(cacheBusterUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Expires", "0")
                connection.setRequestProperty("User-Agent", "CQ-WebView-App")
                connection.setRequestProperty("Accept", "application/json")

                val wasManual = isManualCheckRequested.getAndSet(false) || isManualCheck

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = StringBuilder()
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                    }
                    parseAndProcessResponse(context, response.toString(), localVersionCode, wasManual)
                } else {
                    Log.e(TAG, "Server returned response code: $responseCode")
                    if (wasManual) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "Unable to check for updates (Server: $responseCode)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                val wasManual = isManualCheckRequested.getAndSet(false) || isManualCheck
                if (wasManual) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Failed to check for updates", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                isChecking.set(false)
                connection?.disconnect()
            }
        }
    }

    fun checkForUpdates(context: Context) {
        val localVersionCode = getLocalVersionCode(context)
        if (!isChecking.compareAndSet(false, true)) {
            Log.d(TAG, "Update check already in progress, skipping background context request.")
            return
        }

        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val cacheBusterUrl = "$VERSION_JSON_URL?t=${System.currentTimeMillis()}"
                val url = URL(cacheBusterUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Expires", "0")
                connection.setRequestProperty("User-Agent", "CQ-WebView-App")
                connection.setRequestProperty("Accept", "application/json")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = StringBuilder()
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                    }
                    parseAndProcessResponse(context, response.toString(), localVersionCode, false)
                } else {
                    Log.e(TAG, "Server returned response code for context update check: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates from context", e)
            } finally {
                isChecking.set(false)
                connection?.disconnect()
            }
        }
    }

    fun getLocalVersionCode(context: Context): Long {
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

    fun getLocalVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local version name", e)
            ""
        }
    }

    private fun parseAndProcessResponse(
        context: Context,
        jsonResponse: String,
        localVersionCode: Long,
        isManualCheck: Boolean = false
    ) {
        try {
            val jsonObject = JSONObject(jsonResponse)
            val remoteVersionCode = jsonObject.optLong("versionCode", 0L)
            val versionName = jsonObject.optString("versionName", "New Version")
            val downloadUrl = jsonObject.optString("downloadUrl", "https://github.com/tanvirr007/study-releases/releases/latest")
            val rawBody = jsonObject.optString("changelog", "No changelog provided.")

            // Clean up changelog text for dialog display
            val changelog = extractAndCleanChangelog(rawBody)

            // If a newer version is available
            if (remoteVersionCode > localVersionCode) {
                checkAndShowUpdateNotification(context, remoteVersionCode, versionName, downloadUrl)
                Handler(Looper.getMainLooper()).post {
                    val activity = currentActivityRef?.get()
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                        showUpdateDialog(activity, versionName, changelog, downloadUrl, remoteVersionCode)
                    }
                }
            } else {
                Log.d(TAG, "App is up-to-date (Local: $localVersionCode, Remote: $remoteVersionCode)")
                if (isManualCheck) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "You are using the latest version ($versionName)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing version.json", e)
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
            .replace(Regex("`"), "") // Remove code block ticks
            .replace(Regex("(?m)^(\\s+)[-*•][-\\s*•]*"), "$1- ") // Sub-bullets get single '-' with indentation
            .replace(Regex("(?m)^(?![-*•]\\s*(?:Version|Commit|Build Time|Android|SHA-256):)[-*•]\\s*"), "• ") // Main commits get single '•'
            .trim()
    }

    private fun showUpdateDialog(
        activity: Activity,
        versionName: String,
        changelog: String,
        downloadUrl: String,
        remoteVersionCode: Long
    ) {
        val currentVersionName = getLocalVersionName(activity)
        val currentVerStr = if (currentVersionName.startsWith("v", ignoreCase = true)) currentVersionName else "v$currentVersionName"
        val targetVerStr = if (versionName.startsWith("v", ignoreCase = true)) versionName else "v$versionName"

        val messageText = "A new version of the app is available\n\nWhat's New:\n$changelog"
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
        val padding = (20 * density).toInt()

        // Fetch theme colors dynamically
        val typedArray = activity.obtainStyledAttributes(intArrayOf(
            android.R.attr.colorPrimary,
            android.R.attr.textColorPrimary,
            android.R.attr.textColorSecondary
        ))
        val primaryColor = typedArray.getColor(0, android.graphics.Color.parseColor("#1976D2"))
        val primaryTextColor = typedArray.getColor(1, android.graphics.Color.BLACK)
        val secondaryTextColor = typedArray.getColor(2, android.graphics.Color.GRAY)
        typedArray.recycle()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // Version Transition Pill (e.g. v2.1.30 → v2.1.31)
        val versionPill = TextView(activity).apply {
            text = "$currentVerStr  →  $targetVerStr"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)

            val pillBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(android.graphics.Color.argb(28, android.graphics.Color.red(primaryColor), android.graphics.Color.green(primaryColor), android.graphics.Color.blue(primaryColor)))
            }
            background = pillBg
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
        }
        container.addView(versionPill)

        // Changelog message view
        val messageView = TextView(activity).apply {
            text = spannableMessage
            textSize = 14f
            setTextColor(primaryTextColor)
        }
        container.addView(messageView)

        // Progress section container (initially GONE)
        val progressContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Status message & percentage row
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val statusTextView = TextView(activity).apply {
            text = "Downloading update..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryTextColor)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        statusRow.addView(statusTextView)

        val percentageTextView = TextView(activity).apply {
            text = "0%"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
        }
        statusRow.addView(percentageTextView)
        progressContainer.addView(statusRow)

        // Animated horizontal progress bar
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressDrawable?.setTint(primaryColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            ).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
        }
        progressContainer.addView(progressBar)

        // Metrics row (Downloaded Size / Total Size | Download Speed | ETA)
        val metricsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val sizeTextView = TextView(activity).apply {
            text = "0.0 MB / -- MB"
            textSize = 12f
            setTextColor(secondaryTextColor)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        metricsRow.addView(sizeTextView)

        val speedTextView = TextView(activity).apply {
            text = "-- MB/s"
            textSize = 12f
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        metricsRow.addView(speedTextView)

        val etaTextView = TextView(activity).apply {
            text = "--"
            textSize = 12f
            setTextColor(secondaryTextColor)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        metricsRow.addView(etaTextView)
        progressContainer.addView(metricsRow)

        // Informational user message at bottom
        val infoTextView = TextView(activity).apply {
            text = "Please keep the app open during the update"
            textSize = 11f
            setTextColor(secondaryTextColor)
            setTypeface(null, Typeface.ITALIC)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
        }
        progressContainer.addView(infoTextView)

        container.addView(progressContainer)

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            isScrollbarFadingEnabled = false
            addView(container)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Update Now", null)
            .setNegativeButton("Later") { d, _ ->
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                d.dismiss()
            }
            .create()

        activeDialogRef = WeakReference(dialog)
        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        positiveButton.setOnClickListener {
            // Android 8.0+ installation permission check
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

            // Fallback for HTML release pages
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

            // Start real-time download flow
            downloadApk(
                activity = activity,
                downloadUrl = downloadUrl,
                messageView = messageView,
                progressContainer = progressContainer,
                statusTextView = statusTextView,
                percentageTextView = percentageTextView,
                progressBar = progressBar,
                sizeTextView = sizeTextView,
                speedTextView = speedTextView,
                etaTextView = etaTextView,
                infoTextView = infoTextView,
                secondaryTextColor = secondaryTextColor,
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
        messageView: TextView,
        progressContainer: View,
        statusTextView: TextView,
        percentageTextView: TextView,
        progressBar: ProgressBar,
        sizeTextView: TextView,
        speedTextView: TextView,
        etaTextView: TextView,
        infoTextView: TextView,
        secondaryTextColor: Int,
        positiveButton: Button,
        negativeButton: Button,
        dialog: AlertDialog,
        remoteVersionCode: Long,
        versionName: String
    ) {
        val activityRef = WeakReference(activity)
        val isCancelled = AtomicBoolean(false)

        // UI State: Downloading
        dialog.setTitle("Updating App")
        messageView.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE
        statusTextView.text = "Downloading update..."
        percentageTextView.text = "0%"
        progressBar.isIndeterminate = true
        positiveButton.visibility = View.GONE
        
        negativeButton.visibility = View.VISIBLE
        negativeButton.text = "Cancel"
        negativeButton.setOnClickListener {
            isCancelled.set(true)
            dialog.dismiss()
            Toast.makeText(activity.applicationContext, "Update download cancelled", Toast.LENGTH_SHORT).show()
        }

        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            var apkFile: File? = null

            try {
                val url = URL(downloadUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                val fileLength = connection.contentLength
                inputStream = connection.inputStream

                val currentAct = activityRef.get()
                if (currentAct == null || currentAct.isFinishing || currentAct.isDestroyed || isCancelled.get()) {
                    return@execute
                }

                apkFile = File(currentAct.cacheDir, "update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                outputStream = FileOutputStream(apkFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int

                var lastSampleTime = System.currentTimeMillis()
                var lastSampleBytes = 0L
                var speedFilter = 0L

                while (inputStream.read(data).also { count = it } != -1) {
                    val loopAct = activityRef.get()
                    if (loopAct == null || loopAct.isFinishing || loopAct.isDestroyed || isCancelled.get()) {
                        break
                    }

                    total += count
                    outputStream.write(data, 0, count)

                    val now = System.currentTimeMillis()
                    val timeDeltaMs = now - lastSampleTime

                    // Update UI metrics every ~350ms or when complete
                    if (timeDeltaMs >= 350 || (fileLength > 0 && total >= fileLength)) {
                        val timeDeltaSec = timeDeltaMs / 1000.0
                        val bytesDelta = total - lastSampleBytes
                        val currentSpeed = if (timeDeltaSec > 0) (bytesDelta / timeDeltaSec).toLong() else 0L

                        speedFilter = if (speedFilter == 0L) currentSpeed else (0.7 * speedFilter + 0.3 * currentSpeed).toLong()
                        lastSampleTime = now
                        lastSampleBytes = total

                        val progress = if (fileLength > 0) (total * 100 / fileLength).toInt() else 0
                        val etaSeconds = if (speedFilter > 0 && fileLength > total) (fileLength - total) / speedFilter else 0L

                        val currentDownloadedStr = formatBytes(total)
                        val totalSizeStr = if (fileLength > 0) formatBytes(fileLength.toLong()) else "--"
                        val speedStr = formatSpeed(speedFilter)
                        val etaStr = if (fileLength > 0 && etaSeconds >= 0) formatEta(etaSeconds) else "--"

                        Handler(Looper.getMainLooper()).post {
                            val viewAct = activityRef.get()
                            if (viewAct == null || viewAct.isFinishing || viewAct.isDestroyed || isCancelled.get()) return@post

                            statusTextView.text = "Downloading update..."
                            percentageTextView.text = "$progress%"
                            
                            if (fileLength > 0) {
                                progressBar.isIndeterminate = false
                                updateProgressSmoothly(progressBar, progress)
                                sizeTextView.text = "$currentDownloadedStr / $totalSizeStr"
                            } else {
                                progressBar.isIndeterminate = true
                                sizeTextView.text = currentDownloadedStr
                            }
                            
                            speedTextView.text = speedStr
                            etaTextView.text = etaStr
                        }
                    }
                }
                outputStream.flush()

                if (isCancelled.get()) {
                    apkFile.delete()
                    return@execute
                }

                val finalAct = activityRef.get()
                if (finalAct == null || finalAct.isFinishing || finalAct.isDestroyed) return@execute

                // --- STAGE 1: Download complete ---
                Handler(Looper.getMainLooper()).post {
                    if (finalAct.isFinishing || finalAct.isDestroyed || isCancelled.get()) return@post
                    statusTextView.text = "Download complete"
                    percentageTextView.text = "100%"
                    progressBar.isIndeterminate = false
                    updateProgressSmoothly(progressBar, 100)
                    speedTextView.text = ""
                    etaTextView.text = ""
                    if (apkFile != null && apkFile.exists()) {
                        sizeTextView.text = formatBytes(apkFile.length())
                    }
                }

                Thread.sleep(600)
                if (isCancelled.get()) return@execute

                // --- STAGE 2: Verifying APK... ---
                Handler(Looper.getMainLooper()).post {
                    if (finalAct.isFinishing || finalAct.isDestroyed || isCancelled.get()) return@post
                    statusTextView.text = "Verifying APK..."
                    progressBar.isIndeterminate = true
                }

                // APK validation check using PackageManager
                var isApkValid = false
                if (apkFile != null && apkFile.exists() && apkFile.length() > 0) {
                    try {
                        val packageInfo = finalAct.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                        isApkValid = packageInfo != null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing downloaded APK package", e)
                        isApkValid = false
                    }
                }

                if (!isApkValid) {
                    Handler(Looper.getMainLooper()).post {
                        if (finalAct.isFinishing || finalAct.isDestroyed || isCancelled.get()) return@post
                        dialog.setTitle("Verification Failed")
                        statusTextView.text = "Verification failed"
                        progressBar.visibility = View.GONE
                        infoTextView.text = "Downloaded update is invalid or corrupted"
                        infoTextView.setTextColor(android.graphics.Color.RED)
                        
                        positiveButton.text = "Retry"
                        positiveButton.visibility = View.VISIBLE
                        negativeButton.visibility = View.VISIBLE
                        negativeButton.text = "Cancel"

                        positiveButton.setOnClickListener {
                            infoTextView.setTextColor(secondaryTextColor)
                            infoTextView.text = "Please keep the app open during the update"
                            downloadApk(
                                activity = finalAct,
                                downloadUrl = downloadUrl,
                                messageView = messageView,
                                progressContainer = progressContainer,
                                statusTextView = statusTextView,
                                percentageTextView = percentageTextView,
                                progressBar = progressBar,
                                sizeTextView = sizeTextView,
                                speedTextView = speedTextView,
                                etaTextView = etaTextView,
                                infoTextView = infoTextView,
                                secondaryTextColor = secondaryTextColor,
                                positiveButton = positiveButton,
                                negativeButton = negativeButton,
                                dialog = dialog,
                                remoteVersionCode = remoteVersionCode,
                                versionName = versionName
                            )
                        }
                    }
                    return@execute
                }

                Thread.sleep(600)
                if (isCancelled.get()) return@execute

                // --- STAGE 3: Preparing installer... ---
                Handler(Looper.getMainLooper()).post {
                    if (finalAct.isFinishing || finalAct.isDestroyed || isCancelled.get()) return@post
                    statusTextView.text = "Preparing installer..."
                    
                    // Save pending update info so post-install splash/toast can verify on app relaunch
                    val prefs = finalAct.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("pending_update_version", versionName)
                        .putLong("pending_update_version_code", remoteVersionCode)
                        .apply()
                }

                Thread.sleep(500)
                if (isCancelled.get()) return@execute

                // --- STAGE 4: Launching Android installer... ---
                Handler(Looper.getMainLooper()).post {
                    if (finalAct.isFinishing || finalAct.isDestroyed || isCancelled.get()) return@post
                    statusTextView.text = "Launching Android installer..."
                }

                Thread.sleep(400)

                Handler(Looper.getMainLooper()).post {
                    if (finalAct.isFinishing || finalAct.isDestroyed || isCancelled.get()) return@post
                    if (apkFile != null && apkFile.exists()) {
                        installApk(finalAct, apkFile)
                    }
                    dialog.dismiss()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading update APK", e)

                if (isCancelled.get()) return@execute

                Handler(Looper.getMainLooper()).post {
                    val errorAct = activityRef.get()
                    if (errorAct == null || errorAct.isFinishing || errorAct.isDestroyed) return@post

                    dialog.setTitle("Download Failed")
                    statusTextView.text = "Download failed"
                    progressBar.visibility = View.GONE
                    infoTextView.text = "The connection was lost or the download failed"
                    infoTextView.setTextColor(android.graphics.Color.RED)

                    positiveButton.text = "Retry"
                    positiveButton.visibility = View.VISIBLE
                    negativeButton.visibility = View.VISIBLE
                    negativeButton.text = "Cancel"

                    positiveButton.setOnClickListener {
                        infoTextView.setTextColor(secondaryTextColor)
                        infoTextView.text = "Please keep the app open during the update"
                        downloadApk(
                            activity = errorAct,
                            downloadUrl = downloadUrl,
                            messageView = messageView,
                            progressContainer = progressContainer,
                            statusTextView = statusTextView,
                            percentageTextView = percentageTextView,
                            progressBar = progressBar,
                            sizeTextView = sizeTextView,
                            speedTextView = speedTextView,
                            etaTextView = etaTextView,
                            infoTextView = infoTextView,
                            secondaryTextColor = secondaryTextColor,
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

    private fun updateProgressSmoothly(progressBar: ProgressBar, newProgress: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            progressBar.setProgress(newProgress, true)
        } else {
            ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, newProgress)
                .setDuration(300)
                .start()
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

    fun checkAndShowUpdateNotification(
        context: Context,
        remoteVersionCode: Long,
        versionName: String,
        downloadUrl: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotifiedVersionCode = prefs.getLong(KEY_LAST_NOTIFIED_VERSION_CODE, -1L)
        
        // Skip if notification was already posted for this exact remote version
        if (lastNotifiedVersionCode == remoteVersionCode) {
            return
        }
        
        showUpdateNotification(context, versionName, downloadUrl)
        prefs.edit().putLong(KEY_LAST_NOTIFIED_VERSION_CODE, remoteVersionCode).apply()
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
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new application updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AUTO_UPDATE_DIALOG, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val verStr = if (versionName.startsWith("v", ignoreCase = true)) versionName else "v$versionName"

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OTA")
            .setContentText("Version $verStr is now available")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec.toDouble() / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec.toDouble() / 1024)
            bytesPerSec > 0 -> "$bytesPerSec B/s"
            else -> "0 KB/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds >= 3600 -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                String.format(Locale.US, "%dh %02dm remaining", hours, mins)
            }
            seconds >= 60 -> {
                val mins = seconds / 60
                val secs = seconds % 60
                String.format(Locale.US, "%dm %02ds remaining", mins, secs)
            }
            seconds > 0 -> "${seconds}s remaining"
            else -> "--"
        }
    }
}

