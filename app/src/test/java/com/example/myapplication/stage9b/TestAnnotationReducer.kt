package com.example.myapplication.stage9b

import com.example.myapplication.BlueprintViewModel
import com.example.myapplication.stage8.AnnotationReducer

/** Explicit synthetic admission, used only by tests that do not construct an Android session. */
fun testAnnotationReducer(
    vm: BlueprintViewModel,
    effectSink: (AnnotationReducer.EffectIntent) -> Unit = {},
    sessionKey: Any? = vm,
    currentSessionKey: () -> Any? = { sessionKey },
    sessionActivePredicate: () -> Boolean = { true }
): AnnotationReducer = AnnotationReducer(vm, effectSink, sessionKey, currentSessionKey, sessionActivePredicate)
