package kbapp.llm;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 chat/completions 客户端：流式（SSE delta）与非流式（连通性测试）。
 */
@Component
public class OpenAiCompatClient {

    private final ConfigService config;

    public OpenAiCompatClient(ConfigService config) {
        this.config = config;
    }

    /** 逐 delta 发射回答文本。 */
    public Flux<String> streamChat(List<ChatMessage> messages, AppConfig.Provider p, String model) {
        WebClient client = WebClient.builder()
                .baseUrl(p.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + p.getApiKey())
                .build();
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "stream", true);
        return client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<
                        org.springframework.http.codec.ServerSentEvent<String>>() {})
                .timeout(Duration.ofSeconds(180))
                .map(org.springframework.http.codec.ServerSentEvent::data)
                .filter(s -> s != null && !s.isBlank())
                .map(this::deltaOf)
                .filter(s -> !s.isEmpty());
    }

    /** 非流式完整回答（AI 生成提示词等短任务用）。 */
    public String complete(List<ChatMessage> messages, AppConfig.Provider p, String model) {
        return complete(messages, p, model, 120);
    }

    /** 非流式完整回答，可指定超时秒数（图谱抽取等长输出任务用）。 */
    public String complete(List<ChatMessage> messages, AppConfig.Provider p, String model, int timeoutSec) {
        WebClient client = WebClient.builder()
                .baseUrl(p.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + p.getApiKey())
                .build();
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages.stream().map((m) -> Map.of("role", m.role(), "content", m.content())).toList());
        Map<?, ?> resp = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(java.time.Duration.ofSeconds(timeoutSec));
        if (resp == null || !(resp.get("choices") instanceof java.util.List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> msg)) {
            throw new IllegalStateException("LLM 响应异常");
        }
        return String.valueOf(msg.get("content"));
    }

    /** 非流式 1-token 请求，用于设置页"测试连通"。 */
    public String testChat(AppConfig.Provider p) {
        WebClient client = WebClient.builder()
                .baseUrl(p.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + p.getApiKey())
                .build();
        Map<?, ?> resp = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", p.getChatModel(),
                        "messages", List.of(Map.of("role", "user", "content", "hi")),
                        "max_tokens", 1))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));
        if (resp == null) throw new IllegalStateException("无响应");
        return "ok";
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** SSE data 行 -> delta 文本；[DONE] 返回空串；解析失败的行忽略。 */
    private String deltaOf(String sseData) {
        String s = sseData.trim();
        if (s.isEmpty() || "[DONE]".equals(s)) return "";
        try {
            Map<?, ?> root = JSON.readValue(s, Map.class);
            if (!(root.get("choices") instanceof List<?> choices) || choices.isEmpty()) return "";
            if (!(choices.get(0) instanceof Map<?, ?> choice)) return "";
            if (!(choice.get("delta") instanceof Map<?, ?> delta)) return "";
            Object content = delta.get("content");
            return content == null ? "" : String.valueOf(content);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> toBody(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) out.add(Map.of("role", m.role(), "content", m.content()));
        return out;
    }
}
