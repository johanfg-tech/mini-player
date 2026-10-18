package com.johan.miniplayer;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Conexión a internet que usa la librería que lee YouTube. */
final class NpDownloader extends Downloader {
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static NpDownloader instance;

    private NpDownloader() {}

    static synchronized NpDownloader get() {
        if (instance == null) instance = new NpDownloader();
        return instance;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(request.httpMethod());
        boolean hasUa = false;
        Map<String, List<String>> reqHeaders = request.headers();
        if (reqHeaders != null) {
            for (Map.Entry<String, List<String>> e : reqHeaders.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                for (String v : e.getValue()) c.addRequestProperty(e.getKey(), v);
                if ("User-Agent".equalsIgnoreCase(e.getKey())) hasUa = true;
            }
        }
        if (!hasUa) c.setRequestProperty("User-Agent", UA);

        byte[] data = request.dataToSend();
        if (data != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
        }

        int code = c.getResponseCode();
        String message = c.getResponseMessage();
        if (code == 429) {
            c.disconnect();
            throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
        }

        Map<String, List<String>> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) headers.put(e.getKey(), e.getValue());
        }

        String body = "";
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
                body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        String latestUrl = c.getURL().toString();
        c.disconnect();
        return new Response(code, message, headers, body, latestUrl);
    }
}
