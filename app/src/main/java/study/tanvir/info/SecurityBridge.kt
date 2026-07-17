package study.tanvir.info

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SecurityBridge(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "security_prefs"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val AUTH_TIMEOUT_SECONDS = 60L

        // BIOMETRIC_WEAK covers both weak and strong sensors.
        // Combining BIOMETRIC_STRONG with DEVICE_CREDENTIAL is illegal on API 28-29
        // and combining all three throws IllegalArgumentException.
        private val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    @JavascriptInterface
    fun isBiometricEnabled(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BIOMETRIC, false)
    }

    @JavascriptInterface
    fun setBiometricEnabled(enabled: Boolean) {
        val activity = context as? AppCompatActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val latch = CountDownLatch(1)
        var success = false

        val subtitle = if (enabled) "Authenticate to enable app lock"
            else "Authenticate to disable app lock"

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }

            val executor = ContextCompat.getMainExecutor(activity)

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    success = true
                    latch.countDown()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    latch.countDown()
                }

                override fun onAuthenticationFailed() {
                    // Don't count down — wait for final error or success
                }
            }

            try {
                val prompt = BiometricPrompt(activity, executor, callback)

                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm Identity")
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()

                prompt.authenticate(info)
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        // Timeout prevents hanging the JS thread forever if prompt is dismissed
        // without triggering a callback (e.g. activity destroyed)
        latch.await(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (success) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BIOMETRIC, enabled)
                .apply()
        }
    }
}
