package com.onecaramel.laizysink

import android.os.Bundle
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.onecaramel.laizysink.browser.AndroidHitlController
import com.onecaramel.laizysink.browser.HiddenWebViews
import com.onecaramel.laizysink.security.VaultResult
import kotlinx.coroutines.launch

private val HitlCanvas = Color(0xFF0D0F14)
private val HitlSurface = Color(0xFF161922)
private val HitlBorder = Color(0xFF252A37)
private val HitlAccent = Color(0xFF6366F1)
private val HitlSuccess = Color(0xFF10B981)
private val HitlWarning = Color(0xFFF59E0B)
private val HitlPrimary = Color(0xFFF3F4F6)
private val HitlSecondary = Color(0xFF9CA3AF)

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
            val request = controller.activeRequest
            val visibleTabId = controller.webViewPool.visibleTabId
            BackHandler(enabled = request != null && visibleTabId != null) {
                controller.hideBrowserForLater()
            }
            Box(Modifier.fillMaxSize()) {
                App(platformActions = platformActions)
                HiddenWebViews(
                    pool = controller.webViewPool,
                    visibleTabId = visibleTabId,
                    modifier = Modifier.zIndex(if (visibleTabId == null) -1f else 1f),
                )
                if (request != null) {
                    HitlBrowserChrome(
                        controller = controller,
                        request = request,
                        browserVisible = visibleTabId == request.tabId,
                        modifier = Modifier.zIndex(2f),
                    )
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

@Composable
private fun HitlBrowserChrome(
    controller: AndroidHitlController,
    request: HitlTabRequest,
    browserVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var username by remember(request.taskId, request.tabId) { mutableStateOf("") }
    var password by remember(request.taskId, request.tabId) { mutableStateOf("") }
    var saveToKeychain by remember(request.taskId, request.tabId) { mutableStateOf(false) }
    var actionMessage by remember(request.taskId, request.tabId) { mutableStateOf<String?>(null) }

    val currentDomain = controller.currentDomain(request).orEmpty()
    val expectedOrigin = controller.isExpectedOrigin(request)
    val maskedUsername = controller.maskedUsername(request.domain)
    val pendingReference = controller.pendingVaultReference

    if (!browserVisible) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = HitlCanvas,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Your turn", color = HitlAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(request.domain, color = HitlPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(
                    controller.requestMessage ?: "This sign-in request is still waiting. The task will not resume until you confirm a successful keychain sign-in.",
                    color = HitlSecondary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
                )
                Button(onClick = { controller.resumeBrowser() }) { Text("Resume sign-in page") }
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
            color = HitlSurface.copy(alpha = 0.96f),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, HitlBorder),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("YOUR TURN · SIGN IN", color = HitlAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(request.domain, color = HitlPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    currentDomain.ifBlank { "Waiting for the requested page…" },
                    color = if (expectedOrigin) HitlSecondary else HitlWarning,
                    fontSize = 11.sp,
                )
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
            color = HitlSurface,
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, HitlBorder),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 410.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Sign in to continue", color = HitlPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (request.tooltipText.isNotBlank()) Text(request.tooltipText, color = HitlSecondary, fontSize = 13.sp)

                controller.spotlightMessage?.let { message ->
                    Text(message, color = HitlWarning, fontSize = 12.sp)
                    Button(
                        onClick = { controller.retrySpotlight() },
                        enabled = expectedOrigin,
                    ) { Text("Retry spotlight") }
                }

                if (maskedUsername != null) {
                    Text("Saved account · $maskedUsername", color = HitlSecondary, fontSize = 12.sp)
                } else {
                    Text("No saved account for this domain. You can sign in manually in the page or opt in to save credentials here.", color = HitlSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Username") },
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = saveToKeychain, onCheckedChange = { saveToKeychain = it })
                        Text("Save to encrypted Keychain", color = HitlSecondary, fontSize = 12.sp)
                    }
                    if (saveToKeychain) {
                        Button(
                            onClick = {
                                val secret = password.toCharArray()
                                scope.launch {
                                    try {
                                        val result = controller.saveCredentials(request, username, secret)
                                        actionMessage = when (result) {
                                            VaultResult.SAVED -> "Saved on this device. Use biometric autofill to continue."
                                            VaultResult.CANCELLED -> "Save cancelled. The request remains open."
                                            else -> controller.requestMessage
                                        }
                                        if (result == VaultResult.SAVED) {
                                            username = ""
                                            password = ""
                                            saveToKeychain = false
                                        }
                                    } finally {
                                        secret.fill('\u0000')
                                        password = ""
                                    }
                                }
                            },
                            enabled = username.isNotBlank() && password.isNotEmpty() && expectedOrigin && !controller.isSavingCredentials,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = HitlAccent),
                        ) {
                            if (controller.isSavingCredentials) CircularProgressIndicator() else Text("Save credentials with Biometrics")
                        }
                    }
                }

                if (pendingReference == null) {
                    Button(
                        onClick = {
                            scope.launch {
                                actionMessage = null
                                controller.authenticateAndFill(request)
                                actionMessage = controller.requestMessage
                            }
                        },
                        enabled = maskedUsername != null && expectedOrigin && controller.isBrowserVisible(request) && !controller.isAuthenticating,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = HitlAccent),
                    ) {
                        if (controller.isAuthenticating) CircularProgressIndicator() else Text("Autofill & Sign In with Biometrics")
                    }
                } else {
                    Text(
                        "Credentials are filled. Submit the sign-in form in the page. Laizysink will not submit payment or approve a transaction.",
                        color = HitlSuccess,
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = {
                            if (controller.confirmKeychainCompletion(request)) actionMessage = null
                            else actionMessage = controller.requestMessage
                        },
                        enabled = expectedOrigin && controller.isBrowserVisible(request),
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = HitlSuccess),
                    ) { Text("I signed in · Resume task") }
                }

                actionMessage?.let { Text(it, color = HitlWarning, fontSize = 12.sp) }
                controller.requestMessage?.let { message ->
                    if (message != actionMessage) Text(message, color = HitlSecondary, fontSize = 11.sp)
                }
                Text(
                    "Without a saved Keychain account, manual sign-in keeps this request open. Save credentials locally to resume the task after signing in.",
                    color = HitlSecondary,
                    fontSize = 10.sp,
                )
                Button(
                    onClick = { controller.hideBrowserForLater() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Hide page · keep request open") }
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
