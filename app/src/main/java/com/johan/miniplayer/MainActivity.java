package com.johan.miniplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pantalla de inicio: pide los permisos la primera vez y luego solo abre el
 * reproductor flotante.
 */
public class MainActivity extends Activity {
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        if (ok && prefs.getBoolean("setupDone", false)) {
            startPlayer();
            return;
        }
        render(ok);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void render(boolean overlayOk) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(96), dp(28), dp(40));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1F2140, 0xFF0F1022});
        col.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Mini Player");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Música de YouTube sin anuncios, en una ventanita flotante encima de tus otras apps.");
        sub.setTextColor(0xFFA7A6D6);
        sub.setTextSize(16);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(12), 0, dp(36));
        col.addView(sub);

        if (!overlayOk) {
            col.addView(note("Paso 1: permite que Mini Player se muestre encima de otras apps. "
                    + "Busca \"Mini Player\" en la lista y activa el permiso."));
            col.addView(button("Dar permiso", true, v -> startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))));
        } else {
            col.addView(note("¡Listo! Desde ahora, al tocar el ícono se abre directo el reproductor flotante."));
            col.addView(button("Abrir reproductor", true, v -> {
                prefs.edit().putBoolean("setupDone", true).apply();
                startPlayer();
            }));
        }

        col.addView(note("Recomendado: para que la música no se corte con la pantalla apagada, "
                + "pon Mini Player en \"Sin restricciones\" en la batería."));
        col.addView(button("Ajustes de batería", false, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }));
        setContentView(col);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFD8D7F5);
        t.setTextSize(15);
        t.setPadding(0, dp(18), 0, dp(10));
        return t;
    }

    private Button button(String text, boolean primary, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        GradientDrawable d = primary
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF8D7DFF, 0xFF5D63E6})
                : new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x22FFFFFF, 0x22FFFFFF});
        d.setCornerRadius(dp(16));
        b.setBackground(d);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        b.setLayoutParams(lp);
        return b;
    }

    private void startPlayer() {
        Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.ACT_SHOW);
        startForegroundService(i);
        finish();
    }
}
