package com.zjdqtjs.supervideoDL;

import android.content.Context;
import android.util.Log;

import java.io.File;

public class VideoParser {
    private static final String TAG = "VideoParser";

    public static File parseAndDownload(Context context, String shareUrl, String platform) throws Exception {
        Exception webViewError = null;
        String redirectUrl = resolveFallbackUrl(shareUrl);

        try {
            Log.d(TAG, "WebView parse start: " + platform + " url=" + shareUrl);
            WebViewParser.MediaSource source = new WebViewParser(context, shareUrl).parseMediaSource();
            return VideoDownloader.downloadMedia(context, source);
        } catch (Exception e) {
            webViewError = e;
            Log.w(TAG, "WebView parse failed, fallback to direct mode: " + e.getMessage());
        }

        if (!redirectUrl.equals(shareUrl)) {
            try {
                Log.d(TAG, "WebView parse retry on resolved redirect url: " + redirectUrl);
                WebViewParser.MediaSource retrySource = new WebViewParser(context, redirectUrl).parseMediaSource();
                return VideoDownloader.downloadMedia(context, retrySource);
            } catch (Exception retryError) {
                if (webViewError != null) {
                    Log.w(TAG, "Retry parse also failed: " + retryError.getMessage());
                }
                webViewError = retryError;
            }
        }

        try {
            WebViewParser.MediaSource fallbackSource = new WebViewParser.MediaSource(
                    redirectUrl,
                    redirectUrl.toLowerCase().contains(".m3u8"),
                    shareUrl,
                    null,
                    null
            );
            return VideoDownloader.downloadMedia(context, fallbackSource);
        } catch (Exception fallbackError) {
            String msg = "Video parse/download failed";
            if (webViewError != null) {
                msg += " | WebView: " + webViewError.getMessage();
            }
            msg += " | Fallback: " + fallbackError.getMessage();
            throw new Exception(msg, fallbackError);
        }
    }

    public static String parseVideoUrl(Context context, String shareUrl, String platform) throws Exception {
        Log.d(TAG, "Parse media url: " + platform + " " + shareUrl);
        return new WebViewParser(context, shareUrl).parseMediaSource().getUrl();
    }

    private static String resolveFallbackUrl(String shareUrl) {
        try {
            return HttpUtil.getRedirectUrl(shareUrl);
        } catch (Exception ignored) {
            return shareUrl;
        }
    }
}
