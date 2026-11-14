package com.onecaramel.laizysink.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onecaramel.laizysink.HitlTabRequest
import com.onecaramel.laizysink.security.ZeroTrustKeychain
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Bridges shared HITL requests to Android WebView and the device-local vault. */
class AndroidHitlController(private val activity: Activity) {
    val webViewPool = HiddenWebViewPool(activity)

    private val keychain = ZeroTrustKeychain(activity)
    private val manualCompletions = mutableMapOf<String, (String?) -> Unit>()

    var activeRequest: HitlTabRequest? by mutableStateOf(null)
        private set

    init {
        webViewPool.onManualNavigationCompleted = { tabId ->
            activeRequest = null
            manualCompletions.remove(tabId)?.invoke(null)
        }
    }

    fun reveal(request: HitlTabRequest, onResolved: (String?) -> Unit) {
        val revealPage = {
            val didReveal = webViewPool.reveal(
                tabId = request.tabId,
                url = request.url,
                selector = request.targetSelector,
                tooltip = request.tooltipText,
                domain = request.domain,
            )
            if (didReveal) {
                activeRequest = request
                manualCompletions[request.tabId] = onResolved
            } else {
                activeRequest = null
                Toast.makeText(activity, "Could not open the requested sign-in page.", Toast.LENGTH_LONG).show()
                onResolved(null)
            }
        }
        if (activity.mainLooper.thread == Thread.currentThread()) revealPage() else activity.runOnUiThread(revealPage)
    }

    /** Returns an opaque vault pointer only after biometric approval and successful local injection. */
    suspend fun authenticateAndFill(request: HitlTabRequest): String? = suspendCancellableCoroutine { continuation ->
        if (webViewPool.visibleTabId != request.tabId || webViewPool.tab(request.tabId) == null) {
            val token = continuation.tryResume(null)
            if (token != null) continuation.completeResume(token)
            return@suspendCancellableCoroutine
        }

        val cancellationSignal = keychain.authenticate(activity) { authorized ->
            if (!authorized || !continuation.isActive) {
                val token = continuation.tryResume(null)
                if (token != null) continuation.completeResume(token)
                return@authenticate
            }

            var injectionScheduled = false
            val hasCredentials = keychain.withCredentials(request.domain) { username, password ->
                injectionScheduled = webViewPool.injectCredentials(
                    request.tabId,
                    request.targetSelector,
                    username,
                    password,
                ) { fieldsFound ->
                    val reference = if (fieldsFound) "vault://${request.domain}" else null
                    if (fieldsFound) manualCompletions.remove(request.tabId)
                    val token = continuation.tryResume(reference)
                    if (token != null) continuation.completeResume(token)
                }
            }
            if (!hasCredentials || !injectionScheduled) {
                val token = continuation.tryResume(null)
                if (token != null) continuation.completeResume(token)
            }
        }
        continuation.invokeOnCancellation { cancellationSignal?.cancel() }
    }

    fun openExternalUrl(value: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(activity, "No app is available to open this link.", Toast.LENGTH_SHORT).show() }
    }

    fun returnToApp() {
        webViewPool.visibleTabId?.let { tabId ->
            activeRequest = null
            webViewPool.hide(tabId)
            manualCompletions.remove(tabId)?.invoke(null)
        }
    }

    fun maskedUsername(domain: String): String? = keychain.maskedUsername(domain)

    fun pause() = webViewPool.pauseAll()

    fun resume() = webViewPool.resumeAll()

    fun close() {
        activeRequest = null
        manualCompletions.clear()
        webViewPool.closeAll()
    }
}
