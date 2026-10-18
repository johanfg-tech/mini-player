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
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio que mantiene la ventana flotante sobre las demás apps y reproduce
 * el audio de YouTube con el reproductor nativo (sin anuncios), con controles
 * en la notificación y en la pantalla de bloqueo.
 */
public class PlayerService extends Service {
    static final String ACT_SHOW = "com.johan.miniplayer.SHOW";
    static final String ACT_TOGGLE = "com.johan.miniplayer.TOGGLE";
    static final String ACT_NEXT = "com.johan.miniplayer.NEXT";
    static final String ACT_PREV = "com.johan.miniplayer.PREV";
    static final String ACT_STOP = "com.johan.miniplayer.STOP";
    static final String ACT_BG = "com.johan.miniplayer.BG";

    private static final String CHANNEL = "player";
    private static final int NOTIF_ID = 7;
    private static final int CARD_W = 368, CARD_H = 328, PANEL_H = 600, BUBBLE = 56; // en dp
    private static final long DOCK_DELAY = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Random rnd = new Random();

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private DragLayout root;
    private WebView ui;
    private ExoPlayer player;
    private MediaSession session;
    private SharedPreferences prefs;
    private float density;

    private JSONArray queue = new JSONArray();
    private int qIndex = -1;
    private boolean shuffle, repeat, bubble, panelOpen, docked;
    private String curId;
    private String curTitle = "", curChannel = "";
    private boolean loading;
    private String notice = "";
    private int loadToken;
    private List<StreamResolver.Source> sources;
    private int sourceIndex;
    private String notifKey = "";
    private Bitmap art;
    private String artId;

    private final Runnable dockRunnable = this::dock;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            pushState();
            updateSession();
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
        setupPlayer();

        if (Settings.canDrawOverlays(this)) {
            setupOverlay();
            main.postDelayed(ticker, 500);
        } else {
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a != null && player != null) {
            switch (a) {
                case ACT_TOGGLE: togglePlay(); break;
                case ACT_NEXT: next(); break;
                case ACT_PREV: prev(); break;
                case ACT_STOP: stopSelf(); break;
                case ACT_SHOW: setBubble(false); break;
                case ACT_BG:
                    if (root != null) root.setVisibility(android.view.View.VISIBLE);
                    if (intent.getBooleanExtra("changed", false)) sendBg();
                    break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        player = null;
        if (session != null) session.release();
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        if (ui != null) ui.destroy();
        ui = null;
        io.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ reproductor nativo

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { loading = false; notice = ""; }
                if (state == Player.STATE_ENDED && !repeat) next();
            }

            @Override public void onPlayerError(PlaybackException error) {
                trySource(sourceIndex + 1, true);
            }
        });
    }

    private MediaSource buildSource(StreamResolver.Source s) {
        Map<String, String> headers = new HashMap<>();
        if (s.web) {
            headers.put("Origin", "https://www.youtube.com");
            headers.put("Referer", "https://www.youtube.com/");
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(s.userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(headers);
        if (s.hls) {
            return new HlsMediaSource.Factory(http)
                    .createMediaSource(new MediaItem.Builder().setUri(s.url).setMimeType(MimeTypes.APPLICATION_M3U8).build());
        }
        return new ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(s.url));
    }

    private void trySource(int index, boolean afterError) {
        if (player == null) return;
        if (sources == null || index >= sources.size()) {
            loading = false;
            notice = afterError ? "No se pudo reproducir esta canción" : "Canción no disponible";
            player.stop();
            pushState();
            // Pasa a la siguiente después de un momento
            final int token = loadToken;
            main.postDelayed(() -> { if (token == loadToken && nextIndex() >= 0) next(); }, 2500);
            return;
        }
        sourceIndex = index;
        player.setMediaSource(buildSource(sources.get(index)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void load(String id) {
        if (player == null || id == null || id.isEmpty()) return;
        final int token = ++loadToken;
        curId = id;
        JSONObject it = currentItem();
        if (it != null && id.equals(it.optString("id"))) {
            curTitle = it.optString("title", "");
            curChannel = it.optString("channel", "");
        }
        loading = true;
        notice = "";
        sources = null;
        player.stop();
        player.clearMediaItems();
        pushState();
        io.execute(() -> {
            StreamResolver.Result r = null;
            Exception err = null;
            try {
                r = StreamResolver.resolve(id);
            } catch (Exception e) {
                err = e;
            }
            final StreamResolver.Result res = r;
            final Exception error = err;
            main.post(() -> {
                if (token != loadToken || player == null) return;
                if (res == null) {
                    sources = null;
                    android.util.Log.w("MiniPlayer", "No se pudo leer " + id, error);
                    trySource(0, false);
                    return;
                }
                if (curTitle.isEmpty()) curTitle = res.title;
                if (curChannel.isEmpty()) curChannel = res.channel;
                sources = res.sources;
                trySource(0, false);
            });
        });
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE && curId != null && !loading) {
            load(curId);
            return;
        }
        player.setPlayWhenReady(!player.getPlayWhenReady());
        pushState();
    }

    private JSONObject currentItem() {
        return (qIndex >= 0 && qIndex < queue.length()) ? queue.optJSONObject(qIndex) : null;
    }

    private void play(JSONObject item, JSONArray list, boolean startShuffled) {
        if (list != null && list.length() > 0) {
            queue = list;
            qIndex = 0;
            if (item != null) {
                String id = item.optString("id", "");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o != null && id.equals(o.optString("id"))) { qIndex = i; break; }
                }
            } else if (startShuffled) {
                qIndex = rnd.nextInt(list.length());
            }
        } else if (item != null) {
            queue = new JSONArray();
            queue.put(item);
            qIndex = 0;
        } else {
            return;
        }
        JSONObject cur = currentItem();
        if (cur != null) {
            curTitle = "";
            curChannel = "";
            load(cur.optString("id", ""));
        }
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

    private void goTo(int i) {
        if (i < 0 || i >= queue.length()) return;
        qIndex = i;
        JSONObject o = queue.optJSONObject(i);
        if (o != null) {
            curTitle = "";
            curChannel = "";
            load(o.optString("id", ""));
        }
    }

    private void next() { goTo(nextIndex()); }

    private void prev() {
        if (player == null) return;
        if (player.getCurrentPosition() > 3000 || qIndex <= 0) {
            player.seekTo(0);
            return;
        }
        goTo(qIndex - 1);
    }

    private double posSec() {
        return player == null ? 0 : Math.max(0, player.getCurrentPosition()) / 1000.0;
    }

    private double durSec() {
        if (player == null) return 0;
        long d = player.getDuration();
        return d == C.TIME_UNSET || d < 0 ? 0 : d / 1000.0;
    }

    private boolean isPaused() {
        return player == null || !player.getPlayWhenReady();
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
                    main.removeCallbacks(dockRunnable);
                    if (docked) undock();
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
                    if (bubble) main.postDelayed(dockRunnable, DOCK_DELAY);
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        root = new DragLayout(this);

        ui = new WebView(this);
        ui.setBackgroundColor(Color.TRANSPARENT);
        WebSettings us = ui.getSettings();
        us.setJavaScriptEnabled(true);
        us.setDomStorageEnabled(true);
        ui.addJavascriptInterface(new Bridge(), "Android");
        ui.setWebViewClient(new WebViewClient());
        ui.setWebChromeClient(new WebChromeClient());
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
    }

    private int dp(int v) { return Math.round(v * density); }

    private void clampPos() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (bubble) {
            lp.x = Math.max(-lp.width / 2, Math.min(lp.x, dm.widthPixels - lp.width / 2));
            lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height));
        } else {
            lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - lp.width));
            lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height / 2));
        }
    }

    /** Esconde la burbuja a medias contra el borde más cercano y la vuelve transparente. */
    private void dock() {
        if (root == null || !bubble || docked) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean left = lp.x + lp.width / 2 < dm.widthPixels / 2;
        lp.x = left ? -lp.width / 2 : dm.widthPixels - lp.width / 2;
        docked = true;
        root.setAlpha(0.45f);
        wm.updateViewLayout(root, lp);
    }

    private void undock() {
        if (root == null) return;
        docked = false;
        root.setAlpha(1f);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (lp.x < 0) lp.x = 0;
        else if (lp.x > dm.widthPixels - lp.width) lp.x = dm.widthPixels - lp.width;
        wm.updateViewLayout(root, lp);
    }

    private void moveByPx(float dx, float dy) {
        if (root == null) return;
        lp.x += Math.round(dx);
        lp.y += Math.round(dy);
        clampPos();
        wm.updateViewLayout(root, lp);
    }

    private void applySize() {
        if (root == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int oldW = lp.width;
        if (bubble) {
            lp.width = dp(BUBBLE);
            lp.height = dp(BUBBLE);
        } else {
            lp.width = Math.min(dp(CARD_W), dm.widthPixels);
            lp.height = panelOpen ? Math.min(dp(PANEL_H), dm.heightPixels - dp(40)) : dp(CARD_H);
        }
        lp.x += oldW - lp.width; // mantiene el borde derecho en su sitio
        clampPos();
        if (panelOpen && !bubble) {
            // que el panel quepa completo en la pantalla
            lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height - dp(20)));
        }
        wm.updateViewLayout(root, lp);
    }

    private void setBubble(boolean b) {
        if (root == null) return;
        main.removeCallbacks(dockRunnable);
        docked = false;
        root.setAlpha(1f);
        bubble = b;
        if (b) {
            panelOpen = false;
            setFocusable(false);
        }
        applySize();
        pushState();
        if (b) main.postDelayed(dockRunnable, DOCK_DELAY);
    }

    // ------------------------------------------------------------------ temas y fondo propio

    private java.io.File bgFile() { return new java.io.File(getFilesDir(), "fondo.jpg"); }

    private void sendBg() {
        if (ui == null) return;
        final java.io.File f = bgFile();
        io.execute(() -> {
            String js = "null";
            if (f.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    js = JSONObject.quote("data:image/jpeg;base64,"
                            + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
                } catch (Exception ignored) {
                }
            }
            final String arg = js;
            main.post(() -> { if (ui != null) ui.evaluateJavascript("window.__bg&&window.__bg(" + arg + ")", null); });
        });
    }

    private void setPanel(boolean open) {
        panelOpen = open;
        setFocusable(open);
        applySize();
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
        if (player == null || name == null) return;
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
            case "play": play(a.optJSONObject(0), a.optJSONArray(1), a.optBoolean(2, false)); break;
            case "toggle": togglePlay(); break;
            case "next": next(); break;
            case "prev": prev(); break;
            case "seek": {
                double d = durSec();
                if (d > 0) player.seekTo((long) (d * 1000 * Math.max(0, Math.min(1, a.optDouble(0, 0)))));
                break;
            }
            case "shuffle":
                shuffle = !shuffle;
                prefs.edit().putBoolean("shuffle", shuffle).apply();
                reply(cbId, String.valueOf(shuffle));
                return;
            case "setShuffle":
                shuffle = a.optBoolean(0, false);
                prefs.edit().putBoolean("shuffle", shuffle).apply();
                break;
            case "repeat":
                repeat = !repeat;
                player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                prefs.edit().putBoolean("repeat", repeat).apply();
                reply(cbId, String.valueOf(repeat));
                return;
            case "getMixes": reply(cbId, mixes().toString()); return;
            case "setMixes": {
                JSONArray m = a.optJSONArray(0);
                if (m != null) prefs.edit().putString("mixes", m.toString()).apply();
                break;
            }
            case "drag": if (root != null) root.armed = true; return;
            case "getTheme": reply(cbId, JSONObject.quote(prefs.getString("theme", "crepusculo"))); return;
            case "setTheme": prefs.edit().putString("theme", a.optString(0, "crepusculo")).apply(); break;
            case "getBg": sendBg(); break;
            case "clearBg": bgFile().delete(); sendBg(); break;
            case "pickBg": {
                if (root != null) root.setVisibility(android.view.View.INVISIBLE);
                Intent pick = new Intent(this, PickImageActivity.class);
                pick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(pick);
                } catch (Exception e) {
                    if (root != null) root.setVisibility(android.view.View.VISIBLE);
                }
                break;
            }
            case "panel": setPanel(a.optBoolean(0, false)); break;
            case "mode": setBubble("bubble".equals(a.optString(0, ""))); break;
            case "close": stopSelf(); return;
            default: break;
        }
        reply(cbId, "null");
    }

    // ------------------------------------------------------------------ mixes (listas propias)

    private JSONArray mixes() {
        String saved = prefs.getString("mixes", null);
        if (saved != null) {
            try {
                return new JSONArray(saved);
            } catch (JSONException ignored) {
            }
        }
        // Primera vez: crea "Favoritos" con los favoritos de la versión anterior
        JSONArray out = new JSONArray();
        try {
            JSONObject fav = new JSONObject();
            fav.put("id", "fav");
            fav.put("name", "Favoritos");
            JSONArray songs;
            try {
                songs = new JSONArray(prefs.getString("favs", "[]"));
            } catch (JSONException e) {
                songs = new JSONArray();
            }
            fav.put("songs", songs);
            out.put(fav);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("mixes", out.toString()).apply();
        return out;
    }

    // ------------------------------------------------------------------ estado para la interfaz

    private static double finite(double d) {
        return (Double.isNaN(d) || Double.isInfinite(d)) ? 0 : d;
    }

    private void pushState() {
        if (ui == null || player == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("id", curId == null ? JSONObject.NULL : curId);
            o.put("title", curTitle);
            o.put("channel", curChannel);
            o.put("cur", finite(posSec()));
            o.put("dur", finite(durSec()));
            o.put("paused", isPaused());
            o.put("loading", loading || player.getPlaybackState() == Player.STATE_BUFFERING);
            o.put("notice", notice);
            o.put("shuffle", shuffle);
            o.put("repeat", repeat);
            o.put("bubble", bubble);
            o.put("qIndex", qIndex);
            o.put("qLen", queue.length());
        } catch (JSONException ignored) {
        }
        ui.evaluateJavascript("window.__state&&window.__state(" + o + ")", null);
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
            @Override public void onPlay() { if (isPaused()) togglePlay(); }
            @Override public void onPause() { if (!isPaused()) togglePlay(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { if (player != null) player.seekTo(pos); }
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
        boolean paused = isPaused();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(curTitle.isEmpty() ? "Mini Player" : curTitle)
                .setContentText(curChannel.isEmpty() ? "Toca para abrir el reproductor" : curChannel)
                .setContentIntent(pi(ACT_SHOW, 1))
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "Anterior", ACT_PREV, 2))
                .addAction(action(paused ? R.drawable.ic_play : R.drawable.ic_pause,
                        paused ? "Reproducir" : "Pausa", ACT_TOGGLE, 3))
                .addAction(action(R.drawable.ic_next, "Siguiente", ACT_NEXT, 4))
                .addAction(action(R.drawable.ic_close, "Cerrar", ACT_STOP, 5))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (hasArt()) b.setLargeIcon(art);
        return b.build();
    }

    private void updateSession() {
        if (session == null || player == null) return;
        boolean paused = isPaused();
        double dur = durSec();
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING,
                        player.getCurrentPosition(), paused ? 0f : 1f)
                .build());

        if (curId != null && !curId.equals(artId)) loadArt(curId);
        String key = curId + "|" + curTitle + "|" + curChannel + "|" + paused + "|" + hasArt() + "|" + (long) dur;
        if (key.equals(notifKey)) return;
        notifKey = key;

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, curTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, curChannel)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (dur * 1000));
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
                HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + Uri.encode(id) + "/mqdefault.jpg").openConnection();
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
