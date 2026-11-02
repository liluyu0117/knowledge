package kbapp.web;

import kbapp.ingest.GraphIngestService;
import kbapp.kb.KbRegistry;
import kbapp.retrieve.GraphStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 知识图谱（GraphRAG 基础版）：状态 / 后台抽取 / 查看 / 清空。 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphStore graph;
    private final GraphIngestService ingest;
    private final KbRegistry registry;

    public GraphController(GraphStore graph, GraphIngestService ingest, KbRegistry registry) {
        this.graph = graph;
        this.ingest = ingest;
        this.registry = registry;
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String kbId) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        m.put("ok", true);
        GraphIngestService.Progress p = ingest.status();
        // 只在查询的就是正在跑的库时透出进度，否则显示 idle
        boolean running = "running".equals(p.phase) || "awaiting".equals(p.phase);
        Map<String, Object> prog = new LinkedHashMap<>();
        prog.put("phase", running && kb.id().equals(p.kbId) ? p.phase
                : (running ? "running-other" : ("done".equals(p.phase) ? "done" : "idle")));
        prog.put("docsDone", p.docsDone);
        prog.put("docsTotal", p.docsTotal);
        prog.put("batchFailed", p.batchFailed);
        prog.put("error", p.error);
        m.put("progress", prog);
        m.put("entityCount", graph.entityCount(kb.id()));
        m.put("relationCount", graph.relationCount(kb.id()));
        return m;
    }

    public record BuildBody(String kbId) {}

    /** 后台启动实体/关系抽取（增量：只抽未处理过的块）。 */
    @PostMapping("/build")
    public Map<String, Object> build(@RequestBody BuildBody body) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean started = ingest.start(body == null ? "" : body.kbId);
        m.put("started", started);
        if (!started) m.put("error", "已在运行或知识库不存在");
        return m;
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cancelled", ingest.cancel());
        return m;
    }

    /** 图谱内容（面板展示用），entities/relations 各限前 200 条。 */
    @GetMapping("/view")
    public Map<String, Object> view(@RequestParam String kbId) {
        Map<String, Object> m = new LinkedHashMap<>();
        KbRegistry.KbEntry kb = registry.byId(kbId);
        if (kb == null) { m.put("ok", false); m.put("error", "知识库不存在"); return m; }
        GraphStore.Graph g = graph.graph(kb.id());
        m.put("ok", true);
        List<Map<String, Object>> ents = new ArrayList<>();
        int i = 0;
        for (var e : g.entities.entrySet()) {
            if (i++ >= 200) break;
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("name", e.getKey());
            em.put("type", e.getValue().type());
            em.put("mentions", e.getValue().mentions());
            ents.add(em);
        }
        List<Map<String, Object>> rels = new ArrayList<>();
        int j = 0;
        for (GraphStore.Relation r : g.relations) {
            if (j++ >= 200) break;
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("src", r.src());
            rm.put("dst", r.dst());
            rm.put("desc", r.desc());
            rels.add(rm);
        }
        m.put("entities", ents);
        m.put("relations", rels);
        m.put("entityTotal", g.entities.size());
        m.put("relationTotal", g.relations.size());
        return m;
    }

    /** 清空图谱（重抽前用）。 */
    @DeleteMapping
    public Map<String, Object> clear(@RequestParam String kbId) {
        Map<String, Object> m = new LinkedHashMap<>();
        graph.dropKb(kbId);
        m.put("ok", true);
        return m;
    }
}
