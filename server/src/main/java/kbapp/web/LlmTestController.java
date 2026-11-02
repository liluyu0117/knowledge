package kbapp.web;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.llm.OpenAiCompatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** embedding 连通性测试：返回向量维度。 */
    @GetMapping("/test-embedding")
    public Map<String, Object> testEmbedding(@RequestParam(required = false) String name) {
        AppConfig cfg = config.get();
        AppConfig.Provider p = cfg.providerByName(name == null || name.isBlank() ? cfg.getActiveEmbedding() : name);
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) { m.put("ok", false); m.put("error", "厂商不存在"); return m; }
        if (p.getApiKey().isBlank() || p.getEmbeddingModel().isBlank()) {
            m.put("ok", false); m.put("error", "未配置 API Key 或 Embedding 模型"); return m;
        }
        try {
            float[] v = embedding.embedAll(List.of("连通测试"), p.getName()).get(0);
            m.put("ok", true);
            m.put("dim", v.length);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    /** 厂商连通性测试：?name= 厂商名；不带参数测当前 activeChat。 */
    @GetMapping("/test")
    public Map<String, Object> test(@RequestParam(required = false) String name) {
        AppConfig cfg = config.get();
        AppConfig.Provider p = cfg.providerByName(name == null || name.isBlank() ? cfg.getActiveChat() : name);
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) {
            m.put("ok", false);
            m.put("error", "厂商不存在");
            return m;
        }
        if (p.getApiKey().isBlank()) {
            m.put("ok", false);
            m.put("error", "未配置 API Key");
            return m;
        }
        try {
            llm.testChat(p);
            m.put("ok", true);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }
}
