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

    public SettingsController(ConfigService config) {
        this.config = config;
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
                if (pm.get("baseUrl") != null) p.setBaseUrl(String.valueOf(pm.get("baseUrl")));
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
        if (body.get("mcp") instanceof Map<?, ?> mcp && mcp.get("enabled") instanceof Boolean b) {
            next.getMcp().setEnabled(b);
        }
        config.update(next);
        return get();
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
