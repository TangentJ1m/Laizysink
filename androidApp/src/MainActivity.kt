package com.onecaramel.laizysink

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.onecaramel.laizysink.browser.AndroidHitlController
import com.onecaramel.laizysink.browser.HiddenWebViews

class MainActivity : ComponentActivity() {
    private lateinit var hitlController: AndroidHitlController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hitlController = AndroidHitlController(this)
        setContent {
            val controller = hitlController
            val platformActions = remember(controller) {
                AppPlatformActions(
                    onHitlRequested = controller::reveal,
                    authenticateHitl = controller::authenticateAndFill,
                    openExternalUrl = controller::openExternalUrl,
                )
            }
            val visibleTabId = controller.webViewPool.visibleTabId
            BackHandler(enabled = visibleTabId != null) { controller.returnToApp() }
            Box(Modifier.fillMaxSize()) {
                App(platformActions = platformActions)
                HiddenWebViews(
                    pool = controller.webViewPool,
                    visibleTabId = visibleTabId,
                    modifier = Modifier.zIndex(if (visibleTabId == null) -1f else 1f),
                )
                if (visibleTabId != null) {
                    val expectedDomain = controller.activeRequest?.domain.orEmpty()
                    val currentHost = controller.webViewPool.visibleUrl?.let { Uri.parse(it).host }.orEmpty()
                    val maskedUsername = controller.maskedUsername(expectedDomain)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(10.dp)
                            .fillMaxWidth()
                            .zIndex(2f),
                        color = Color(0xF2161922),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Human approval • $expectedDomain", color = Color(0xFFF3F4F6), fontWeight = FontWeight.SemiBold)
                                Text("Current page • ${currentHost.ifBlank { expectedDomain }}", color = Color(0xFF9CA3AF))
                                if (maskedUsername != null) Text("Saved account • $maskedUsername", color = Color(0xFF9CA3AF))
                            }
                            Button(onClick = controller::returnToApp) { Text("Return to Laizysink") }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        if (::hitlController.isInitialized) hitlController.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::hitlController.isInitialized) hitlController.resume()
    }

    override fun onDestroy() {
        if (::hitlController.isInitialized) hitlController.close()
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
