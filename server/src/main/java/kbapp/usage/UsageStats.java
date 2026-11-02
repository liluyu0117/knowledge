package kbapp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量用量统计：问答次数、检索次数、重排调用与耗时。
 * 只做本机自用口径，不做精确计费；数据落 .kb/usage.json，重启不清零。
 */
@Service
public class UsageStats {

    private static final Logger log = LoggerFactory.getLogger(UsageStats.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final AppPaths paths;
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private volatile long rerankMsSum = 0;
    private volatile String day = java.time.LocalDate.now().toString();
    private volatile boolean dirty = false;

    public UsageStats(AppPaths paths) {
        this.paths = paths;
    }

    private Path file() {
        return paths.dataDir().resolve("usage.json");
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Map<?, ?> m = M.readValue(f.toFile(), Map.class);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Number n) counters.put(String.valueOf(e.getKey()), n.longValue());
            }
        } catch (Exception e) {
            log.warn("读取 usage.json 失败：{}", e.toString());
        }
    }

    /** 计数：key 形如 ask / search / rerank / rerankFail。 */
    public synchronized void inc(String key) {
        rollover();
        counters.merge(key, 1L, Long::sum);
        counters.merge("total", 1L, Long::sum);
        dirty = true;
        if (counters.getOrDefault("total", 0L) % 20 == 0) flush();
    }

    public synchronized void addRerankMs(long ms) {
        rerankMsSum += Math.max(0, ms);
        dirty = true;
    }

    private void rollover() {
        String today = java.time.LocalDate.now().toString();
        if (!today.equals(day)) {
            // 换天：把当日计数归零（历史累计保留在 total 里）
            day = today;
            counters.keySet().removeIf(k -> k.startsWith("today."));
        }
    }

    public synchronized Map<String, Object> snapshot() {
        rollover();
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : counters.entrySet()) m.put(e.getKey(), e.getValue());
        long rerank = counters.getOrDefault("rerank", 0L);
        m.put("rerankAvgMs", rerank > 0 ? Math.round((double) rerankMsSum / rerank) : 0);
        m.put("since", day);
        return m;
    }

    public synchronized void flush() {
        if (!dirty) return;
        try {
            M.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), counters);
            dirty = false;
        } catch (Exception e) {
            log.warn("写入 usage.json 失败：{}", e.toString());
        }
    }
}
