package com.example.douyinvideo_downloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class JsonUtil {
    public static String extractFromJson(String json, String path) {
        try {
            JsonElement root = JsonParser.parseString(json);
            String[] keys = path.split("\\.");

            JsonElement current = root;
            for (String key : keys) {
                if (current == null || current.isJsonNull()) {
                    return null;
                }

                if (key.contains("[")) {
                    // 处理数组索引
                    String arrayKey = key.substring(0, key.indexOf('['));
                    int index = Integer.parseInt(key.substring(key.indexOf('[') + 1, key.indexOf(']')));

                    if (current.isJsonObject()) {
                        JsonObject obj = current.getAsJsonObject();
                        current = obj.get(arrayKey).getAsJsonArray().get(index);
                    }
                } else {
                    if (current.isJsonObject()) {
                        current = current.getAsJsonObject().get(key);
                    }
                }
            }

            return current != null ? current.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}