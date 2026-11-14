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

/** Keeps attached WebViews alive between research steps so cookies and page state remain available. */
class HiddenWebViewPool(context: Context) {
    private val webContext = context
    private val tabs = linkedMapOf<String, WebView>()
    private val requestedUrls = mutableMapOf<String, String>()
    private val spotlightRequests = mutableMapOf<String, SpotlightRequest>()
    private val mutableTabIds = mutableStateListOf<String>()
    private val spotlightScript by lazy {
        webContext.assets.open("spotlight.js").bufferedReader().use { it.readText() }
    }

    val tabIds: List<String> get() = mutableTabIds

    var visibleTabId: String? by mutableStateOf(null)
        private set

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
                private var firstPageFinished = false

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    view.evaluateJavascript(spotlightScript) {
                        spotlightRequests[tabId]?.let { request ->
                            applySpotlight(tabId, request)
                        }
                    }
                    // A navigation after the first settled page is the signal that the user has
                    // continued through the manual sign-in/HITL step. Re-hide it after the new page
                    // has loaded, while leaving the WebView and its session attached in the pool.
                    if (firstPageFinished && visibleTabId == tabId && pageUrl != requestedUrls[tabId]) {
                        hide(tabId)
                    }
                    firstPageFinished = true
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
        if (!matchesDomain(url, domain) || !open(tabId, url)) return false
        val request = SpotlightRequest(selector, tooltip)
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

    /** Injects only into standard username/password fields; it never submits the form. */
    fun injectCredentials(tabId: String, username: String, password: CharArray, onComplete: (Boolean) -> Unit): Boolean {
        val webView = tabs[tabId] ?: return false
        val script = """(function(){
            const user=document.querySelector('input[autocomplete="username"],input[type="email"],input[name*="user" i]');
            const pass=document.querySelector('input[autocomplete="current-password"],input[type="password"]');
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
        if (visibleTabId == id) visibleTabId = null
    }

    fun close(id: String) {
        if (visibleTabId == id) visibleTabId = null
        spotlightRequests.remove(id)
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

    private fun applySpotlight(tabId: String, request: SpotlightRequest) {
        val script = "window.LaizysinkSpotlight?.show(${JSONObject.quote(request.selector)}, ${JSONObject.quote(request.tooltip)});"
        tabs[tabId]?.evaluateJavascript(script, null)
    }

    private fun showTab(webView: WebView) {
        webView.visibility = View.VISIBLE
        webView.alpha = 1f
        webView.isClickable = true
        webView.isFocusable = true
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun isSafeWebUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
    }

    private fun matchesDomain(value: String, domain: String): Boolean {
        if (domain.isBlank()) return false
        val host = Uri.parse(value).host?.lowercase() ?: return false
        val expected = domain.lowercase().removePrefix("www.")
        return host == expected || host.endsWith(".$expected")
    }

    private data class SpotlightRequest(val selector: String, val tooltip: String)
}

/** WebViews stay mounted at alpha zero; hidden ones cannot take pointer input or accessibility focus. */
@Composable
fun HiddenWebViews(pool: HiddenWebViewPool, visibleTabId: String?) {
    Box(Modifier.fillMaxSize()) {
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
