package com.fish.novel;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class LegadoUtil {

    private static final Logger LOG = Logger.getInstance(LegadoUtil.class);

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Gson gson = new Gson();

    private static String getBaseUrl() {
        String url = NovelConfig.getInstance().getLegadoUrl();
        if (!url.startsWith("http")) return "http://" + url;
        return url;
    }

    public record Book(String name, String author, String bookUrl, String coverUrl,
                       int durChapterIndex, int durChapterPos, long durChapterTime, int totalChapterNum) {}

    public record Chapter(String title, int index, String url) {}

    public record ChapterContent(String title, String content, int index) {}

    public static Optional<Book> findBookByName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        String json = sendRequest("/getBookshelf");
        List<Book> books = parseDataList(json, Book.class);
        return books.stream()
                .filter(b -> b.name() != null && b.name().contains(name))
                .findFirst();
    }

    public static List<Chapter> getChapterList(Book book) {
        String encodedUrl = encode(book.bookUrl());
        String json = sendRequest("/getChapterList?url=" + encodedUrl);
        return parseDataList(json, Chapter.class);
    }

    public static Optional<ChapterContent> getBookContent(Book book, int index) {
        String encodedUrl = encode(book.bookUrl());
        String url = "/getBookContent?url=%s&index=%d".formatted(encodedUrl, index);
        String json = sendRequest(url);

        if (json == null) return Optional.empty();
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            JsonElement target = root.has("data") ? root.get("data") : root;

            String contentStr;
            if (target.isJsonPrimitive()) {
                contentStr = target.getAsString();
            } else if (target.isJsonObject() && target.getAsJsonObject().has("content")) {
                contentStr = target.getAsJsonObject().get("content").getAsString();
            } else {
                contentStr = target.toString(); // Fallback
            }
            return Optional.of(new ChapterContent(null, contentStr, index));
        } catch (Exception e) {
            LOG.warn("Failed to parse chapter content for index " + index, e);
            return Optional.empty();
        }
    }

    public static boolean saveProgress(Book book, int durChapterIndex, int durChapterPos, String durChapterTitle) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("name", book.name());
            payload.put("author", book.author());
            payload.put("durChapterIndex", durChapterIndex);
            payload.put("durChapterPos", durChapterPos);
            payload.put("durChapterTitle", durChapterTitle);
            payload.put("durChapterTime", System.currentTimeMillis());
            payload.put("url", book.bookUrl());

            String jsonBody = gson.toJson(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseUrl() + "/saveBookProgress"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("Failed to save reading progress, status code: " + response.statusCode());
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to save reading progress for book: " + book.name(), e);
            return false;
        }
    }

    private static String sendRequest(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseUrl() + path))
                    .header("User-Agent", "LegadoJavaClient")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            LOG.warn("Legado request failed, path: " + path + ", status code: " + response.statusCode());
            return null;
        } catch (Exception e) {
            LOG.warn("Legado request failed, path: " + path, e);
            return null;
        }
    }

    private static <T> List<T> parseDataList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            JsonElement root = gson.fromJson(json, JsonElement.class);
            JsonElement array = root.isJsonObject() && root.getAsJsonObject().has("data")
                    ? root.getAsJsonObject().get("data")
                    : root;
            if (array.isJsonArray()) {
                return gson.fromJson(array, TypeToken.getParameterized(List.class, clazz).getType());
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse Legado list response for type: " + clazz.getSimpleName(), e);
        }
        return Collections.emptyList();
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
