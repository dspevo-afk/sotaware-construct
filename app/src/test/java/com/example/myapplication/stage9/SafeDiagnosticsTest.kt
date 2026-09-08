package com.example.myapplication.stage9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeDiagnosticsTest {
    @Test fun disabledBoundaryDoesNotEmitOrEvaluateFacts() {
        var evaluated = false
        var emitted = false
        DiagnosticBoundary(false) { _, _, _, _ -> emitted = true }.emit(
            DiagnosticLevel.DEBUG, DiagnosticEvent.SEARCH_ACTIVITY,
            facts = { evaluated = true; DiagnosticFacts(count = 3) }
        )
        assertFalse(evaluated)
        assertFalse(emitted)
    }

    @Test fun enabledBoundaryEmitsAllowlistedFactsAndExceptionClassOnly() {
        var received: List<Any?> = emptyList()
        DiagnosticBoundary(true) { level, event, facts, errorClass ->
            received = listOf(level, event, facts, errorClass)
        }.emit(
            DiagnosticLevel.ERROR,
            DiagnosticEvent.OPERATION_FAILED,
            facts = { DiagnosticFacts(page = 2, count = 4) },
            error = IllegalStateException("account=secret uri=/private/doc.pdf", RuntimeException("nested"))
        )
        assertEquals(DiagnosticLevel.ERROR, received[0])
        assertEquals(DiagnosticEvent.OPERATION_FAILED, received[1])
        assertEquals(DiagnosticFacts(page = 2, count = 4), received[2])
        assertEquals(IllegalStateException::class.java.name, received[3])
    }

    @Test fun factsAreBoundedAndSensitiveValuesHaveNoAPIRepresentation() {
        val safe = DiagnosticFacts(page = -1, count = 2_000_000, statusCode = 700).sanitized()
        assertEquals(DiagnosticFacts(statusCode = 700), safe)
        val api = DiagnosticBoundary::class.java.declaredMethods.map { it.name }
        assertTrue(api.contains("emit"))
        assertFalse(api.any { it.contains("message", ignoreCase = true) || it.contains("payload", ignoreCase = true) })
    }

    @Test fun geometryAndExportDetailsCannotEnterOutput() {
        var output = ""
        DiagnosticBoundary(true) { _, event, facts, errorClass ->
            output = "event=$event facts=$facts error=$errorClass"
        }.emit(
            DiagnosticLevel.DEBUG,
            DiagnosticEvent.EXPORT_ACTIVITY,
            facts = { DiagnosticFacts(page = 3, count = 1) }
        )
        assertFalse(output.contains("width", ignoreCase = true))
        assertFalse(output.contains("height", ignoreCase = true))
        assertFalse(output.contains("x=", ignoreCase = true))
        assertFalse(output.contains("y=", ignoreCase = true))
        assertFalse(output.contains("geometry", ignoreCase = true))
    }

    @Test fun directAndroidLogCallsOnlyExistInAdapter() {
        val root = locateProductionRoot()
        val production = root.walkTopDown().filter { it.extension == "kt" && it.isFile }.toList()
        val offenders = production.filter { it.name != "SafeDiagnostics.kt" &&
            Regex("android\\.util\\.Log|\\bLog\\.(d|i|w|e|v)\\s*\\(").containsMatchIn(it.readText()) }
        assertTrue("raw Android Log calls: $offenders", offenders.isEmpty())
        assertTrue(File(root, "stage9/SafeDiagnostics.kt").readText().contains("BuildConfig.DEBUG"))
    }

    private fun locateProductionRoot(): File {
        val candidates = sequenceOf(
            File("src/main/java/com/example/myapplication"),
            File("app/src/main/java/com/example/myapplication")
        )
        return candidates.first { it.isDirectory }
    }
}
