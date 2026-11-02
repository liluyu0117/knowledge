package kbapp.llm;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 /v1/embeddings 客户端（智谱 embedding-3、通义 text-embedding 等均适用）。
 * 每次调用按当前配置动态解析厂商与 key。
 */
@Component
public class OpenAiCompatEmbedding implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatEmbedding.class);
    private static final int BATCH = 16;

    private final ConfigService config;

    public OpenAiCompatEmbedding(ConfigService config) {
        this.config = config;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return embedAll(texts, (String) null);
    }

    /** 指定厂商名的 embedding（连通性测试用）；null 走当前 activeEmbedding。 */
    public List<float[]> embedAll(List<String> texts, String providerName) {
        return embedAll(texts, resolveProvider(providerName));
    }

    /** 用给定的（可能尚未保存的）厂商配置跑 embedding，设置页「测试向量」用。 */
    public List<float[]> embedAll(List<String> texts, AppConfig.Provider p) {
        WebClient client = WebClient.builder()
                .baseUrl(p.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + p.getApiKey())
                .build();
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH, texts.size()));
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", p.getEmbeddingModel());
            body.put("input", batch);
            // 部分模型（doubao-embedding-vision 等）支持 dimensions 降维；>0 才传
            if (p.getEmbeddingDim() > 0) body.put("dimensions", p.getEmbeddingDim());
            Map<?, ?> resp = client.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(60));
            if (resp == null || !(resp.get("data") instanceof List<?> data) || data.size() != batch.size()) {
                throw new IllegalStateException("embedding 响应异常");
            }
            Object[] arr = data.toArray();
            for (int j = 0; j < arr.length; j++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) arr[j];
                if (!(item.get("embedding") instanceof List<?> nums)) {
                    throw new IllegalStateException("embedding 缺失");
                }
                float[] v = new float[nums.size()];
                for (int k = 0; k < nums.size(); k++) v[k] = ((Number) nums.get(k)).floatValue();
                // 服务端未按 dimensions 降维时截断兜底（Lucene 上限 1024）
                if (p.getEmbeddingDim() > 0 && v.length > p.getEmbeddingDim()) {
                    v = java.util.Arrays.copyOf(v, p.getEmbeddingDim());
                }
                // 批量接口按传入顺序返回，但仍按 index 字段对齐以防厂商乱序
                int idx = item.get("index") instanceof Number n ? n.intValue() : j;
                while (out.size() <= i + idx) out.add(null);
                out.set(i + idx, v);
            }
        }
        return out;
    }

    private AppConfig.Provider resolveProvider(String name) {
        AppConfig c = config.get();
        String use = (name == null || name.isBlank()) ? c.getActiveEmbedding() : name;
        AppConfig.Provider p = c.providerByName(use);
        if (p == null || p.getApiKey().isBlank() || p.getEmbeddingModel().isBlank()) {
            throw new IllegalStateException("未配置可用的 embedding 厂商（activeEmbedding=" + c.getActiveEmbedding() + "）");
        }
        return p;
    }
}
