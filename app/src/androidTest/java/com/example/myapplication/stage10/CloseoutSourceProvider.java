package com.example.myapplication.stage10;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Synthetic test-APK provider, deliberately independent of target-APK Kotlin libraries. */
public final class CloseoutSourceProvider extends ContentProvider {
    private String revision = "a";
    private boolean changeAfterOpen;
    private int opens;
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "a": case "b": revision = method; changeAfterOpen = false; opens = 0; break;
            case "a-then-b": revision = "a"; changeAfterOpen = true; opens = 0; break;
            case "state": break;
            default: throw new IllegalArgumentException("unsupported fixture command");
        }
        Bundle result = new Bundle();
        result.putInt("opens", opens); result.putString("revision", revision);
        return result;
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only fixture");
        String selected = revision;
        File file = new File(getContext().getCacheDir(), "closeout-source-" + selected + ".pdf");
        if (!file.exists()) {
            try (InputStream input = getContext().getAssets().open("stage10/pdfs/" + selected + "/plan.pdf");
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (IOException failure) {
                FileNotFoundException wrapped = new FileNotFoundException("fixture could not be copied");
                wrapped.initCause(failure); throw wrapped;
            }
        }
        opens++;
        if (changeAfterOpen) revision = "b";
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{"plan.pdf"}); return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
