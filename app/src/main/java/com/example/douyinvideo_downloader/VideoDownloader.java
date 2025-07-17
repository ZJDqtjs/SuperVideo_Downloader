package com.example.douyinvideo_downloader;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VideoDownloader {
    private static final String TAG = "VideoDownloader";
    private static final String DOWNLOAD_SUBDIR = "DouyinDL";

    public static File downloadVideo(Context context, String videoUrl) throws Exception {
        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            throw new Exception("视频URL为空");
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
                
        Request request = new Request.Builder()
                .url(videoUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                .build();

        Response response = null;
        try {
            response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                throw new Exception("下载失败: HTTP " + response.code() + " " + response.message());
            }

            if (response.body() == null) {
                throw new Exception("响应体为空");
            }

            // 创建文件名
            String fileName = "video_" + System.currentTimeMillis() + ".mp4";

            // 保存文件到公共下载目录
            return saveToPublicDownloads(response, fileName);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private static File saveToPublicDownloads(Response response, String fileName) throws IOException {
        // 获取公共下载目录下的子目录
        File downloadDir = getDownloadDirectory();

        // 确保目录存在
        if (!downloadDir.exists()) {
            boolean created = downloadDir.mkdirs();
            if (!created) {
                throw new IOException("无法创建下载目录: " + downloadDir.getAbsolutePath());
            }
        }

        // 创建目标文件
        File outputFile = new File(downloadDir, fileName);
        
        if (response.body() == null) {
            throw new IOException("响应体为空，无法下载文件");
        }

        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        
        try {
            inputStream = response.body().byteStream();
            outputStream = new FileOutputStream(outputFile);

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
            
            outputStream.flush();
            
        } catch (IOException e) {
            // 删除可能的不完整文件
            if (outputFile.exists()) {
                boolean deleted = outputFile.delete();
                if (!deleted) {
                    Log.w(TAG, "无法删除不完整的文件: " + outputFile.getAbsolutePath());
                }
            }
            throw new IOException("文件写入失败: " + e.getMessage(), e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "关闭输入流失败", e);
            }
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "关闭输出流失败", e);
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

