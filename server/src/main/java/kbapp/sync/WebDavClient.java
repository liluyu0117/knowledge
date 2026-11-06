package kbapp.sync;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小 WebDAV 客户端（坚果云等）：PUT / GET / MKCOL / PROPFIND(深度1)。
 * 只用 JDK HttpClient，零第三方依赖。
 */
@Component
public class WebDavClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private String auth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    // 坚果云对 WebDAV 有请求频率限制，连续快速请求会返回 403 —— 全局限速 + 403 自动等待重试
    private volatile long lastReqAt = 0;

    private void throttle() {
        long gap = System.currentTimeMillis() - lastReqAt;
        long min = MIN_INTERVAL.get();
        if (gap < min) {
            try { Thread.sleep(min - gap); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastReqAt = System.currentTimeMillis();
    }

    /** 临时放宽最小请求间隔（毫秒）；传 0 恢复默认 450。用于恢复这类大批量下载场景。 */
    public void wideThrottle(int minIntervalMs) {
        MIN_INTERVAL.set(minIntervalMs <= 0 ? 450 : minIntervalMs);
    }

    private final java.util.concurrent.atomic.AtomicInteger MIN_INTERVAL =
            new java.util.concurrent.atomic.AtomicInteger(450);

    private HttpResponse<byte[]> send(HttpRequest req) throws Exception {
        // 两类可重试错误：
        // - 403：云盘直链签名的频率风控，窗口约 10-20s，需要长退避；
        // - 5xx：网关/上游瞬时失败（AList 代理阿里云盘时常返回 500），短退避即可。
        for (int attempt = 0; ; attempt++) {
            throttle();
            // 每次新建 HttpClient：不复用 TCP 连接，规避坚果云关闭的 keep-alive 死连接
            HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            boolean risk403 = code == 403;
            boolean transient5xx = code >= 500 && code < 600;
            if ((!risk403 && !transient5xx) || attempt >= 4) return resp;
            long waitMs = risk403 ? 10000L * (attempt + 1) : 2000L * (1L << attempt);
            try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return resp; }
        }
    }

    private URI uri(String server, String path) {
        String base = server.endsWith("/") ? server : server + "/";
        String p = path.startsWith("/") ? path.substring(1) : path;
        return URI.create(base + p);
    }

    public void put(String server, String user, String pass, String path, byte[] data) throws Exception {
        put(server, user, pass, path, data, Duration.ofSeconds(120));
    }

    /** 大文件传输：可传更长超时（或 null 不限时）。 */
    public void put(String server, String user, String pass, String path, byte[] data, Duration timeout) throws Exception {
        var builder = HttpRequest.newBuilder(uri(server, path))
                .header("Authorization", auth(user, pass))
                .PUT(HttpRequest.BodyPublishers.ofByteArrays(chunks(data)));
        if (timeout != null) builder.timeout(timeout);
        HttpRequest req = builder.build();
        HttpResponse<byte[]> resp = send(req);
        if (resp.statusCode() >= 300) throw new IllegalStateException("WebDAV PUT 失败: HTTP " + resp.statusCode());
    }

    public byte[] get(String server, String user, String pass, String path) throws Exception {
        return get(server, user, pass, path, Duration.ofSeconds(120));
    }

    public byte[] get(String server, String user, String pass, String path, Duration timeout) throws Exception {
        var builder = HttpRequest.newBuilder(uri(server, path))
                .header("Authorization", auth(user, pass));
        if (timeout != null) builder.timeout(timeout);
        HttpRequest req = builder.GET().build();
        HttpResponse<byte[]> resp = send(req);
        // AList 等网关会把下载 302 到对象存储直链（常见为 https，与请求的 http 跨协议，
        // JDK HttpClient 即便设 followRedirects 也不跟跨协议跳转），这里手动跟随。
        int redirects = 0;
        while (resp.statusCode() >= 300 && resp.statusCode() < 400 && redirects < 5) {
            String loc = resp.headers().firstValue("Location").orElse(null);
            if (loc == null) break;
            URI next = resp.uri().resolve(loc);
            var rb = HttpRequest.newBuilder(next).GET();
            // 直链主机与网关不同时不再携带网关凭据（避免把 Basic 认证泄露给第三方存储）
            if (next.getHost() != null && next.getHost().equals(resp.uri().getHost())) {
                rb.header("Authorization", auth(user, pass));
            }
            if (timeout != null) rb.timeout(timeout);
            resp = send(rb.build());
            redirects++;
        }
        if (resp.statusCode() >= 300) throw new IllegalStateException("WebDAV GET 失败: HTTP " + resp.statusCode());
        return resp.body();
    }

    public void mkcol(String server, String user, String pass, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(server, path))
                .header("Authorization", auth(user, pass))
                .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
                .build();
        http.send(req, HttpResponse.BodyHandlers.ofByteArray()); // 405 = 已存在，忽略
    }

    public record Entry(String name, long lastModified, boolean directory) {}

    /** PROPFIND 深度1：列出 path 下的条目（不含 path 自身）。 */
    public List<Entry> propfind(String server, String user, String pass, String path) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                + "<d:displayname/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>";
        HttpRequest req = HttpRequest.newBuilder(uri(server, path))
                .header("Authorization", auth(user, pass))
                .header("Depth", "1")
                .header("Content-Type", "application/xml")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();
        throttle();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return new ArrayList<>(); // 云端目录尚不存在 = 空
        if (resp.statusCode() >= 300) throw new IllegalStateException("WebDAV PROPFIND 失败: HTTP " + resp.statusCode());
        String xml = resp.body();
        List<Entry> out = new ArrayList<>();
        String selfPath = path.endsWith("/") ? path : path + "/";
        Matcher m = Pattern.compile("<(?:D:|d:)?response>(.*?)</(?:D:|d:)?response>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            String href = tag(block, "href");
            if (href == null) continue;
            String decoded = java.net.URLDecoder.decode(href, StandardCharsets.UTF_8);
            String name = decoded.endsWith("/") ? decoded.substring(0, decoded.length() - 1) : decoded;
            int slash = name.lastIndexOf('/');
            name = slash >= 0 ? name.substring(slash + 1) : name;
            if (name.isBlank() || (selfPath.endsWith(name) && decoded.startsWith(selfPath))) continue;
            boolean isDir = block.contains("<d:collection") || block.contains("<D:collection");
            long lm = 0;
            String lmStr = tag(block, "getlastmodified");
            if (lmStr != null) {
                try {
                    lm = java.time.ZonedDateTime.parse(lmStr.trim(),
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                } catch (Exception ignored) { }
            }
            out.add(new Entry(name, lm, isDir));
        }
        return out;
    }

    /** 把 byte[] 切成 64KB 分块流式发送。 */
    public static Iterable<byte[]> chunks(byte[] data) {
        int chunk = 64 * 1024;
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < data.length; i += chunk) {
            out.add(java.util.Arrays.copyOfRange(data, i, Math.min(i + chunk, data.length)));
        }
        if (out.isEmpty()) out.add(new byte[0]);
        return out;
    }

    private String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:D:|d:)?" + name + "[^>]*>(.*?)</(?:D:|d:)?" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
