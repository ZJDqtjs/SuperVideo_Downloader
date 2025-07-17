package com.example.douyinvideo_downloader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class WebViewParser {

    private static final String TAG = "WebViewParser";
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<String> videoUrlRef = new AtomicReference<>();
    private final CountDownLatch latch = new CountDownLatch(1);
    private final Set<String> candidateUrls = new HashSet<>();
    private static final int MAX_JS_RETRY = 3;
    private int jsRetryCount = 0;
    private final String originalUrl;
    private WebView webView; // Add reference to properly clean up

    // 平台视频URL正则匹配器
    private static final Pattern DOUYIN_PATTERN = Pattern.compile("https://v[\\d]+-web\\.douyinvod\\.com/.*");
    private static final Pattern KUAISHOU_PATTERN = Pattern.compile("https://v[\\d]+\\.kwaicdn\\.com/ksc2/.*");
    private static final Pattern BILIBILI_PATTERN = Pattern.compile("https://[\\w\\d\\.]+\\.mcdn\\.bilivideo\\.cn:\\d+/.*");

    public WebViewParser(Context context, String shareUrl) {
        this.context = context;
        this.originalUrl = shareUrl;
    }

    public String parseVideoUrl() throws Exception {
        try {
            mainHandler.post(() -> {
                try {
                    webView = new WebView(context);
                    webView.getSettings().setJavaScriptEnabled(true);
                    webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
                    webView.getSettings().setDomStorageEnabled(true);
                    webView.getSettings().setDatabaseEnabled(true);
                    webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36");

                    // 添加JavaScript接口
                    webView.addJavascriptInterface(new JSInterface(), "AndroidParser");

                    // 设置自定义WebViewClient
                    webView.setWebViewClient(new VideoWebViewClient());

                    // 加载URL
                    if (originalUrl != null && !originalUrl.trim().isEmpty()) {
                        webView.loadUrl(originalUrl);
                    } else {
                        Log.e(TAG, "Original URL is null or empty");
                        latch.countDown();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error creating WebView", e);
                    latch.countDown();
                }
            });

            // 等待获取结果
            if (latch.await(30, TimeUnit.SECONDS)) {
                String finalUrl = videoUrlRef.get();
                if (finalUrl != null && !finalUrl.isEmpty()) {
                    return finalUrl;
                }
            }

            // 超时后尝试从候选URL中选择最佳
            if (!candidateUrls.isEmpty()) {
                return selectBestCandidate();
            }

            throw new Exception("解析超时，无法获取视频地址");
        } finally {
            // 确保WebView被清理
            cleanupWebView();
        }
    }

    private void cleanupWebView() {
        mainHandler.post(() -> {
            if (webView != null) {
                try {
                    webView.stopLoading();
                    webView.clearHistory();
                    webView.clearCache(true);
                    webView.loadUrl("about:blank");
                    webView.removeJavascriptInterface("AndroidParser");
                    webView.setWebViewClient(null);
                    webView.setWebChromeClient(null);
                    webView.destroy();
                    webView = null;
                    Log.d(TAG, "WebView cleaned up successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Error cleaning up WebView", e);
                }
            }
        });
    }

    private String selectBestCandidate() {
        // 优先匹配平台特征URL
        for (String url : candidateUrls) {
            if (isPlatformVideoUrl(url)) {
                Log.d(TAG, "从候选中选择平台特征URL: " + url);
                return url;
            }
        }

        // 其次选择.mp4直链
        for (String url : candidateUrls) {
            if (url.contains(".mp4") && !url.contains("?x-expires")) {
                Log.d(TAG, "从候选中选择MP4直链: " + url);
                return url;
            }
        }

        // 最后返回任意候选
        String fallback = candidateUrls.iterator().next();
        Log.w(TAG, "无最佳候选，返回任意URL: " + fallback);
        return fallback;
    }

    private boolean isPlatformVideoUrl(String url) {
        // 抖音匹配: https://v3-web.douyinvod.com/...
        if ((originalUrl.contains("douyin") || url.contains("douyin"))) {
            if (DOUYIN_PATTERN.matcher(url).matches()) {
                return true;
            }
        }

        // 快手匹配: https://v1.kwaicdn.com/ksc2/...
        if ((originalUrl.contains("kuaishou") || url.contains("kuaishou"))) {
            if (KUAISHOU_PATTERN.matcher(url).matches()) {
                return true;
            }
        }

        // 哔哩哔哩匹配: https://xy118x213x14ux138xy.mcdn.bilivideo.cn:4483/...
        if ((originalUrl.contains("bilibili") || url.contains("bilibili"))) {
            if (BILIBILI_PATTERN.matcher(url).matches()) {
                return true;
            }
        }

        return false;
    }

    private class VideoWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();

            // 识别并收集可能的视频资源
            if (isPotentialVideoUrl(url)) {
                synchronized (candidateUrls) {
                    candidateUrls.add(url);
                }

                // 如果匹配平台特征URL，立即作为结果
                if (isPlatformVideoUrl(url)) {
                    Log.d(TAG, "拦截到平台特征视频资源: " + url);
                    synchronized (videoUrlRef) {
                        if (videoUrlRef.get() == null) {
                            videoUrlRef.set(url);
                            latch.countDown();
                        }
                    }
                } else {
                    Log.d(TAG, "发现候选视频资源: " + url);
                }
            }

            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "页面加载完成: " + url);

            // 延迟执行JS，等待动态内容加载
            mainHandler.postDelayed(() -> injectVideoDetectionJS(view, url), 1500);
        }

        private void injectVideoDetectionJS(WebView view, String pageUrl) {
            jsRetryCount++;
            Log.d(TAG, "注入视频检测JS (尝试: " + jsRetryCount + ")");

            // 根据不同平台优化JS选择器
            String jsCode = buildDetectionJS(pageUrl);

            view.evaluateJavascript(jsCode, result -> {
                if (result != null && !result.equals("null") && !result.isEmpty()) {
                    try {
                        String videoUrl = result.replace("\"", "");
                        if (isValidVideoUrl(videoUrl)) {
                            Log.d(TAG, "JS检测到视频地址: " + videoUrl);

                            // 如果是平台特征URL或高质量视频，优先使用
                            if (isPlatformVideoUrl(videoUrl) ||
                                    videoUrl.contains(".mp4")) {
                                synchronized (videoUrlRef) {
                                    if (videoUrlRef.get() == null) {
                                        videoUrlRef.set(videoUrl);
                                        latch.countDown();
                                    }
                                }
                            } else {
                                // 添加到候选集
                                synchronized (candidateUrls) {
                                    candidateUrls.add(videoUrl);
                                }
                            }
                        } else if (jsRetryCount < MAX_JS_RETRY) {
                            Log.w(TAG, "无效视频地址，重试中: " + videoUrl);
                            mainHandler.postDelayed(() -> injectVideoDetectionJS(view, pageUrl), 1000);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "处理JS结果出错", e);
                    }
                } else if (jsRetryCount < MAX_JS_RETRY) {
                    Log.w(TAG, "未检测到视频元素，重试中...");
                    mainHandler.postDelayed(() -> injectVideoDetectionJS(view, pageUrl), 1000);
                }
            });
        }



        private String buildDetectionJS(String pageUrl) {
            // 抖音特殊处理 - 可能需要查找特定ID的视频
            if (pageUrl.contains("douyin") || originalUrl.contains("douyin")) {
                return "(function() {" +
                        "   try {" +
                        "       var video = document.querySelector('video[id^=\"douyin\"]') || " +
                        "                  document.querySelector('video');" +
                        "       if (video && video.src) return video.src;" +
                        "   } catch (e) {" +
                        "       console.error('抖音视频检测错误:', e);" +
                        "   }" +
                        "   return null;" +
                        "})();";
            }

            // 快手特殊处理 - 查找特定类名的视频
            if (pageUrl.contains("kuaishou") || originalUrl.contains("kuaishou")) {
                return "(function() {" +
                        "   try {" +
                        "       var video = document.querySelector('video.player-video') || " +
                        "                  document.querySelector('video');" +
                        "       if (video && video.src) return video.src;" +
                        "   } catch (e) {" +
                        "       console.error('快手视频检测错误:', e);" +
                        "   }" +
                        "   return null;" +
                        "})();";
            }

            // 哔哩哔哩特殊处理 - 查找播放器中的视频
            if (pageUrl.contains("bilibili") || originalUrl.contains("bilibili")) {
                return "(function() {" +
                        "   try {" +
                        "       var player = document.querySelector('.bpx-player-video-wrap video') || " +
                        "                   document.querySelector('video');" +
                        "       if (player && player.src) return player.src;" +
                        "   } catch (e) {" +
                        "       console.error('B站视频检测错误:', e);" +
                        "   }" +
                        "   return null;" +
                        "})();";
            }

            // 通用检测
            return "(function() {" +
                    "   try {" +
                    "       var videos = document.querySelectorAll('video');" +
                    "       var maxSize = 0;" +
                    "       var mainVideoUrl = '';" +
                    "       " +
                    "       for (var i = 0; i < videos.length; i++) {" +
                    "           var v = videos[i];" +
                    "           if (v.src && v.readyState > 0) {" +
                    "               var area = v.videoWidth * v.videoHeight;" +
                    "               if (area > maxSize) {" +
                    "                   maxSize = area;" +
                    "                   mainVideoUrl = v.src;" +
                    "               }" +
                    "           }" +
                    "       }" +
                    "       " +
                    "       if (mainVideoUrl) return mainVideoUrl;" +
                    "   } catch (e) {" +
                    "       console.error('通用视频检测错误:', e);" +
                    "   }" +
                    "   return null;" +
                    "})();";
        }

        private boolean isPotentialVideoUrl(String url) {
            if (url == null || url.trim().isEmpty()) {
                return false;
            }
            try {
                return url.contains(".mp4") ||
                        url.contains(".m3u8") ||
                        url.contains(".ts") ||
                        url.contains("video") ||
                        url.contains("stream") ||
                        url.contains("play") ||
                        url.contains("media") ||
                        url.contains("aweme") ||
                        url.contains("mime=video");
            } catch (Exception e) {
                Log.e(TAG, "检查潜在视频URL时出错", e);
                return false;
            }
        }

        private boolean isValidVideoUrl(String url) {
            if (url == null || url.trim().isEmpty()) {
                return false;
            }
            try {
                return url.startsWith("http") &&
                        (url.contains(".mp4") ||
                                url.contains(".m3u8") ||
                                url.contains("video") ||
                                url.contains("stream"));
            } catch (Exception e) {
                Log.e(TAG, "验证视频URL时出错", e);
                return false;
            }
        }


    }

    private class JSInterface {
        @JavascriptInterface
        public void onVideoUrlFound(String url) {
            try {
                Log.d(TAG, "JS接口获取到视频地址: " + url);
                if (url != null && !url.trim().isEmpty() && url.startsWith("http")) {
                    synchronized (videoUrlRef) {
                        if (videoUrlRef.get() == null) {
                            videoUrlRef.set(url.trim());
                            latch.countDown();
                        }
                    }
                } else {
                    Log.w(TAG, "JS接口获取到无效的URL: " + url);
                }
            } catch (Exception e) {
                Log.e(TAG, "JS接口处理URL时出错", e);
            }
        }
    }
}