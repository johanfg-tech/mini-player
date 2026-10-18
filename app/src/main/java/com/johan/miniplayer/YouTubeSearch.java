package com.johan.miniplayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Busca videos en YouTube leyendo los datos de la página de resultados. */
final class YouTubeSearch {
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36";
    private static final int MAX = 25;

    private YouTubeSearch() {}

    static JSONArray search(String query) throws Exception {
        URL url = new URL("https://www.youtube.com/results?hl=es&gl=CO&search_query="
                + URLEncoder.encode(query, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "es-CO,es;q=0.9");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            c.disconnect();
        }
        String html = sb.toString();
        int start = html.indexOf("var ytInitialData = ");
        if (start < 0) start = html.indexOf("var ytInitialData=");
        JSONArray out = new JSONArray();
        if (start < 0) return out;
        start = html.indexOf('{', start);
        int end = html.indexOf(";</script>", start);
        if (start < 0 || end < 0) return out;
        Object data = new JSONObject(html.substring(start, end));
        walk(data, out, new HashSet<>());
        return out;
    }

    private static void walk(Object o, JSONArray out, Set<String> seen) throws JSONException {
        if (out.length() >= MAX || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject obj = (JSONObject) o;
            JSONObject v = obj.optJSONObject("videoRenderer");
            if (v != null && v.has("videoId")) {
                String id = v.getString("videoId");
                if (seen.add(id)) {
                    JSONObject item = new JSONObject();
                    item.put("id", id);
                    item.put("title", text(v.optJSONObject("title")));
                    JSONObject owner = v.optJSONObject("ownerText");
                    if (owner == null) owner = v.optJSONObject("longBylineText");
                    item.put("channel", text(owner));
                    item.put("duration", text(v.optJSONObject("lengthText")));
                    out.put(item);
                }
                return;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext() && out.length() < MAX) walk(obj.opt(keys.next()), out, seen);
        } else if (o instanceof JSONArray) {
            JSONArray arr = (JSONArray) o;
            for (int i = 0; i < arr.length() && out.length() < MAX; i++) walk(arr.opt(i), out, seen);
        }
    }

    private static String text(JSONObject t) {
        if (t == null) return "";
        if (t.has("simpleText")) return t.optString("simpleText", "");
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString();
    }
}
