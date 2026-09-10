package com.example.myapplication.stage9b;

import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Test-APK control plane. Java-only because this provider runs without target-APK libraries. */
public final class Stage9BFixtureControlProvider extends ContentProvider {
    public static final String AUTHORITY = "com.example.myapplication.test.stage9b.fixturecontrol";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceFixtureCaller();
        long identity = Binder.clearCallingIdentity();
        try (ContentProviderClient client = getContext().getContentResolver()
                .acquireContentProviderClient(Stage9BQualificationDocumentsProvider.AUTHORITY)) {
            if (client == null) throw new IllegalStateException("fixture document provider unavailable");
            ContentProvider provider = client.getLocalContentProvider();
            if (!(provider instanceof Stage9BQualificationDocumentsProvider)) {
                throw new IllegalStateException("fixture providers must share the test APK process");
            }
            return ((Stage9BQualificationDocumentsProvider) provider).handleFixtureCall(method, arg, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void enforceFixtureCaller() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("fixture context unavailable");
        int caller = Binder.getCallingUid();
        if (caller == context.getApplicationInfo().uid) return;
        PackageManager packages = context.getPackageManager();
        try {
            ApplicationInfo target = packages.getApplicationInfo("com.sotaware.construct", 0);
            if (caller == target.uid && (target.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    && packages.checkSignatures(caller, context.getApplicationInfo().uid)
                        == PackageManager.SIGNATURE_MATCH) return;
        } catch (PackageManager.NameNotFoundException missingTarget) {
            throw new SecurityException("Stage9B fixture debug target is not installed", missingTarget);
        }
        throw new SecurityException("Stage9B fixture control requires the exact signed debug target UID");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] args, String sortOrder) { throw unsupported(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw unsupported(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw unsupported(); }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] args) { throw unsupported(); }
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("use bounded fixture calls");
    }
}
