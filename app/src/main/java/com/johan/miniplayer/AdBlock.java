package com.johan.miniplayer;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/** Bloqueo de anuncios por red (como hace Brave Shields con la lista básica). */
final class AdBlock {
    private static final String[] HOSTS = {
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "googletagservices.com", "googletagmanager.com", "google-analytics.com",
            "imasdk.googleapis.com", "adservice.google.com", "ads.youtube.com",
            "pagead2.googlesyndication.com", "static.doubleclick.net"
    };
    private static final String[] PATHS = {
            "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_",
            "/youtubei/v1/log_event", "/api/stats/qoe", "/api/stats/atr",
            "/ad_data_204", "/pcs/activeview"
    };

    private AdBlock() {}

    static boolean isAd(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host != null) {
            host = host.toLowerCase(Locale.ROOT);
            for (String h : HOSTS) {
                if (host.equals(h) || host.endsWith("." + h)) return true;
            }
        }
        String path = uri.getPath();
        if (path != null) {
            for (String p : PATHS) {
                if (path.contains(p)) return true;
            }
        }
        return false;
    }

    static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }
}
