package com.johan.miniplayer;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio que mantiene la ventana flotante sobre las demás apps y reproduce
 * YouTube (con bloqueo de anuncios) en segundo plano, con controles en la
 * notificación y en la pantalla de bloqueo.
 */
public class PlayerService extends Service {
    static final String ACT_SHOW = "com.johan.miniplayer.SHOW";
    static final String ACT_TOGGLE = "com.johan.miniplayer.TOGGLE";
    static final String ACT_NEXT = "com.johan.miniplayer.NEXT";
    static final String ACT_PREV = "com.johan.miniplayer.PREV";
    static final String ACT_STOP = "com.johan.miniplayer.STOP";

    private static final String CHANNEL = "player";
    private static final int NOTIF_ID = 7;
    private static final int CARD_W = 368, CARD_H = 328, BUBBLE = 76; // en dp

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Random rnd = new Random();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private DragLayout root;
    private WebView ui, yt;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;
    private float density;

    private JSONArray queue = new JSONArray();
    private int qIndex = -1;
    private boolean wantPlay, shuffle, repeat, bubble;
    private String lastEndedId;
    private String curId;
    private String curTitle = "", curChannel = "";
    private boolean curPaused = true, curAd;
    private double curPos, curDur;
    private String notifKey = "";
    private Bitmap art;
    private String artId;

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            poll();
            main.postDelayed(this, 500);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getBoolean("repeat", false);
        density = getResources().getDisplayMetrics().density;

        createChannel();
        setupSession();
        startFg();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniPlayer:play");
        wakeLock.setReferenceCounted(false);

        if (Settings.canDrawOverlays(this)) {
            setupOverlay();
            main.postDelayed(poller, 1000);
        } else {
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a != null && yt != null) {
            switch (a) {
                case ACT_TOGGLE: setPlaying(curPaused); break;
                case ACT_NEXT: next(); break;
                case ACT_PREV: prev(); break;
                case ACT_STOP: stopSelf(); break;
                case ACT_SHOW: setBubble(false); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (session != null) session.release();
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        if (yt != null) yt.destroy();
        if (ui != null) ui.destroy();
        yt = null;
        ui = null;
        io.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ventana flotante

    /** Contenedor que permite arrastrar la ventana usando coordenadas de pantalla. */
    private class DragLayout extends FrameLayout {
        boolean armed;
        float lastX, lastY, downX, downY;

        DragLayout(Context c) { super(c); }

        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            float x = ev.getRawX(), y = ev.getRawY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x; downY = y; lastX = x; lastY = y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (armed) moveByPx(x - lastX, y - lastY);
                    lastX = x; lastY = y;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (armed) {
                        armed = false;
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        float moved = Math.abs(x - downX) + Math.abs(y - downY);
                        if (bubble && moved < 12 * density && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                            setBubble(false);
                        }
                    }
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "RequiresFeature", "RestrictedApi"})
    private void setupOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        root = new DragLayout(this);

        // WebView de YouTube (oculto detrás de la tarjeta)
        yt = new WebView(this);
        WebSettings ys = yt.getSettings();
        ys.setJavaScriptEnabled(true);
        ys.setDomStorageEnabled(true);
        ys.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        final boolean docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (docStart) {
            try {
                WebViewCompat.addDocumentStartJavaScript(yt, Scripts.DOC_START,
                        Collections.singleton("https://*.youtube.com"));
            } catch (Exception ignored) {}
        }
        yt.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (AdBlock.isAd(r.getUrl())) return AdBlock.empty();
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                if (host == null) return true;
                return !(host.endsWith("youtube.com") || host.endsWith("youtu.be") || host.endsWith("google.com"));
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (!docStart) v.evaluateJavascript(Scripts.DOC_START, null);
            }
        });
        yt.setWebChromeClient(new WebChromeClient());
        yt.setAlpha(0.01f);
        FrameLayout.LayoutParams ytLp = new FrameLayout.LayoutParams(dp(200), dp(120));
        ytLp.leftMargin = dp(60);
        ytLp.topMargin = dp(80);
        root.addView(yt, ytLp);

        // WebView de la interfaz (la tarjeta)
        ui = new WebView(this);
        ui.setBackgroundColor(Color.TRANSPARENT);
        WebSettings us = ui.getSettings();
        us.setJavaScriptEnabled(true);
        us.setDomStorageEnabled(true);
        ui.addJavascriptInterface(new Bridge(), "Android");
        ui.setWebViewClient(new WebViewClient());
        ui.loadUrl("file:///android_asset/player.html");
        root.addView(ui, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(dp(CARD_W), dp(CARD_H),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("x", Math.max(0, dm.widthPixels - dp(CARD_W)));
        lp.y = prefs.getInt("y", dm.heightPixels / 3);
        clampPos();
        wm.addView(root, lp);

        yt.loadUrl("https://m.youtube.com/");
    }

    private int dp(int v) { return Math.round(v * density); }

    private void clampPos() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = Math.max(-lp.width / 3, Math.min(lp.x, dm.widthPixels - lp.width * 2 / 3));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height / 2));
    }

    private void moveByPx(float dx, float dy) {
        if (root == null) return;
        lp.x += Math.round(dx);
        lp.y += Math.round(dy);
        clampPos();
        wm.updateViewLayout(root, lp);
    }

    private void setBubble(boolean b) {
        if (root == null) return;
        bubble = b;
        if (b) setFocusable(false);
        int oldW = lp.width;
        lp.width = dp(b ? BUBBLE : CARD_W);
        lp.height = dp(b ? BUBBLE : CARD_H);
        lp.x += oldW - lp.width; // mantiene el borde derecho en su sitio
        clampPos();
        wm.updateViewLayout(root, lp);
        pushState();
    }

    private void setFocusable(boolean focus) {
        if (root == null) return;
        if (focus) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wm.updateViewLayout(root, lp);
        if (focus && ui != null) ui.requestFocus();
    }

    // ------------------------------------------------------------------ puente con la interfaz

    private final class Bridge {
        @JavascriptInterface
        public void call(final String name, final String args, final String cbId) {
            main.post(() -> handle(name, args, cbId));
        }
    }

    private void reply(String cbId, String json) {
        if (cbId == null || cbId.isEmpty() || ui == null) return;
        ui.evaluateJavascript("window.__cb&&window.__cb(" + JSONObject.quote(cbId) + "," + json + ")", null);
    }

    private void handle(String name, String argsJson, final String cbId) {
        if (yt == null || name == null) return;
        JSONArray a;
        try {
            a = new JSONArray(argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        } catch (JSONException e) {
            a = new JSONArray();
        }
        switch (name) {
            case "search": {
                final String q = a.optString(0, "");
                io.execute(() -> {
                    String res;
                    try {
                        res = YouTubeSearch.search(q).toString();
                    } catch (Exception e) {
                        res = "{\"error\":true}";
                    }
                    final String r = res;
                    main.post(() -> reply(cbId, r));
                });
                return;
            }
            case "play": play(a.optJSONObject(0), a.optJSONArray(1)); break;
            case "toggle": setPlaying(curPaused); break;
            case "next": next(); break;
            case "prev": prev(); break;
            case "seek": seekTo(curDur * a.optDouble(0, 0)); break;
            case "shuffle":
                shuffle = !shuffle;
                prefs.edit().putBoolean("shuffle", shuffle).apply();
                reply(cbId, String.valueOf(shuffle));
                return;
            case "repeat":
                repeat = !repeat;
                prefs.edit().putBoolean("repeat", repeat).apply();
                reply(cbId, String.valueOf(repeat));
                return;
            case "fav": reply(cbId, String.valueOf(toggleFav())); return;
            case "favorites": reply(cbId, favs().toString()); return;
            case "drag": if (root != null) root.armed = true; return;
            case "focusable": setFocusable(a.optBoolean(0, false)); break;
            case "mode": setBubble("bubble".equals(a.optString(0, ""))); break;
            case "close": stopSelf(); return;
            default: break;
        }
        reply(cbId, "null");
    }

    // ------------------------------------------------------------------ reproducción

    private JSONObject currentItem() {
        return (qIndex >= 0 && qIndex < queue.length()) ? queue.optJSONObject(qIndex) : null;
    }

    private void play(JSONObject item, JSONArray list) {
        if (item == null) return;
        String id = item.optString("id", "");
        if (id.isEmpty()) return;
        if (list != null && list.length() > 0) {
            queue = list;
            qIndex = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) { qIndex = i; break; }
            }
        } else {
            queue = new JSONArray();
            queue.put(item);
            qIndex = 0;
        }
        load(id);
    }

    private void load(String id) {
        if (yt == null || id == null || id.isEmpty()) return;
        wantPlay = true;
        lastEndedId = null;
        curId = id;
        curPos = 0;
        curDur = 0;
        JSONObject it = currentItem();
        if (it != null && id.equals(it.optString("id"))) {
            curTitle = it.optString("title", "");
            curChannel = it.optString("channel", "");
        }
        yt.loadUrl("https://m.youtube.com/watch?v=" + Uri.encode(id));
        pushState();
    }

    private int nextIndex() {
        int n = queue.length();
        if (n == 0) return -1;
        if (shuffle && n > 1) {
            int i;
            do { i = rnd.nextInt(n); } while (i == qIndex);
            return i;
        }
        return qIndex + 1 < n ? qIndex + 1 : -1;
    }

    private void next() {
        int i = nextIndex();
        if (i < 0) return;
        qIndex = i;
        JSONObject o = queue.optJSONObject(i);
        if (o != null) load(o.optString("id", ""));
    }

    private void prev() {
        if (curPos > 3 || qIndex <= 0) { seekTo(0); return; }
        qIndex--;
        JSONObject o = queue.optJSONObject(qIndex);
        if (o != null) load(o.optString("id", ""));
    }

    private void seekTo(double sec) {
        if (yt == null || Double.isNaN(sec) || Double.isInfinite(sec)) return;
        yt.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.currentTime=" + sec + ";})()", null);
    }

    private void setPlaying(boolean playIt) {
        if (yt == null) return;
        wantPlay = playIt;
        yt.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){"
                + (playIt ? "var p=v.play();if(p&&p.catch)p.catch(function(){});" : "v.pause();") + "}})()", null);
    }

    private void poll() {
        if (yt == null) return;
        yt.evaluateJavascript(Scripts.poll(repeat, wantPlay), this::onPoll);
    }

    private static double finite(double d) {
        return (Double.isNaN(d) || Double.isInfinite(d)) ? 0 : d;
    }

    private void onPoll(String res) {
        if (res == null || "null".equals(res) || yt == null) return;
        try {
            JSONObject s = new JSONObject(res);
            String id = s.isNull("id") ? null : s.optString("id", null);
            boolean paused = s.optBoolean("paused", true);
            curAd = s.optBoolean("ad", false);
            curPos = finite(s.optDouble("cur", 0));
            curDur = finite(s.optDouble("dur", 0));
            curPaused = paused;

            JSONObject it = currentItem();
            boolean isQueued = it != null && id != null && id.equals(it.optString("id"));
            if (id != null && !wantPlay) curId = id;
            if (isQueued) {
                curTitle = it.optString("title", "");
                curChannel = it.optString("channel", "");
            } else if (id != null && !wantPlay) {
                curTitle = s.optString("title", "");
                curChannel = "";
            }

            if (wantPlay && isQueued && !paused && !curAd) wantPlay = false;

            if (s.optBoolean("ended", false) && !repeat && !wantPlay && isQueued && !id.equals(lastEndedId)) {
                lastEndedId = id;
                next();
            }

            if (!paused) wakeLock.acquire(60_000L);
            else if (wakeLock.isHeld()) wakeLock.release();

            pushState();
            updateSession();
        } catch (JSONException ignored) {
        }
    }

    private void pushState() {
        if (ui == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("id", curId == null ? JSONObject.NULL : curId);
            o.put("title", curTitle);
            o.put("channel", curChannel);
            o.put("cur", finite(curPos));
            o.put("dur", finite(curDur));
            o.put("paused", curPaused);
            o.put("ad", curAd);
            o.put("fav", isFav(curId));
            o.put("shuffle", shuffle);
            o.put("repeat", repeat);
            o.put("bubble", bubble);
        } catch (JSONException ignored) {
        }
        ui.evaluateJavascript("window.__state&&window.__state(" + o + ")", null);
    }

    // ------------------------------------------------------------------ favoritos

    private JSONArray favs() {
        try {
            return new JSONArray(prefs.getString("favs", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private boolean isFav(String id) {
        if (id == null) return false;
        JSONArray f = favs();
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return true;
        }
        return false;
    }

    private boolean toggleFav() {
        if (curId == null) return false;
        JSONArray f = favs();
        JSONArray kept = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o == null) continue;
            if (curId.equals(o.optString("id"))) removed = true;
            else kept.put(o);
        }
        JSONArray out = kept;
        if (!removed) {
            out = new JSONArray();
            JSONObject n = new JSONObject();
            try {
                n.put("id", curId);
                n.put("title", curTitle);
                n.put("channel", curChannel);
                n.put("duration", fmt(curDur));
            } catch (JSONException ignored) {
            }
            out.put(n);
            for (int i = 0; i < kept.length(); i++) out.put(kept.opt(i));
        }
        prefs.edit().putString("favs", out.toString()).apply();
        return !removed;
    }

    private static String fmt(double t) {
        long s = (long) finite(t);
        return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
    }

    // ------------------------------------------------------------------ notificación y controles del sistema

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void setupSession() {
        session = new MediaSession(this, "MiniPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { setPlaying(true); }
            @Override public void onPause() { setPlaying(false); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo(pos / 1000.0); }
            @Override public void onStop() { stopSelf(); }
        });
        session.setActive(true);
    }

    private void startFg() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String title, String act, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi(act, code)).build();
    }

    private boolean hasArt() {
        return art != null && curId != null && curId.equals(artId);
    }

    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(curTitle.isEmpty() ? "Mini Player" : curTitle)
                .setContentText(curChannel.isEmpty() ? "Toca para abrir el reproductor" : curChannel)
                .setContentIntent(pi(ACT_SHOW, 1))
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "Anterior", ACT_PREV, 2))
                .addAction(action(curPaused ? R.drawable.ic_play : R.drawable.ic_pause,
                        curPaused ? "Reproducir" : "Pausa", ACT_TOGGLE, 3))
                .addAction(action(R.drawable.ic_next, "Siguiente", ACT_NEXT, 4))
                .addAction(action(R.drawable.ic_close, "Cerrar", ACT_STOP, 5))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (hasArt()) b.setLargeIcon(art);
        return b.build();
    }

    private void updateSession() {
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(curPaused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING,
                        (long) (curPos * 1000), curPaused ? 0f : 1f)
                .build());

        if (curId != null && !curId.equals(artId)) loadArt(curId);
        String key = curId + "|" + curTitle + "|" + curChannel + "|" + curPaused + "|" + hasArt() + "|" + (long) curDur;
        if (key.equals(notifKey)) return;
        notifKey = key;

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, curTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, curChannel)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (curDur * 1000));
        if (hasArt()) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(mb.build());
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private void loadArt(final String id) {
        artId = id;
        art = null;
        io.execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            main.post(() -> {
                if (id.equals(artId) && result != null) {
                    art = result;
                    notifKey = "";
                }
            });
        });
    }
}
