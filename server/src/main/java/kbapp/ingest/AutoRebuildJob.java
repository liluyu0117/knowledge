package kbapp.ingest;

import kbapp.config.ConfigService;
import kbapp.kb.KbRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时增量重扫：按配置间隔对所有知识库跑一次增量构建（无变化时只是比一遍 sig，成本很低），
 * 这样往目录里丢文件不用手动点「构建索引」。
 */
@Component
public class AutoRebuildJob {

    private static final Logger log = LoggerFactory.getLogger(AutoRebuildJob.class);

    private final ConfigService config;
    private final IndexBuilder builder;
    private final KbRegistry registry;

    private volatile long lastRun = 0;

    public AutoRebuildJob(ConfigService config, IndexBuilder builder, KbRegistry registry) {
        this.config = config;
        this.builder = builder;
        this.registry = registry;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        int minutes = config.get().getIndex().getAutoMinutes();
        if (minutes <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRun < minutes * 60_000L) return;
        if (builder.isRunning()) return;
        lastRun = now;
        for (KbRegistry.KbEntry kb : registry.list()) {
            if (builder.isRunning()) break;
            try {
                builder.start(kb.id());
                log.info("自动增量重扫：{}", kb.name());
            } catch (Exception e) {
                log.warn("自动重扫 {} 失败：{}", kb.name(), e.toString());
            }
        }
    }
}
