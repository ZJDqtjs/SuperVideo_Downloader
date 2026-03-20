package com.zjdqtjs.supervideoDL;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WebViewParser {

    private static final String TAG = "WebViewParser";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int PARSE_TIMEOUT_SECONDS = 30;
    private static final int ACCEPT_SCORE = 80;
    private static final int MIN_CANDIDATE_SCORE = 10;
    private static final int MAX_PROBE_CANDIDATES = 40;
    private static final int PROBE_BYTES = 4096;
    private static final int ACTIVE_SNIFF_MAX_TRIES = 10;
    private static final long ACTIVE_SNIFF_INTERVAL_MS = 900L;
    private static final OkHttpClient probeClient = new OkHttpClient();
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "https?://[^\\s\"']+(?:\\.(?:m3u8|mp4|m4s|ts)(?:\\?[^\\s\"']*)?|aweme/v1/(?:play|playwm)(?:\\?[^\\s\"']*)?|stream(?:/[^\\s\"']*)?(?:\\?[^\\s\"']*)?)",
            Pattern.CASE_INSENSITIVE
    );

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CountDownLatch parseLatch = new CountDownLatch(1);
    private final AtomicReference<MediaSource> bestSourceRef = new AtomicReference<>();
    private final AtomicReference<WebView> webViewRef = new AtomicReference<>();
    private final Map<String, Integer> candidateScoreMap = new ConcurrentHashMap<>();
    private volatile int activeSniffTryCount = 0;

    private final String originalUrl;
    private volatile String lastPageUrl;
    private volatile int bestScore = Integer.MIN_VALUE;

    public WebViewParser(Context context, String shareUrl) {
        this.context = context;
        this.originalUrl = shareUrl;
        this.lastPageUrl = shareUrl;
    }

    public String parseVideoUrl() throws Exception {
        return parseMediaSource().getUrl();
    }

    public MediaSource parseMediaSource() throws Exception {
        mainHandler.post(this::createAndLoadWebView);

        parseLatch.await(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        destroyWebView();

        MediaSource best = bestSourceRef.get();
        if (best != null) {
            Log.d(TAG, "Resolved media: " + best.url + " score=" + bestScore);
            return best;
        }

        Log.d(TAG, "No direct media resolved, start binary probe. candidateCount=" + candidateScoreMap.size());
        MediaSource sniffed = probeCandidatesByBinary();
        if (sniffed != null) {
            Log.d(TAG, "Resolved media by binary probe: " + sniffed.url);
            return sniffed;
        }

        throw new Exception("WebView parser did not capture a downloadable media URL");
    }

    private void createAndLoadWebView() {
        WebView webView = new WebView(context);
        webViewRef.set(webView);
        activeSniffTryCount = 0;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(DEFAULT_USER_AGENT);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new JSBridge(), "AndroidParser");
        webView.setWebViewClient(new ParserWebViewClient());
        webView.loadUrl(originalUrl);
    }

    private void destroyWebView() {
        mainHandler.post(() -> {
            WebView webView = webViewRef.getAndSet(null);
            if (webView == null) {
                return;
            }

            try {
                webView.stopLoading();
            } catch (Throwable ignored) {
            }

            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
        });
    }

    private void scheduleActiveSniffing(WebView view) {
        if (view == null || parseLatch.getCount() == 0) {
            return;
        }

        if (activeSniffTryCount >= ACTIVE_SNIFF_MAX_TRIES) {
            return;
        }

        activeSniffTryCount++;

        triggerCenterTap(view);
        injectAutoPlayJs(view);

        mainHandler.postDelayed(() -> {
            WebView current = webViewRef.get();
            if (current == view && parseLatch.getCount() > 0) {
                scheduleActiveSniffing(view);
            }
        }, ACTIVE_SNIFF_INTERVAL_MS);
    }

    private void triggerCenterTap(WebView view) {
        try {
            int width = view.getWidth();
            int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            float x = width / 2f;
            float y = height / 2f;
            long downTime = System.currentTimeMillis();

            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0);

            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);

            down.recycle();
            up.recycle();
        } catch (Exception e) {
            Log.d(TAG, "Center tap simulation failed: " + e.getMessage());
        }
    }

    private void injectAutoPlayJs(WebView view) {
        String js = "(function(){"
                + "try{"
                + "function clickIf(el){if(!el){return;} try{el.click();}catch(e){}"
                + " try{var evt=new MouseEvent('click',{bubbles:true,cancelable:true,view:window}); el.dispatchEvent(evt);}catch(e){}"
                + "}"
                + "var selectors=["
                + "'.xgplayer-start','.xgplayer-play','.xgplayer-replay',"
                + "'.xgplayer-poster','.xgplayer-cover','.xgplayer-controls',"
                + "'.play-btn','.player-play-btn','.btn-play',"
                + "'.note-video-play-btn','.media-player-icon-play',"
                + "'.video-play-btn','.video-player .play','.video-player .poster'"
                + "];"
                + "for(var i=0;i<selectors.length;i++){"
                + " var list=document.querySelectorAll(selectors[i]);"
                + " for(var j=0;j<list.length;j++){clickIf(list[j]);}"
                + "}"
                + "var videos=document.querySelectorAll('video');"
                + "for(var k=0;k<videos.length;k++){"
                + " var v=videos[k];"
                + " try{v.muted=true;v.playsInline=true;v.setAttribute('playsinline','true');v.setAttribute('webkit-playsinline','true');}catch(e){}"
                + " try{var p=v.play(); if(p&&p.catch){p.catch(function(){});} }catch(e){}"
                + " if(v.currentSrc&&window.AndroidParser&&AndroidParser.onVideoUrlFound){AndroidParser.onVideoUrlFound(v.currentSrc);}"
                + " if(v.src&&window.AndroidParser&&AndroidParser.onVideoUrlFound){AndroidParser.onVideoUrlFound(v.src);}"
                + "}"
                + "var center=document.elementFromPoint(window.innerWidth/2, window.innerHeight/2);"
                + "clickIf(center);"
                + "return 'ok';"
                + "}catch(e){return 'err:'+e.message;}"
                + "})();";

        view.evaluateJavascript(js, null);
    }

    private void addCandidate(String rawUrl, Map<String, String> requestHeaders) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return;
        }

        String url = sanitizeUrl(rawUrl);
        if (!url.startsWith("http")) {
            return;
        }

        int score = scoreUrl(url, requestHeaders);
        if (score < MIN_CANDIDATE_SCORE) {
            return;
        }

        Integer knownScore = candidateScoreMap.putIfAbsent(url, score);
        if (knownScore != null && knownScore >= score) {
            return;
        }

        if (score <= bestScore) {
            return;
        }

        bestScore = score;
        String referer = lastPageUrl != null ? lastPageUrl : originalUrl;
        String cookies = CookieManager.getInstance().getCookie(referer);

        MediaSource source = new MediaSource(url, isStreamUrl(url), referer, DEFAULT_USER_AGENT, cookies);
        bestSourceRef.set(source);
        Log.d(TAG, "Candidate media: " + url + " score=" + score);

        if (score >= ACCEPT_SCORE) {
            parseLatch.countDown();
        }
    }

    private int scoreUrl(String url, Map<String, String> requestHeaders) {
        String lower = url.toLowerCase();
        int score = 0;
        boolean hasExplicitMediaExt = lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains(".ts") || lower.contains(".m4s");

        if (lower.contains(".m3u8")) score += 95;
        if (lower.contains(".mp4")) score += 90;
        if (lower.contains(".ts") || lower.contains(".m4s")) score += 70;
        if (lower.contains("mime=video")) score += 60;
        if (lower.contains("video_url=") || lower.contains("playurl=") || lower.contains("url=")) score += 45;
        if (lower.contains("video=")) score += 10;
        if (lower.contains("play") || lower.contains("stream")) score += 25;
        if (lower.contains("bilivideo") || lower.contains("kwaicdn") || lower.contains("douyinvod")) score += 40;
        if (lower.contains("aweme") || lower.contains("watermark") || lower.contains("download_addr")) score += 20;

        if (isKnownMediaEndpoint(lower)) {
            score += 110;
        }

        if (requestHeaders != null) {
            String accept = requestHeaders.get("Accept");
            if (accept == null) {
                accept = requestHeaders.get("accept");
            }
            if (accept != null) {
                String lowerAccept = accept.toLowerCase();
                if (lowerAccept.contains("video/")) {
                    score += 35;
                }
                if (lowerAccept.contains("application/vnd.apple.mpegurl")) {
                    score += 35;
                }
            }
        }

        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
                lower.contains(".gif") || lower.contains(".webp") || lower.contains(".css") ||
                lower.contains(".js") || lower.contains("favicon")) {
            score -= 60;
        }

        // API endpoints often return JSON metadata instead of media bytes.
        if ((lower.contains("/api/") || lower.contains("aweme/v1/") || lower.contains("feed/")) &&
            !hasExplicitMediaExt && !isKnownMediaEndpoint(lower)) {
            score -= 90;
        }

        if (lower.contains(".json")) {
            score -= 90;
        }

        if (!hasExplicitMediaExt && !hasMediaIndicator(url, requestHeaders)) {
            score -= 35;
        }

        return score;
    }

    private boolean hasMediaIndicator(String url, Map<String, String> requestHeaders) {
        String lower = url.toLowerCase();

        if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".m4s") || lower.contains(".ts")) {
            return true;
        }

        if (isKnownMediaEndpoint(lower)) {
            return true;
        }

        if (lower.contains("mime=video") || lower.contains("content_type=video") || lower.contains("mediatype=video")) {
            return true;
        }

        if (requestHeaders != null) {
            String accept = requestHeaders.get("Accept");
            if (accept == null) {
                accept = requestHeaders.get("accept");
            }
            if (accept != null) {
                String lowerAccept = accept.toLowerCase();
                if (lowerAccept.contains("video/") || lowerAccept.contains("application/vnd.apple.mpegurl")) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isKnownMediaEndpoint(String lowerUrl) {
        boolean isDouyinPlay = (lowerUrl.contains("iesdouyin.com/aweme/v1/play") ||
                lowerUrl.contains("aweme.snssdk.com/aweme/v1/play") ||
                lowerUrl.contains("douyin.com/aweme/v1/play")) &&
                lowerUrl.contains("video_id=");

        boolean isXhsStream = (lowerUrl.contains("xhscdn.com/stream/") ||
                lowerUrl.contains("xhscdn.com/stream?"));

        return isDouyinPlay || isXhsStream;
    }

    private boolean isStreamUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains(".ts") || lower.contains(".m4s");
    }

    private String sanitizeUrl(String url) {
        String out = url.trim();
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() > 1) {
            out = out.substring(1, out.length() - 1);
        }
        return out.replace("\\u0026", "&");
    }

    private void parseMediaCandidatesFromJson(String resultJson) {
        if (resultJson == null) {
            return;
        }

        String decoded = resultJson.replace("\\\"", "\"");
        Matcher matcher = MEDIA_URL_PATTERN.matcher(decoded);
        while (matcher.find()) {
            addCandidate(matcher.group(), null);
        }
    }

    private MediaSource probeCandidatesByBinary() {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(candidateScoreMap.entrySet());
        if (entries.isEmpty()) {
            Log.d(TAG, "Binary probe skipped: no candidates collected");
            return null;
        }

        entries.sort(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed());
        int limit = Math.min(entries.size(), MAX_PROBE_CANDIDATES);
        Log.d(TAG, "Binary probe candidates=" + entries.size() + " inspectTop=" + limit);

        for (int i = 0; i < limit; i++) {
            String candidateUrl = entries.get(i).getKey();
            try {
                MediaSource source = probeSingleCandidate(candidateUrl);
                if (source != null) {
                    return source;
                }
            } catch (Exception e) {
                Log.d(TAG, "Probe candidate skipped: " + candidateUrl + " err=" + e.getMessage());
            }
        }

        return null;
    }

    private MediaSource probeSingleCandidate(String candidateUrl) throws Exception {
        String referer = lastPageUrl != null ? lastPageUrl : originalUrl;
        String cookies = CookieManager.getInstance().getCookie(referer);

        Request.Builder builder = new Request.Builder()
                .url(candidateUrl)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-" + (PROBE_BYTES - 1));

        if (referer != null && !referer.isEmpty()) {
            builder.header("Referer", referer);
        }
        if (cookies != null && !cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }

        try (Response response = probeClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }

            String finalUrl = response.request().url().toString();
            String contentType = response.header("Content-Type");
            byte[] sample = response.peekBody(PROBE_BYTES).bytes();

            if (!isLikelyMediaResponse(finalUrl, contentType, sample)) {
                return null;
            }

            String finalReferer = lastPageUrl != null ? lastPageUrl : originalUrl;
            String finalCookie = CookieManager.getInstance().getCookie(finalReferer);
            boolean streaming = isStreamingByBinary(finalUrl, contentType, sample);
            return new MediaSource(finalUrl, streaming, finalReferer, DEFAULT_USER_AGENT, finalCookie);
        }
    }

    private boolean isLikelyMediaResponse(String finalUrl, String contentType, byte[] sample) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        String lowerUrl = finalUrl == null ? "" : finalUrl.toLowerCase();

        if (looksLikeTextPayload(sample)) {
            return false;
        }

        if (lowerType.contains("video/") || lowerType.contains("mpegurl") || lowerType.contains("mp2t")) {
            return true;
        }

        if (lowerType.contains("application/octet-stream") && looksLikeBinaryMedia(sample)) {
            return true;
        }

        if ((lowerUrl.contains(".mp4") || lowerUrl.contains(".m3u8") || lowerUrl.contains(".ts") ||
                lowerUrl.contains(".m4s") || isKnownMediaEndpoint(lowerUrl)) && looksLikeBinaryMedia(sample)) {
            return true;
        }

        return false;
    }

    private boolean isStreamingByBinary(String finalUrl, String contentType, byte[] sample) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        String lowerUrl = finalUrl == null ? "" : finalUrl.toLowerCase();

        if (lowerType.contains("mpegurl") || lowerType.contains("mp2t")) {
            return true;
        }
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".m4s") || lowerUrl.contains(".ts")) {
            return true;
        }
        String text = sampleAsLowerText(sample);
        return text.startsWith("#extm3u");
    }

    private boolean looksLikeBinaryMedia(byte[] sample) {
        if (sample == null || sample.length == 0) {
            return false;
        }
        if (looksLikeMp4(sample) || looksLikeM3u8(sample) || looksLikeTs(sample)) {
            return true;
        }

        // Some CDNs serve MP4 as octet-stream without obvious extension/signature in first bytes.
        return !looksLikeTextPayload(sample);
    }

    private boolean looksLikeMp4(byte[] sample) {
        if (sample.length < 12) {
            return false;
        }
        return sample[4] == 'f' && sample[5] == 't' && sample[6] == 'y' && sample[7] == 'p';
    }

    private boolean looksLikeM3u8(byte[] sample) {
        String text = sampleAsLowerText(sample);
        return text.startsWith("#extm3u");
    }

    private boolean looksLikeTs(byte[] sample) {
        if (sample.length < 188) {
            return false;
        }
        if ((sample[0] & 0xFF) != 0x47) {
            return false;
        }
        int second = 188;
        return sample.length > second && (sample[second] & 0xFF) == 0x47;
    }

    private boolean looksLikeTextPayload(byte[] sample) {
        String text = sampleAsLowerText(sample).trim();
        return text.startsWith("{") || text.startsWith("[") || text.startsWith("<html") ||
                text.startsWith("<!doctype") || text.startsWith("<?xml") ||
                text.contains("\"errno\"") || text.contains("\"errmsg\"") ||
                text.contains("\"status_code\"");
    }

    private String sampleAsLowerText(byte[] sample) {
        if (sample == null || sample.length == 0) {
            return "";
        }
        int len = Math.min(sample.length, 1024);
        return new String(sample, 0, len, StandardCharsets.UTF_8).toLowerCase();
    }

    private class ParserWebViewClient extends WebViewClient {
        @Override
        public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            addCandidate(request.getUrl().toString(), request.getRequestHeaders());
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            lastPageUrl = url;
            injectMediaDetectionJs(view);
            scheduleActiveSniffing(view);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            lastPageUrl = url;
            scheduleActiveSniffing(view);
        }

        private void injectMediaDetectionJs(WebView view) {
            String js = "(function(){"
                    + "try{"
                    + "var list=[];"
                    + "function pushU(u){if(!u){return;} list.push(u); try{ if(window.AndroidParser&&AndroidParser.onVideoUrlFound){ AndroidParser.onVideoUrlFound(u);} }catch(e){} }"
                    + "if(!window.__svd_hooked){"
                    + " window.__svd_hooked=true;"
                    + " var ofetch=window.fetch;"
                    + " if(ofetch){window.fetch=function(input,init){try{var fu=(typeof input==='string')?input:(input&&input.url);pushU(fu);}catch(e){} return ofetch.apply(this,arguments);};}"
                    + " var oopen=XMLHttpRequest.prototype.open;"
                    + " XMLHttpRequest.prototype.open=function(method,url){try{pushU(url);}catch(e){} return oopen.apply(this,arguments);};"
                    + "}"
                    + "var videos=document.querySelectorAll('video');"
                    + "for(var i=0;i<videos.length;i++){"
                    + " var v=videos[i];"
                    + " if(v.currentSrc){pushU(v.currentSrc);}"
                    + " if(v.src){pushU(v.src);}"
                    + " var srcs=v.querySelectorAll('source');"
                    + " for(var j=0;j<srcs.length;j++){if(srcs[j].src){pushU(srcs[j].src);}}"
                    + "}"
                    + "var html=document.documentElement.innerHTML;"
                    + "var reg=/(https?:\\/\\/[^\\s\\\"']+(?:\\.(m3u8|mp4|m4s|ts)(\\?[^\\s\\\"']*)?|aweme\\/v1\\/(play|playwm)(\\?[^\\s\\\"']*)?|stream(\\/[^\\s\\\"']*)?(\\?[^\\s\\\"']*)?))/gi;"
                    + "var m; while((m=reg.exec(html))!==null){pushU(m[1]);}"
                    + "return JSON.stringify(list);"
                    + "}catch(e){return '[]';}"
                    + "})();";

            view.evaluateJavascript(js, WebViewParser.this::parseMediaCandidatesFromJson);
        }
    }

    private class JSBridge {
        @JavascriptInterface
        public void onVideoUrlFound(String url) {
            addCandidate(url, null);
        }
    }

    public static class MediaSource {
        private final String url;
        private final boolean streaming;
        private final String referer;
        private final String userAgent;
        private final String cookie;

        public MediaSource(String url, boolean streaming, String referer, String userAgent, String cookie) {
            this.url = url;
            this.streaming = streaming;
            this.referer = referer;
            this.userAgent = userAgent;
            this.cookie = cookie;
        }

        public String getUrl() {
            return url;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public String getReferer() {
            return referer;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public String getCookie() {
            return cookie;
        }
    }
}
