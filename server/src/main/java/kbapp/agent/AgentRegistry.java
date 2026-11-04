package kbapp.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 智能体注册表（.kb/agents.json）：名称 + 聊天厂商/模型 + 绑定知识库。 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Agent(String id, String name, String provider, String model, String kbId,
                        String systemPrompt, String description, String color, Boolean withNotes) {
        /** 问答时是否同时检索「我的笔记」库；旧数据缺省视为开启。 */
        public boolean withNotesEnabled() { return withNotes == null || withNotes; }
    }

    private final AppPaths paths;
    private volatile List<Agent> agents = new ArrayList<>();

    public AgentRegistry(AppPaths paths) {
        this.paths = paths;
    }

    @PostConstruct
    public synchronized void init() {
        Path f = paths.dataDir().resolve("agents.json");
        try {
            if (Files.exists(f)) {
                agents = new ArrayList<>(List.of(M.readValue(f.toFile(), Agent[].class)));
            }
        } catch (IOException e) {
            log.warn("读取 agents.json 失败，使用空列表", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(paths.dataDir());
            M.writerWithDefaultPrettyPrinter()
                    .writeValue(paths.dataDir().resolve("agents.json").toFile(), agents);
        } catch (IOException e) {
            throw new IllegalStateException("写入 agents.json 失败", e);
        }
    }

    public synchronized List<Agent> list() { return agents; }

    public synchronized Agent byId(String id) {
        if (id == null || id.isBlank()) return null;
        return agents.stream().filter((a) -> a.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized Agent create(String name, String provider, String model, String kbId,
                                     String systemPrompt, String description, String color, Boolean withNotes) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Agent a = new Agent(id, name, provider, model, kbId, systemPrompt, description, color, withNotes);
        agents.add(a);
        save();
        return a;
    }

    public synchronized void update(String id, String name, String provider, String model, String kbId,
                                    String systemPrompt, String description, String color, Boolean withNotes) {
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).id().equals(id)) {
                agents.set(i, new Agent(id, name, provider, model, kbId, systemPrompt, description, color, withNotes));
                save();
                return;
            }
        }
    }

    public synchronized void remove(String id) {
        agents.removeIf((a) -> a.id().equals(id));
        save();
    }
}
