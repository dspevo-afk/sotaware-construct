package com.example.myapplication.stage9a;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;

/** Pure platform Java because the separate test APK process does not inherit target Kotlin runtime. */
public final class CameraTestActivity extends Activity {
    public static final String CAMERA_ASSET = "stage7/photos/small_valid_photo.jpg";
    public static final String SAVE = "Save fixture photo";
    public static final String CANCEL = "Cancel fixture photo";
    private static final String KEY_OUTPUT = "camera-output";
    private Uri outputUri;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String restored = state == null ? null : state.getString(KEY_OUTPUT);
        outputUri = restored == null ? getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT) : Uri.parse(restored);
        if (outputUri == null) { setResult(RESULT_CANCELED); finish(); return; }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, (int)(96 * getResources().getDisplayMetrics().density), 24, 24);
        TextView title = new TextView(this);
        title.setText("Synthetic camera fixture");
        content.addView(title);
        Button save = new Button(this); save.setText(SAVE); save.setOnClickListener(v -> complete(true));
        content.addView(save);
        Button cancel = new Button(this); cancel.setText(CANCEL); cancel.setOnClickListener(v -> complete(false));
        content.addView(cancel);
        setContentView(content);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (outputUri != null) state.putString(KEY_OUTPUT, outputUri.toString());
        super.onSaveInstanceState(state);
    }

    private void complete(boolean success) {
        int result = RESULT_CANCELED;
        if (success) {
            try (OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
                if (output == null) throw new java.io.IOException("camera output unavailable");
                try (InputStream input = getAssets().open(CAMERA_ASSET)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                output.flush();
                result = RESULT_OK;
            } catch (Exception error) {
                android.util.Log.e("STAGE9A_CAMERA_FIXTURE", "fixture transfer failed", error);
            }
        }
        setResult(result);
        finish();
    }
}
