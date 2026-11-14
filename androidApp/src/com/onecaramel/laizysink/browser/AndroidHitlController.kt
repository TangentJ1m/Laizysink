package com.onecaramel.laizysink.browser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onecaramel.laizysink.HitlTabRequest
import com.onecaramel.laizysink.security.VaultResult
import com.onecaramel.laizysink.security.ZeroTrustKeychain
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Bridges shared HITL requests to the foreground WebView and the device-local vault. */
class AndroidHitlController(private val activity: Activity) {
    val webViewPool = HiddenWebViewPool(activity)

    private val keychain = ZeroTrustKeychain(activity)
    private var completion: ((String?) -> Unit)? = null
    private var activeOperation: CancellationSignal? = null
    private var activeSpotlightRequestId: Long? = null
    private var activeOperationId = 0L

    var activeRequest: HitlTabRequest? by mutableStateOf(null)
        private set

    var spotlightMessage: String? by mutableStateOf(null)
        private set

    var requestMessage: String? by mutableStateOf(null)
        private set

    var isAuthenticating by mutableStateOf(false)
        private set

    var isSavingCredentials by mutableStateOf(false)
        private set

    var pendingVaultReference: String? by mutableStateOf(null)
        private set

    init {
        webViewPool.onSpotlightResult = { result ->
            val request = activeRequest
            if (request != null && result.tabId == request.tabId && result.requestId == activeSpotlightRequestId) {
                spotlightMessage = when (result.status) {
                    SpotlightStatus.SHOWN -> null
                    SpotlightStatus.SELECTOR_MISSING -> "The requested sign-in control was not found. You can continue in the page or retry the spotlight."
                    SpotlightStatus.INVALID_SELECTOR -> "The requested sign-in target is invalid. You can continue in the page or retry after the page changes."
                    SpotlightStatus.ERROR -> "The sign-in target could not be highlighted. You can continue in the page or retry."
                }
            }
        }
        webViewPool.onNavigationStarted = { tabId, url ->
            val request = activeRequest
            if (request?.tabId == tabId) {
                requestMessage = if (keychain.matchesDomain(request.domain, url)) {
                    "Page changed. Laizysink is waiting for your confirmation."
                } else {
                    "This page is outside the requested sign-in domain. Keychain actions are paused."
                }
                // Navigation is observable only. A redirect, reload, or page hop never completes HITL.
            }
        }
    }

    /** Accepts a request and reveals its exact tab. A failed reveal stays pending and can be retried. */
    fun reveal(request: HitlTabRequest, onResolved: (String?) -> Unit) {
        val revealPage = {
            cancelActiveOperation()
            webViewPool.visibleTabId?.let(webViewPool::hide)
            activeRequest = request
            completion = onResolved
            activeSpotlightRequestId = null
            pendingVaultReference = null
            requestMessage = null
            spotlightMessage = "Finding the requested sign-in control…"
            revealCurrentRequest()
        }
        if (activity.mainLooper.thread == Thread.currentThread()) revealPage() else activity.runOnUiThread(revealPage)
    }

    private fun revealCurrentRequest(): Boolean {
        val request = activeRequest ?: return false
        val didReveal = webViewPool.reveal(
            tabId = request.tabId,
            url = request.url,
            selector = request.targetSelector,
            tooltip = request.tooltipText,
            domain = request.domain,
        )
        if (didReveal) {
            activeSpotlightRequestId = webViewPool.spotlightRequestId(request.tabId)
            requestMessage = null
            if (activeSpotlightRequestId == null) {
                spotlightMessage = "The page is open. Waiting for the sign-in target check."
            }
        } else {
            spotlightMessage = "The requested page could not be opened safely. Check the request domain and retry."
            requestMessage = "The task remains waiting for this browser step."
        }
        return didReveal
    }

    fun resumeBrowser(): Boolean {
        if (activity.mainLooper.thread != Thread.currentThread()) {
            activity.runOnUiThread { revealCurrentRequest() }
            return true
        }
        return revealCurrentRequest()
    }

    /** Retries spotlight placement without treating a missing selector as a completed sign-in. */
    fun retrySpotlight(): Boolean {
        val request = activeRequest ?: return false
        if (!webViewPool.isRevealed(request.tabId)) return false
        spotlightMessage = "Retrying the sign-in target check…"
        val retried = webViewPool.retrySpotlight(request.tabId)
        if (!retried) spotlightMessage = "The spotlight could not be retried. Resume the requested page first."
        return retried
    }

    fun currentUrl(request: HitlTabRequest): String? =
        if (activeRequest?.sameIdentity(request) == true) webViewPool.currentUrl(request.tabId) else null

    fun currentDomain(request: HitlTabRequest): String? =
        if (activeRequest?.sameIdentity(request) == true) webViewPool.currentDomain(request.tabId) else null

    fun isBrowserVisible(request: HitlTabRequest): Boolean =
        activeRequest?.sameIdentity(request) == true && webViewPool.isRevealed(request.tabId)

    fun isExpectedOrigin(request: HitlTabRequest): Boolean {
        if (activeRequest?.sameIdentity(request) != true) return false
        val url = webViewPool.currentUrl(request.tabId) ?: return false
        return keychain.matchesDomain(request.domain, url)
    }

    fun maskedUsername(domain: String): String? = keychain.maskedUsername(domain)

    /**
     * Shows the biometric prompt and injects only into the requested HTTPS origin. The returned
     * reference is provisional: the user must finish signing in and explicitly resume the task.
     */
    suspend fun authenticateAndFill(request: HitlTabRequest): String? {
        if (!canUseKeychain(request)) {
            requestMessage = "Autofill is paused because the requested tab or HTTPS domain is not active."
            return null
        }
        cancelActiveOperation()
        val operationId = activeOperationId
        isAuthenticating = true
        requestMessage = null
        pendingVaultReference = null

        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            var authResult: VaultResult? = null
            var injectionScheduled = false
            var fieldsFound: Boolean? = null
            var signal: CancellationSignal? = null

            fun finishIfReady() {
                if (resumed.get() || !continuation.isActive) return
                val result = authResult ?: return
                val reference = when (result) {
                    VaultResult.AUTHORIZED -> {
                        if (!injectionScheduled || fieldsFound == false) null
                        else if (fieldsFound == true && canUseKeychain(request)) "vault://${normalizedDomain(request.domain)}"
                        else null
                    }
                    else -> null
                }
                if (!resumed.compareAndSet(false, true)) return
                if (operationId != activeOperationId || activeRequest?.sameIdentity(request) != true) {
                    continuation.resume(null)
                    return
                }
                activeOperation = null
                isAuthenticating = false
                if (reference != null) {
                    pendingVaultReference = reference
                    requestMessage = "Credentials were placed in the sign-in form. Finish signing in on the page, then resume the task here."
                } else {
                    requestMessage = when (result) {
                        VaultResult.NO_MATCH -> "No saved credentials were found for this domain. You can continue manually in the browser."
                        VaultResult.CANCELLED -> "Biometric authentication was cancelled. This request is still open."
                        VaultResult.KEY_INVALIDATED -> "The saved credential key is unavailable. Recreate the local credential after signing in manually."
                        VaultResult.INVALID_DOMAIN -> "The current page does not match the saved credential domain. Autofill was stopped."
                        VaultResult.FAILED -> "Biometric authentication or local autofill failed. The request is still open."
                        VaultResult.SAVED -> "Credentials were saved locally. Authenticate again to fill the sign-in form."
                        VaultResult.AUTHORIZED -> if (fieldsFound == true) {
                            "The page changed before autofill finished. The credential reference was not sent."
                        } else {
                            "The sign-in form did not accept the requested fields. Continue manually in the browser."
                        }
                    }
                }
                continuation.resume(reference)
            }

            val returnedSignal = keychain.authenticateAndUseCredentials(
                activity = activity,
                domain = request.domain,
                use = { username, password ->
                    if (!canUseKeychain(request)) {
                        fieldsFound = false
                        injectionScheduled = false
                        false
                    } else {
                        val scheduled = webViewPool.injectCredentials(
                            tabId = request.tabId,
                            targetSelector = request.targetSelector,
                            username = username,
                            password = password,
                        ) { found ->
                            fieldsFound = found
                            finishIfReady()
                        }
                        injectionScheduled = scheduled
                        if (!scheduled) fieldsFound = false
                        scheduled
                    }
                },
                onResult = { result ->
                    authResult = result
                    finishIfReady()
                },
            )
            signal = returnedSignal
            if (operationId == activeOperationId) activeOperation = returnedSignal
            continuation.invokeOnCancellation {
                signal?.cancel()
                if (operationId == activeOperationId) {
                    if (activeOperation === signal) activeOperation = null
                    isAuthenticating = false
                }
            }
        }
    }

    /** Opt-in save for credentials entered by the user; values never enter shared state or events. */
    suspend fun saveCredentials(request: HitlTabRequest, username: String, password: CharArray): VaultResult {
        if (!isExpectedOrigin(request) || !webViewPool.isRevealed(request.tabId)) {
            password.fill('\u0000')
            requestMessage = "Credentials can only be saved while the requested HTTPS domain is visible."
            return VaultResult.INVALID_DOMAIN
        }
        cancelActiveOperation()
        val operationId = activeOperationId
        isSavingCredentials = true
        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            var signal: CancellationSignal? = null
            val returnedSignal = keychain.saveWithBiometrics(
                activity = activity,
                domain = request.domain,
                username = username,
                password = password,
                onResult = { result ->
                    if (resumed.compareAndSet(false, true)) {
                        if (operationId == activeOperationId && activeRequest?.sameIdentity(request) == true) {
                            activeOperation = null
                            isSavingCredentials = false
                            requestMessage = when (result) {
                                VaultResult.SAVED -> "Encrypted credentials were saved on this device. Authenticate to fill the sign-in form."
                                VaultResult.CANCELLED -> "Saving was cancelled. The browser request is still open."
                                VaultResult.KEY_INVALIDATED -> "The local encryption key is unavailable. Sign in manually or recreate the keychain."
                                VaultResult.INVALID_DOMAIN -> "Credentials were not saved because the requested domain is invalid."
                                VaultResult.FAILED -> "Credentials could not be saved. The browser request is still open."
                                VaultResult.AUTHORIZED -> "The credential operation completed."
                                VaultResult.NO_MATCH -> "No credential was stored."
                            }
                        }
                        continuation.resume(result)
                    }
                },
            )
            signal = returnedSignal
            if (operationId == activeOperationId) activeOperation = returnedSignal
            continuation.invokeOnCancellation {
                signal?.cancel()
                if (operationId == activeOperationId) {
                    if (activeOperation === signal) activeOperation = null
                    isSavingCredentials = false
                }
            }
        }
    }

    private fun canUseKeychain(request: HitlTabRequest): Boolean =
        activeRequest?.sameIdentity(request) == true &&
            webViewPool.isRevealed(request.tabId) &&
            keychain.matchesDomain(request.domain, webViewPool.currentUrl(request.tabId).orEmpty())

    /** Sends the opaque reference only after successful local fill and an explicit user confirmation. */
    fun confirmKeychainCompletion(request: HitlTabRequest): Boolean {
        val reference = pendingVaultReference ?: return false
        if (activeRequest?.sameIdentity(request) != true || !canUseKeychain(request)) {
            requestMessage = "The requested HTTPS page is no longer active. Resume it before completing this step."
            return false
        }
        val onResolved = completion
        if (onResolved == null) {
            requestMessage = "The task callback is unavailable. Keep this request open and contact the app shell."
            return false
        }

        completion = null
        pendingVaultReference = null
        activeSpotlightRequestId = null
        activeRequest = null
        webViewPool.hide(request.tabId)
        requestMessage = null
        onResolved(reference)
        return true
    }

    /** Hides the page while preserving the pending request and its completion callback. */
    fun hideBrowserForLater() {
        activeRequest?.let { request ->
            webViewPool.hide(request.tabId)
            requestMessage = "This browser request is still waiting. Resume the page to continue."
        }
    }

    fun openExternalUrl(value: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(activity, "No app is available to open this link.", Toast.LENGTH_SHORT).show() }
    }

    fun pause() = webViewPool.pauseAll()

    fun resume() = webViewPool.resumeAll()

    /** Cancels prompts and releases browser references without falsely completing the task. */
    fun close() {
        cancelActiveOperation()
        activeRequest = null
        completion = null
        pendingVaultReference = null
        activeSpotlightRequestId = null
        webViewPool.onSpotlightResult = null
        webViewPool.onNavigationStarted = null
        webViewPool.closeAll()
    }

    private fun cancelActiveOperation() {
        activeOperationId += 1
        activeOperation?.cancel()
        activeOperation = null
        isAuthenticating = false
        isSavingCredentials = false
    }

    private fun normalizedDomain(domain: String): String = domain.trim().lowercase().removePrefix("www.")

    private fun HitlTabRequest.sameIdentity(other: HitlTabRequest): Boolean =
        taskId == other.taskId && tabId == other.tabId
}
