package com.example.myapplication.stage9;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Test-APK provider: uses only platform/Java classes because this process does
 * not inherit the instrumented application's Kotlin runtime. PdfRenderer needs
 * a seekable descriptor, so immutable bundled fixtures use a bounded file cache.
 */
public final class QualificationFixtureProvider extends ContentProvider {
    private static final Set<String> ASSETS = new HashSet<>(Arrays.asList(
            "stage7/photos/small_valid_photo.jpg",
            "stage7/photos/high_resolution_phone_photo.jpg",
            "stage7/pdfs/scanned/scanned_text_fixture.pdf",
            "stage7/pdfs/scanned/scanned_image_only.pdf",
            "stage7/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf",
            "stage7/pdfs/blueprint/large_blueprint.pdf"
    ));

    @Override public boolean onCreate() { return true; }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        String asset = String.join("/", uri.getPathSegments());
        if (!"r".equals(mode) || !ASSETS.contains(asset) || getContext() == null) {
            throw new FileNotFoundException("Unknown or non-readable qualification fixture");
        }
        File directory = new File(getContext().getCacheDir(), "qualification-fixtures");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new FileNotFoundException("Fixture cache unavailable");
        }
        File fixture = new File(directory, asset.replace('/', '_'));
        try {
            if (!fixture.isFile()) {
                File partial = File.createTempFile("fixture-", ".partial", directory);
                try {
                    try (InputStream input = getContext().getAssets().open(asset);
                         FileOutputStream output = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        output.getFD().sync();
                    }
                    if (!partial.renameTo(fixture)) throw new IOException("Fixture publish failed");
                } finally {
                    // This exact partial was created above; never sweep a cache directory.
                    if (partial.exists() && !partial.delete()) partial.deleteOnExit();
                }
            }
            return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException failure) {
            FileNotFoundException error = new FileNotFoundException("Fixture could not be prepared");
            error.initCause(failure);
            throw error;
        }
    }

    @Override public String getType(Uri uri) {
        String path = uri.getPath();
        return path != null && path.endsWith(".pdf") ? "application/pdf" : "image/jpeg";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
