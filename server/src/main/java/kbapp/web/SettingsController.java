package kbapp.web;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ConfigService config;
    private final kbapp.retrieve.RerankClient rerankClient;
    private final kbapp.usage.UsageStats usage;

    public SettingsController(ConfigService config, kbapp.retrieve.RerankClient rerankClient,
                              kbapp.usage.UsageStats usage) {
        this.config = config;
        this.rerankClient = rerankClient;
        this.usage = usage;
    }

    @GetMapping
    public Map<String, Object> get() {
        AppConfig c = config.get();
        Map<String, Object> m = c.toMap();
        // api_key 脱敏：仅返回末 4 位
        List<Map<String, Object>> masked = new ArrayList<>();
        for (AppConfig.Provider p : c.getProviders()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName());
            pm.put("baseUrl", p.getBaseUrl());
            pm.put("apiKeyMasked", mask(p.getApiKey()));
            pm.put("hasKey", !p.getApiKey().isBlank());
            pm.put("chatModel", p.getChatModel());
            pm.put("embeddingModel", p.getEmbeddingModel());
            pm.put("embeddingDim", p.getEmbeddingDim());
            masked.add(pm);
        }
        m.put("providers", masked);
        // 重排序配置里的 apiKey 同样脱敏
        AppConfig.Rerank rr = c.getRerank();
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("enabled", rr.isEnabled());
        rm.put("baseUrl", rr.getBaseUrl());
        rm.put("model", rr.getModel());
        rm.put("candidate", rr.getCandidate());
        rm.put("topN", rr.getTopN());
        rm.put("apiKeyMasked", mask(rr.getApiKey()));
        rm.put("hasKey", rr.getApiKey() != null && !rr.getApiKey().isBlank());
        m.put("rerank", rm);
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("enabled", c.getApi().isEnabled());
        am.put("key", c.getApi().getKey());
        m.put("api", am);
        return m;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        AppConfig next = config.get().copy();
        if (body.containsKey("sourceRoot")) next.setSourceRoot(String.valueOf(body.get("sourceRoot")).trim());
        if (body.containsKey("activeChat")) next.setActiveChat(String.valueOf(body.get("activeChat")));
        if (body.containsKey("activeEmbedding")) next.setActiveEmbedding(String.valueOf(body.get("activeEmbedding")));
        if (body.get("rag") instanceof Map<?, ?> rag) {
            Map<?, ?> r = rag;
            if (r.get("topK") != null) next.getRag().setTopK(((Number) r.get("topK")).intValue());
            if (r.get("candidate") != null) next.getRag().setCandidate(((Number) r.get("candidate")).intValue());
            if (r.get("rrfK") != null) next.getRag().setRrfK(((Number) r.get("rrfK")).intValue());
            if (r.get("maxPerDoc") != null) next.getRag().setMaxPerDoc(((Number) r.get("maxPerDoc")).intValue());
            if (r.get("analyzer") != null) next.getRag().setAnalyzer(String.valueOf(r.get("analyzer")));
            if (r.get("minRerankScore") instanceof Number n) next.getRag().setMinRerankScore(n.doubleValue());
            if (r.get("minRelScore") instanceof Number n) next.getRag().setMinRelScore(n.doubleValue());
        }
        if (body.get("providers") instanceof List<?> list) {
            List<AppConfig.Provider> merged = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> pm)) continue;
                String newName = pm.get("name") == null ? "" : String.valueOf(pm.get("name")).trim();
                String oldName = pm.get("oldName") == null ? "" : String.valueOf(pm.get("oldName")).trim();
                // 支持改名：优先按 oldName 找旧配置迁移（保留密钥），否则按新名找
                AppConfig.Provider p = null;
                if (!oldName.isBlank() && !oldName.equals(newName)) {
                    p = next.providerByName(oldName);
                }
                if (p == null) p = next.providerByName(newName);
                boolean isNew = p == null;
                if (isNew) p = new AppConfig.Provider();
                if (!newName.isBlank()) p.setName(newName);
                if (pm.get("baseUrl") != null) p.setBaseUrl(normBase(String.valueOf(pm.get("baseUrl"))));
                // key 为空或脱敏形式时不覆盖已存的真实 key
                Object key = pm.get("apiKey");
                if (key != null && !String.valueOf(key).isBlank()
                        && !String.valueOf(key).contains("****")) {
                    p.setApiKey(String.valueOf(key).trim());
                }
                if (pm.get("chatModel") != null) p.setChatModel(String.valueOf(pm.get("chatModel")));
                if (pm.get("embeddingModel") != null) p.setEmbeddingModel(String.valueOf(pm.get("embeddingModel")));
                if (pm.get("embeddingDim") instanceof Number n) p.setEmbeddingDim(n.intValue());
                merged.add(p);
                // 改名后同步 active 引用
                if (!oldName.isBlank() && !oldName.equals(newName)) {
                    if (oldName.equals(next.getActiveChat())) next.setActiveChat(newName);
                    if (oldName.equals(next.getActiveEmbedding())) next.setActiveEmbedding(newName);
                }
            }
            next.setProviders(merged);
            // active 指向的厂商被删除时回落到第一个
            if (next.providerByName(next.getActiveChat()) == null && !merged.isEmpty()) {
                next.setActiveChat(merged.get(0).getName());
            }
            if (next.providerByName(next.getActiveEmbedding()) == null && !merged.isEmpty()) {
                next.setActiveEmbedding(merged.get(0).getName());
            }
        }
        if (body.get("rerank") instanceof Map<?, ?> rk) {
            AppConfig.Rerank r = next.getRerank();
            if (rk.get("enabled") instanceof Boolean b) r.setEnabled(b);
            if (rk.get("baseUrl") != null) r.setBaseUrl(normBase(String.valueOf(rk.get("baseUrl"))));
            if (rk.get("model") != null) r.setModel(String.valueOf(rk.get("model")).trim());
            if (rk.get("candidate") instanceof Number n) r.setCandidate(n.intValue());
            if (rk.get("topN") instanceof Number n) r.setTopN(n.intValue());
            Object key = rk.get("apiKey");
            if (key != null && !String.valueOf(key).isBlank() && !String.valueOf(key).contains("****")) {
                r.setApiKey(String.valueOf(key).trim());
            }
        }
        if (body.get("index") instanceof Map<?, ?> idx) {
            if (idx.get("autoMinutes") instanceof Number n) next.getIndex().setAutoMinutes(n.intValue());
            if (idx.get("chunkChars") instanceof Number n) next.getIndex().setChunkChars(n.intValue());
            if (idx.get("chunkOverlapChars") instanceof Number n) next.getIndex().setChunkOverlapChars(n.intValue());
        }
        if (body.get("api") instanceof Map<?, ?> api) {
            if (api.get("enabled") instanceof Boolean b) next.getApi().setEnabled(b);
            Object k = api.get("key");
            if (k != null && !String.valueOf(k).isBlank()) next.getApi().setKey(String.valueOf(k).trim());
            if (Boolean.TRUE.equals(api.get("regenerate"))) next.getApi().setKey(randomKey());
        }
        if (body.get("mcp") instanceof Map<?, ?> mcp && mcp.get("enabled") instanceof Boolean b) {
            next.getMcp().setEnabled(b);
        }
        config.update(next);
        return get();
    }

    /** 重排序连通性测试：设置页「测试」按钮用。 */
    @org.springframework.web.bind.annotation.PostMapping("/rerank/test")
    public Map<String, Object> testRerank(@RequestBody(required = false) Map<String, Object> body) {
        String q = body == null || body.get("query") == null ? null : String.valueOf(body.get("query"));
        return rerankClient.selfTest(q);
    }

    /** 本机用量快照：问答/检索/重排次数与重排平均耗时。 */
    @GetMapping("/usage")
    public Map<String, Object> usage() {
        return usage.snapshot();
    }

    /** 去掉末尾斜杠：避免客户端拼出 //chat/completions（"https://" 本身不算）。 */
    private static String normBase(String url) {
        String v = url == null ? "" : url.trim();
        while (v.length() > 1 && v.endsWith("/") && !v.endsWith("://")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private String randomKey() {
        byte[] buf = new byte[24];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
