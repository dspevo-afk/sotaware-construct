package com.example.myapplication.stage9

import android.util.Log
import com.example.myapplication.BuildConfig

/**
 * The sole production diagnostic boundary.  Callers select an allowlisted
 * event and bounded operational facts; they cannot provide arbitrary text.
 */
enum class DiagnosticEvent {
    OPERATION_STARTED,
    OPERATION_COMPLETED,
    OPERATION_FAILED,
    RESOURCE_CREATED,
    RESOURCE_CLEANUP_FAILED,
    SEARCH_ACTIVITY,
    OCR_ACTIVITY,
    ANNOTATION_ACTIVITY,
    RENDER_ACTIVITY,
    EXPORT_ACTIVITY,
    AUTH_ACTIVITY,
    SYNC_ACTIVITY,
    STORAGE_ACTIVITY,
    INPUT_REJECTED,
    LIMIT_REACHED,
    LEGACY_PATH_IGNORED
}

enum class DiagnosticLevel { DEBUG, WARN, ERROR }

/** Only non-sensitive, bounded operational values may cross the boundary. */
data class DiagnosticFacts(
    val page: Int? = null,
    val count: Int? = null,
    val statusCode: Int? = null
) {
    fun sanitized(): DiagnosticFacts = DiagnosticFacts(
        page = page?.takeIf { it in 0..1_000_000 },
        count = count?.takeIf { it in 0..1_000_000 },
        statusCode = statusCode?.takeIf { it in 100..999 }
    )
}

class DiagnosticBoundary(
    private val enabled: Boolean,
    private val emitter: (DiagnosticLevel, DiagnosticEvent, DiagnosticFacts, String?) -> Unit
) {
    fun emit(
        level: DiagnosticLevel,
        event: DiagnosticEvent,
        facts: () -> DiagnosticFacts = { DiagnosticFacts() },
        error: Throwable? = null
    ) {
        if (!enabled) return
        val safeErrorClass = error?.javaClass?.name
        emitter(level, event, facts().sanitized(), safeErrorClass)
    }
}

object SafeDiagnostics {
    private val boundary = DiagnosticBoundary(BuildConfig.DEBUG) { level, event, facts, errorClass ->
        val suffix = buildString {
            facts.page?.let { append(" page=").append(it) }
            facts.count?.let { append(" count=").append(it) }
            facts.statusCode?.let { append(" status=").append(it) }
            errorClass?.let { append(" exception=").append(it) }
        }
        val message = "event=${event.name}$suffix"
        when (level) {
            DiagnosticLevel.DEBUG -> Log.d("SOTA_DIAGNOSTICS", message)
            DiagnosticLevel.WARN -> Log.w("SOTA_DIAGNOSTICS", message)
            DiagnosticLevel.ERROR -> Log.e("SOTA_DIAGNOSTICS", message)
        }
    }

    fun debug(event: DiagnosticEvent, facts: () -> DiagnosticFacts = { DiagnosticFacts() }) =
        boundary.emit(DiagnosticLevel.DEBUG, event, facts)

    fun warn(event: DiagnosticEvent, facts: () -> DiagnosticFacts = { DiagnosticFacts() }) =
        boundary.emit(DiagnosticLevel.WARN, event, facts)

    fun error(
        event: DiagnosticEvent,
        facts: () -> DiagnosticFacts = { DiagnosticFacts() },
        error: Throwable? = null
    ) = boundary.emit(DiagnosticLevel.ERROR, event, facts, error)
}
