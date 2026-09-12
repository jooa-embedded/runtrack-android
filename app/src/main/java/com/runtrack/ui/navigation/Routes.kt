package com.runtrack.ui.navigation

/** Type-safe-ish route constants for Navigation Compose. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DEVICE = "device"
    const val LIVE_RUN = "live_run"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val FATIGUE = "fatigue"

    const val ARG_SESSION_ID = "sessionId"

    private const val DETAIL_BASE = "detail"
    const val DETAIL = "$DETAIL_BASE/{$ARG_SESSION_ID}"
    fun detail(sessionId: Long) = "$DETAIL_BASE/$sessionId"

    private const val COMPARISON_BASE = "comparison"
    const val COMPARISON = "$COMPARISON_BASE/{$ARG_SESSION_ID}"
    fun comparison(sessionId: Long) = "$COMPARISON_BASE/$sessionId"
}