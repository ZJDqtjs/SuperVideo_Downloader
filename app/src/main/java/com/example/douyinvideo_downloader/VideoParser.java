package com.example.douyinvideo_downloader;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;

public class VideoParser {
    private static final String TAG = "VideoParser";

    public static File parseAndDownload(Context context, String shareUrl, String platform) throws Exception {
        Log.d(TAG, "开始解析并下载" + platform + "视频: " + shareUrl);

        try {
            // 1. 解析视频URL
            String videoUrl = parseVideoUrl(context, shareUrl, platform);
            Log.i(TAG, platform + "视频地址解析成功: " + videoUrl);

            // 2. 下载视频文件
            File videoFile = VideoDownloader.downloadVideo(context, videoUrl);
            Log.i(TAG, platform + "视频下载完成: " + videoFile.getAbsolutePath());

            return videoFile;

        } catch (Exception e) {
            Log.e(TAG, platform + "视频处理失败: " + e.getMessage());
            throw new Exception(platform + "视频处理失败: " + e.getMessage(), e);
        }
    }

    public static String parseVideoUrl(Context context, String shareUrl, String platform) throws Exception {
        Log.d(TAG, "开始解析" + platform + "视频URL: " + shareUrl);
        try {
            WebViewParser parser = new WebViewParser(context, shareUrl);
            String videoUrl = parser.parseVideoUrl();
            Log.i(TAG, platform + "视频URL解析成功: " + videoUrl);
            return videoUrl;
        } catch (Exception e) {
            Log.e(TAG, platform + "视频URL解析失败: " + e.getMessage());
            throw new Exception(platform + "视频URL解析失败: " + e.getMessage(), e);
        }
    }
}