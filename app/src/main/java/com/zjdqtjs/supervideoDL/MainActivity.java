package com.zjdqtjs.supervideoDL;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.FrameLayout;
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

    private ClipboardManager clipboardManager;
    private String lastClipboardUrl = "";
    private EditText urlInput;
    private TextView statusText;
    private Button pasteBtn;
    private Button downloadBtn;
    private ProgressBar progressBar;
    private FrameLayout sniffWebViewContainer;
    private ListView historyList;
    private SwipeRefreshLayout swipeRefresh;
    private Spinner platformSpinner;
    private List<String> historyFiles = new ArrayList<>();
    private ArrayAdapter<String> historyAdapter;
    private ArrayAdapter<CharSequence> platformAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChangedListener =
            this::pasteFromClipboard;

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
        sniffWebViewContainer = findViewById(R.id.sniffWebViewContainer);
        historyList = findViewById(R.id.historyList);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        platformSpinner = findViewById(R.id.platformSpinner);

        // 提供可见WebView容器，便于用户手动点击播放触发真实视频流请求
        WebViewParser.setPreviewContainer(sniffWebViewContainer);

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

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipboardChangedListener);
        }

        // 检查剪贴板
        pasteFromClipboard();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardChangedListener);
        }
    }

    private void processClipboardContent(String content) {
        String extractedUrl = extractVideoUrl(content);
        if (extractedUrl != null) {
            // 避免重复设置相同的文本
            if (!extractedUrl.equals(urlInput.getText().toString()) && !extractedUrl.equals(lastClipboardUrl)) {
                lastClipboardUrl = extractedUrl;
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
        if (url == null || url.isEmpty()) {
            platformSpinner.setSelection(4);
            return;
        }

        String lowerUrl = url.toLowerCase();
        String host = "";
        try {
            Uri uri = Uri.parse(url);
            if (uri.getHost() != null) {
                host = uri.getHost().toLowerCase();
            }
        } catch (Exception ignored) {
        }

        if (lowerUrl.contains("douyin") || lowerUrl.contains("iesdouyin") ||
                host.contains("douyin.com")) {
            platformSpinner.setSelection(0); // 抖音
        } else if (lowerUrl.contains("bilibili") || host.contains("b23.tv") ||
                host.contains("bili22.cn") || host.contains("bili23.cn") ||
                host.contains("bili33.cn") || host.contains("bili2233.cn") ||
                host.contains("bilivideo.com")) {
            platformSpinner.setSelection(1); // B站
        } else if (lowerUrl.contains("kuaishou") || lowerUrl.contains("gifshow") ||
                host.contains("kuaishou.com")) {
            platformSpinner.setSelection(2); // 快手
        } else if (lowerUrl.contains("ixigua") || lowerUrl.contains("toutiao") ||
                host.contains("ixigua.com")) {
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

        // 匹配任意HTTP(S)链接，兼容分享中转域名
        String regex = "https?:\\/\\/[^\\s]+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String foundUrl = matcher.group();

            // 移除可能的结尾标点
            foundUrl = foundUrl.replaceAll("[\\),.;!?]+$", "");

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
        statusText.setText("正在解析视频地址... 如未自动播放，可在下方预览窗口手动点击播放");
        downloadBtn.setEnabled(false);
        pasteBtn.setEnabled(false);

        executor.execute(() -> {
            try {
                mainHandler.post(() -> {
                    statusText.setText("正在通过WebView实时解析并下载...");
                });

                File videoFile = VideoParser.parseAndDownload(MainActivity.this, extractedUrl, selectedPlatform);

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
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardChangedListener);
        }
        WebViewParser.setPreviewContainer(null);
    }
}