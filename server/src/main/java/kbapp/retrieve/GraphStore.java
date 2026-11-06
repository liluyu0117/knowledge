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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量知识图谱（GraphRAG 基础版）：.kb/graph-{kbId}.json。
 *
 * 结构 = 实体（名称→类型/出现次数） + 关系（src/dst/描述/来源块） + 已抽取块的 hash 表（增量依据）。
 * 检索时把「问句命中的实体的关联实体名」并入术语扩展，与业务术语表同一权重路，不改变主检索逻辑。
 */
@Service
public class GraphStore {

    private static final Logger log = LoggerFactory.getLogger(GraphStore.class);
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Entity(String type, int mentions) {}
    public record Relation(String src, String dst, String desc, List<String> sources) {}

    public static class Graph {
        public Map<String, Entity> entities = new LinkedHashMap<>();
        public List<Relation> relations = new ArrayList<>();
        /** path -> 已抽取的块 hash 列表（增量抽取依据） */
        public Map<String, List<String>> chunkHashes = new LinkedHashMap<>();
    }

    private final AppPaths paths;
    private final Map<String, Graph> cache = new ConcurrentHashMap<>();

    public GraphStore(AppPaths paths) {
        this.paths = paths;
    }

    private Path file(String kbId) {
        return paths.dataDir().resolve("graph-" + kbId + ".json");
    }

    /** 拿某库的图（懒加载，损坏/不存在当空图）。 */
    public Graph graph(String kbId) {
        return cache.computeIfAbsent(kbId, this::load);
    }

    private Graph load(String kbId) {
        Graph g = new Graph();
        Path f = file(kbId);
        if (!Files.exists(f)) return g;
        try {
            Graph read = M.readValue(f.toFile(), Graph.class);
            if (read != null) {
                if (read.entities != null) g.entities = read.entities;
                if (read.relations != null) g.relations = read.relations;
                if (read.chunkHashes != null) g.chunkHashes = read.chunkHashes;
            }
        } catch (Exception e) {
            log.warn("读取 graph-{}.json 失败，按空图处理：{}", kbId, e.toString());
        }
        return g;
    }

    public synchronized void save(String kbId, Graph g) {
        try {
            Path f = file(kbId);
            Files.createDirectories(f.getParent());
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), g);
            cache.put(kbId, g);
        } catch (IOException e) {
            throw new IllegalStateException("写入 graph-" + kbId + ".json 失败", e);
        }
    }

    public synchronized void dropKb(String kbId) {
        if (kbId != null) cache.remove(kbId);
        try {
            Files.deleteIfExists(file(kbId));
        } catch (IOException ignored) { }
    }

    public int entityCount(String kbId) { return graph(kbId).entities.size(); }
    public int relationCount(String kbId) { return graph(kbId).relations.size(); }

    /** 记录一批新实体（合并 mentions）。 */
    public void addEntities(String kbId, Map<String, String> nameToType) {
        Graph g = graph(kbId);
        for (var e : nameToType.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().trim();
            if (name.isEmpty() || name.length() > 60) continue;
            Entity old = g.entities.get(name);
            g.entities.put(name, new Entity(
                    e.getValue() == null || e.getValue().isBlank() ? (old == null ? "概念" : old.type()) : e.getValue().trim(),
                    old == null ? 1 : old.mentions() + 1));
        }
    }

    /** 记录一批新关系（同 src+dst+desc 去重，sources 合并）。 */
    public void addRelations(String kbId, List<Relation> rels) {
        Graph g = graph(kbId);
        for (Relation r : rels) {
            if (r == null || r.src() == null || r.dst() == null) continue;
            String src = r.src().trim(), dst = r.dst().trim();
            if (src.isEmpty() || dst.isEmpty() || src.equals(dst) || src.length() > 60 || dst.length() > 60) continue;
            boolean found = false;
            for (Relation old : g.relations) {
                if (old.src().equals(src) && old.dst().equals(dst)
                        && old.desc().equals(r.desc() == null ? "" : r.desc())) {
                    Set<String> s = new LinkedHashSet<>(old.sources() == null ? List.of() : old.sources());
                    if (r.sources() != null) s.addAll(r.sources());
                    g.relations.set(g.relations.indexOf(old),
                            new Relation(src, dst, old.desc(), new ArrayList<>(s)));
                    found = true;
                    break;
                }
            }
            if (!found) {
                g.relations.add(new Relation(src, dst, r.desc() == null ? "" : r.desc(),
                        r.sources() == null ? List.of() : r.sources()));
            }
        }
    }

    /** 增量抽取依据：某文档已抽取的块 hash。 */
    public List<String> doneHashes(String kbId, String path) {
        return graph(kbId).chunkHashes.getOrDefault(path, List.of());
    }

    public void markHashes(String kbId, String path, List<String> hashes) {
        Graph g = graph(kbId);
        Set<String> merged = new LinkedHashSet<>(g.chunkHashes.getOrDefault(path, List.of()));
        merged.addAll(hashes);
        g.chunkHashes.put(path, new ArrayList<>(merged));
    }

    /**
     * 查询扩展：问句中出现的实体 → 把与其有直接关系的实体名作为补充检索词。
     * 例：问句含「OA」且图谱里有「OA —工单流转→ 运维平台」，则补充「运维平台」。
     */
    public List<String> expand(String kbId, String query) {
        if (query == null || query.isBlank()) return List.of();
        Graph g = graph(kbId);
        if (g.entities.isEmpty()) return List.of();
        Set<String> extra = new LinkedHashSet<>();
        for (String name : g.entities.keySet()) {
            if (!query.contains(name)) continue;
            for (Relation r : g.relations) {
                if (name.equals(r.src()) && !query.contains(r.dst())) extra.add(r.dst());
                if (name.equals(r.dst()) && !query.contains(r.src())) extra.add(r.src());
            }
        }
        return new ArrayList<>(extra);
    }
}
