package kbapp.web;

import kbapp.config.AppConfig;
import kbapp.config.ConfigService;
import kbapp.kb.KbRegistry;
import kbapp.retrieve.HybridSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 REST 接口（给其他系统集成用）：/api/v1/*
 * 用 X-API-Key 校验，Key 在设置页生成；未启用时一律 403。
 */
@RestController
@RequestMapping("/api/v1")
public class OpenApiController {

    private final ConfigService config;
    private final HybridSearchService search;
    private final KbRegistry registry;

    public OpenApiController(ConfigService config, HybridSearchService search, KbRegistry registry) {
        this.config = config;
        this.search = search;
        this.registry = registry;
    }

    private boolean allowed(String key) {
        AppConfig.Api api = config.get().getApi();
        return api.isEnabled() && api.getKey() != null && !api.getKey().isBlank()
                && api.getKey().equals(key);
    }

    private ResponseEntity<Map<String, Object>> denied() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", "未授权：请在设置页启用开放接口并携带 X-API-Key");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(m);
    }

    public record SearchBody(String q, String kbId, Integer topK, String folder, java.util.List<String> tags) {}

    /** GET /api/v1/search?q=&kbId=&topK=&tags= —— 只要检索结果，不要 LLM。kbId 可传 id 或库名，缺省用第一个库。 */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchGet(
            @RequestParam String q,
            @RequestParam(required = false) String kbId,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) String tags,
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        return doSearch(q, kbId, topK, folder, splitTags(tags), key);
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchPost(
            @RequestBody SearchBody body,
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        return doSearch(body.q(), body.kbId(),
                body.topK() == null ? 5 : body.topK(), body.folder(), body.tags(), key);
    }

    private ResponseEntity<Map<String, Object>> doSearch(String q, String kbId, int topK,
                                                         String folder, java.util.List<String> tags, String key) {
        if (!allowed(key)) return denied();
        if (q == null || q.isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "缺少参数 q");
            return ResponseEntity.badRequest().body(m);
        }
        HybridSearchService.Result r;
        List<Map<String, Object>> hits = new ArrayList<>();
        try {
            KbRegistry.KbEntry kb = registry.resolve(kbId);
            if (kb == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", false);
                m.put("error", "找不到知识库：" + kbId);
                m.put("hint", "GET /api/v1/kbs 可列出全部 kbId；不传 kbId 时使用第一个库");
                return ResponseEntity.badRequest().body(m);
            }
            r = search.search(q, Math.min(Math.max(topK, 1), 30), folder, null, true, kb.id(), tags);
            for (var h : r.hits()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("docPath", h.docPath());
                m.put("docTitle", h.docTitle());
                m.put("seq", h.seq());
                m.put("heading", h.heading());
                m.put("text", h.snippetHtml().replace("<mark>", "").replace("</mark>", ""));
                m.put("score", h.rerankScore() != null ? h.rerankScore() : h.rrfScore());
                hits.add(m);
            }
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "检索失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return ResponseEntity.internalServerError().body(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("query", q);
        m.put("kbId", kbId);
        if (tags != null && !tags.isEmpty()) m.put("tags", tags);
        m.put("rerankUsed", r.rerankUsed());
        m.put("results", hits);
        return ResponseEntity.ok(m);
    }

    /** 逗号/中文逗号分隔 → 去空去重。 */
    private static java.util.List<String> splitTags(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String t : s.split("[,，;；]")) {
            String v = t.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /** GET /api/v1/kbs —— 列出知识库，便于调用方知道有哪些 kbId。 */
    @GetMapping("/kbs")
    public ResponseEntity<List<Map<String, Object>>> kbs(
            @RequestHeader(value = "X-API-Key", required = false) String key) {
        if (!allowed(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbRegistry.KbEntry kb : registry.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", kb.id());
            m.put("name", kb.name());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }
}
