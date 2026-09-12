package com.example.myapplication.stage8

import com.example.myapplication.BlueprintViewModel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Supplies the explicit session boundary required by the production reducer
 * to native tests. The key is stable for the lifetime of this reducer, while
 * the predicate keeps the fixture active without introducing a second state
 * owner.
 */
internal fun stage8TestReducer(
    vm: BlueprintViewModel,
    effectSink: (AnnotationReducer.EffectIntent) -> Unit = {}
): AnnotationReducer {
    val key = Any()
    return AnnotationReducer(
        vm = vm,
        effectSink = effectSink,
        sessionKey = key,
        currentSessionKey = { key },
        sessionActivePredicate = { true }
    )
}

/** Wait for the actual decoded PDF and gesture surface, not merely its toolbar. */
internal fun ComposeTestRule.awaitPdfCanvas() {
    waitUntil(30_000L) {
        try {
            onNodeWithTag(com.example.myapplication.PDF_READY_CANVAS_TAG).assertIsDisplayed()
            true
        } catch (_: AssertionError) { false }
    }
    waitForIdle()
}
