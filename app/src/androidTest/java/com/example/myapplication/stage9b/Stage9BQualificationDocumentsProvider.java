package com.example.myapplication.stage9b;

import android.Manifest;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;

/**
 * Test-only provider for the native Stage 9B SAF workflow.  Its files live in
 * the instrumentation package's files directory, which is separate from the
 * target application's data directory.  The host test manifest must register
 * this class with the authority in [AUTHORITY].
 */
public final class Stage9BQualificationDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY =
            "com.example.myapplication.test.stage9b.documents";
    public static final String ROOT_ID = "stage9b-root";
    public static final String PDF_ID = "stage9b-pdf";
    public static final String BUNDLE_ID = "stage9b-bundle";
    public static final String RETIRED_ID = "stage9b-retired";
    public static final String SOURCE_NAME = "stage9b-source.pdf";
    public static final String EXPECTED_NAME = "stage9b-expected.properties";
    public static final String RETIRED_NAME = "stage9b-retired-format.sotaware";
    public static final String CALL_PREPARE = "stage9b.prepare";
    public static final String CALL_CLEAR = "stage9b.clear";
    public static final String CALL_LIST = "stage9b.list";
    public static final String CALL_HASH = "stage9b.hash";
    public static final String CALL_OPEN = "stage9b.open";
    public static final String CALL_WRITE_EXPECTED = "stage9b.writeExpected";
    public static final String CALL_READ_EXPECTED = "stage9b.readExpected";
    public static final String CALL_WRITE_RETIRED = "stage9b.writeRetired";
    public static final String CALL_READ_RETIRED = "stage9b.readRetired";
    private static final String KEY_NAMES = "names";
    private static final String KEY_BYTES = "bytes";
    private static final String KEY_FILE_DESCRIPTOR = "fileDescriptor";
    private static final String KEY_SIZE = "size";
    private static final String KEY_SHA256 = "sha256";
    private static final long MAX_CONTROL_BYTES = 512L * 1024L;
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L;
    private static final String PDF_ASSET =
            "stage7/pdfs/cropped-rotated/embedded_text_crop_offset_rotate.pdf";
    private File rootDirectory;

    private static final class PendingWrite {
        int openCount;
        boolean failed;
    }
    private final java.util.Map<String, PendingWrite> pendingWrites = new java.util.HashMap<>();

    private File pendingWriteMarker(File file) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    file.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new File(rootDirectory, ".write-" + Base64.getUrlEncoder()
                    .withoutPadding().encodeToString(hash) + ".partial");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private PendingWrite beginWrite(File file) throws IOException {
        // Every writer must belong to this process's still-pending create.
        // A marker without that owner survives restart as UNKNOWN, not a new
        // opportunity to overwrite or certify an interrupted artifact.
        PendingWrite state = pendingWrites.get(file.getName());
        if (state == null || state.failed || !file.isFile() || !pendingWriteMarker(file).isFile()) {
            throw new IOException("Stage9B writer has no current pending creation owner");
        }
        state.openCount++;
        return state;
    }

    private boolean completedArtifact(File file) {
        return file.isFile() && !pendingWriteMarker(file).exists() &&
                !pendingWrites.containsKey(file.getName());
    }

    private void syncFixtureDirectory() throws IOException {
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = android.system.Os.open(rootDirectory.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_CLOEXEC, 0);
            if (!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode)) {
                throw new IOException("fixture sync target is not a directory");
            }
            android.system.Os.fsync(descriptor);
        } catch (android.system.ErrnoException error) {
            throw new IOException("could not sync fixture directory", error);
        } finally {
            if (descriptor != null) try { android.system.Os.close(descriptor); }
            catch (android.system.ErrnoException error) { throw new IOException("could not close fixture directory", error); }
        }
    }

    private synchronized void finishWrite(File file, PendingWrite state, IOException error) {
        synchronized (pendingWrites) {
            if (pendingWrites.get(file.getName()) != state) return;
            state.failed |= error != null;
            state.openCount--;
            if (state.openCount == 0 && !state.failed) {
                // A nonempty file is not completion: only the real writer's
                // final descriptor close permits the oracle to inspect it.
                if (pendingWriteMarker(file).delete()) pendingWrites.remove(file.getName());
            }
        }
    }


    public static android.net.Uri documentUri(String documentId) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, documentId);
    }

    private static File providerDirectory(android.content.Context context) {
        return new File(context.getFilesDir(), "stage9b-documents-provider");
    }

    private static File artifactFile(android.content.Context context) {
        return new File(providerDirectory(context), "stage9b-export.sotaware");
    }

    private static File retiredArtifactFile(android.content.Context context) {
        return new File(providerDirectory(context), RETIRED_NAME);
    }

    @Override
    public boolean onCreate() {
        android.content.Context context = getContext();
        if (context == null) return false;
        rootDirectory = providerDirectory(context);
        if (!rootDirectory.isDirectory() && !rootDirectory.mkdirs()) return false;
        try {
            ensureSeeded(new File(rootDirectory, SOURCE_NAME), PDF_ASSET);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    @Override
    public synchronized Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES
        } : projection;
        MatrixCursor result = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : columns) {
            if (DocumentsContract.Root.COLUMN_ROOT_ID.equals(column)) row.add(ROOT_ID);
            else if (DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(column)) row.add(ROOT_ID);
            else if (DocumentsContract.Root.COLUMN_TITLE.equals(column)) row.add("SOTAware Stage 9B");
            else if (DocumentsContract.Root.COLUMN_FLAGS.equals(column)) {
                row.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
            } else if (DocumentsContract.Root.COLUMN_MIME_TYPES.equals(column)) {
                row.add("application/pdf\napplication/zip\napplication/octet-stream");
            } else row.add(null);
        }
        return result;
    }

    @Override
    public synchronized Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        return documentCursor(documentId, projection, true);
    }

    @Override
    public synchronized Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException("unknown Stage9B parent");
        String[] columns = projection == null ? defaultDocumentColumns() : projection;
        MatrixCursor result = new MatrixCursor(columns);
        addDocumentRow(result, columns, PDF_ID, SOURCE_NAME, SOURCE_NAME, "application/pdf", false);
        File bundle = artifactFile(providerContext());
        if (completedArtifact(bundle)) addDocumentRow(result, columns, dynamicId(bundle.getName()), bundle.getName(), bundle.getName(), "application/zip", false);
        File retired = retiredArtifactFile(providerContext());
        if (completedArtifact(retired)) addDocumentRow(result, columns, dynamicId(retired.getName()), retired.getName(), retired.getName(), "application/zip", false);
        File[] extras = rootDirectory.listFiles();
        if (extras != null) {
            for (File file : extras) {
                if (!completedArtifact(file) || isInternalControlFile(file.getName()) ||
                        file.getName().equals(SOURCE_NAME) || file.getName().equals(bundle.getName()) ||
                        file.getName().equals(retired.getName())) continue;
                addDocumentRow(result, columns, dynamicId(file.getName()), file.getName(), file.getName(), mimeFor(file.getName()), false);
            }
        }
        return result;
    }

    /**
     * Test-only fixture controls.  The manifest's MANAGE_DOCUMENTS permission
     * protects this entry point; callers additionally adopt that permission
     * only around each synchronous management operation.  No production app
     * path depends on these methods.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // Framework DocumentsContract calls (including createDocument) must
        // retain DocumentsProvider's own URI-grant/permission dispatch.
        if (method == null || !method.startsWith("stage9b.")) {
            return super.call(method, arg, extras);
        }
        android.content.Context context = providerContext();
        context.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_DOCUMENTS,
                "Stage9B fixture management requires MANAGE_DOCUMENTS");
        return handleFixtureCall(method, arg, extras);
    }

    /** Same-APK entry used only after the control provider verifies its caller. */
    synchronized Bundle handleFixtureCall(String method, String arg, Bundle extras) {
        try {
            ensureSeeded(new File(rootDirectory, SOURCE_NAME), PDF_ASSET);
            if (CALL_PREPARE.equals(method)) {
                Bundle result = new Bundle();
                try (Cursor roots = queryRoots(null)) {
                    result.putBoolean("rootAvailable", roots.moveToFirst());
                }
                return result;
            }
            if (CALL_CLEAR.equals(method)) {
                clearSyntheticFiles();
                return new Bundle();
            }
            if (CALL_LIST.equals(method)) {
                ArrayList<String> names = new ArrayList<>();
                File[] files = rootDirectory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (completedArtifact(file) && !file.getName().endsWith(".partial")) {
                            names.add(file.getName());
                        }
                    }
                }
                Collections.sort(names);
                Bundle result = new Bundle();
                result.putStringArrayList(KEY_NAMES, names);
                return result;
            }
            if (CALL_HASH.equals(method)) {
                File file = controlFile(arg);
                Bundle result = new Bundle();
                result.putLong(KEY_SIZE, file.length());
                result.putString(KEY_SHA256, sha256(file));
                return result;
            }
            if (CALL_OPEN.equals(method)) {
                File file = controlFile(arg);
                if (file.length() > MAX_ARTIFACT_BYTES) {
                    throw new IllegalArgumentException("Stage9B artifact exceeds the bounded copy limit");
                }
                Bundle result = new Bundle();
                result.putParcelable(
                        KEY_FILE_DESCRIPTOR,
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY));
                result.putLong(KEY_SIZE, file.length());
                result.putString(KEY_SHA256, sha256(file));
                return result;
            }
            if (CALL_WRITE_EXPECTED.equals(method)) {
                writeControlFile(EXPECTED_NAME, bytesFrom(extras));
                return new Bundle();
            }
            if (CALL_READ_EXPECTED.equals(method)) {
                return bytesResult(readControlFile(EXPECTED_NAME));
            }
            if (CALL_WRITE_RETIRED.equals(method)) {
                writeControlFile(RETIRED_NAME, bytesFrom(extras));
                return new Bundle();
            }
            if (CALL_READ_RETIRED.equals(method)) {
                return bytesResult(readControlFile(RETIRED_NAME));
            }
            throw new IllegalArgumentException("unknown Stage9B provider control");
        } catch (IOException failure) {
            throw new IllegalStateException("Stage9B provider fixture operation failed", failure);
        }
    }

    @Override
    public synchronized ParcelFileDescriptor openDocument(String documentId, String mode,
                                             CancellationSignal signal) throws FileNotFoundException {
        File file = resolveFile(documentId);
        if (file == null) throw new FileNotFoundException("unknown Stage9B document");
        if (signal != null && signal.isCanceled()) throw new FileNotFoundException("document open cancelled");
        if (mode.contains("w") || mode.contains("a")) {
            int flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY;
            if (!mode.contains("a")) flags |= ParcelFileDescriptor.MODE_TRUNCATE;
            final PendingWrite write;
            try {
                write = beginWrite(file);
            } catch (IOException failure) {
                FileNotFoundException error = new FileNotFoundException("could not track Stage9B writer");
                error.initCause(failure);
                throw error;
            }
            try {
                return ParcelFileDescriptor.open(file, flags,
                        new android.os.Handler(android.os.Looper.getMainLooper()),
                        error -> finishWrite(file, write, error));
            } catch (IOException failure) {
                finishWrite(file, write, failure);
                FileNotFoundException error = new FileNotFoundException("could not open Stage9B writer");
                error.initCause(failure);
                throw error;
            }
        }
        if (!completedArtifact(file)) {
            throw new FileNotFoundException("Stage9B document writer has not completed successfully");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public synchronized String createDocument(String parentDocumentId, String mimeType,
                                 String displayName) throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException("unknown Stage9B parent");
        final String safeName;
        try {
            safeName = safeName(displayName);
        } catch (IllegalArgumentException invalidName) {
            FileNotFoundException failure = new FileNotFoundException("unsafe Stage9B document name");
            failure.initCause(invalidName);
            throw failure;
        }
        File file = new File(rootDirectory, safeName);
        if (file.exists()) throw new FileNotFoundException("Stage9B document already exists");
        try {
            File marker = pendingWriteMarker(file);
            if (!marker.createNewFile()) throw new IOException("unresolved Stage9B creation marker exists");
            // Publish the incomplete marker durably before any output file can
            // exist. A restarted provider never adopts this unknown generation.
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.write(java.util.UUID.randomUUID().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            syncFixtureDirectory();
            if (!file.createNewFile()) throw new IOException("could not create Stage9B document");
            pendingWrites.put(file.getName(), new PendingWrite());
        } catch (IOException failure) {
            FileNotFoundException error = new FileNotFoundException("could not create Stage9B document");
            error.initCause(failure);
            throw error;
        }
        return dynamicId(safeName);
    }

    @Override
    public synchronized void deleteDocument(String documentId) throws FileNotFoundException {
        if (PDF_ID.equals(documentId) || ROOT_ID.equals(documentId)) throw new FileNotFoundException("protected Stage9B document");
        File file = resolveFile(documentId);
        if (file == null) throw new FileNotFoundException("unknown Stage9B document");
        PendingWrite writer = pendingWrites.get(file.getName());
        if (writer != null && writer.openCount > 0) {
            throw new FileNotFoundException("cannot delete a Stage9B document with an active writer");
        }
        if (!file.delete()) throw new FileNotFoundException("Stage9B document delete failed");
        synchronized (pendingWrites) {
            pendingWrites.remove(file.getName());
            File marker = pendingWriteMarker(file);
            if (marker.exists() && !marker.delete()) {
                throw new FileNotFoundException("Stage9B document marker delete failed");
            }
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) return "vnd.android.document/directory";
        File file = resolveFile(documentId);
        if (file == null) throw new FileNotFoundException("unknown Stage9B document");
        return mimeFor(file.getName());
    }

    private Cursor documentCursor(String id, String[] projection, boolean includeRoot) throws FileNotFoundException {
        String[] columns = projection == null ? defaultDocumentColumns() : projection;
        MatrixCursor result = new MatrixCursor(columns);
        if (ROOT_ID.equals(id)) {
            addDocumentRow(result, columns, ROOT_ID, ROOT_ID, "SOTAware Stage 9B", "vnd.android.document/directory", true);
            return result;
        }
        File file = resolveFile(id);
        if (file == null || !file.exists()) throw new FileNotFoundException("unknown Stage9B document");
        addDocumentRow(result, columns, id, file.getName(), file.getName(), mimeFor(file.getName()), false);
        return result;
    }

    private File resolveFile(String id) {
        if (PDF_ID.equals(id)) return new File(rootDirectory, SOURCE_NAME);
        if (BUNDLE_ID.equals(id)) return artifactFile(providerContext());
        if (RETIRED_ID.equals(id)) return retiredArtifactFile(providerContext());
        if (id != null && id.startsWith("file:")) {
            try {
                String name = new String(Base64.getUrlDecoder().decode(id.substring(5)), java.nio.charset.StandardCharsets.UTF_8);
                String safe = safeName(name);
                File file = new File(rootDirectory, safe);
                return file.getParentFile().equals(rootDirectory) ? file : null;
            } catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    private String dynamicId(String name) {
        return "file:" + Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void addDocumentRow(MatrixCursor result, String[] columns, String id, String name,
                                String title, String mime, boolean directory) {
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : columns) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) row.add(id);
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) row.add(name);
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) row.add(mime);
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) {
                File file = resolveFile(id);
                row.add(directory || file == null ? null : file.length());
            } else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) {
                File file = resolveFile(id);
                row.add(directory || file == null ? null : file.lastModified());
            } else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) {
                row.add(directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE :
                        (mime.equals("application/pdf") ? 0 : DocumentsContract.Document.FLAG_SUPPORTS_DELETE));
            } else if (DocumentsContract.Document.COLUMN_ICON.equals(column)) row.add(null);
            else if (DocumentsContract.Document.COLUMN_SUMMARY.equals(column)) row.add(title);
            else row.add(null);
        }
    }

    private static String[] defaultDocumentColumns() {
        return new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_ICON,
                DocumentsContract.Document.COLUMN_SUMMARY
        };
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".sotaware") || lower.endsWith(".zip")) return "application/zip";
        String extension = MimeTypeMap.getFileExtensionFromUrl(name);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String safeName(String name) {
        if (TextUtils.isEmpty(name) || name.length() > 255 || name.equals(".") || name.equals("..") ||
                name.contains("/") || name.contains("\\") || name.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("unsafe Stage9B document name");
        }
        return name;
    }

    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null || rootDirectory == null) throw new IllegalStateException("provider is not initialized");
        return context;
    }

    private void ensureSeeded(File destination, String assetPath) throws IOException {
        if (destination.isFile() && destination.length() > 0) return;
        File partial = new File(destination.getParentFile(), destination.getName() + ".partial");
        try (InputStream input = providerContext().getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
            if (!partial.renameTo(destination)) throw new IOException("Stage9B fixture publish failed");
        } finally {
            if (partial.exists()) partial.delete();
        }
    }

    private synchronized void clearSyntheticFiles() throws IOException {
        for (PendingWrite writer : pendingWrites.values()) {
            if (writer.openCount > 0) throw new IOException("cannot clear fixtures with an active writer");
        }
        // The same provider monitor serializes create/open/read/delete/clear.
        // Old callbacks can never complete a newly-created same-name file.
        synchronized (pendingWrites) { pendingWrites.clear(); }
        File[] files = rootDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !SOURCE_NAME.equals(file.getName()) && !file.delete()) {
                throw new IOException("could not clear Stage9B synthetic file");
            }
        }
    }

    private File controlFile(String name) throws IOException {
        if (TextUtils.isEmpty(name)) throw new IOException("missing Stage9B file name");
        final String safe;
        try {
            safe = safeName(name);
        } catch (IllegalArgumentException invalidName) {
            IOException failure = new IOException("unsafe Stage9B file name");
            failure.initCause(invalidName);
            throw failure;
        }
        File root = rootDirectory.getCanonicalFile();
        File candidate = new File(root, safe).getCanonicalFile();
        if (!root.equals(candidate.getParentFile()) || !candidate.isFile()) {
            throw new FileNotFoundException("unknown Stage9B provider file");
        }
        if (!completedArtifact(candidate)) {
            throw new IOException("Stage9B document writer has not completed successfully");
        }
        return candidate;
    }

    private byte[] bytesFrom(Bundle extras) {
        byte[] bytes = extras == null ? null : extras.getByteArray(KEY_BYTES);
        if (bytes == null) throw new IllegalArgumentException("missing Stage9B control bytes");
        if (bytes.length > MAX_CONTROL_BYTES) {
            throw new IllegalArgumentException("Stage9B control bytes exceed the bounded limit");
        }
        return bytes;
    }

    private byte[] readControlFile(String name) throws IOException {
        File file = controlFile(name);
        if (file.length() > MAX_CONTROL_BYTES) {
            throw new IOException("Stage9B control file exceeds the bounded limit");
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("truncated Stage9B control file");
                if (count == 0) continue;
                offset += count;
            }
            return bytes;
        }
    }

    private Bundle bytesResult(byte[] bytes) {
        Bundle result = new Bundle();
        result.putByteArray(KEY_BYTES, bytes);
        return result;
    }

    private void writeControlFile(String name, byte[] bytes) throws IOException {
        File target = new File(rootDirectory, name).getCanonicalFile();
        File root = rootDirectory.getCanonicalFile();
        if (!root.equals(target.getParentFile())) throw new IOException("Stage9B control path escaped root");
        File partial = new File(rootDirectory, name + ".partial");
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                output.write(bytes);
                output.getFD().sync();
            }
            try {
                Files.move(
                        partial.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        partial.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException unsupported) {
                Files.move(
                        partial.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }

    private static boolean isInternalControlFile(String name) {
        return EXPECTED_NAME.equals(name) || name.endsWith(".partial");
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                total += count;
                if (total > MAX_ARTIFACT_BYTES) {
                    throw new IOException("Stage9B artifact exceeds the bounded hash limit");
                }
                digest.update(buffer, 0, count);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }
}
