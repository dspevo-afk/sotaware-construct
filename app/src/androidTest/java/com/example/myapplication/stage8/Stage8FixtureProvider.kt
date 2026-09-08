package com.example.myapplication.stage8

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread

/** Serves immutable androidTest assets through a real content URI to production loaders. */
class Stage8FixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Stage8 fixtures are read-only" }
        val assetPath = uri.pathSegments.joinToString("/")
        val context = requireNotNull(context)
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "stage8-fixture-$assetPath") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    context.assets.open(assetPath).use { input -> input.copyTo(output) }
                }
            } catch (_: Throwable) {
                pipe[1].close()
            }
        }
        return pipe[0]
    }

    override fun getType(uri: Uri): String = when {
        uri.path?.endsWith(".pdf", ignoreCase = true) == true -> "application/pdf"
        uri.path?.endsWith(".jpg", ignoreCase = true) == true ||
            uri.path?.endsWith(".jpeg", ignoreCase = true) == true -> "image/jpeg"
        else -> "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
