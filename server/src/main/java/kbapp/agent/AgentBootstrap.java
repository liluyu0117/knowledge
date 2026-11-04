package kbapp.agent;

import kbapp.chat.ChatStore;
import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能体启动初始化：不再有「默认助手」兜底——每个会话都必须归属某个智能体。
 *
 * <p>1) 一个智能体都没有时，自动创建一个通用智能体（普通智能体，可编辑可删除），避免无法提问；
 * 2) 把历史遗留的「未绑定 / 绑定了已删除智能体」的会话改绑到首个智能体，保证进入任意会话都能回显其智能体。
 */
@Component
public class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    private final AgentRegistry registry;
    private final ChatStore chat;
    private final ConfigService config;

    public AgentBootstrap(AgentRegistry registry, ChatStore chat, ConfigService config) {
        this.registry = registry;
        this.chat = chat;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        if (registry.list().isEmpty()) {
            AppConfig cfg = config.get();
            AppConfig.Provider p = cfg.providerByName(cfg.getActiveChat());
            String model = p == null ? "" : p.getChatModel();
            AgentRegistry.Agent a = registry.create(
                    "通用助手",
                    cfg.getActiveChat() == null ? "" : cfg.getActiveChat(),
                    model == null ? "" : model,
                    "default",
                    "",
                    "通用问答智能体（未指定角色）",
                    "#6366F1", true);
            log.info("未检测到智能体，已创建通用助手 {}", a.id());
        }

        List<AgentRegistry.Agent> all = registry.list();
        if (all.isEmpty()) return;
        Set<String> valid = new LinkedHashSet<>();
        for (AgentRegistry.Agent a : all) valid.add(a.id());
        String fallback = all.get(0).id();
        int n = chat.rebindUnknownAgents(valid, fallback);
        if (n > 0) log.info("已将 {} 个未归属智能体的会话改绑到 {}", n, all.get(0).name());
    }
}
