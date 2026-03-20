package com.zjdqtjs.supervideoDL;

import android.content.Context;
import android.util.Log;

import java.io.File;

public class VideoParser {
    private static final String TAG = "VideoParser";
    private static final String TRACE = "SVD_TRACE";

    public static File parseAndDownload(Context context, String shareUrl, String platform) throws Exception {
        Exception webViewError = null;
        String redirectUrl = resolveFallbackUrl(shareUrl);
        Log.d(TAG, TRACE + " parseAndDownload.start platform=" + platform + " url=" + shareUrl + " redirect=" + redirectUrl);

        try {
            Log.d(TAG, "WebView parse start: " + platform + " url=" + shareUrl);
            WebViewParser.MediaSource source = new WebViewParser(context, shareUrl).parseMediaSource();
            Log.d(TAG, TRACE + " stage.primaryParse.ok mediaUrl=" + source.getUrl() + " streaming=" + source.isStreaming());
            return VideoDownloader.downloadMedia(context, source);
        } catch (Exception e) {
            webViewError = e;
            Log.w(TAG, "WebView parse failed, fallback to direct mode: " + e.getMessage());
            Log.w(TAG, TRACE + " stage.primaryParse.fail " + e.getMessage());
        }

        if (!redirectUrl.equals(shareUrl)) {
            try {
                Log.d(TAG, "WebView parse retry on resolved redirect url: " + redirectUrl);
                WebViewParser.MediaSource retrySource = new WebViewParser(context, redirectUrl).parseMediaSource();
                Log.d(TAG, TRACE + " stage.redirectParse.ok mediaUrl=" + retrySource.getUrl() + " streaming=" + retrySource.isStreaming());
                return VideoDownloader.downloadMedia(context, retrySource);
            } catch (Exception retryError) {
                if (webViewError != null) {
                    Log.w(TAG, "Retry parse also failed: " + retryError.getMessage());
                }
                Log.w(TAG, TRACE + " stage.redirectParse.fail " + retryError.getMessage());
                webViewError = retryError;
            }
        }

        if (!looksLikeDirectMediaUrl(redirectUrl)) {
            String msg = "WebView parser did not capture a downloadable media URL";
            if (webViewError != null) {
                msg += " (last error: " + webViewError.getMessage() + ")";
            }
            Log.e(TAG, TRACE + " stage.directFallback.skip nonMediaUrl=" + redirectUrl);
            throw new Exception(msg, webViewError);
        }

        try {
            WebViewParser.MediaSource fallbackSource = new WebViewParser.MediaSource(
                    redirectUrl,
                    redirectUrl.toLowerCase().contains(".m3u8"),
                    shareUrl,
                    null,
                    null
            );
            Log.d(TAG, TRACE + " stage.directFallback.start mediaUrl=" + fallbackSource.getUrl());
            return VideoDownloader.downloadMedia(context, fallbackSource);
        } catch (Exception fallbackError) {
            String msg = "Video parse/download failed";
            if (webViewError != null) {
                msg += " | WebView: " + webViewError.getMessage();
            }
            msg += " | Fallback: " + fallbackError.getMessage();
            Log.e(TAG, TRACE + " stage.directFallback.fail " + msg);
            throw new Exception(msg, fallbackError);
        }
    }

    private static boolean looksLikeDirectMediaUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m3u8") ||
                lower.contains(".m4s") || lower.contains(".ts") ||
                lower.contains("/aweme/v1/play") || lower.contains("xhscdn.com/stream");
    }

    public static String parseVideoUrl(Context context, String shareUrl, String platform) throws Exception {
        Log.d(TAG, "Parse media url: " + platform + " " + shareUrl);
        return new WebViewParser(context, shareUrl).parseMediaSource().getUrl();
    }

    private static String resolveFallbackUrl(String shareUrl) {
        try {
            String resolved = HttpUtil.getRedirectUrl(shareUrl);
            Log.d(TAG, TRACE + " redirect.resolve from=" + shareUrl + " to=" + resolved);
            return resolved;
        } catch (Exception ignored) {
            Log.w(TAG, TRACE + " redirect.resolve.fail useOriginal=" + shareUrl);
            return shareUrl;
        }
    }
}
