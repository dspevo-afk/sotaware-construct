package com.example.myapplication.stage8

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One interruptible momentum owner; each frame is admitted to its captured viewer. */
internal class ViewerFling(private val scope: CoroutineScope) {
    private var job: Job? = null
    fun stop() { job?.cancel(); job = null }

    fun start(velocity: Offset, isCurrent: () -> Boolean, panBy: (Offset) -> Boolean) {
        stop()
        if (!velocity.x.isFinite() || !velocity.y.isFinite() || velocity.getDistance() < 100f) return
        job = scope.launch {
            var previous = 0f
            AnimationState(initialValue = 0f, initialVelocity = 1f).animateDecay(
                exponentialDecay(frictionMultiplier = 1.8f, absVelocityThreshold = 0.02f)
            ) {
                val delta = value - previous
                previous = value
                // The first animation frame has zero elapsed time. It is not a
                // boundary hit and must not cancel the momentum before it starts.
                if (!isCurrent() || (delta > 0f && !panBy(velocity * delta))) cancelAnimation()
            }
        }
    }
}
