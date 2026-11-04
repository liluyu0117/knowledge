package kbapp.llm;

import kbapp.chat.ChatStore;
import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.retrieve.HybridSearchService;
import kbapp.retrieve.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 问答编排（设计文档 8）：检索 → 组装上下文 → LLM 流式 → 落库。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int HISTORY_ROUNDS = 3;

    private final ConfigService config;
    private final HybridSearchService search;
    private final OpenAiCompatClient llm;
    private final ChatStore chat;
    private final kbapp.agent.AgentRegistry agents;
    private final kbapp.usage.UsageStats usage;

    public ChatService(ConfigService config, HybridSearchService search,
                       OpenAiCompatClient llm, ChatStore chat, kbapp.agent.AgentRegistry agents,
                       kbapp.usage.UsageStats usage) {
        this.config = config;
        this.search = search;
        this.llm = llm;
        this.chat = chat;
        this.agents = agents;
        this.usage = usage;
    }

    /** agentId 可为空：为空时用全局 activeChat + 默认知识库。tags 非空时检索只取带任一标签的文档。 */
    public Flux<AskEvent> ask(String question, String folder, String sessionId, String providerName, String agentId,
                              Integer topK, String style, java.util.List<String> tags) {
        usage.inc("ask");
        AppConfig cfg = config.get();
        String chatProvider = providerName;
        String chatModel = null;
        String kbId = "default";

        kbapp.agent.AgentRegistry.Agent agent = agents.byId(agentId);
        if (agent != null) {
            chatProvider = agent.provider();
            chatModel = agent.model();
            kbId = agent.kbId() == null || agent.kbId().isBlank() ? "default" : agent.kbId();
        }
        // 已有会话绑定优先：打开旧会话继续提问时，永远跟随会话归属的智能体（防串库）
        if (sessionId != null && !sessionId.isBlank()) {
            String bound = chat.sessionAgent(sessionId);
            if (bound != null && !bound.isBlank()) {
                kbapp.agent.AgentRegistry.Agent boundAgent = agents.byId(bound);
                if (boundAgent != null) {
                    chatProvider = boundAgent.provider();
                    chatModel = boundAgent.model();
                    kbId = boundAgent.kbId() == null || boundAgent.kbId().isBlank() ? "default" : boundAgent.kbId();
                }
            }
        }

        AppConfig.Provider provider = cfg.providerByName(
                chatProvider == null || chatProvider.isBlank() ? cfg.getActiveChat() : chatProvider);
        if (provider == null || provider.getApiKey().isBlank()) {
            return Flux.just(new AskEvent.Error("未配置可用的聊天厂商，请先在设置页配置"));
        }
        if (chatModel == null || chatModel.isBlank()) chatModel = provider.getChatModel();
        String model = chatModel;

        // 新会话记录归属智能体（不再有「默认助手」，历史空值由 AgentBootstrap 启动时改绑）
        String bindAgent = agentId;
        if (sessionId == null || sessionId.isBlank()) {
            kbapp.agent.AgentRegistry.Agent a = agents.byId(agentId);
            if (a == null) bindAgent = null;
        }
        String sid = sessionId;
        if (sid == null || sid.isBlank()) {
            sid = chat.createSession(makeTitle(question), bindAgent).id();
        } else if (agent != null) {
            // 旧会话（未绑定）里首次用智能体提问 → 补绑定，保证下次打开能回显该智能体
            String bound = chat.sessionAgent(sid);
            if (bound == null || bound.isBlank()) chat.bindAgent(sid, agent.id());
        }
        String finalSid = sid;
        chat.markAsk(finalSid, true); // 会话列表「生成中」标记

        // 检索
        List<SearchHit> hits;
        HybridSearchService.Result found;
        try {
            found = search.search(question, topK, folder, null, true, kbId, tags);
            hits = found.hits();
        } catch (Exception e) {
            log.warn("检索失败", e);
            String msg = String.valueOf(e.getMessage());
            chat.markAsk(finalSid, false);
            if (msg.contains("知识库不存在")) {
                // 智能体绑定的库被删了：告诉用户怎么修，而不是抛底层异常
                return Flux.just(new AskEvent.Error(
                        "该智能体绑定的知识库已不存在，请到「知识库」页重建，或编辑智能体改绑到其他库后再提问"));
            }
            return Flux.just(new AskEvent.Error("检索失败: " + msg));
        }

        // 无答案兜底：命中被阈值全部过滤（或本来就没命中）时不调 LLM，直接给出可操作的提示
        if (hits.isEmpty()) {
            String msg = found.dropped() > 0
                    ? "检索到 " + found.dropped() + " 个片段，但相关度都低于阈值，已忽略。\n\n"
                      + "可以换个更具体的问法，或到「调参」页把「相似度阈值」调低一些再试。"
                    : "知识库中没有检索到相关内容。\n\n"
                      + "可以确认这份文档是否已加入知识库并完成索引，或换一个更贴近文档原话的问法。";
            chat.appendMessage(finalSid, "user", question, null, null);
            chat.appendMessage(finalSid, "assistant", msg, List.of(), model);
            chat.markAsk(finalSid, false);
            return Flux.just(new AskEvent.Delta(msg), new AskEvent.Done(finalSid, msg, List.of(), model));
        }

        // 组装消息
        List<ChatMessage> messages = new ArrayList<>();
        String styleClause = RagContextBuilder.styleClause(style);
        messages.add(ChatMessage.system(agent != null
                ? RagContextBuilder.systemPrompt(agent.name(), agent.description(), agent.systemPrompt()) + styleClause
                : RagContextBuilder.SYSTEM_PROMPT + styleClause));
        String context = RagContextBuilder.buildContext(hits);
        if (!context.isEmpty()) {
            messages.add(ChatMessage.user("知识库片段：\n\n" + context));
        }
        for (ChatMessage h : chat.recentMessages(finalSid, HISTORY_ROUNDS)) {
            messages.add(new ChatMessage(h.role(), h.content()));
        }
        messages.add(ChatMessage.user(question));

        chat.appendMessage(finalSid, "user", question, null, null);

        // 来源引用提前构建，完成与中断两个路径共用
        List<Map<String, Object>> sources = new ArrayList<>();
        for (SearchHit h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docPath", h.docPath());
            m.put("docTitle", h.docTitle());
            m.put("seq", h.seq());
            m.put("heading", h.heading());
            m.put("score", h.rrfScore());
            sources.add(m);
        }

        // 流式转发
        StringBuilder full = new StringBuilder();
        Sinks.Many<AskEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        reactor.core.Disposable sub = llm.streamChat(messages, provider, model)
                .subscribe(
                        delta -> {
                            full.append(delta);
                            sink.tryEmitNext(new AskEvent.Delta(delta));
                        },
                        err -> {
                            log.warn("LLM 流式失败", err);
                            sink.tryEmitNext(new AskEvent.Error("生成失败: " + err.getMessage()));
                            sink.tryEmitComplete();
                        },
                        () -> {
                            chat.appendMessage(finalSid, "assistant", full.toString(), sources, model);
                            sink.tryEmitNext(new AskEvent.Done(finalSid, full.toString(), sources, model));
                            sink.tryEmitComplete();
                        });
        // 用户点"停止生成"：断开上游 LLM 请求，已生成的半截回答照常入库
        return sink.asFlux().doOnCancel(() -> {
            sub.dispose();
            if (full.length() > 0) {
                chat.appendMessage(finalSid, "assistant", full.toString() + "\n\n（已停止生成）", sources, model);
            }
        }).doFinally((sig) -> chat.markAsk(finalSid, false));
    }

    /** 会话标题：压掉多余空白与句末标点，超长截断，避免列表里一长串重复问句。 */
    private static String makeTitle(String question) {
        String s = String.valueOf(question).replaceAll("\\s+", " ").trim();
        s = s.replaceAll("[？?。！!，,；;：:\\s]+$", "");
        if (s.isEmpty()) return "新会话";
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }
}
