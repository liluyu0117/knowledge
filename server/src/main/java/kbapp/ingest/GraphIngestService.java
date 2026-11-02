package kbapp.ingest;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.kb.KbRegistry;
import kbapp.llm.ChatMessage;
import kbapp.llm.OpenAiCompatClient;
import kbapp.retrieve.GraphStore;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GraphRAG 抽取管道：后台对每个文档的块批量调 LLM 抽「实体 + 关系」，存 GraphStore。
 *
 * - 增量：按块内容 hash 记录已抽取的块，重建索引后只抽新块；
 * - 可取消：抽完当前一批后停止；
 * - 一次失败不中断整轮（该批记为失败继续下一批）。
 */
@Service
public class GraphIngestService {

    private static final Logger log = LoggerFactory.getLogger(GraphIngestService.class);
    /** 每批拼多少个块给 LLM（块 ~500 字，4 块 ≈ 2000 字输入，太大响应会超时）。 */
    private static final int BATCH_BLOCKS = 4;
    private static final int LLM_TIMEOUT_SEC = 240;
    private static final int MAX_DOCS = 500;

    private final ConfigService config;
    private final KbRegistry registry;
    private final GraphStore graph;
    private final OpenAiCompatClient llm;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-ingest");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelled = false;

    public GraphIngestService(ConfigService config, KbRegistry registry, GraphStore graph, OpenAiCompatClient llm) {
        this.config = config;
        this.registry = registry;
        this.graph = graph;
        this.llm = llm;
    }

    public static class Progress {
        public String phase = "idle";   // idle | running | done | error | cancelled
        public String kbId = "";
        public int docsTotal;
        public int docsDone;
        public int batchFailed;
        public int entityCount;
        public int relationCount;
        public String error = "";
    }

    private volatile Progress prog = new Progress();

    public Progress status() {
        return prog;
    }

    public boolean start(String kbId) {
        KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null) return false;
        if (!running.compareAndSet(false, true)) return false;
        cancelled = false;
        AppConfig cfg = config.get();
        AppConfig.Provider p = cfg.providerByName(cfg.getActiveChat());
        if (p == null || p.getApiKey().isBlank()) {
            running.set(false);
            prog = new Progress();
            prog.phase = "error";
            prog.error = "未配置可用的聊天厂商，无法抽取实体";
            return true;
        }
        Progress pr = new Progress();
        pr.phase = "running";
        pr.kbId = kb.id();
        pr.entityCount = graph.entityCount(kb.id());
        pr.relationCount = graph.relationCount(kb.id());
        prog = pr;
        worker.submit(() -> {
            try {
                run(cfg, p, kb, pr);
            } catch (Throwable e) {
                log.error("图谱抽取失败", e);
                pr.phase = "error";
                pr.error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public boolean cancel() {
        if (!running.get()) return false;
        cancelled = true;
        return true;
    }

    private void run(AppConfig cfg, AppConfig.Provider p, KbRegistry.KbEntry kb, Progress pr) {
        Path dir = registry.indexDir(kb);
        if (dir == null || !Files.exists(dir)) {
            pr.phase = "error";
            pr.error = "该知识库还没有索引，先构建索引";
            return;
        }
        String model = p.getChatModel();
        // 文档 -> 块列表（只读 SEQ/BODY/CHUNK_HASH，不加载向量）
        Map<String, List<Object[]>> docs = new LinkedHashMap<>();
        try (Directory d = FSDirectory.open(dir);
             DirectoryReader reader = DirectoryReader.open(d)) {
            for (var lc : reader.leaves()) {
                var lr = lc.reader();
                var live = lr.getLiveDocs();
                for (int i = 0; i < lr.maxDoc(); i++) {
                    if (live != null && !live.get(i)) continue;
                    var doc = lr.storedFields().document(i, java.util.Set.of(
                            IndexSchema.DOC_PATH, IndexSchema.SEQ, IndexSchema.BODY, IndexSchema.CHUNK_HASH));
                    String path = IndexSchema.getString(doc, IndexSchema.DOC_PATH);
                    if (path.isEmpty()) continue;
                    docs.computeIfAbsent(path, k -> new ArrayList<>()).add(new Object[]{
                            IndexSchema.getInt(doc, IndexSchema.SEQ),
                            IndexSchema.getString(doc, IndexSchema.BODY),
                            IndexSchema.getString(doc, IndexSchema.CHUNK_HASH)});
                }
            }
        } catch (Exception e) {
            pr.phase = "error";
            pr.error = "读取索引失败：" + e.getMessage();
            return;
        }

        pr.docsTotal = Math.min(docs.size(), MAX_DOCS);
        int docIdx = 0;
        for (var entry : docs.entrySet()) {
            if (cancelled) { pr.phase = "cancelled"; return; }
            if (docIdx++ >= MAX_DOCS) break;
            String path = entry.getKey();
            List<Object[]> chunks = entry.getValue();

            // 增量：过滤掉已抽取的块
            List<Object[]> todo = new ArrayList<>();
            for (Object[] c : chunks) {
                if (!graph.doneHashes(kb.id(), path).contains(String.valueOf(c[2]))) todo.add(c);
            }
            if (todo.isEmpty()) { pr.docsDone++; continue; }

            for (int from = 0; from < todo.size(); from += BATCH_BLOCKS) {
                if (cancelled) { pr.phase = "cancelled"; return; }
                List<Object[]> batch = todo.subList(from, Math.min(todo.size(), from + BATCH_BLOCKS));
                try {
                    extractBatch(cfg, p, model, kb.id(), path, batch);
                } catch (Exception e) {
                    pr.batchFailed++;
                    log.info("图谱抽取一批失败 {}: {}", path, e.toString());
                }
            }
            pr.docsDone++;
            pr.entityCount = graph.entityCount(kb.id());
            pr.relationCount = graph.relationCount(kb.id());
        }
        pr.phase = cancelled ? "cancelled" : "done";
        pr.entityCount = graph.entityCount(kb.id());
        pr.relationCount = graph.relationCount(kb.id());
        log.info("图谱抽取完成: kb={} 实体 {} 关系 {} 失败批 {}", kb.id(), pr.entityCount, pr.relationCount, pr.batchFailed);
    }

    /** 抽取一批块：拼 prompt → LLM → 解析 JSON → 合并入 GraphStore → 记 hash。 */
    private void extractBatch(AppConfig cfg, AppConfig.Provider p, String model,
                              String kbId, String path, List<Object[]> batch) {
        StringBuilder sb = new StringBuilder();
        List<String> hashes = new ArrayList<>();
        for (Object[] c : batch) {
            int seq = (Integer) c[0];
            String body = String.valueOf(c[1]);
            hashes.add(String.valueOf(c[2]));
            sb.append("【").append(path).append("#").append(seq).append("】\n");
            sb.append(body.length() > 800 ? body.substring(0, 800) : body).append("\n\n");
        }
        String prompt = """
                从下面的企业知识库片段中抽取实体与关系，只依据文本，不要编造。

                输出严格的 JSON（不要 markdown 代码块）：
                {"entities":[{"name":"实体名","type":"系统|流程|角色|规则|概念|文档"}],
                 "relations":[{"src":"实体名","dst":"实体名","desc":"一句话关系描述"}]}

                要求：
                - 实体名用文本中的原词（系统名、流程名、岗位、平台、文档名等专有名词）；
                - 关系必须是文本明确表达的（如"XX流程 包含 XX环节"），泛泛的"相关"不要；
                - 没有可抽取内容时输出 {"entities":[],"relations":[]}。

                文本片段：
                """ + sb;

        String resp = llm.complete(List.of(new ChatMessage("user", prompt)), p, model, LLM_TIMEOUT_SEC);
        parseAndMerge(kbId, path, resp, hashes);
    }

    private void parseAndMerge(String kbId, String path, String resp, List<String> hashes) {
        if (resp == null || resp.isBlank()) return;
        String json = resp.strip();
        json = json.replaceAll("^```(json)?", "").replaceAll("```$", "").strip();
        int s = json.indexOf('{');
        int e = json.lastIndexOf('}');
        if (s < 0 || e <= s) return;
        json = json.substring(s, e + 1);
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            Map<String, String> ents = new HashMap<>();
            if (root.get("entities") != null && root.get("entities").isArray()) {
                for (var n : root.get("entities")) {
                    String name = n.path("name").asText("").trim();
                    if (!name.isEmpty()) ents.put(name, n.path("type").asText("概念"));
                }
            }
            List<GraphStore.Relation> rels = new ArrayList<>();
            if (root.get("relations") != null && root.get("relations").isArray()) {
                List<String> sources = new ArrayList<>();
                for (var h : hashes) {
                    // hash 反不出 seq，来源标注到文档级即可
                }
                for (var n : root.get("relations")) {
                    rels.add(new GraphStore.Relation(
                            n.path("src").asText("").trim(),
                            n.path("dst").asText("").trim(),
                            n.path("desc").asText("").trim(),
                            List.of(path)));
                }
            }
            graph.addEntities(kbId, ents);
            graph.addRelations(kbId, rels);
            graph.markHashes(kbId, path, hashes);
            graph.save(kbId, graph.graph(kbId));
        } catch (Exception ex) {
            throw new IllegalStateException("解析抽取结果失败: " + ex.getMessage(), ex);
        }
    }
}
