package com.onecaramel.laizysink.browser

import android.content.Context
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import org.json.JSONObject
import java.util.UUID

/** Keeps WebViews alive between research steps so cookies and page state remain available. */
class HiddenWebViewPool(context: Context) {
    private val appContext = context.applicationContext
    private val tabs = linkedMapOf<String, WebView>()
    private val spotlightScript by lazy { appContext.assets.open("spotlight.js").bufferedReader().use { it.readText() } }

    fun open(url: String): String {
        val id = "tab_${UUID.randomUUID()}"
        tabs[id] = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            alpha = 0f
            visibility = View.INVISIBLE
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    view.evaluateJavascript(spotlightScript, null)
                }
            }
            loadUrl(url)
        }
        return id
    }

    fun tab(id: String): WebView? = tabs[id]

    fun reveal(id: String, selector: String, tooltip: String): WebView? = tabs[id]?.also { webView ->
        webView.visibility = View.VISIBLE
        webView.alpha = 1f
        webView.evaluateJavascript("window.LaizysinkSpotlight?.show(${JSONObject.quote(selector)}, ${JSONObject.quote(tooltip)});", null)
    }

    fun hide(id: String) {
        tabs[id]?.let { webView ->
            webView.evaluateJavascript("window.LaizysinkSpotlight?.clear();", null)
            webView.alpha = 0f
            webView.visibility = View.INVISIBLE
        }
    }

    fun close(id: String) { tabs.remove(id)?.destroy() }
    fun activeTabIds(): Set<String> = tabs.keys
    internal fun allTabs(): Map<String, WebView> = tabs.toMap()
}

@Composable
fun HiddenWebViews(pool: HiddenWebViewPool, visibleTabId: String?) {
    Box(Modifier.fillMaxSize()) {
        pool.allTabs().forEach { (id, webView) ->
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().alpha(if (id == visibleTabId) 1f else 0f))
        }
    }
}


