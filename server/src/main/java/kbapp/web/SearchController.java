package kbapp.web;

import kbapp.retrieve.HybridSearchService;
import kbapp.retrieve.SearchHit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final HybridSearchService search;

    public SearchController(HybridSearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q,
                                      @RequestParam(required = false) Integer topK,
                                      @RequestParam(required = false) String folder,
                                      @RequestParam(required = false) Integer maxPerDoc,
                                      @RequestParam(defaultValue = "true") boolean vector,
                                      @RequestParam(defaultValue = "default") String kbId,
                                      @RequestParam(required = false) String tags) {
        HybridSearchService.Result r = search.search(q, topK, folder, maxPerDoc, vector, kbId, splitTags(tags));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", q);
        m.put("total", r.hits().size());
        m.put("candidatesKw", r.candidatesKw());
        m.put("candidatesVec", r.candidatesVec());
        m.put("vectorUsed", r.vectorUsed());
        m.put("rerankUsed", r.rerankUsed());
        m.put("dropped", r.dropped());
        m.put("expanded", r.expanded());
        m.put("results", r.hits());
        return m;
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
}
