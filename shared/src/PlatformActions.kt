package com.onecaramel.laizysink

import com.onecaramel.laizysink.state.HitlTabRequest

/** Native actions required to reveal a HITL page, authenticate locally, or open a cited URL. */
data class AppPlatformActions(
    val onHitlRequested: (HitlTabRequest, (String?) -> Unit) -> Unit = { _, _ -> },
    val authenticateHitl: suspend (HitlTabRequest) -> String? = { null },
    val openExternalUrl: (String) -> Unit = {},
)
