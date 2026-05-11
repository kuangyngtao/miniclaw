package com.miniclaw.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaw.tools.Result;
import com.miniclaw.tools.Tool;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final int MAX_OUTPUT_CHARS = 8000;
    private static final long CACHE_TTL_MS = 15 * 60 * 1000;
    private static final int MAX_CACHE_SIZE = 50;
    private static final String USER_AGENT = "miniclaw/0.1.0";

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "url": {"type": "string", "description": "要抓取的网页 URL"},
            "prompt": {"type": "string", "description": "描述要从页面中提取什么信息"}
          },
          "required": ["url", "prompt"]
        }""";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public WebFetchTool() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "从指定 URL 抓取网页内容并提取为纯文本。"
            + "用于查阅在线文档、API 参考、GitHub issue 等。"
            + "url: 要抓取的网页地址; prompt: 描述需要提取什么信息。"
            + "结果会被截断为 " + MAX_OUTPUT_CHARS + " 字符。"
            + "相同 URL 在 15 分钟内会命中缓存。";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Result<String> execute(String arguments) {
        // --- 解析参数 ---
        String url;
        String prompt;
        try {
            JsonNode node = mapper.readTree(arguments);
            if (node == null || !node.has("url") || node.get("url").asText().isBlank()) {
                return err("T-002", "缺少必需参数 'url'");
            }
            url = node.get("url").asText().trim();
            if (!node.has("prompt") || node.get("prompt").asText().isBlank()) {
                return err("T-002", "缺少必需参数 'prompt'");
            }
            prompt = node.get("prompt").asText().trim();
        } catch (JsonProcessingException e) {
            return err("T-002", "参数 JSON 解析失败: " + e.getMessage());
        }

        log.info("[WebFetch] url={}, prompt={}", url, prompt);

        // --- 验证 URL ---
        URI uri;
        try {
            uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return err("T-002", "无效的 URL: " + url);
            }
            String scheme = uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return err("T-002", "仅支持 http/https 协议: " + scheme);
            }
        } catch (URISyntaxException e) {
            return err("T-002", "无效的 URL: " + url);
        }

        // --- 检查缓存 ---
        CacheEntry cached = cache.get(url);
        if (cached != null) {
            if (!cached.isExpired()) {
                log.info("[WebFetch] 缓存命中: {}", url);
                return new Result.Ok<>(truncate(cached.content));
            }
            cache.remove(url);
        }
        sweepExpired();

        // --- HTTP GET ---
        String html;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html, text/plain, application/json, */*")
                .GET()
                .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status < 200 || status >= 400) {
                return err("T-005", "HTTP " + status + " — 请求失败: " + url);
            }

            byte[] body = response.body().readNBytes(MAX_RESPONSE_BYTES);
            String contentType = response.headers().firstValue("Content-Type")
                .orElse("text/html");
            String charset = extractCharset(contentType);
            html = new String(body, charset);

        } catch (HttpTimeoutException e) {
            return err("T-004", "请求超时 (30s): " + url);
        } catch (IOException e) {
            return err("T-005", "网络请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("T-005", "请求被中断");
        }

        // --- HTML → 纯文本 ---
        String text = Jsoup.parse(html).text();

        // --- 缓存 + 截断 ---
        if (cache.size() < MAX_CACHE_SIZE) {
            cache.put(url, new CacheEntry(text));
        }
        return new Result.Ok<>(truncate(text));
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS)
            + "\n\n[truncated at " + MAX_OUTPUT_CHARS + " chars, "
            + (text.length() - MAX_OUTPUT_CHARS) + " more chars omitted]";
    }

    private static String extractCharset(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("charset=")) {
                String charset = trimmed.substring(8).trim();
                try {
                    java.nio.charset.Charset.forName(charset);
                    return charset;
                } catch (Exception ignored) {}
            }
        }
        return StandardCharsets.UTF_8.name();
    }

    private void sweepExpired() {
        for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
            }
        }
    }

    private static Result<String> err(String code, String message) {
        return new Result.Err<>(new Result.ErrorInfo(code, message));
    }

    private static class CacheEntry {
        final String content;
        final long timestamp;

        CacheEntry(String content) {
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
