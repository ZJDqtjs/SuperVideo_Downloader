package com.example.douyinvideo_downloader;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class HttpUtil {
    private static final OkHttpClient client = new OkHttpClient();
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36";

    public static String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: " + response.code());
            }
            return response.body().string();
        }
    }

    public static String getRedirectUrl(String url) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isRedirect() && response.header("Location") != null) {
                return response.header("Location");
            }
            return url;
        }
    }
}