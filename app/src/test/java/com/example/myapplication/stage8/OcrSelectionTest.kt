package com.example.myapplication.stage8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrSelectionTest {
    @Test fun loadedSelectionRequiresSameSessionAndPage() {
        assertEquals(2, OcrSelection.boxIndexOrNull("A", "A", 4, 4, 2, 3))
        assertNull(OcrSelection.boxIndexOrNull("A", "B", 4, 4, 2, 3))
        assertNull(OcrSelection.boxIndexOrNull("A", "A", 4, 5, 2, 3))
        assertNull(OcrSelection.boxIndexOrNull("A", "A", 4, 4, 3, 3))
    }

    @Test fun cacheMissCompletionAdmitsSelectionOnlyForCapturedDocumentAndPage() {
        assertEquals(
            OcrSelection.LoadedSelection(1),
            OcrSelection.admitLoadedSelection("doc-a", "doc-a", 2, 2, 1, 3)
        )
        assertNull(OcrSelection.admitLoadedSelection("doc-a", "doc-b", 2, 2, 1, 3))
        assertNull(OcrSelection.admitLoadedSelection("doc-a", "doc-a", 2, 3, 1, 3))
        assertNull(OcrSelection.admitLoadedSelection("doc-a", "doc-a", 2, 2, -1, 3))
    }
}
