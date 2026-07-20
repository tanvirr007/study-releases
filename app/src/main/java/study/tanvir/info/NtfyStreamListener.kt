package study.tanvir.info

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NtfyStreamListener {
    private const val TAG = "NtfyStreamListener"
    private const val NTFY_TOPIC_URL = "https://ntfy.sh/tanvir007_cq_ota/json"
    
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    
    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Starting NtfyStreamListener...")
            registerNetworkCallback(context.applicationContext)
            connectStream()
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "Stopping NtfyStreamListener...")
            unregisterNetworkCallback()
            isConnected.set(false)
        }
    }

    private fun connectStream() {
        executor.execute {
            var retryDelayMs = 5000L
            val maxRetryDelayMs = 60000L

            while (isRunning.get()) {
                var connection: HttpURLConnection? = null
                var reader: BufferedReader? = null

                try {
                    Log.d(TAG, "Connecting to ntfy stream: $NTFY_TOPIC_URL")
                    val url = URL(NTFY_TOPIC_URL)
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 0 // Keep connection open indefinitely for SSE stream
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/x-ndjson")

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        isConnected.set(true)
                        retryDelayMs = 5000L // Reset backoff on successful connection
                        Log.i(TAG, "Connected successfully to ntfy.sh real-time OTA stream")

                        reader = BufferedReader(InputStreamReader(connection.inputStream))
                        var line: String? = reader.readLine()

                        while (isRunning.get() && line != null) {
                            val rawLine = line.trim()
                            if (rawLine.isNotEmpty()) {
                                handleNtfyEvent(rawLine)
                            }
                            line = reader.readLine()
                        }
                    } else {
                        Log.w(TAG, "ntfy stream HTTP connection failed with status code: $responseCode")
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "ntfy stream connection error, retrying in ${retryDelayMs / 1000}s", e)
                    }
                } finally {
                    isConnected.set(false)
                    try { reader?.close() } catch (_: Exception) {}
                    try { connection?.disconnect() } catch (_: Exception) {}
                }

                if (!isRunning.get()) break

                // Exponential backoff before reconnecting
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    break
                }
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryDelayMs)
            }
        }
    }

    private fun handleNtfyEvent(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val event = json.optString("event", "")
            
            if (event == "message") {
                val title = json.optString("title", "OTA")
                val message = json.optString("message", "")
                Log.i(TAG, "Received real-time ntfy OTA push! Title: $title | Message: $message")

                appContext?.let { context ->
                    UpdateChecker.checkForUpdates(context)
                }
            } else if (event == "open") {
                Log.d(TAG, "ntfy stream connection confirmed active.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ntfy event JSON: $jsonText", e)
        }
    }

    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available, ensuring ntfy stream listener is active...")
                    if (isRunning.get() && !isConnected.get()) {
                        connectStream()
                    }
                }
            }

            networkCallback = callback

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback for ntfy listener", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val context = appContext ?: return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            networkCallback?.let {
                cm.unregisterNetworkCallback(it)
                networkCallback = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }
}
