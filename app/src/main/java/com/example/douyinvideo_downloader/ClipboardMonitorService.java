package com.example.douyinvideo_downloader;

import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ClipboardMonitorService extends Service implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardMonitor";
    private static final String CLIPBOARD_UPDATE_ACTION = "com.example.douyinvideo_downloader.CLIPBOARD_UPDATE";
    private static final long MIN_UPDATE_INTERVAL = 5000; // 5秒

    private ClipboardManager clipboardManager;
    private long lastClipboardUpdateTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(this);
        }
        Log.d(TAG, "剪贴板监听服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onPrimaryClipChanged() {
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