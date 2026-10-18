package com.johan.miniplayer;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;

/** Obtiene la dirección directa del audio de un video de YouTube (sin anuncios). */
final class StreamResolver {
    private static boolean initialized;

    static final class Source {
        final String url;
        final boolean hls;
        final String userAgent;
        final boolean web;

        Source(String url, boolean hls) {
            this.url = url;
            this.hls = hls;
            String ua = NpDownloader.UA;
            boolean isWeb = false;
            try {
                if (YoutubeParsingHelper.isAndroidStreamingUrl(url)) ua = YoutubeParsingHelper.getAndroidUserAgent(null);
                else if (YoutubeParsingHelper.isIosStreamingUrl(url)) ua = YoutubeParsingHelper.getIosUserAgent(null);
                else if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) ua = YoutubeParsingHelper.getVisionOsUserAgent(null);
                else isWeb = true;
            } catch (Exception ignored) {
            }
            this.userAgent = ua;
            this.web = isWeb;
        }
    }

    static final class Result {
        final List<Source> sources = new ArrayList<>();
        String title = "";
        String channel = "";
        long duration;
    }

    private StreamResolver() {}

    private static synchronized void init() {
        if (!initialized) {
            NewPipe.init(NpDownloader.get());
            initialized = true;
        }
    }

    private static int score(AudioStream a) {
        int s = 0;
        if (a.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) s += 1_000_000;
        if (a.getFormat() == MediaFormat.M4A) s += 100_000;
        String u = a.isUrl() ? a.getContent() : "";
        try {
            if (!YoutubeParsingHelper.isWebStreamingUrl(u)) s += 10_000; // preferir clientes móviles
        } catch (Exception ignored) {
        }
        s += Math.max(0, Math.min(a.getAverageBitrate(), 5000));
        return s;
    }

    static Result resolve(String videoId) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
        Result r = new Result();
        r.title = info.getName() == null ? "" : info.getName();
        r.channel = info.getUploaderName() == null ? "" : info.getUploaderName();
        r.duration = info.getDuration();

        List<AudioStream> audio = new ArrayList<>(info.getAudioStreams());
        audio.sort((a, b) -> Integer.compare(score(b), score(a)));
        for (AudioStream a : audio) {
            if (!a.isUrl()) continue;
            if (a.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) r.sources.add(new Source(a.getContent(), false));
            else if (a.getDeliveryMethod() == DeliveryMethod.HLS) r.sources.add(new Source(a.getContent(), true));
        }
        // Respaldo: video con audio incluido
        for (VideoStream v : info.getVideoStreams()) {
            if (!v.isUrl() || v.isVideoOnly()) continue;
            if (v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) r.sources.add(new Source(v.getContent(), false));
            else if (v.getDeliveryMethod() == DeliveryMethod.HLS) r.sources.add(new Source(v.getContent(), true));
        }
        String hls = info.getHlsUrl();
        if (hls != null && !hls.isEmpty()) r.sources.add(new Source(hls, true));
        return r;
    }
}
