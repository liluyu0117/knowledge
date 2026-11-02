package kbapp.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序客户端：Cohere 风格的 POST {baseUrl}/rerank。
 * 硅基流动 / 智谱 / Jina / Cohere 都兼容这一协议：
 * 请求 {"model","query","documents":[...],"top_n"}，响应 {"results":[{"index","relevance_score"}]}。
 * 任何异常都返回 null，由检索层回落到 RRF 顺序，不影响可用性。
 */
@Service
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final int MAX_DOCS = 50;

    private final ConfigService config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final kbapp.usage.UsageStats usage;

    public RerankClient(ConfigService config, kbapp.usage.UsageStats usage) {
        this.config = config;
        this.usage = usage;
    }

    public boolean enabled() {
        AppConfig.Rerank r = config.get().getRerank();
        return r != null && r.isEnabled()
                && r.getBaseUrl() != null && !r.getBaseUrl().isBlank()
                && r.getModel() != null && !r.getModel().isBlank();
    }

    /**
     * 给 documents 打分，返回与 documents 等长的分数数组（0~1）。
     * 失败（未配置 / 超时 / 协议不符）返回 null。
     */
    public double[] score(String query, List<String> documents, int topN) {
        return score(query, documents, topN, false);
    }

    /** ignoreEnabled=true 时忽略「启用」开关，仅按地址/模型是否配置来判断（设置页测试用）。 */
    public double[] score(String query, List<String> documents, int topN, boolean ignoreEnabled) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) return null;
        AppConfig.Rerank cfg = config.get().getRerank();
        if (cfg == null) return null;
        boolean ready = cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()
                && cfg.getModel() != null && !cfg.getModel().isBlank()
                && (ignoreEnabled || cfg.isEnabled());
        if (!ready) return null;

        List<String> docs = documents.size() > MAX_DOCS ? documents.subList(0, MAX_DOCS) : documents;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.getModel());
            body.put("query", query);
            body.put("documents", docs);
            body.put("top_n", topN > 0 ? Math.min(topN, docs.size()) : docs.size());
            String json = M.writeValueAsString(body);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(join(cfg.getBaseUrl(), "/rerank")))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                rb.header("Authorization", "Bearer " + cfg.getApiKey().trim());
            }

            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            usage.inc("rerank");
            usage.addRerankMs(System.currentTimeMillis() - t0);
            if (resp.statusCode() / 100 != 2) {
                log.warn("rerank 返回 {}：{}", resp.statusCode(), brief(resp.body()));
                return null;
            }
            return parse(resp.body(), docs.size());
        } catch (Exception e) {
            usage.inc("rerankFail");
            log.warn("rerank 调用失败，回落到 RRF 顺序：{}", e.toString());
            return null;
        }
    }

    /** 解析 results，按 index 还原成与原文等长的分数数组（缺失的给 0）。 */
    private double[] parse(String body, int size) throws Exception {
        JsonNode root = M.readTree(body);
        JsonNode results = root.has("results") ? root.get("results") : root.get("data");
        if (results == null || !results.isArray()) {
            log.warn("rerank 响应缺少 results 字段：{}", brief(body));
            return null;
        }
        double[] scores = new double[size];
        for (JsonNode it : results) {
            int idx = it.has("index") ? it.get("index").asInt(-1) : -1;
            double s = it.has("relevance_score") ? it.get("relevance_score").asDouble(-1)
                    : it.has("score") ? it.get("score").asDouble(-1) : -1;
            if (idx >= 0 && idx < size && s >= 0) scores[idx] = s;
        }
        return scores;
    }

    private String join(String baseUrl, String path) {
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    private String brief(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /** 设置页「测试连通」用：返回前若干条的分数与耗时。 */
    public Map<String, Object> selfTest(String query) {
        List<String> docs = new ArrayList<>(List.of(
                "上线流程需要经过需求评审、开发、测试、上线审批四个环节。",
                "今天天气不错，适合出门散步。"));
        long t0 = System.currentTimeMillis();
        double[] s = score(query == null || query.isBlank() ? "上线流程有哪些环节" : query, docs, docs.size(), true);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", s != null);
        m.put("tookMs", System.currentTimeMillis() - t0);
        m.put("model", config.get().getRerank().getModel());
        if (s == null) {
            m.put("error", "调用失败：请检查地址是否以 /v1 结尾、模型名与密钥是否正确（服务端日志有详细原因）");
        } else {
            m.put("scores", s);
        }
        return m;
    }
}
