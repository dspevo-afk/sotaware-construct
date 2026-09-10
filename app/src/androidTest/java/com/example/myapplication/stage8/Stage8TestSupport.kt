package com.example.myapplication.stage8

import com.example.myapplication.BlueprintViewModel

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
