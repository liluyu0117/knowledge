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
                                      @RequestParam(defaultValue = "default") String kbId) {
        HybridSearchService.Result r = search.search(q, topK, folder, maxPerDoc, vector, kbId);
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
}
