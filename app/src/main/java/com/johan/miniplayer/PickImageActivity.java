package com.johan.miniplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Pantalla invisible que abre la galería para elegir la imagen de fondo del reproductor. */
public class PickImageActivity extends Activity {
    private static final int REQ = 11;
    private static final int MAX_SIDE = 900;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Elige una imagen de fondo"), REQ);
        } catch (Exception e) {
            done(false);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ) return;
        Uri uri = data != null ? data.getData() : null;
        if (resultCode != RESULT_OK || uri == null) {
            done(false);
            return;
        }
        new Thread(() -> {
            boolean ok = save(uri);
            runOnUiThread(() -> done(ok));
        }).start();
    }

    private boolean save(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return false;
            int w = bmp.getWidth(), h = bmp.getHeight();
            float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
            File f = new File(getFilesDir(), "fondo.jpg");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void done(boolean changed) {
        Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.ACT_BG);
        i.putExtra("changed", changed);
        try {
            startService(i);
        } catch (Exception ignored) {
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
