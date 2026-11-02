package kbapp.retrieve;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 块级屏蔽表（.kb/chunk-flags.json）：把解析错、内容过时或无关的块排除在检索之外，
 * 不重建索引也能立即生效。
 */
@Service
public class ChunkFlagStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkFlagStore.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final AppPaths paths;
    /** kbId -> ("path#seq" -> 备注/时间) */
    private volatile Map<String, Map<String, String>> flags = new LinkedHashMap<>();

    public ChunkFlagStore(AppPaths paths) {
        this.paths = paths;
    }

    private Path file() {
        return paths.dataDir().resolve("chunk-flags.json");
    }

    private static String key(String docPath, int seq) {
        return docPath + "#" + seq;
    }

    @PostConstruct
    public synchronized void init() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) return;
            Map<String, Map<String, String>> loaded = M.readValue(f.toFile(),
                    M.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Map.class));
            flags = loaded == null ? new LinkedHashMap<>() : loaded;
        } catch (Exception e) {
            log.warn("读取 chunk-flags.json 失败：{}", e.toString());
        }
    }

    private void persist() {
        try {
            M.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), flags);
        } catch (IOException e) {
            throw new IllegalStateException("写入 chunk-flags.json 失败", e);
        }
    }

    public boolean isDisabled(String kbId, String docPath, int seq) {
        Map<String, String> m = flags.get(kbId);
        return m != null && m.containsKey(key(docPath, seq));
    }

    public synchronized void set(String kbId, String docPath, int seq, boolean disabled, String note) {
        Map<String, String> m = flags.computeIfAbsent(kbId, k -> new LinkedHashMap<>());
        String k = key(docPath, seq);
        if (disabled) {
            m.put(k, note == null || note.isBlank()
                    ? java.time.LocalDateTime.now().withNano(0).toString() : note.trim());
        } else {
            m.remove(k);
        }
        if (m.isEmpty()) flags.remove(kbId);
        persist();
    }

    /** 列出某个库被屏蔽的块。 */
    public List<Map<String, Object>> list(String kbId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> m = flags.get(kbId);
        if (m == null) return out;
        for (Map.Entry<String, String> e : m.entrySet()) {
            int i = e.getKey().lastIndexOf('#');
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docPath", i > 0 ? e.getKey().substring(0, i) : e.getKey());
            try {
                row.put("seq", Integer.parseInt(e.getKey().substring(i + 1)));
            } catch (Exception ex) {
                row.put("seq", -1);
            }
            row.put("note", e.getValue());
            out.add(row);
        }
        return out;
    }
}
