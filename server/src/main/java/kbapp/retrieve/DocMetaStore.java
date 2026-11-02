package kbapp.retrieve;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kbapp.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档级元数据（标签）存储：.kb/docmeta-{kbId}.json。
 *
 * 设计要点：
 * - 标签是文档级属性，不进 Lucene 索引 —— 检索时用「允许的 path 集合」下推为
 *   TermInSetQuery 过滤，避免为打标签而全库重建索引；
 * - 每库一份 JSON，内存缓存 + 写时落盘；孤儿条目（文档已删）无害，删文件时顺手清理。
 */
@Service
public class DocMetaStore {

    private static final Logger log = LoggerFactory.getLogger(DocMetaStore.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record DocMeta(List<String> tags, String source, String updatedAt) {}

    private final AppPaths paths;
    /** kbId -> (docPath -> DocMeta) */
    private final Map<String, Map<String, DocMeta>> cache = new ConcurrentHashMap<>();

    public DocMetaStore(AppPaths paths) {
        this.paths = paths;
    }

    private Path file(String kbId) {
        return paths.dataDir().resolve("docmeta-" + kbId + ".json");
    }

    /** 拿某库的整张表（懒加载，损坏当空表处理）。 */
    private Map<String, DocMeta> table(String kbId) {
        return cache.computeIfAbsent(kbId, this::load);
    }

    private Map<String, DocMeta> load(String kbId) {
        Map<String, DocMeta> out = new LinkedHashMap<>();
        Path f = file(kbId);
        if (!Files.exists(f)) return out;
        try {
            var root = M.readTree(f.toFile());
            var docs = root.get("docs");
            if (docs != null && docs.isObject()) {
                for (var it = docs.fields(); it.hasNext(); ) {
                    var e = it.next();
                    List<String> tags = new ArrayList<>();
                    var t = e.getValue().get("tags");
                    if (t != null && t.isArray()) for (var x : t) {
                        String s = x.asText("").trim();
                        if (!s.isEmpty() && !tags.contains(s)) tags.add(s);
                    }
                    out.put(e.getKey(), new DocMeta(tags,
                            e.getValue().path("source").asText(""),
                            e.getValue().path("updatedAt").asText("")));
                }
            }
        } catch (Exception ex) {
            log.warn("读取 docmeta-{}.json 失败，按空表处理：{}", kbId, ex.toString());
        }
        return out;
    }

    private void persist(String kbId, Map<String, DocMeta> table) {
        try {
            Path f = file(kbId);
            Files.createDirectories(f.getParent());
            var root = M.createObjectNode();
            var docs = root.putObject("docs");
            for (var e : sortedCopy(table).entrySet()) {
                var o = docs.putObject(e.getKey());
                var tags = o.putArray("tags");
                for (String t : e.getValue().tags()) tags.add(t);
                o.put("source", e.getValue().source() == null ? "" : e.getValue().source());
                o.put("updatedAt", e.getValue().updatedAt() == null ? "" : e.getValue().updatedAt());
            }
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), root);
        } catch (IOException ex) {
            throw new IllegalStateException("写入 docmeta-" + kbId + ".json 失败", ex);
        }
    }

    /** 按路径排序输出，diff 友好。 */
    private Map<String, DocMeta> sortedCopy(Map<String, DocMeta> table) {
        Map<String, DocMeta> sorted = new java.util.TreeMap<>();
        sorted.putAll(table);
        return sorted;
    }

    // ───────────────────────── 查询 ─────────────────────────

    /** 某文档的标签（无记录返回空集）。 */
    public Set<String> tagsOf(String kbId, String docPath) {
        DocMeta m = table(kbId).get(docPath);
        return m == null ? Set.of() : new LinkedHashSet<>(m.tags());
    }

    /** 某文档的完整元信息（可能 null）。 */
    public DocMeta metaOf(String kbId, String docPath) {
        return table(kbId).get(docPath);
    }

    /** 整库的元信息表（管理页渲染用），返回副本。 */
    public Map<String, DocMeta> all(String kbId) {
        return new LinkedHashMap<>(table(kbId));
    }

    /** 全库出现过的标签（去重排序）。 */
    public List<String> allTags(String kbId) {
        Set<String> out = new TreeSet<>();
        for (DocMeta m : table(kbId).values()) out.addAll(m.tags());
        return new ArrayList<>(out);
    }

    /** 带任一所列标签的文档路径集合 —— 检索过滤用。 */
    public Set<String> pathsWithAnyTag(String kbId, Collection<String> tags) {
        Set<String> want = tags == null ? Set.of()
                : tags.stream().filter(t -> t != null && !t.isBlank()).map(String::trim).collect(java.util.stream.Collectors.toSet());
        Set<String> out = new LinkedHashSet<>();
        if (want.isEmpty()) return out;
        for (var e : table(kbId).entrySet()) {
            for (String t : e.getValue().tags()) {
                if (want.contains(t)) { out.add(e.getKey()); break; }
            }
        }
        return out;
    }

    // ───────────────────────── 写入 ─────────────────────────

    /** 设置（或清空）单个文档的标签；tags 为空 = 清除该条目的标签，条目若无来源也一并删除。 */
    public synchronized void setTags(String kbId, String docPath, List<String> tags, String source) {
        if (kbId == null || docPath == null || docPath.isBlank()) return;
        Map<String, DocMeta> t = new LinkedHashMap<>(table(kbId));
        List<String> clean = normalize(tags);
        if (clean.isEmpty()) {
            DocMeta old = t.get(docPath);
            if (old != null && (old.source() == null || old.source().isBlank())) {
                t.remove(docPath);
            } else if (old != null) {
                t.put(docPath, new DocMeta(List.of(), old.source(), now()));
            }
        } else {
            DocMeta old = t.get(docPath);
            t.put(docPath, new DocMeta(clean,
                    source != null && !source.isBlank() ? source : (old == null ? "" : old.source()),
                    now()));
        }
        persist(kbId, t);
        cache.put(kbId, t);
    }

    /** 批量写标签（上传时一次带上）。 */
    public synchronized void setTagsBulk(String kbId, Map<String, List<String>> tagsByPath, String source) {
        if (kbId == null || tagsByPath == null || tagsByPath.isEmpty()) return;
        Map<String, DocMeta> t = new LinkedHashMap<>(table(kbId));
        boolean changed = false;
        for (var e : tagsByPath.entrySet()) {
            List<String> clean = normalize(e.getValue());
            if (clean.isEmpty()) continue;
            DocMeta old = t.get(e.getKey());
            t.put(e.getKey(), new DocMeta(clean,
                    source != null && !source.isBlank() ? source : (old == null ? "" : old.source()),
                    now()));
            changed = true;
        }
        if (changed) {
            persist(kbId, t);
            cache.put(kbId, t);
        }
    }

    /** 文档被删除时清理条目，避免表无限膨胀。 */
    public synchronized void removeDoc(String kbId, String docPath) {
        if (kbId == null || docPath == null) return;
        Map<String, DocMeta> t = table(kbId);
        if (!t.containsKey(docPath)) return;
        Map<String, DocMeta> next = new LinkedHashMap<>(t);
        next.remove(docPath);
        persist(kbId, next);
        cache.put(kbId, next);
    }

    /** 整库删除时清缓存。 */
    public synchronized void dropKb(String kbId) {
        if (kbId != null) cache.remove(kbId);
    }

    private static List<String> normalize(List<String> tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) return out;
        for (String t : tags) {
            if (t == null) continue;
            String s = t.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out.size() > 20 ? out.subList(0, 20) : out;   // 每文档最多 20 个标签
    }

    private static String now() {
        return java.time.LocalDateTime.now().withNano(0).toString();
    }
}
