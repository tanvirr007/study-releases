package study.tanvir.info

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class OtaCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var connection: HttpURLConnection? = null
        return try {
            val localVersionCode = UpdateChecker.getLocalVersionCode(applicationContext)
            val cacheBusterUrl = "${UpdateChecker.VERSION_JSON_URL}?t=${System.currentTimeMillis()}"
            val url = URL(cacheBusterUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "CQ-WebView-App")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = StringBuilder()
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                }

                val jsonObject = JSONObject(response.toString())
                val remoteVersionCode = jsonObject.optLong("versionCode", 0L)
                val versionName = jsonObject.optString("versionName", "New Version")
                val downloadUrl = jsonObject.optString("downloadUrl", "https://github.com/tanvirr007/study-releases/releases/latest")

                if (remoteVersionCode > localVersionCode) {
                    UpdateChecker.checkAndShowUpdateNotification(
                        applicationContext,
                        remoteVersionCode,
                        versionName,
                        downloadUrl
                    )
                }
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("OtaCheckWorker", "Error checking for updates in background worker", e)
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val WORK_NAME = "OtaCheckWorkerJob"

        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<OtaCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            } catch (e: Exception) {
                Log.e("OtaCheckWorker", "Failed to schedule periodic OTA check worker", e)
            }
        }
    }
}
