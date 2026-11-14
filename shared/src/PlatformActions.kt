package com.onecaramel.laizysink


data class HitlTabRequest(
    val taskId: String,
    val tabId: String,
    val url: String,
    val targetSelector: String,
    val tooltipText: String,
    val domain: String,
)

/** Native actions required to reveal a HITL page, authenticate locally, or open a cited URL. */
data class AppPlatformActions(
    val onHitlRequested: (HitlTabRequest, (String?) -> Unit) -> Unit = { _, _ -> },
    val authenticateHitl: suspend (HitlTabRequest) -> String? = { null },
    val openExternalUrl: (String) -> Unit = {},
)
