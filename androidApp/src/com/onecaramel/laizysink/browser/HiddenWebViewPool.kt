package com.onecaramel.laizysink.browser

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

enum class SpotlightStatus {
    SHOWN,
    SELECTOR_MISSING,
    INVALID_SELECTOR,
    ERROR,
}

data class SpotlightResult(val tabId: String, val requestId: Long, val status: SpotlightStatus)

/** Keeps attached WebViews alive between research steps so cookies and page state remain available. */
class HiddenWebViewPool(context: Context) {
    private val webContext = context
    private val tabs = linkedMapOf<String, WebView>()
    private val requestedUrls = mutableMapOf<String, String>()
    private val spotlightRequests = mutableMapOf<String, SpotlightRequest>()
    private val pageGenerations = mutableMapOf<String, Long>()
    private val mutableTabIds = mutableStateListOf<String>()
    private var nextSpotlightRequestId = 0L
    private val spotlightScript by lazy {
        webContext.assets.open("spotlight.js").bufferedReader().use { it.readText() }
    }

    val tabIds: List<String> get() = mutableTabIds

    var visibleTabId: String? by mutableStateOf(null)
        private set

    var visibleUrl: String? by mutableStateOf(null)
        private set

    /** Reports navigation starts for observation only; navigation does not mean authentication succeeded. */
    var onNavigationStarted: ((tabId: String, url: String) -> Unit)? = null

    /** Reports the result of applying the current spotlight request, independently of authentication. */
    var onSpotlightResult: ((SpotlightResult) -> Unit)? = null

    fun currentUrl(tabId: String): String? = tabs[tabId]?.url

    fun currentDomain(tabId: String): String? = currentUrl(tabId)?.let(::hostOf)

    fun isRevealed(tabId: String): Boolean = visibleTabId == tabId && tabs[tabId]?.alpha == 1f

    fun spotlightRequestId(tabId: String): Long? = spotlightRequests[tabId]?.requestId

    /** Registers a background page. It remains attached and loaded when another tab is revealed. */
    fun open(tabId: String, url: String): Boolean {
        if (!isSafeWebUrl(url)) return false
        val existing = tabs[tabId]
        if (existing != null) {
            if (requestedUrls[tabId] != url) {
                requestedUrls[tabId] = url
                existing.loadUrl(url)
            }
            return true
        }

        val webView = WebView(webContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            alpha = 0f
            visibility = View.VISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                    val currentPageUrl = pageUrl ?: view.url
                    pageGenerations[tabId] = (pageGenerations[tabId] ?: 0L) + 1L
                    if (visibleTabId == tabId) visibleUrl = currentPageUrl
                    spotlightRequests[tabId]?.reported = false
                    currentPageUrl?.let { onNavigationStarted?.invoke(tabId, it) }
                }

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    val currentPageUrl = pageUrl ?: view.url
                    if (visibleTabId == tabId) visibleUrl = currentPageUrl
                    spotlightRequests[tabId]?.let { request -> applySpotlight(tabId, request) }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isSafeWebUrl(request.url.toString())
            }
        }
        tabs[tabId] = webView
        requestedUrls[tabId] = url
        mutableTabIds += tabId
        webView.loadUrl(url)
        return true
    }

    /** Opens/reuses the event's tab and reveals its requested login target. Call on the UI thread. */
    fun reveal(tabId: String, url: String, selector: String, tooltip: String, domain: String): Boolean {
        // HITL may lead to credential entry, so never reveal a cleartext or mismatched origin.
        if (!isSecureWebUrl(url) || !matchesDomain(url, domain) || !open(tabId, url)) return false
        val request = SpotlightRequest(++nextSpotlightRequestId, selector, tooltip)
        spotlightRequests[tabId] = request
        tabs[tabId]?.let { webView ->
            visibleTabId?.takeIf { it != tabId }?.let(::hide)
            visibleTabId = tabId
            showTab(webView)
            if (webView.progress == 100) applySpotlight(tabId, request)
        }
        return tabs.containsKey(tabId)
    }

    fun tab(id: String): WebView? = tabs[id]

    /** Retries a missing or failed selector after the caller has surfaced a recoverable error. */
    fun retrySpotlight(tabId: String): Boolean {
        val request = spotlightRequests[tabId] ?: return false
        if (!isRevealed(tabId)) return false
        request.reported = false
        if (tabs[tabId]?.progress == 100) applySpotlight(tabId, request)
        return true
    }

    /** Injects only into standard username/password fields; it never submits the form. */
    fun injectCredentials(tabId: String, targetSelector: String, username: String, password: CharArray, onComplete: (Boolean) -> Unit): Boolean {
        val webView = tabs[tabId] ?: return false
        val script = """(function(){
            let target;try{target=document.querySelector(${JSONObject.quote(targetSelector)});}catch(_){return false;}
            if(!target)return false;
            const form=target.form||target.closest('form')||target;
            const user=(form.matches&&form.matches('input'))?form:form.querySelector('input[autocomplete="username"],input[type="email"],input[name*="user" i]');
            const pass=form.querySelector('input[autocomplete="current-password"],input[type="password"]');
            if(!user||!pass)return false;
            const set=(el,value)=>{const d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,value);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));};
            set(user,${JSONObject.quote(username)});set(pass,${JSONObject.quote(String(password))});return true;
        })()""".trimIndent()
        webView.evaluateJavascript(script) { result -> onComplete(result == "true") }
        return true
    }

    fun hide(id: String) {
        tabs[id]?.let { webView ->
            webView.evaluateJavascript("window.LaizysinkSpotlight?.clear();", null)
            webView.alpha = 0f
            webView.isClickable = false
            webView.isFocusable = false
        }
        spotlightRequests.remove(id)
        if (visibleTabId == id) {
            visibleTabId = null
            visibleUrl = null
        }
    }

    fun close(id: String) {
        if (visibleTabId == id) {
            visibleTabId = null
            visibleUrl = null
        }
        spotlightRequests.remove(id)
        pageGenerations.remove(id)
        requestedUrls.remove(id)
        tabs.remove(id)?.let { webView ->
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        mutableTabIds.remove(id)
    }

    fun closeAll() = tabs.keys.toList().forEach(::close)

    fun pauseAll() = tabs.values.forEach(WebView::onPause)

    fun resumeAll() = tabs.values.forEach(WebView::onResume)

    private fun applySpotlight(tabId: String, request: SpotlightRequest) {
        val webView = tabs[tabId] ?: return
        if (!isRevealed(tabId) || webView.progress != 100 || spotlightRequests[tabId]?.requestId != request.requestId) return
        val pageGeneration = pageGenerations[tabId] ?: 0L
        val script = "$spotlightScript\nwindow.LaizysinkSpotlight.show(${JSONObject.quote(request.selector)}, ${JSONObject.quote(request.tooltip)});"
        webView.evaluateJavascript(script) { encodedResult ->
            val activeRequest = spotlightRequests[tabId]
            if (activeRequest?.requestId != request.requestId || pageGenerations[tabId] != pageGeneration || !isRevealed(tabId) || request.reported) return@evaluateJavascript
            val status = when (encodedResult?.trim('"')) {
                "shown" -> SpotlightStatus.SHOWN
                "selector-missing" -> SpotlightStatus.SELECTOR_MISSING
                "invalid-selector" -> SpotlightStatus.INVALID_SELECTOR
                else -> SpotlightStatus.ERROR
            }
            request.reported = true
            onSpotlightResult?.invoke(SpotlightResult(tabId, request.requestId, status))
        }
    }

    private fun showTab(webView: WebView) {
        webView.visibility = View.VISIBLE
        webView.alpha = 1f
        visibleUrl = webView.url
        webView.isClickable = true
        webView.isFocusable = true
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun isSafeWebUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val safeScheme = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        return safeScheme && !uri.host.isNullOrBlank() && uri.encodedAuthority?.contains('@') != true
    }

    private fun isSecureWebUrl(value: String): Boolean =
        Uri.parse(value).scheme.equals("https", ignoreCase = true) && isSafeWebUrl(value)

    private fun hostOf(value: String): String? =
        runCatching { Uri.parse(value).host?.lowercase()?.trimEnd('.') }.getOrNull()

    private fun matchesDomain(value: String, domain: String): Boolean {
        if (domain.isBlank()) return false
        val host = hostOf(value) ?: return false
        val expected = domain.lowercase().removePrefix("www.").trimEnd('.')
        return host == expected || host.endsWith(".$expected")
    }

    private data class SpotlightRequest(
        val requestId: Long,
        val selector: String,
        val tooltip: String,
        var reported: Boolean = false,
    )
}

/** WebViews stay mounted at alpha zero; hidden ones cannot take pointer input or accessibility focus. */
@Composable
fun HiddenWebViews(pool: HiddenWebViewPool, visibleTabId: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        pool.tabIds.sortedBy { it == visibleTabId }.forEach { id ->
            val visible = id == visibleTabId
            val webView = pool.tab(id) ?: return@forEach
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (visible) 1f else 0f)
                    .pointerInput(visible) {
                        if (!visible) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                update = { view ->
                    view.alpha = if (visible) 1f else 0f
                    view.isClickable = visible
                    view.isFocusable = visible
                    view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
            )
        }
    }
}
