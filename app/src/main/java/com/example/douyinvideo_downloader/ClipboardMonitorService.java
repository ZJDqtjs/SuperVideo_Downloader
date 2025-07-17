package com.example.douyinvideo_downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ClipboardMonitorService extends Service implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardMonitor";
    private static final String CLIPBOARD_UPDATE_ACTION = "com.example.douyinvideo_downloader.CLIPBOARD_UPDATE";
    private static final long MIN_UPDATE_INTERVAL = 5000; // 5秒
    private static final String CHANNEL_ID = "clipboard_monitor_channel";
    private static final int NOTIFICATION_ID = 1;

    private ClipboardManager clipboardManager;
    private long lastClipboardUpdateTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 创建通知渠道（Android 8.0+）
        createNotificationChannel();
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());
        
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(this);
            Log.d(TAG, "剪贴板监听服务已启动");
        } else {
            Log.e(TAG, "无法获取剪贴板管理器");
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "视频下载服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("监听剪贴板中的视频链接");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("视频下载器运行中")
                .setContentText("正在监听剪贴板中的视频链接")
                .setSmallIcon(android.R.drawable.ic_menu_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSound(null)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onPrimaryClipChanged() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastClipboardUpdateTime < MIN_UPDATE_INTERVAL) {
                return; // 避免频繁触发
            }
            lastClipboardUpdateTime = currentTime;

            if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    ClipData.Item item = clipData.getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null) {
                        String clipboardContent = text.toString();
                        Log.d(TAG, "剪贴板内容更新: " + clipboardContent);

                        // 发送广播通知Activity
                        Intent intent = new Intent(CLIPBOARD_UPDATE_ACTION);
                        intent.putExtra("clipboard_content", clipboardContent);
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "处理剪贴板变化时出错", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(this);
        }
        Log.d(TAG, "剪贴板监听服务已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}