package com.example.myapplication.stage3

/**
 * Handles the caller/UI consequence of selecting the same document while its
 * existing session remains active. The coordinator intentionally treats that
 * selection as a state-machine no-op; the caller may restore navigation only
 * after independently proving that the returned session is current and ready.
 */
internal fun restoreAlreadyActiveSession(
    result: SwitchResult,
    isCurrent: (DocumentSessionToken) -> Boolean,
    isReady: (DocumentSessionToken) -> Boolean,
    restoreBrowser: (DocumentSession) -> Unit
): Boolean {
    val alreadyActive = result as? SwitchResult.AlreadyActive ?: return false
    val session = alreadyActive.session
    if (!isCurrent(session.token) || !isReady(session.token)) return false
    restoreBrowser(session)
    return true
}
