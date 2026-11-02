package kbapp.web;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.llm.OpenAiCompatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商连通性测试。
 * 支持两种调用：GET ?name=（测已保存的配置）与 POST 表单值（测还没保存的改动/新增项）。
 * 未传的字段回落到同名已保存配置，所以「改名后立刻测」「新建还没保存就测」都能通过。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmTestController {

    private final ConfigService config;
    private final OpenAiCompatClient llm;
    private final kbapp.llm.OpenAiCompatEmbedding embedding;

    public LlmTestController(ConfigService config, OpenAiCompatClient llm,
                             kbapp.llm.OpenAiCompatEmbedding embedding) {
        this.config = config;
        this.llm = llm;
        this.embedding = embedding;
    }

    /** 兼容旧调用：按已保存的厂商配置测聊天。 */
    @GetMapping("/test")
    public Map<String, Object> testByName(@RequestParam(required = false) String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null) body.put("name", name);
        return test(body);
    }

    /** 兼容旧调用：按已保存的厂商配置测向量。 */
    @GetMapping("/test-embedding")
    public Map<String, Object> testEmbeddingByName(@RequestParam(required = false) String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null) body.put("name", name);
        return testEmbedding(body);
    }

    /** 测聊天连通：body 里带上表单当前值即可，不必先保存。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        AppConfig.Provider saved = savedByName(b.get("name"));
        String baseUrl = pick(b, "baseUrl", saved == null ? "" : saved.getBaseUrl());
        String model = pick(b, "chatModel", saved == null ? "" : saved.getChatModel());
        String key = pickKey(b, saved);

        Map<String, Object> m = new LinkedHashMap<>();
        if (baseUrl.isBlank()) return fail(m, "还没填 Base URL");
        if (model.isBlank()) return fail(m, "还没填聊天模型");
        if (key.isBlank()) return fail(m, "还没填 API Key");

        AppConfig.Provider p = new AppConfig.Provider();
        p.setName("inline-test");
        p.setBaseUrl(normBase(baseUrl));
        p.setChatModel(model);
        p.setApiKey(key);

        long t0 = System.currentTimeMillis();
        try {
            llm.testChat(p);
            m.put("ok", true);
            m.put("tookMs", System.currentTimeMillis() - t0);
            m.put("model", model);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", brief(e));
        }
        return m;
    }

    /** 测向量：返回实际维度，顺便让用户知道该填多少。 */
    @PostMapping("/test-embedding")
    public Map<String, Object> testEmbedding(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        AppConfig.Provider saved = savedByName(b.get("name"));
        String baseUrl = pick(b, "baseUrl", saved == null ? "" : saved.getBaseUrl());
        String model = pick(b, "embeddingModel", saved == null ? "" : saved.getEmbeddingModel());
        int dim = intOf(b.get("embeddingDim"), saved == null ? 1024 : saved.getEmbeddingDim());
        String key = pickKey(b, saved);

        Map<String, Object> m = new LinkedHashMap<>();
        if (baseUrl.isBlank()) return fail(m, "还没填 Base URL");
        if (model.isBlank()) return fail(m, "还没填 Embedding 模型");
        if (key.isBlank()) return fail(m, "还没填 API Key");

        AppConfig.Provider p = new AppConfig.Provider();
        p.setName("inline-test");
        p.setBaseUrl(normBase(baseUrl));
        p.setEmbeddingModel(model);
        p.setEmbeddingDim(dim);
        p.setApiKey(key);

        long t0 = System.currentTimeMillis();
        try {
            float[] v = embedding.embedAll(List.of("连通测试"), p).get(0);
            m.put("ok", true);
            m.put("dim", v.length);
            m.put("tookMs", System.currentTimeMillis() - t0);
            m.put("model", model);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", brief(e));
        }
        return m;
    }

    // ── 内部工具 ──

    private AppConfig.Provider savedByName(Object nameObj) {
        String n = nameObj == null ? "" : String.valueOf(nameObj).trim();
        return n.isBlank() ? null : config.get().providerByName(n);
    }

    /** body 里有这个键就用 body（允许用户清空），没有才回落到已保存值。 */
    private static String pick(Map<String, Object> b, String k, String fallback) {
        if (!b.containsKey(k)) return fallback == null ? "" : fallback.trim();
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 空值或脱敏占位（****abcd）都视为「没改」，回落到已保存的 key。 */
    private static String pickKey(Map<String, Object> b, AppConfig.Provider saved) {
        Object v = b.get("apiKey");
        String k = v == null ? "" : String.valueOf(v).trim();
        if (k.isBlank() || k.contains("****")) return saved == null ? "" : saved.getApiKey();
        return k;
    }

    private static int intOf(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignored) { }
        }
        return fallback;
    }

    /** 去掉末尾斜杠，避免拼出 //chat/completions（"https://" 这种不算）。 */
    static String normBase(String url) {
        String v = url == null ? "" : url.trim();
        while (v.length() > 1 && v.endsWith("/") && !v.endsWith("://")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static Map<String, Object> fail(Map<String, Object> m, String error) {
        m.put("ok", false);
        m.put("error", error);
        return m;
    }

    /** 底层异常信息常带一大段响应体，截断到一眼能读。 */
    private static String brief(Exception e) {
        String s = e.getMessage() == null ? e.toString() : e.getMessage();
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }
}
