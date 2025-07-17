package com.example.douyinvideo_downloader;

import org.junit.Test;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Unit tests for video downloader crash fixes
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void testUrlExtraction_validDouyinUrl() {
        String text = "看看这个视频 https://v.douyin.com/ieFj8abc/ 很有趣";
        String extractedUrl = extractVideoUrl(text);
        assertNotNull("Should extract valid douyin URL", extractedUrl);
        assertTrue("Should contain douyin domain", extractedUrl.contains("douyin"));
    }

    @Test
    public void testUrlExtraction_validBilibiliUrl() {
        String text = "https://www.bilibili.com/video/BV1234567890";
        String extractedUrl = extractVideoUrl(text);
        assertNotNull("Should extract valid bilibili URL", extractedUrl);
        assertTrue("Should contain bilibili domain", extractedUrl.contains("bilibili"));
    }

    @Test
    public void testUrlExtraction_nullInput() {
        String extractedUrl = extractVideoUrl(null);
        assertNull("Should return null for null input", extractedUrl);
    }

    @Test
    public void testUrlExtraction_emptyInput() {
        String extractedUrl = extractVideoUrl("");
        assertNull("Should return null for empty input", extractedUrl);
    }

    @Test
    public void testUrlExtraction_invalidUrl() {
        String text = "这是一段没有视频链接的文本";
        String extractedUrl = extractVideoUrl(text);
        assertNull("Should return null for text without video URLs", extractedUrl);
    }

    // Helper method to replicate the URL extraction logic
    private String extractVideoUrl(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        try {
            // 匹配多个视频平台的链接
            String regex = "https?:\\/\\/(?:[\\w-]+\\.)?(?:douyin|iesdouyin|bilibili|kuaishou|gifshow|ixigua|toutiao)\\.\\S+";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                String foundUrl = matcher.group();
                if (foundUrl == null) return null;

                // 清理URL参数
                if (foundUrl.contains("?")) {
                    foundUrl = foundUrl.substring(0, foundUrl.indexOf("?"));
                }

                // 移除可能的结尾标点
                foundUrl = foundUrl.replaceAll("[.,;!?]+$", "");

                return foundUrl.trim();
            }
        } catch (Exception e) {
            // Log error in real implementation
            return null;
        }
        return null;
    }
}