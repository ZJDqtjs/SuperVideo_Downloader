package com.zjdqtjs.supervideoDL;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VideoDownloader {
    private static final String TAG = "VideoDownloader";
    private static final String TRACE = "SVD_TRACE";
    private static final String DOWNLOAD_SUBDIR = "DouyinDL";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int MAX_SEGMENTS = 800;

    private static final OkHttpClient client = new OkHttpClient();

    public static File downloadVideo(Context context, String videoUrl) throws Exception {
        WebViewParser.MediaSource source = new WebViewParser.MediaSource(videoUrl, isM3u8(videoUrl), videoUrl, DEFAULT_USER_AGENT, null);
        return downloadMedia(context, source);
    }

    public static File downloadMedia(Context context, WebViewParser.MediaSource source) throws Exception {
        if (source == null || source.getUrl() == null || source.getUrl().isEmpty()) {
            throw new Exception("Invalid media source");
        }

        Log.d(TAG, TRACE + " download.start url=" + source.getUrl() + " streamingHint=" + source.isStreaming() +
                " referer=" + source.getReferer() + " cookie=" + (source.getCookie() != null && !source.getCookie().isEmpty()));

        if (source.isStreaming() || isM3u8(source.getUrl())) {
            Log.d(TAG, TRACE + " download.route m3u8/stream");
            return downloadM3u8Stream(source);
        }

        Log.d(TAG, TRACE + " download.route direct");
        return downloadDirect(source);
    }

    private static File downloadDirect(WebViewParser.MediaSource source) throws Exception {
        Request request = buildRequest(source.getUrl(), source);

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("Download failed: HTTP " + response.code());
            }

            Log.d(TAG, TRACE + " direct.response code=" + response.code() + " type=" + response.header("Content-Type") +
                    " len=" + response.body().contentLength() + " finalUrl=" + response.request().url());

            ensureMediaResponse(response, source.getUrl());

            String ext = inferFileExtension(source.getUrl(), response.header("Content-Type"));
            File outputFile = createOutputFile(ext);
            writeResponseBody(response, outputFile);

            Log.d(TAG, "Direct media saved: " + outputFile.getAbsolutePath());
            return outputFile;
        }
    }

    private static File downloadM3u8Stream(WebViewParser.MediaSource source) throws Exception {
        String playlistUrl = source.getUrl();
        String m3u8Text = fetchText(playlistUrl, source);
        Log.d(TAG, TRACE + " m3u8.fetch url=" + playlistUrl + " textLen=" + m3u8Text.length());

        if (m3u8Text.contains("#EXT-X-STREAM-INF")) {
            playlistUrl = resolveMasterPlaylist(playlistUrl, m3u8Text);
            m3u8Text = fetchText(playlistUrl, source);
            Log.d(TAG, TRACE + " m3u8.master selected=" + playlistUrl + " textLen=" + m3u8Text.length());
        }

        if (m3u8Text.contains("#EXT-X-KEY")) {
            throw new Exception("Encrypted m3u8 is not supported currently");
        }

        List<String> segments = parseSegments(playlistUrl, m3u8Text);
        if (segments.isEmpty()) {
            throw new Exception("No downloadable segment found in m3u8");
        }
        Log.d(TAG, TRACE + " m3u8.segments count=" + segments.size());

        File outputFile = createOutputFile(".mp4");
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            int count = 0;
            for (String segmentUrl : segments) {
                if (count >= MAX_SEGMENTS) {
                    Log.w(TAG, "Segment limit reached, truncating stream: " + MAX_SEGMENTS);
                    break;
                }

                Request segmentRequest = buildRequest(segmentUrl, source);
                try (Response response = client.newCall(segmentRequest).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.w(TAG, TRACE + " m3u8.segment.fail code=" + response.code() + " seg=" + segmentUrl);
                        throw new IOException("Segment request failed: " + response.code() + " " + segmentUrl);
                    }

                    try (InputStream in = new BufferedInputStream(response.body().byteStream())) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                }
                count++;
            }
        }

        Log.d(TAG, "Stream media saved: " + outputFile.getAbsolutePath() + " segments=" + segments.size());
        return outputFile;
    }

    private static String fetchText(String url, WebViewParser.MediaSource source) throws IOException {
        Request request = buildRequest(url, source);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Request failed: HTTP " + response.code());
            }
            Log.d(TAG, TRACE + " fetchText.ok code=" + response.code() + " type=" + response.header("Content-Type") + " url=" + response.request().url());
            return response.body().string();
        }
    }

    private static String resolveMasterPlaylist(String playlistUrl, String content) throws Exception {
        String[] lines = content.split("\\r?\\n");
        int bestBandwidth = -1;
        String bestVariant = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }

            int bandwidth = extractBandwidth(line);
            String next = findNextUriLine(lines, i + 1);
            if (next == null) {
                continue;
            }

            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth;
                bestVariant = resolveUrl(playlistUrl, next);
            }
        }

        if (bestVariant == null) {
            throw new Exception("No variant stream found in master m3u8");
        }

        return bestVariant;
    }

    private static int extractBandwidth(String streamInfLine) {
        String marker = "BANDWIDTH=";
        int idx = streamInfLine.indexOf(marker);
        if (idx < 0) {
            return 0;
        }

        int start = idx + marker.length();
        int end = start;
        while (end < streamInfLine.length() && Character.isDigit(streamInfLine.charAt(end))) {
            end++;
        }

        try {
            return Integer.parseInt(streamInfLine.substring(start, end));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String findNextUriLine(String[] lines, int start) {
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return line;
            }
        }
        return null;
    }

    private static List<String> parseSegments(String playlistUrl, String content) throws Exception {
        String[] lines = content.split("\\r?\\n");
        List<String> segments = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            segments.add(resolveUrl(playlistUrl, line));
        }

        return segments;
    }

    private static String resolveUrl(String base, String target) throws Exception {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target;
        }
        URI baseUri = new URI(base);
        return baseUri.resolve(target).toString();
    }

    private static Request buildRequest(String url, WebViewParser.MediaSource source) {
        String requestUrl = normalizeTransportUrl(url);
        Request.Builder builder = new Request.Builder().url(requestUrl);

        String userAgent = source.getUserAgent();
        if (userAgent == null || userAgent.isEmpty()) {
            userAgent = DEFAULT_USER_AGENT;
        }
        builder.header("User-Agent", userAgent);
        builder.header("Accept", "*/*");

        String referer = source.getReferer();
        if (referer == null || referer.isEmpty()) {
            referer = inferPlatformReferer(requestUrl);
        }
        if (referer != null && !referer.isEmpty()) {
            builder.header("Referer", referer);

            String origin = extractOrigin(referer);
            if (origin != null && !origin.isEmpty()) {
                builder.header("Origin", origin);
            }
        }

        if (source.getCookie() != null && !source.getCookie().isEmpty()) {
            builder.header("Cookie", source.getCookie());
        }

        Log.d(TAG, TRACE + " request.build url=" + requestUrl + " referer=" + referer +
                " hasCookie=" + (source.getCookie() != null && !source.getCookie().isEmpty()));

        return builder.build();
    }

    private static String normalizeTransportUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        String lower = url.toLowerCase();
        boolean isXhsMediaCdn = lower.contains("xhscdn.com") || lower.contains("sns-video");
        if (isXhsMediaCdn && lower.startsWith("http://")) {
            String upgraded = "https://" + url.substring("http://".length());
            Log.d(TAG, TRACE + " request.upgradeHttps from=" + url + " to=" + upgraded);
            return upgraded;
        }

        return url;
    }

    private static String inferPlatformReferer(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.contains("douyin") || lower.contains("iesdouyin") || lower.contains("snssdk.com/aweme/")) {
            return "https://www.iesdouyin.com/";
        }
        if (lower.contains("xhscdn.com") || lower.contains("xiaohongshu.com")) {
            return "https://www.xiaohongshu.com/";
        }
        return null;
    }

    private static String extractOrigin(String referer) {
        try {
            URI uri = new URI(referer);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeResponseBody(Response response, File outputFile) throws IOException {
        try (InputStream inputStream = response.body().byteStream();
             FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    private static String inferFileExtension(String url, String contentType) {
        String lowerUrl = url == null ? "" : url.toLowerCase();
        String lowerType = contentType == null ? "" : contentType.toLowerCase();

        if (lowerUrl.contains(".m3u8") || lowerType.contains("mpegurl")) {
            return ".mp4";
        }
        if (lowerUrl.contains(".ts") || lowerType.contains("mp2t")) {
            return ".mp4";
        }
        return ".mp4";
    }

    private static boolean isM3u8(String url) {
        return url != null && url.toLowerCase().contains(".m3u8");
    }

    private static void ensureMediaResponse(Response response, String sourceUrl) throws Exception {
        String contentType = response.header("Content-Type");
        String lowerType = contentType == null ? "" : contentType.toLowerCase();

        if (lowerType.contains("application/json") ||
                lowerType.contains("text/") ||
                lowerType.contains("html") ||
                lowerType.contains("javascript") ||
                lowerType.contains("xml")) {
            Log.w(TAG, TRACE + " mediaCheck.fail contentType=" + contentType + " url=" + sourceUrl);
            throw new Exception("Parsed URL is not a video resource (Content-Type: " + contentType + ")");
        }

        String sniff = response.peekBody(1024).string().trim().toLowerCase();
        if (sniff.startsWith("{") || sniff.startsWith("[") || sniff.startsWith("<html") ||
                sniff.startsWith("<!doctype") || sniff.startsWith("<?xml") ||
                sniff.contains("\"errno\"") || sniff.contains("\"errmsg\"")) {
            Log.w(TAG, TRACE + " mediaCheck.fail textPayload url=" + sourceUrl + " preview=" + sniff.substring(0, Math.min(120, sniff.length())));
            throw new Exception("Parsed URL returned text/json instead of media: " + sourceUrl);
        }

        Log.d(TAG, TRACE + " mediaCheck.pass url=" + sourceUrl + " type=" + contentType);
    }

    private static File createOutputFile(String ext) throws IOException {
        File dir = getDownloadDirectory();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create download directory: " + dir.getAbsolutePath());
        }
        return new File(dir, "video_" + System.currentTimeMillis() + ext);
    }

    public static File getDownloadDirectory() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBDIR
        );
    }
}
