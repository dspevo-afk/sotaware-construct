package com.example.myapplication.projects;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;

/** Synthetic, read-only tree exercised through the real Android folder picker. */
public final class ProjectFixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.sotaware.construct.test.projects";
    private static final String[] DOC_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    };
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS
        };
        MatrixCursor result = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : columns) {
            switch (column) {
                case DocumentsContract.Root.COLUMN_ROOT_ID:
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID: row.add("project-fixtures"); break;
                case DocumentsContract.Root.COLUMN_TITLE: row.add("SOTAware Project Tests"); break;
                case DocumentsContract.Root.COLUMN_FLAGS: row.add(DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD); break;
                default: row.add(null);
            }
        }
        return result;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        add(result, id); return result;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        switch (parent) {
            case "project-fixtures":
                add(result, "project-alpha"); add(result, "project-beta");
                for (int index = 0; index < 32; index++) add(result, "project-failure-" + index);
                break;
            case "project-alpha": add(result, "electrical"); break;
            case "electrical": add(result, "alpha-pdf"); break;
            case "project-beta": add(result, "beta-pdf"); break;
            default:
                if (!isFailureFolder(parent)) throw new FileNotFoundException("Unknown fixture folder");
                break;
        }
        return result;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        if (parent.equals(child)) return false;
        return parent.equals("project-fixtures") && (child.equals("project-alpha") || child.equals("project-beta") || isFailureFolder(child) || child.equals("electrical") || child.endsWith("-pdf"))
            || parent.equals("project-alpha") && (child.equals("electrical") || child.equals("alpha-pdf"))
            || parent.equals("electrical") && child.equals("alpha-pdf")
            || parent.equals("project-beta") && child.equals("beta-pdf");
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        String name;
        switch (id) {
            case "project-fixtures": name = "Project fixtures"; break;
            case "project-alpha": name = "Project Alpha"; break;
            case "project-beta": name = "Project Beta"; break;
            case "electrical": name = "Electrical"; break;
            case "alpha-pdf": case "beta-pdf": name = "plan.pdf"; break;
            default:
                if (isFailureFolder(id)) {
                    name = "Failure Fixture " + id.substring("project-failure-".length());
                    break;
                }
                throw new FileNotFoundException("Unknown fixture document");
        }
        boolean pdf = id.endsWith("-pdf");
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(name); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add(pdf ? "application/pdf" : DocumentsContract.Document.MIME_TYPE_DIR); break;
                case DocumentsContract.Document.COLUMN_FLAGS: row.add(0); break;
                case DocumentsContract.Document.COLUMN_SIZE: row.add(pdf ? fixture(id).length() : null); break;
                default: row.add(null);
            }
        }
    }
    private static boolean isFailureFolder(String id) {
        if (!id.startsWith("project-failure-")) return false;
        try {
            int index = Integer.parseInt(id.substring("project-failure-".length()));
            return index >= 0 && index < 32;
        } catch (NumberFormatException error) { return false; }
    }
    private File fixture(String id) throws FileNotFoundException {
        if (!id.equals("alpha-pdf") && !id.equals("beta-pdf")) throw new FileNotFoundException("Not a fixture PDF");
        File file = new File(getContext().getCacheDir(), "project-" + id + ".pdf");
        if (!file.exists()) {
            String asset = id.equals("alpha-pdf") ? "stage10/pdfs/a/plan.pdf" : "stage10/pdfs/b/plan.pdf";
            try (InputStream input = getContext().getAssets().open(asset); FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            } catch (IOException error) { throw new FileNotFoundException("Fixture unavailable"); }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!mode.equals("r")) throw new FileNotFoundException("Read-only fixture");
        return ParcelFileDescriptor.open(fixture(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
