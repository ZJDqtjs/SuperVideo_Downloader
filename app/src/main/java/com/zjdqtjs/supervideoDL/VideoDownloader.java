package com.zjdqtjs.supervideoDL;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VideoDownloader {
    private static final String TAG = "VideoDownloader";
    private static final String DOWNLOAD_SUBDIR = "DouyinDL";

    public static File downloadVideo(Context context, String videoUrl) throws Exception {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(videoUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("下载失败: " + response.code());
            }

            // 创建文件名
            String fileName = "video_" + System.currentTimeMillis() + ".mp4";

            // 保存文件到公共下载目录
            return saveToPublicDownloads(response, fileName);
        }
    }

    private static File saveToPublicDownloads(Response response, String fileName) throws IOException {
        // 获取公共下载目录下的子目录
        File downloadDir = getDownloadDirectory();

        // 确保目录存在
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw new IOException("无法创建下载目录: " + downloadDir.getAbsolutePath());
        }

        // 创建目标文件
        File outputFile = new File(downloadDir, fileName);

        try (InputStream inputStream = response.body().byteStream();
             FileOutputStream outputStream = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            long contentLength = response.body().contentLength();

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                // 记录下载进度
                if (contentLength > 0) {
                    int progress = (int) ((totalBytesRead * 100) / contentLength);
                    Log.d(TAG, "下载进度: " + progress + "%");
                }
            }
        }

        Log.d(TAG, "视频下载完成: " + outputFile.getAbsolutePath());
        return outputFile;
    }

    /**
     * 获取下载目录
     */
    public static File getDownloadDirectory() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBDIR
        );
    }
}

