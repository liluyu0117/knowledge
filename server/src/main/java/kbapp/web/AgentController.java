package kbapp.web;

import kbapp.agent.AgentRegistry;
import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.llm.OpenAiCompatClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 智能体 CRUD：名称 + 聊天厂商/模型 + 绑定知识库。 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRegistry registry;
    private final ConfigService config;
    private final OpenAiCompatClient llm;
    private final kbapp.chat.ChatStore chat;

    public AgentController(AgentRegistry registry, ConfigService config, OpenAiCompatClient llm,
                           kbapp.chat.ChatStore chat) {
        this.registry = registry;
        this.config = config;
        this.llm = llm;
        this.chat = chat;
    }

    @GetMapping
    public List<AgentRegistry.Agent> list() {
        return registry.list();
    }

    public record AgentBody(String name, String provider, String model, String kbId,
                            String systemPrompt, String description, String color, Boolean withNotes) {}

    public record GenPromptBody(String name, String description, String provider, String model) {}

    /** AI 生成系统提示词草稿。 */
    @PostMapping("/gen-prompt")
    public Map<String, Object> genPrompt(@RequestBody GenPromptBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        AppConfig cfg = config.get();
        AppConfig.Provider p = cfg.providerByName(
                body.provider() == null || body.provider().isBlank() ? cfg.getActiveChat() : body.provider());
        if (p == null || p.getApiKey().isBlank()) {
            m.put("ok", false); m.put("error", "未配置可用的聊天厂商"); return m;
        }
        String model = body.model() == null || body.model().isBlank() ? p.getChatModel() : body.model();
        String meta = "你是提示词工程师。为一个知识库问答应用的智能体撰写系统提示词。"
                + "智能体名称：" + (body.name() == null ? "未命名" : body.name()) + "。"
                + "职责描述：" + (body.description() == null || body.description().isBlank() ? "（未提供，请根据名称合理推断）" : body.description()) + "。"
                + "要求：只输出提示词正文（不要解释）；第一行定义角色与专长；"
                + "随后列出回答规则（依据知识库、标注来源、不编造、结构化输出）。中文。";
        try {
            String out = llm.complete(
                    java.util.List.of(new kbapp.llm.ChatMessage("user", meta)), p, model);
            m.put("ok", true);
            m.put("prompt", out);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return m;
    }

    @PostMapping
    public AgentRegistry.Agent create(@RequestBody AgentBody body) {
        return registry.create(body.name(), body.provider(), body.model(), body.kbId(),
                body.systemPrompt(), body.description(), body.color(), body.withNotes());
    }

    @PutMapping("/{id}")
    public AgentRegistry.Agent update(@PathVariable String id, @RequestBody AgentBody body) {
        registry.update(id, body.name(), body.provider(), body.model(), body.kbId(),
                body.systemPrompt(), body.description(), body.color(), body.withNotes());
        return registry.byId(id);
    }

    /** 删除智能体；其会话不再落到「默认助手」，而是转给剩余的第一个智能体。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        registry.remove(id);
        List<AgentRegistry.Agent> rest = registry.list();
        String fallback = rest.isEmpty() ? null : rest.get(0).id();
        int moved = chat.rebindAgent(id, fallback);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("movedSessions", moved);
        m.put("fallbackAgentId", fallback);
        return m;
    }
}
