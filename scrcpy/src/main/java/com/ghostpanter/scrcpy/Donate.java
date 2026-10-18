package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;

// Shows WeChat / Alipay donation QR codes. Long-press saves to Photos.
public final class Donate extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_donate);
        Ui.padForSystemBars(findViewById(R.id.root));
        bindSave(R.id.donate_wechat, "donate_wechat.png");
        bindSave(R.id.donate_alipay, "donate_alipay.png");
    }

    private void bindSave(int viewId, String fileName) {
        ImageView image = findViewById(viewId);
        image.setOnLongClickListener(v -> {
            saveToGallery(image, fileName);
            return true;
        });
    }

    private void saveToGallery(ImageView image, String fileName) {
        Drawable drawable = image.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            Toast.makeText(this, getString(R.string.donate_save_failed, "no bitmap"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.donate_save_failed, "empty"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        // RELATIVE_PATH is API 29+; on API 28 MediaStore still accepts insert
        // into Images without it (WRITE_EXTERNAL_STORAGE was removed from the
        // manifest, so save may fail on API 28 — toast reports that).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/scrcpy-android");
        }
        Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Toast.makeText(this, getString(R.string.donate_save_failed, "insert"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("compress failed");
            }
            Toast.makeText(this, R.string.donate_saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(e, "donate: save %s failed", fileName);
            Toast.makeText(this, getString(R.string.donate_save_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }
}
