package com.example.myapplication.stage5

import com.example.myapplication.stage4.FileSyncMetadataStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Real file-backed metadata/outbox logic with explicit JVM-only payload I/O.
 * Windows lacks SecureDirectoryStream. Android instrumentation uses the secure
 * production default instead of this test adapter.
 */
internal fun testFileSyncMetadataStore(
    root: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): FileSyncMetadataStore = FileSyncMetadataStore(root, dispatcher, TestPhotoPathOperationsFactory)
