package com.example.douyinvideo_downloader;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_CODE = 100;
    private static final String TAG = "ClipboardMonitor";
    private static final String CLIPBOARD_UPDATE_ACTION = "com.example.douyinvideo_downloader.CLIPBOARD_UPDATE";

    private ClipboardManager clipboardManager;
    private EditText urlInput;
    private TextView statusText;
    private Button pasteBtn;
    private Button downloadBtn;
    private ProgressBar progressBar;
    private ListView historyList;
    private SwipeRefreshLayout swipeRefresh;
    private Spinner platformSpinner;
    private List<String> historyFiles = new ArrayList<>();
    private ArrayAdapter<String> historyAdapter;
    private ArrayAdapter<CharSequence> platformAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 广播接收器，用于接收剪贴板更新
    private final BroadcastReceiver clipboardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CLIPBOARD_UPDATE_ACTION.equals(intent.getAction())) {
                String clipboardContent = intent.getStringExtra("clipboard_content");
                if (clipboardContent != null) {
                    processClipboardContent(clipboardContent);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化UI组件
        urlInput = findViewById(R.id.urlInput);
        statusText = findViewById(R.id.statusText);
        pasteBtn = findViewById(R.id.pasteBtn);
        downloadBtn = findViewById(R.id.downloadBtn);
        progressBar = findViewById(R.id.progressBar);
        historyList = findViewById(R.id.historyList);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        platformSpinner = findViewById(R.id.platformSpinner);

        // 设置平台选择器
        platformAdapter = ArrayAdapter.createFromResource(this,
                R.array.video_platforms, android.R.layout.simple_spinner_item);
        platformAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platformSpinner.setAdapter(platformAdapter);

        // 设置历史列表适配器
        historyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, historyFiles);
        historyList.setAdapter(historyAdapter);

        // 设置下拉刷新
        swipeRefresh.setOnRefreshListener(this::loadDownloadHistory);

        // 设置历史项目点击事件
        historyList.setOnItemClickListener((parent, view, position, id) -> {
            String fileName = historyFiles.get(position);
            File videoFile = new File(VideoDownloader.getDownloadDirectory(), fileName);
            if (videoFile.exists()) {
                openVideo(videoFile);
            } else {
                Toast.makeText(MainActivity.this, "文件不存在", Toast.LENGTH_SHORT).show();
                loadDownloadHistory();
            }
        });

        // 初始化剪贴板管理器
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        // 粘贴按钮点击事件
        pasteBtn.setOnClickListener(v -> pasteFromClipboard());

        // 下载按钮点击事件
        downloadBtn.setOnClickListener(v -> {
            String inputText = urlInput.getText().toString().trim();
            if (!inputText.isEmpty()) {
                startDownload(inputText);
            } else {
                Toast.makeText(MainActivity.this, "请输入视频链接", Toast.LENGTH_SHORT).show();
            }
        });

        // 输入框文本变化监听
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 自动提取链接
                String extractedUrl = extractVideoUrl(s.toString());
                if (extractedUrl != null && !extractedUrl.equals(urlInput.getText().toString())) {
                    urlInput.setText(extractedUrl);
                    urlInput.setSelection(extractedUrl.length());

                    // 根据URL自动选择平台
                    autoSelectPlatform(extractedUrl);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 检查并请求权限
        if (checkPermissions()) {
            // 加载下载历史
            loadDownloadHistory();
        } else {
            requestPermissions();
        }

        // 启动剪贴板监听服务
        startClipboardMonitor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册广播接收器
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(clipboardReceiver, new IntentFilter(CLIPBOARD_UPDATE_ACTION));

        // 检查剪贴板
        pasteFromClipboard();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册广播接收器
        LocalBroadcastManager.getInstance(this).unregisterReceiver(clipboardReceiver);
    }

    private void startClipboardMonitor() {
        Intent serviceIntent = new Intent(this, ClipboardMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void processClipboardContent(String content) {
        String extractedUrl = extractVideoUrl(content);
        if (extractedUrl != null) {
            // 避免重复设置相同的文本
            if (!extractedUrl.equals(urlInput.getText().toString())) {
                runOnUiThread(() -> {
                    urlInput.setText(extractedUrl);
                    urlInput.setSelection(extractedUrl.length());
                    autoSelectPlatform(extractedUrl);
                    Toast.makeText(this, "已从剪贴板获取链接", Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    private void autoSelectPlatform(String url) {
        if (url.contains("douyin") || url.contains("iesdouyin")) {
            platformSpinner.setSelection(0); // 抖音
        } else if (url.contains("bilibili")) {
            platformSpinner.setSelection(1); // B站
        } else if (url.contains("kuaishou") || url.contains("gifshow")) {
            platformSpinner.setSelection(2); // 快手
        } else if (url.contains("ixigua") || url.contains("toutiao")) {
            platformSpinner.setSelection(3); // 西瓜视频
        } else {
            platformSpinner.setSelection(4); // 其他平台
        }
    }

    private void pasteFromClipboard() {
        if (clipboardManager == null) return;

        if (clipboardManager.hasPrimaryClip()) {
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                ClipData.Item item = clipData.getItemAt(0);
                if (item.getText() != null) {
                    String pasteData = item.getText().toString();
                    processClipboardContent(pasteData);
                }
            }
        }
    }

    private String extractVideoUrl(String text) {
        if (text == null || text.isEmpty()) return null;

        // 匹配多个视频平台的链接
        String regex = "https?:\\/\\/(?:[\\w-]+\\.)?(?:douyin|iesdouyin|bilibili|kuaishou|gifshow|ixigua|toutiao)\\.\\S+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String foundUrl = matcher.group();

            // 清理URL参数
            if (foundUrl.contains("?")) {
                foundUrl = foundUrl.substring(0, foundUrl.indexOf("?"));
            }

            // 移除可能的结尾标点
            foundUrl = foundUrl.replaceAll("[.,;!?]+$", "");

            return foundUrl;
        }
        return null;
    }

    private void startDownload(String inputText) {
        String extractedUrl = extractVideoUrl(inputText);
        if (extractedUrl == null) {
            Toast.makeText(this, "未找到有效的视频链接", Toast.LENGTH_SHORT).show();
            return;
        }

        String selectedPlatform = platformSpinner.getSelectedItem().toString();

        progressBar.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("正在解析视频地址...");
        downloadBtn.setEnabled(false);
        pasteBtn.setEnabled(false);

        executor.execute(() -> {
            try {
                // 使用通用解析器解析真实视频地址
                String videoUrl = VideoParser.parseVideoUrl(MainActivity.this, extractedUrl, selectedPlatform);

                if (videoUrl == null || videoUrl.isEmpty()) {
                    throw new Exception("无法解析视频地址");
                }

                Log.d(TAG, "获取到视频地址: " + videoUrl);

                mainHandler.post(() -> {
                    statusText.setText("正在下载视频...");
                });

                // 下载视频
                File videoFile = VideoDownloader.downloadVideo(MainActivity.this, videoUrl);

                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    downloadBtn.setEnabled(true);
                    pasteBtn.setEnabled(true);
                    Toast.makeText(MainActivity.this,
                            "下载完成: " + videoFile.getName(),
                            Toast.LENGTH_LONG).show();

                    // 清空输入框
                    urlInput.setText("");

                    // 更新历史列表
                    loadDownloadHistory();
                });

            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    downloadBtn.setEnabled(true);
                    pasteBtn.setEnabled(true);
                    Toast.makeText(MainActivity.this,
                            "下载失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadDownloadHistory() {
        historyFiles.clear();
        File downloadDir = VideoDownloader.getDownloadDirectory();
        if (downloadDir.exists() && downloadDir.isDirectory()) {
            File[] files = downloadDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp4")) {
                        historyFiles.add(file.getName());
                    }
                }
                // 按修改时间倒序排序
                Collections.sort(historyFiles, (f1, f2) -> {
                    File file1 = new File(downloadDir, f1);
                    File file2 = new File(downloadDir, f2);
                    return Long.compare(file2.lastModified(), file1.lastModified());
                });
            }
        }
        historyAdapter.notifyDataSetChanged();
        swipeRefresh.setRefreshing(false);
    }

    private void openVideo(File videoFile) {
        try {
            Uri contentUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider",
                    videoFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法播放视频: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return true; // Android 11+ 使用分区存储，不需要额外权限
        } else {
            int writePermission = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return writePermission == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 不需要请求存储权限
            loadDownloadHistory();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadDownloadHistory();
            } else {
                Toast.makeText(this, "需要存储权限才能保存视频", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        // 停止剪贴板监听服务
        stopService(new Intent(this, ClipboardMonitorService.class));
    }
}